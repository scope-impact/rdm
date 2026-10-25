(ns steps.kernel-bus
  "Step definitions for dhf/spec/kernel/bus.feature: `rdm-spec check
  --events` in a copy of the room (the shared When, steps.kernel-command).

  Context keys set here: :dir the copy; :runs both runs of a run twice (and
  :run, the second); :catalogue the configured catalogue's path."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- event-lines [run] (filterv kc/event-line? (s/err-lines run)))

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (s/err-lines r)
          es (event-lines r)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (= ["event: KnowledgeIndexed published-by knowledge/sources"
                    "event: SpecParsed published-by spec/parser"
                    "event: SpecGated published-by spec/check"]
                   es)
                (str "the events listed are " (pr-str es) "\n" (s/show r)))
      (s/check! (every? kc/event-line? (drop-while (complement kc/event-line?) ls))
                (str "the events do not come after the findings and warnings\n" (s/show r)))
      nil)))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice"
  (fn [ctx line]
    (let [a (kc/run-line ctx line)
          b (kc/run-line a line)]
      (assoc b :runs [(:run a) (:run b)]))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[a b] (:runs ctx)]
      (s/check! (and a b) "the command was not run twice")
      (s/check! (seq (event-lines a)) (str "the first run lists no event\n" (s/show a)))
      (s/check! (= (event-lines a) (event-lines b))
                (str "the runs list different events:\n" (s/show a) "\n" (s/show b)))
      nil)))

(defstep #"the configured event catalogue declares every other event, each with its publisher, but not SpecParsed"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rel (or (:events (kc/config-of dir)) "dhf/spec/events.edn")
          f (fs/file dir rel)
          catalogue (edn/read-string (slurp (str f)))]
      (s/check! (contains? catalogue :SpecParsed) "the catalogue declares no SpecParsed to leave out")
      (s/check! (every? :published-by (vals catalogue)) "an event of the catalogue names no publisher")
      (spit (str f) (pr-str (dissoc catalogue :SpecParsed)))
      (assoc ctx :dir dir :catalogue rel))))

(defstep #"it exits 2 and names SpecParsed and the configured catalogue"
  (fn [ctx]
    (kc/refused-naming! ctx "SpecParsed" (:catalogue ctx))
    nil))
