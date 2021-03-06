(ns deckoptimizer.trackobot-proxy
  (:gen-class)
  (:require
    [clj-http.client :as client]
    [ring.middleware.json :refer [wrap-json-response]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.session :refer [wrap-session]]
    [compojure.core :refer [GET ANY defroutes]]
    [compojure.route :as route]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.util.response :as resp]
    [cemerick.friend :as friend]
    [cemerick.friend.workflows :refer [make-auth]]
    [environ.core :refer [env]]
    )
  )



(defn get-history [{{{[username token] :current} ::friend/identity} :session
                    {page "page"} :params :as req}]
  (try
    (-> 
      (client/get (str "https://trackobot.com/profile/history.json?username=" username "&token=" token "&page=" (or page 0))
                  {:as :json})

      (select-keys [:body :status])
      (assoc :headers {}))
    (catch Exception e
      {:status 500
       :body (:body e)
       :headers {}})))


(defn login [{{{[username token] :current} ::friend/identity} :session}]
  (if (and username token)
    {:body {:username username :message "Ok"} :status 200 :headers {"Content-Type" "application/json"}}
    {:body {:message "Invalid api key or username"}
     :status 403
     :headers {"Content-Type" "application/json"}}))

(defroutes api
  (GET "/" [] (-> (resp/resource-response "index.html")
                  (resp/content-type "text/html")))
  (GET "/login" req (login req))
  (friend/logout
    (ANY "/logout" req (resp/redirect "/")))
  (GET "/history.json" req (friend/authorize #{::user} (get-history req)))
  (route/resources "/" {:root ""})
  )

(defn wrap-static-session [handler]
  (fn [req]
    (handler (-> req
                 (assoc-in [:session :token] "awGDK5iVNEC_TpT_iXnQ")
                 (assoc-in [:session :username] "white-succubus-6699")))))

; Auth

(defn api-key-workflow [{uri :uri params :params :as req}]
  (let [token (get params "token")
        username (get params "username")]
    (when (and (= uri "/login") username token)
      (make-auth {:identity [username token] :roles #{::user}}
                 {::friend/redirect-on-auth? false}))))

(def app
  (-> api
      (friend/authenticate {:workflows [api-key-workflow]
                            :unauthorized-handler login})
      (wrap-params)
      (wrap-session)
      (wrap-json-response)
      ))

(defn -main []
  (run-jetty #'app {:port (Integer/parseInt (env :port "3000"))}))


(comment
  ((wrap-static-session api)
   {:request-method :get :uri "/history.json" :params {"page" "1"}})
  )

