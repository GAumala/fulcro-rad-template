(ns com.example.ui.root
  "App UI root: the login page, or the app (nav + current route) once logged in."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.example.ui.login :as login :refer [LoginPage ui-login-page]]
    [com.example.ui.routes :refer [LandingPage Routes ui-routes]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]))

(defn nav-button [label on-click]
  (dom/button {:data-rad-type "nav-button" :onClick on-click} label))

(defsc Root [this {::app/keys     [active-remotes]
                   :ui/keys       [auth-status login routes]
                   :session/keys  [current-user]}]
  {:query         [:ui/auth-status
                   :session/current-user
                   {:ui/login (comp/get-query LoginPage)}
                   {:ui/routes (comp/get-query Routes)}
                   ;; Makes Root re-render when the routing chart changes state
                   [::sc/session-id '_]
                   ::app/active-remotes]
   :initial-state {:ui/auth-status :checking
                   :ui/login       {}
                   :ui/routes      {}}}
  (case auth-status
    :logged-out (ui-login-page login)
    :logged-in
    (let [busy?         (seq active-remotes)
          ;; The routing chart enters :routing-info/open when an active route (e.g. a dirty form) vetoes navigation
          route-denied? (contains? (scf/current-configuration this scr/session-id) :routing-info/open)]
      (dom/div
        (dom/nav {:data-rad-type "nav-bar"}
          (nav-button "Home" (fn [] (scr/route-to! this LandingPage)))
          (nav-button "Accounts" (fn [] (scr/route-to! this AccountList)))
          (nav-button "New Account" (fn [] (form/create! this AccountForm)))
          (dom/span {:data-rad-type "current-user"} (:account/email current-user))
          (nav-button "Log out" (fn [] (comp/transact! this [(login/logout {})])))
          (when busy? (dom/span {:data-rad-type "busy-indicator"} "Loading...")))
        (when route-denied?
          (dom/div {:data-rad-type "route-denied-modal"}
            (dom/p "You have unsaved changes.")
            (dom/button {:onClick (fn [] (scr/abandon-route-change! this))} "Stay")
            (dom/button {:onClick (fn [] (scr/force-continue-routing! this))} "Discard changes")))
        (dom/main
          (ui-routes routes))))
    ;; :checking — waiting for the server to say whether the session cookie is logged in
    (dom/div "Loading...")))
