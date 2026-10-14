(ns com.example.components.nrepl
  "Optional nREPL server inside the running app, for operating it live (adding users, inspecting data, fixing
   things) without a restart. Off unless a port is configured (NREPL_PORT in prod).

   It only listens on 127.0.0.1: an nREPL connection can run any code in the server and has no login of its own,
   so it must never be reachable from the network. Reach it from the machine itself, `docker exec`, or an SSH
   tunnel. The uberjar includes a client:

     java -cp app.jar clojure.main -m nrepl.cmdline --connect --host 127.0.0.1 --port 7888"
  (:require
    [com.example.components.config :refer [config]]
    [mount.core :refer [defstate]]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as log]))

(defn start!
  "Starts an nREPL server on 127.0.0.1:`port` (0 picks a free port)."
  [port]
  (let [server (nrepl/start-server :bind "127.0.0.1" :port port)]
    (log/info "nREPL listening on 127.0.0.1:" (:port server))
    server))

(defstate nrepl-server
  :start (some-> (::port config) start!)
  :stop (some-> nrepl-server nrepl/stop-server))
