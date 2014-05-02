(ns taoensso.tower.tests.main
  (:require [expectations   :as test  :refer :all]
            [taoensso.tower :as tower :refer (with-tscope)])
  (:import  [java.util Date]))

(comment (test/run-tests '[taoensso.tower.tests.main]))

(defn- before-run {:expectations-options :before-run} [])
(defn- after-run  {:expectations-options :after-run}  [])

;;;; Localization

(given [s n] (expect s (tower/fmt :en-ZA n))
  "1,000.1"       1000.10
  "100,000,000.1" 100000000.10
  "1,000"         1000)

(given [s n] (expect s (tower/fmt :en-US n))
  "1,000.1"       1000.10
  "100,000,000.1" 100000000.10
  "1,000"         1000)

(given [s n] (expect s (tower/fmt :de-DE n))
  "1.000,1"       1000.10
  "100.000.000,1" 100000000.10
  "1.000"         1000)

(expect "1,000.1"    (tower/fmt :en-US 1000.10))
(expect "1,000"      (tower/fmt :en-US 1000.10 :integer))
(expect "$1,000.10"  (tower/fmt :en-US 1000.10 :currency))

(expect "1.000,1"    (tower/fmt :de-DE 1000.10))
(expect "1.000"      (tower/fmt :de-DE 1000.10 :integer))
(expect "1.000,10 €" (tower/fmt :de-DE 1000.10 :currency))

(given [s n] (expect s (tower/fmt :en-ZA n :percent))
  "314%"   (float (/ 22 7))
  "200%"   2
  "50%"    (float (/ 1 2))
  "75%"    (float (/ 3 4)))

(given [s n] (expect s (tower/fmt :en-ZA n :currency))
  "R 123.45"         123.45
  "R 12,345.00"      12345
  "R 123,456,789.00" 123456789
  "R 123,456,789.20" 123456789.20)

(given [s n] (expect s (tower/fmt :en-US n :currency))
  "$123.45"         123.45
  "$12,345.00"      12345
  "$123,456,789.00" 123456789
  "$123,456,789.20" 123456789.20)

(given [s n] (expect s (tower/fmt :de-DE n :currency))
  "123,45 €"         123.45
  "12.345,00 €"      12345
  "123.456.789,00 €" 123456789
  "123.456.789,20 €" 123456789.20)

(given [n s] (expect n (tower/parse :en-ZA s))
  1000.01    "1000.01"
  1000.01    "1,000.01"
  1000000.01 "1,000,000.01"
  1.23456    "1.23456")

(given [n s] (expect n (tower/parse :en-US s))
  1000.01    "1000.01"
  1000.01    "1,000.01"
  1000000.01 "1,000,000.01"
  1.23456    "1.23456")

(given [n s] (expect n (tower/parse :de-DE s))
  1000.01    "1000,01"
  1000.01    "1.000,01"
  1000000.01 "1.000.000,01"
  1.23456    "1,23456")

(given [n s] (expect n (tower/parse :en-ZA s :currency))
  123.45        "R 123.45"
  12345         "R 123,45"
  123456.01     "R 123,456.01"
  123456789.01  "R 123,456,789.01")

(given [n s] (expect n (tower/parse :en-US s :currency))
  123.45        "$123.45"
  12345         "$123,45"
  123456.01     "$123,456.01"
  123456789.01  "$123,456,789.01")

(given [n s] (expect n (tower/parse :de-DE s :currency))
  123.45       "123,45 €"
  12345        "123.45 €"
  123456.01    "123.456,01 €"
  123456789.01 "123.456.789,01 €")

(defn test-dt [y m d] (Date. (Date/UTC (- y 1900) (- m 1) d 0 0 0)))

(given [s y m d] (expect s (tower/fmt :en-ZA (test-dt y m d)))
  "01 Feb 2012" 2012 2 1
  "25 Mar 2012" 2012 3 25)

(given [s y m d] (expect s (tower/fmt :en-US (test-dt y m d)))
  "Feb 1, 2012"  2012 2 1
  "Mar 25, 2012" 2012 3 25)

(given [s y m d] (expect s (tower/fmt :de-DE (test-dt y m d)))
  "01.02.2012" 2012 2 1
  "25.03.2012" 2012 3 25)

(def pt (tower/make-t tower/example-tconfig))

;;;; Translations
;;; (pt :en-US [:example/foo :example/bar])) searches:
;; :example/foo in the :en-US locale.
;; :example/bar in the :en-US locale.
;; :example/foo in the :en locale.
;; :example/bar in the :en locale.
;; :example/foo in the fallback locale.
;; :example/bar in the fallback locale.
;; :missing in any of the above locales.

;;; Basic locale selection & fallback
(expect ":en :example/foo text"    (pt :en    :example/foo)) ; :en
(expect ":en-US :example/foo text" (pt :en-US :example/foo)) ; :en-US
(expect ":en :example/foo text"    (pt :en-GB :example/foo)) ; :en-GB -> :en
(expect ":de :example/foo text"    (pt :zh-CN :example/foo)) ; :zh-CN -> :zh -> fb-loc
(expect ":ja 日本語"               (pt :ja    :example/foo)) ; External resource
(expect ":ja 日本語"               (pt :ja-JP :example/foo)) ; :ja-JP -> :ja

(expect ":en-US :example/foo text" (pt :jvm-default :example/foo)) ; not= fb-loc

;;; Scoping
(expect ":en :example/foo text"     (pt :en :example/foo))
(expect ":en :example.bar/baz text" (pt :en :example.bar/baz))
(expect (pt :en :example/foo)     (with-tscope :example     (pt :en :foo)))
(expect (pt :en :example.bar/baz) (with-tscope :example.bar (pt :en :baz)))

;;; Decorators
(expect "&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;"
        (pt :en :example/inline-markdown))
(expect "<p>&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;</p>"
        (pt :en :example/block-markdown))
(expect "<tag>**strong**</tag>"
        (pt :en :example/with-exclaim))

;;; Arg interpolation
(expect "Hello Steve, how are you?" (pt :en :example/greeting "Steve"))
(expect Exception ((tower/make-t {:dictionary {}}) :en :anything "Any arg"))

;;; Missing translations
(expect "|Missing translation: [[:en] nil [:invalid]]|"
        (pt :en :invalid))
(expect nil (pt :de :invalid)) ; No locale-appropriate :missing key
(expect "|Missing translation: [[:en] :whatever [:invalid]]|"
        (with-tscope :whatever (pt :en :invalid)))
(expect "|Missing translation: [[:en] nil [:invalid]]|"
        (pt :en :invalid "arg"))
(expect "|Missing translation: [[:en] nil [:invalid :invalid]]|"
        (pt :en [:invalid :invalid]))

;;; Fallbacks
(expect ":en :example/foo text" (pt :en       [:invalid :example/foo]))
(expect "Explicit fallback"     (pt :en       [:invalid "Explicit fallback"]))
(expect nil                     (pt :en       [:invalid nil]))
(expect ":de :example/foo text" (pt [:zh :de] :example/foo))
(expect ":de :example/foo text" (pt [:zh :de] [:invalid :example/foo]))

;;; Aliases
(expect "Hello Bob, how are you?" (pt :en :example/greeting-alias "Bob"))
(expect (pt :en :example.bar/baz) (pt :en :example/baz-alias))
