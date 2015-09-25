(ns deckoptimizer.core
  (:require [reagent.core :as r]
            [ajax.core :refer [GET]]
            [clojure.set :as sets]
            [goog.string :as gstring]
            )
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]]
    )
  )

(defonce base-url "/history.json?page=")

(defonce *appdata* (r/atom {:authenticated false
                            :username nil}))
(defonce *filters* (r/atom {:class-filter "Druid"
                            :deck-filter ""
                            :n-games 100}))
(defonce *games* (r/atom []))

(defonce ALL-CLASSES ["Druid" "Hunter" "Mage" "Paladin" "Priest" "Rogue" "Shaman" "Warlock" "Warrior"])

(defn filter-game [filters game]
  (and
    (= (:hero game) (get filters :class-filter))
    (if (not (empty? (:deck-filter filters)))
      (= (:hero_deck game) (get filters :deck-filter))
      true)))

(defn populate-data [games-atom filters]
  (reset! games-atom [])

  (doseq [p (range (inc (/ (get filters :n-games) 15)))]
    (GET "/history.json"
         {:params {:page (inc p)}
          :response-format :json
          :keywords? true
          :handler (fn [data]
                     (->> data
                          (:history)
                          (filter (partial filter-game filters))
                          (swap! games-atom concat)))})))

; Data Crunching
(defn won? [game-data]
  (= (:result game-data) "win"))

(defn annotate-with-cards-played [game]
  (->> game
       (:card_history)
       (filter #(= (:player %) "me"))
       (map #(get-in % [:card :name]))
       (set)
       (assoc game :cards-played)
       ))


(defn card-stats [games card]
  (let [games-with-card (filter #(get (:cards-played %) card) games)
        win (count (filter won? games-with-card))
        lose (or (- (count games-with-card) win) 1)
        naive-ratio (/ (float win) (+ win lose))
        overall-ratio (/ (count (filter won? games)) (count games))
        unplayed (- (count games) (count games-with-card))
        ]
    {:name card
     :win win
     :lost lose
     :unplayed unplayed
     :deviation (- naive-ratio overall-ratio)
     :naive-ratio naive-ratio
     :ratio-with-unplayed (/ (float win) (+ win lose unplayed))}))


(defn all-card-stats [games]
  (let [games (map annotate-with-cards-played games)
        all-cards (apply sets/union (map :cards-played games))]
    (->> all-cards
         (map (partial card-stats games))
         (sort-by :deviation)
         (reverse))))

(defn sort-games [games]
  (->> games
      (sort-by #(js/Date. (:added %)))))

(defn form-val [id]
  (aget (js/document.getElementById id) "value"))

(defn handle-submit [e]
  (.preventDefault e)
  (js/console.log @*filters*)
  (populate-data *games* @*filters*)
  false
  )

(defn update-filter! [k]
  (fn [e]
    (swap! *filters* assoc k (-> e .-target .-value))))

(defn update-number! [k]
  (fn [e]
    (swap! *filters* assoc k (js/parseInt (-> e .-target .-value)))))

(defn update-select! [k]
  (fn [e]
    (let  [el (.-target e)
           v (.-value (aget (.-options el) (.-selectedIndex el)))]
      (swap! *filters* assoc k v))))

(defn input-form []
  (js/console.log "Render input form")
  (let [{class-filter :class-filter
         deck-filter :deck-filter
         n-games :n-games} @*filters*]
    [:form {:id "filter-form" :on-submit handle-submit}

     [:label {:for "deck-filter"} "Filter by Class"]
     [:select {:name "class-filter" :id "class-filter" :value class-filter :on-change (update-select! :class-filter)}
      (for [cls ALL-CLASSES]
        [:option {:value cls :key cls} cls])]

     [:label {:for "deck-filter"} "Filter by Deck Name"]
     [:input {:name "deck-filter" :id "deck-filter" :type "text" :value deck-filter :on-change (update-filter! :deck-filter)}]

     [:label {:for "n-games"} "Number of Games to check"]
     [:input {:name "n-games" :id "n-games" :type "number" :value n-games :on-change (update-number! :n-games)}]

     [:input {:type "submit" :value "Update Data"}]]))


(defn cards-table []
  (let [n-games (:n-games @*filters*)
        scoped-games (->> @*games*
                          (sort-games)
                          (take n-games))
        overall-ratio (* 100 (/ (count (filter won? scoped-games))
                                (count scoped-games)))
        ]
    [:div.table
     [:p (str "Checking " (count scoped-games) " games matching filter in last " n-games " games. "
              "Overall win ratio: " (.toFixed overall-ratio 1) "%")] 
     [:table
      [:thead
       [:tr
        [:th.card-name "Card Name"]
        [:th "Wins"]
        [:th "Losses"]
        [:th "Unplayed"]
        [:th "Win %"]
        [:th "Deviation %"]
        [:th "Win % (including unplayed)"]]]
      [:tbody
       (for [card (all-card-stats scoped-games)]
         [:tr {:key (:name card)}
          [:td.card-name (:name card)]
          [:td (:win card)]
          [:td (:lost card)]
          [:td (:unplayed card)]
          [:td (.toFixed (* 100 (:naive-ratio card)) 1)]
          [:td {:class (cond
                         (< (:deviation card) -0.05) "bad"
                         (> (:deviation card) 0.05) "good"
                         :else "neutral")}
           (.toFixed (* 100 (:deviation card)) 1)]
          [:td (.toFixed (* 100 (:ratio-with-unplayed card)) 1)]])]]]))


(defn check-login [params]
  (GET "/login" {:params params
                 :keywords? true
                 :response-format :json
                 :handler (fn [data]
                            (js/console.log "OK!" data)
                            (swap! *appdata* assoc
                                   :authenticated true
                                   :username (:username data))
                            (populate-data *games* @*filters*)
                            (js/console.log "UPDATED AUTHENTICATED" @*appdata*)
                            )
                 :error-handler (fn [data]
                            (js/console.log "ERROR!" data)
                            (swap! *appdata* assoc :authenticated false))
                 }))

(defn login-form []
  [:div#login-modal
   [:h2 "Please Log In"]
   [:label {:for "username"} "Trackobot Username"]
   [:input {:type "text" :name "username" :id "username"}]
   [:label {:for "api_key"} "Trackobot API Key"]
   [:input {:type "text" :name "token" :id "token"}]
   [:button {:on-click (fn [] (check-login {:username (form-val "username")
                                            :token (form-val "token")}))} "Log In"]]
  )

(defn app []
  (let  [logged-in (:authenticated @*appdata*)]
  [:div#main
   (if logged-in
     [:div.container
      [:div.top (str "Welcome, " (:username @*appdata*))]
      [:h1 "McHammar's Deck Evolver!"]
      [input-form]
      [cards-table]
      ]
     [login-form])]))

(check-login {})

(r/render-component [app] (js/document.getElementById "app"))

(comment
  (let [x (chan)]
    x
    )
(.send xhr (str base-url p) )
  (try
   (fetch-history-page 0) 
   (catch Exception e (js/console.log e))
    )
  (.get xhr (str base-url "1"))
  (let [c (fetch-history-page 0)]
    (go (js/console.log (<! c)))
    )
  )
