(ns com.example.auth-test
  "Login, logout, the API guard and user management (see `test-support` for the setup)."
  (:require
    [clojure.test :refer [use-fixtures]]
    [com.example.components.auth :as auth]
    [com.example.components.auth-cli :as auth-cli]
    [com.example.components.parser :as parser]
    [com.example.session :as session]
    [com.example.test-support :as ts]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.headless :as h]
    [fulcro-spec.core :refer [specification behavior component assertions =>]]))

(use-fixtures :once ts/with-backend)

(defn anonymous-query
  "Runs `eql` through the parser exactly as an API request with no session would."
  [eql]
  (parser/parser {:ring/request {:session {}}} eql))

(specification "Logging in"
  (behavior "starts on the login page"
    (assertions
      (ts/auth-status (ts/test-app)) => :logged-out))
  (let [app (ts/test-app)]
    (ts/log-in! app "user@example.com" "wrong")
    (assertions
      "rejects a wrong password with a generic message"
      (ts/auth-status app) => :logged-out
      (.contains (ts/page-text app) "Invalid email or password") => true))
  (let [app (ts/test-app)]
    (ts/log-in! app "nobody@example.com" ts/password)
    (assertions
      "rejects an unknown email with the same message"
      (.contains (ts/page-text app) "Invalid email or password") => true))
  (let [app (ts/test-app)]
    (ts/log-in! app "disabled@example.com" ts/password)
    (assertions
      "rejects a disabled user"
      (ts/auth-status app) => :logged-out))
  (let [app (ts/logged-in-app)]
    (assertions
      "accepts the right password and shows the app"
      (ts/auth-status app) => :logged-in
      (.contains (ts/page-text app) "user@example.com") => true)))

(specification "The API without a session"
  (assertions
    "refuses protected data"
    (:account/all-accounts (anonymous-query [{:account/all-accounts [:account/email]}])) => nil
    "reports nobody logged in"
    (:session/current-user (anonymous-query [:session/current-user])) => nil
    "has no way to query users or password hashes"
    (pr-str (anonymous-query [{[:user/email "user@example.com"] [:user/id :user/password-hash]}]))
    =fn=> #(not (.contains ^String % "bcrypt"))))

(specification "Logging out"
  (let [app (ts/logged-in-app)]
    (h/click-on-text! app "Log out")
    (assertions
      "returns to the login page"
      (ts/auth-status app) => :logged-out)
    (session/check-session! app)
    (df/load! app :account/all-accounts nil)
    (assertions
      "ends the session on the server: the same cookies are no longer logged in"
      (ts/auth-status app) => :logged-out
      (:account/all-accounts (app/current-state app)) =fn=> empty?)))

(specification "Managing users"
  (component "add-user!"
    (auth/add-user! "new-user@example.com" "s3cret")
    (assertions
      "creates a user who can log in"
      (:user/email (auth/authenticate "new-user@example.com" "s3cret")) => "new-user@example.com"
      "refuses a duplicate email"
      (auth/add-user! "new-user@example.com" "other") =throws=> #"already exists"))
  (component "set-password!"
    (auth/set-password! "new-user@example.com" "changed")
    (assertions
      "replaces the password"
      (auth/authenticate "new-user@example.com" "s3cret") => nil
      (:user/email (auth/authenticate "new-user@example.com" "changed")) => "new-user@example.com"))
  (component "set-active!"
    (auth/set-active! "new-user@example.com" false)
    (assertions
      "disabling prevents login"
      (auth/authenticate "new-user@example.com" "changed") => nil)
    (auth/set-active! "new-user@example.com" true)
    (assertions
      "enabling allows it again"
      (some? (auth/authenticate "new-user@example.com" "changed")) => true))
  (component "the CLI"
    ;; Calls the command handler directly: -main would restart mount against prod config and exit the JVM
    (with-in-str "piped-password\n" (#'auth-cli/run "add-user" "cli-user@example.com"))
    (assertions
      "adds a user, reading the password from stdin when there is no terminal"
      (:user/email (auth/authenticate "cli-user@example.com" "piped-password")) => "cli-user@example.com")))
