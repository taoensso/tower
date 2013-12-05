(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure. Wraps standard Java facilities when possible."
  {:author "Peter Taoussanis, Janne Asmala"}
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]
            [taoensso.tower.utils :as utils :refer (defmem-)])
  (:import  [java.util Date Locale TimeZone Formatter]
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
  [s & [form]]
  (java.text.Normalizer/normalize s
    (case form
      (nil :nfc) java.text.Normalizer$Form/NFC
      :nfkc      java.text.Normalizer$Form/NFKC
      :nfd       java.text.Normalizer$Form/NFD
      :nfkd      java.text.Normalizer$Form/NFKD
      (throw (Exception. (format "Unrecognized normalization form: %s" form))))))

(comment (normalize "hello" :invalid))

;;;; Localized text formatting

;; (defmem- f-str Formatter [Loc] (Formatter. Loc))

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
  (fmt-str :de "foobar %s!"  102.22)

  (fmt-msg :de "foobar {0,number,integer}!" 102.22)
  (fmt-str :de "foobar %d!" (int 102.22))

  ;; "choice" formatting is about the only redeeming quality of `fmt-msg`. Note
  ;; that choice text must be unescaped! Use `!` decorator:
  (mapv #(fmt-msg :de "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
        (range 5)))

;;;; Localized country & language names

(defn- get-localized-sorted-map
  "Returns {<localized-name> <iso-code>} sorted map."
  [iso-codes display-loc display-fn]
  (let [pairs (->> iso-codes (mapv (fn [code] [(display-fn code) code])))
        comparator (fn [ln-x ln-y] (.compare (collator (locale display-loc))
                                            ln-x ln-y))]
    (into (sorted-map-by comparator) pairs)))

(def iso-countries (->> (Locale/getISOCountries)
                        (mapv (comp keyword str/lower-case)) (set)))

(def countries "Returns (sorted-map <localized-name> <iso-code> ...)."
  (memoize
   (fn ([loc] (countries loc iso-countries))
      ([loc iso-countries]
         (get-localized-sorted-map iso-countries (locale loc)
           (fn [code] (.getDisplayCountry (Locale. "" (name code)) (locale loc))))))))

(def iso-languages (->> (Locale/getISOLanguages)
                        (mapv (comp keyword str/lower-case)) (set)))

(def languages "Returns (sorted-map <localized-name> <iso-code> ...)."
  (memoize
   (fn ([loc] (languages loc iso-languages))
      ([loc iso-languages]
         (get-localized-sorted-map iso-languages (locale loc)
           (fn [code] (let [Loc (Locale. (name code))]
                       (str (.getDisplayLanguage Loc (locale loc))
                            ;; Also provide each name in it's OWN language
                            (when (not= Loc (locale loc))
                              (str " (" (.getDisplayLanguage Loc Loc) ")"))))))))))

(comment (countries :en)
         (languages :pl [:en :de :pl]))

;;;; Timezones (doesn't depend on locales)

(def major-timezone-ids
  (->> (TimeZone/getAvailableIDs)
       (filterv #(re-find #"^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.*" %))
       (set)))

(defn- timezone-display-name "(GMT +05:30) Colombo"
  [city-tz-id offset]
  (let [[region city] (str/split city-tz-id #"/")
        offset-mins   (/ offset 1000 60)]
    (format "(GMT %s%02d:%02d) %s"
            (if (neg? offset-mins) "-" "+")
            (Math/abs (int (/ offset-mins 60)))
            (mod (int offset-mins) 60)
            (str/replace city "_" " "))))

(comment (timezone-display-name "Asia/Bangkok" (* 90 60 1000)))

(def timezones "Returns (sorted-map-by offset <tz-name> <tz-id> ...)."
  (utils/memoize-ttl (* 3 60 60 1000) ; 3hr ttl
   (fn []
     (let [instant (System/currentTimeMillis)
           tzs (->> major-timezone-ids
                    (mapv (fn [id]
                            (let [tz     (TimeZone/getTimeZone id)
                                  offset (.getOffset tz instant)]
                              [(timezone-display-name id offset) id offset]))))
           tz-pairs (->> tzs (mapv   (fn [[dn id offset]] [dn id])))
           offsets  (->> tzs (reduce (fn [m [dn id offset]] (assoc m dn offset)) {}))
           comparator (fn [dn-x dn-y]
                        (let [cmp1 (compare (offsets dn-x) (offsets dn-y))]
                          (if-not (zero? cmp1) cmp1
                            (compare dn-x dn-y))))]
       (into (sorted-map-by comparator) tz-pairs)))))

(comment (reverse (sort ["-00:00" "+00:00" "-01:00" "+01:00" "-01:30" "+01:30"]))
         (count       (timezones))
         (take 5      (timezones))
         (take-last 5 (timezones)))

;;;; Translations

(def dev-mode?       "Global fallback dev-mode?." (atom true))
(def fallback-locale "Global fallback locale."    (atom :en))

(def scoped "Merges scope keywords: (scope :a.b :c/d :e) => :a.b.c.d/e"
  (memoize (fn [& ks] (utils/merge-keywords ks))))

(comment (scoped :a.b :c :d))

(def ^:dynamic *tscope* nil)
(defmacro with-tscope
  "Executes body within the context of thread-local translation scope binding.
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
   {:en         {:example {:foo         ":en :example/foo text"
                           :foo_comment "Hello translator, please do x"
                           :bar {:baz ":en :example.bar/baz text"}
                           :greeting "Hello %s, how are you?"
                           :inline-markdown "<tag>**strong**</tag>"
                           :block-markdown* "<tag>**strong**</tag>"
                           :with-exclaim!   "<tag>**strong**</tag>"
                           :greeting-alias :example/greeting
                           :baz-alias      :example.bar/baz}
                 :missing  "<Missing translation: [%1$s %2$s %3$s]>"}
    :en-US      {:example {:foo ":en-US :example/foo text"}}
    :en-US-var1 {:example {:foo ":en-US-var1 :example/foo text"}}
    :ja "test_ja.clj" ; Import locale's map from another resource
    }
    ;;; Advanced options
   :scope-var  #'*tscope*
   :root-scope nil
   :fmt-fn     fmt-str ; (fn [loc fmt args])
   :log-missing-translation-fn
   (fn [{:keys [dev-mode? locale ks scope] :as args}]
     (timbre/logp (if dev-mode? :debug :warn) "Missing translation" args))})

;;; Dictionaries

(defn- dict-load [dict] {:pre [(or (map? dict) (string? dict))]}
  (if-not (string? dict) dict
    (try (-> dict io/resource io/reader slurp read-string)
      (catch Exception e
        (throw (Exception. (format "Failed to load dictionary from resource: %s"
                                   dict) e))))))

(defn- dict-inherit-parent-trs
  "Merges each locale's translations over its parent locale translations."
  [dict] {:pre [(map? dict)]}
  (into {}
   (for [loc (keys dict)]
     (let [loc-parts (str/split (name loc) #"[-_]")
           loc-tree  (mapv #(keyword (str/join "-" %))
                           (take-while identity (iterate butlast loc-parts)))
           ;; Import locale's map from another resource:
           dict      (if-not (string? (dict loc)) dict
                       (assoc dict loc (dict-load (dict loc))))]
       [loc (apply utils/merge-deep (mapv dict (rseq loc-tree)))]))))

(comment (dict-inherit-parent-trs {:en    {:foo ":en foo"
                                           :bar ":en :bar"}
                                   :en-US {:foo ":en-US foo"}
                                   :ja    "test_ja.clj"}))

(def ^:private dict-prepare (comp dict-inherit-parent-trs dict-load))

(defn- dict-compile-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:ns1.<...>.nsN/unscoped-key {:locale (f translation decorator)}}"
  [dict path] {:pre [(>= (count path) 3) (vector? path)]}
  (let [loc         (first  path)
        translation (peek   path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]
        [_ unscoped-k decorator] (->> (re-find #"([^!\*_]+)([!\*_].*)*"
                                               (name (peek (pop path))))
                                      (mapv keyword))
        translation ; Resolve possible translation alias
        (if-not (keyword? translation) translation
          (let [target (get-in dict
                         (into [loc] (->> (utils/explode-keyword translation)
                                          (mapv keyword))))]
            (when-not (keyword? target) target)))]

    (when translation
      (when-let [translation*
                 (case decorator
                   (:_comment :_note) nil
                   (:_html :!)        translation
                   (:_md   :*)        (-> translation utils/html-escape
                                          (utils/markdown {:inline? false}))
                   (-> translation utils/html-escape
                       (utils/markdown {:inline? true})))]
        {(apply scoped (conj scope-ks unscoped-k)) {loc translation*}}))))

(def ^:private dict-compile-prepared
  "Compiles text translations stored in simple development-friendly
  Clojure map into form required by localized text translator.

    {:en {:example {:inline-markdown \"<tag>**strong**</tag>\"
                    :block-markdown  \"<tag>**strong**</tag>\"
                    :with-exclaim!   \"<tag>**strong**</tag>\"
                    :foo_comment     \"Hello translator, please do x\"}}}
    =>
    {:example/inline-markdown {:en \"&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;\"}
     :example/block-markdown  {:en \"<p>&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;</p>\"}
     :example/with-exclaim!   {:en \"<tag>**strong**</tag>\"}}}

  Note the optional key decorators."
  (memoize
   (fn [dict-prepared]
     (->> dict-prepared
          (utils/leaf-nodes)
          (mapv #(dict-compile-path dict-prepared (vec %)))
          ;; 1-level deep merge:
          (apply merge-with merge)))))

(def ^:private dict-compile* (comp dict-compile-prepared dict-prepare)) ; For dev-mode
(def ^:private dict-compile  (memoize dict-compile*)) ; For prod-mode

(comment
  (time (dotimes [_ 1000] (dict-compile* (:dictionary example-tconfig))))
  (time (dotimes [_ 1000] (dict-compile  (:dictionary example-tconfig)))))

;;;

(defn translate
  "Takes dictionary key (or vector of descending- preference keys) within a
  (possibly nil) scope, and returns the best translation available for given
  locale. With additional arguments, treats translation as pattern for
  config's `:fmt-fn` (defaults to `fmt-str`). `:fmt-fn` can also be used for
  advanced arg transformations like Hiccup form rendering, etc.

  See `example-tconfig` for config details."
  [loc config scope k-or-ks & fmt-args]
  (let [{:keys [dev-mode? dictionary fallback-locale log-missing-translation-fn
                scope-var root-scope fmt-fn]
         :or   {dev-mode?       @dev-mode?
                fallback-locale (or (:default-locale config) ; Backwards comp
                                    @fallback-locale)
                scope-var       #'*tscope*
                fmt-fn          fmt-str}} config

        scope  (if-not (identical? scope ::scope-var) scope
                       (when-let [v scope-var] (var-get v)))

        ;; For shared dictionaries. Experimental - intentionally undocumented
        scope  (scoped root-scope scope)

        dict   (if dev-mode? (dict-compile* dictionary)
                             (dict-compile  dictionary))
        ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])
        get-tr #(get-in dict [(scoped scope %1) (locale-key %2)])
        tr
        (or (some #(get-tr % loc) (take-while keyword? ks)) ; Try loc & parents
            (let [last-k (peek ks)]
              (if-not (keyword? last-k)
                last-k ; Explicit final, non-keyword fallback (may be nil)

                (do (when-let [log-f log-missing-translation-fn]
                      (log-f {:dev-mode? dev-mode? :ns (str *ns*)
                              :locale loc :scope scope :ks ks}))
                    (or
                     ;; Try fallback-locale & parents
                     (some #(get-tr % fallback-locale) ks)

                     ;; Try :missing key in loc, parents, fallback-loc, & parents
                     (when-let [pattern (or (get-in dict [:missing loc])
                                            (get-in dict [:missing fallback-locale]))]
                       (let [str* #(if (nil? %) "nil" (str %))]
                         (fmt-str loc pattern (str* loc) (str* scope) (str* ks)))))))))]

    (if-not fmt-args tr
      (if-not tr (throw (Exception. "Can't format nil translation pattern."))
        (apply fmt-fn loc tr fmt-args)))))

(defn t "Like `translate` but uses a thread-local translation scope."
  [loc config k-or-ks & fmt-str-args]
  (apply translate loc config ::scope-var k-or-ks fmt-str-args))

(comment (t :en-ZA example-tconfig :example/foo)
         (with-tscope :example (t :en-ZA example-tconfig :foo))
         (with-tscope :invalid
           (t :en (assoc example-tconfig :scope-var nil) :example/foo))

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
(def oldt #(apply t (or *locale* :jvm-default) (assoc @config :fmt-fn fmt-msg) %&))
