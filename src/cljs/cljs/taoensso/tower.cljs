(ns cljs.taoensso.tower
  "EXPERIMENTAL ClojureScript support for Tower.
  PRE-alpha - almost certain to change."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [goog.string    :as gstr]
            [goog.string.format])
  (:require-macros [cljs.taoensso.tower :as tower-macros]))

;;;; TODO
;; * NB: Locale-aware format fn for fmt-str.
;; * Move utils to dedicated ns?
;; * Migrate to cljx codebase?
;; * Localization stuff?

;;;; Utils

(defn- log [x]
  (if (js* "typeof console != 'undefined'")
    (.log js/console x)
    (js/print x))
  nil)

(defn- ^:crossover fq-name
  [x] (if (string? x) x
        (let [n (name x)]
          (if-let [ns (namespace x)] (str ns "/" n) n))))

(defn- ^:crossover explode-keyword [k] (str/split (fq-name k) #"[\./]"))
(defn- ^:crossover merge-keywords  [ks & [as-ns?]]
  (let [parts (->> ks (filterv identity) (mapv explode-keyword) (reduce into []))]
    (when-not (empty? parts)
      (if as-ns? ; Don't terminate with /
        (keyword (str/join "." parts))
        (let [ppop (pop parts)]
          (keyword (when-not (empty? ppop) (str/join "." ppop))
                   (peek parts)))))))

(def ^:crossover scoped (memoize (fn [& ks] (merge-keywords ks))))

(defn- fmt-str "Removed from cljs.core 0.0-1885, Ref. http://goo.gl/su7Xkj"
  [_loc fmt & args] (apply gstr/format fmt args))

;;;; Config

(def ^:dynamic *locale* nil)
(def ^:dynamic *tscope* nil)

(def ^:crossover locale-key ; Careful - subtle diff from jvm version:
  (memoize #(keyword (str/replace (name %) #_(str (locale %)) "_" "-"))))

(def locale locale-key)

;;;; Localization ; TODO

;;;; Translations

(comment ; Dictionaries
  (def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
  (def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj")))

(def ^:crossover loc-tree
  (memoize ; Also used runtime by `translate` fn
   (fn [loc]
     (let [loc-parts (str/split (-> loc locale-key name) #"[-_]")
           loc-tree  (mapv #(keyword (str/join "-" %))
                           (take-while identity (iterate butlast loc-parts)))]
       loc-tree))))

(defn make-t
  [tconfig] {:pre [(map? tconfig) ; (:dictionary tconfig)
                   ]}
  (let [{:keys [compiled-dictionary ; dictionary
                dev-mode? fallback-locale scope-fn fmt-fn
                log-missing-translation-fn]
         :or   {fallback-locale :en
                scope-fn (fn [k] (scoped *tscope* k))
                fmt-fn   fmt-str
                log-missing-translation-fn
                (fn [{:keys [locale ks scope] :as args}]
                  (log (str "Missing translation" args)))}} tconfig]

    (assert (:compiled-dictionary tconfig) "Missing tconfig key: :compiled-dictionary")
    (assert (not (:dictionary tconfig))    "Invalid tconfig key: :dictionary")

    (let [nstr (fn [x] (if (nil? x) "nil" (str x)))
          dict-cached   compiled-dictionary
          ;; (when-not dev-mode? (dict-compile-cached dictionary))
          ;;; Could cache these for extra perf (probably overkill):
          find-scoped   (fn [d k l] (some #(get-in d [(scope-fn k) %]) (loc-tree l)))
          find-unscoped (fn [d k l] (some #(get-in d [          k  %]) (loc-tree l)))]

      (fn new-t [loc k-or-ks & fmt-args]
        (let [dict (or dict-cached ; (dict-compile-uncached dictionary)
                       )
              ks   (if (vector? k-or-ks) k-or-ks [k-or-ks])
              tr
              (or
               ;; Try loc & parents:
               (some #(find-scoped dict % loc) (take-while keyword? ks))
               (let [last-k (peek ks)]
                 (if-not (keyword? last-k)
                   last-k ; Explicit final, non-keyword fallback (may be nil)
                   (do
                     (when-let [log-f log-missing-translation-fn]
                       (log-f {:locale loc :scope (scope-fn nil) :ks ks
                               :dev-mode? dev-mode? ; :ns (str *ns*)
                               }))
                     (or
                      ;; Try fallback-locale & parents:
                      (some #(find-scoped dict % fallback-locale) ks)

                      ;; Try :missing in loc, parents, fallback-loc, & parents:
                      (when-let [pattern
                                 (or (find-unscoped dict :missing loc)
                                     (find-unscoped dict :missing fallback-locale))]
                        (fmt-fn loc pattern (nstr loc) (nstr (scope-fn nil))
                          (nstr ks))))))))]

          (if (nil? fmt-args) tr
            (if (nil? tr) (throw (js/Error. "Can't format nil translation pattern."))
              (apply fmt-fn loc tr fmt-args))))))))
