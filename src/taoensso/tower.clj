(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure. Wraps standard Java facilities when possible."
  {:author "Peter Taoussanis"}
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]
            [taoensso.tower.utils :as utils :refer (defmem-)])
  (:import  [java.util Date Locale TimeZone]
            [java.text Collator NumberFormat DateFormat]))

;;;; Locales (big L for the Java object)

(defn- make-Locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code."
  ([lang]                 (Locale. lang))
  ([lang country]         (Locale. lang country))
  ([lang country variant] (Locale. lang country variant)))

(def ^:private available-Locales (set (Locale/getAvailableLocales)))

(defn parse-Locale
  "Returns valid Locale matching given name string/keyword, or nil if no valid
  matching Locale could be found. `locale` should be of form :en, :en-US,
  :en-US-variant, or :jvm-default."
  [locale]
  (when locale
    (cond
     (= locale :jvm-default) (Locale/getDefault)
     (instance? Locale locale) locale
     :else
     (let [new-Locale (apply make-Locale (str/split (name locale) #"[-_]"))]
       (when (available-Locales new-Locale) new-Locale)))))

(comment (map parse-Locale [nil :invalid :jvm-default :en-US :en-US-var1
                            (Locale/getDefault)]))

(def ^:dynamic *Locale* (parse-Locale :jvm-default))
(defmacro with-locale
  "Executes body within the context of thread-local locale binding, enabling
  use of translation and localization functions. `locale` should be of form :en,
  :en-US, :en-US-variant, or :jvm-default."
  [locale & body]
  `(binding [*Locale* (or (parse-Locale ~locale)
                          (throw (Exception. (str "Invalid locale: " ~locale))))]
     ~@body))

;;;; Collation, etc.

(defmem- get-collator Collator [locale] (Collator/getInstance locale))

(defn l-compare "Localized Unicode comparator."
  [x y] (.compare (get-collator *Locale*) x y))

(comment (with-locale :jvm-default (sort l-compare ["a" "d" "c" "b" "f" "_"])))

(defn normalize
  "Transforms Unicode string into W3C-recommended standard de/composition form
  allowing easier searching and sorting of strings. Normalization is considered
  good hygiene when communicating with a DB or other software."
  [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))

;;;; Localized number formatting

(defmem- f-number   NumberFormat [Loc] (NumberFormat/getNumberInstance   Loc))
(defmem- f-integer  NumberFormat [Loc] (NumberFormat/getIntegerInstance  Loc))
(defmem- f-percent  NumberFormat [Loc] (NumberFormat/getPercentInstance  Loc))
(defmem- f-currency NumberFormat [Loc] (NumberFormat/getCurrencyInstance Loc))

(defn format-number   [x] (.format (f-number   *Locale*) x))
(defn format-integer  [x] (.format (f-integer  *Locale*) x))
(defn format-percent  [x] (.format (f-percent  *Locale*) x))
(defn format-currency [x] (.format (f-currency *Locale*) x))

(defn parse-number    [s] (.parse (f-number   *Locale*) s))
(defn parse-integer   [s] (.parse (f-integer  *Locale*) s))
(defn parse-percent   [s] (.parse (f-percent  *Locale*) s))
(defn parse-currency  [s] (.parse (f-currency *Locale*) s))

(comment (with-locale :en-ZA (format-currency 200))
         (with-locale :en-ZA (parse-currency "R 200.33")))

;;;; Localized date/time formatting

(defmem- f-date DateFormat [st Loc]    (DateFormat/getDateInstance st Loc))
(defmem- f-time DateFormat [st Loc]    (DateFormat/getTimeInstance st Loc))
(defmem- f-dt   DateFormat [ds ts Loc] (DateFormat/getDateTimeInstance ds ts Loc))

(defn style
  "Returns a DateFormat time/date style constant by style key e/o
  #{:default :short :medium :long :full}."
  ([] (style :default))
  ([style]
     (case style
       :default DateFormat/DEFAULT
       :short   DateFormat/SHORT
       :medium  DateFormat/MEDIUM
       :long    DateFormat/LONG
       :full    DateFormat/FULL
       (throw (Exception. (str "Unknown style: " style))))))

(defn format-date
  ([d]       (format-date (style) d))
  ([style d] (.format (f-date style *Locale*) d)))

(defn format-time
  ([t]       (format-time (style) t))
  ([style t] (.format (f-time style *Locale*) t)))

(defn format-dt
  ([dt] (format-dt (style) (style) dt))
  ([date-style time-style dt]
     (.format (f-dt date-style time-style *Locale*) dt)))

(defn parse-date
  ([s]       (parse-date (style) s))
  ([style s] (.parse (f-date style *Locale*) s)))

(defn parse-time
  ([s]       (parse-time (style) s))
  ([style s] (.parse (f-time style *Locale*) s)))

(defn parse-dt
  ([s] (parse-dt (style) (style) s))
  ([date-style time-style s]
     (.parse (f-dt date-style time-style *Locale*) s)))

(comment (with-locale :en-ZA (format-date (Date.)))
         (with-locale :en-ZA (format-date (style :full) (Date.)))
         (with-locale :en-ZA (format-time (style :short) (Date.)))
         (with-locale :en-ZA (format-dt (style :full) (style :full) (Date.))))

;;;; Localized text formatting

(defn format-str
  "Like clojure.core/format but uses a locale."
  ^String [fmt & args]
  (String/format *Locale* fmt (to-array args)))

(defn format-msg
  "Creates a localized message formatter and parse pattern string, substituting
  given arguments as per MessageFormat spec."
  ^String [pattern & args]
  (let [formatter (java.text.MessageFormat. pattern *Locale*)]
    (.format formatter (to-array args))))

(comment
  (with-locale :de (format-msg "foobar {0}!" 102.22))
  (with-locale :de (format-msg "foobar {0,number,integer}!" 102.22))
  (with-locale :de ; Note that choice text must be unescaped! Use `!` decorator.
    (-> #(format-msg "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
        (map (range 5)) doall)))

;;;; Localized country & language names

(def ^:private get-sorted-localized-names
  "Returns map containing ISO codes and corresponding localized names, both
  sorted by the localized names."
  (memoize
   (fn [display-fn iso-codes display-Loc]
     (let [;; [localized-display-name code] seq sorted by localized-display-name
           sorted-pairs
           (->> (for [code iso-codes] [(display-fn code) code])
                (sort (fn [[x _] [y _]]
                        (.compare (get-collator display-Loc) x y))))]
       ;; mapv requires Clojure 1.4+
       {:sorted-names (vec (map first sorted-pairs))
        :sorted-codes (vec (map second sorted-pairs))}))))

(def ^:private all-iso-countries (seq (Locale/getISOCountries)))

(defn sorted-localized-countries
  "Returns map containing ISO country codes and corresponding localized country
  names, both sorted by the localized names."
  ([] (sorted-localized-countries all-iso-countries))
  ([iso-countries]
     (get-sorted-localized-names
      (fn [code] (.getDisplayCountry (Locale. "" code) *Locale*))
      iso-countries *Locale*)))

(comment (with-locale :pl (sorted-localized-countries ["GB" "DE" "PL"])))

(def ^:private all-iso-languages (seq (Locale/getISOLanguages)))

(defn sorted-localized-languages
  "Returns map containing ISO language codes and corresponding localized
  language names, both sorted by the localized names."
  ([] (sorted-localized-languages all-iso-languages))
  ([iso-languages]
     (get-sorted-localized-names
      (fn [code] (let [Loc (Locale. code)]
                  (str (.getDisplayLanguage Loc *Locale*)
                       ;; Also provide each name in it's OWN language
                       (when (not= Loc *Locale*)
                         (str " (" (.getDisplayLanguage Loc Loc) ")")))))
      iso-languages *Locale*)))

(comment (with-locale :pl (sorted-localized-languages ["en" "de" "pl"])))

;;;; Timezones (doesn't depend on locales)

(def ^:private major-timezone-ids
  (->> (TimeZone/getAvailableIDs)
       (filter #(re-find #"^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.*" %))))

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
  "Returns map containing timezone IDs and corresponding pretty timezone names,
  both sorted by the timezone's offset. Caches result for 3 hours."
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
       {:sorted-names (vec (map first sorted-pairs))
        :sorted-ids   (vec (map second sorted-pairs))}))))

(comment (take 10      (:sorted-names (sorted-timezones)))
         (take-last 10 (:sorted-names (sorted-timezones))))

;;;; Translations

(def ^:dynamic *translation-scope* nil)
(defmacro with-scope
  "Executes body within the context of thread-local translation-scope binding.
  `translation-scope` should be a keyword like :example.greetings, or nil."
  [translation-scope & body]
  `(binding [*translation-scope* ~translation-scope] ~@body))

(def example-config
  "Example/test config as passed to `t`, `wrap-i18n-middlware`, etc.

  :dictionary should be a map, or named resource containing a map of form
  {:locale {:ns1 ... {:nsN {:key<decorator> text ...} ...} ...} ...}}.

  Named resource will be watched for changes when `:dev-mode?` is true."
  {:dev-mode?      true
   :default-locale :en
   :dictionary ; Map or named resource containing map
   {:en         {:example {:foo       ":en :example/foo text"
                           :foo_note  "Hello translator, please do x"
                           :bar {:baz ":en :example.bar/baz text"}
                           :greeting  "Hello {0}, how are you?"
                           :with-markdown "<tag>**strong**</tag>"
                           :with-exclaim! "<tag>**strong**</tag>"
                           :greeting-alias :example/greeting
                           :baz-alias      :example.bar/baz}
                 :missing  "<Missing translation: {0}>"}
    :en-US      {:example {:foo ":en-US :example/foo text"}}
    :en-US-var1 {:example {:foo ":en-US-var1 :example/foo text"}}}

   :log-missing-translation-fn
   (fn [{:keys [dev-mode? locale k-or-ks scope] :as args}]
     (timbre/logp (if dev-mode? :warn :debug) "Missing translation" args))})

(defn- compile-dict-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:locale {:ns1.<...>.nsN/unscoped-key (f translation decorator)}}"
  [path]
  (assert (and (seq path) (>= (count path) 3))
          (str "Malformed dictionary path: " path))
  (let [[locale-name :as path] (vec path)
        translation (peek path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]
        [_ unscoped-k decorator] ; Check for possible decorator
        (->> (re-find #"([^!_]+)([!_].*)*" (name (peek (pop path))))
             (map keyword))

        ;; [:ns1 ... :nsN :unscoped-key] => :ns1.<...>.nsN/unscoped-key
        scoped-key (keyword (when (seq scope-ks) (str/join "." (map name scope-ks)))
                            (name unscoped-k))]

    (when-let [translation
               (if (keyword? translation)
                 translation ; TODO Add *compile*-time alias support
                 (case decorator
                       :_note      nil
                       (:_html :!) translation
                       (-> translation utils/escape-html
                           utils/inline-markdown->html)))]
      {locale-name {scoped-key translation}})))

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
               (->> (map compile-dict-path (utils/leaf-paths (or raw-dict {})))
                    (apply merge-with merge))))]
        (swap! dict-cache assoc dictionary dd)
        @dd))))

(comment (compile-dict example-config)
         (compile-dict {:dictionary "tower-dictionary.clj"})
         (compile-dict {}))

(def ^:private locales-to-check
  "Given a Locale, returns vector of dictionary locale names to check, in order
  of preference:
    #<Locale en_US_var1> => [:en-US-var1 :en-US :en]
    #<Locale en_US>      => [:en-US :en]
    #<Locale en>         => [:en]"
  (memoize
   (fn [Loc]
     (let [parts (str/split (str Loc) #"_")]
       (mapv #(keyword (str/join "-" %))
             (take-while identity (iterate butlast parts)))))))

(comment (locales-to-check (parse-Locale :en-US)))

(def ^:private scoped-key
  "(scoped-key :a.b.c :k)     => :a.b.c/k
   (scoped-key :a.b.c :d.e/k) => :a.b.c.d.e/k"
  (memoize
   (fn [root-scope scoped-key]
     (if root-scope
       (let [full-scope
             (str (name root-scope)
                  (when-let [more (namespace scoped-key)] (str "." more)))
             unscoped-key (name scoped-key)]
         (keyword full-scope unscoped-key))
       scoped-key))))

(comment (scoped-key :a.b.c :k)
         (scoped-key :a.b.c :d.e/k)
         (scoped-key nil :k)
         (scoped-key nil :a.b/k))

(defn- recursive-get-in ; TODO Make aliases a [dictionary] compile-time feature
  [m ks]
  (reduce (fn [m k]
            (loop [target k seen? #{}]
              (when-not (seen? target)
                (let [v (get m target)]
                  (if (keyword? v)
                    (recur v (conj seen? target))
                    v)))))
          m ks))

(defn t ; translate
  "Localized text translator. Takes (possibly scoped) dictionary key (or vector
  of descending-preference keys) of form :nsA.<...>.nsN/key within a root scope
  :ns1.<...>.nsM and returns the best translation available for working locale.

  With additional arguments, treats translated text as pattern for message
  formatting.

  See `tower/example-config` for config details."
  ([config k-or-ks & interpolation-args]
     (when-let [pattern (t config k-or-ks)]
       (apply format-msg pattern interpolation-args)))
  ([config k-or-ks]
     (assert (map? config) (str "Malformed config map: " config))
     (let [{:keys [dev-mode? default-locale dictionary log-missing-translation-fn]}
           (assoc config :dictionary (compile-dict config))]

       (let [;; :ns1.<...>.nsM.nsA.<...>/nsN = :ns1.<...>.nsN/key
             sk          (partial scoped-key *translation-scope*)
             get-in-dict (partial recursive-get-in dictionary)
             lchoices    (locales-to-check *Locale*)
             lchoices*   (delay (if-not (some #{default-locale} lchoices)
                                  (conj lchoices default-locale)
                                  lchoices))
             kchoices*   (if (vector? k-or-ks) k-or-ks [k-or-ks])
             kchoices    (take-while keyword? kchoices*)
             missing-args (delay {:locale  (first lchoices)
                                  :scope   *translation-scope*
                                  :k-or-ks k-or-ks})]

         (or
          ;; Try named keys in named locale, allowing transparent fallback to
          ;; locale with stripped variation &/or region
          (some get-in-dict (for [l lchoices k kchoices] [l (sk k)]))

          (let [last-kchoice* (peek kchoices*)]
            (if-not (keyword? last-kchoice*)
              last-kchoice* ; Return provided explicit fallback value

              (do
                (when-let [log-f log-missing-translation-fn]
                  (log-f (assoc @missing-args :dev-mode? dev-mode?
                                              :ns        (str *ns*))))

                (or
                 ;; Try fall back to named keys in (different) default locale
                 (when-not (= @lchoices* lchoices)
                   (some get-in-dict (for [k kchoices] [default-locale (sk k)])))

                 ;; Try fall back to :missing key in named or default locale
                 (when-let [pattern (some get-in-dict (for [l @lchoices*]
                                                        [l :missing]))]
                   ;; Use 's to escape {}'s for MessageFormat
                   (format-msg pattern (str "'" @missing-args "'"))))))))))))

(comment (with-locale :en-ZA (t example-config :example/foo))
         (with-locale :en-ZA (with-scope :example (t example-config :foo)))
         (with-locale :en-ZA (t example-config :invalid))
         (with-locale :en-ZA (t example-config :invalid))
         (with-locale :en-ZA (t example-config [:invalid :example/foo]))
         (with-locale :en-ZA (t example-config [:invalid "Explicit fallback"]))

         (def ec (assoc example-config :dev-mode? false))
         (time (dotimes [_ 10000] (compile-dict ec)))              ; ~11ms
         (time (dotimes [_ 10000] (t ec :example/foo)))            ; ~45ms
         (time (dotimes [_ 10000] (t ec [:invalid :example/foo]))) ; ~70ms
         (time (dotimes [_ 10000] (t ec [:invalid nil])))          ; ~70ms
         )

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?