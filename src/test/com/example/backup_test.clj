(ns com.example.backup-test
  "Online backups against a real on-disk Datomic Local database (backups of an in-memory one are refused)."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [use-fixtures]]
    [com.example.components.backup :as backup]
    [com.example.components.config :as config]
    [com.example.components.database :refer [datomic-connections]]
    [com.example.components.server :as server]
    [com.example.model.account :as account]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [datomic.client.api :as d]
    [datomic.local :as dl]
    [fulcro-spec.core :refer [specification behavior component assertions =>]]
    [mount.core :as mount])
  (:import
    (java.io File)
    (java.nio.file Files)
    (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- temp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(def ^:dynamic *storage* nil)
(def ^:dynamic *backups* nil)

(defn with-disk-backend [run-tests]
  (let [storage (temp-dir "storage") backups (io/file (temp-dir "backup-root") "backups")]
    (mount/start-with-args
      {:config    "config/dev.edn"
       :overrides {:com.fulcrologic.rad.database-adapters.datomic/databases
                   {:main {:datomic/schema   :production
                           :datomic/client   {:server-type :datomic-local
                                              :storage-dir (.getPath storage)
                                              :system      "test"}
                           :datomic/database "app"}}
                   ::backup/dir (.getPath backups)}}
      (mount/except [#'server/http-server]))
    (try
      (binding [*storage* storage *backups* backups]
        (run-tests))
      (finally
        (mount/stop)))))

(use-fixtures :once with-disk-backend)

(defn- conn [] (:main datomic-connections))

(defn- add-account! [email] (d/transact (conn) {:tx-data [(account/new-account email :account/id (new-uuid))]}))

(defn- emails-in-backup
  "Opens the backup at `path` the way a restore would (as the storage dir) and returns its account emails."
  [path]
  (let [opts {:storage-dir path :system "test" :db-name "app"}]
    (try
      (let [c (d/connect (d/client {:server-type :datomic-local :storage-dir path :system "test"}) {:db-name "app"})]
        (set (map first (d/q '[:find ?e :where [_ :account/email ?e]] (d/db c)))))
      (finally (dl/release-db opts)))))

(defn- perms [^File f] (PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath f) (make-array java.nio.file.LinkOption 0))))

(specification "backup!"
  (add-account! "before@example.com")
  (let [{:keys [path t]} (backup/backup!)]
    (add-account! "after@example.com")
    (assertions
      "writes a verified backup and reports its database t"
      (pos? t) => true
      (.getName (io/file path)) =fn=> #(re-matches #"backup-\d{8}T\d{6}Z" %)
      "contains the data as of the backup, and nothing written later"
      (contains? (emails-in-backup path) "before@example.com") => true
      (contains? (emails-in-backup path) "after@example.com") => false
      "is readable only by the server's user"
      (perms (io/file path)) => "rwx------"
      (every? #(#{"rw-------" "rwx------"} (perms %)) (file-seq (io/file path))) => true
      "leaves no temp directory behind"
      (some #(.startsWith (.getName ^File %) ".tmp") (.listFiles *backups*)) => nil)))

(specification "backup! while the database is being written"
  (let [running (atom true)
        writer  (future (loop [i 0]
                          (when @running
                            (add-account! (str "load-" i "@example.com"))
                            (recur (inc i)))))
        results (try
                  (vec (repeatedly 5 #(do (Thread/sleep 200) (backup/backup!))))
                  (finally (reset! running false) @writer))]
    (assertions
      "every backup verifies and opens"
      (count results) => 5
      (every? #(seq (emails-in-backup (:path %))) results) => true
      "each later backup has at least as much data"
      (apply <= (map :t results)) => true)))

(specification "backup! refuses unsafe or impossible setups"
  (component "no backup directory"
    (with-redefs [config/config (dissoc config/config ::backup/dir)]
      (assertions
        (backup/backup!) =throws=> #"Set BACKUP_DIR")))
  (component "in-memory database"
    (with-redefs [config/config (assoc-in config/config
                                  [:com.fulcrologic.rad.database-adapters.datomic/databases :main :datomic/client :storage-dir]
                                  :mem)]
      (assertions
        (backup/backup!) =throws=> #"in memory")))
  (component "backup directory inside the database storage"
    (with-redefs [config/config (assoc config/config ::backup/dir (.getPath (io/file *storage* "backups")))]
      (assertions
        (backup/backup!) =throws=> #"must not be inside"
        "and creates nothing there"
        (.exists (io/file *storage* "backups")) => false))))

(specification "prune-backups!"
  (let [stray  (doto (io/file *backups* "not-a-backup") .mkdirs)
        failed (doto (io/file *backups* ".tmp-backup-20200101T000000Z") .mkdirs)
        before (backup/backups)
        newest (vec (take 2 before))
        _      (backup/prune-backups! 2)]
    (assertions
      "keeps the newest backups"
      (backup/backups) => newest
      "removes leftovers of failed attempts"
      (.exists failed) => false
      "never touches directories it didn't create"
      (.exists stray) => true
      "requires a positive count"
      (backup/prune-backups! 0) =throws=> #"positive")))
