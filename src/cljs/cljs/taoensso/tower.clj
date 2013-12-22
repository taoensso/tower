(ns cljs.taoensso.tower ; macros
  {:author "Peter Taoussanis"}
  (:require [taoensso.tower :as tower]))

(defmacro dict-compile
  "Tower's standard dictionary compiler, as a compile-time macro."
  [dict] (tower/dict-compile dict))

(defmacro with-locale [loc & body]
  `(binding [cljs.taoensso.tower/*locale* (cljs.taoensso.tower/locale ~loc)]
     ~@body))

(defmacro with-tscope [translation-scope & body]
  `(binding [cljs.taoensso.tower/*tscope* ~translation-scope]
     ~@body))
