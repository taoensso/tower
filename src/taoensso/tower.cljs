(ns taoensso.tower
  "Experimental ClojureScript support for Tower."
  {:author "Peter Taoussanis"}
  (:require-macros [taoensso.tower :as tower-macros])
  (:require [clojure.string  :as str]
            [taoensso.encore :as encore]))

;;;; TODO
;; * NB: Locale-aware format fn for fmt-str.
;; * Localization stuff?

;;;; Utils

(def ^:crossover scoped (memoize (fn [& ks] (encore/merge-keywords ks))))

(defn- fmt-str
  "goog.string's `format` was removed from cljs.core 0.0-1885,
  Ref. http://goo.gl/su7Xkj"
  [_loc fmt & args] (apply encore/format fmt args))

;;;; Config

(def ^:dynamic *locale* nil)
(def ^:dynamic *tscope* nil)

(def locale-key ; Crossover (modified)
  (memoize #(keyword (str/replace (name %) #_(str (locale %)) "_" "-"))))

(def locale locale-key)

;;;; Localization

;; Nothing here yet

;;;; Translations

(comment ; Dictionaries
  (def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
  (def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj")))

(def ^:private loc-tree
  (let [loc-tree*
        (memoize
          (fn [loc]
            (let [loc-parts (str/split (-> loc locale-key name) #"[-_]")
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

(defn make-t ; Crossover (modified)
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
                  (encore/log (str "Missing translation" args)))}} tconfig]

    (assert (:compiled-dictionary tconfig) "Missing tconfig key: :compiled-dictionary")
    (assert (not (:dictionary tconfig))    "Invalid tconfig key: :dictionary")

    (let [nstr          (fn [x] (if (nil? x) "nil" (str x)))
          dict-cached   compiled-dictionary
          find-scoped   (fn [d k l] (some #(get-in d [(scope-fn k) %]) (loc-tree l)))
          find-unscoped (fn [d k l] (some #(get-in d [          k  %]) (loc-tree l)))]

      (fn new-t [loc-or-locs k-or-ks & fmt-args]
        (let [l-or-ls loc-or-locs
              dict (or dict-cached ; (dict-compile-uncached dictionary)
                       )
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
                               :dev-mode? dev-mode? ; :ns (str *ns*)
                               }))
                     (or
                      ;; Try fallback-locale & parents:
                      (some #(find-scoped dict % fallback-locale) ks)

                      ;; Try :missing in locales, parents, fallback-loc, & parents:
                      (when-let [pattern
                                 (or (find-unscoped dict :missing l-or-ls)
                                     (find-unscoped dict :missing fallback-locale))]
                        (fmt-fn loc1 pattern (nstr ls) (nstr (scope-fn nil))
                          (nstr ks))))))))]

          (if (nil? fmt-args) tr
            (if (nil? tr) (throw (js/Error. "Can't format nil translation pattern."))
              (apply fmt-fn loc1 tr fmt-args))))))))
