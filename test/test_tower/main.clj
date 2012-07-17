(ns test-tower.main
  (:import  [java.util Date])
  (:require [taoensso.tower.ring :as ring])
  (:use [clojure.test]
        [taoensso.tower :as tower :only (with-locale with-scope parse-number t set-config! config-defaults)]))


(use-fixtures :each
  (fn [f]
    (reset! tower/config tower/config-defaults)
    (f)
    (reset! tower/config tower/config-defaults)))

(defmacro wza [& body] `(with-locale :en_ZA ~@body))
(defmacro wus [& body] `(with-locale :en_US ~@body))
(defmacro wde [& body] `(with-locale :de_DE ~@body))

(deftest test-compare)

(deftest test-number-formatting
  (are [expected actual] (is (= expected (wza (tower/format-number actual))))
       "1,000.1"       1000.10
       "100,000,000.1" 100000000.10
       "1,000"         1000)

  (are [expected actual] (is (= expected (wus (tower/format-number actual))))
       "1,000.1"       1000.10
       "100,000,000.1" 100000000.10
       "1,000"         1000)

  (are [expected actual] (is (= expected (wde (tower/format-number actual))))
       "1.000,1"       1000.10
       "100.000.000,1" 100000000.10
       "1.000"         1000)

  (wus
    (is (= "1,000.1"     (tower/format-number   1000.10)))
    (is (= "1,000"       (tower/format-integer  1000)))
    (is (= "$1,000.10"   (tower/format-currency 1000.10))))

  (wde
    (is (= "1.000,1"     (tower/format-number   1000.10)))
    (is (= "1.000"       (tower/format-integer  1000)))
    (is (= "1.000,10 €"  (tower/format-currency 1000.10)))))

(deftest format-percent
  (are [expected actual] (is (= expected (wza (tower/format-percent actual))))
       "314%"   (float (/ 22 7))
       "200%"   2
       "50%"    (float (/ 1 2))
       "75%"    (float (/ 3 4))))

(deftest format-currency
  (are [expected actual] (is (= expected (wza (tower/format-currency actual))))
       "R 123.45" 123.45
       "R 12,345.00" 12345
       "R 123,456,789.00" 123456789
       "R 123,456,789.20" 123456789.20)

    (are [expected actual] (is (= expected (wus (tower/format-currency actual))))
       "$123.45" 123.45
       "$12,345.00" 12345
       "$123,456,789.00" 123456789
       "$123,456,789.20" 123456789.20)

    (are [expected actual] (is (= expected (wde (tower/format-currency actual))))
       "123,45 €" 123.45
       "12.345,00 €" 12345
       "123.456.789,00 €" 123456789
       "123.456.789,20 €" 123456789.20))

(deftest test-number-parsing
  (are [expected actual] (is (= expected (wza (tower/parse-number actual))))
       1000.01    "1000.01"
       1000.01    "1,000.01"
       1000000.01 "1,000,000.01"
       1.23456    "1.23456")

  (are [expected actual] (is (= expected (wus (tower/parse-number actual))))
       1000.01    "1000.01"
       1000.01    "1,000.01"
       1000000.01 "1,000,000.01"
       1.23456    "1.23456")

  (are [expected actual] (is (= expected (wde (tower/parse-number actual))))
       1000.01    "1000,01"
       1000.01    "1.000,01"
       1000000.01 "1.000.000,01"
       1.23456    "1,23456"))

(deftest test-currency-parsing
  (are [expected actual] (is (= expected (wza (tower/parse-currency actual))))
       123.45        "R 123.45"
       12345         "R 123,45"
       123456.01     "R 123,456.01"
       123456789.01  "R 123,456,789.01")

  (are [expected actual] (is (= expected (wus (tower/parse-currency actual))))
       123.45        "$123.45"
       12345         "$123,45"
       123456.01     "$123,456.01"
       123456789.01  "$123,456,789.01")

  (are [expected actual] (is (= expected (wde (tower/parse-currency actual))))
       123.45  "123,45 €"
       12345   "123.45 €"
       123456.01   "123.456,01 €"
       123456789.01   "123.456.789,01 €"))

(defn construct-date
  ([y m d]
     (construct-date y m d 0 0 0))
  ([y m d h mm s]
     (Date. (- y 1900) (- m 1) d h mm s)))

(deftest test-dt-formatting
  ;; Please refer to http://docs.oracle.com/javase/1.4.2/docs/api/java/util/Date.html to see why we substract 1900 from year and 1 from month
  ;; that's JDK format.
  (wus
    (are [expected y m d] (is (= expected (tower/format-date (construct-date y m d))))
         "Feb 1, 2012" 2012 2 1
         "Mar 25, 2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :short) (construct-date y m d))))
         "2/1/12" 2012 2 1
         "3/25/12" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :long) (construct-date y m d))))
         "February 1, 2012" 2012 2 1
         "March 25, 2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :full) (construct-date y m d))))
         "Wednesday, February 1, 2012" 2012 2 1
         "Sunday, March 25, 2012" 2012 3 25))

  (wza
    (are [expected y m d] (is (= expected (tower/format-date (construct-date y m d))))
         "01 Feb 2012" 2012 2 1
         "25 Mar 2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :short) (construct-date y m d))))
         "2012/02/01" 2012 2 1
         "2012/03/25" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :full) (construct-date y m d))))
         "Wednesday 01 February 2012" 2012 2 1
         "Sunday 25 March 2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :long) (construct-date y m d))))
         "01 February 2012" 2012 2 1
         "25 March 2012" 2012 3 25))

  (wde
    (are [expected y m d] (is (= expected (tower/format-date (construct-date y m d))))
         "01.02.2012" 2012 2 1
         "25.03.2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :short) (construct-date y m d))))
         "01.02.12" 2012 2 1
         "25.03.12" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :long) (construct-date y m d))))
         "1. Februar 2012" 2012 2 1
         "25. März 2012" 2012 3 25)

    (are [expected y m d] (is (= expected (tower/format-date (tower/style :full) (construct-date y m d))))
         "Mittwoch, 1. Februar 2012" 2012 2 1
         "Sonntag, 25. März 2012" 2012 3 25)))

(deftest t-dt-parsing
  (wus
    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date source)))
         2012 2 1  "Feb 1, 2012"
         2012 3 25 "Mar 25, 2012")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :short) source)))
         2012 2 1  "2/1/12"
         2012 3 25 "3/25/12")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :long) source)))
         2012 2 1   "February 1, 2012"
         2012 3 25  "March 25, 2012" )

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :full) source)))
         2012 2 1 "Wednesday, February 1, 2012"
         2012 3 25 "Sunday, March 25, 2012"))


  (wza
    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date source)))
         2012 2 1  "01 Feb 2012"
         2012 3 25 "25 Mar 2012")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :short) source)))
         2012 2 1  "2012/02/01"
         2012 3 25 "2012/03/25" )

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :full) source)))
         2012 2 1  "Wednesday 01 February 2012"
         2012 3 25 "Sunday 25 March 2012")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :long) source)))
         2012 2 1  "01 February 2012"
         2012 3 25 "25 March 2012" ))

  (wde
    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date source)))
         2012 2 1  "01.02.2012"
         2012 3 25 "25.03.2012")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :short) source)))
         2012 2 1  "01.02.12"
         2012 3 25 "25.03.12")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :long) source)))
         2012 2 1  "1. Februar 2012"
         2012 3 25 "25. März 2012")

    (are [y m d source] (is (= (construct-date y m d) (tower/parse-date (tower/style :full) source)))
         2012 2 1  "Mittwoch, 1. Februar 2012"
         2012 3 25 "Sonntag, 25. März 2012")))

(deftest t-parse-locale
  (is (= "en" (.toString (tower/parse-Locale :en))))
  (is (= "en_US" (.toString (tower/parse-Locale :en_US))))
  (is (= "en_GB" (.toString (tower/parse-Locale :en_GB))))
  (is (= "de" (.toString (tower/parse-Locale :de))))
  (is (= "de_DE" (.toString (tower/parse-Locale :de_DE)))))

(deftest test-translations
  (testing "When dictionary is still empty"
    (is (= "**:foo/bar**" (wza (tower/t :foo/bar)))))

  (testing "When items are present in dictionary"
    (tower/set-config! [:dictionary] {:en_ZA { :foo { :bar "Test Word" }}})
    (is (= "Test Word" (wza (tower/t :foo/bar))))
    (is (= "**:foo**" (wde (tower/t :foo))))
    (is (= "**:foo/bar**" (wde (tower/t :foo/bar)))))
  (testing "Decorated values"
    (tower/set-config! [:dictionary] {:en_ZA { :foo { :decorated { :bar.html "<tag>"
                                                                   :bar.md   "**strong**"} }}})
    (is (= "<tag>" (wza (tower/t :foo/decorated/bar.html))))
    (is (= "<strong>tag</strong>" (wza (tower/t :foo/decorated/bar.md))))))

(deftest test-text-formatting)
(deftest test-translations)