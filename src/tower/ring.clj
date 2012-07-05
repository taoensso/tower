(ns tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [clojure.string :as str]
              [tower.core  :as tower]
              [tower.utils :as utils]))

;;;; Parsers, etc.

(defn parse-http-accept-header
  "Parses HTTP Accept header and returns sequence of [choice weight] pairs
  sorted by weight."
  [header]
  (->> (for [choice (->> (str/split (str header) #",")
                         (filter (complement str/blank?)))]
         (let [[lang q] (str/split choice #";")]
           [(-> lang str/trim)
            (or (when q (Float/parseFloat (second (str/split q #"="))))
                1)]))
       (sort-by second) reverse))

(comment (parse-http-accept-header nil)
         (parse-http-accept-header "en-GB")
         (parse-http-accept-header "en-GB,en;q=0.8,en-US;q=0.6")
         (parse-http-accept-header "en-GB  ,  en; q=0.8, en-US;  q=0.6")
         (parse-http-accept-header "a,"))

(defn locale-from-headers
  "Parses HTTP Accept-Language header and returns highest weighted locale that
  is valid, or nil if none is."
  [request]
  (let [header (str (get-in request [:headers "accept-language"]))]
    (->> (parse-http-accept-header header)
         (map first)
         (filter tower/parse-Locale)
         first)))

(comment (locale-from-headers
          {:headers {"accept-language" "en-GB,en;q=0.8,en-US;q=0.6"}}))

(defn locale-from-uri "\"/foo/bar/locale/en/\" => \"en\""
  [request] (second (re-find #"\/locale\/([^\/]+)" (str (:uri request)))))

(comment (locale-from-uri {:uri "/foo/bar/locale/en/"}))

;;;; Middleware

(defn make-wrap-i18n-middleware
  "Sets i18n bindings for request after determining locale preference from
  (tower/parse-Locale (locale-selector-fn request)), session, query params,
  URI, or headers.

  `locale-selector-fn` can be used to select a locale by IP address, subdomain,
  top-level domain, etc.

  If :dev-mode? is set in tower/config and if dictionary was loaded using
  tower/load-dictionary-from-map-resource!, dictionary will be automatically
  reloaded each time the resource's file changes."
  [& {:keys [locale-selector-fn]}]
  (fn [handler]
    (fn [request]

      ;; Dictionary resource reloading
      (let [{:keys [dev-mode? dict-res-name]} @tower/config]
        (when (and dev-mode? dict-res-name
                   (utils/some-file-resources-modified? dict-res-name))
          (tower/load-dictionary-from-map-resource! dict-res-name)))

      (let [locale (->> [(when locale-selector-fn (locale-selector-fn request))
                         (-> request :session :locale)
                         (-> request :params  :locale)
                         (locale-from-uri     request)
                         (locale-from-headers request)
                         :default]
                        (filter tower/parse-Locale)
                        first)]

        (tower/with-locale locale
          (handler request))))))