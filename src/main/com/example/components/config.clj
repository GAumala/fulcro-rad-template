(ns com.example.components.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.server.config :as fserver]
    [com.example.lib.logging :as logging]
    [mount.core :refer [defstate args]]
    [taoensso.timbre :as log]))

(def defaults-path "config/defaults.edn")

(defn- read-raw
  "The EDN at `path` as written, before env resolution. Absolute paths come from disk, others from the classpath
   (the same rules as `fserver/load-config!`)."
  [path]
  (let [f (io/file path)]
    (some-> (if (.isAbsolute f) (when (.exists f) f) (io/resource path)) slurp edn/read-string)))

(defn env-references
  "Names of the environment variables referenced as `:env/NAME` or `:env.edn/NAME` anywhere in `edn`."
  [edn]
  (into (sorted-set)
    (comp (filter keyword?)
      (filter #(contains? #{"env" "env.edn"} (namespace %)))
      (map name))
    (tree-seq coll? seq edn)))

(defn missing-env
  "Sorted names of the variables referenced in `raw-configs` that are unset or blank according to `getenv`,
   except those listed in any config's `::optional-env`."
  [raw-configs getenv]
  (let [optional (into #{} (mapcat ::optional-env) raw-configs)]
    (->> raw-configs
      (mapcat env-references)
      (remove optional)
      (filter #(str/blank? (getenv %)))
      (into (sorted-set)))))

(defn- check-env!
  "Fails fast, naming every missing variable at once, instead of letting a nil setting break something later."
  [config-path]
  (when-let [missing (seq (missing-env [(read-raw defaults-path) (read-raw config-path)] #(System/getenv %)))]
    (throw (ex-info (str "Missing required environment variables: " (str/join ", " missing)
                      " (referenced in " config-path "). Set them, or add them to "
                      ::optional-env " if they may be left unset.")
             {:missing (vec missing)}))))

(defstate config
  "The overrides option in args is for overriding configuration in tests.

   `config/defaults.edn` will *always* be the base of your configuration. The mount `args` can be used
   to override which config file is merged on top of defaults (see `server.clj`).

   Specifying -Dconfig on the JVM can be used to override config merged into the result, and will override
   the mount args. An absolute path will come from the filesystem, and a relative path will come from the CLASSPATH.

   Values like `:env/NAME` (string) or `:env.edn/NAME` (parsed as EDN) come from environment variables. Every
   referenced variable must be set unless it is listed in `::optional-env`; startup fails otherwise."
  :start (let [{:keys [config overrides]
                :or   {config "config/dev.edn"}} (args)
               config-path   (or (System/getProperty "config") config)
               _             (check-env! config-path)
               loaded-config (merge (fserver/load-config! {:config-path config}) overrides)]
           (log/info "Loading config" config-path)
           (logging/configure-logging! loaded-config)
           loaded-config))
