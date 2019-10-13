(ns keel.resources
  (:require [clojure.core.async :refer [chan go pipe <! >!] :as async]
            [clojure.core.match :refer [match]]
            [clojure.string :as str]

            [kapibara.core :as k]
            [kapibara.discovery :as kd]
            [kapibara.util :as ku]))


(defn parse-ref
  [ref]

  (cond
    (not (str/starts-with? ref "/")) {:error "invalid ref format"}
    (str/ends-with? ref "/") {:error "invalid ref format"}
    :else
    (match
     (str/split (str/replace ref #"^/|/$" "") #"/")

     ;; Core API
     ["api" version resource-name name]
     {:groupVersion version
      :name resource-name
      :object/name name}

     ["api" version resource-name]
     {:groupVersion version
      :name resource-name}

     ["api" version "namespaces" ns resource-name]
     {:groupVersion version
      :name resource-name
      :namespace/name ns}

     ["api" version "namespaces" ns resource-name name]
     {:groupVersion version
      :name resource-name
      :namespace/name ns
      :object/name name}

     ;; Non-core APIs
     ["apis" group version resource-name]
     {:groupVersion (str group "/" version)
      :name resource-name}

     ["apis" group version resource-name name]
     {:groupVersion (str group "/" version)
      :name resource-name
      :object/name name}

     ["apis" group version "namespaces" ns resource-name]
     {:groupVersion (str group "/" version)
      :name resource-name
      :namespace/name ns}

     ["apis" group version "namespaces" ns resource-name name]
     {:groupVersion (str group "/" version)
      :name resource-name
      :namespace/name ns
      :object/name name}

     :else {:error "invalid ref format"})))


(defn- cache-api-resource
  [m resource]
  (let [[group version] (ku/split-group-version (:groupVersion resource))]
    (assoc-in m [group version (:name resource)] resource)))


(defn cache-api-resource-list
  [m resource-list]
  (let [resources (-> resource-list
                      kd/distribute-list-group-version
                      :resources)]
    (reduce cache-api-resource m resources)))


;; FIXME: Building caches two different ways is messy and repetitive.
(defn- cache-api-resource-by-kind
  [m resource]
  (let [[group version] (ku/split-group-version (:groupVersion resource))]
    (assoc-in m [group version (:kind resource)] resource)))


;; FIXME: Building caches two different ways is messy and repetitive.
(defn cache-api-resource-list-by-kind
  [m resource-list]
  (let [resources (-> resource-list
                      kd/distribute-list-group-version
                      :resources)]
    (reduce cache-api-resource-by-kind m resources)))


(defn hack-build-api-resource-cache-from-scratch
  [client]
  (let [groups (clojure.core.async/<!! @(kd/get-api-groups client))
        all-res (->> groups
                     :groups
                     (sequence (comp
                                (map :preferredVersion)
                                (map (partial kd/get-api-resources client))
                                (map deref)))
                     async/merge
                     (async/into [])
                     (async/<!!))]
    {:by-name (reduce cache-api-resource-list {} all-res)
     :by-kind (reduce cache-api-resource-list-by-kind {} all-res)}))

(comment
  (def client (k/make-client "http://localhost:8080"))

  (hack-build-api-resource-cache-from-scratch client)

  (parse-ref "/api/v1/nodes/foo")
  (parse-ref "/api/v1/nodes")

  (parse-ref "/api/v1/namespaces/default/configmaps/foo")

  (parse-ref "/api/v1/namespaces/default/configmaps")
  (parse-ref "/apis/rbac.authorization.k8s.io/v1/namespaces/kube-system/roles/kube-proxy")
  (parse-ref "/apis/rbac.authorization.k8s.io/v1/namespaces/kube-system/roles")
  (parse-ref "/apis/rbac.authorization.k8s.io/v1/clusterroles/view")
  (parse-ref "/apis/rbac.authorization.k8s.io/v1/clusterroles")

  ;; invalid
  (parse-ref "api/v1/namespaces/default/configmaps/foo")
  (parse-ref "/api/v1/namespaces/default/configmaps/foo/")
  (parse-ref "/apis/rbac.authorization.k8s.io/v1/clusterroles/one/two")

  )
