(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure. Wraps standard Java facilities when possible."
  {:author "Peter Taoussanis"}
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre])
  (:use     [taoensso.tower.utils :as utils :only (defmem-)])
  (:import  [java.util Date Locale TimeZone]
            [java.text Collator NumberFormat DateFormat]))

;;;; Default configuration

(declare compiled-dictionary)

(utils/defonce* config
  "This map atom controls everything about the way Tower operates.

  To enable translations, :dictionary should be a map of form
  {:locale {:ns1 ... {:nsN {:key<decorator> text}}}}}.

  :default-locale controls fallback locale for `with-locale` and `t`.
  :dev-mode? controls `t` automatic dictionary reloading and default
    `log-missing-translation!-fn` behaviour.

  See source code for details.
  See `set-config!`, `merge-config!` for convenient config/dictionary
  editing."
  (atom {:dev-mode?      true
         :default-locale :en

         ;; Canonical example dictionary used for dev/debug, unit tests,
         ;; README, etc.
         :dictionary
         {:en         {:example {:foo       ":en :example/foo text"
                                 :foo_note  "Hello translator, please do x"
                                 :bar {:baz ":en :example.bar/baz text"}
                                 :greeting  "Hello {0}, how are you?"
                                 :with-markdown "<tag>**strong**</tag>"
                                 :with-exclaim! "<tag>**strong**</tag>"}
                       :missing  "<Missing translation: {0}>"}

          :en-US      {:example {:foo ":en-US :example/foo text"}}
          :en-US-var1 {:example {:foo ":en-US-var1 :example/foo text"}}}

         :log-missing-translation!-fn
         (fn [{:keys [dev-mode? locale k-or-ks scope] :as args}]
           (timbre/log (if dev-mode? :warn :debug)
             "Missing translation" args))}))

(defn set-config!   [ks val] (swap! config assoc-in ks val))
(defn merge-config! [& maps] (apply swap! config utils/deep-merge maps))

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
  :en-US-variant, or :default for config's default."
  [locale]
  (when locale
    (if (= locale :default)
      (or (parse-Locale (:default-locale @config)) (Locale/getDefault))
      (let [new-Locale (apply make-Locale (str/split (name locale) #"[-_]"))]
        (when (available-Locales new-Locale) new-Locale)))))

(comment (map parse-Locale [nil :invalid :default :en-US :en-US-var1]))

;;;; Bindings

(def ^:dynamic *Locale* (parse-Locale :default))
(def ^:dynamic *translation-scope* nil)

(defmacro with-locale
  "Executes body within the context of thread-local locale binding, enabling
  use of translation and localization functions. `locale` should be of form :en,
  :en-US, :en-US-variant, or :default for config's default."
  [locale & body]
  `(binding [*Locale* (or (parse-Locale ~locale)
                          (throw (Exception. (str "Invalid locale: " ~locale))))]
     ~@body))

(defmacro with-scope
  "Executes body within the context of thread-local translation-scope binding.
  `translation-scope` should be a keyword like :example.greetings, or nil."
  [translation-scope & body]
  `(binding [*translation-scope* ~translation-scope] ~@body))

(def scope "(scope :a.b.c :d.e :f) => :a.b.c.d.e.f"
  (memoize
   (fn [& ks]
     (let [parts (mapcat #(str/split (name %) #"\.") ks)]
       (keyword (str/join "." parts))))))

(comment (scope :foo.bar :x.y :z)
         (scope :z))

;;;; Collation, etc.

(defmem- get-collator Collator [locale] (Collator/getInstance locale))

(defn l-compare "Localized Unicode comparator."
  [x y] (.compare (get-collator *Locale*) x y))

(comment (with-locale :default (sort l-compare ["a" "d" "c" "b" "f" "_"])))

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

;;;; Timezones (note: doesn't use locales)

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

;;;; Dictionary management

(def ^:private compiled-dictionary
  "Compiled form of (:dictionary @config) for efficiently storable and
  accessible display-ready text translations."
  (atom {}))

(defn load-dictionary-from-map-resource!
  "Sets dictionary by reading and merging Clojure map from named resource.
  Without any arguments, searches for `tower-dictionary.clj` in classpath and
  Leiningen's resource paths."
  ([] (load-dictionary-from-map-resource! "tower-dictionary.clj"))
  ([resource-name]
     (try (merge-config!
           {:dict-res-name  resource-name ; For automatic reloading
            :dictionary (-> resource-name
                            io/resource
                            io/reader
                            slurp
                            read-string)})
          (set-config! [:dict-res-name] resource-name)
          (utils/file-resources-modified? resource-name) ; Prime diff cache
          (catch Exception e
            (throw (Exception. (str "Failed to load dictionary from resource: "
                                    resource-name) e))))))

(defn- compile-map-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:locale {:ns1.<...>.nsN/unscoped-key (f translation decorator)}}"
  [path]
  (when-not (and (seq path) (>= (count path) 3))
    (throw (Exception. "Failed to compile dictionary: malformed")))
  (let [path        (vec path)
        locale-name (first path)
        translation (peek path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]

        [_ unscoped-k decorator] ; Check for possible decorator
        (->> (re-find #"([^!_]+)([!_].*)*" (name (peek (pop path))))
             (map keyword))

        ;; [:ns1 ... :nsN :unscoped-key] => :ns1.<...>.nsN/unscoped-key
        scoped-key (keyword (when (seq scope-ks) (str/join "." (map name scope-ks)))
                            (name unscoped-k))]

    (when-let [translation (case decorator
                             :_note      nil
                             (:_html :!) translation
                             (-> translation utils/escape-html
                                 utils/inline-markdown->html))]
      {locale-name {scoped-key translation}})))

(defn- compile-dictionary!
  "Compiles text translations stored in simple development-friendly Clojure map
  into form required by localized text translator.

    {:en {:example {:with-markdown \"<tag>**strong**</tag>\"
                    :with-exclaim! \"<tag>**strong**</tag>\"
                    :foo_note      \"Hello translator, please do x\"}}}
    =>
    {:en {:example/with-markdown \"&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;\"
          :example/with-exclaim! \"<tag>**strong**</tag>\"}}

  Note the optional key decorators."
  []
  (->> (utils/leaf-paths (:dictionary @config))
       (map compile-map-path)
       (apply merge-with merge) ; 1-level recursive merge
       (reset! compiled-dictionary)))

(compile-dictionary!) ; Actually compile default dictionary now

;; Automatically re-compile any time dictionary changes
(add-watch
 config "dictionary-watch"
 (fn [key ref old-state new-state]
   (when (or (not= (:dictionary old-state)
                   (:dictionary new-state))
             (not= (:dictionary-compiler-options old-state)
                   (:dictionary-compiler-options new-state)))
     (compile-dictionary!))))

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; Translations

(def ^:private locales-to-check
  "Given a Locale, returns vector of dictionary locale names to check, in order
  of preference:
    #<Locale en_US_var1> => [:en-US-var1 :en-US :en]
    #<Locale en_US>      => [:en-US :en]
    #<Locale en>         => [:en]"
  (memoize
   (fn [Loc]
     (let [parts (str/split (str Loc) #"_")]
       (vec (for [n (range (count parts) 0 -1)]
              (keyword (str/join "-" (take n parts)))))))))

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

(defn t ; translate
  "Localized text translator. Takes (possibly scoped) dictionary key (or vector
  of descending-preference keys) of form :nsA.<...>.nsN/key within a root scope
  :ns1.<...>.nsM and returns the best translation available for working locale.

  With additional arguments, treats translated text as pattern for message
  formatting.

  If :dev-mode? is set in `tower/config` and if dictionary was loaded using
  `tower/load-dictionary-from-map-resource!`, dictionary will be automatically
  reloaded each time the resource file changes."
  ([k-or-ks & interpolation-args]
     (when-let [pattern (t k-or-ks)]
       (apply format-msg pattern interpolation-args)))
  ([k-or-ks]
     (let [{:keys [dev-mode? default-locale log-missing-translation!-fn
                   dict-res-name]} @config]

       ;; Automatic dictionary reloading
       (when (and dev-mode? dict-res-name
                  (utils/file-resources-modified? dict-res-name))
         (load-dictionary-from-map-resource! dict-res-name))

       (let [;; :ns1.<...>.nsM.nsA.<...>/nsN = :ns1.<...>.nsN/key
             sk          (partial scoped-key *translation-scope*)
             get-in-dict (partial get-in @compiled-dictionary)
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
                (log-missing-translation!-fn
                 (assoc @missing-args :dev-mode? dev-mode?
                                      :ns        *ns*))

                (or
                 ;; Try fall back to named keys in (different) default locale
                 (when-not (= @lchoices* lchoices)
                   (some get-in-dict (for [k kchoices] [default-locale (sk k)])))

                 ;; Try fall back to :missing key in named or default locale
                 (when-let [pattern (some get-in-dict (for [l @lchoices*]
                                                        [l :missing]))]
                   (format-msg pattern @missing-args)))))))))))

(comment (with-locale :en-ZA (t :example/foo))
         (with-locale :en-ZA (with-scope :example (t :foo)))
         (with-locale :en-ZA (t :invalid))
         (with-locale :en-ZA (t* :invalid))
         (with-locale :en-ZA (t [:invalid :example/foo]))
         (with-locale :en-ZA (t [:invalid "Explicit fallback"]))

         ;; Benchmarks (dev-mode disabled):
         (time (dotimes [_ 10000] (t :example/foo)))            ; +/- 35ms
         (time (dotimes [_ 10000] (t [:invalid :example/foo]))) ; +/- 60ms
         (time (dotimes [_ 10000] (t [:invalid nil])))          ; +/- 45ms
         )

(defmacro tstr
  "EXPERIMENTAL. Like `t` but joins together multiple translations:
  (tstr :k1 :k2 \"arg1\" \"arg2\" [:k3-choice1 :k3-choice2]) =>
  (str/join \" \" (t :k1) (t :k2 \"arg1\" \"arg2\") (t [:k3-choice1 :k3-choice2]))"
  ([k-or-ks] `(t ~k-or-ks))
  ([k-or-ks & more]
     (let [;; Lists of 1 k-or-ks, each followed by optional args for interpolation
           partitions
           (loop [v [] remaining (cons k-or-ks more)]
             (if-not (seq remaining)
               v
               (let [partition
                     (vec (utils/take-until
                           #(and (not (keyword? %)) (not (vector?  %)))
                           remaining))]
                 (recur (conj v partition) (drop (count partition) remaining)))))]
       `(str/join " " (map #(apply t %) ~partitions)))))

(comment (with-locale :en-ZA (tstr :example/greeting "Steve" [:example/foo])))