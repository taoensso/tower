(ns cljs.taoensso.tower
  "EXPERIMENTAL ClojureScript support for Tower.
  PRE-alpha - almost certain to change."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str])
  (:require-macros [cljs.taoensso.tower.macros :as tower-macros]))

(def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
(def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj"))
