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
  (cond (nil? loc) nil
        (instance? Locale loc) loc
        (= :jvm-default loc) (Locale/getDefault)
        :else (ensure-valid-Locale
               (apply make-Locale (str/split (name loc) #"[-_]")))))

(def locale
  "Returns valid Locale matching given name string/keyword, or throws an
  exception if none could be found. `loc` should be of form :en, :en-US,
  :en-US-variant, or :jvm-default."
  (memoize
   (fn [loc] (or (try-locale loc)
                (throw (Exception. (str "Invalid locale: " loc)))))))

(def locale-key "Returns locale keyword for given Locale object or locale keyword."
  (memoize #(keyword (str/replace (str (locale %)) "_" "-"))))

(comment
  (mapv try-locale [nil :invalid :jvm-default :en-US :en-US-var1 (Locale/getDefault)])
  (time (dotimes [_ 10000] (locale :en))))

(def ^:dynamic *locale* nil)
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
  (memoize
   (fn [style]
     (let [[type len1 len2] (if-not style [nil]
                              (mapv keyword (str/split (name style) #"-")))
           st1              (dt-styles (or len1      :default))
           st2              (dt-styles (or len2 len1 :default))]
       [type st1 st2]))))

(defmem- f-date DateFormat [Loc st]    (DateFormat/getDateInstance st Loc))
(defmem- f-time DateFormat [Loc st]    (DateFormat/getTimeInstance st Loc))
(defmem- f-dt   DateFormat [Loc ds ts] (DateFormat/getDateTimeInstance ds ts Loc))

(defmem- f-number   NumberFormat [Loc] (NumberFormat/getNumberInstance   Loc))
(defmem- f-integer  NumberFormat [Loc] (NumberFormat/getIntegerInstance  Loc))
(defmem- f-percent  NumberFormat [Loc] (NumberFormat/getPercentInstance  Loc))
(defmem- f-currency NumberFormat [Loc] (NumberFormat/getCurrencyInstance Loc))

(defprotocol     IFmt (pfmt [x loc style]))
(extend-protocol IFmt
  Date
  (pfmt [dt loc style]
    (let [[type st1 st2] (parse-style style)]
      (case (or type :date)
        :date (.format (f-date loc st1)     dt)
        :time (.format (f-time loc st1)     dt)
        :dt   (.format (f-dt   loc st1 st2) dt)
        (throw (Exception. (str "Unknown style: " style))))))
  Number
  (pfmt [n loc style]
    (case (or style :number)
      :number   (.format (f-number   loc) n)
      :integer  (.format (f-integer  loc) n)
      :percent  (.format (f-percent  loc) n)
      :currency (.format (f-currency loc) n)
      (throw (Exception. (str "Unknown style: " style))))))

(defn fmt
  "Formats Date/Number as a string.
  `style` is <:#{date time dt}-#{default short medium long full}>,
  e.g. :date-full, :time-short, etc. (default :date-default)."
  [loc x & [style]] (pfmt x (locale loc) style))

(defn parse
  "Parses date/number string as a Date/Number. See `fmt` for possible `style`s
  (default :number)."
  [loc s & [style]]
  (let [loc (locale loc)
        [type st1 st2] (parse-style style)]
    (case (or type :number)
      :number   (.parse (f-number   loc) s)
      :integer  (.parse (f-integer  loc) s)
      :percent  (.parse (f-percent  loc) s)
      :currency (.parse (f-currency loc) s)

      :date     (.parse (f-date loc st1)     s)
      :time     (.parse (f-time loc st1)     s)
      :dt       (.parse (f-dt   loc st1 st2) s)
      (throw (Exception. (str "Unknown style: " style))))))

(defmem- collator Collator [Loc] (Collator/getInstance Loc))
(defn lcomparator "Returns localized comparator."
  [loc & [style]]
  (let [Col (collator (locale loc))]
    (case (or style :asc)
      :asc  #(.compare Col %1 %2)
      :desc #(.compare Col %2 %1)
      (throw (Exception. (str "Unknown style: " style))))))

(defn lsort "Localized sort. `style` e/o #{:asc :desc} (default :asc)."
  [loc coll & [style]] (sort (lcomparator loc style) coll))

(comment
  (fmt :en (Date.))
  (fmt :de (Date.))
  (fmt :en (Date.) :date-short)
  (fmt :en (Date.) :dt-long)

  (fmt   :en-US 55.474   :currency)
  (parse :en-US "$55.47" :currency)

  (fmt   :en (/ 3 9) :percent)
  (parse :en "33%"   :percent)

  (parse :en (fmt :en (Date.)) :date)

  (lsort :en ["a" "d" "c" "b" "f" "_"]))

(defn normalize
  "Transforms Unicode string into W3C-recommended standard de/composition form
  allowing easier searching and sorting of strings. Normalization is considered
  good hygiene when communicating with a DB or other software."
  [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))

;;;; Localized text formatting

(defn fmt-str "Like clojure.core/format but takes a locale."
  ^String [loc fmt & args] (String/format (locale loc) fmt (to-array args)))

(defn fmt-msg
  "Creates a localized message formatter and parse pattern string, substituting
  given arguments as per MessageFormat spec."
  ^String [loc pattern & args]
  (let [formatter (java.text.MessageFormat. pattern (locale loc))]
    (.format formatter (to-array args))))

(comment
  (fmt-msg :de "foobar {0}!" 102.22)
  (fmt-msg :de "foobar {0,number,integer}!" 102.22)
  ;; Note that choice text must be unescaped! Use `!` decorator:
  (mapv #(fmt-msg :de "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
        (range 5)))

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

(defn countries
  "Returns ISO country codes and corresponding localized country names, both
  sorted by the localized names."
  ([loc] (countries loc all-iso-countries))
  ([loc iso-countries]
     (get-sorted-localized-names
      (fn [code] (.getDisplayCountry (Locale. "" code) (locale loc)))
      iso-countries (locale loc))))

(comment (countries :pl ["GB" "DE" "PL"]))

(def ^:private all-iso-languages (vec (Locale/getISOLanguages)))

(defn languages
  "Returns ISO language codes and corresponding localized language names, both
  sorted by the localized names."
  ([loc] (languages loc all-iso-languages))
  ([loc iso-languages]
     (get-sorted-localized-names
      (fn [code] (let [Loc (Locale. code)]
                  (str (.getDisplayLanguage Loc (locale loc))
                       ;; Also provide each name in it's OWN language
                       (when (not= Loc (locale loc))
                         (str " (" (.getDisplayLanguage Loc Loc) ")")))))
      iso-languages (locale loc))))

(comment (languages :pl ["en" "de" "pl"]))

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

(def timezones
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

(comment (take 10      (first  (timezones)))
         (take-last 10 (second (timezones))))

;;;; Translations

(def dev-mode?       "Global fallback dev-mode?." (atom true))
(def fallback-locale "Global fallback locale."    (atom :en))

(def scoped "Merges scope keywords: (scope :a.b :c/d :e) => :a.b.c.d/e"
  (memoize (fn [& ks] (utils/merge-keywords ks))))

(comment (scoped :a.b :c :d))

(def ^:dynamic *tscope* nil)
(defmacro with-tscope
  "Executes body within the context of thread-local translation-scope binding.
  `translation-scope` should be a keyword like :example.greetings, or nil."
  [translation-scope & body] `(binding [*tscope* ~translation-scope] ~@body))

(def example-tconfig
  "Example/test config as passed to `t`, `wrap-i18n-middlware`, etc.

  :dictionary should be a map, or named resource containing a map of form
  {:locale {:ns1 ... {:nsN {:key<decorator> text ...} ...} ...} ...}}.

  Named resource will be watched for changes when `:dev-mode?` is true."
  {:dev-mode? true
   :fallback-locale :en
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
     (timbre/logp (if dev-mode? :debug :warn) "Missing translation" args))})

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

      {loc {(apply scoped (conj scope-ks unscoped-k)) translation}})))

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
  [raw-dict dev-mode?]
  (if-let [dd (and (or (not dev-mode?)
                       (not (string? raw-dict))
                       (not (utils/file-resources-modified? [raw-dict])))
                   (@dict-cache raw-dict))]
    @dd
    (let [dd
          (delay
           (let [raw-dict
                 (if-not (string? raw-dict)
                   raw-dict ; map or nil
                   (try (-> raw-dict io/resource io/reader slurp read-string)
                        (catch Exception e
                          (throw
                           (Exception. (str "Failed to load dictionary from"
                                            "resource: " raw-dict) e)))))]
             (->> (map (partial compile-dict-path raw-dict)
                       (utils/leaf-nodes (or raw-dict {})))
                  (apply merge-with merge) ; 1-level deep merge
                  (inherit-parent-trs))))]
      (swap! dict-cache assoc raw-dict dd)
      @dd)))

(comment (inherit-parent-trs (:dictionary example-tconfig))
         (compile-dict (:dictionary example-tconfig) true)
         (compile-dict "tower-dictionary.clj" true)
         (compile-dict nil true))

(defn t-scoped
  "Like `t` but uses an explicit (rather than thread-bound) root scope for
  dictionary keys:
    (t-scoped :en example-tconfig :a.b [:c1 :c2]) =>
    (t        :en example-tconfig [:a.b/c1 :a.b/c2]

  Useful for lib authors and other contexts where one wants to ignore the
  thread-bound translation scope."
  [loc config scope k-or-ks & fmt-msg-args]
  (let [{:keys [dev-mode? dictionary fallback-locale log-missing-translation-fn]
         :or   {dev-mode?       @dev-mode?
                fallback-locale (or (:default-locale config) ; Backwards comp
                                    @fallback-locale)}} config
        dict   (compile-dict dictionary dev-mode?)
        ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])
        get-tr #(get-in dict [(locale-key %1) (scoped scope %2)])
        tr
        (or (some #(get-tr loc %) (take-while keyword? ks)) ; Try loc & parents
            (let [last-k (peek ks)]
              (if-not (keyword? last-k)
                last-k ; Explicit final, non-keyword fallback (may be nil)

                (do (when-let [log-f log-missing-translation-fn]
                      (log-f {:dev-mode? dev-mode? :ns (str *ns*)
                              :locale loc :scope scope :ks ks}))
                    (or
                     ;; Try fallback-locale & parents
                     (some #(get-tr fallback-locale %) ks)

                     ;; Try :missing key in loc, parents, fallback-loc, & parents
                     (when-let [pattern (or (get-tr loc             :missing)
                                            (get-tr fallback-locale :missing))]
                       (fmt-msg loc pattern loc scope ks)))))))]

    (if-not fmt-msg-args tr
      (apply fmt-msg loc tr fmt-msg-args))))

(defn t ; translate
  "Localized text translator. Takes dictionary key (or vector of descending-
  preference keys) and returns the best translation available for given locale.
  With additional arguments, treats translation as pattern for `fmt-msg`.

  See `example-tconfig` for config details."
  [loc config k-or-ks & fmt-msg-args]
  (apply t-scoped loc config *tscope* k-or-ks fmt-msg-args))

(comment (t :en-ZA example-tconfig :example/foo)
         (with-tscope :example (t :en-ZA example-tconfig :foo))

         (t :en example-tconfig :invalid)
         (t :en example-tconfig [:invalid :example/foo])
         (t :en example-tconfig [:invalid "Explicit fallback"])

         (def prod-c (assoc example-tconfig :dev-mode? false))
         (time (dotimes [_ 10000] (t :en prod-c :example/foo)))            ; ~30ms
         (time (dotimes [_ 10000] (t :en prod-c [:invalid :example/foo]))) ; ~45ms
         (time (dotimes [_ 10000] (t :en prod-c [:invalid nil])))          ; ~35ms
         )

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; DEPRECATED
;; The v2 API basically breaks everything. To allow lib consumers to migrate
;; gradually, the entire v1 API is reproduced below. This should allow Tower v2
;; to act as a quasi drop-in replacement for v1, despite the huge changes
;; under-the-covers.

(defn parse-Locale "DEPRECATED: Use `locale` instead."
  [loc] (if (= loc :default) (locale :jvm-default) (locale loc)))

(defn l-compare "DEPRECATED." [x y] (.compare (collator *locale*) x y))

(defn format-number   "DEPRECATED." [x] (fmt *locale* x :number))
(defn format-integer  "DEPRECATED." [x] (fmt *locale* x :integer))
(defn format-percent  "DEPRECATED." [x] (fmt *locale* x :percent))
(defn format-currency "DEPRECATED." [x] (fmt *locale* x :currency))

(defn parse-number    "DEPRECATED." [s] (parse *locale* s :number))
(defn parse-integer   "DEPRECATED." [s] (parse *locale* s :integer))
(defn parse-percent   "DEPRECATED." [s] (parse *locale* s :percent))
(defn parse-currency  "DEPRECATED." [s] (parse *locale* s :currency))

(defn- new-style [& xs] (keyword (str/join "-" (mapv name xs))))

(defn style "DEPRECATED."
  ([] :default)
  ([style] (or (dt-styles style)
               (throw (Exception. (str "Unknown style: " style))))))

(defn format-date "DEPRECATED."
  ([d]       (fmt *locale* d :date))
  ([style d] (fmt *locale* d (new-style :date style))))

(defn format-time "DEPRECATED."
  ([t]       (fmt *locale* t :time))
  ([style t] (fmt *locale* t (new-style :time style))))

(defn format-dt "DEPRECATED."
  ([dt]               (fmt *locale* dt :dt))
  ([dstyle tstyle dt] (fmt *locale* dt (new-style :dt dstyle tstyle))))

(defn parse-date "DEPRECATED."
  ([s]       (parse *locale* s :date))
  ([style s] (parse *locale* s (new-style :date style))))

(defn parse-time "DEPRECATED."
  ([s]       (parse *locale* s :time))
  ([style s] (parse *locale* s (new-style :time style))))

(defn parse-dt "DEPRECATED."
  ([s]               (parse *locale* s :dt))
  ([dstyle tstyle s] (parse *locale* s (new-style :dt dstyle tstyle))))

(def format-str "DEPRECATED." #(apply fmt-str *locale* %&))
(def format-msg "DEPRECATED." #(apply fmt-msg *locale* %&))

(defn- sorted-old [f & args]
  (fn [& args]
    (let [[names ids] (apply f *locale* args)]
      {:sorted-names names
       :sorted-ids   ids})))

(def sorted-localized-countries "DEPRECATED." (sorted-old countries))
(def sorted-localized-languages "DEPRECATED." (sorted-old languages))
(def sorted-timezones           "DEPRECATED." (sorted-old timezones))

(def config "DEPRECATED." (atom example-tconfig))
(defn set-config!   "DEPRECATED." [ks val] (swap! config assoc-in ks val))
(defn merge-config! "DEPRECATED." [& maps] (apply swap! config utils/merge-deep maps))

(defn load-dictionary-from-map-resource! "DEPRECATED."
  ([] (load-dictionary-from-map-resource! "tower-dictionary.clj"))
  ([resource-name & [merge?]]
     (try (let [new-dictionary (-> resource-name io/resource io/reader slurp
                                   read-string)]
            (if (= false merge?)
              (set-config!   [:dictionary] new-dictionary)
              (merge-config! {:dictionary  new-dictionary})))

          (set-config! [:dict-res-name] resource-name)
          (utils/file-resources-modified? resource-name)
          (catch Exception e
            (throw (Exception. (str "Failed to load dictionary from resource: "
                                    resource-name) e))))))

(defmacro with-scope "DEPRECATED." [translation-scope & body]
  `(with-tscope ~translation-scope ~@body))

;; BREAKS v1 due to unavoidable name clash
(def t' #(apply t (or *locale* :jvm-default) @config %&))
