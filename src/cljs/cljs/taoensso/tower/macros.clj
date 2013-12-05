(ns cljs.taoensso.tower.macros "EXPERIMENTAL."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [taoensso.tower :as tower]))

(defmacro dict-compile [dict] (tower/dict-compile dict))

(println "DEBUG - cljs.taoensso.tower.macros loaded: " {:en {:a "**Test**"}})
