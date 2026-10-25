(ns steps.kernel-bus
  "Step definitions for dhf/spec/kernel/bus.feature, and the shared `\"rdm-spec
  ...\" is run twice` (also dhf/spec/publishing/site.feature): both runs are
  kept as :runs, the second also as :run. Events are read from the error
  stream, where `--events` prints one line per event:
  `event: NAME published-by PUBLISHER`."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

(defn- run-twice [ctx line]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        args (mapv #(if (= "ROOM" %) (or (:room ctx) "/tmp/room") %) (s/split-args line))
        r1 (apply s/rdm-spec! dir args)
        r2 (apply s/rdm-spec! dir args)]
    (assoc ctx :dir (str dir) :runs [r1 r2] :run r2)))

(defn events
  "The events a run printed: [[name publisher] ...], in order."
  [r]
  (vec (for [l (s/err-lines r)
             :let [[_ e p] (re-matches #"event: (\S+) published-by (\S+)" l)]
             :when e]
         [e p])))

(defn- needs-check! []
  (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
                  'rdm-spec.architecture.lint 'rdm-spec.architecture.views))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice"
  (fn [ctx line] (run-twice ctx line)))

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx]
    (needs-check!)
    (let [r (:run ctx)
          evs (events r)]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (= [["KnowledgeIndexed" "knowledge/sources"] ["SpecParsed" "spec/parser"] ["SpecGated" "spec/check"]]
                   evs)
                (str "the events printed are " (pr-str evs) "\n" (s/show r))))))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (needs-check!)
    (let [[r1 r2] (:runs ctx)]
      (s/check! (and r1 r2) "the command was not run twice")
      (s/check! (seq (events r1)) (str "the first run printed no event\n" (s/show r1)))
      (s/check! (= (events r1) (events r2))
                (str "the runs differ: " (pr-str (events r1)) " and " (pr-str (events r2)))))))

(defstep #"the configured event catalogue does not declare (\S+)"
  (fn [ctx event]
    (let [dir (s/copy-of-room!)
          rel (:events (s/room-config))
          f (fs/file dir rel)
          cat (edn/read-string (slurp f))]
      (s/check! (contains? cat (keyword event)) (str rel " does not declare " event " to begin with"))
      (spit f (pr-str (dissoc cat (keyword event))))
      (assoc ctx :dir dir :event event :catalogue rel))))

(defstep #"it exits 2 and names (\S+) and the configured catalogue"
  (fn [ctx event]
    (needs-check!)
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (some #(and (str/includes? % event) (str/includes? % (:catalogue ctx))) (s/err-lines r))
                (str "no line names " event " and " (:catalogue ctx) "\n" (s/show r))))))
