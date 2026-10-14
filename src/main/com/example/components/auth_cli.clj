(ns com.example.components.auth-cli
  "Command-line user management, for when there is no REPL (e.g. a production box or container):

     java -cp app.jar clojure.main -m com.example.components.auth-cli add-user me@example.com
     java -cp app.jar clojure.main -m com.example.components.auth-cli set-password me@example.com
     java -cp app.jar clojure.main -m com.example.components.auth-cli disable me@example.com
     java -cp app.jar clojure.main -m com.example.components.auth-cli enable me@example.com
     java -cp app.jar clojure.main -m com.example.components.auth-cli list

   The password is prompted for (not echoed) on a terminal, or read from the first line of stdin when piped,
   so it never ends up in shell history or the process list.

   Uses the same config as the server (config/prod.edn, or -Dconfig=...). With Datomic Local, stop the server
   first: Datomic Local storage is meant for one process at a time."
  (:require
    [com.example.components.auth :as auth]
    [com.example.components.config :as config]
    [com.example.components.database :as database]
    [mount.core :as mount]))

(defn- read-password []
  (if-let [console (System/console)]
    (let [p1 (String. (.readPassword console "Password: " (object-array 0)))
          p2 (String. (.readPassword console "Repeat password: " (object-array 0)))]
      (when-not (= p1 p2) (throw (ex-info "Passwords don't match" {})))
      p1)
    (read-line)))

(defn- run [command email]
  (case command
    "add-user" (do (auth/add-user! email (read-password)) (println "Added" email))
    "set-password" (do (auth/set-password! email (read-password)) (println "Password changed for" email))
    "disable" (do (auth/set-active! email false) (println "Disabled" email))
    "enable" (do (auth/set-active! email true) (println "Enabled" email))
    "list" (doseq [{:user/keys [email active?]} (sort-by :user/email (auth/users))]
             (println email (if active? "" "(disabled)")))
    (throw (ex-info (str "Unknown command: " command ". Use add-user, set-password, disable, enable or list.") {}))))

(defn -main [& [command email]]
  (let [exit-code (try
                    (when (and (not= "list" command) (empty? email))
                      (throw (ex-info "Usage: <add-user|set-password|disable|enable> <email>  or  list" {})))
                    ;; Only what user management needs: no web server, no session key required
                    (mount/start-with-args {:config "config/prod.edn"}
                      #'config/config #'database/datomic-connections #'auth/auth)
                    (run command email)
                    0
                    (catch Exception e
                      (binding [*out* *err*] (println "Error:" (ex-message e)))
                      1)
                    (finally
                      (mount/stop)))]
    (shutdown-agents)
    (System/exit exit-code)))
