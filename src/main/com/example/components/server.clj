(ns com.example.components.server
  (:gen-class)
  (:require
    [org.httpkit.server :refer [run-server]]
    [mount.core :as mount :refer [defstate]]
    [taoensso.timbre :as log]
    [com.example.components.config :refer [config]]
    [com.example.components.ring-middleware :refer [middleware]]))

(defstate http-server
  :start
  (let [cfg     (get config :org.httpkit.server/config)
        stop-fn (run-server middleware cfg)]
    (log/info "Starting webserver with config " cfg)
    {:stop stop-fn})
  :stop
  (let [{:keys [stop]} http-server]
    (when stop
      (stop))))

;; Entry point for the uberjar. In development the server is controlled from `src/dev/development.clj`.
;; The config file can be overridden with `-Dconfig=/path/to/file.edn`.
(defn -main [& _args]
  (mount/start-with-args {:config "config/prod.edn"})
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable mount/stop)))
