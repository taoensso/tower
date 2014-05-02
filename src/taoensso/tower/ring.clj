(ns taoensso.tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [taoensso.tower :as tower]
              [taoensso.tower.utils :as utils]))

(defn wrap-tower
  "Determines locale preference for request by attempting to parse a valid
  locale from (locale-selector request), (:locale session), (:locale params),
  request headers, etc. `locale-selector` can be used to select locale by IP
  address, subdomain, TLD, etc.

  Adds keys to Ring request:
    * `:locale`  - Preferred locale: `:en`, `:en-US`, etc.
    * `:locales` - Accept-lang locales: `[:en-GB :en :en-US :fr-FR :fr]`, etc.
    * `:tconfig` - tconfig map as given.
    * `:t`       - (fn [locale-or-locales k-or-ks & fmt-args]).
    * `:t'`      - (fn [k-or-ks & fmt-args]), using `:locales` as above."
  [handler tconfig & [{:keys [locale-selector fallback-locale]
                       :or   {fallback-locale :jvm-default} :as opts}]]
  (fn [{:keys [session params uri server-name headers] :as request}]
    (let [accept-lang-locales ; [:en-GB :en :en-US], etc.
          (->> (get headers "accept-language")
               (utils/parse-http-accept-header)
               (mapv    first)
               (filterv tower/try-locale)
               (mapv    tower/locale-key))

          preferred-locale ; Always used for formatting
          (tower/locale-key
            (some tower/try-locale
              [(:locale request)
               (when-let [ls locale-selector] (ls request))
               (:locale session)
               (:locale params)
               (some identity accept-lang-locales)
               fallback-locale]))

          t'-locales ; Ordered, non-distinct locales to search for translations
          (-> (into [preferred-locale] accept-lang-locales)
              (conj :jvm-default))

          t  (tower/make-t tconfig)
          t' (partial t t'-locales)]
      (tower/with-locale preferred-locale ; Used for deprecated API
        (handler
         (merge request
           {:locale  preferred-locale
            :locales accept-lang-locales
            :tconfig tconfig}

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
