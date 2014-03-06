(ns taoensso.tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [taoensso.tower :as tower]
              [taoensso.tower.utils :as utils]))

(defn- locale-from-headers [headers]
  (when-let [header (get headers "accept-language")]
    (some tower/try-locale (map first (utils/parse-http-accept-header header)))))

(comment (locale-from-headers- {"accept-language" "en-GB,en;q=0.8,en-US;q=0.6"}))

(defn wrap-tower
  "Determines a locale preference for request by attempting to parse a valid
  locale from (locale-selector request), (:locale session), (:locale params),
  request headers, etc. `locale-selector` can be used to select locale by IP
  address, subdomain, TLD, etc.

  Adds keys to Ring request:
    * `:locale`  - :en, :en-US, etc.
    * `:tconfig` - tconfig map as given.
    * `:t`       - (fn [locale k-or-ks & fmt-args]).
    * `:t'`      - (fn [k-or-ks & fmt-args]), using `:locale` as above."
  [handler tconfig & [{:keys [locale-selector fallback-locale]
                       :or   {fallback-locale :jvm-default} :as opts}]]
  (fn [{:keys [session params uri server-name headers] :as request}]
    (let [loc (some tower/try-locale [(:locale request)
                                      (when-let [ls locale-selector] (ls request))
                                      (:locale session)
                                      (:locale params)
                                      (locale-from-headers headers)
                                      fallback-locale])
          t  (tower/make-t tconfig)
          t' (partial t loc)]
      (tower/with-locale loc ; Used for deprecated API
        (handler
         (merge request
           {:locale  (tower/locale-key loc)
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
