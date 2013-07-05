(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure. Wraps standard Java facilities when possible."
  {:author "Peter Taoussanis, Janne Asmala"}
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]
            [taoensso.tower.utils :as utils :refer (defmem-)])
  (:import  [java.util Date Locale TimeZone]
            [java.text Collator NumberFormat DateFormat]))

;; TODO Wrap for v1 API where possible

;;;; Locales (big L for the Java object) & bindings

(def ^:private ensure-valid-Locale (set (Locale/getAvailableLocales)))
(defn- make-Locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code."
  ([lang]                 (Locale. lang))
  ([lang country]         (Locale. lang country))
  ([lang country variant] (Locale. lang country variant)))

(defn try-locale
  "Like `locale` but returns nil if no valid matching Locale could be found."
  [loc]
  (when loc
    (cond (instance? Locale loc) loc
          (= :jvm-default loc) (Locale/getDefault)
          :else (ensure-valid-Locale
                 (apply make-Locale (str/split (name loc) #"[-_]"))))))

(defn locale
  "Returns valid Locale matching given name string/keyword, or throws an
  exception if none could be found. `loc` should be of form :en, :en-US,
  :en-US-variant, or :jvm-default."
  [loc] (or (try-locale loc) (throw (Exception. (str "Invalid locale: " loc)))))

(comment
  (map locale [nil :invalid :jvm-default :en-US :en-US-var1 (Locale/getDefault)])
  (time (dotimes [_ 10000] (locale :en))))

(def ^:dynamic *locale* nil) ; (locale :jvm-default)
(defmacro with-locale
  "Executes body within the context of thread-local locale binding, enabling
  use of translation and localization functions. `loc` should be of form :en,
  :en-US, :en-US-variant, or :jvm-default."
  [loc & body] `(binding [*locale* (locale ~loc)] ~@body))

;;;; Localization

(def ^:private ^:const dt-styles
  {:default DateFormat/DEFAULT
   :short   DateFormat/SHORT
   :medium  DateFormat/MEDIUM
   :long    DateFormat/LONG
   :full    DateFormat/FULL})

(def ^:private parse-style "style -> [type subtype1 subtype2 ...]"
  (memoize (fn [style] (mapv keyword (str/split (name style) #"-")))))

(defmem- f-date DateFormat [Loc st]    (DateFormat/getDateInstance st Loc))
(defmem- f-time DateFormat [Loc st]    (DateFormat/getTimeInstance st Loc))
(defmem- f-dt   DateFormat [Loc ds ts] (DateFormat/getDateTimeInstance ds ts Loc))

(defmem- f-number   NumberFormat [Loc] (NumberFormat/getNumberInstance   Loc))
(defmem- f-integer  NumberFormat [Loc] (NumberFormat/getIntegerInstance  Loc))
(defmem- f-percent  NumberFormat [Loc] (NumberFormat/getPercentInstance  Loc))
(defmem- f-currency NumberFormat [Loc] (NumberFormat/getCurrencyInstance Loc))

(defmem- collator Collator [Loc] (Collator/getInstance Loc))

(defprotocol     ILocalize (plocalize [x loc] [x loc style]))
(extend-protocol ILocalize
  nil
  (plocalize
    ([x loc]       nil)
    ([x loc style] nil))

  Date ; format
  (plocalize
    ([dt loc] (plocalize dt loc :date))
    ([dt loc style]
       (let [[type len1 len2] (parse-style style)
             st1              (dt-styles (or len1      :default))
             st2              (dt-styles (or len2 len1 :default))]
         (case type
           :date (.format (f-date loc st1)     dt)
           :time (.format (f-time loc st1)     dt)
           :dt   (.format (f-dt   loc st1 st2) dt)
           (throw (Exception. (str "Unknown style: " style)))))))

  Number ; format
  (plocalize
    ([n loc] (plocalize n loc :number))
    ([n loc style]
       (case style
         :number   (.format (f-number   loc) n)
         :integer  (.format (f-integer  loc) n)
         :percent  (.format (f-percent  loc) n)
         :currency (.format (f-currency loc) n)
         (throw (Exception. (str "Unknown style: " style))))))

  String ; parse
  (plocalize
    ([s loc] (plocalize s loc :number))
    ([s loc style]
       (case style
         :number   (.parse (f-number   loc) s)
         :integer  (.parse (f-integer  loc) s)
         :percent  (.parse (f-percent  loc) s)
         :currency (.parse (f-currency loc) s)
         (throw (Exception. (str "Unknown style: " style))))))

  clojure.lang.IPersistentCollection ; sort
  (plocalize
    ([coll loc] (plocalize coll loc :asc))
    ([coll loc style]
       (case style
         :asc  (sort (fn [x y] (.compare (collator loc) x y)) coll)
         :desc (sort (fn [x y] (.compare (collator loc) y x)) coll)
         (throw (Exception. (str "Unknown style: " style)))))))

(defn localize
  "Localizes given arg by arg type:
    * Nil    - nil.
    * Date   - Formatted string. `style` is <:#{date time dt}-#{default short
               medium long full}>, e.g. :date-full, :time-short, etc. Default is
               :date-default.
    * Number - Formatted string. `style` e/o #{:number :integer :percent
               :currency}. Default is :number.
    * String - Parsed number. `style` e/o #{:number :integer :percent :currency}.
               Default is :number.
    * Coll   - Sorted collection. `style` e/o #{:asc :desc}, default is :asc."
  ([loc x]       (plocalize x (locale loc)))
  ([loc x style] (plocalize x (locale loc) style)))

(comment
  (localize :en (Date.))
  (localize :de (Date.))
  (localize :en (Date.) :date-short)
  (localize :en (Date.) :dt-long)

  (localize :en 55.474  :currency)
  (localize :en (/ 3 9) :percent)

  (localize :en    "25%"    :percent)
  (localize :en-US "$55.47" :currency)

  (localize :en ["a" "d" "c" "b" "f" "_"]))

(defn normalize
  "Transforms Unicode string into W3C-recommended standard de/composition form
  allowing easier searching and sorting of strings. Normalization is considered
  good hygiene when communicating with a DB or other software."
  [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))

;;;; Localized text formatting

(defn format-str "Like clojure.core/format but takes a locale."
  ^String [loc fmt & args] (String/format (locale loc) fmt (to-array args)))

(defn format-msg
  "Creates a localized message formatter and parse pattern string, substituting
  given arguments as per MessageFormat spec."
  ^String [loc pattern & args]
  (let [formatter (java.text.MessageFormat. pattern (locale loc))]
    (.format formatter (to-array args))))

(comment
  (format-msg :de "foobar {0}!" 102.22)
  (format-msg :de "foobar {0,number,integer}!" 102.22)
  ;; Note that choice text must be unescaped! Use `!` decorator:
  (-> #(format-msg :de "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
      (map (range 5)) doall))

;;;; Localized country & language names

(def ^:private get-sorted-localized-names
  "Returns ISO codes and corresponding localized names, both sorted by the
  localized names."
  (memoize
   (fn [display-fn iso-codes display-loc]
     (let [;; [localized-display-name code] seq sorted by localized-display-name
           sorted-pairs
           (->> (for [code iso-codes] [(display-fn code) code])
                (sort (fn [[x _] [y _]]
                        (.compare (collator (locale display-loc)) x y))))]
       [(mapv first sorted-pairs) (mapv second sorted-pairs)]))))

(def ^:private all-iso-countries (vec (Locale/getISOCountries)))

(defn sorted-localized-countries
  "Returns ISO country codes and corresponding localized country names, both
  sorted by the localized names."
  ([loc] (sorted-localized-countries loc all-iso-countries))
  ([loc iso-countries]
     (get-sorted-localized-names
      (fn [code] (.getDisplayCountry (Locale. "" code) (locale loc)))
      iso-countries (locale loc))))

(comment (sorted-localized-countries :pl ["GB" "DE" "PL"]))

(def ^:private all-iso-languages (vec (Locale/getISOLanguages)))

(defn sorted-localized-languages
  "Returns ISO language codes and corresponding localized language names, both
  sorted by the localized names."
  ([loc] (sorted-localized-languages loc all-iso-languages))
  ([loc iso-languages]
     (get-sorted-localized-names
      (fn [code] (let [Loc (Locale. code)]
                  (str (.getDisplayLanguage Loc (locale loc))
                       ;; Also provide each name in it's OWN language
                       (when (not= Loc (locale loc))
                         (str " (" (.getDisplayLanguage Loc Loc) ")")))))
      iso-languages (locale loc))))

(comment (sorted-localized-languages :pl ["en" "de" "pl"]))

;;;; Timezones (doesn't depend on locales)

(def ^:private major-timezone-ids
  (->> (TimeZone/getAvailableIDs)
       (filterv #(re-find #"^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.*" %))))

(defn- timezone-display-name "(GMT +05:30) Colombo"
  [city-tz-id offset]
  (let [[region city] (str/split city-tz-id #"/")
        offset-mins   (/ offset 1000 60)]
    (str "(GMT " (if (neg? offset-mins) "-" "+")
         (format "%02d:%02d"
                 (Math/abs (int (/ offset-mins 60)))
                 (mod (int offset-mins) 60))
         ") " city)))

(comment (timezone-display-name "Asia/Bangkok" (* 90 60 1000)))

(def sorted-timezones
  "Returns timezone IDs and corresponding pretty timezone names, both sorted by
  the timezone's offset. Caches result for 3 hours."
  (utils/memoize-ttl
   #=(* 3 60 60 1000) ; 3hr ttl
   (fn []
     (let [;; [timezone-display-name id] seq sorted by timezone's offset
           sorted-pairs
           (->> (for [id major-timezone-ids]
                  (let [instant (System/currentTimeMillis)
                        tz      (TimeZone/getTimeZone id)
                        offset  (.getOffset tz instant)]
                    [offset (timezone-display-name id offset) id]))
                (sort-by first)
                (map (comp vec rest)))]
       [(mapv first  sorted-pairs)
        (mapv second sorted-pairs)]))))

(comment (take 10      (first (sorted-timezones)))
         (take-last 10 (second (sorted-timezones))))

;;;; Translations

(def ^:dynamic *tscope* nil)
(defmacro with-scope
  "Executes body within the context of thread-local translation-scope binding.
  `translation-scope` should be a keyword like :example.greetings, or nil."
  [translation-scope & body] `(binding [*tscope* ~translation-scope] ~@body))

(def scope (memoize (fn [& ks] (utils/merge-keywords ks true))))
(comment (scope :a :b :c.d.e))

(def example-tconfig
  "Example/test config as passed to `t`, `wrap-i18n-middlware`, etc.

  :dictionary should be a map, or named resource containing a map of form
  {:locale {:ns1 ... {:nsN {:key<decorator> text ...} ...} ...} ...}}.

  Named resource will be watched for changes when `:dev-mode?` is true."
  {:dev-mode? true
   :default-locale :en ; Dictionary's own translation fallback locale
   :dictionary ; Map or named resource containing map
   {:en         {:example {:foo       ":en :example/foo text"
                           :foo_note  "Hello translator, please do x"
                           :bar {:baz ":en :example.bar/baz text"}
                           :greeting  "Hello {0}, how are you?"
                           :with-markdown "<tag>**strong**</tag>"
                           :with-exclaim! "<tag>**strong**</tag>"
                           :greeting-alias :example/greeting
                           :baz-alias      :example.bar/baz}
                 :missing  "<Missing translation: [{0} {1} {2}]>"}
    :en-US      {:example {:foo ":en-US :example/foo text"}}
    :en-US-var1 {:example {:foo ":en-US-var1 :example/foo text"}}}

   :log-missing-translation-fn
   (fn [{:keys [dev-mode? locale ks scope] :as args}]
     (timbre/logp (if dev-mode? :warn :debug) "Missing translation" args))})

(defn- compile-dict-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:locale {:ns1.<...>.nsN/unscoped-key (f translation decorator)}}"
  [raw-dict path]
  (assert (>= (count path) 3) (str "Malformed dictionary path: " path))
  (let [[loc :as path] (vec path)
        translation (peek path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]
        [_ unscoped-k decorator] (->> (re-find #"([^!_]+)([!_].*)*"
                                               (name (peek (pop path))))
                                      (mapv keyword))

        translation (if-not (keyword? translation)
                      translation
                      (let [target ; Translation alias
                            (get-in raw-dict
                                    (into [loc]
                                          (->> (utils/explode-keyword translation)
                                               (mapv keyword))))]
                        (when-not (keyword? target) target)))]

    (when-let [translation
               (when translation
                 (case decorator
                   :_note      nil
                   (:_html :!) translation
                   (-> translation utils/escape-html utils/inline-markdown->html)))]

      {loc {(utils/merge-keywords (conj scope-ks unscoped-k)) translation}})))

(defn- inherit-parent-trs
  "Merges each locale's translations over its parent locale translations."
  [dict]
  (into {}
   (for [loc (keys dict)]
     (let [loc-parts (str/split (name loc) #"[-_]")
           loc-tree  (mapv #(keyword (str/join "-" %))
                           (take-while identity (iterate butlast loc-parts)))]
       [loc (apply merge (map dict (rseq loc-tree)))]))))

(comment (inherit-parent-trs {:en    {:foo ":en foo"
                                      :bar ":en :bar"}
                              :en-US {:foo ":en-US foo"}}))

(def ^:private dict-cache (atom {}))
(defn- compile-dict
  "Compiles text translations stored in simple development-friendly
  Clojure map into form required by localized text translator.

    {:en {:example {:with-markdown \"<tag>**strong**</tag>\"
                    :with-exclaim! \"<tag>**strong**</tag>\"
                    :foo_note      \"Hello translator, please do x\"}}}
    =>
    {:en {:example/with-markdown \"&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;\"
          :example/with-exclaim! \"<tag>**strong**</tag>\"}}

  Note the optional key decorators."
  [config]
  (let [{:keys [dev-mode? dictionary]} config]
    (if-let [dd (and (or (not dev-mode?)
                         (not (string? dictionary))
                         (not (utils/file-resources-modified? [dictionary])))
                     (@dict-cache dictionary))]
      @dd
      (let [dd
            (delay
             (let [raw-dict
                   (if-not (string? dictionary)
                     dictionary ; map or nil
                     (try (-> dictionary io/resource io/reader slurp read-string)
                          (catch Exception e
                            (throw
                             (Exception. (str "Failed to load dictionary from"
                                              "resource: " dictionary) e)))))]
               (->> (map (partial compile-dict-path raw-dict)
                         (utils/leaf-nodes (or raw-dict {})))
                    (apply merge-with merge) ; 1-level deep merge
                    (inherit-parent-trs))))]
        (swap! dict-cache assoc dictionary dd)
        @dd))))

(comment (inherit-parent-trs (:dictionary example-tconfig))
         (compile-dict example-tconfig)
         (compile-dict {:dictionary "tower-dictionary.clj"})
         (compile-dict {}))

(def ^:private scoped-key (memoize utils/merge-keywords))
(def           loc-key    (memoize #(keyword (str/replace (str (locale %)) "_" "-"))))

(defn t ; translate
  "Localized text translator. Takes (possibly scoped) dictionary key (or vector
  of descending-preference keys) of form :nsA.<...>.nsN/key within a root scope
  :ns1.<...>.nsM and returns the best translation available for working locale.

  With additional arguments, treats translated text as pattern for message
  formatting.

  See `tower/example-tconfig` for config details."
  ([loc config k-or-ks & interpolation-args]
     (when-let [pattern (t loc config k-or-ks)]
       (apply format-msg loc pattern interpolation-args)))
  ([loc config k-or-ks]
     (let [{:keys [dev-mode? default-locale log-missing-translation-fn]} config
           dict (compile-dict config)]

       (let [loc-k  (loc-key loc)
             scope  *tscope*
             ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])
             get-tr #(get-in dict [%1 (scoped-key [scope %2])])]

         (or (some #(get-tr loc-k %) (take-while keyword? ks)) ; Try loc & parents
             (let [last-k (peek ks)]
               (if-not (keyword? last-k)
                 last-k ; Explicit final, non-keyword fallback (may be nil)

                 (do (when-let [log-f log-missing-translation-fn]
                       (log-f {:dev-mode? dev-mode? :ns (str *ns*)
                               :locale loc-k :scope scope :ks ks}))

                     (or (when default-locale ; Try default-locale & parents
                           (some #(get-tr default-locale %) ks))

                         ;; Try :missing key in loc & parents
                         (when-let [pattern (get-tr loc-k :missing)]
                           (format-msg loc pattern loc-k scope ks)))))))))))

(comment (t :en-ZA example-tconfig :example/foo)
         (with-scope :example (t :en-ZA example-tconfig :foo))

         (t :en  example-tconfig :invalid)
         (t :en example-tconfig [:invalid :example/foo])
         (t :en example-tconfig [:invalid "Explicit fallback"])

         (def prod-c (assoc example-tconfig :dev-mode? false))
         (time (dotimes [_ 10000] (compile-dict prod-c)))                  ; ~8ms
         (time (dotimes [_ 10000] (t :en prod-c :example/foo)))            ; ~30ms
         (time (dotimes [_ 10000] (t :en prod-c [:invalid :example/foo]))) ; ~45ms
         (time (dotimes [_ 10000] (t :en prod-c [:invalid nil])))          ; ~35ms
         )

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; Deprecated TODO

;; * :default -> :jvm-default for parse-Locale

;; (def parse-Locale parse-locale) ; DEPRECATED

;; (defmacro ^:private defn-bound [name f]
;;   `(defn ~(symbol name) ~(str "Like `" f "` but uses thread-bound locale.")
;;      {:arglists (map (comp vec rest) (:arglists (meta (var ~f))))}
;;      [& sigs#] (apply ~f ~'*locale* sigs#)))
