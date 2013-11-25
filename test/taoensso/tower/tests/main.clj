(ns taoensso.tower.tests.main
  (:require [expectations   :as test  :refer :all]
            [taoensso.tower :as tower :refer (with-locale with-tscope t)])
  (:import  [java.util Date]))

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

(def pt (fn [a1 & an] (apply tower/t a1 tower/example-tconfig an)))

;;;; Translations

;;; Locale selection & fallback
(given [s loc] (expect s (pt loc :example/foo))
  ":en :example/foo text"    :en
  ":en-US :example/foo text" :en-US
  ":en :example/foo text"    :en-GB
  ":en-US :example/foo text" :jvm-default
  ":en :example/foo text"    :zh-CN
  ":ja 日本語"               :ja)

;;; Fall back to config's :de-DE fault-locale
(expect ":en :example/foo text" (pt :de-DE :example/foo))

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

;;; Missing keys & key fallback
(expect "&lt;Missing translation: [:en nil [:invalid]]&gt;"
        (pt :en :invalid))
(expect "&lt;Missing translation: [:en :whatever [:invalid]]&gt;"
        (with-tscope :whatever (pt :en :invalid)))
(expect "&lt;Missing translation: [:en nil [:invalid]]&gt;"
        (pt :en :invalid "arg"))
(expect ":en :example/foo text"
        (pt :en [:invalid :example/foo]))
(expect "&lt;Missing translation: [:en nil [:invalid :invalid]]&gt;"
        (pt :en [:invalid :invalid]))
(expect "Explicit fallback" (pt :en [:invalid "Explicit fallback"]))
(expect nil (pt :en [:invalid nil]))

;;; Aliases
(expect "Hello Bob, how are you?" (pt :en :example/greeting-alias "Bob"))
(expect (pt :en :example.bar/baz) (pt :en :example/baz-alias))
