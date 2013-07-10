(ns taoensso.tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [taoensso.tower :as tower]
              [taoensso.tower.utils :as utils]))

(defn- locale-from-headers [headers]
  (when-let [header (get headers "accept-language")]
    (some tower/try-locale (map first (utils/parse-http-accept-header header)))))

(comment (locale-from-headers- {"accept-language" "en-GB,en;q=0.8,en-US;q=0.6"}))

(defn wrap-tower-middleware
  "Determines a locale preference for request by attempting to parse a valid
  locale from (locale-selector request), (:locale session), (:locale params),
  request headers, etc. `locale-selector` can be used to select locale by IP
  address, subdomain, TLD, etc.

  Establishes a thread-local locale binding with `tower/*locale*`, and adds
  `:locale`, `:t`, and `:t'` (t with scope) keys to request."
  [handler & [{:keys [locale-selector fallback-locale tconfig]
               :or   {fallback-locale :jvm-default
                      tconfig tower/example-tconfig}}]]
  (fn [{:keys [session params uri server-name headers] :as request}]
    (let [loc (some tower/try-locale [(:locale request)
                                      (when-let [ls locale-selector] (ls request))
                                      (:locale session)
                                      (:locale params)
                                      (locale-from-headers headers)
                                      fallback-locale])]
      (tower/with-locale loc
        (handler
         (assoc request
           :locale (tower/locale-key loc)
           :tconfig tconfig
           :t   (if tconfig (partial tower/t  loc tconfig) (partial tower/t  loc))
           :t'  (if tconfig (partial tower/t' loc tconfig) (partial tower/t' loc))))))))

;;;; Deprecated

(defn wrap-i18n-middleware "DEPRECATED: Use `wrap-tower-middleware` instead."
  [handler & {:keys [locale-selector-fn]}]
  (wrap-tower-middleware handler {:locale-selector locale-selector-fn}))