(ns steps.kernel-bus
  "Steps of the kernel component Event bus (dhf/spec/08-plugins/bus.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def check-events
  ["event: KnowledgeIndexed published-by knowledge/sources"
   "event: SpecParsed published-by spec/parser"
   "event: SpecGated published-by spec/check"])

(defn event-lines [run] (filterv #(str/starts-with? % "event: ") (str/split-lines (str (:err run)))))

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx]
    (k/check! (= check-events (event-lines ctx)) "the events listed are " (pr-str (event-lines ctx)) "; " (k/shows ctx))
    ctx))

(defstep #"a spec and its sources unchanged between two runs" (fn [ctx] (k/spec-project! ctx)))

(defstep #"\"rdm-spec check --events\" is run twice"
  (fn [ctx] (-> ctx (k/run-rdm "check --events") (k/run-rdm "check --events"))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[a b] (take-last 2 (:runs ctx))]
      (k/check! (seq (event-lines a)) "no event was listed: " (k/shows a))
      (k/check! (= (event-lines a) (event-lines b)) "the runs listed " (pr-str (event-lines a)) " and " (pr-str (event-lines b)))
      ctx)))

(def order-placed
  {:OrderPlaced {:description "An order was placed" :published-by :shop/cart :carries [:order] :shape {:order "the order"}}})

(defn catalogue! [ctx which]
  (k/write-edn! ctx "dhf/spec/events.edn"
                (case which
                  "empty" {}
                  "one that declares only its own event OrderPlaced" order-placed
                  "one that declares SpecParsed with another publisher"
                  {:SpecParsed {:description "Parsed, the project's way" :published-by :shop/cart :carries [:x] :shape {:x "x"}}}))
  ctx)

(defstep #"a project set up by rdm-spec whose configured event catalogue declares only the event OrderPlaced, published by its own component"
  (fn [ctx] (catalogue! (k/project! ctx) "one that declares only its own event OrderPlaced")))

(defstep #"it exits 0 and lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= check-events (event-lines ctx)) "the events listed are " (pr-str (event-lines ctx)))
    ctx))

(defstep #"a project set up by rdm-spec whose configured event catalogue is (.+)"
  (fn [ctx which] (catalogue! (k/project! ctx) which)))

(defstep #"it lists the same events, with the same publishers, in the same order, as with an empty catalogue"
  (fn [ctx]
    (let [empty-run (k/run-rdm (catalogue! (k/project! (dissoc ctx :dir :base)) "empty") "check --events")]
      (k/check! (= check-events (event-lines empty-run)) "with an empty catalogue: " (k/shows empty-run))
      (k/check! (= (event-lines empty-run) (event-lines ctx)) "the events listed are " (pr-str (event-lines ctx)))
      ctx)))
