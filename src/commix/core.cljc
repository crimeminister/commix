(ns commix.core
  (:refer-clojure :exclude [ref flatten])
  (:require [com.stuartsierra.dependency :as dep]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.string :as str]
            #?(:clj  [clojure.pprint]
               :cljs [cljs.pprint]))
  #?(:cljs (:require-macros [commix.macros :refer [defaction]])
     :clj (:require [commix.macros :refer [defaction]])))

(defn default-exception-handler [system ex]
  #?(:clj  (clojure.pprint/pprint ex)
     :cljs (cljs.pprint/pprint ex))
  system)

(def ^:dynamic *exception-handler*
  (atom default-exception-handler))

(def ^:dynamic *trace-function*
  (atom nil))

(def ^{:doc "When action :A is performed on a component, all the component's
dependencies must have their status in (:A @required-dependent-status).
Exception is thrown if this condition is not satisfied."}
  required-dependency-status
  (atom {:init #{:init :resume}
         :resume #{:init :resume}
         :halt #{:ALL}
         :suspend #{:ALL}}))

(def ^{:doc "When action :A is performed on a component, all the component's
dependents must have their status in (:A @required-dependent-status). Exception
is thrown if this condition is not satisfied."}
  required-dependent-status
  (atom {:init #{:ALL}
         :resume #{:ALL}
         :halt #{:halt nil}
         :suspend #{:suspend :halt nil}}))

(def ^{:doc
       "If action :A can run after :B then :B must be in (:A @can-run-on-status).
If this condition is not satisfied action is not performed (silently)."}
  can-run-on-status
  (atom {:init    #{nil :halt}
         :halt    #{:init :resume :suspend}
         :resume  #{:suspend}
         :suspend #{:init :resume}}))


;;; UTILS

(declare com? com-conf)

(defn- namespaced-keyword? [x]
  (and (keyword? x) (namespace x) true))

(defn- normalize-paths [paths]
  (if-not (coll? paths)
    ;; keyword
    (normalize-paths [[paths]])
    (into #{} (map #(if (vector? %) % [%]) paths))))

(defn- flatten
  "Transform a nested config into a map of [keyseq comp].
  `comp' is a config map or a Comp record."
  ([v]
   (reduce-kv flatten {} v))
  ([fmap k v]
   (let [k    (if (vector? k) k [k])
         fmap (assoc fmap k v)
         v    (if (com? v) (com-conf v) v)]
     (if-not (map? v)
       fmap
       (reduce-kv (fn [fmap k1 v1]
                    (flatten fmap (conj (vec k) k1) v1))
                  fmap
                  v)))))
(defn nodes
  "Get all component names from the system graph."
  [system]
  (set/difference (dep/nodes (-> system meta ::graph))
                  #{::ROOT}))

(defn values
  "Get a flat map of instantiated components from the system map."
  [system]
  (into (sorted-map)
        (map #(vector % (get-in system (conj % :cx/value)))
             (nodes system))))

(defn status
  "Get a grouped map of components by last run action."
  [system]
  (let [coms (nodes system)]
    (->> coms
         (map #(vector % (get-in system (conj % :cx/status))))
         (group-by second)
         (map (fn [[k v]] [k (into (sorted-set) (map first v))]))
         (into {}))))

#?(:cljs
   (defn format [fmt & args]
     (loop [s fmt
            [a & args] args]
       (if a
         (recur (str/replace-first s #"%s" (str a)) args)
         s))))


;;; COMs

(defn com
  "Create a component from a key and a config map.
  If `config' is a symbol it is automatically resolved.

  (cx/com :ns/key {:param 1})

  (cx/com :ns/key)        ; empty config
  (cx/com :ns/key config) ; from symbol

  (cx/com :ns/key config {:param 1}) ; merge {:param 1} into config

  (cx/com {:param 1})              ; equivalent to
  (cx/com :cx/identity {:param 1})"
  {:style/indent :defn}
  ([config-or-key]
   (cond
     (keyword? config-or-key) (com config-or-key {})
     (map? config-or-key)     (com (get config-or-key :cx/key :cx/identity) config-or-key)
     :else                    (throw (ex-info "Invalid config argument. Must be a map or a keyword." {:config config-or-key}))))
  ([key config]
   (cond
     (symbol? key)    (do
                        #?(:clj (com (var-get (resolve key)) config))
                        #?(:cljs (throw (js/Error. "Cannot resolve symbols in clojurescript."))))
     (symbol? config) (do
                        #?(:clj (com key (if-let [var (resolve config)]
                                             (var-get var)
                                             (throw (ex-info "Cannot resolve config." {:symbol config})))))
                        #?(:cljs (throw (js/Error. "Cannot resolve symbols in clojurescript."))))
     (map? key)       (com (get key :cx/key :cx/identity) key config)
     (keyword? key)   (into (sorted-map)
                            (assoc config :cx/key key))
     :else            (throw (ex-info "Invalid key object supplied." {:key key :config config}))))
  ([key config merge-config]
   (merge (com key config) merge-config)))


(defn- com-seq? [x]
  (and (seq? x) (contains? #{'cx/com 'commix.core/com} (first x))))

(defn- com-map? [x]
  (and (map? x) (contains? x :cx/key)))

(defn com? [x]
  (or (com-seq? x) (com-map? x)))

(defn- com-key [x]
  (if (com-map? x)
    (:cx/key x)
    (if (com? x)
      (if (= (count x) 3)
        (second x)
        :cx/identity)
      (throw (ex-info "Invalid com." {:object x})))))

(defn- com-conf
  "Get configuration component from a com."
  [x]
  (if (com-seq? x)
    (last x)
    (if (com-map? x)
      (dissoc x :cx/key :cx/value :cx/status :cx/path :cx/system)
      (throw (ex-info "Invalid com." {:object x})))))

(defn expand-config [config]
  (let [expand-kwds (not (-> config meta ::graph)) ; expand only on init
        expand-v    (fn [[k v]]
                      (if (and expand-kwds
                               (namespaced-keyword? k)
                               (map? v) (not (com-map? v))
                               (not= k :cx/value))
                        (com k v)
                        (if (com-seq? v)
                          (com (com-key v) (com-conf v))
                          v)))]
    (walk/postwalk
      #(if (and (vector? %) (= (count %) 2))
         (assoc % 1 (expand-v %))
         %)
      config)))

(defn- get-coms-in-path
  "Retrieve the set of dependencies in `path'."
  [config path]
  {:pre [(vector? path)]}
  (let [v (get-in config path true)]
    (if (com? v)
      #{path}
      (cond
        (map? v) (reduce-kv (fn [c k v]
                              (if (and (keyword? k) (namespace k))
                                ;; FIXME: this one is most likely wrong
                                (conj c (conj path k))
                                (into c (get-coms-in-path config (conj path k)))))
                            #{}
                            v)
        ;; (coll? v) (set (mapcat #(get-coms-in-path config %) v))
        :else    #{}))))

#?(:clj
   ;; Adapted from Integrant
   (do

     (defn- keyword->namespaces [kw]
       (if-let [ns (namespace kw)]
         [(symbol ns)
          (symbol (str ns "." (name kw)))]))

     (defn- try-require [sym]
       (try (do (require sym) sym)
            (catch java.io.FileNotFoundException _)))

     (defn load-namespaces
       "Attempt to load the namespaces referenced by the keys in a configuration.
     If a key is namespaced, both the namespace and the namespace concatenated
     with the name will be tried. For example, if a key is :foo.bar/baz, then the
     function will attempt to load the namespaces foo.bar and foo.bar.baz. Upon
     completion, a list of all loaded namespaces will be returned."
       [config]
       (let [config (expand-config config)
             keys   (volatile! [])]
         (walk/postwalk (fn [v]
                          (if (com-map? v)
                            (do (vswap! keys conj (com-key v)) v)
                            v))
                        config)
         (doall (->> @keys
                     (mapcat keyword->namespaces)
                     (distinct)
                     (keep try-require)))))))


;;; REFS

(defn ref
  "Create a reference to a key in a config map."
  [key]
  `(cx/ref ~key))

(defn ref?
  "Return true if its argument is a ref."
  [x]
  (and (seq? x)
       (contains? #{'cx/ref 'commix.core/ref} (first x))))

(defn- ref-key [x]
  (if (ref? x)
    (let [k (second x)]
      (if (vector? k) k [k]))
    (throw (ex-info "Invalid ref." {:object x}))))

(defn- get-refs
  "Get set of refs in object v."
  [v & [path]]
  (let [path (or path [])
        refs-in-map (fn [v path]
                      (reduce-kv (fn [s k v]
                                   (if (com? v)
                                     (conj s (conj path k))
                                     (into s (get-refs v (conj path k)))))
                                 #{} v))]
    (cond (ref? v)  #{(ref-key v)}
          (map? v)  (refs-in-map v path)
          (coll? v) (set (mapcat get-refs v))
          :else     #{})))

(defn- all-refs
  "Take in a flat config and return a set of ref paths for each entry."
  [flat-conf]
  (into {}
        (map (fn [[k v]] [k (get-refs v)])
             flat-conf)))

;; (all-refs (flatten {:z (com {:a  (com {})
;;                              :b  {:c (com {})
;;                                   :d (com {})}
;;                              ::d {}})}))

;;; DEPS

(defn- dep-path-of-a-ref
  "Get full dependency path of a ref."
  [config com-path ref-path]
  {:pre [(vector? ref-path)]}
  (loop [cp com-path]
    (let [rk (into (vec cp) ref-path)]
      (if (get-in config rk)
        rk
        (when (seq cp)
          (recur (butlast cp)))))))

(defn- deps-from-ref
  "Get a set of full paths to dpendencies of ref key ref-path in flat map fconfig.
  com-path is the component path where ref-path was found."
  [config com-path ref-path]
  (when-let [dp (dep-path-of-a-ref config com-path ref-path)]
    (get-coms-in-path config dp)))

(defn- dependency-graph
  "Dependency graph from config."
  [config]
  (let [config (expand-config config)
        refs   (all-refs (flatten config))]
    (reduce-kv (fn [g k v]
                 (if-not (com? (get-in config k))
                   g
                   (reduce (fn [g r]
                             (if-let [deps (deps-from-ref config k r)]
                               (reduce (fn [g d]
                                         (dep/depend g k d))
                                       g deps)
                               (throw (ex-info "Missing dependency."
                                               {; :call     `(~'deps-from-ref ~'config ~k ~r)
                                                :ref-key  r
                                                :component k}))))
                           ;; Need ::ROOT for tracking nodes with no deps
                           (dep/depend g k ::ROOT)
                           v)))
               (dep/graph)
               refs)))

(defn- transitive-dependencies [graph-or-sys paths reverse? exclude-self?]
  (if (satisfies? dep/DependencyGraph graph-or-sys)
    ;; graph
    (let [graph (dep/remove-all graph-or-sys ::ROOT)
          nodes (if paths
                  (let [paths  (normalize-paths paths)
                        extras (set/difference paths (dep/nodes graph))]
                    (when (seq extras)
                      (throw (ex-info "No component(s) in path(s)." {:paths extras})))
                    (->> ((if reverse?
                            dep/transitive-dependents-set
                            dep/transitive-dependencies-set)
                          graph paths)
                         (set/union (if exclude-self? #{} paths))
                         (sort (dep/topo-comparator graph))))
                  (dep/topo-sort graph))]
      (if reverse?
        (reverse nodes)
        nodes))
    ;; system
    (if (map? graph-or-sys)
      (transitive-dependencies (-> graph-or-sys meta ::graph) paths reverse? exclude-self?)
      (throw (ex-info "Invalid system. Must be a map or a dependency graph." {:supplied graph-or-sys})))))

(defn dependencies [graph-or-sys & [paths exclude-self?]]
  (transitive-dependencies graph-or-sys paths false exclude-self?))

(defn dependents [graph-or-sys & [paths exclude-self?]]
  (transitive-dependencies graph-or-sys paths true exclude-self?))


;;; KEYED METHODS

(defn- com-dispatch-fn [node]
  (or (:cx/key node)
      (throw (ex-info "Missing :cx/key in system node." {:config node}))))

(defmulti init-node
  "Turn a config value associated with a key into a concrete implementation.
  In order to avoid bugs due to typos there is no default method for
  `init-node'."
  {:arglists '([node])}
  com-dispatch-fn)

(defmethod init-node :cx/identity [node]
  ;; Not dissoccing other special keys. Otherwise would need to dissoc
  ;; recursively for other nested :cx/identity components.
  (dissoc node :cx/path :cx/system))

(defmulti halt-node
  "Halt a running or suspended node.
  Often used for stopping processes or cleaning up resources. As with other key
  methods the return of `halt-node' is inserted into the system map, this could
  be used for statistics reports and inspection of the stopped
  components. Default method is an identity."
  {:arglists '([node])}
  com-dispatch-fn)

(defmethod halt-node :default [node] (:cx/value node))

(defmulti resume-node
  "Resume a previously suspended node.
  By default calls `init-node'."
  {:arglists '([node])}
  com-dispatch-fn)

(defmethod resume-node :default [node]
  (init-node node))

(defmulti suspend-node
  "Suspend a running node, so that it may be eventually passed to resume-node.
  By default this multimethod calls halt-node, but it may be customized to do
  things like keep a server running, but buffer incoming requests until the
  server is resumed." {:arglists '([node])} com-dispatch-fn)

(defmethod suspend-node :default [node]
  (halt-node node))


;;; ACTIONS

(defn can-run? [system com-path action]
  (let [status (:cx/status (get-in system com-path))
        run?   (contains? (get @can-run-on-status action) status)]
    (when @*trace-function*
      (@*trace-function* (format "%s %s on %s (current status: %s)"
                                (if run? "Running" "Skipping")
                                action com-path status)))
    run?))

(defn check-deps-status [system com-path action reverse?]
  (let [deps     (transitive-dependencies system [com-path] reverse? true)
        required (get (if reverse?
                        @required-dependent-status
                        @required-dependency-status)
                      action)]
    (when-not (contains? required :ALL)
      (doseq [dp deps]
        (let [status (get-in system (conj dp :cx/status))]
          (when-not (contains? required status)
            (throw (ex-info (format "Wrong %s status." (if reverse? "dependent" "dependency"))
                            (merge {:com-path   com-path
                                    :action action
                                    :required-status required}
                                   (if reverse?
                                     {:dependent                 dp
                                      :dependent-status          status}
                                     {:dependency                 dp
                                      :dependency-status          status}))))))))))


(defn action-exception [system com-path action-class ex]
  (ex-info (format "Error in %s action on component %s" action-class com-path)
           {:reason ::action-exception
            :action action-class
            :com-path com-path
            :com (get-in system com-path)
            :ex ex}
           ex))

(defn run-action [system paths action]
  (let [exception  (atom nil)]
    (loop [completed      ()
           [path & paths] paths
           system         system]
      (if-not path
        system
        (let [system
              (try
                (action system path)
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) ex
                    (reset! exception ex)
                  system)
                (catch #?(:clj Throwable :cljs :default) ex
                    (reset! exception (action-exception system path :unknown ex))
                  system)
                )]
          (if @exception
            (when @*exception-handler*
             (@*exception-handler* system @exception))
            (recur (cons path completed) paths system)))))))

;; fixme: document or think of a more intuitive name
(defn- make-value [system path]
  (walk/prewalk #(if (com? %) (:cx/value %) %)
                (get-in system path)))

;; (defn- fill-obj-with-config [system com-path]
;;   (update-in system com-path #(assoc % :cx/value (com-conf %))))

(defn- get-filled-config-with-deps [system com-path]
  (let [filler #(walk/postwalk
                  (fn [v]
                    (if (ref? v)
                      (let [dp (dep-path-of-a-ref system com-path (ref-key v))]
                        (make-value system dp))
                      v))
                  %)]
    (let [val-path (conj com-path :cx/value)
          ;; system   (if (get-in system val-path)
          ;;            system
          ;;            (fill-obj-with-config system com-path))
          ]
      (filler (get-in system com-path)))))

(defn- update-value-in [system com-path f]
  (let [node      (get-filled-config-with-deps system com-path)
        new-value (f (assoc node
                            :cx/system system
                            :cx/path com-path))]
    (assoc-in system (conj com-path :cx/value) new-value)))

(defaction init-com :init [system com-path]
  (update-value-in system com-path init-node))

(defaction halt-com :halt [system com-path]
  (update-value-in system com-path halt-node))

(defaction suspend-com :suspend [system com-path]
  (update-value-in system com-path suspend-node))

(defaction resume-com :resume [system com-path]
  (update-value-in system com-path resume-node))

(defn- resume-or-init-com [system com-path]
  (-> system
      (resume-com com-path)
      (init-com com-path)))


;;; LIFE CYCLE ACTIONS

(defn- system-dispatch-fn [system & _]
  (:cx/system system))

(defmulti init
  "Turn config into a system map.
  Keys are traversed in dependency order and initiated via the init-node."
  {:arglists '([config] [config paths])}
  system-dispatch-fn)

(defmethod init :default
  ([config & [paths]]
   (let [config (expand-config config)
         graph  (dependency-graph config)
         system (vary-meta config assoc ::graph graph)
         deps   (dependencies system paths)]
     (run-action system deps init-com))))

(defmulti halt
  "Halt a system map by applying `halt-node' in reverse dependency order."
  {:arglists '([system] [system paths])}
  system-dispatch-fn)

(defmethod halt :default
  [system & [paths]]
  (let [deps (dependents system paths)]
    (run-action system deps halt-com)))

(defmulti resume 
  "Resume components from preceding suspend.
  Dependencies that have been previously suspended will also be resumed,
  dependencies which were halted or never initialized will be initialized."
  {:arglists '([system] [system paths])}
  system-dispatch-fn)

(defmethod resume :default
  [system & [paths]]
  (let [deps (dependencies system paths)]
    (run-action system deps resume-com)))

(defmulti resume-or-init
  "Resume components from preceding suspend.
  Dependencies that have been previously suspended will also be resumed,
  dependencies which were halted or never initialized will be initialized."
  {:arglists '([system] [system paths])}
  system-dispatch-fn)

(defmethod resume-or-init :default
  [system & [paths]]
  (let [deps (dependencies system paths)]
    (run-action system deps resume-or-init-com)))

(defmulti suspend 
  "Resume components from preceding suspend.
  All components that depend on this component will be also suspended."
  {:arglists '([system] [system paths])}
  system-dispatch-fn)

(defmethod suspend :default
  [system & [paths]]
  (let [deps (dependents system paths)]
    (run-action system deps suspend-com)))

