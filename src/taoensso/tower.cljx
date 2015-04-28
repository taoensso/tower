(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure/Script. Wraps standard Java/Script facilities when possible."
  {:author "Peter Taoussanis, Janne Asmala"}
  #+clj (:require [clojure.string  :as str]
                  [clojure.java.io :as io]
                  [taoensso.encore :as encore]
                  [taoensso.timbre :as timbre]
                  [taoensso.tower.utils :as utils :refer (defmem- defmem-*)])
  #+clj (:import  [java.util Date Locale TimeZone Formatter]
                  [java.text Collator NumberFormat DateFormat])
  #+cljs (:require-macros [taoensso.encore :as encore]
                          [taoensso.tower  :as tower-macros])
  #+cljs (:require        [clojure.string  :as str]
                          [goog.string     :as gstr]
                          [goog.string.format]
                          [taoensso.encore :as encore]))

;;;; Encore version check

#+clj
(let [min-encore-version 1.21] ; Let's get folks on newer versions here
  (if-let [assert! (ns-resolve 'taoensso.encore 'assert-min-encore-version)]
    (assert! min-encore-version)
    (throw
      (ex-info
        (format
          "Insufficient com.taoensso/encore version (< %s). You may have a Leiningen dependency conflict (see http://goo.gl/qBbLvC for solution)."
          min-encore-version)
        {:min-version min-encore-version}))))

;;;; Locales
;;; We use the following terms:
;; 'Locale'     - Valid JVM Locale object.
;; 'locale'     - Valid JVM Locale object, or a locale kw like `:en-GB`.
;; 'jvm-locale' - Valid JVM Locale object, or a locale kw like `:en-GB` which
;;                can become a valid JVM Locale object.
;; 'kw-locale'  - A locale kw like `:en-GB`. These are the only locales we use
;;                with Cljs.
;;
;; The Clj localization API wraps JVM facilities so requires locales which are
;; or can become valid JVM Locale objects. In contrast, the translation API is
;; independent of any JVM facilities so can take arbitrary locales.

#+clj (def ^:private all-Locales (set (Locale/getAvailableLocales)))
#+clj
(defn- make-Locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code."
  ([lang]                 (Locale. lang))
  ([lang country]         (Locale. lang country))
  ([lang country variant] (Locale. lang country variant)))

#+clj
(defn try-jvm-locale
  "Like `jvm-locale` but returns nil if no valid matching Locale could be found."
  [loc & [lang-only?]]
  (when loc
    (cond
      (= :jvm-default loc)
      (if-not lang-only? (Locale/getDefault)
        (make-Locale (.getLanguage ^Locale (Locale/getDefault))))

      (instance? Locale loc)
      (if-not lang-only? loc
        (make-Locale (.getLanguage ^Locale loc)))

      :else
      (let [loc-parts (str/split (name loc) #"[-_]")]
        (all-Locales
          (if-not lang-only?
            (apply make-Locale loc-parts)
            (make-Locale (first loc-parts))))))))

#+clj
(def jvm-locale
  "Returns valid Locale matching given name string/keyword, or throws an
  exception if none could be found. `loc` should be of form :en, :en-US,
  :en-US-variant, or :jvm-default."
  (memoize
    (fn [loc & [lang-only?]]
      (or (try-jvm-locale loc lang-only?)
        (throw (ex-info (str "Invalid locale: " loc)
                 {:loc loc :lang-only? lang-only?}))))))

(comment
  (encore/qb 10000 (jvm-locale :en))
  (let [ls [nil :invalid :en-invalid :en-GB (Locale/getDefault)]]
    [(map #(try-jvm-locale %)            ls)
     (map #(try-jvm-locale % :lang-only) ls)]))

(def kw-locale "\"en_gb-var1\" -> :en-gb-var1, etc."
  (memoize
    (fn [?loc & [lang-only?]]
      (let [loc-name
            #+cljs (name (or ?loc :nil))
            #+clj  (if-let [jvm-loc (try-jvm-locale ?loc lang-only?)]
                     ;; (str jvm-loc) ; No! See [1] in comments below
                     (.toLanguageTag ^Locale jvm-loc)
                     (name (or ?loc :nil)))
            loc-name (str/replace loc-name "_" "-")
            loc-name (if-not lang-only? loc-name
                       (first (str/split loc-name #"-")))]
        (keyword loc-name)))))

(comment
  ;; [1] Ref. https://github.com/ptaoussanis/tower/issues/56 re: weird JVM
  ;; locale handling for certain locales:
  [(str (Locale. "id")) (Locale. "id") (.toLanguageTag (Locale. "id"))
   (kw-locale :id)]

  (map #(kw-locale %) [nil :whatever-foo :en (jvm-locale :en) "en-GB" :jvm-default])
  (map #(kw-locale % :lang-only) [nil :whatever-foo :en (jvm-locale :en) "en-GB" :jvm-default])

  )

;;;; Localization
;; The Java date API is a mess, but we (thankfully!) don't need much of it for
;; the simple date formatting+parsing that Tower provides. So while the
;; java.time (Java 8) and Joda-Time APIs are better, we choose instead to just
;; use the widely-available date API and patch over the relevant nasty bits.
;; This is an implementation detail from the perspective of lib consumers.
;;
;; Note that we use DateFormat rather than SimpleDateFormat since it offers
;; better facilities (esp. wider locale support, etc.).

;; Unlike SimpleDateFormat (with it's arbitrary patterns), DateFormat supports
;; a limited set of predefined locale-specific styles:
#+clj
(def ^:private ^:const dt-styles
  {:default DateFormat/DEFAULT
   :short   DateFormat/SHORT
   :medium  DateFormat/MEDIUM
   :long    DateFormat/LONG
   :full    DateFormat/FULL})

#+clj
(def ^:private parse-style "style -> [type subtype1 subtype2 ...]"
  (memoize
   (fn [style]
     (let [[type len1 len2] (if-not style [nil]
                              (map keyword (str/split (name style) #"-")))
           st1              (dt-styles (or len1      :default))
           st2              (dt-styles (or len2 len1 :default))]
       [type st1 st2]))))

;;; Some contortions here to get high performance thread-safe formatters (none
;;; of the java.text formatters are thread-safe!). Each constructor* call will
;;; return a memoized (=> shared) proxy, that'll return thread-local instances
;;; on `.get`.
;;
#+clj
(do
  (defmem-* f-date*             [Loc st] (DateFormat/getDateInstance st Loc))
  (defn-    f-date  ^DateFormat [Loc st] (.get (f-date* Loc st)))
  (defmem-* f-time*             [Loc st] (DateFormat/getTimeInstance st Loc))
  (defn-    f-time  ^DateFormat [Loc st] (.get (f-time* Loc st)))
  (defmem-* f-dt*            [Loc ds ts] (DateFormat/getDateTimeInstance ds ts Loc))
  (defn-    f-dt ^DateFormat [Loc ds ts] (.get (f-dt* Loc ds ts)))
  ;;
  (defmem-* f-number*                [Loc] (NumberFormat/getNumberInstance   Loc))
  (defn-    f-number   ^NumberFormat [Loc] (.get (f-number* Loc)))
  (defmem-* f-integer*               [Loc] (NumberFormat/getIntegerInstance  Loc))
  (defn-    f-integer  ^NumberFormat [Loc] (.get (f-integer* Loc)))
  (defmem-* f-percent*               [Loc] (NumberFormat/getPercentInstance  Loc))
  (defn-    f-percent  ^NumberFormat [Loc] (.get (f-percent* Loc)))
  (defmem-* f-currency*              [Loc] (NumberFormat/getCurrencyInstance Loc))
  (defn-    f-currency ^NumberFormat [Loc] (.get (f-currency* Loc))))

#+clj
(defprotocol     IFmt (pfmt [x loc style]))
#+clj
(extend-protocol IFmt
  Date
  (pfmt [dt loc style]
    (let [[type st1 st2] (parse-style style)]
      (case (or type :date)
        :date (.format (f-date loc st1)     dt)
        :time (.format (f-time loc st1)     dt)
        :dt   (.format (f-dt   loc st1 st2) dt)
        (throw (ex-info (str "Unknown style: " style)
                 {:style style})))))
  Number
  (pfmt [n loc style]
    (case (or style :number)
      :number   (.format (f-number   loc) n)
      :integer  (.format (f-integer  loc) n)
      :percent  (.format (f-percent  loc) n)
      :currency (.format (f-currency loc) n)
      (throw (ex-info (str "Unknown style: " style)
               {:style style})))))

#+clj
(defn fmt
  "Formats Date/Number as a string.
  `style` is <:#{date time dt}-#{default short medium long full}>,
  e.g. :date-full, :time-short, etc. (default :date-default)."
  [loc x & [style]] (pfmt x (jvm-locale loc) style))

#+clj
(defn parse
  "Parses date/number string as a Date/Number. See `fmt` for possible `style`s
  (default :number)."
  [loc s & [style]]
  (let [loc (jvm-locale loc)
        [type st1 st2] (parse-style style)]
    (case (or type :number)
      :number   (.parse (f-number   loc) s)
      :integer  (.parse (f-integer  loc) s)
      :percent  (.parse (f-percent  loc) s)
      :currency (.parse (f-currency loc) s)

      :date     (.parse (f-date loc st1)     s)
      :time     (.parse (f-time loc st1)     s)
      :dt       (.parse (f-dt   loc st1 st2) s)
      (throw (ex-info (str "Unknown style: " style)
               {:style style})))))

#+clj
(defmem- collator Collator [Loc] (Collator/getInstance Loc))
#+clj
(defn lcomparator "Returns localized comparator."
  [loc & [style]]
  (let [Col (collator (jvm-locale loc))]
    (case (or style :asc)
      :asc  #(.compare Col %1 %2)
      :desc #(.compare Col %2 %1)
      (throw (ex-info (str "Unknown style: " style)
               {:style style})))))

#+clj
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

#+clj
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
      (throw (ex-info (str "Unrecognized normalization form: " form)
               {:form form})))))

(comment (normalize "hello" :invalid))

;;;; Localized text formatting

;; (defmem- f-str Formatter [Loc] (Formatter. Loc))

#+clj
(defn fmt-str ; Requires a valid JVM locale
  "Like clojure.core/format but takes a locale, doesn't throw on nil pattern."
  ^String [loc fmt & args]
  (String/format (jvm-locale loc) (or fmt "") (to-array args)))

#+cljs
(defn fmt-str "Alpha - subject to change."
  ;; TODO We don't actually have any locale-aware cljs-side format fn
  [_loc fmt & args] (apply encore/format (or fmt "") args))

#+clj
(defn fmt-msg
  "Creates a localized MessageFormat and uses it to format given pattern string,
  substituting arguments as per MessageFormat spec."
  ^String [loc ^String pattern & args]
  (let [mformat (java.text.MessageFormat. (or pattern "") (jvm-locale loc))]
    (.format mformat (to-array args))))

(comment
  (fmt-msg :de "foobar {0}!" 102.22)
  (fmt-str :de "foobar %s!"  102.22)

  (fmt-msg :de "foobar {0,number,integer}!" 102.22)
  (fmt-str :de "foobar %d!" (int 102.22))

  ;; "choice" formatting is about the only redeeming quality of `fmt-msg`. Note
  ;; that choice text must be unescaped! Use `!` decorator:
  (map #(fmt-msg :de "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
        (range 5)))

;;;; Localized country & language names

#+clj
(def iso-countries (set (map (comp keyword str/lower-case) (Locale/getISOCountries))))

#+clj
(defn country-name "Experimental."
  [code display-loc]
  (let [loc (Locale. "" (name (encore/have iso-countries code)))
        display-loc (jvm-locale display-loc)]
    (.getDisplayCountry loc display-loc)))

(comment (country-name :za :de))

#+clj
(def iso-langs (set (map (comp keyword str/lower-case) (Locale/getISOLanguages))))

#+clj
(defn lang-name "Experimental."
  [code & [?display-loc]]
  (let [loc (Locale. (name (encore/have iso-langs code)))
        display-loc (if ?display-loc (jvm-locale ?display-loc) loc)]
    (.getDisplayLanguage loc display-loc)))

(comment (lang-name :en :de))

#+clj
(def get-countries
  "Experimental. Useful format for [sorted] lists, stitching into maps."
  (memoize
    (fn [loc & [?iso-countries]]
      (let [iso-countries (or ?iso-countries iso-countries)
            lcmp          (lcomparator loc)
            data          (map (fn [code] {:code code :name (country-name code loc)})
                            iso-countries)]
        ;; Sensible default sort
        (into [] (sort-by :name lcmp data))))))

(comment (get-langs :en))

#+clj
(def get-langs
  "Experimental. Useful format for [sorted] lists, stitching into maps."
  (memoize
    (fn [loc & [?iso-langs]]
      (let [iso-langs (or ?iso-langs iso-langs)
            lcmp      (lcomparator loc)
            data      (map (fn [code] {:code code :name (lang-name code loc)
                                      :own-name (lang-name code)})
                        iso-langs)]
        ;; Sensible default sort
        (into [] (sort-by :name lcmp data))))))

(comment (get-countries :en))

;;;; Timezones (doesn't depend on locales)

#+clj (def ^:private major-tz-regex
        #"^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.*")
#+clj (def tz-ids-all   (set (TimeZone/getAvailableIDs)))
#+clj (def tz-ids-major (set (filter #(re-find major-tz-regex %) tz-ids-all)))

#+clj
(defn timezone
  ([] (timezone "UTC"))
  ([tz-id]
     (let [tz-id (str/upper-case (encore/have string? tz-id))
           tz    (java.util.TimeZone/getTimeZone tz-id)]
       (when (= (.getID ^TimeZone tz) tz-id) tz))))

#+clj
(defn- tz-name "(GMT +05:30) Colombo"
  [city-tz-id offset]
  (let [[region city] (str/split city-tz-id #"/")
        offset-mins   (/ offset 1000 60)]
    (format "(GMT %s%02d:%02d) %s"
      (if (neg? offset-mins) "-" "+")
      (Math/abs (int (/ offset-mins 60)))
      (mod (int offset-mins) 60)
      (str/replace city "_" " "))))

(comment (tz-name "Asia/Bangkok" (* 90 60 1000)))

#+clj
(def get-timezones
  "Experimental. Useful format for [sorted] lists, stitching into maps."
  (encore/memoize* (encore/ms :hours 1) ; tz info varies with time
    (fn [& [?tz-ids]]
      (let [tz-ids  (or ?tz-ids tz-ids-major)
            instant (System/currentTimeMillis)
            data    (map (fn [id]
                           (let [tz     (TimeZone/getTimeZone id)
                                 offset (.getOffset tz instant)]
                             {:id     id
                              :offset offset
                              :tz     tz
                              :name   (tz-name id offset)}))
                      tz-ids)]
        ;; Sort by :offset, then :name on tie:
        (into [] (sort-by (fn keyfn [x] [(:offset x) (:name x)]) data))))))

(comment (get-timezones))

;;;; Translations

(def ^:dynamic *tscope* nil)
(defmacro ^:also-cljs with-tscope
  "Executes body with given translation scope binding."
  [translation-scope & body]
  `(binding [taoensso.tower/*tscope* ~translation-scope] ~@body))

(def scoped "Merges scope keywords: (scope :a.b :c/d :e) => :a.b.c.d/e"
  (memoize (fn [& ks] (encore/merge-keywords ks))))

(comment (scoped :a.b :c/d :e))

(def tscoped scoped) ; Alias for `:refer`s,

(def ^:private loc-tree
  "Returns intelligent, descending-preference vector of locale keys to search
  for given locale or vector of descending-preference locales."
  (let [loc-tree*
        (memoize
          (fn [loc] ; :en-GB-var1 -> [:en-GB-var1 :en-GB :en]
            (let [loc-parts (str/split (name (kw-locale loc)) #"-")]
              (mapv #(keyword (str/join "-" %))
                (take-while identity (iterate butlast loc-parts))))))]

    (fn [loc-or-locs] ; Expensive, perf-sensitive, not easily memo'd
      (if-not (vector? loc-or-locs)
        (loc-tree* loc-or-locs) ; Build search tree from single locale
        ;; Build search tree from multiple desc-preference locales (some
        ;; transducers would be nice here):
        ;; (encore/distinctv (reduce into (mapv loc-tree* loc-or-locs)))
        (let [lang-first-idxs ; {:en 0 :fr 1 ...}
              (zipmap (encore/distinctv (mapv (comp peek loc-tree*)
                                          loc-or-locs))
                (range))

              loc-score ; :en-GB -> -2
              (fn [loc]
                (let [tree   (loc-tree* loc)
                      lang   (peek  tree)
                      nparts (count tree)]
                  (- (* 10 (get lang-first-idxs lang)) nparts)))

              locs (reduce into [] (mapv loc-tree* loc-or-locs))
              sorted-locs (sort-by loc-score (encore/distinctv locs))]
          (vec sorted-locs))))))

(comment
  (loc-tree [:en-US :fr-FR :fr :en :DE-de]) ; [:en-US :en :fr-FR :fr :de-DE :de]
  (loc-tree [:en-US :en-GB]) ; [:en-US :en-GB :en]
  (encore/qb 10000 (loc-tree [:en-US :fr-FR :fr :en :DE-de])))

;;; tconfig

(defn default-tfmt-str "Implementation detail. Based on `encore/format`."
  #+clj ^String [ loc fmt & args]
  #+cljs        [_loc fmt & args] ; TODO Locale support?
  (let [fmt  (or fmt "") ; Prevent NPE
        args (mapv encore/nil->str args)]

    #+clj
    (if-let [jvm-locale (try-jvm-locale loc)]
      (String/format jvm-locale fmt (to-array args))
      (String/format            fmt (to-array args)) ; Ignore locale
      )

    #+cljs (apply gstr/format fmt args) ; Ignore locale
    ))

#+clj
(def example-tconfig
  "Example/test config as passed to `make-t`, Ring middleware, etc.

  :dictionary should be a map, or named resource containing a map of form
  {:locale {:ns1 ... {:nsN {:key<decorator> text ...} ...} ...} ...}}.

  Named resource will be watched for changes when `:dev-mode?` is true."
  {:dictionary ; Map or named resource containing map
   {:en   {:example {:foo         ":en :example/foo text"
                     :foo_comment "Hello translator, please do x"
                     :bar {:baz ":en :example.bar/baz text"}
                     :greeting "Hello %s, how are you?"
                     :inline-markdown "<tag>**strong**</tag>"
                     :block-markdown* "<tag>**strong**</tag>"
                     :with-exclaim!   "<tag>**strong**</tag>"
                     :greeting-alias  :example/greeting
                     :baz-alias       :example.bar/baz
                     :foo_undecorated ":en :foo_undecorated text"}
           :missing  "|Missing translation: [%1$s %2$s %3$s]|"}
    :en-US {:example {:foo ":en-US :example/foo text"}}
    :de    {:example {:foo ":de :example/foo text"}}
    ;; :ja "test_ja.clj" ; Import locale's map from external resource

    ;; Dictionaries support arbitrary locale keys (need not be recognized as
    ;; valid JVM Locales):
    :arbitrary {:example {:foo ":arbitrary :example/foo text"}}}

   :dev-mode? true ; Set to true for auto dictionary reloading
   :fallback-locale :de
   :scope-fn  (fn [] *tscope*) ; Experimental, undocumented
   :fmt-fn    default-tfmt-str ; (fn [loc fmt args])
   :log-missing-translation-fn
   (fn [{:keys [locales ks scope dev-mode?] :as args}]
     (let [pattern "Missing-translation: %s"]
       (if dev-mode?
         (timbre/debugf pattern args)
         (timbre/warnf  pattern args))))})

;;; Dictionaries

(comment ; ClojureScript
  (def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
  (def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj")))

#+clj
(def dict-load "Implementation detail."
  (let [load1
        (fn [dict] {:pre [(encore/have? [:or map? string?] dict)]}
          (if-not (string? dict) dict
            (try (-> dict io/resource io/reader slurp read-string)
                 (catch Exception e
                   (throw (ex-info (str "Failed to load dictionary from resource: " dict)
                            {:dict dict} e))))))]
    (fn [dict]
      (let [;; Load top-level dict:
            dict (encore/have map? (load1 dict))]
        ;;; Load any leaf maps + merge parent->child translations
        ;; With `t`'s new searching behaviour, it's no longer actually necessary that
        ;; we actually merge parent->child translations. Doing so anyway can yield a
        ;; small perf bump, but will also bring larger pre-compiled dicts into Cljs.
        ;; The merging behaviour here will likly be nixed shortly.
        (into {}
          (for [loc (keys dict)]
            (let [;; Import locale's map from another resource:
                  dict (if-not (string? (dict loc)) dict
                         (assoc dict loc (load1 (dict loc))))]
              [loc (apply encore/merge-deep (map dict (rseq (loc-tree loc))))])))))))

(defmacro ^:also-cljs dict-load* "Compile-time loader."
  [dict] (dict-load (eval dict)))

(comment
  (dict-load
    {:en        {:foo ":en foo"
                 :bar ":en :bar"}
     :en-US     {:foo ":en-US foo"}
     :ja        "test_ja.clj"
     :arbitrary {:foo ":arbitrary :example/foo text"}}))

#+clj
(def ^:private default-decorators
  "Experimental, currently undocumented."
  {:default
   [[".comment" #{:comment}]
    [".md!"     #{:md-inline}]
    [".mdb!"    #{:md-block}]
    [".md"      #{:md-inline :esc}]
    [".mdb"     #{:md-block  :esc}]
    ["!"        #{}]
    [""         #{:esc}] ; Always matches
    ]
   :reactjs ; Reactjs automatically escapes itself
   [[".comment" #{:comment}]
    ;;; For use with dangerouslySetInnerHTML:
    [".md!"     #{:md-inline}]
    [".mdb!"    #{:md-block}]
    [".md"      #{:md-inline :esc}]
    [".mdb"     #{:md-block  :esc}]
    ;; --------------------------------------
    ["!"        #{}]
    [""         #{}]]
   :legacy
   [["_comment" #{:comment}]
    ["_note"    #{:comment}]
    ["_html"    #{}]
    ["!"        #{}]
    ["_md"      #{:md-block  :esc}]
    ["*"        #{:md-block  :esc}]
    [""         #{:md-inline :esc}]]})

#+clj
(defn- match-decorators [decorators k]
  (let [kname (name k)
        [suffix optset :as match]
        (some (fn [[suffix optset :as match]]
                (when (encore/str-ends-with? kname suffix) match))
          (encore/have vector? decorators))

        k-without-suffix
        (if (= "" suffix) k
          (keyword (encore/substr kname 0 (- (count kname) (count suffix)))))]
    [(encore/have keyword? k-without-suffix)
     (encore/have set?     optset)]))

(comment (match-decorators (default-decorators :default) :hello))

#+clj
(defn- dict-compile-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:ns1.<...>.nsN/unscoped-key {:locale (f translation decorator)}}"
  [dict path & [{:keys [decorators] :or {decorators :legacy}}]]
  {:pre [(>= (count path) 3) (vector? path)]}
  (let [loc         (first  path)
        translation (peek   path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]
        unscoped-k  (peek (pop path))
        decorators        (or (get default-decorators decorators) decorators)
        [unscoped-k opt?] (match-decorators decorators unscoped-k)
        ?translation ; Resolve possible translation alias
        (if-not (keyword? translation) translation
          (let [target (get-in dict
                         (into [loc] (->> (encore/explode-keyword translation)
                                          (map keyword))))]
            (when-not (keyword? target) target)))]

    (when-let [tr ?translation]
      (when-not (opt? :comment)
        (let [esc  utils/html-escape
              md   utils/markdown
              tr*  tr
              tr*  (if-not (opt? :esc)       tr* (esc tr*))
              tr*  (if-not (opt? :md-inline) tr* (md {:inline? true}  tr*))
              tr*  (if-not (opt? :md-block)  tr* (md {:inline? false} tr*))]
          {(apply scoped (conj scope-ks unscoped-k)) {loc tr*}})))))

#+clj
(def dict-compile
  "Implementation detail.
  Compiles text translations stored in simple development-friendly Clojure map
  into form required by localized text translator:
    {:en {:example {:foo <tr>}}} => {:example/foo {:en <decorated-tr>}}"
  (let [compile-loaded-dict
        (memoize
          (fn [loaded-dict & [opts]]
            (->> loaded-dict
                 (utils/leaf-nodes)
                 (mapv #(dict-compile-path loaded-dict (vec %) opts))
                 ;; 1-level deep merge:
                 (apply merge-with merge))))]

    (fn [dict & [{:as opts :keys [dict-filter]}]]
      (let [loaded-dict (dict-load dict)
            loaded-dict (if-not dict-filter loaded-dict
                          (dict-filter loaded-dict))]
        (compile-loaded-dict loaded-dict opts)))))

(comment (encore/qb 1000 (dict-compile (:dictionary example-tconfig))))

(defmacro ^:also-cljs dict-compile* "Compile-time compiler."
  [dict & [opts]] (dict-compile (eval dict) (eval opts)))

;;;

(defn- nstr [x] (if (nil? x) "nil" (str x)))
(defn- find1 ; This fn is perf sensitive, but isn't easily memo'd
  ([dict scope k ltree] ; Find scoped
     (let [[l1 :as ls] ltree
           scoped-k ; (if-not scope k (scoped scope k))
           (scoped scope k) ; Even with nil scope, to get ns/kw form
           ]
       (if (next ls)
         (some #(get-in dict [scoped-k %]) ls)
         (do    (get-in dict [scoped-k l1])))))
  ([dict k ltree] ; Find unscoped
     (let [[l1 :as ls] ltree]
       (if (next ls)
         (some #(get-in dict [k %]) ls)
         (do    (get-in dict [k l1]))))))

(defn- make-t-uncached
  [tconfig] {:pre [(map? tconfig) #+clj (:dictionary tconfig)]}
  (let [{:keys [#+clj dictionary #+cljs compiled-dictionary
                dev-mode? fallback-locale scope-fn fmt-fn
                log-missing-translation-fn cache-locales?]
         :or   {fallback-locale :en
                cache-locales? #+clj false #+cljs true
                scope-fn (fn [] *tscope*)
                fmt-fn   default-tfmt-str
                log-missing-translation-fn
                (fn [{:keys [dev-mode?] :as args}]
                  (let [pattern "Missing-translation: %s"]
                    #+clj  (if dev-mode?
                             (timbre/debugf pattern args)
                             (timbre/warnf  pattern args))
                    #+cljs (if dev-mode?
                             (encore/debugf pattern args)
                             (encore/warnf  pattern args))))}} tconfig

        #+cljs _
        #+cljs (do (assert (:compiled-dictionary tconfig)
                     "Missing tconfig key: :compiled-dictionary")
                   (assert     (not (:dictionary tconfig))
                     "Invalid tconfig key: :dictionary"))

        ;; Nb `loc-tree` is expensive and not easily cached at the top-level,
        ;; but a _per_ `t` cache is trivial when `l-or-ls` is constant (e.g.
        ;; with the Ring middleware):
        loc-tree* (if cache-locales? (memoize loc-tree) loc-tree)
        get-dict
        #+cljs (fn [] compiled-dictionary)
        #+clj
        (let [compile1  (fn [] (dict-compile dictionary
                                ;; These opts are experimental, undocumented:
                                {:dict-filter (:dict-filter tconfig nil)
                                 :decorators  (:decorators  tconfig :legacy)}))
              cached_   (delay (compile1))
              ;; Blunt impact on dev-mode benchmarks, etc.:
              compile1* (encore/memoize* 2000 compile1)]
          (fn [] (if dev-mode? (compile1*) @cached_)))]

    (fn new-t [l-or-ls k-or-ks & fmt-args]
      (let [dict  (get-dict)
            ks    (if (vector? k-or-ks) k-or-ks [k-or-ks])
            ls    (if (vector? l-or-ls) l-or-ls [l-or-ls])
            [l1]  ls ; Preferred locale (always used for fmt)
            scope (scope-fn)
            ks?   (vector? k-or-ks)
            tr
            (or
              ;; Try locales & parents:
              (let [ltree (loc-tree* ls)]
                (if ks?
                  (some #(find1 dict scope % ltree) (take-while keyword? ks))
                  (find1 dict scope k-or-ks ltree)))

              (let [last-k (peek ks)]
                (if-not (keyword? last-k)
                  ;; Explicit final, non-keyword fallback (may be nil)
                  (if (nil? last-k) ::nil last-k)
                  (do
                    (when-let [log-f log-missing-translation-fn]
                      (log-f {:locales ls :scope scope :ks ks :dev-mode? dev-mode?}))
                    (or
                      ;; Try fallback-locale & parents:
                      (let [ltree (loc-tree* fallback-locale)]
                        (if ks?
                          (some #(find1 dict scope % ltree) ks)
                          (find1 dict scope k-or-ks ltree)))

                      ;; Try (unscoped) :missing in locales, parents,
                      ;; fallback-loc, & parents:
                      (let [ltree (loc-tree* (conj ls fallback-locale))]
                        (when-let [pattern (find1 dict :missing ltree)]
                          (fmt-fn l1 pattern (nstr ls) (nstr scope) (nstr ks)))))))))]

        (when-not (encore/kw-identical? tr ::nil)
          (let [tr (or tr "")]
            (if (nil? fmt-args) tr
              (apply fmt-fn l1 tr fmt-args))))))))

(def ^:private make-t-cached (memoize make-t-uncached))
(def make-t
  #+clj
  (fn [{:as tconfig :keys [dev-mode?]}]
    (if dev-mode? (make-t-uncached tconfig)
                  (make-t-cached   tconfig)))
  #+cljs make-t-uncached)

(comment
  (t :en-ZA example-tconfig :example/foo)
  (with-tscope :example (t :en-ZA example-tconfig :foo))
  (t :en example-tconfig :invalid)
  (t :en example-tconfig [:invalid :example/foo])
  (t :en example-tconfig [:invalid "Explicit fallback"])

  ;;; Invalid locales
  (t nil      example-tconfig :example/foo)
  (t :invalid example-tconfig :example/foo)

  (do
    (def prod-t (make-t (merge example-tconfig
                          {:dev-mode? false :cache-locales? true})))
    (encore/qb 10000
      (prod-t :en :example/foo)
      (prod-t :en [:invalid "fallback"]) ; Can act like gettext
      (prod-t :en [:invalid :example/foo])
      (prod-t :en [:invalid nil])
      (prod-t [:es-UY :ar-KW :sr-CS :en] [:invalid nil])))
  ;; [87.38 161.25 84.32 94.05] ; v3.0.2
  ;; [12.28  21.03 17.04 29.31] ; v3.1.0-SNAPSHOT (after perf work)
  )

#+clj (defn dictionary->xliff [m]) ; TODO Use hiccup?
#+clj (defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; DEPRECATED
;; The v2 API basically breaks everything. To allow lib consumers to migrate
;; gradually, the entire v1 API is reproduced below. This should allow Tower v2
;; to act as a quasi drop-in replacement for v1, despite the huge changes
;; under-the-covers.

#+clj
(do
  (def dev-mode?       "DEPRECATED." (atom true))
  (def fallback-locale "DEPRECATED." (atom :en))

  (def ^:dynamic *locale* nil)
  (defmacro with-locale "DEPRECATED."
    [loc & body] `(binding [*locale* (jvm-locale ~loc)] ~@body))

  (def ^:private migrate-tconfig
    (memoize
      (fn [tconfig scope]
        (assoc tconfig
          :scope-fn  (fn [] (scoped (:root-scope tconfig) *tscope*))
          :dev-mode? (if (contains? tconfig :dev-mode?)
                       (:dev-mode? tconfig) @dev-mode?)
          :fallback-locale (if (contains? tconfig :fallback-locale)
                             (:fallback-locale tconfig)
                             (or (:default-locale tconfig) @fallback-locale))))))

  (defn translate "DEPRECATED. Use `make-t` instead."
    [loc tconfig scope k-or-ks & fmt-args]
    (apply (make-t (migrate-tconfig tconfig scope)) loc k-or-ks fmt-args))

  (defn t "DEPRECATED. Use `make-t` instead."
    [loc tconfig k-or-ks & fmt-args]
    (apply (make-t (migrate-tconfig tconfig nil)) loc k-or-ks fmt-args))

  (defn parse-Locale "DEPRECATED: Use `locale` instead."
    [loc] (if (= loc :default) (jvm-locale :jvm-default) (jvm-locale loc)))

  (defn l-compare "DEPRECATED." [x y] (.compare (collator *locale*) x y))

  (defn format-number   "DEPRECATED." [x] (fmt *locale* x :number))
  (defn format-integer  "DEPRECATED." [x] (fmt *locale* x :integer))
  (defn format-percent  "DEPRECATED." [x] (fmt *locale* x :percent))
  (defn format-currency "DEPRECATED." [x] (fmt *locale* x :currency))

  (defn parse-number    "DEPRECATED." [s] (parse *locale* s :number))
  (defn parse-integer   "DEPRECATED." [s] (parse *locale* s :integer))
  (defn parse-percent   "DEPRECATED." [s] (parse *locale* s :percent))
  (defn parse-currency  "DEPRECATED." [s] (parse *locale* s :currency))

  (defn- new-style [& xs] (keyword (str/join "-" (map name xs))))

  (defn style "DEPRECATED."
    ([] :default)
    ([style] (or (dt-styles style)
               (throw (ex-info (str "Unknown style: " style)
                        {:style style})))))

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

  (def config "DEPRECATED." (atom example-tconfig))
  (defn set-config!   "DEPRECATED." [ks val] (swap! config assoc-in ks val))
  (defn merge-config! "DEPRECATED." [& maps] (apply swap! config encore/merge-deep maps))

  (defn load-dictionary-from-map-resource! "DEPRECATED."
    ([] (load-dictionary-from-map-resource! "tower-dictionary.clj"))
    ([resource-name & [merge?]]
       (try (let [new-dictionary (-> resource-name io/resource io/reader slurp
                                   read-string)]
              (if (= false merge?)
                (set-config!   [:dictionary] new-dictionary)
                (merge-config! {:dictionary  new-dictionary})))

            (set-config! [:dict-res-name] resource-name)
            (encore/file-resources-modified? resource-name)
            (catch Exception e
              (throw (ex-info (str "Failed to load dictionary from resource: "
                                resource-name)
                       {:resource-name resource-name} e))))))

  (defmacro with-scope "DEPRECATED." [translation-scope & body]
    `(with-tscope ~translation-scope ~@body))

  ;; BREAKS v1 due to unavoidable name clash
  (def oldt #(apply t (or *locale* :jvm-default) (assoc @config :fmt-fn fmt-msg) %&))

  (def     locale "DEPRECATED as of v2.1.0." jvm-locale)
  (def try-locale "DEPRECATED as of v2.1.0." try-jvm-locale)

  (def iso-languages      iso-langs)
  (def all-timezone-ids   tz-ids-all)
  (def major-timezone-ids tz-ids-major)

  (defn- get-localized-sorted-map
  "Returns {<localized-name> <iso-code>} sorted map."
  [iso-codes display-loc display-fn]
  (let [pairs (->> iso-codes (mapv (fn [code] [(display-fn code) code])))
        comparator (fn [ln-x ln-y] (.compare (collator (jvm-locale display-loc))
                                    ln-x ln-y))]
    (into (sorted-map-by comparator) pairs)))

  (def countries "DEPRECATED."
    (memoize
      (fn ([loc] (countries loc iso-countries))
        ([loc iso-countries]
           (get-localized-sorted-map iso-countries (jvm-locale loc)
             (fn [code] (.getDisplayCountry (Locale. "" (name code))
                         (jvm-locale loc))))))))

  (def languages "DEPRECATED."
    (memoize
      (fn ([loc] (languages loc iso-languages))
        ([loc iso-languages]
           (get-localized-sorted-map iso-languages (jvm-locale loc)
             (fn [code] (let [Loc (Locale. (name code))]
                         (str (.getDisplayLanguage Loc Loc) ; Lang, in itself
                           (when (not= Loc (jvm-locale loc :lang-only))
                             (format " (%s)" ; Lang, in current lang
                               (.getDisplayLanguage Loc (jvm-locale loc))))))))))))

  (def timezones "DEPRECATED."
    (encore/memoize* (* 3 60 60 1000) ; 3hr ttl
      (fn
        ([] (timezones major-timezone-ids))
        ([timezone-ids]
           (let [instant (System/currentTimeMillis)
                 tzs (->> timezone-ids
                       (mapv (fn [id]
                               (let [tz     (TimeZone/getTimeZone id)
                                     offset (.getOffset tz instant)]
                                 [(tz-name id offset) id offset]))))
                 tz-pairs (->> tzs (mapv   (fn [[dn id offset]] [dn id])))
                 offsets  (->> tzs (reduce (fn [m [dn id offset]] (assoc m dn offset)) {}))
                 comparator (fn [dn-x dn-y]
                              (let [cmp1 (compare (offsets dn-x) (offsets dn-y))]
                                (if-not (zero? cmp1) cmp1
                                        (compare dn-x dn-y))))]
             (into (sorted-map-by comparator) tz-pairs))))))

  (comment
    (reverse (sort ["-00:00" "+00:00" "-01:00" "+01:00" "-01:30" "+01:30"]))
    (count       (timezones))
    (take 5      (timezones))
    (take-last 5 (timezones)))

  (def sorted-localized-countries "DEPRECATED." (sorted-old countries))
  (def sorted-localized-languages "DEPRECATED." (sorted-old languages))
  (def sorted-timezones           "DEPRECATED." (sorted-old timezones)))
