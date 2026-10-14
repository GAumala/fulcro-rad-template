(ns com.example.ui.routes
  "The routing statechart. There is no router component: `routing-chart` IS the router.
   Each page is a state in the chart, and `Routes` renders whichever route state is active."
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.statechart.application :as rad-app]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.statechart.report :as report]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
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
   go in the query string.

   With authentication, the chart is only started once someone is logged in (see `com.example.session`),
   so every route here is behind the login."
  (statechart {:initial :state/route-root}
    (scr/routing-regions
      (scr/routes {:id :state/root :routing/root `Routes}
        (scr/rstate {:route/target `LandingPage :route/segment "home"})
        (report/report-route-state {:route/target AccountList :route/segment "accounts"})
        (form/form-route-state {:route/target AccountForm :route/segment "account"})))))

(defn start-routing!
  "Starts the routing chart (once). In the browser, URL sync then routes to the URL the user arrived on."
  [app]
  (let [runtime (::app/runtime-atom app)]
    (when-not (::routing-started? @runtime)
      (swap! runtime assoc ::routing-started? true)
      (rad-app/start-routing! app routing-chart)
      ;; Keeps the browser URL and the routing chart in sync (and restores the route on page load)
      #?(:cljs (rad-app/install-url-sync! app)))))
