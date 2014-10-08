(ns taoensso.tower
  "Tower ClojureScript stuff - still pretty limited."
  {:author "Peter Taoussanis"}
  (:require-macros [taoensso.tower :as tower-macros])
  (:require [clojure.string  :as str]
            [taoensso.encore :as encore]))

;;;; Localization ; TODO Maybe later?

;;;; Utils

(def scoped ; Crossover (direct)
  (memoize (fn [& ks] (encore/merge-keywords ks))))

(defn- fmt-str
  "goog.string's `format` was removed from cljs.core 0.0-1885,
  Ref. http://goo.gl/su7Xkj"
  ;; TODO Locale-aware format fn would be nice, but no obvious+easy way of
  ;; implementing one to get Java-like semantics (?)
  [_loc fmt & args] (apply encore/format (or fmt "") args))

;;;; Translations

(def ^:dynamic *tscope* nil)

(comment ; Dictionaries
  (def my-dict-inline   (tower-macros/dict-compile {:en {:a "**hello**"}}))
  (def my-dict-resource (tower-macros/dict-compile "slurps/i18n/utils.clj")))

(def kw-locale ; Crossover (modified)
  (memoize
    (fn [?loc & [lang-only?]]
      (let [loc-name (name (or ?loc :nil))
            loc-name (str/replace loc-name "_" "-")
            loc-name (if-not lang-only? loc-name
                       (first (str/split loc-name #"-")))]
        (keyword loc-name)))))

(def loc-tree ; Crossover (direct)
  (let [loc-tree*
        (memoize
          (fn [loc]
            (let [loc-parts (str/split (-> loc kw-locale name) #"-")
                  loc-tree  (map #(keyword (str/join "-" %))
                              (take-while identity (iterate butlast loc-parts)))]
              loc-tree)))
        loc-primary (memoize (fn [loc] (peek  (loc-tree* loc))))
        loc-nparts  (memoize (fn [loc] (count (loc-tree* loc))))]
    (encore/memoize* 80000 nil ; Also used runtime by translation fns
      (fn [loc-or-locs]
        (if-not (vector? loc-or-locs)
          (loc-tree* loc-or-locs) ; Build search tree from single locale
          ;; Build search tree from multiple desc-preference locales:
          (let [primary-locs (->> loc-or-locs (map loc-primary) (encore/distinctv))
                primary-locs-sort (zipmap primary-locs (range))]
            (->> loc-or-locs
                 (map loc-tree*)
                 (reduce into)
                 (encore/distinctv)
                 (sort-by #(- (* 10 (primary-locs-sort (loc-primary %) 0))
                              (loc-nparts %)))
                 (take 6) ; Cap potential perf hit of searching loc-tree
                 (vec))))))))

(defn- nstr [x] (if (nil? x) "nil" (str x)))
(defn- find1 ; This fn is perf sensitive, but isn't easily memo'd
  ([dict scope k l-or-ls] ; Find scoped
     (let [[l1 :as ls] (loc-tree l-or-ls)
           scoped-k    (if-not scope k (scoped scope k))]
       (if (next ls)
         (some #(get-in dict [scoped-k %]) ls)
         (do    (get-in dict [scoped-k l1])))))
  ([dict k l-or-ls] ; Find unscoped
     (let [[l1 :as ls] (loc-tree l-or-ls)]
       (if (next ls)
         (some #(get-in dict [k %]) ls)
         (do    (get-in dict [k l1]))))))

(defn make-t ; Crossover (modified)
  [tconfig] {:pre [(map? tconfig) ; (:dictionary tconfig)
                   ]}
  (let [{:keys [compiled-dictionary ; dictionary
                dev-mode? fallback-locale scope-fn fmt-fn
                log-missing-translation-fn]
         :or   {fallback-locale :en
                scope-fn (fn [] *tscope*)
                fmt-fn   fmt-str
                log-missing-translation-fn
                (fn [{:keys [locale ks scope] :as args}]
                  (encore/log (str "Missing translation" args)))}} tconfig

        _ (assert (:compiled-dictionary tconfig)  "Missing tconfig key: :compiled-dictionary")
        _ (assert     (not (:dictionary tconfig)) "Invalid tconfig key: :dictionary")

        dict-cached compiled-dictionary]

    (fn new-t [l-or-ls k-or-ks & fmt-args]
      (let [dict   (or dict-cached #_(dict-compile* dictionary))
            ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])
            ls     (if (vector? l-or-ls) l-or-ls [l-or-ls])
            [loc1] ls ; Preferred locale (always used for fmt)
            scope  (scope-fn)
            ks?    (sequential? k-or-ks)
            tr
            (or
              ;; Try locales & parents:
              (if ks?
                (some #(find1 dict scope % l-or-ls) (take-while keyword? ks))
                (find1 dict scope k-or-ks l-or-ls))

              (let [last-k (peek ks)]
                (if-not (keyword? last-k)
                  last-k ; Explicit final, non-keyword fallback (may be nil)
                  (do
                    (when-let [log-f log-missing-translation-fn]
                      (log-f {:locales ls :scope scope :ks ks
                              :dev-mode? dev-mode? ; :ns (str *ns*)
                              }))
                    (or
                      ;; Try fallback-locale & parents:
                      (if ks?
                        (some #(find1 dict scope % fallback-locale) ks)
                        ((find1 dict scope k-or-ks fallback-locale)))

                      ;; Try (unscoped) :missing in locales, parents, fallback-loc, & parents:
                      (when-let [pattern (find1 dict :missing (conj ls fallback-locale))]
                        (fmt-fn loc1 pattern (nstr ls) (nstr scope) (nstr ks))))))))]

        (if (nil? fmt-args) tr
          (apply fmt-fn loc1 (or tr "") fmt-args))))))
