(ns com.example.components.auth
  "Users and password login.

   * Users: a `user` entity (`:user/id`, `:user/email`, `:user/password-hash`, `:user/active?`), separate from the
     app's own entities. It is plain Datomic schema, deliberately NOT RAD attributes: RAD generates resolvers for
     every attribute it knows about, which would let any client query the hashes. Only this namespace reads them.
     Manage users with `add-user!` / `set-password!` / `set-active!` from a REPL, or the `auth-cli` namespace.
   * Sessions: the logged-in user id lives in the Ring session (see `com.example.lib.session`).
   * Authorization: `guard-plugin` rejects every resolver and mutation except the public ones below unless the
     request has a logged-in user. Finer-grained rules (roles, ownership) go there too.

   To remove authentication from the app, see \"Removing authentication\" in the README."
  (:require
    [buddy.hashers :as hashers]
    [com.example.components.database :refer [datomic-connections]]
    [com.example.lib.session :as session]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [datomic.client.api :as d]
    [mount.core :refer [defstate]]))

(def schema
  [{:db/ident       :user/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :user/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :user/password-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(defn- conn [] (:main datomic-connections))

(defn- find-user [db email]
  (ffirst (d/q '[:find (pull ?e [:user/id :user/email :user/active? :user/password-hash])
                 :in $ ?email
                 :where [?e :user/email ?email]]
            db email)))

;; ---------------------------------------------------------------------------------------------------------------
;; User management (REPL / auth-cli)

(defn add-user!
  "Creates an active user. Throws if the email is already taken. Returns the new user's id."
  [email password]
  (when (find-user (d/db (conn)) email)
    (throw (ex-info (str "A user with email " email " already exists") {:email email})))
  (let [id (new-uuid)]
    (d/transact (conn) {:tx-data [{:user/id            id
                                   :user/email         email
                                   :user/password-hash (hashers/derive password)
                                   :user/active?       true}]})
    id))

(defn set-password!
  "Replaces the password of the user with `email`."
  [email password]
  (when-not (find-user (d/db (conn)) email)
    (throw (ex-info (str "No user with email " email) {:email email})))
  (d/transact (conn) {:tx-data [{:db/id [:user/email email] :user/password-hash (hashers/derive password)}]}))

(defn set-active!
  "Enables or disables login for the user with `email` (sessions they already have stay valid until logout)."
  [email active?]
  (d/transact (conn) {:tx-data [{:db/id [:user/email email] :user/active? (boolean active?)}]}))

(defn users
  "All users (without hashes)."
  []
  (map first (d/q '[:find (pull ?e [:user/id :user/email :user/active?]) :where [?e :user/id]] (d/db (conn)))))

;; ---------------------------------------------------------------------------------------------------------------
;; Login

;; Checked against when the email is unknown, so a missing user takes as long as a wrong password.
(def ^:private dummy-hash (delay (hashers/derive (str (random-uuid)))))

(defn authenticate
  "Returns `{:user/id .. :user/email ..}` when `email`/`password` match an active user, else nil."
  [email password]
  (let [{:user/keys [password-hash active?] :as user} (when (string? email) (find-user (d/db (conn)) email))]
    (if (and password-hash (string? password))
      (when (and (:valid (hashers/verify password password-hash)) active?)
        (select-keys user [:user/id :user/email]))
      (do (hashers/verify (str password) @dummy-hash) nil))))

(defn current-user-id [env] (get (session/session env) ::user-id))

(pc/defresolver current-user [env _]
  {::pc/output [{:session/current-user [:user/id :user/email]}]}
  {:session/current-user
   (when-let [id (current-user-id env)]
     (not-empty (d/pull (d/db (conn)) [:user/id :user/email] [:user/id id])))})

(pc/defmutation login [env {:keys [email password]}]
  {::pc/output [:session/current-user :session/error]}
  (if-let [{:user/keys [id] :as user} (authenticate email password)]
    (do
      ;; Keep the rest of the session (it holds the CSRF token) and ask Ring for a new session id,
      ;; so an id planted before login (session fixation) is worthless afterwards.
      (session/set-session! env (vary-meta (assoc (session/session env) ::user-id id) assoc :recreate true))
      {:session/current-user user})
    {:session/error "Invalid email or password"}))

(pc/defmutation logout [env _]
  {::pc/output []}
  (session/set-session! env nil)
  {})

(def resolvers [current-user login logout])

;; ---------------------------------------------------------------------------------------------------------------
;; Authorization

(def ^:private public-resolvers #{`current-user})
(def ^:private public-mutations #{`login `logout})

(defn- not-authenticated [] (ex-info "Not authenticated" {:status 401}))

(def guard-plugin
  "Pathom plugin: only `public-resolvers`/`public-mutations` run without a logged-in user."
  {::pc/wrap-resolve (fn [resolve]
                       (fn [env input]
                         (if (or (current-user-id env)
                               (contains? public-resolvers (-> env ::pc/resolver-data ::pc/sym)))
                           (resolve env input)
                           (throw (not-authenticated)))))
   ::p/wrap-mutate   (fn [mutate]
                       (fn [env k params]
                         (if (or (current-user-id env) (contains? public-mutations k))
                           (mutate env k params)
                           {:action (fn [] (throw (not-authenticated)))})))})

;; Installs (idempotently) the user schema at startup.
(defstate auth
  :start (do (d/transact (conn) {:tx-data schema}) :started))
