(ns com.example.components.auth
  "Password authentication with sessions.

   * Credentials: `:account/password-hash` (bcrypt via buddy-hashers). It is plain Datomic schema, deliberately NOT a
     RAD attribute: RAD generates resolvers for every attribute it knows about, which would let any client query
     the hash. Only the code in this namespace reads it.
   * Sessions: the logged-in account id lives in the Ring session (see `ring-middleware` for the cookie store).
     Pathom mutations can't touch the Ring response, so `login`/`logout` put the new session in a volatile that
     `ring-middleware/wrap-api` copies onto the response.
   * Authorization: `guard-plugin` rejects every resolver and mutation except the public ones below unless the
     request has a logged-in account. Finer-grained rules (roles, ownership) go there too."
  (:require
    [buddy.hashers :as hashers]
    [com.example.components.config :refer [config]]
    [com.example.components.database :refer [datomic-connections]]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [datomic.client.api :as d]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]))

(def schema
  ;; noHistory: an old hash is useless once replaced, so don't keep it in the database's history.
  [{:db/ident       :account/password-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true}])

(defn- conn [] (:main datomic-connections))

(defn- find-account [db email]
  (some->> (d/q '[:find (pull ?e [:account/id :account/email :account/active? :account/password-hash])
                  :in $ ?email
                  :where [?e :account/email ?email]]
             db email)
    ffirst))

(defn set-password!
  "Sets (or replaces) the password of the account with `email`. Use from the REPL, or call from your own
   admin/self-service mutation."
  [email password]
  (d/transact (conn) {:tx-data [{:db/id                 [:account/email email]
                                 :account/password-hash (hashers/derive password)}]}))

;; Checked against when the email is unknown, so a missing account takes as long as a wrong password.
(def ^:private dummy-hash (delay (hashers/derive (str (random-uuid)))))

(defn authenticate
  "Returns `{:account/id .. :account/email ..}` when `email`/`password` match an active account, else nil."
  [email password]
  (let [{:account/keys [password-hash active?] :as account} (when (string? email) (find-account (d/db (conn)) email))]
    (if (and password-hash (string? password))
      (when (and (:valid (hashers/verify password password-hash)) active?)
        (select-keys account [:account/id :account/email]))
      (do (hashers/verify (str password) @dummy-hash) nil))))

;; ---------------------------------------------------------------------------------------------------------------
;; Session helpers. `env` is the Pathom env, which carries the Ring request (see ring-middleware/wrap-api).

(defn session [env] (get-in env [:ring/request :session]))

(defn current-account-id [env] (get (session env) ::account-id))

(defn- set-session! [env new-session]
  (vreset! (::session-update env) new-session))

;; ---------------------------------------------------------------------------------------------------------------
;; API

(pc/defresolver current-user [env _]
  {::pc/output [{:session/current-user [:account/id :account/email]}]}
  {:session/current-user
   (when-let [id (current-account-id env)]
     (some-> (d/pull (d/db (conn)) [:account/id :account/email] [:account/id id])
       (select-keys [:account/id :account/email])
       not-empty))})

(pc/defmutation login [env {:keys [email password]}]
  {::pc/output [:session/current-user :session/error]}
  (if-let [{:account/keys [id] :as account} (authenticate email password)]
    (do
      ;; Keep the rest of the session (it holds the CSRF token) and ask Ring for a new session id,
      ;; so an id planted before login (session fixation) is worthless afterwards.
      (set-session! env (vary-meta (assoc (session env) ::account-id id) assoc :recreate true))
      {:session/current-user account})
    {:session/error "Invalid email or password"}))

(pc/defmutation logout [env _]
  {::pc/output []}
  (set-session! env nil)
  {})

(def resolvers [current-user login logout])

(def ^:private public-resolvers #{`current-user})
(def ^:private public-mutations #{`login `logout})

(defn- not-authenticated [] (ex-info "Not authenticated" {:status 401}))

(def guard-plugin
  "Pathom plugin: only `public-resolvers`/`public-mutations` run without a logged-in account."
  {::pc/wrap-resolve (fn [resolve]
                       (fn [env input]
                         (if (or (current-account-id env)
                               (contains? public-resolvers (-> env ::pc/resolver-data ::pc/sym)))
                           (resolve env input)
                           (throw (not-authenticated)))))
   ::p/wrap-mutate   (fn [mutate]
                       (fn [env k params]
                         (if (or (current-account-id env) (contains? public-mutations k))
                           (mutate env k params)
                           {:action (fn [] (throw (not-authenticated)))})))})

;; ---------------------------------------------------------------------------------------------------------------
;; Startup: install the schema, and optionally create the first admin from config (ADMIN_EMAIL/ADMIN_PASSWORD in
;; prod). An existing password is never overwritten, so leaving the variables set doesn't reset it.

(defn- ensure-admin! [{:keys [email password]}]
  (when (and (seq email) (seq password))
    (let [{:account/keys [password-hash] :as account} (find-account (d/db (conn)) email)]
      (when-not password-hash
        (log/info "Creating initial login for" email)
        (d/transact (conn) {:tx-data [(cond-> {:account/email         email
                                               :account/password-hash (hashers/derive password)}
                                        (nil? account) (assoc :account/id (new-uuid) :account/active? true))]})))))

(defstate auth
  :start
  (do
    (d/transact (conn) {:tx-data schema})
    (ensure-admin! (::admin config))
    :started))
