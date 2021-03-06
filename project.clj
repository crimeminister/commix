(defproject commix "0.1.0-ALPHA"
  :description "Micro-framework for data-driven composable system architectures"
  :url "https://github.com/vspinu/commix"
  :license {:name "The MIT License"
            :url  "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.stuartsierra/dependency "0.2.0"]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                                  [org.clojure/spec.alpha "0.1.123"]
                                  [org.clojure/clojurescript "1.9.854"]
                                  [com.cemerick/piggieback "0.2.2"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.228"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/clojurescript "1.7.228"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                                  [org.clojure/clojurescript "1.9.854"]
                                  [org.clojure/spec.alpha "0.1.123"]]}
             :1.9-no-spec {:dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                                          [org.clojure/clojurescript "1.9.854"]]}
             }
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :plugins [[lein-doo "0.1.7"]]
  :cljsbuild {:builds [{:id           "test-nashorn"
                        :source-paths ["src" "test"]
                        :compiler     {:output-to     "target/cljs/test-nashorn/test-commix.js"
                                       :output-dir    "target/cljs/test-nashorn/out"
                                       :main          commix.test-runner
                                       :optimizations :simple}}
                       {:id           "test-node"
                        :source-paths ["src" "test"]
                        :compiler     {:target        :nodejs
                                       :output-to     "target/cljs/test-node/test-commix.js"
                                       :output-dir    "target/cljs/test-node/out"
                                       :main          commix.test-runner
                                       :optimizations :none}}]}
  :aliases {"test-nashorn" ["doo" "nashorn" "test-nashorn" "once"]
            "test-node"    ["doo" "node" "test-node" "once"]
            "test-cljs"    ["do" ["test-nashorn"] ["test-node"]]
            "test-clj"     ["test" ":only" "commix.core-test"]
            "test"         ["test" ":only" "commix.core-test"]
            "test-min"     ["do" ["test-clj"] ["test-node"]]
            "test-all"     ["do" ["test-clj"] ["test-node"] ["test-nashorn"]]})
