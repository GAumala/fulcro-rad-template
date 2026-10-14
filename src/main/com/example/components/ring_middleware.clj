(ns com.example.components.ring-middleware
  (:require
    [clojure.string :as str]
    [com.example.components.auth :as auth]
    [com.example.components.config :as config]
    [com.example.components.parser :as parser]
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [hiccup.page :refer [html5]]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.util.response :as resp]
    [taoensso.timbre :as log])
  (:import
    (java.security SecureRandom)
    (java.util HexFormat)))

(defn index [csrf-token]
  (html5 {:lang "en"}
    [:head
     [:title "Application"]
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"}]
     ;; Add your stylesheet(s) here, e.g. a compiled Tailwind/DaisyUI build:
     ;; [:link {:href "/css/main.css" :rel "stylesheet"}]
     [:link {:rel "shortcut icon" :href "data:image/x-icon;," :type "image/x-icon"}]
     [:script (str "var fulcro_network_csrf_token = '" csrf-token "';")]]
    [:body
     ;; Initial content until the client mounts (avoids a blank flash while JS loads)
     [:div#app "Loading..."]
     [:script {:src "/js/main/main.js"}]]))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      ;; Mutations (login/logout) can't return a Ring response, so they leave the new session here
      (let [session-update (volatile! ::unchanged)
            response       (server/handle-api-request (:transit-params request)
                             (fn [query]
                               (parser/parser {:ring/request          request
                                               ::auth/session-update session-update}
                                 query)))]
        (cond-> response
          (not= ::unchanged @session-update) (assoc :session @session-update)))
      (handler request))))

(def not-found-handler
  (fn [req]
    {:status 404
     :body   {}}))

(defn wrap-html-routes [ring-handler]
  ;; We want to default to the index since HTML5 routes will come here
  (fn [{:keys [uri anti-forgery-token] :as req}]
    (if (or (str/starts-with? uri "/api")
          (str/starts-with? uri "/js"))
      (ring-handler req)
      (-> (resp/response (index anti-forgery-token))
        (resp/content-type "text/html")))))

(defn- session-key
  "16-byte key for the encrypted session cookie, from the 32 hex chars in `:key` (SESSION_KEY in prod).
   Without one, a random key is used: fine in dev, but every restart logs everybody out."
  ^bytes [{:keys [key allow-ephemeral?]}]
  (cond
    (seq key) (let [bs (.parseHex (HexFormat/of) key)]
                (when-not (= 16 (alength bs))
                  (throw (ex-info "Session key must be 32 hex characters (16 bytes). Generate one with `openssl rand -hex 16`." {})))
                bs)
    allow-ephemeral? (do (log/warn "No session key configured; sessions will not survive a restart.")
                         (let [bs (byte-array 16)] (.nextBytes (SecureRandom.) bs) bs))
    :else (throw (ex-info "No session key configured. Set SESSION_KEY (generate with `openssl rand -hex 16`)." {}))))

(defn- session-config
  "Encrypted cookie sessions: nothing stored server side, so sessions survive restarts and work across instances."
  [{:keys [secure-cookies?] :as cfg}]
  {:store        (cookie-store {:key (session-key cfg)})
   :cookie-name  "app-session"
   :cookie-attrs {:http-only true
                  :same-site :lax
                  ;; Unset (nil) counts as true, so production is secure unless explicitly disabled
                  :secure    (not (false? secure-cookies?))}})

(defstate middleware
  :start
  (let [defaults-config (assoc (:ring.middleware/defaults-config config/config)
                          :session (session-config (::auth/session config/config)))]
    (-> not-found-handler
      (wrap-api "/api")
      (server/wrap-transit-params {})
      (server/wrap-transit-response {})
      (wrap-html-routes)
      (wrap-defaults defaults-config))))

