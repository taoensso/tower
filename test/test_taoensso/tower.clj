(ns test-taoensso.tower
  (:use [clojure.test]
        [taoensso.tower :as tower :only (with-locale with-scope t)]))

;; TODO Tests

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
(deftest test-translations)