(ns cljs.taoensso.tower
  "EXPERIMENTAL ClojureScript support for Tower.
  PRE-alpha - almost certain to change."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str])
  (:require-macros [cljs.taoensso.tower.macros :as tower-macros]))

;;;; Utils

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

;; TODO Some (?) locale-aware text format fn
(defn- format "Removed from cljs.core 0.0-1885, Ref. http://goo.gl/su7Xkj"
  [fmt & args] (apply goog.string/format fmt args))

;;;; Config

(def ^:dynamic *locale* nil)
(def ^:dynamic *tscope* nil)

(def ^:crossover locale-key
  (memoize #(keyword (str/replace (str (#_locale %) %) "_" "-"))))

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

(defn translate [loc config scope k-or-ks & fmt-args]
  (let [{:keys [compiled-dictionary fallback-locale log-missing-translation-fn
                root-scope fmt-fn]
         :or   {fallback-locale :en
                fmt-fn          format ; fmt-str
                }} config

        scope  (scoped root-scope scope)
        dict   compiled-dictionary
        ks     (if (vector? k-or-ks) k-or-ks [k-or-ks])

        get-tr*  (fn [k l] (get-in dict [              k  l]))  ; Unscoped k
        get-tr   (fn [k l] (get-in dict [(scoped scope k) l]))  ; Scoped k
        find-tr* (fn [k l] (some #(get-tr* k %1) (loc-tree l))) ; Try loc & parents
        find-tr  (fn [k l] (some #(get-tr  k %1) (loc-tree l))) ; ''

        tr
        (or (some #(find-tr % loc) (take-while keyword? ks)) ; Try loc & parents
            (let [last-k (peek ks)]
              (if-not (keyword? last-k)
                last-k ; Explicit final, non-keyword fallback (may be nil)

                (do (when-let [log-f log-missing-translation-fn]
                      (log-f {;; :ns (str *ns*) ; ??
                              :locale loc :scope scope :ks ks}))
                    (or
                     ;; Try fallback-locale & parents
                     (some #(find-tr % fallback-locale) ks)

                     ;; Try :missing key in loc, parents, fallback-loc, & parents
                     (when-let [pattern (or (find-tr* :missing loc)
                                            (find-tr* :missing fallback-locale))]
                       (let [str* #(if (nil? %) "nil" (str %))]
                         (fmt-fn loc pattern (str* loc) (str* scope) (str* ks)))))))))]

    (if-not fmt-args tr
      (if-not tr (throw (js/Error. "Can't format nil translation pattern."))
        (apply fmt-fn loc tr fmt-args)))))

(defn t [loc config k-or-ks & fmt-args]
  (apply translate loc config *tscope* k-or-ks fmt-args))
