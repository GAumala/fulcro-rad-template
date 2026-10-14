(ns com.example.ui.login
  "Login page and the login/logout mutations. The server side is `com.example.components.auth`."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.example.session :as session]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))

(def ^:private login-ident [:component/id ::LoginPage])
;; Server-side names (the server ns is CLJ-only, so these can't be syntax-quoted from CLJS)
(def ^:private server-login 'com.example.components.auth/login)
(def ^:private server-logout 'com.example.components.auth/logout)

(defmutation login [_params]
  (action [{:keys [state]}]
    (swap! state update-in login-ident assoc :ui/busy? true :ui/error nil))
  (remote [env]
    (m/with-server-side-mutation env server-login))
  (ok-action [{:keys [app state result]}]
    (let [{:session/keys [current-user]} (get-in result [:body server-login])]
      (swap! state update-in login-ident assoc :ui/busy? false :ui/password "")
      (if current-user
        (session/logged-in! app current-user)
        (swap! state assoc-in (conj login-ident :ui/error) "Invalid email or password"))))
  (error-action [{:keys [state]}]
    (swap! state update-in login-ident assoc :ui/busy? false :ui/error "Could not reach the server")))

(defmutation logout [_params]
  (remote [env]
    (m/with-server-side-mutation env server-logout))
  (ok-action [{:keys [app]}]
    ;; In the browser, a full reload drops all client state (forms, reports, routing) and fetches a fresh page
    #?(:cljs (.assign js/location "/")
       :clj  (session/logged-out! app))))

(defsc LoginPage [this {:ui/keys [email password error busy?]}]
  {:query         [:ui/email :ui/password :ui/error :ui/busy?]
   :ident         (fn [] login-ident)
   :initial-state {:ui/email "" :ui/password "" :ui/error nil :ui/busy? false}}
  (dom/form {:data-rad-type "login"
             :onSubmit      (fn [e] (evt/prevent-default! e))}
    (dom/h2 "Log in")
    (dom/div
      (dom/label {:htmlFor "login-email"} "Email")
      (dom/input {:id           "login-email"
                  :type         "email"
                  :autoComplete "username"
                  :value        email
                  :onChange     (fn [e] (m/set-string! this :ui/email :event e))}))
    (dom/div
      (dom/label {:htmlFor "login-password"} "Password")
      (dom/input {:id           "login-password"
                  :type         "password"
                  :autoComplete "current-password"
                  :value        password
                  :onChange     (fn [e] (m/set-string! this :ui/password :event e))}))
    (when error
      (dom/div {:data-rad-type "login-error" :role "alert"} error))
    ;; type=submit so Enter in a field also clicks it; preventDefault stops the native form post
    (dom/button {:id       "login-submit"
                 :type     "submit"
                 :disabled (boolean busy?)
                 :onClick  (fn [e]
                             (evt/prevent-default! e)
                             (comp/transact! this [(login {:email email :password password})]))}
      "Log in")))

(def ui-login-page (comp/factory LoginPage))
