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

(def ^:private loc-tree ; Crossover (direct)
  (let [loc-tree*
        (memoize
          (fn [loc] ; :en-GB-var1 -> [:en-GB-var1 :en-GB :en]
            (let [loc-parts (str/split (name (kw-locale loc)) #"-")]
              (mapv #(keyword (str/join "-" %))
                (take-while identity (iterate butlast loc-parts))))))]

    (fn [loc-or-locs] ; Expensive, perf-sensitive, not easily memo'd
      (if-not (vector? loc-or-locs)
        (loc-tree* loc-or-locs) ; Build search tree from single locale
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

(defn- nstr [x] (if (nil? x) "nil" (str x)))
(defn- find1 ; This fn is perf sensitive, but isn't easily memo'd
  ([dict scope k ltree] ; Find scoped
     (let [[l1 :as ls] ltree
           scoped-k    (if-not scope k (scoped scope k))]
       (if (next ls)
         (some #(get-in dict [scoped-k %]) ls)
         (do    (get-in dict [scoped-k l1])))))
  ([dict k ltree] ; Find unscoped
     (let [[l1 :as ls] ltree]
       (if (next ls)
         (some #(get-in dict [k %]) ls)
         (do    (get-in dict [k l1]))))))

(defn make-t ; Crossover (modified)
  [tconfig] {:pre [(map? tconfig) ; (:dictionary tconfig)
                   ]}
  (let [{:keys [compiled-dictionary ; dictionary
                dev-mode? fallback-locale scope-fn fmt-fn
                log-missing-translation-fn cache-locales?]
         :or   {fallback-locale :en
                cache-locales?  true ; Different from server
                scope-fn (fn [] *tscope*)
                fmt-fn   fmt-str
                log-missing-translation-fn
                (fn [{:keys [locale ks scope] :as args}]
                  (encore/log (str "Missing translation" args)))}} tconfig

        _ (assert (:compiled-dictionary tconfig)  "Missing tconfig key: :compiled-dictionary")
        _ (assert     (not (:dictionary tconfig)) "Invalid tconfig key: :dictionary")

        loc-tree*   (if cache-locales? (memoize loc-tree) loc-tree)
        dict-cached compiled-dictionary]

    (fn new-t [l-or-ls k-or-ks & fmt-args]
      (let [dict   (or dict-cached #_(dict-compile* dictionary))
            ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])
            ls     (if (vector? l-or-ls) l-or-ls [l-or-ls])
            [loc1] ls ; Preferred locale (always used for fmt)
            scope  (scope-fn)
            ks?    (vector? k-or-ks)
            tr
            (or
              ;; Try locales & parents:
              (let [ltree (loc-tree* ls)]
                (if ks?
                  (some #(find1 dict scope % ltree) (take-while keyword? ks))
                  (find1 dict scope k-or-ks ltree)))

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
                      (let [ltree (loc-tree* fallback-locale)]
                        (if ks?
                          (some #(find1 dict scope % ltree) ks)
                          (find1 dict scope k-or-ks ltree)))

                      ;; Try (unscoped) :missing in locales, parents,
                      ;; fallback-loc, & parents:
                      (let [ltree (loc-tree* (conj ls fallback-locale))]
                        (when-let [pattern (find1 dict :missing ltree)]
                          (fmt-fn l1 pattern (nstr ls) (nstr scope) (nstr ks)))))))))]

        (if (nil? fmt-args) tr
          (apply fmt-fn loc1 (or tr "") fmt-args))))))
