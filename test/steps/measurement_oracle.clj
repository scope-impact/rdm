(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature.

  An oracle fixture (`oracle!`) is written into a project's
  dhf/harness/oracle/: a fixture project tree (the room's model and one
  feature file of three scenarios, one planned), cycles 01 and 02 of the cut
  spec-1 with their ratifications and verdicts, and goldens written here by
  hand (`goldens`), never by the instrument. The scenarios run the oracle
  task, which the kernel's task table dispatches: until it does, they cannot
  be verified (broken)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def oracle-dir "dhf/harness/oracle")

(defn- task-row [task]
  (some #(when (= task (:task %)) %) @(requiring-resolve 'rdm-spec.kernel.command/tasks)))

(defn needs-oracle-task!
  "Broken until the kernel's task table has the oracle task."
  []
  (s/needs-built! 'rdm-spec.measurement.oracle)
  (when-not (task-row "oracle")
    (throw (ex-info "cannot be verified yet: the kernel's task table has no oracle task"
                    {:rdm-spec.kernel.runner/broken true}))))

(defn needs-gate!
  "Broken until the kernel runs the oracle as the gate of `task`."
  [task]
  (needs-oracle-task!)
  (when-not (str/includes? (pr-str (task-row task)) "rdm-spec.measurement.oracle")
    (throw (ex-info (str "cannot be verified yet: the kernel's task table runs no oracle gate before " task)
                    {:rdm-spec.kernel.runner/broken true}))))

;; ---------------------------------------------------------------------------
;; The fixture

(def scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def verdicts
  {"01" [{:id "spec-0012" :verdict :fail :evidence "no finding was printed"}
         {:id "spec-0014" :verdict :pass :evidence "check exited 1 naming the view"}]
   "02" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]})

(def tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn- cycle-edn [_c] (assoc mc/default-cycle :cut "spec-1"))

(defn goldens
  "The goldens, by path under the oracle directory, as written by hand."
  []
  (let [checklist (fn [c]
                    {"cycle" c "cut" "spec-1"
                     "items" [{"id" "spec-0012" "title" "A view with no description fails the lint"
                               "context" "architecture" "component" "Lint" "level" "e2e"}
                              {"id" "spec-0014" "title" "A view over the limit fails the lint"
                               "context" "architecture" "component" "Lint" "level" "contract"}]
                     "planned" [{"id" "spec-0013" "title" "A component that owns no feature file fails the lint"}]})
        score (fn [c]
                (merge (select-keys (cycle-edn c) [:cut :scope :builder :grader :spec-owner])
                       {:cycle c :room-tree tree :items (get verdicts c) :passed 1 :scored 2}))]
    {"goldens/01/checklist.json" (json/generate-string (checklist "01") {:pretty true})
     "goldens/02/checklist.json" (json/generate-string (checklist "02") {:pretty true})
     "goldens/01/score.edn" (pr-str (score "01"))
     "goldens/02/score.edn" (pr-str (score "02"))
     "goldens/scoreboard-01-02.edn" (pr-str {:cycles ["01" "02"] :compared 2 :better 1 :worse 1
                                             :better-items ["spec-0012"] :worse-items ["spec-0014"]
                                             :scored {"01" 2 "02" 2} :only-in {"01" [] "02" []}
                                             :statements []})}))

(defn oracle!
  "Writes the oracle fixture into `dir`, the goldens changed by `f`."
  ([dir] (oracle! dir identity))
  ([dir f]
   (let [o (fn [rel] (str oracle-dir "/" rel))]
     (s/write! dir (o "project/dhf/spec/model.edn") (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
     (s/write! dir (o (str "project/" mc/lint-feature)) (mc/feature-text scenarios))
     (doseq [c ["01" "02"]]
       (s/write! dir (o (str "cycles/" c "/cycle.edn")) (pr-str (cycle-edn c)))
       (s/write! dir (o (str "cycles/" c "/ratified.edn"))
                 (pr-str {:cut "spec-1" :room-tree tree :outcome :passed :checkout (str "dhf/cycles/" c "/build")}))
       (s/write! dir (o (str "cycles/" c "/verdicts.edn")) (pr-str (get verdicts c))))
     (doseq [[rel text] (f (goldens))] (s/write! dir (o rel) text))
     dir)))

(defn- snapshot [dir]
  (let [d (fs/file dir oracle-dir)]
    (if (fs/directory? d)
      (into {} (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                 [(str (fs/relativize d f)) (slurp (str f))]))
      {})))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- out-edn [ctx] (kc/stdout-edn (run ctx)))

;; ---------------------------------------------------------------------------
;; The instrument is judged against recorded goldens

(defstep #"dhf/harness/oracle/ holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx]
    (needs-oracle-task!)
    (assoc ctx :dir (oracle! (s/project!)))))

(defstep #"it exits 0 and names each golden it compared"
  (fn [ctx]
    (needs-oracle-task!)
    (kc/exits! ctx 0)
    (let [compared (set (get-in (out-edn ctx) [:oracle :compared]))
          want (set (for [g (keys (goldens))] (str oracle-dir "/" g)))]
      (s/check! (= want compared) (str "it names " (pr-str (sort compared)) ", not " (pr-str (sort want)))))))

(defstep #"dhf/harness/oracle/ holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (needs-oracle-task!)
    (let [rel "goldens/02/score.edn"
          dir (oracle! (s/project!)
                       (fn [gs] (update gs rel #(pr-str (assoc-in (edn/read-string %) [:items 1 :verdict] :pass)))))]
      (assoc ctx :dir dir :golden (str oracle-dir "/" rel)))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (needs-oracle-task!)
    (kc/exits! ctx 1)
    (let [lines (s/err-lines (run ctx))
          golden (:golden ctx)]
      (s/check! (some #(and (str/starts-with? % golden) (str/includes? % "spec-0014")
                            (re-find #":pass\b.*:fail\b" %))
                      lines)
                (str "no line names " golden ", spec-0014, :pass and :fail\n" (s/show (run ctx))))
      (s/check! (= 1 (count lines)) (str "only that golden differs\n" (s/show (run ctx)))))))

(defstep #"dhf/harness/oracle/ holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (needs-oracle-task!)
    (let [rel "goldens/scoreboard-01-02.edn"
          dir (oracle! (s/project!)
                       (fn [gs] (update gs rel #(pr-str (assoc (edn/read-string %) :better 0 :better-items [])))))]
      (assoc ctx :dir dir :golden (str oracle-dir "/" rel) :before (snapshot dir)))))

(defstep #"it rewrites that golden, names it, and appends the reason with the date to dhf/harness/oracle/RECORDED\.md"
  (fn [ctx]
    (needs-oracle-task!)
    (kc/exits! ctx 0)
    (let [dir (:dir ctx)
          golden (:golden ctx)
          after (snapshot dir)
          changed (set (for [[k v] after :when (not= v (get (:before ctx) k))] k))
          recorded (get-in (out-edn ctx) [:oracle :recorded])
          notes (get after "RECORDED.md")
          reason (second (re-find #"--reason '([^']+)'" (str/join " " (:args (run ctx)))))]
      (s/check! (= [golden] recorded) (str "it names " (pr-str recorded) "\n" (s/show (run ctx))))
      (s/check! (= #{(subs golden (inc (count oracle-dir))) "RECORDED.md"} changed)
                (str "it changed " (pr-str changed)))
      (s/check! (= 1 (:better (edn/read-string (get after (subs golden (inc (count oracle-dir)))))))
                "the rewritten golden is not what the instrument gives")
      (s/check! (and notes (str/includes? notes (str (java.time.LocalDate/now)))
                     (str/includes? notes "the scoreboard now says the denominator moved"))
                (str "RECORDED.md holds no dated reason: " (pr-str notes) " (reason " reason ")"))
      (let [again (kc/run! ctx dir ["oracle"])]
        (s/check! (zero? (:exit again)) (str "the oracle still fails after recording\n" (s/show again)))))))

(defstep #"it exits 2, says a reason is required, and rewrites nothing"
  (fn [ctx]
    (needs-oracle-task!)
    (kc/exits! ctx 2)
    (s/check! (re-find #"cannot-run: .*a reason is required" (:err (run ctx))) (str "it does not say a reason is required\n" (s/show (run ctx))))
    ;; the run's project is a fresh copy of the room (kernel_command/run-line):
    ;; another fresh copy is what it held before the run
    (s/check! (= (snapshot (s/copy-of-room!)) (snapshot (:dir ctx))) "the oracle directory changed")))

;; ---------------------------------------------------------------------------
;; Nothing is fenced or ratified while the oracle is red

(defstep #"a project whose dhf/harness/oracle/ holds a golden the instrument does not reproduce"
  (fn [ctx]
    (needs-oracle-task!)
    (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})]
      (oracle! dir (fn [gs] (update gs "goldens/01/score.edn" #(pr-str (assoc (edn/read-string %) :passed 2)))))
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      ;; an empty room: fence would build it, ratify would take it
      (assoc ctx :dir dir :room (s/tmp-dir) :cycle "03"))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx what]
    (let [task (first (:args (run ctx)))]
      (needs-gate! task)
      (kc/exits! ctx 1)
      (let [oracle (kc/run! ctx (:dir ctx) ["oracle"])
            findings (s/err-lines oracle)
            lines (set (s/err-lines (run ctx)))]
        (s/check! (and (= 1 (:exit oracle)) (seq findings)) (str "the oracle itself does not fail\n" (s/show oracle)))
        (s/check! (every? lines findings) (str "the oracle's findings are not printed unchanged\n" (s/show (run ctx))))
        (case what
          "builds no room" (s/check! (empty? (fs/list-dir (:room ctx))) "fence built a room")
          (s/check! (not (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn"))))
                    "ratify wrote ratified.edn"))))))

(defstep #"a project with no dhf/harness/oracle/"
  (fn [ctx]
    (needs-oracle-task!)
    (let [dir (s/copy-of-room!)]
      (when (fs/exists? (fs/file dir oracle-dir)) (fs/delete-tree (fs/file dir oracle-dir)))
      (assoc ctx :dir dir))))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (needs-oracle-task!)
    (kc/exits! ctx 0)
    (s/check! (re-find #"no oracle to run" (:out (run ctx))) (str "it does not say so\n" (s/show (run ctx))))))
