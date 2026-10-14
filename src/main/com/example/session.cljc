(ns com.example.session
  "Client-side session: who is logged in, and the switch between the login page and the app.

   Routing only starts once someone is logged in. In the browser, URL sync then routes to whatever
   URL the user arrived on, so deep links work through the login page."
  (:require
    [com.example.ui.routes :refer [routing-chart]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.rad.statechart.application :as rad-app]))

(defn- start-routing! [app]
  (let [runtime (::app/runtime-atom app)]
    (when-not (::routing-started? @runtime)
      (swap! runtime assoc ::routing-started? true)
      (rad-app/start-routing! app routing-chart)
      ;; Keeps the browser URL and the routing chart in sync (and restores the route on page load)
      #?(:cljs (rad-app/install-url-sync! app)))))

(defn logged-in! [app user]
  (swap! (::app/state-atom app) assoc :session/current-user user :ui/auth-status :logged-in)
  (start-routing! app))

(defn logged-out! [app]
  (swap! (::app/state-atom app) assoc :session/current-user nil :ui/auth-status :logged-out))

(defn check-session!
  "Asks the server who is logged in (the session cookie decides), then shows the app or the login page."
  [app]
  (df/load! app :session/current-user nil
    {:post-action  (fn [{:keys [app state]}]
                     (if-let [user (:session/current-user @state)]
                       (logged-in! app user)
                       (logged-out! app)))
     :error-action (fn [{:keys [app]}] (logged-out! app))}))
