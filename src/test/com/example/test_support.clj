(ns com.example.test-support
  "Shared setup for the headless end-to-end tests: the real client (login, routing chart, forms, reports, renderers)
   against the real Ring handler, Pathom parser and an in-memory Datomic, all in one JVM. No browser and no HTTP
   server: the client's remote calls the Ring handler directly, carrying cookies between requests like a browser."
  (:require
    [com.example.client :as client]
    [com.example.components.auth :as auth]                  ; AUTH: remove
    [com.example.components.database :refer [datomic-connections]]
    [com.example.components.ring-middleware :as ring]
    [com.example.components.server :as server]
    [com.example.model.account :as account]
    [com.example.ui.root :refer [Root]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lr]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [datomic.client.api :as d]
    [mount.core :as mount]))

(def password "letmein")

(defn with-backend
  "clojure.test :once fixture: starts everything except the HTTP server against a fresh in-memory database
   with sample accounts and users."
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
  ;; AUTH: remove these users
  (auth/add-user! "user@example.com" password)
  (auth/add-user! "disabled@example.com" password)
  (auth/set-active! "disabled@example.com" false)
  (try
    (run-tests)
    (finally
      (mount/stop))))

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

(defn logged-in-app
  "A `test-app` with a user logged in."
  []
  ;; AUTH: without authentication, make this just (test-app) and delete `log-in!` and `auth-status`
  (doto (test-app) (log-in! "user@example.com" password)))

(defn auth-status [app] (:ui/auth-status (app/current-state app)))

(defn db-emails
  "Every :account/email in the database."
  []
  (set (map first (d/q '[:find ?e :where [_ :account/email ?e]] (d/db (:main datomic-connections))))))
