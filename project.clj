(defproject com.taoensso/tower "3.1.0-beta3"
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
  [[org.clojure/clojure "1.4.0"]
   [com.taoensso/encore "1.24.1"]
   [com.taoensso/timbre "3.4.0"]
   [markdown-clj        "0.9.65"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
   :test {:dependencies [[expectations            "2.0.16"]
                         [org.clojure/test.check  "0.7.0"]
                         [ring/ring-core          "1.3.2"
                          :exclusions [org.clojure/tools.reader]]]
          :plugins [[lein-expectations "0.0.8"]
                    [lein-autoexpect   "1.4.2"]]}
   :dev
   [:1.6 :test
    {:dependencies
     [[org.clojure/clojurescript "0.0-2261"]]

     :plugins
     [;; These must be in :dev, Ref. https://github.com/lynaghk/cljx/issues/47:
      [com.keminglabs/cljx             "0.4.0"]
      [lein-cljsbuild                  "1.0.3"]
      ;;
      [lein-pprint                     "1.1.1"]
      [lein-ancient                    "0.5.5"]
      [com.cemerick/austin             "0.1.4"]
      [lein-expectations               "0.0.8"]
      [lein-autoexpect                 "1.2.2"]
      [com.cemerick/clojurescript.test "0.3.1"]
      [codox                           "0.8.10"]]}]}

  :cljx
  {:builds
   [{:source-paths ["src" "test"] :rules :clj  :output-path "target/classes"}
    {:source-paths ["src" "test"] :rules :cljs :output-path "target/classes"}]}

  :cljsbuild
  {:test-commands {"node"    ["node" :node-runner "target/main.js"]
                   "phantom" ["phantomjs" :runner "target/main.js"]}
   :builds ; Compiled in parallel
   [{:id :main
     :source-paths ["src" "test" "target/classes"]
     :compiler     {:output-to "target/main.js"
                    :optimizations :advanced
                    :pretty-print false}}]}

  :test-paths ["test" "src"]
  ;;:hooks      [cljx.hooks leiningen.cljsbuild]
  ;;:prep-tasks [["cljx" "once"] "javac" "compile"]
  :prep-tasks   [["with-profile" "+dev" ; Workaround for :dev cljx
                  "cljx" "once"] "javac" "compile"]
  :codox {:language :clojure ; [:clojure :clojurescript] ; No support?
          :sources  ["target/classes"]
          :src-linenum-anchor-prefix "L"
          :src-dir-uri "http://github.com/ptaoussanis/encore/blob/master/src/"
          :src-uri-mapping {#"target/classes"
                            #(.replaceFirst (str %) "(.cljs$|.clj$)" ".cljx")}}

  :aliases
  {"test-all"   ["with-profile" "default:+1.5:+1.6" "expectations"]
   ;; "test-all"   ["with-profile" "default:+1.6" "expectations"]
   "test-auto"  ["with-profile" "+test" "autoexpect"]
   "build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "deploy-lib" ["do" "build-once," "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+server-jvm" "repl" ":headless"]}

  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/groups/public/"})
