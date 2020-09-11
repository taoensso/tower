(defproject com.taoensso/tower "3.1.0-beta5"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "i18n & L10n library for Clojure/Script"
  :url "https://github.com/taoensso/tower"

  :license
  {:name "Eclipse Public License - v 1.0"
   :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :test-paths ["test" #_"src"]

  :dependencies
  [[com.taoensso/encore "2.68.0"]
   [com.taoensso/timbre "4.7.3"]
   [markdown-clj        "0.9.89"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :provided {:dependencies [[org.clojure/clojurescript "1.11.60"]
                             [org.clojure/clojure       "1.11.1"]]}
   :c1.12    {:dependencies [[org.clojure/clojure       "1.12.0-alpha9"]]}
   :c1.11    {:dependencies [[org.clojure/clojure       "1.11.1"]]}
   :c1.10    {:dependencies [[org.clojure/clojure       "1.10.3"]]}
   :c1.9     {:dependencies [[org.clojure/clojure       "1.9.0"]]}

   :graal-tests
   {:source-paths ["test"]
    :main taoensso.graal-tests
    :aot [taoensso.graal-tests]
    :uberjar-name "graal-tests.jar"
    :dependencies
    [[org.clojure/clojure                  "1.11.1"]
     [com.github.clj-easy/graal-build-time "1.0.5"]]}

   :dev
   {:jvm-opts ["-server" #_"-Dtaoensso.elide-deprecated=true"]

    :global-vars
    {*warn-on-reflection* true
     *assert*             true
     *unchecked-math*     false #_:warn-on-boxed}

    :dependencies
    [[org.clojure/test.check "0.9.0"]
     [expectations           "2.1.9"]
     [ring/ring-core         "1.5.0"]]

    :plugins
    [[lein-expectations "0.0.8"]
     [lein-autoexpect   "1.4.2"]
     [lein-pprint       "1.3.2"]
     [lein-ancient      "0.7.0"]
     [lein-cljsbuild    "1.1.8"]
     [com.taoensso.forks/lein-codox "0.10.11"]]

    :codox
    {:language #{:clojure :clojurescript}
     :base-language :clojure}}}

  :cljsbuild
  {:test-commands {"node" ["node" "target/test.js"]}
   :builds
   [{:id :main
     :source-paths ["src"]
     :compiler
     {:output-to "target/main.js"
      :optimizations :advanced}}

    {:id :test
     :source-paths ["src" "test"]
     :compiler
     {:output-to "target/test.js"
      :target :nodejs
      :optimizations :simple}}]}

  :aliases
  {"start-dev"  ["with-profile" "+dev" "repl" ":headless"]
   "build-once" ["do" ["clean"] ["cljsbuild" "once"]]
   "deploy-lib" ["do" ["build-once"] ["deploy" "clojars"] ["install"]]

   "test-clj"   ["with-profile" "+c1.12:+c1.11:+c1.10:+c1.9" "test"]
   "test-cljs"  ["with-profile" "+c1.12" "cljsbuild"         "test"]
   "test-all"   ["do" ["clean"] ["test-clj"] ["test-cljs"]]})
