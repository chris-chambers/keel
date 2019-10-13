(ns keel.core
  (:refer-clojure :exclude [apply get])
  (:require [clojure.core.async :as async]
            [clojure.core.match :refer [match]]
            [clojure.string :as str]

            [kapibara.core :as k]
            [kapibara.resources :as kr]
            [kapibara.util :as ku]

            [keel.resources :as res]))


;; TODO: May belong in keel.resources
(defn- ref->resource
  [resource-cache ref]
  (let [[group version] (ku/split-group-version (:groupVersion ref))]
    (get-in resource-cache [:by-name group version (:name ref)])))


;; TODO: May belong in keel.resources
(defn- object->resource
  [resource-cache obj]
  (let [[group version] (ku/split-group-version (:apiVersion obj))
        path [:by-kind group version (:kind obj)]]
    (get-in resource-cache path)))


;; TODO: May belong in keel.resources
(defn- object->ref
  [resource obj]
  (merge (select-keys resource [:groupVersion :name])
         (when (:namespaced resource)
           {:namespace/name (-> obj :metadata :namespace)})
         {:object/name (-> obj :metadata :name)}))


(defn- get-one
  [client resource-cache ref options]
  (let [resource (ref->resource resource-cache ref)
        ;; FIXME: This probably needs to be conditional _inside_ the transducer
        ;;        for maximum correctness.  I'm just not sure how to do it.
        ch (async/chan 1 (if (:object/name ref)
                           (map identity)
                           (comp
                            (map kr/distribute-list-version-kind)
                            kr/unpack-list)))]

    (k/update-request-chan
     (kr/request client (merge ref options {:resource resource}))
     #(async/pipe % ch))))


(defn get
  ([client resource-cache refs]
   (get client resource-cache refs nil))
  ([client resource-cache refs options]
   (k/merge-requests (map #(get-one client resource-cache % options) refs))))


(defn- watch-one
  [client resource-cache ref options]
  (let [resource (ref->resource resource-cache ref)]
    (kr/request client (merge ref options {:resource resource
                                           :verb :watch}))))


(defn watch
  ([client resource-cache refs]
   (watch client resource-cache refs nil))
  ([client resource-cache refs options]
   (k/merge-requests (map #(watch-one client resource-cache % options) refs))))


;; FIXME: Needs to use a resource cache to produce canonical refs (synthetic,
;;        at the :preferredVersion)
(defn- object-key [obj]
  (str/join "/" [(-> obj :metadata :namespace)
                 (-> obj :kind)
                 (-> obj :metadata :name)]))


(defn- classify-apply
  [start-map live-map target-map k]

  (match
   [(start-map k) (live-map k) (target-map k)]
   [nil nil nil] :invalid
   [nil nil  _ ] :create
   [nil  _  nil] :invalid
   [nil  _   _ ] :annex
   [ _  nil nil] :gone
   [ _  nil  _ ] :restore
   [ _   _  nil] :delete
   [src  _  tgt] :update ; TODO: (if different :update :up-to-date)
   ))


(def ^:private apply-source-map
  {:create  :target
   :annex   :target
   :gone    :start
   :restore :target
   :delete  :live
   :update  :target})


(def ^:private apply-verb-map
  {:create  :create
   :annex   :update
   :restore :create
   :delete  :delete
   :update  :update})


(defn plan-apply
  [start live target]

  (let [start-map  (zipmap (map object-key start) start)
        target-map (zipmap (map object-key target) target)
        live-map   (zipmap (map object-key live) live)

        classify #(classify-apply start-map live-map target-map %)

        all-keys (set (map object-key (concat start target)))
        actions (zipmap all-keys (map classify all-keys))
        add-meta (fn [item]
                   (let [k (object-key item)
                         action (actions k)
                         source (apply-source-map action)]
                     {:key k
                      :action action
                      :source source
                      :item item}))]

    (map #(dissoc % :key :source)
         (concat
          (sequence (comp (map add-meta) (filter #(#{:target} (:source %)))) target)
          (sequence (comp (map add-meta)
                          (filter #(#{:start :live} (:source %)))
                          (map #(if (= :live (:source %))
                                  (assoc % :item (live-map (:key %)))
                                  %)))
                    (reverse start))))))


(defn- execute-apply-one
  [client resource-cache plan-item]
  (let [obj (:item plan-item)
        verb (apply-verb-map (:action plan-item))
        resource (object->resource resource-cache obj)
        options (merge {:resource resource
                        :body obj
                        :verb verb}
                       (object->ref resource obj))]
    (when verb
      (kr/request client options))))


(defn execute-apply
  [client resource-cache plan]
  ;; REVIEW: This code is fairly intricate to allow the whole request to be
  ;;         aborted without leaking anything.  Is it possible to make the
  ;;         concept of a serial, abortable `kapibara/Request` part of Kapibara
  ;;         itself?  Similar to `kapibara/merge-requests`, but lazy and serial.
  (let [ch (async/chan)
        ;; Keeps the currently-active connection for `abortfn` to use.  Contains
        ;; `::aborted` when the called has aborted this process.
        active-conn (atom ::none)
        abortfn (fn abort []
                  (throw (Exception. "abort is hard to implement on Clojure < 1.9.0. Waiting on GraalVM 19.3, for JDK 11 and the new HTTPClient, anyway"))
                  #_(let [[conn _] (reset-vals! active-conn ::aborted)]
                    (if-not (#{::none ::aborted} conn)
                      (k/abort! conn))))]
    (async/go-loop [plan' plan]
      (if-let [plan-item (first plan')]
        (if-let [conn (execute-apply-one client resource-cache plan-item)]
          (if-not (compare-and-set! active-conn ::none conn)
            ;; If c-a-s fails, we have been aborted, but the `abort` fn did not
            ;; see this connection.  Clean it up and stop.
            (k/abort! conn)
            ;; Wait for the result.  Note that this does not support multi-value
            ;; results.  But, we currently always generate single-value requests,
            ;; so there is no need.
            (let [result (async/<! @conn)]
              (async/>! ch (merge plan-item {:result result}))
              ;; If aborted, or if the result was an error, stop early.
              (when (and (compare-and-set! active-conn conn ::none)
                         (not (:kapibara/error result)))
                (recur (rest plan')))))
          (do
            (async/>! ch plan-item)
            (recur (rest plan'))))
        ;; If there are no more items, close the channel and set `active-conn`
        ;; to an idle state.
        (do (async/close! ch)
            (reset! active-conn ::none))))
    (k/->Request ch abortfn)))

(comment
  (plan-apply start live target)


  (<!! @(execute-apply client res-cache (plan-apply start live target)) )

  (do
    (require '[clojure.core.async :refer [<!!]])

    (def refs (into [] (map res/parse-ref)
                    ["/api/v1/namespaces/default/configmaps"
                     #_"/api/v1/namespaces/foo/configmaps"]))

    (def client (k/make-client "http://localhost:8080"))
    (def res-cache (res/hack-build-api-resource-cache-from-scratch client)))

  (def x (get client res-cache refs))
  (<!! (async/into [] @x))

  (def x (get-one client
                   res-cache
                   (clojure.core/get refs 0)
                   {}))

  (<!! @x)

  (def x (watch client res-cache refs))
  (<!! @x)

  (k/abort! x)

  (do
    (def start [{:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "foo"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "quux"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "xyzzy"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "killed"
                            :namespace "default"}}

                ])

    (def target [{:kind "ConfigMap"
                  :apiVersion "v1"
                  :metadata {:name "foo"
                             :namespace "default"}
                  :data {:boom "goes the dynamite"}}

                 {:kind "ConfigMap"
                  :apiVersion "v1"
                  :metadata {:name "bar"
                             :namespace "default"}}

                 {:kind "ConfigMap"
                  :apiVersion "v1"
                  :metadata {:name "baz"
                             :namespace "default"}}

                 {:kind "ConfigMap"
                  :apiVersion "v1"
                  :metadata {:name "killed"
                             :namespace "default"}}

                 ])

    (def live
      (let [refs (set (map #(object->ref (object->resource res-cache %) %)
                           (concat start target)))]
        (remove #(:kapibara/error %)
                (<!! (async/into [] @(get client res-cache refs))))))

    (let [plan (plan-apply start live target)]
      plan
      (<!! (async/into [] @(execute-apply client res-cache plan)))))

  (do
    (def start [{:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "foo"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "bar"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "baz"
                            :namespace "default"}}

                {:kind "ConfigMap"
                 :apiVersion "v1"
                 :metadata {:name "killed"
                            :namespace "default"}}])

    (def target nil)

    (def live
      (let [refs (set (map #(object->ref (object->resource res-cache %) %)
                           (concat start target)))]
        (remove #(:kapibara/error %)
                (<!! (async/into [] @(get client res-cache refs))))))

    (let [plan (plan-apply start live target)]
      plan
      (<!! (async/into [] @(execute-apply client res-cache plan)))))

  )
