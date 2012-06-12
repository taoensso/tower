(ns tower.ring "Tower i18n middleware for Ring."
    {:author "Peter Taoussanis"}
    (:require [tower.core :as tower]))

(defn i18n-middleware
  "Sets appropriate i18n bindings for request after determining locale
  preference from request's session, URL, or headers."
  [handler]
  (fn [request]

    ;; TODO Check URL for /en_US/ style suffix and fall back to HTTP header
    ;; (HTTP_ACCEPT_LANGUAGE?) or default locale.
    (let [locale (or (-> request :session :locale
                         ;; ...
                         ))]
      (tower/with-locale locale
        (handler request)))))