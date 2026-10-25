(ns steps.kernel-bus
  "Step definitions for dhf/spec/kernel/bus.feature, and the shared When
  `\"rdm-spec ...\" is run twice` (publishing/site uses it too).

  The events a run published are read from its error stream, as the spec's
  README fixes them: one line per event, `event: NAME published-by
  PUBLISHER`, after the findings and warnings. The publishers expected are
  read here from the project's own catalogue, independently of the bus."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private event-line #"event: (\S+) published-by (\S+)")

(defn event-lines
  "The run's event lines, parsed: [[name publisher] ...] in printed order."
  [run]
  (vec (keep #(when-let [[_ n p] (re-matches event-line %)] [n p]) (s/err-lines run))))

(defn- check-events-last!
  "Fails unless every event line comes after every other line of the error stream."
  [run]
  (let [lines (s/err-lines run)
        flags (map #(boolean (re-matches event-line %)) lines)]
    (s/check! (= flags (sort-by #(if % 1 0) flags))
              (str "an event line comes before a finding or a warning\n" (s/show run)))))

(defn- names-in
  "\"A, B and C\" -> [\"A\" \"B\" \"C\"]."
  [s]
  (vec (remove str/blank? (map str/trim (str/split s #",|\band\b")))))

(defn- config-of [dir]
  (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- catalogue-of [dir]
  (edn/read-string (slurp (str (fs/file dir (:events (config-of dir)))))))

(defn- kw-name [k] (subs (str k) 1))

(defn- tree
  "{rel bytes-as-vector} of every file under `d`."
  [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

;; ---------------------------------------------------------------------------
;; A run can show the events it published, and they never vary

(defstep #"the error stream lists (.+), each with its publisher, in the order they were published"
  (fn [ctx names]
    (let [r (or (:run ctx) (s/fail! "no command was run"))
          catalogue (catalogue-of (:dir ctx))
          expected (mapv (fn [n]
                           (let [decl (get catalogue (keyword n))]
                             (s/check! decl (str n " is not in the project's catalogue"))
                             [n (kw-name (:published-by decl))]))
                         (names-in names))]
      (s/check! (#{0 1} (:exit r)) (str "the task did not run\n" (s/show r)))
      (s/check! (= expected (event-lines r))
                (str "expected the events " (pr-str expected) ", got " (pr-str (event-lines r)) "\n" (s/show r)))
      (check-events-last! r))))

(defstep #"a spec and its sources unchanged between two runs"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :inputs (tree (fs/file dir "dhf"))))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice"
  (fn [ctx line]
    (let [c1 (kc/run-line ctx line)
          _ (when-let [before (:inputs ctx)]
              (s/check! (= before (tree (fs/file (:dir c1) "dhf")))
                        "the first run changed the inputs under dhf/"))
          c2 (kc/run-line c1 line)]
      (assoc c2 :runs [(:run c1) (:run c2)]))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (let [[r1 r2] (:runs ctx)]
      (s/check! (and r1 r2) "the command was not run twice")
      (doseq [r [r1 r2]]
        (s/check! (#{0 1} (:exit r)) (str "the task did not run\n" (s/show r)))
        (s/check! (seq (event-lines r)) (str "the run lists no event\n" (s/show r))))
      (s/check! (= (event-lines r1) (event-lines r2))
                (str "the runs list different events:\n" (pr-str (event-lines r1)) "\n" (pr-str (event-lines r2)))))))

;; ---------------------------------------------------------------------------
;; The bus carries only the events the project's catalogue declares

(def ^:private moved-catalogue
  "Where the scenario configures its catalogue: not the default path, so a bus
  that read a fixed path would be caught."
  "dhf/harness/events-under-test.edn")

(defstep #"the configured event catalogue declares every other event, each with its publisher, but not (\S+)"
  (fn [ctx event]
    (let [dir (s/copy-of-room!)
          cfg (config-of dir)
          catalogue (catalogue-of dir)]
      (s/check! (contains? catalogue (keyword event)) (str event " is not in the room's catalogue"))
      (s/check! (every? :published-by (vals catalogue)) "an event in the room's catalogue has no publisher")
      (s/write! dir moved-catalogue (pr-str (dissoc catalogue (keyword event))))
      (s/write! dir "dhf/harness/config.edn" (pr-str (assoc cfg :events moved-catalogue)))
      (assoc ctx :dir dir))))

(defstep #"it exits 2 and names (\S+) and the configured catalogue"
  (fn [ctx event]
    (let [r (or (:run ctx) (s/fail! "no command was run"))
          configured (:events (config-of (:dir ctx)))
          lines (filter #(and (str/includes? % event) (str/includes? % configured)) (s/err-lines r))]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
      (s/check! (some #(str/starts-with? % "cannot-run: ") lines)
                (str "no cannot-run line names " event " and " configured "\n" (s/show r))))))
