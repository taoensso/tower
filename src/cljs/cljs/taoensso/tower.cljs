(ns cljs.taoensso.tower
  "EXPERIMENTAL ClojureScript support for Tower.
  PRE-alpha - almost certain to change."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str])
  (:require-macros [cljs.taoensso.tower.macros :as tower-macros]))

(defn echo [x] x) ; Just testing

(defn testfn [x] (tower-macros/testmacro x))

;; TODO Take dictionary from named resource and compile at compile-time?

(def example-tconfig
  {:dictionary
   {:ja "test_ja.clj"}})
