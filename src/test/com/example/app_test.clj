(ns com.example.app-test
  "End-to-end tests that run the real client (routing chart, forms, reports, renderers)
   against the real Pathom parser and an in-memory Datomic, all in one JVM.
   No browser and no HTTP: the client talks to the parser through a loopback remote."
  (:require
    [clojure.test :refer [use-fixtures]]
    [com.example.client :as client]
    [com.example.components.database :refer [datomic-connections]]
    [com.example.components.parser :as parser]
    [com.example.components.server :as server]
    [com.example.model.account :as account]
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.example.ui.root :refer [Root]]
    [com.fulcrologic.fulcro.headless :as h]
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
  (try
    (run-tests)
    (finally
      (mount/stop))))

(use-fixtures :once with-backend)

(defn test-app []
  (doto (h/build-test-app
          {:root-class Root
           :remotes    {:remote (lr/pathom-remote parser/parser :env-fn (fn [] {:ring/request {}}))}})
    (client/start! {:event-loop? :immediate})))

(defn page-text [app]
  (h/render-frame! app)
  (pr-str (h/hiccup-frame app)))

(defn db-emails []
  (set (map first (d/q '[:find ?e :where [_ :account/email ?e]] (d/db (:main datomic-connections))))))

(specification "Account report"
  (let [app (test-app)]
    (scr/route-to! app AccountList)
    (assertions
      "loads active accounts from the server"
      (.contains (page-text app) "tony@example.com") => true
      "hides inactive accounts by default"
      (.contains (page-text app) "sally@example.com") => false)))

(specification "Creating an account"
  (let [app (test-app)]
    (form/create! app AccountForm)
    (h/render-frame! app)
    (h/type-into-labeled! app "Email" "new@example.com")
    (h/click-on-text! app "Save")
    (assertions
      "persists the new account"
      (contains? (db-emails) "new@example.com") => true)))

(specification "Leaving a form with unsaved changes"
  (let [app (test-app)]
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
