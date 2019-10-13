(ns keel.cmd
  (:refer-clojure :exclude [apply get])
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
  (let [client (k/make-client "http://localhost:8080")
        res-cache (res/hack-build-api-resource-cache-from-scratch client)
        refs (map res/parse-ref args)
        prn-chan (async/chan 2 (map json/pprint))
        cmd (if (:watch options) core/watch core/get)
        req (cmd client res-cache refs)]

    (<!! (print-request-items req))))


(defn plan
  {:cli/usage "[FILENAME]"
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
                                                 cli/maybe-recursive
                                                 (cli/clear :recursive)
                                                 cli/concat-value)]
                 ["-z" "--live FILENAME"
                  :parse-fn io/as-file]]}
  [args options]

  ;; TODO: Look for a waybill as the one-and-only argument.  If `:inputs` or
  ;;       `:live` are set along with the waybill, die.
  ;; TODO: Round up all the resources given by `:inputs` and `:live`.
  ;; TODO: If `:live` is not present, query the cluster based on `:inputs`.

  #_(core/plan-apply start live target))


(defn apply
  {:cli/options []}
  [args options]

  ;; TODO: Read the plan from a file in args[0], or from stdin.
  ;; TODO: Create-or-load the resource cache for this server.
  ;; TODO: Validate that the `live` list in the plan matches the cluster
  ;;       (by resource uid and version).  If not, die. (add an override flag?)
  ;; TODO: Print/write the result of applying.
  #_(core/execute-apply resource-cache plan))


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
