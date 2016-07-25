(ns taoensso.tower.ring "Tower i18n middleware for Ring."
  {:author "Peter Taoussanis"}
  (:require [clojure.string       :as str]
            [taoensso.tower       :as tower]
            [taoensso.tower.utils :as utils]
            [taoensso.encore      :as enc]))

(defmacro with-locale [loc & body] body)
(enc/deprecated
  (defmacro with-locale [loc & body]
    `(tower/with-locale ~loc ~@body)))

(defn wrap-tower
  "Determines locale preference for request by attempting to parse a valid
  locale from Ring request. `(fn locale-selector [ring-request])` can be used to
  select locale(s) by IP address, subdomain, TLD, etc.

  Adds keys to Ring request:
    * :locale      - Preferred locale: `:en`, `:en-US`, etc.
    * :locales     - Desc-preference locales: `[:en-GB :en :en-US :fr-FR :fr]`, etc.
    * :jvm-locale  - As `:locale`, but a recognized JVM locale.
    * :jvm-locales - As `:locales`, but only recognized JVM locales.
    * :tconfig     - tconfig map as given.
    * :t           - (fn [locale-or-locales k-or-ks & fmt-args]).
    * :t'          - (fn [k-or-ks & fmt-args]), using `:locales` as above."
  [handler tconfig & [{:keys [locale-selector fallback-locale langs-only?
                              t'-nsorted-locales]
                       :or   {fallback-locale :jvm-default} :as opts}]]
  (fn [{:keys [session params uri server-name headers] :as request}]
    (let [->kw-locale #(tower/kw-locale % langs-only?)
          accept-lang-locales ; ["en-GB" "en" "en-US"], etc.
          (->> (get headers "accept-language")
               (utils/parse-http-accept-header)
               (mapv (fn [[l q]] l)))

          sorted-locales
          (->>
            ;; Written for speed over clarity; a transducer would be perfect here
            (reduce
              (fn [v in]
                (cond
                  (nil? in) v
                  (sequential? in) ; into
                  (reduce (fn [v in] (if (nil? in) v
                                        (conj! v (->kw-locale in))))
                    v
                    in)
                  :else (conj! v (->kw-locale in))))
              (transient [])
              ;; Each of these may be >=0 locales:
              [(:locale request)
               (when-let [ls locale-selector] (ls request))
               (:locale session)
               (:locale params)
               accept-lang-locales
               fallback-locale])
            (persistent!)
            ;; (filterv identity)
            ;; (mapv ->kw-locale)
            (utils/distinctv))

          sorted-jvm-locales   (filterv tower/try-jvm-locale sorted-locales)

          preferred-locale     (get sorted-locales     0)
          preferred-jvm-locale (get sorted-jvm-locales 0)

          t  (tower/make-t tconfig) ; Constructor will be cached in prod
          t' (partial (tower/make-t (assoc tconfig :cache-locales? true))
               (if-let [ntake t'-nsorted-locales]
                 (subvec sorted-locales 0 (min ntake (count sorted-locales)))
                 sorted-locales))]

      (with-locale preferred-jvm-locale ; For deprecated API
        (handler
          (merge request
            {:locale      preferred-locale
             :locales     sorted-locales
             :jvm-locale  preferred-jvm-locale
             :jvm-locales sorted-jvm-locales
             :tconfig     tconfig}

            (if (:legacy-t? opts)
              {:t  t'} ; DEPRECATED (:t will use parsed locale)
              {:t  t   ; Takes locale/s arg
               :t' t'  ; Uses parsed locales
               })))))))

;;;; Deprecated

(enc/deprecated
  (defn wrap-tower-middleware "DEPRECATED. Use `wrap-tower` instead."
    [handler & [{:as   opts
                 :keys [locale-selector fallback-locale tconfig]
                 :or   {fallback-locale :jvm-default
                        tconfig tower/example-tconfig}}]]
    (wrap-tower handler tconfig (assoc opts :legacy-t? true)))

  (defn wrap-i18n-middleware "DEPRECATED: Use `wrap-tower` instead."
    [handler & {:keys [locale-selector-fn]}]
    (wrap-tower-middleware handler {:locale-selector locale-selector-fn})))
