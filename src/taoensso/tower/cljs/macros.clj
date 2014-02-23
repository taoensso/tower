 (ns taoensso.tower.cljs.macros
   {:author "Peter Taoussanis"}
   (:require [taoensso.tower :as tower]))

(defmacro dict-compile
  "Tower's standard dictionary compiler, as a compile-time macro."
  [dict] (tower/dict-compile dict))

(defmacro with-locale [loc & body]
  `(binding [taoensso.tower.cljs/*locale* (taoensso.tower.cljs/locale ~loc)]
     ~@body))

(defmacro with-tscope [translation-scope & body]
  `(binding [taoensso.tower.cljs/*tscope* ~translation-scope]
     ~@body))
