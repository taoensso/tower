(ns taoensso.tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [taoensso.tower :as tower]
              [taoensso.tower.utils :as utils]))

(defn locale-from-headers
  "Parses HTTP Accept-Language header and returns highest weighted locale that
  is valid, or nil if none is."
  [request]
  (let [header (str (get-in request [:headers "accept-language"]))]
    (some tower/parse-Locale (map first (utils/parse-http-accept-header header)))))

(comment (locale-from-headers
          {:headers {"accept-language" "en-GB,en;q=0.8,en-US;q=0.6"}}))

(defn locale-from-uri "\"/foo/bar/locale/en/\" => \"en\""
  [request] (second (re-find #"\/locale\/([^\/]+)" (str (:uri request)))))

(comment (locale-from-uri {:uri "/foo/bar/locale/en/"}))

(defn wrap-i18n-middleware
  "Ring middleware that sets i18n bindings for request after determining
  locale preference from (tower/parse-Locale (locale-selector-fn request)),
  session, query params, URI, or headers.

  `locale-selector-fn` can be used to select a locale by IP address, subdomain,
  top-level domain, etc."
  [handler t-config & [{:keys [locale-selector-fn default-locale]
                        :or   {default-locale :jvm-default}}]]
  (fn [request]
    (let [locale (some tower/parse-Locale
                       [(when locale-selector-fn (locale-selector-fn request))
                        (-> request :session :locale)
                        (-> request :params  :locale)
                        (locale-from-uri     request)
                        (locale-from-headers request)
                        default-locale])]
      (tower/with-locale locale
        (handler (assoc request :t (partial tower/t t-config)))))))