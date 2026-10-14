(ns com.example.ui.root
  "App UI root and the routing statechart.

   There is no router component: `routing-chart` IS the router. Each page is a
   state in the chart, and `Routes` renders whichever route state is active."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]))

(defsc LandingPage [this props]
  {:query         [:ui/placeholder]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {:ui/placeholder true}}
  (dom/div {:data-rad-type "landing-page"}
    (dom/h2 "Welcome to your new Application!")))

;; Renders the active route. `:preserve-dynamic-query? true` is required: routing swaps this query at runtime.
(defsc Routes [this props]
  {:query                   [:ui/placeholder]
   :preserve-dynamic-query? true
   :initial-state           {}
   :ident                   (fn [] [:component/id ::Routes])}
  (scr/ui-current-subroute this comp/factory))

(def ui-routes (comp/factory Routes))

(def routing-chart
  "Every navigable page must appear here. Forms use `form-route-state` (starts the form's
   statechart on entry, guards against leaving with unsaved changes); reports use
   `report-route-state`; anything else is a plain `scr/rstate`.

   `:route/segment` is the URL path for the state. It defaults to the component's name
   (e.g. /AccountList), so set it for nice URLs. Route params (form `:id`, report controls)
   go in the query string."
  (statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :state/root :routing/root `Routes}
        (scr/rstate {:route/target `LandingPage :route/segment "home"})
        (report/report-route-state {:route/target AccountList :route/segment "accounts"})
        (form/form-route-state {:route/target AccountForm :route/segment "account"})))))

(defn nav-button [label on-click]
  (dom/button {:data-rad-type "nav-button" :onClick on-click} label))

(defsc Root [this {::app/keys [active-remotes]
                   :ui/keys   [ready? routes]}]
  {:query         [:ui/ready?
                   {:ui/routes (comp/get-query Routes)}
                   ;; Makes Root re-render when the routing chart changes state
                   [::sc/session-id '_]
                   ::app/active-remotes]
   :initial-state {:ui/ready? false
                   :ui/routes {}}}
  (let [busy?         (seq active-remotes)
        ;; The routing chart enters :routing-info/open when an active route (e.g. a dirty form) vetoes navigation
        route-denied? (contains? (scf/current-configuration this scr/session-id) :routing-info/open)]
    (if-not ready?
      (dom/div "Loading...")
      (dom/div
        (dom/nav {:data-rad-type "nav-bar"}
          (nav-button "Home" (fn [] (scr/route-to! this LandingPage)))
          (nav-button "Accounts" (fn [] (scr/route-to! this AccountList)))
          (nav-button "New Account" (fn [] (form/create! this AccountForm)))
          (when busy? (dom/span {:data-rad-type "busy-indicator"} "Loading...")))
        (when route-denied?
          (dom/div {:data-rad-type "route-denied-modal"}
            (dom/p "You have unsaved changes.")
            (dom/button {:onClick (fn [] (scr/abandon-route-change! this))} "Stay")
            (dom/button {:onClick (fn [] (scr/force-continue-routing! this))} "Discard changes")))
        (dom/main
          (ui-routes routes))))))
