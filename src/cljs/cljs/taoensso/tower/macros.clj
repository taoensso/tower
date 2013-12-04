(ns cljs.taoensso.tower.macros "EXPERIMENTAL."
  {:author "Peter Taoussanis"}
  (:require [taoensso.tower :as tower]))

(defmacro testmacro [x] `(str "hello"))
