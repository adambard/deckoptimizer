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
(defonce *filters* (r/atom {:class-filter "Any"
                            :deck-filter ""
                            :mode-filter "Ranked"
                            :n-games 100
                            :vs-class-filter "Any"
                            :vs-deck-filter ""
                            }))
(defonce *games* (r/atom []))

(defn log-> [it]
  (js/console.log it)
  it)

(defn lower [s]
  (if (not (empty? s))
    (.toLowerCase s)))

(defonce ALL-CLASSES ["Any" "Druid" "Hunter" "Mage" "Paladin" "Priest" "Rogue" "Shaman" "Warlock" "Warrior"])
(defonce ALL-MODES ["Any" "Ranked" "Casual" "Arena"])

(defn apply-filter [filter-v v]
  (if (and (not (empty? filter-v))
           (not (= "Any" filter-v)))
    (= (lower filter-v) (lower v))
    true))

(defn filter-game [filters game]
  (and
    (apply-filter (:class-filter filters) (:hero game))
    (apply-filter (:mode-filter filters) (:mode game))
    (apply-filter (:deck-filter filters) (:hero_deck game))
    (apply-filter (:vs-class-filter filters) (:opponent game))
    (apply-filter (:vs-deck-filter filters) (:opponent_deck game))))

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

(defn filter-text-field [label field-key]
  (let [v (get @*filters* field-key)
        field-name (name field-key)]
    [:div.field
     [:label {:for field-name} label]
     [:input {:name field-name
              :id field-name
              :type "text"
              :value v
              :on-change (update-filter! field-key)}]]))

(defn filter-select-field [label field-key options]
  (let [v (get @*filters* field-key)
        field-name (name field-key)]
    [:div.field
     [:label {:for field-name} label]
     [:select {:name field-name :id field-name :value v :on-change (update-select! field-key)}
      (for [cls options]
        [:option {:value cls :key cls} cls])]]))

(defn load-form []
  [:form {:id "filter-form" :on-submit handle-submit}
   [:div.field
      [:label {:for "n-games"} "# of games to load"]
      [:input {:name "n-games" :id "n-games" :type "number" :value (:n-games @*filters*) :on-change (update-number! :n-games)}]]
   [:input {:type "submit" :value "Update Data"}]])

(defn filter-form []
  (let [{class-filter :class-filter
         deck-filter :deck-filter
         mode-filter :mode-filter
         n-games :n-games
         vs-class-filter :vs-class-filter
         vs-deck-filter :vs-deck-filter} @*filters*]
    [:div.filter-form
     [filter-select-field "Game Mode" :mode-filter ALL-MODES]
     [filter-select-field "My Class" :class-filter ALL-CLASSES]
     [filter-text-field "My Deck" :deck-filter]

     [filter-select-field "Opponent's Class" :vs-class-filter ALL-CLASSES]
     [filter-text-field "Opponent's Deck" :vs-deck-filter]]
     ))


(defn cards-table []
  (let [n-games (:n-games @*filters*)
        scoped-games (->> @*games*
                          (filter (partial filter-game @*filters*))
                          (sort-games)
                          (take n-games))
        overall-ratio (* 100 (/ (count (filter won? scoped-games))
                                (count scoped-games)))
        ]
    [:div.table
     [:p (str "Checking " (count scoped-games) " games matching filter in last " (count @*games*) " games. "
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
                            (swap! *appdata* assoc
                                   :authenticated true
                                   :username (:username data))
                            (populate-data *games* @*filters*)
                            )
                 :error-handler (fn [data]
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

(defn debug-component []
  [:ul
  (for [game @*games*]
    [:li {:key (:id game)}
     (str (:id game) " "
          (:hero game) " "
          (:hero_deck game) " "
          (:opponent game) " "
          (:opponent_deck game)
          )
          [:pre (js/JSON.stringify (clj->js game))]])])

(defn app []
  (let [logged-in (:authenticated @*appdata*)]
    [:div#main
     (if logged-in
       [:div.container
        [:div.top (str "Welcome, " (:username @*appdata*) ". ")
         [:a {:href "/logout"}  "Log Out"]]
        [:h1 "McHammar's Deck Evolver!"]
        [load-form]
        [filter-form]
        [cards-table]
        ;[debug-component]
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
