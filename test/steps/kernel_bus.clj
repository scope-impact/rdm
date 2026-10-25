(ns steps.kernel-bus
  "Step definitions for dhf/spec/kernel/bus.feature: the events a run prints
  with --events (`event: NAME published-by PUBLISHER`, after the findings and
  warnings), and the bus's refusal of an event its catalogue does not declare.
  Each scenario runs the command in its own copy of the room's allow-list."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def event-line
  "One event line: its name and its publisher."
  #"^event: (\S+) published-by (\S+)$")

(defn- events-of
  "The events a run printed: [[name publisher] ...], in printed order."
  [r]
  (vec (keep #(when-let [[_ n p] (re-matches event-line %)] [n p]) (s/err-lines r))))

(defn- check-room!
  "A copy of the room's allow-list in which rdm-spec check can run."
  []
  (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
                  'rdm-spec.architecture.lint 'rdm-spec.architecture.views)
  (str (s/copy-of-room!)))

(defn- catalogue-file [dir]
  (str (fs/file dir (:events (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))))))

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx]
    (let [r (or (:run ctx) (s/fail! "no command was run"))
          catalogue (edn/read-string (slurp (catalogue-file (:dir ctx))))
          want (mapv (fn [e] [e (kw-name (get-in catalogue [(keyword e) :published-by]))])
                     ["KnowledgeIndexed" "SpecParsed" "SpecGated"])
          lines (s/err-lines r)
          got (events-of r)]
      (s/check! (= want got) (str "the events printed are " (pr-str got) ", not " (pr-str want) "\n" (s/show r)))
      (s/check! (every? #(re-matches event-line %) (drop-while #(not (str/starts-with? % "event: ")) lines))
                (str "the event lines are not the last on the error stream\n" (s/show r))))))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx] (assoc ctx :dir (check-room!))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice"
  (fn [ctx line]
    (let [first-run (kc/run-line ctx line)
          second-run (kc/run-line first-run line)]
      (assoc second-run :runs [(:run first-run) (:run second-run)]))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[a b] (or (:runs ctx) (s/fail! "the command was not run twice"))
          ea (events-of a)
          eb (events-of b)]
      (s/check! (seq ea) (str "the first run printed no event\n" (s/show a)))
      (s/check! (= ea eb) (str "the runs printed different events: " (pr-str ea) " then " (pr-str eb))))))

(def other-catalogue "dhf/spec/other-events.edn")

(defstep #"the configured event catalogue declares events, but not SpecParsed"
  (fn [ctx]
    (let [dir (check-room!)
          catalogue (edn/read-string (slurp (catalogue-file dir)))
          config-f (str (fs/file dir "dhf/harness/config.edn"))]
      (s/check! (contains? catalogue :SpecParsed) "the room's catalogue declares no SpecParsed")
      ;; another file, configured as the catalogue, so the refusal must name the configured one
      (s/write! dir other-catalogue (pr-str (dissoc catalogue :SpecParsed)))
      (spit config-f (pr-str (assoc (edn/read-string (slurp config-f)) :events other-catalogue)))
      (assoc ctx :dir dir :catalogue other-catalogue))))

(defstep #"it exits 2 and names SpecParsed and the configured catalogue"
  (fn [ctx]
    (let [r (or (:run ctx) (s/fail! "no command was run"))]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % "cannot-run: ")
                            (str/includes? % "SpecParsed")
                            (str/includes? % (:catalogue ctx)))
                      (s/err-lines r))
                (str "no cannot-run line names SpecParsed and " (:catalogue ctx) "\n" (s/show r))))))
