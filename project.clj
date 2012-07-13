(defproject com.taoensso/tower "0.6.0"
  :description "Simple internationalization (i18n) library for Clojure."
  :url "https://github.com/ptaoussanis/tower"
  :license {:name "Eclipse Public License"}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [com.taoensso/timbre "0.7.0"]]
  :profiles {:1.3   {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4   {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5   {:dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}
             :dev   {:dependencies []}}
  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}
                 "sonatype-snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"
                                       :snapshots true
                                       :releases {:checksum :fail :update :always}}}
  :aliases {"all" ["with-profile" "1.3:1.4:1.5"]}
  :min-lein-version "2.0.0"
  :warn-on-reflection true)
