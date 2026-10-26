(ns steps.kernel-bus
  "Step definitions of dhf/spec/kernel/bus.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def check-events
  ["event: KnowledgeIndexed published-by knowledge/sources"
   "event: SpecParsed published-by spec/parser"
   "event: SpecGated published-by spec/check"])

(defn event-lines [err]
  (filter #(str/starts-with? % "event: ") (str/split-lines (str err))))

(defn- in-order? [lines want]
  (= want (filter (set want) lines)))

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx]
    (let [ls (event-lines (:err ctx))]
      (assert (every? (set ls) check-events) (str "events: " (pr-str ls) "; " (:err ctx)))
      (assert (in-order? ls check-events) (pr-str ls))
      ctx)))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx] (k/project! ctx)))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[a b] (map (comp event-lines :err) (:runs ctx))]
      (assert (seq a) "no event was listed")
      (assert (= a b) (str (pr-str a) " / " (pr-str b)))
      ctx)))

(defn shop-project!
  "A project set up by rdm-spec with one bounded context, shop, whose
  component till owns an empty feature, and fresh views."
  [ctx]
  (let [ctx (k/init-project! ctx)]
    (k/write-files! (:dir ctx) {"dhf/spec/model.edn" (k/pr-edn (k/model {:contexts [["shop" ["till"]]]}))
                                "dhf/spec/shop/till.feature" "Feature: Till\n  Takes payments.\n"})
    (k/views! ctx)))

(def catalogues
  {"empty" {}
   "one that declares only its own event OrderPlaced"
   {:OrderPlaced {:description "An order was placed" :published-by :shop/till
                  :carries [:order] :shape {:order "the order"}}}
   "one that declares SpecParsed with another publisher"
   {:SpecParsed {:description "Not rdm-spec's" :published-by :shop/till
                 :carries [:x] :shape {:x "x"}}}})

(defstep #"a project set up by rdm-spec whose configured event catalogue declares only the event OrderPlaced, published by its own component"
  (fn [ctx]
    (let [ctx (shop-project! ctx)]
      (k/write-files! (:dir ctx) {"dhf/spec/events.edn" (k/pr-edn (catalogues "one that declares only its own event OrderPlaced"))})
      ctx)))

(defstep #"it exits 0 and lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (in-order? (event-lines (:err ctx)) check-events) (:err ctx))
    ctx))

(defstep #"a project set up by rdm-spec whose configured event catalogue is (.+)"
  (fn [ctx which]
    (let [ctx (shop-project! ctx)]
      (when-not (contains? catalogues which) (fail! (str "no such catalogue: " which)))
      (k/write-files! (:dir ctx) {"dhf/spec/events.edn" (k/pr-edn (catalogues which))})
      ctx)))

(defstep #"it lists the same events, with the same publishers, in the same order, as with an empty catalogue"
  (fn [ctx]
    (let [got (event-lines (:err ctx))]
      (k/write-files! (:dir ctx) {"dhf/spec/events.edn" "{}\n"})
      (let [empty-run (k/rdm ctx ["check" "--events"])
            want (event-lines (:err empty-run))]
        (assert (seq want) (str "the empty catalogue's run listed no event: " (:err empty-run)))
        (assert (= want got) (str (pr-str got) " differs from " (pr-str want)))
        ctx))))
