(ns com.example.config-test
  (:require
    [com.example.components.config :as config]
    [fulcro-spec.core :refer [specification assertions =>]]))

(def prod-like
  {:http                       {:port :env.edn/PORT}
   :db                         {:dir :env/STORAGE_DIR}
   :plain                      "no env here"
   ::config/optional-env #{"NREPL_PORT"}
   :repl                       :env.edn/NREPL_PORT})

(specification "env-references"
  (assertions
    "finds :env/ and :env.edn/ references at any depth"
    (config/env-references prod-like) => #{"PORT" "STORAGE_DIR" "NREPL_PORT"}))

(specification "missing-env"
  (assertions
    "names every unset variable that isn't optional"
    (config/missing-env [prod-like] {}) => #{"PORT" "STORAGE_DIR"}
    "treats blank values as missing"
    (config/missing-env [prod-like] {"PORT" " " "STORAGE_DIR" "/data"}) => #{"PORT"}
    "is empty when everything required is set"
    (config/missing-env [prod-like] {"PORT" "3000" "STORAGE_DIR" "/data"}) => #{}
    "honors optional-env declared in any of the merged files"
    (config/missing-env [{:x :env/A} {::config/optional-env #{"A"}}] {}) => #{}))
