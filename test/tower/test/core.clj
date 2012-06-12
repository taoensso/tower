(ns tower.test.core
  (:use [clojure.test]
        [tower.core :as tower :only (with-i18n with-locale with-scope)]))

;; TODO Tests (help would be welcome!!)

(defmacro wza [& body] `(with-locale :en_ZA ~@body))

(deftest test-compare)

(deftest test-number-formatting
  (is (= "1,000.1"    (wza (tower/format-number   1000.10))))
  (is (= "1,000"      (wza (tower/format-integer  1000))))
  (is (= "314%"       (wza (tower/format-percent  (float (/ 22 7))))))
  (is (= "R 1,000.10" (wza (tower/format-currency 1000.10)))))

(deftest test-number-parsing)

(deftest test-dt-formatting)
(deftest test-dt-parsing)

(deftest test-text-formatting)

(deftest test-dictionary-compiler)

;; TODO Use default :dictionary for testing

(deftest test-translations)