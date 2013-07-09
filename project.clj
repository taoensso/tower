(defproject com.taoensso/tower "2.0.0-alpha10"
  :description "Clojure i18n & L10n library"
  :url "https://github.com/ptaoussanis/tower"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure     "1.4.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [expectations            "1.4.49"]
                 [com.taoensso/timbre     "2.1.2"]]
  :profiles {:1.4  {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6  {:dependencies [[org.clojure/clojure "1.6.0-master-SNAPSHOT"]]}
             :dev  {:dependencies []}
             :test {:dependencies [[ring/ring-core      "1.2.0"]]}}
  :aliases {"test-all"    ["with-profile" "test,1.4:test,1.5:test,1.6" "expectations"]
            "test-auto"   ["with-profile" "test" "autoexpect"]
            "start-dev"   ["with-profile" "dev,test,bench" "repl" ":headless"]
            "codox"       ["with-profile" "test" "doc"]}
  :plugins [[lein-expectations "0.0.8"]
            [lein-autoexpect   "0.2.5"]
            [lein-ancient      "0.4.2"]
            [codox             "0.6.4"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
