(ns com.example.app-test
  "End-to-end tests that run the real client (login, routing chart, forms, reports, renderers) against the real
   Ring handler, Pathom parser and an in-memory Datomic, all in one JVM. No browser and no HTTP server: the client's
   remote calls the Ring handler directly, carrying cookies between requests like a browser would."
  (:require
    [clojure.test :refer [use-fixtures]]
    [com.example.client :as client]
    [com.example.components.auth :as auth]
    [com.example.components.database :refer [datomic-connections]]
    [com.example.components.parser :as parser]
    [com.example.components.ring-middleware :as ring]
    [com.example.components.server :as server]
    [com.example.model.account :as account]
    [com.example.session :as session]
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.example.ui.root :refer [Root]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lr]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [datomic.client.api :as d]
    [fulcro-spec.core :refer [specification behavior assertions =>]]
    [mount.core :as mount]))

(defn with-backend
  "Starts everything except the HTTP server, against a fresh in-memory database."
  [run-tests]
  (mount/start-with-args
    {:config    "config/dev.edn"
     :overrides {:com.fulcrologic.rad.database-adapters.datomic/databases
                 {:main {:datomic/schema   :production
                         :datomic/client   {:server-type :datomic-local
                                            :storage-dir :mem
                                            :system      "test"}
                         :datomic/database (str "test-" (random-uuid))}}}}
    (mount/except [#'server/http-server]))
  (d/transact (:main datomic-connections)
    {:tx-data [(account/new-account "tony@example.com" :account/id (new-uuid 100))
               (account/new-account "sally@example.com" :account/id (new-uuid 101) :account/active? false)]})
  (auth/set-password! "tony@example.com" "letmein")
  (auth/set-password! "sally@example.com" "letmein")
  (try
    (run-tests)
    (finally
      (mount/stop))))

(use-fixtures :once with-backend)

(defn test-app
  "A client app whose remote goes through the real Ring middleware (sessions, cookies, CSRF config)."
  []
  (doto (h/build-test-app {:root-class Root
                           ;; The loopback remote also passes a pre-parsed :cookies map whose values are still
                           ;; URL-encoded; Ring would trust it and fail to decrypt the session cookie. Dropping it makes
                           ;; Ring parse the Cookie header, exactly as with a browser.
                           :remotes    {:remote (lr/fulcro-ring-remote
                                                  (fn [request] (ring/middleware (dissoc request :cookies :session))))}})
    (client/start! {:event-loop? :immediate})))

(defn page-text [app]
  (h/render-frame! app)
  (pr-str (h/hiccup-frame app)))

(defn main-text
  "Text of the routed content only (excludes the nav bar, which shows the logged-in email)."
  [app]
  (h/render-frame! app)
  (pr-str (first (hic/find-by-tag (h/hiccup-frame app) :main))))

(defn log-in! [app email password]
  (h/render-frame! app)
  ;; By id: in a form with several labeled inputs, `h/type-into-labeled!` returns the form's FIRST input for any
  ;; label but the first one (it picks the outermost container that holds a matching label).
  (h/type-into! app "login-email" email)
  (h/type-into! app "login-password" password)
  (h/render-frame! app)
  (h/click! app "login-submit"))

(defn logged-in-app []
  (doto (test-app) (log-in! "tony@example.com" "letmein")))

(defn auth-status [app] (:ui/auth-status (app/current-state app)))

(defn db-emails []
  (set (map first (d/q '[:find ?e :where [_ :account/email ?e]] (d/db (:main datomic-connections))))))

(defn anonymous-query
  "Runs `eql` through the parser exactly as an API request with no session would."
  [eql]
  (parser/parser {:ring/request {:session {}} ::auth/session-update (volatile! nil)} eql))

(specification "Logging in"
  (behavior "starts on the login page"
    (assertions
      (auth-status (test-app)) => :logged-out))
  (let [app (test-app)]
    (log-in! app "tony@example.com" "wrong")
    (assertions
      "rejects a wrong password with a generic message"
      (auth-status app) => :logged-out
      (.contains (page-text app) "Invalid email or password") => true))
  (let [app (test-app)]
    (log-in! app "nobody@example.com" "letmein")
    (assertions
      "rejects an unknown email with the same message"
      (.contains (page-text app) "Invalid email or password") => true))
  (let [app (test-app)]
    (log-in! app "sally@example.com" "letmein")
    (assertions
      "rejects an inactive account"
      (auth-status app) => :logged-out))
  (let [app (logged-in-app)]
    (assertions
      "accepts the right password and shows the app"
      (auth-status app) => :logged-in
      (.contains (page-text app) "tony@example.com") => true)))

(specification "The API without a session"
  (assertions
    "refuses protected data"
    (:account/all-accounts (anonymous-query [{:account/all-accounts [:account/email]}])) => nil
    "reports nobody logged in"
    (:session/current-user (anonymous-query [:session/current-user])) => nil
    "never exposes password hashes, even when asked"
    (pr-str (anonymous-query [{[:account/id (new-uuid 100)] [:account/password-hash]}])) =fn=> #(not (.contains ^String % "bcrypt"))))

(specification "Logging out"
  (let [app (logged-in-app)]
    (h/click-on-text! app "Log out")
    (assertions
      "returns to the login page"
      (auth-status app) => :logged-out)
    (session/check-session! app)
    (df/load! app :account/all-accounts nil)
    (assertions
      "ends the session on the server: the same cookies are no longer logged in"
      (auth-status app) => :logged-out
      (:account/all-accounts (app/current-state app)) =fn=> empty?)))

(specification "Account report"
  (let [app (logged-in-app)]
    (scr/route-to! app AccountList)
    (assertions
      "loads active accounts from the server"
      (.contains (main-text app) "tony@example.com") => true
      "hides inactive accounts by default"
      (.contains (main-text app) "sally@example.com") => false)))

(specification "Creating an account"
  (let [app (logged-in-app)]
    (form/create! app AccountForm)
    (h/render-frame! app)
    (h/type-into-labeled! app "Email" "new@example.com")
    (h/click-on-text! app "Save")
    (assertions
      "persists the new account"
      (contains? (db-emails) "new@example.com") => true)))

(specification "Leaving a form with unsaved changes"
  (let [app (logged-in-app)]
    (form/edit! app AccountForm (new-uuid 100))
    (h/render-frame! app)
    (h/type-into-labeled! app "Email" "changed@example.com")
    (scr/route-to! app AccountList)
    (behavior "is denied until the user decides"
      (assertions
        (.contains (page-text app) "You have unsaved changes.") => true))
    (h/click-on-text! app "Discard changes")
    (assertions
      "continues to the requested route when changes are discarded"
      (.contains (page-text app) "All Accounts") => true
      "does not save the discarded edit"
      (contains? (db-emails) "changed@example.com") => false)))
