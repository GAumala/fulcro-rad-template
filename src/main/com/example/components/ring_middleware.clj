(ns com.example.components.ring-middleware
  (:require
    [clojure.string :as str]
    [com.example.components.config :as config]
    [com.example.components.parser :as parser]
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [hiccup.page :refer [html5]]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [ring.util.response :as resp]))

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
      (server/handle-api-request (:transit-params request)
        (fn [query]
          (parser/parser {:ring/request request}
            query)))
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

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config/config)]
    (-> not-found-handler
      (wrap-api "/api")
      (server/wrap-transit-params {})
      (server/wrap-transit-response {})
      (wrap-html-routes)
      (wrap-defaults defaults-config))))

