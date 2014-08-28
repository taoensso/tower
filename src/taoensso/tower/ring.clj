(ns taoensso.tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [taoensso.tower :as tower]
              [taoensso.tower.utils :as utils]
              [taoensso.encore :as encore]))

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
  [handler tconfig & [{:keys [locale-selector fallback-locale]
                       :or   {fallback-locale :jvm-default} :as opts}]]
  (fn [{:keys [session params uri server-name headers] :as request}]
    (let [accept-lang-locales ; [:en-GB :en :en-US], etc.
          (->> (get headers "accept-language")
               (utils/parse-http-accept-header)
               (mapv (comp tower/kw-locale first)))

          sorted-locales ; Quite expensive
          (->>
            (reduce (fn [v in] (if (sequential? in) (into v in) (conj v in)))
              []
              [(:locale request)
               (when-let [ls locale-selector]
                 (ls request)) ; May return >=0 locales
               (:locale session)
               (:locale params)
               accept-lang-locales
               fallback-locale])
            (filterv identity)
            (mapv tower/kw-locale)
            (encore/distinctv))

          sorted-jvm-locales   (filterv tower/try-jvm-locale sorted-locales)

          preferred-locale     (first sorted-locales)
          preferred-jvm-locale (first sorted-jvm-locales)

          t  (tower/make-t tconfig)
          t' (partial t sorted-locales)]

      (tower/with-locale preferred-jvm-locale ; For deprecated API
        (handler
          (merge request
            {:locale      preferred-locale
             :locales     sorted-locales
             :jvm-locale  preferred-jvm-locale
             :jvm-locales sorted-jvm-locales
             :tconfig     tconfig}

            (if (:legacy-t? opts)
              {:t  t'} ; DEPRECATED (:t will use parsed locale)
              {:t  t   ; Takes locale arg
               :t' t'  ; Uses parsed locale
               })))))))

;;;; Deprecated

(defn wrap-tower-middleware "DEPRECATED. Use `wrap-tower` instead."
  [handler & [{:as   opts
               :keys [locale-selector fallback-locale tconfig]
               :or   {fallback-locale :jvm-default
                      tconfig tower/example-tconfig}}]]
  (wrap-tower handler tconfig (assoc opts :legacy-t? true)))

(defn wrap-i18n-middleware "DEPRECATED: Use `wrap-tower` instead."
  [handler & {:keys [locale-selector-fn]}]
  (wrap-tower-middleware handler {:locale-selector locale-selector-fn}))
