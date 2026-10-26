(ns steps.kernel-bus
  "Step definitions for dhf/spec/kernel/bus.feature: `rdm-spec check
  --events` in a project rdm-spec set up (steps.kernel-command's
  project-set-up!), whose configured event catalogue a step writes.

  The run prints each event it published on the error stream, after the
  findings and warnings, as `event: NAME published-by PUBLISHER`
  (dhf/spec/README.md, \"How every task reports\").

  Context keys set here: :dir (the project), :runs (both runs of a step that
  runs the command twice), :run (the last run)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def check-events
  "What rdm-spec check publishes, in order, with each publisher."
  [["KnowledgeIndexed" "knowledge/sources"] ["SpecParsed" "spec/parser"] ["SpecGated" "spec/check"]])

(defn event-lines
  "The event lines of a run's error stream, in order."
  [run]
  (filterv #(str/starts-with? % "event: ") (s/err-lines run)))

(defn events-of
  "[[NAME PUBLISHER]] of a run's event lines."
  [run]
  (vec (for [l (event-lines run)]
         (let [[_ n p] (re-matches #"event: (\S+) published-by (\S+)" l)]
           (s/check! n (str "not an event line: " l))
           [n p]))))

(defn- events-last!
  "Checks the event lines come after every finding and warning."
  [run]
  (let [ls (s/err-lines run)
        firsts (drop-while #(not (str/starts-with? % "event: ")) ls)]
    (s/check! (every? #(str/starts-with? % "event: ") firsts)
              (str "a finding or warning follows the events\n" (s/show run)))))

(defn- lists-check-events! [run]
  (s/check! (= check-events (events-of run))
            (str "the events listed are " (pr-str (events-of run)) ", not " (pr-str check-events) "\n" (s/show run)))
  (events-last! run))

;; ---------------------------------------------------------------------------
;; A project's own catalogue

(def ^:private own-event
  {:OrderPlaced {:description "An order was placed"
                 :published-by :shop/orders
                 :carries [:order]
                 :shape {:order "{:id :lines}"}}})

(def ^:private catalogues
  {"empty" {}
   "one that declares only its own event OrderPlaced" own-event
   "one that declares SpecParsed with another publisher"
   {:SpecParsed {:description "Not rdm-spec's SpecParsed" :published-by :shop/orders
                 :carries [:order] :shape {:order "an order"}}}})

(defn- write-catalogue! [dir cat]
  (let [rel (or (:events (kc/config-of dir)) "dhf/spec/events.edn")]
    (s/write! dir rel (str ";; The project's own events.\n" (pr-str cat) "\n"))))

(defn- with-catalogue [ctx cat]
  (let [ctx (kc/project-set-up! ctx)]
    (write-catalogue! (:dir ctx) cat)
    ctx))

(defstep #"a project set up by rdm-spec whose configured event catalogue declares only the event OrderPlaced, published by its own component"
  (fn [ctx] (with-catalogue ctx own-event)))

(defstep #"a project set up by rdm-spec whose configured event catalogue is (.+)"
  (fn [ctx which]
    (with-catalogue ctx (or (get catalogues which) (s/fail! (str "no such catalogue: " which))))))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx] (kc/project-set-up! ctx)))

;; ---------------------------------------------------------------------------
;; Running

(defstep #"\"rdm-spec ([^\"]*)\" is run twice"
  (fn [ctx line]
    (let [a (kc/run-line ctx line)
          b (kc/run-line a line)]
      (assoc b :runs [(:run a) (:run b)]))))

;; ---------------------------------------------------------------------------
;; What a run shows

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx] (lists-check-events! (kc/the-run ctx)) nil))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[a b] (:runs ctx)]
      (s/check! (and a b) "the command was not run twice")
      (s/check! (seq (events-of a)) (str "the first run lists no event\n" (s/show a)))
      (s/check! (= (events-of a) (events-of b))
                (str "the runs list " (pr-str (events-of a)) " and " (pr-str (events-of b))))
      nil)))

(defstep #"it exits 0 and lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher"
  (fn [ctx]
    (lists-check-events! (kc/exits! ctx 0))
    nil))

(defstep #"it lists the same events, with the same publishers, in the same order, as with an empty catalogue"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          dir (:dir ctx)
          _ (write-catalogue! dir {})
          empty-run (:run (kc/run-line ctx "check --events"))]
      (s/check! (seq (events-of empty-run)) (str "a run with an empty catalogue lists no event\n" (s/show empty-run)))
      (s/check! (= (events-of empty-run) (events-of r))
                (str "the run lists " (pr-str (events-of r)) "; with an empty catalogue "
                     (pr-str (events-of empty-run)) "\n" (s/show r)))
      (s/check! (fs/exists? (fs/file dir)) "the project is gone")
      nil)))
