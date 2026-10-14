(ns development
  (:require
    [clojure.tools.namespace.repl :as tools-ns :refer [set-refresh-dirs]]
    [com.example.components.auth :as auth]                  ; AUTH: remove
    [com.example.components.database :refer [datomic-connections]]
    [com.example.components.ring-middleware]
    [com.example.components.server]
    [com.example.model.account :as account]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [datomic.client.api :as d]
    [mount.core :as mount]
    [taoensso.timbre :as log]))

;; Prevent tools-ns from finding source in other places, such as resources
(set-refresh-dirs "src/main" "src/dev")

(comment
  (let [db (d/db (:main datomic-connections))]
    (d/pull db '[*] [:account/id (new-uuid 100)])))

(defn seed!
  "Adds sample data. Fixed ids make it safe to run repeatedly."
  []
  (dt/set-timezone! "America/Los_Angeles")
  (when-let [connection (:main datomic-connections)]
    (log/info "SEEDING data.")
    ;; AUTH: remove the next 3 lines
    (when-not (some #(= "dev@example.com" (:user/email %)) (auth/users))
      (auth/add-user! "dev@example.com" "letmein"))
    (log/info "Dev login: dev@example.com / letmein")
    (d/transact connection
      {:tx-data [(account/new-account "tony@example.com" :account/id (new-uuid 100))
                 (account/new-account "sam@example.com" :account/id (new-uuid 101))
                 (account/new-account "sally@example.com" :account/id (new-uuid 102) :account/active? false)]})))

(defn start []
  (mount/start-with-args {:config "config/dev.edn"})
  (seed!)
  :ok)

(defn stop
  "Stop the server."
  []
  (mount/stop))

(defn fast-restart
  "Stop and restart the server, without reloading source."
  []
  (stop)
  (start))

(defn restart
  "Stop, refresh, and restart the server."
  []
  (stop)
  (tools-ns/refresh :after 'development/start))
