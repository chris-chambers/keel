(ns keel.internal.cli)

(defn assoc-at
  [key]
  (fn [next]
    (fn [map _ val]
      (next map key val))))


(defn with-value
  [val]
  (fn [next]
    (fn [map key _]
      (next map key val))))

(defn tag-value
  [tag]
  (fn [next]
    (fn [map key val]
      (next map key {tag val}))))


(defn set-group
  [next]
  (fn [map key val]
    (next map key (assoc val :group (:last-group map :target)))))


(defn concat-value
  [next]
  (fn [map key val]
    (next map key (concat (clojure.core/get map key) [val]))))


(defn maybe-recursive
  [next]
  (fn [map key val]
    (let [val' (merge val (when (:recursive map) {:recursive true}))]
      (next map key val'))))


(defn clear
  [key]
  (fn [next]
    (fn [map k val]
      (next (dissoc map key) k val))))


(defn comp-middleware
  [& middlewares]
  ((apply comp middlewares) assoc))
