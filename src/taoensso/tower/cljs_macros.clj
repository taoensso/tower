(ns taoensso.tower.cljs-macros
  {:author "Peter Taoussanis"}
  (:require [taoensso.tower :as tower]))

(defmacro dict-compile
  "Tower's standard dictionary compiler, as a compile-time macro."
  [dict] (tower/dict-compile dict))

(defmacro with-tscope [translation-scope & body]
  `(binding [taoensso.tower/*tscope* ~translation-scope]
     ~@body))
