(ns taoensso.tower.tests.main
  (:require [expectations   :as test  :refer :all]
            [taoensso.tower :as tower :refer ()]))

(defn- before-run {:expectations-options :before-run} [])
(defn- after-run  {:expectations-options :after-run}  [])

(expect true) ; TODO Add tests (PRs welcome!)