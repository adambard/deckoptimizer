(set-env!
  :source-paths #{"src/cljs" "src/cljc" "src/clj"}
  :resource-paths #{"resources"}
  :dependencies '[[com.cemerick/piggieback "0.2.1"]
                  [org.clojure/tools.nrepl "0.2.10" :scope "test"]
                  [adzerk/boot-cljs "0.0-3308-0" :scope "test"]
                  [adzerk/boot-cljs-repl "0.1.10-SNAPSHOT" :scope "test"]
                  [adzerk/boot-reload    "0.3.1" :scope "test"]
                  [pandeiro/boot-http "0.6.3" :scope "test"]
                  [reagent "0.5.1"]
                  [org.clojure/clojure "1.7.0"]

                  ; Cljs
                  [org.clojure/clojurescript "1.7.28"]
                  [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                  [cljs-ajax "0.3.14"]

                  ; Clj
                  [environ "1.0.1"]
                  [boot-environ "1.0.1"]
                  [clj-http "2.0.0"]
                  [com.cemerick/friend "0.2.1"]
                  [ring "1.3.0"]
                  [ring/ring-json "0.3.1"]
                  [compojure "1.3.4"]
                  [javax.servlet/servlet-api "2.5"]
                  [http-kit "2.1.18"]])

(require
  '[adzerk.boot-cljs      :refer :all]
  '[adzerk.boot-cljs-repl :refer :all]
  '[pandeiro.boot-http :refer [serve]]
  '[adzerk.boot-reload :refer :all]
  '[environ.core :refer [env]]
  '[environ.boot :refer [environ]])


(deftask dev []
  (comp
    (serve :handler 'deckoptimizer.trackobot-proxy/app :reload true)
    ;(serve)
    (watch)
    (reload)
    (cljs-repl)
    (cljs :optimizations :none)))

(deftask build []
  (cljs :optimizations :whitespace))

(deftask run []
  (comp
    (wait)
    (serve :handler 'deckoptimizer.trackobot-proxy/app :port (Integer/parseInt (env :port "3000")))))
