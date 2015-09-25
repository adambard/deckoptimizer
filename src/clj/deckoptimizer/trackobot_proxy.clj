(ns deckoptimizer.trackobot-proxy
  (:require
    [clj-http.client :as client]
    [ring.middleware.json :refer [wrap-json-response]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.session :refer [wrap-session]]
    [compojure.core :refer [GET defroutes]]
    [compojure.route :as route]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.util.response :as resp]
    [cemerick.friend :as friend]
    [cemerick.friend.workflows :refer [make-auth]]
    )
  )



(defn get-history [{{{[username token] :current} ::friend/identity} :session
                    {page "page"} :params :as req}]
  (prn req)
  (try
    (-> 
      (client/get (str "https://trackobot.com/profile/history.json?username=" username "&token=" token "&page=" (or page 0))
                  {:as :json})

      (select-keys [:body :status])
      (assoc :headers {}))
    (catch Exception e
      {:status 500
       :body (:body e)
       :headers {"X-ERROR" (str e)} }) ))


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


(comment
  ((wrap-static-session api) {:request-method :get :uri "/history.json" :params {"page" "1"}})
  (run-jetty #'app {:port 5000 :join? false})
  (meta (app {:uri "/login" :request-method :get :params {"username" "white-succubus-6699"
                                                         "token" "awGDK5iVNEC_TpT_iXnQ"
                                                    }
        :headers {"Cookie" "ring-session=528fb5a9-2d1f-482c-9f87-754b58334764;Path=/"}
        }))
  )

