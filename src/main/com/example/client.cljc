(ns com.example.client
  "Client entry point. CLJS: the browser app (`init` is shadow-cljs's :init-fn).
   CLJ: `start!` is reused by headless tests (see src/test) to boot the very same app."
  (:require
    #?@(:cljs [[com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
               [fulcro.inspect.tool :as inspect]])
    [com.example.application :refer [SPA]]
    [com.example.session :as session]                       ; AUTH: remove
    [com.example.ui.routes :as routes]
    [com.example.ui.render.plugin]                          ; registers the RAD renderers
    [com.example.ui.root :refer [Root]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.networking.http-remote :as net]
    [com.fulcrologic.rad.statechart.application :as rad-app]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [taoensso.timbre :as log]))

(defn start!
  "Installs statecharts and checks the session; routing starts once someone is logged in
   (see `com.example.session`). `:event-loop?` is `true` in the browser and `:immediate` in CLJ tests."
  ([app] (start! app {}))
  ([app {:keys [event-loop?] :or {event-loop? true}}]
   ;; default time zone (can be changed at login for given user)
   (datetime/set-timezone! "America/Los_Angeles")
   (rad-app/install-statecharts! app {:event-loop? event-loop?})
   ;; AUTH: replace the next line with (routes/start-routing! app)
   (session/check-session! app)
   app))

#?(:cljs
   (defn- remote []
     (net/fulcro-http-remote
       {:url                "/api"
        :request-middleware (cond-> (net/wrap-fulcro-request)
                              (exists? js/fulcro_network_csrf_token)
                              (net/wrap-csrf-token js/fulcro_network_csrf_token))})))

#?(:cljs
   (defn refresh
     "Hot code reload."
     []
     (log/info "Reloading")
     (comp/refresh-dynamic-queries! @SPA)
     (app/mount! @SPA Root "app")))

#?(:cljs
   (defn init []
     ;; makes js console logging a bit nicer
     (log/merge-config! {:output-fn prefix-output-fn
                         :appenders {:console (console-appender)}})
     (log/info "Starting App")
     (let [app (rad-app/fulcro-rad-app {:remotes {:remote (remote)}})]
       (reset! SPA app)
       (inspect/add-fulcro-inspect! app)                    ; no-op unless Fulcro Inspect is enabled
       (app/mount! app Root "app")
       (start! app))))
