(ns build
  "Builds the production uberjar. Run `make release` first so the compiled
   CLJS (resources/public/js/main) is on the classpath and gets packaged."
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/app.jar")
(def main-ns 'com.example.components.server)

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (when-not (.exists (io/file "resources/public/js/main/main.js"))
    (throw (ex-info "Missing compiled CLJS. Run `make release` first." {})))
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs   ["src/main" "resources"]
                 :target-dir class-dir})
    ;; AOT compiles the entry point and (transitively) everything it requires, for faster startup.
    (b/compile-clj {:basis      basis
                    :ns-compile [main-ns]
                    :class-dir  class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns})
    (println "Built" uber-file)))
