#_(set! *warn-on-reflection* true)

(ns keel.main
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]

            [keel.cmd :as cmd])
  (:gen-class))


(def usage "usage: keel")


(def root-option-spec
  [["-h" "--help"]])


(defn -usage
  [action summary]
  ;; TODO: Improve `-usage`
  (str "usage: " action "\n\n" summary))


(def actions
  {"get"     #'cmd/get
   "collect" #'cmd/collect
   "plan"    #'cmd/plan
   "apply"   #'cmd/apply
   "reverse" #'cmd/reverse
   "stable"  #'cmd/stable})


(defn -main-core
  [& args]

  (let [{:keys [options arguments errors summary]}
        (parse-opts args root-option-spec :in-order true)
        [action & action-args] arguments]

    (cond
      ;; TODO: Make this `errors` handling reusable for subcommands.
      errors {:status 1 :message (str/join "\n" errors)}
      (nil? action) {:status 1 :message (-usage "keel" summary)}
      :else
      (let [action-var (actions action)]
        (if-not action-var
          {:status 1 :message (str "fatal: '" action "' is not a keel command")}
          (let [action-cli-options (-> action-var meta :cli/options)
                {action-options :options
                 :keys [arguments errors summary]}
                (parse-opts action-args action-cli-options)]
            (cond
              errors {:status 1 :message (str/join "\n" errors)}
              :else (action-var arguments action-options))))))))

(comment
  (println (:message (-main-core "get")))

  )


(defn -main
  "The entry point for all of keel."
  [& args]

  (let [{:keys [message status] :or {status 0}}
        (apply -main-core args)]
    (when message (println message))
    (System/exit status)))


(comment
  (-main-core)

  (-main-core "get" "-w" "/api/v1/namespaces/default/configmaps")

  (-main-core "collect" "-Rf" "one.json" "-l" "things.txt" "-f" "two.json")


  (-main-core "collect"
              "--live" "live.json"

              "--start"
              "-f" "source-one.json"
              "-l" "source-things.txt"

              "--target"
              "-f" "target-one.json"
              "-l" "target-things.txt")


  ;; simple install
  (-main-core "collect"

              "--target"
              "-f" "scenarios/simple/r1/10-foo.json")

  ;; simple delete
  (-main-core "collect"

              "--start"
              "-f" "scenarios/simple/r1/10-foo.json")


  ;; TODO: Figure out how to set stdin when testing. (Should be possible to bind
  ;;       *in* to a string stream)
  (-main-core "plan" "waybill.json")

  ;; TODO: Figure out how to set stdin when testing. (Should be possible to bind
  ;;       *in* to a string stream)
  (-main-core "apply" "plan.json")
  (-main-core "stable")

  (-main-core "-x" "-y" "get" "-w")

  (-main-core "get" "-w")
  (-main-core "get")

  )
