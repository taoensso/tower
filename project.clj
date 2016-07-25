(defproject com.taoensso/tower "3.1.0-beta4"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Clojure/Script i18n & L10n library"
  :url "https://github.com/ptaoussanis/tower"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}

  :dependencies
  [[org.clojure/clojure "1.5.1"]
   [com.taoensso/encore "2.68.0"]
   [com.taoensso/timbre "4.7.3"]
   [markdown-clj        "0.9.89"]]

  :plugins
  [[lein-pprint  "1.1.2"]
   [lein-ancient "0.6.10"]
   [lein-codox   "0.9.5"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
   :1.7  {:dependencies [[org.clojure/clojure "1.7.0"]]}
   :1.8  {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :1.9  {:dependencies [[org.clojure/clojure "1.9.0-alpha10"]]}
   :test {:dependencies [[org.clojure/test.check "0.9.0"]
                         [expectations           "2.1.9"]
                         [ring/ring-core         "1.5.0"]]
          :plugins      [[lein-expectations      "0.0.8"]
                         [lein-autoexpect        "1.4.2"]]}
   :dev
   [:1.9 :test :server-jvm
    {:dependencies
     [[org.clojure/clojurescript "1.9.93"]]

     :plugins
     [;; These must be in :dev, Ref. https://github.com/lynaghk/cljx/issues/47:
      [com.keminglabs/cljx             "0.6.0"]
      [lein-cljsbuild                  "1.1.3"]]}]}

  :source-paths ["src" "target/classes"]
  :test-paths   ["src" "test" "target/test-classes"]

  :cljx
  {:builds
   [{:source-paths ["src"]        :rules :clj  :output-path "target/classes"}
    {:source-paths ["src"]        :rules :cljs :output-path "target/classes"}
    {:source-paths ["src" "test"] :rules :clj  :output-path "target/test-classes"}
    {:source-paths ["src" "test"] :rules :cljs :output-path "target/test-classes"}]}

  :cljsbuild
  {:test-commands {}
   :builds
   [{:id "main"
     :source-paths   ["src" "target/classes"]
     ;; :notify-command ["terminal-notifier" "-title" "cljsbuild" "-message"]
     :compiler       {:output-to "target/main.js"
                      :optimizations :advanced
                      :pretty-print false}}
    {:id "tests"
     :source-paths   ["src" "target/classes" "test" "target/test-classes"]
     ;; :notify-command []
     :compiler       {:output-to "target/tests.js"
                      :optimizations :whitespace
                      :pretty-print true
                      :main "taoensso.tempura.tests"}}]}

  :auto-clean false
  :prep-tasks [["cljx" "once"] "javac" "compile"]

  :codox
  {:language :clojure ; [:clojure :clojurescript] ; No support?
   :source-paths ["target/classes"]
   :source-uri
   {#"target/classes" "https://github.com/ptaoussanis/tempura/blob/master/src/{classpath}x#L{line}"
    #".*"             "https://github.com/ptaoussanis/tempura/blob/master/{filepath}#L{line}"}}

  :aliases
  {"test-all"   ["do" "clean," "cljx" "once,"
                 "with-profile" "+1.9:+1.8:+1.7:+1.6:+1.5" "expectations"]
   "build-once" ["do" "clean," "cljx" "once," "cljsbuild" "once" "main"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+dev" "repl" ":headless"]}

  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
