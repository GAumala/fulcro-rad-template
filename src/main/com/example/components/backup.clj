(ns com.example.components.backup
  "Online backups of the Datomic Local database, run from a REPL inside the live server (no downtime):

     (require '[com.example.components.backup :as backup])
     (backup/backup!)            ; => {:path \"/backups/backup-20261014T170203Z\" :t 1234 ...}
     (backup/backups)            ; newest first
     (backup/prune-backups! 7)   ; delete all but the 7 newest

   Datomic Local documents copying the database directory as its backup method. How the copy is made matters,
   though: a database is an append-only `db.log` plus `log.idx`, an index into it. Copying the index first is safe
   during writes (the log can only have grown since); copying the log first can yield a corrupt backup. So this:

   * copies index files first, into a hidden temp directory, then renames it: a crash never leaves a partial
     backup that looks complete;
   * verifies before reporting success: opens the copy and checks it is a complete snapshot of the live database
     (see `check-copy`), retaking the copy when it caught a transaction mid-write;
   * makes everything readable only by the server's user (backups contain all data, password hashes included).

   Restoring: stop the server and copy the backup's contents into the storage directory (don't run the server on
   the backup directory itself: it would modify the backup). See \"Backups\" in the README.

   Only for Datomic Local. Datomic Pro and Cloud have their own backup tools."
  (:require
    [clojure.java.io :as io]
    [com.example.components.config :refer [config]]
    [com.example.components.database :refer [datomic-connections]]
    [datomic.client.api :as d]
    [datomic.local :as dl]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (java.nio.file CopyOption Files StandardCopyOption)
    (java.nio.file.attribute PosixFilePermissions)
    (java.time ZoneOffset ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(def ^:private backup-name-pattern #"backup-\d{8}T\d{6}Z")
(def ^:private temp-name-pattern #"\.tmp-backup-\d{8}T\d{6}Z")

(defn- owner-only! [^File f]
  ;; POSIX permissions; ignored on filesystems without them
  (try
    (Files/setPosixFilePermissions (.toPath f)
      (PosixFilePermissions/fromString (if (.isDirectory f) "rwx------" "rw-------")))
    (catch UnsupportedOperationException _ nil))
  f)

(defn- database-settings
  "Where the live database lives, from the config. Throws when it can't be backed up this way."
  []
  (let [{:datomic/keys [client database]} (get-in config [:com.fulcrologic.rad.database-adapters.datomic/databases :main])
        {:keys [server-type storage-dir system]} client]
    (when-not (#{:datomic-local :dev-local} server-type)
      (throw (ex-info "backup! only handles Datomic Local; use your Datomic Pro/Cloud backup tools." {:server-type server-type})))
    (when-not (string? storage-dir)
      (throw (ex-info "The database is in memory (:storage-dir :mem); there is nothing on disk to back up." {})))
    {:storage-dir storage-dir :system system :db-name database}))

(defn backup-dir
  "The configured backup directory (BACKUP_DIR in prod), as an absolute File. Throws if unset."
  ^File []
  (let [dir (::dir config)]
    (when (empty? dir)
      (throw (ex-info "No backup directory configured. Set BACKUP_DIR (an absolute path outside the database storage)." {})))
    (let [f (io/file dir)]
      (when-not (.isAbsolute f)
        (throw (ex-info (str "Backup directory must be an absolute path: " dir) {})))
      f)))

(defn- copy-database-files!
  "Copies the live database directory into `target`, index files before the log."
  [^File source ^File target]
  (.mkdirs target)
  (owner-only! target)
  (let [files (->> (.listFiles source)
                (filter #(.isFile ^File %))
                (remove #(= ".lock" (.getName ^File %)))
                ;; index first: see the namespace docstring
                (sort-by (fn [^File f] [(if (.endsWith (.getName f) ".idx") 0 1) (.getName f)])))]
    (when (empty? files)
      (throw (ex-info (str "No database files found in " source) {})))
    (doseq [^File f files
            :let [dest (io/file target (.getName f))]]
      (Files/copy (.toPath f) (.toPath dest) ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES]))
      (owner-only! dest))))

(defn- tx-datoms
  "The datoms of transaction `t` as recorded in the log (realized, so it is safe to release the db afterwards)."
  [conn t]
  (some->> (d/tx-range conn {:start t :end (inc t)}) first :data (mapv (juxt :e :a :v :tx :added)) set))

(defn- check-copy
  "Opens the copy at `root` and compares it with the live database. Returns `{:t ..}` when it is a complete,
   consistent snapshot, or `{:problem ..}`. Checks that its newest transaction:
   * is not newer than the live database's,
   * is recorded in its log exactly as in the live log (a copy taken while that transaction was being written can
     contain its data but an empty log entry; that gap would be permanent after a restore), and
   * has all its data visible to queries."
  [^File root {:keys [system db-name]} live-conn]
  (let [opts {:storage-dir (.getPath root) :system system :db-name db-name}]
    (try
      (let [copy   (d/connect (d/client {:server-type :datomic-local :storage-dir (.getPath root) :system system})
                     {:db-name db-name})
            db     (d/db copy)
            t      (:t db)
            copied (tx-datoms copy t)
            live   (tx-datoms live-conn t)]
        (cond
          (> t (:t (d/db live-conn))) {:problem "the copy is newer than the live database"}
          (not= copied live) {:problem (str "its newest transaction (t " t ") is incomplete in its log")}
          (not-every? (fn [[e a v _ added]]
                        (or (not added) (seq (d/datoms db {:index :eavt :components [e a v]}))))
            live) {:problem (str "its newest transaction (t " t ") is not fully visible to queries")}
          :else {:t t}))
      (catch Exception e
        {:problem (str "it doesn't open: " (ex-message e))})
      (finally
        (try (dl/release-db opts) (catch Exception _ nil))))))

(def ^:private max-attempts 5)

(def ^:private lock (Object.))

(defn backup!
  "Backs up the live database into a new `backup-<UTC timestamp>` directory under the backup directory, verifies
   it, and returns `{:path .. :t .. :bytes .. :attempts ..}`. Safe while the server is serving requests."
  []
  (locking lock
    (let [{:keys [storage-dir system db-name] :as db} (database-settings)
          root     (backup-dir)
          _        (when (.startsWith (.toPath (.getCanonicalFile root)) (.toPath (.getCanonicalFile (io/file storage-dir))))
                     (throw (ex-info "The backup directory must not be inside the database storage directory." {})))
          _        (doto root .mkdirs owner-only!)
          source   (io/file storage-dir system db-name)
          ;; Names have 1s resolution; a backup in the same second waits for the next one
          name     (loop []
                     (let [n (str "backup-" (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
                                              (ZonedDateTime/now ZoneOffset/UTC)))]
                       (if (.exists (io/file root n)) (do (Thread/sleep 200) (recur)) n)))
          final    (io/file root name)
          temp     (io/file root (str ".tmp-" name))]
      (try
        ;; A copy taken while a transaction is being written can be incomplete (see `check-copy`); that is detected
        ;; and the copy retaken. Each attempt only takes as long as copying the files.
        (loop [attempt 1]
          (doseq [^File f (reverse (file-seq temp))] (.delete f))
          (copy-database-files! source (doto (io/file temp system db-name) (-> .getParentFile .mkdirs)))
          (doseq [^File d [temp (io/file temp system)]] (owner-only! d))
          (let [{:keys [t problem]} (check-copy temp db (:main datomic-connections))]
            (cond
              t
              (do
                ;; Opening the copy to check it may have created files (e.g. .lock); lock everything down
                (doseq [f (file-seq temp)] (owner-only! f))
                (Files/move (.toPath temp) (.toPath final) ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                (let [result {:path     (.getPath final)
                              :t        t
                              :bytes    (reduce + (map #(.length ^File %) (file-seq final)))
                              :attempts attempt}]
                  (log/info "Backup complete" result)
                  result))

              (< attempt max-attempts)
              (do (log/info "Backup copy rejected (" problem "), retrying") (Thread/sleep 100) (recur (inc attempt)))

              :else
              (throw (ex-info (str "Backup failed: after " max-attempts " attempts the copy was still not consistent ("
                                problem "). Retry later, or use a volume snapshot.") {:problem problem})))))
        (catch Throwable e
          (doseq [^File f (reverse (file-seq temp))] (.delete f))
          (throw e))))))

(defn backups
  "Completed backups, newest first."
  []
  (let [root (backup-dir)]
    (->> (.listFiles root)
      (filter #(and (.isDirectory ^File %) (re-matches backup-name-pattern (.getName ^File %))))
      (sort-by #(.getName ^File %) #(compare %2 %1))
      (mapv #(.getPath ^File %)))))

(defn prune-backups!
  "Deletes all but the `keep` newest backups (and leftovers of failed attempts). Only touches directories this
   namespace created. Returns the deleted paths."
  [keep]
  (when-not (and (int? keep) (pos? keep))
    (throw (ex-info "keep must be a positive integer" {:keep keep})))
  (let [root     (backup-dir)
        old      (drop keep (backups))
        failed   (->> (.listFiles root)
                   (filter #(re-matches temp-name-pattern (.getName ^File %)))
                   (map #(.getPath ^File %)))
        doomed   (concat old failed)]
    (doseq [path doomed]
      (doseq [^File f (reverse (file-seq (io/file path)))] (.delete f))
      (log/info "Deleted backup" path))
    (vec doomed)))
