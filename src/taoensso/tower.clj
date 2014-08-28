(ns taoensso.tower
  "Simple internationalization (i18n) and localization (L10n) library for
  Clojure. Wraps standard Java facilities when possible."
  {:author "Peter Taoussanis, Janne Asmala"}
  (:require [clojure.string  :as str]
            [clojure.java.io :as io]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre]
            [taoensso.tower.utils :as utils :refer (defmem- defmem-*)])
  (:import  [java.util Date Locale TimeZone Formatter]
            [java.text Collator NumberFormat DateFormat]))

;;;; Locales
;;; We use the following terms:
;; 'Locale'     - Valid JVM Locale object.
;; 'locale'     - Valid JVM Locale object, or a locale kw like `:en-GB`.
;; 'jvm-locale' - Valid JVM Locale object, or a locale kw like `:en-GB` which
;;                can become a valid JVM Locale object.
;; 'kw-locale'  - A locale kw like `:en-GB`.
;;
;; The localization API wraps JVM facilities so requires locales which are or
;; can become valid JVM Locale objects. In contrast, the translation API is
;; independent of any JVM facilities so can take arbitrary locales.

(def ^:private ensure-valid-Locale (set (Locale/getAvailableLocales)))
(defn- make-Locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code."
  ([lang]                 (Locale. lang))
  ([lang country]         (Locale. lang country))
  ([lang country variant] (Locale. lang country variant)))

(defn try-jvm-locale
  "Like `jvm-locale` but returns nil if no valid matching Locale could be found."
  [loc & [lang-only?]]
  (when loc
    (cond
     (identical? :jvm-default loc)
     (if-not lang-only? (Locale/getDefault)
       (make-Locale (.getLanguage ^Locale (Locale/getDefault))))

     (instance? Locale loc)
     (if-not lang-only? loc
       (make-Locale (.getLanguage ^Locale loc)))

     :else
     (let [loc-parts (str/split (name loc) #"[-_]")]
       (ensure-valid-Locale
        (if-not lang-only?
          (apply make-Locale loc-parts)
          (make-Locale (first loc-parts))))))))

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
  (time (dotimes [_ 10000] (jvm-locale :en)))
  (let [ls [nil :invalid :en-invalid :en-GB (Locale/getDefault)]]
    [(map #(try-jvm-locale %)            ls)
     (map #(try-jvm-locale % :lang-only) ls)]))

(def kw-locale
  (memoize
    (fn [?loc]
      (let [loc-name (if-let [jvm-loc (try-jvm-locale ?loc)]
                       (str jvm-loc)
                       (name (or ?loc :nil)))]
        (keyword (str/replace loc-name "_" "-"))))))

(comment (map kw-locale [nil :whatever-foo :en (jvm-locale :en) "en-GB"
                         :jvm-default]))

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

;;; Some contortions here to get high performance thread-safe formatters (none
;;; of the java.text formatters are thread-safe!). Each constructor* call will
;;; return a memoized (=> shared) proxy, that'll return thread-local instances
;;; on `.get`.
;;
(defmem-* f-date*             [Loc st] (DateFormat/getDateInstance st Loc)) ; proxy
(defn-    f-date  ^DateFormat [Loc st] (.get (f-date* Loc st)))
(defmem-* f-time*             [Loc st] (DateFormat/getTimeInstance st Loc)) ; proxy
(defn-    f-time  ^DateFormat [Loc st] (.get (f-time* Loc st)))
(defmem-* f-dt*            [Loc ds ts] (DateFormat/getDateTimeInstance ds ts Loc)) ; proxy
(defn-    f-dt ^DateFormat [Loc ds ts] (.get (f-dt* Loc ds ts)))
;;
(defmem-* f-number*                [Loc] (NumberFormat/getNumberInstance   Loc)) ; proxy
(defn-    f-number   ^NumberFormat [Loc] (.get (f-number* Loc)))
(defmem-* f-integer*               [Loc] (NumberFormat/getIntegerInstance  Loc)) ; proxy
(defn-    f-integer  ^NumberFormat [Loc] (.get (f-integer* Loc)))
(defmem-* f-percent*               [Loc] (NumberFormat/getPercentInstance  Loc)) ; proxy
(defn-    f-percent  ^NumberFormat [Loc] (.get (f-percent* Loc)))
(defmem-* f-currency*              [Loc] (NumberFormat/getCurrencyInstance Loc)) ; proxy
(defn-    f-currency ^NumberFormat [Loc] (.get (f-currency* Loc)))

(defprotocol     IFmt (pfmt [x loc style]))
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

(defn fmt
  "Formats Date/Number as a string.
  `style` is <:#{date time dt}-#{default short medium long full}>,
  e.g. :date-full, :time-short, etc. (default :date-default)."
  [loc x & [style]] (pfmt x (jvm-locale loc) style))

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

(defmem- collator Collator [Loc] (Collator/getInstance Loc))
(defn lcomparator "Returns localized comparator."
  [loc & [style]]
  (let [Col (collator (jvm-locale loc))]
    (case (or style :asc)
      :asc  #(.compare Col %1 %2)
      :desc #(.compare Col %2 %1)
      (throw (ex-info (str "Unknown style: " style)
               {:style style})))))

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
      (throw (ex-info (str "Unrecognized normalization form: " form)
               {:form form})))))

(comment (normalize "hello" :invalid))

;;;; Localized text formatting

;; (defmem- f-str Formatter [Loc] (Formatter. Loc))

(defn fmt-str "Like clojure.core/format but takes a locale."
  ^String [loc fmt & args] (String/format (jvm-locale loc) fmt (to-array args)))

(defn fmt-msg
  "Creates a localized MessageFormat and uses it to format given pattern string,
  substituting arguments as per MessageFormat spec."
  ^String [loc ^String pattern & args]
  (let [mformat (java.text.MessageFormat. pattern (jvm-locale loc))]
    (.format mformat (to-array args))))

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
        comparator (fn [ln-x ln-y] (.compare (collator (jvm-locale display-loc))
                                            ln-x ln-y))]
    (into (sorted-map-by comparator) pairs)))

(def iso-countries (->> (Locale/getISOCountries)
                        (mapv (comp keyword str/lower-case)) (set)))

(def countries "Returns (sorted-map <localized-name> <iso-code> ...)."
  (memoize
   (fn ([loc] (countries loc iso-countries))
      ([loc iso-countries]
         (get-localized-sorted-map iso-countries (jvm-locale loc)
           (fn [code] (.getDisplayCountry (Locale. "" (name code))
                       (jvm-locale loc))))))))

(def iso-languages (->> (Locale/getISOLanguages)
                        (mapv (comp keyword str/lower-case)) (set)))

(def languages "Returns (sorted-map <localized-name> <iso-code> ...)."
  (memoize
   (fn ([loc] (languages loc iso-languages))
      ([loc iso-languages]
         (get-localized-sorted-map iso-languages (jvm-locale loc)
           (fn [code] (let [Loc (Locale. (name code))]
                       (str (.getDisplayLanguage Loc Loc) ; Lang, in itself
                         (when (not= Loc (jvm-locale loc :lang-only))
                           (format " (%s)" ; Lang, in current lang
                             (.getDisplayLanguage Loc (jvm-locale loc))))))))))))

(comment (countries :en)
         (languages :pl    [:en :de :pl])
         (languages :en-GB [:en :de :pl]))

;;;; Timezones (doesn't depend on locales)

(def all-timezone-ids (set (TimeZone/getAvailableIDs)))
(def major-timezone-ids
  (->> all-timezone-ids
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
  (encore/memoize* (* 3 60 60 1000) ; 3hr ttl
    (fn
      ([] (timezones major-timezone-ids))
      ([timezone-ids]
         (let [instant (System/currentTimeMillis)
               tzs (->> timezone-ids
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
           (into (sorted-map-by comparator) tz-pairs))))))

(comment
  (reverse (sort ["-00:00" "+00:00" "-01:00" "+01:00" "-01:30" "+01:30"]))
  (count       (timezones))
  (take 5      (timezones))
  (take-last 5 (timezones)))

;;;; Translations

(declare dev-mode? fallback-locale) ; DEPRECATED

(def scoped "Merges scope keywords: (scope :a.b :c/d :e) => :a.b.c.d/e"
  (memoize (fn [& ks] (encore/merge-keywords ks))))

(comment (scoped :a.b :c/d :e))

(def ^:dynamic *tscope* nil)
(defmacro ^:also-cljs with-tscope
  "Executes body with given translation scope binding."
  [translation-scope & body]
  `(binding [taoensso.tower/*tscope* ~translation-scope] ~@body))

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
    :ja "test_ja.clj" ; Import locale's map from external resource

    ;; Dictionaries support arbitrary locale keys (need not be recognized as
    ;; valid JVM Locales):
    :arbitrary {:example {:foo ":arbitrary :example/foo text"}}}

   :dev-mode? true ; Set to true for auto dictionary reloading
   :fallback-locale :de
   :scope-fn  (fn [k] (scoped *tscope* k)) ; Experimental, undocumented
   :fmt-fn    fmt-str ; (fn [loc fmt args])
   :log-missing-translation-fn
   (fn [{:keys [locale ks scope] :as args}]
     (timbre/logp (if dev-mode? :debug :warn)
       "Missing translation" args))})

;;; Dictionaries

(defn- dict-load [dict] {:pre [(or (map? dict) (string? dict))]}
  (if-not (string? dict) dict
    (try (-> dict io/resource io/reader slurp read-string)
      (catch Exception e
        (throw (ex-info (str "Failed to load dictionary from resource: " dict)
                 {:dict dict} e))))))

(def loc-tree
  "Implementation detail.
  Returns intelligent, descending-preference vector of locale keys to search
  for given locale or vector of descending-preference locales."
  (let [loc-tree*
        (memoize
          (fn [loc]
            (let [loc-parts (str/split (-> loc kw-locale name) #"[-_]")
                  loc-tree  (mapv #(keyword (str/join "-" %))
                              (take-while identity (iterate butlast loc-parts)))]
              loc-tree)))
        loc-primary (memoize (fn [loc] (peek  (loc-tree* loc))))
        loc-nparts  (memoize (fn [loc] (count (loc-tree* loc))))]
    (encore/memoize* 80000 nil ; Also used runtime by translation fns
      (fn [loc-or-locs]
        (if-not (vector? loc-or-locs)
          (loc-tree* loc-or-locs) ; Build search tree from single locale
          ;; Build search tree from multiple desc-preference locales:
          (let [primary-locs (->> loc-or-locs (mapv loc-primary) (encore/distinctv))
                primary-locs-sort (zipmap primary-locs (range))]
            (->> loc-or-locs
                 (mapv loc-tree*)
                 (reduce into)
                 (encore/distinctv)
                 (sort-by #(- (* 10 (primary-locs-sort (loc-primary %) 0))
                              (loc-nparts %)))
                 (vec))))))))

(comment
  (loc-tree [nil :whatever-foo :en]) ; [:nil :whatever-foo :whatever :en]
  (loc-tree :en-US)   ; [:en-US :en]
  (loc-tree [:en-US]) ; [:en-US :en]
  (loc-tree [:en-GB :en-US])     ; [:en-GB :en-US :en]
  (loc-tree [:en-GB :en :en-US]) ; [:en-GB :en-US :en]
  (loc-tree [:en-GB :fr-FR :en-US]) ; [:en-GB :en-US :en :fr-FR :fr]
  (loc-tree [:en-US :fr-FR :fr :en :DE-de]) ; [:en-US :en :fr-FR :fr :de-DE :de]
  (time (dotimes [_ 10000] (loc-tree [:en-US :fr-FR :fr :en :DE-de]))))

(defn- dict-inherit-parent-trs
  "Merges each locale's translations over its parent locale translations."
  [dict] {:pre [(map? dict)]}
  (into {}
   (for [loc (keys dict)]
     (let [loc-tree' (loc-tree loc)
           ;; Import locale's map from another resource:
           dict      (if-not (string? (dict loc)) dict
                       (assoc dict loc (dict-load (dict loc))))]
       [loc (apply encore/merge-deep (mapv dict (rseq loc-tree')))]))))

(comment
  (dict-inherit-parent-trs
    {:en        {:foo ":en foo"
                 :bar ":en :bar"}
     :en-US     {:foo ":en-US foo"}
     :ja        "test_ja.clj"
     :arbitrary {:foo ":arbitrary :example/foo text"}}))

(def ^:private dict-prepare (comp dict-inherit-parent-trs dict-load))

(defn- dict-compile-path
  "[:locale :ns1 ... :nsN unscoped-key<decorator> translation] =>
  {:ns1.<...>.nsN/unscoped-key {:locale (f translation decorator)}}"
  [dict path] {:pre [(>= (count path) 3) (vector? path)]}
  (let [loc         (first  path)
        translation (peek   path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsN]

        unscoped-k     (peek (pop path))
        unscoped-kname (name unscoped-k)

        decorators [:_comment :_note :_html :! :_md :*]
        ?decorator (some #(when (encore/str-ends-with? unscoped-kname (name %)) %)
                      decorators)

        unscoped-k (if-not ?decorator unscoped-k
                     (keyword (encore/substr unscoped-kname 0
                                (- (count unscoped-kname)
                                   (count (name ?decorator))))))

        translation ; Resolve possible translation alias
        (if-not (keyword? translation) translation
          (let [target (get-in dict
                         (into [loc] (->> (encore/explode-keyword translation)
                                          (mapv keyword))))]
            (when-not (keyword? target) target)))]

    (when translation
      (when-let [translation*
                 (case ?decorator
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

(def dict-compile* (comp dict-compile-prepared dict-prepare)) ; Public for cljs macro
(comment (time (dotimes [_ 1000] (dict-compile* (:dictionary example-tconfig)))))

(defmacro ^:only-cljs dict-compile
  "Tower's standard dictionary compiler, as a compile-time macro. For use with
  ClojureScript."
  [dict] (dict-compile* dict))

;;;

(defn- make-t-uncached
  [tconfig] {:pre [(map? tconfig) (:dictionary tconfig)]}
  (let [{:keys [dictionary dev-mode? fallback-locale scope-fn fmt-fn
                log-missing-translation-fn]
         :or   {fallback-locale :en
                scope-fn (fn [k] (scoped *tscope* k))
                fmt-fn   fmt-str
                log-missing-translation-fn
                (fn [{:keys [locale ks scope] :as args}]
                  (timbre/logp (if dev-mode? :debug :warn)
                    "Missing translation" args))}} tconfig]

    (let [nstr          (fn [x] (if (nil? x) "nil" (str x)))
          dict-cached   (when-not dev-mode? (dict-compile* dictionary))
          find-scoped   (fn [d k l] (some #(get-in d [(scope-fn k) %]) (loc-tree l)))
          find-unscoped (fn [d k l] (some #(get-in d [          k  %]) (loc-tree l)))]

      (fn new-t [loc-or-locs k-or-ks & fmt-args]
        (let [l-or-ls loc-or-locs
              dict (or dict-cached (dict-compile* dictionary)) ; Recompile (slow)
              ks   (if (vector? k-or-ks) k-or-ks [k-or-ks])
              ls   (if (vector? l-or-ls) l-or-ls [l-or-ls])
              loc1 (nth ls 0) ; Preferred locale (always used for fmt)
              tr
              (or
               ;; Try locales & parents:
               (some #(find-scoped dict % l-or-ls) (take-while keyword? ks))
               (let [last-k (peek ks)]
                 (if-not (keyword? last-k)
                   last-k ; Explicit final, non-keyword fallback (may be nil)
                   (do
                     (when-let [log-f log-missing-translation-fn]
                       (log-f {:locales ls :scope (scope-fn nil) :ks ks
                               :dev-mode? dev-mode? :ns (str *ns*)}))
                     (or
                      ;; Try fallback-locale & parents:
                      (some #(find-scoped dict % fallback-locale) ks)

                      ;; Try :missing in locales, parents, fallback-loc, & parents:
                      (when-let [pattern
                                 (or (find-unscoped dict :missing l-or-ls)
                                     (find-unscoped dict :missing fallback-locale))]
                        (fmt-fn loc1 pattern (nstr ls) (nstr (scope-fn nil))
                          (nstr ks))))))))]

          (if (nil? fmt-args)
            tr
            (if (nil? tr)
              (throw (ex-info "Can't format nil translation pattern."
                       {:tr tr :fmt-args fmt-args}))
              (apply fmt-fn loc1 tr fmt-args))))))))

(def ^:private make-t-cached (memoize make-t-uncached))
(defn make-t
  "Returns a new translation fn for given config map:
  (make-t example-tconfig) => (fn t [locale k-or-ks & fmt-args]).
  See `example-tconfig` for config details."
  [{:as tconfig :keys [dev-mode?]}]
  (if dev-mode? (make-t-uncached tconfig)
                (make-t-cached   tconfig)))

(comment
  (t :en-ZA example-tconfig :example/foo)
  (with-tscope :example (t :en-ZA example-tconfig :foo))
  (t :en example-tconfig :invalid)
  (t :en example-tconfig [:invalid :example/foo])
  (t :en example-tconfig [:invalid "Explicit fallback"])

  ;;; Invalid locales
  (t nil      example-tconfig :example/foo)
  (t :invalid example-tconfig :example/foo)

  (def prod-t (make-t (assoc example-tconfig :dev-mode? false)))
  (time (dotimes [_ 10000] (prod-t :en :example/foo)))            ; ~18ms
  (time (dotimes [_ 10000] (prod-t :en [:invalid :example/foo]))) ; ~38ms
  (time (dotimes [_ 10000] (prod-t :en [:invalid nil])))          ; ~20ms
  (time (dotimes [_ 10000] (prod-t [:es-UY :ar-KW :sr-CS :en]
                             [:invalid nil]))) ; ~90ms for 14 lookups
  )

(defn dictionary->xliff [m]) ; TODO Use hiccup?
(defn xliff->dictionary [s]) ; TODO Use clojure.xml/parse?

;;;; DEPRECATED
;; The v2 API basically breaks everything. To allow lib consumers to migrate
;; gradually, the entire v1 API is reproduced below. This should allow Tower v2
;; to act as a quasi drop-in replacement for v1, despite the huge changes
;; under-the-covers.

(def ^:dynamic *locale* nil)
(defmacro with-locale "DEPRECATED."
  [loc & body] `(binding [*locale* (jvm-locale ~loc)] ~@body))

(def ^:private migrate-tconfig
  (memoize
   (fn [tconfig scope]
     (assoc tconfig
       :scope-fn  (fn [k] (scoped (:root-scope tconfig) *tscope* scope k))
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

(def dev-mode?       "DEPRECATED." (atom true))
(def fallback-locale "DEPRECATED." (atom :en))

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

(defn- new-style [& xs] (keyword (str/join "-" (mapv name xs))))

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

(def sorted-localized-countries "DEPRECATED." (sorted-old countries))
(def sorted-localized-languages "DEPRECATED." (sorted-old languages))
(def sorted-timezones           "DEPRECATED." (sorted-old timezones))

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
