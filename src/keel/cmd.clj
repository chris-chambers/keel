(ns keel.cmd
  (:refer-clojure :exclude [apply get reverse])
  (:require [clojure.core.async :refer [<!!] :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]

            [kapibara.core :as k]

            [keel.core :as core]
            [keel.internal.cli :as cli]
            [keel.resources :as res]))


(defn- print-request-items
  [req]
  (async/go-loop []
    (when-let [item (async/<! @req)]
      (json/pprint item :escape-slash false)
      (recur))))


;; TODO: Add output format option for '--watch' that would push the `type` field
;;       down into an `event/type` field inside the objects themselves.  This
;;       would allow users to have consistent queries in programs like `jq` when
;;       switching from `get` to `get --watch`.
(defn get
  {:cli/usage "[REF ...]"
   :cli/options [["-w" "--watch"]]}
  [args options]
  (let [client (k/client {:uri "http://localhost:8001"})
        res-cache (res/hack-build-api-resource-cache-from-scratch client)
        refs (map res/parse-ref args)
        prn-chan (async/chan 2 (map json/pprint))
        cmd (if (:watch options) core/watch core/get)
        req (cmd client res-cache refs)]

    (<!! (print-request-items req))))


(defn- get-live-state-from-objects
  [client resource-cache objects]
  (let [refs (set (map #(core/object->ref-XX resource-cache %) objects))]
    ;; FIXME: die on error.
    (remove #(:kapibara.core/error %)
            (<!! (async/into [] @(core/get client resource-cache refs))))))


(defn collect
  {:cli/usage ""
   :cli/options [["-s" "--start"
                  :assoc-fn (cli/comp-middleware (cli/assoc-at :last-group)
                                                 (cli/with-value :start))]
                 ["-t" "--target"
                  :assoc-fn (cli/comp-middleware (cli/assoc-at :last-group)
                                                 (cli/with-value :target))]
                 ["-R" "--recursive"]
                 ["-f" "--filename FILENAME"
                  :parse-fn io/as-file
                  :assoc-fn (cli/comp-middleware (cli/assoc-at :inputs)
                                                 (cli/tag-value :file)
                                                 cli/set-group
                                                 cli/maybe-recursive
                                                 (cli/clear :recursive)
                                                 cli/concat-value)]
                 ["-l" "--list FILENAME"
                  :parse-fn io/as-file
                  :assoc-fn (cli/comp-middleware (cli/assoc-at :inputs)
                                                 (cli/tag-value :list)
                                                 cli/set-group
                                                 (cli/clear :recursive)
                                                 cli/concat-value)]
                 ["-z" "--[no-]live"
                  :default false]]}
  [args options]
  (let [start-sources (filter #(= :start (:group %)) (:inputs options))
        start (core/collect start-sources)
        target-sources (filter #(= :target (:group %)) (:inputs options))
        target (core/collect target-sources)]
    (json/pprint (merge (when (not-empty start-sources) {:start start})
                        (when (not-empty target-sources) {:target target})
                        (when (:live options)
                          (let [client (k/client {:uri "http://localhost:8001"})
                                res-cache (res/hack-build-api-resource-cache-from-scratch client)
                                live (get-live-state-from-objects client res-cache (concat start target))]
                            {:live live})))
                 :escape-slash false)))


(defn plan
  {:cli/usage "[FILENAME]"
   :cli/options []}
  [args options]
  (let [client (k/client {:uri "http://localhost:8001"})
        res-cache (res/hack-build-api-resource-cache-from-scratch client)
        ;; TODO: Don't always read from stdin.  Check args[0]
        {:keys [start live target]} (json/read *in* :key-fn keyword)
        live (if live
               live
               (get-live-state-from-objects client res-cache (concat start target)))
        plan (core/plan-apply start live target)]
    (json/pprint plan :escape-slash false))
  {:status 0})


(defn apply
  {:cli/usage "[FILENAME]"
   :cli/options []}
  [args options]

  ;; TODO: Validate that the `live` list in the plan matches the cluster
  ;;       (by resource uid and version).  If not, die. (add an override flag?)

  (let [client (k/client {:uri "http://localhost:8001"})
        res-cache (res/hack-build-api-resource-cache-from-scratch client)
        ;; TODO: Don't always read from stdin.  Check args[0]
        plan (into [] (map #(update % :action keyword))
                   (json/read *in* :key-fn keyword))
        req (core/execute-apply client res-cache plan)]

    (<!! (print-request-items req))))


(defn reverse
  {:cli/usage ""
   :cli/options []}
  [args options]
  ;; TODO: reverse should take the output of `apply` and produce a waybill for
  ;;       `plan` that puts everything back like it was.
  (println "reverse" args options))


(defn stable
  {:cli/options []}
  [args options]
  (println "stable" args options))


(comment
  (get ["/api/v1/namespaces/default/configmaps"] {})

  (def target-ns "foo")

  (def apply1
    {:start []
     :target [{:apiVersion "v1"
               :kind "Namespace"
               :metadata {:name target-ns}}
              {:apiVersion "v1"
               :kind "ConfigMap"
               :metadata {:name "foo"
                          :namespace target-ns}
               :data {"what" "great!"}}]})

  (spit "apply1.json" (str (json/write-str apply1) \newline))

  )
