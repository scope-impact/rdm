(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature.

  An oracle fixture (`oracle!`) is dhf/harness/oracle/ in a project the
  scenario makes: two fixture cycles, 01 and 02, each a small project at the
  cut spec-3 (the room's configuration, model, needs and events, and one
  feature file of three scenarios, one planned), its cycle.edn, its verdicts
  and its goldens. The checklist and score goldens are written here by hand
  from what the spec says they hold; each scoreboard golden is what
  `rdm-spec scoreboard` gives on the hand-written scores (the cycle before
  it, the first's being the last), so no golden is recorded by the oracle
  under test."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-oracle! []
  (s/needs-built! 'rdm-spec.measurement.oracle 'rdm-spec.measurement.checklist
                  'rdm-spec.measurement.scorer 'rdm-spec.measurement.scoreboard))

(def oracle-dir "dhf/harness/oracle")

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def verdicts
  {"01" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]
   "02" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :pass :evidence "the view over the limit was named"}]})

(defn- checklist-golden [cycle]
  (json/generate-string
   (array-map "cycle" cycle "cut" "spec-3"
              "items" [(array-map "id" "spec-0012" "title" "A view with no description fails the lint"
                                  "context" "architecture" "component" "Lint" "level" "e2e")
                       (array-map "id" "spec-0014" "title" "A view over the limit fails the lint"
                                  "context" "architecture" "component" "Lint" "level" "contract")]
              "planned" [(array-map "id" "spec-0013" "title" "A component that owns no feature file fails the lint")])
   {:pretty true}))

(defn- score-golden [cycle]
  (let [items (get verdicts cycle)]
    {:cycle cycle :cut "spec-3" :scope :project :room-tree empty-tree
     :builder (:builder mc/default-cycle) :grader (:grader mc/default-cycle) :spec-owner (:spec-owner mc/default-cycle)
     :items items
     :passed (count (filter #(= :pass (:verdict %)) items))
     :scored (count items)}))

(defn- fixture-project!
  "The fixture project's files at the cut, under `dir`."
  [dir]
  (doseq [rel ["dhf/harness/config.edn" "dhf/spec/model.edn" "dhf/spec/needs.edn" "dhf/spec/events.edn"]
          :let [src (fs/file s/room-root rel)]
          :when (fs/exists? src)]
    (s/write! dir rel (slurp (str src))))
  (s/write! dir mc/lint-feature (mc/feature-text mc/default-scenarios)))

(defn- scoreboard-goldens
  "{cycle text}: `rdm-spec scoreboard PREV CYCLE` on the hand-written scores."
  [ctx]
  (let [dir (s/project!)]
    (doseq [c ["01" "02"]]
      (s/write! dir (mc/cycle-path c "cycle.edn") (pr-str mc/default-cycle))
      (s/write! dir (mc/cycle-path c "score.edn") (pr-str (score-golden c))))
    (into {} (for [[prev c] [["02" "01"] ["01" "02"]]]
               (let [r (kc/run! ctx dir ["scoreboard" prev c])]
                 (s/check! (zero? (:exit r)) (str "rdm-spec scoreboard failed on the fixture's scores\n" (s/show r)))
                 [c (pr-str (:scoreboard (kc/stdout-edn r)))])))))

(defn oracle!
  "Writes the oracle fixture into `dir`'s dhf/harness/oracle/."
  [ctx dir]
  (let [boards (scoreboard-goldens ctx)]
    (doseq [c ["01" "02"]
            :let [base (str oracle-dir "/" c "/")]]
      (fixture-project! (fs/file dir base "project"))
      (s/write! dir (str base "cycle.edn") (pr-str mc/default-cycle))
      (s/write! dir (str base "verdicts.edn") (pr-str (get verdicts c)))
      (s/write! dir (str base "checklist.json") (checklist-golden c))
      (s/write! dir (str base "score.edn") (pr-str (score-golden c)))
      (s/write! dir (str base "scoreboard.edn") (get boards c)))
    dir))

(def corrupted "dhf/harness/oracle/01/score.edn")

(defn- corrupt!
  "Makes cycle 01's score golden say pass for its second item, which the
  scorer files as a fail."
  [dir]
  (let [g (score-golden "01")]
    (s/write! dir corrupted (pr-str (-> g (assoc-in [:items 1 :verdict] :pass) (assoc :passed 2))))))

(defn- snapshot [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir oracle-dir) "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"dhf/harness/oracle/ holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (oracle! ctx (s/project!))]
      (assoc ctx :dir dir :before (snapshot dir)))))

(defstep #"dhf/harness/oracle/ holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (oracle! ctx (s/project!))]
      (corrupt! dir)
      (assoc ctx :dir dir :before (snapshot dir)))))

(defstep #"dhf/harness/oracle/ holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (oracle! ctx (s/project!))]
      (corrupt! dir)
      (assoc ctx :dir dir :before (snapshot dir)))))

(defstep #"a project whose dhf/harness/oracle/ holds a golden the instrument does not reproduce"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})]
      (oracle! ctx dir)
      (corrupt! dir)
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (assoc ctx :dir dir :cycle "03"))))

(defstep #"a project with no dhf/harness/oracle/"
  (fn [ctx]
    (s/needs-built! 'rdm-spec.measurement.oracle)
    (assoc ctx :dir (s/project!))))

;; ---------------------------------------------------------------------------
;; Thens

(def ^:private all-goldens
  (vec (for [c ["01" "02"] g ["checklist.json" "score.edn" "scoreboard.edn"]] (str oracle-dir "/" c "/" g))))

(defstep #"it exits 0 and names each golden it compared"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          compared (get-in (kc/stdout-edn r) [:oracle :compared])]
      (s/check! (= (sort all-goldens) (sort compared))
                (str "it names " (pr-str compared) " as compared, expected " (pr-str all-goldens) "\n" (s/show r)))
      nil)))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          hits (filter #(and (str/starts-with? % (str corrupted ":"))
                             (str/includes? % "spec-0014")
                             (str/includes? % ":pass") (str/includes? % ":fail"))
                       (s/err-lines r))]
      (s/check! (seq hits) (str "no finding names " corrupted ", spec-0014, :pass and :fail\n" (s/show r)))
      nil)))

(defstep #"it rewrites that golden, names it, and appends the reason with the date to dhf/harness/oracle/RECORDED\.md"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          dir (:dir ctx)
          reason (second (drop-while #(not= "--reason" %) (:args r)))
          after (snapshot dir)
          changed (set (for [[k v] after :when (not= v (get (:before ctx) k))] k))
          recorded (str oracle-dir "/RECORDED.md")
          golden (edn/read-string (get after corrupted))]
      (s/check! (= #{corrupted recorded} changed)
                (str "the files changed are " (pr-str (sort changed)) ", expected " corrupted " and " recorded))
      (s/check! (= :fail (get-in golden [:items 1 :verdict])) (str "the golden was not rewritten: " (pr-str golden)))
      (s/check! (some #(= corrupted %) (get-in (kc/stdout-edn r) [:oracle :recorded]))
                (str "the result does not name " corrupted "\n" (s/show r)))
      (let [text (get after recorded)
            today (str (java.time.LocalDate/now))]
        (s/check! (some #(and (str/includes? % reason) (str/includes? % today)) (str/split-lines text))
                  (str "RECORDED.md holds no line with the reason and " today ":\n" text)))
      (let [again (kc/run! ctx dir ["oracle"])]
        (s/check! (zero? (:exit again)) (str "the oracle still fails after recording\n" (s/show again))))
      nil)))

(defstep #"it exits 2, says a reason is required, and rewrites nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (some #(and (str/starts-with? % "cannot-run: ") (str/includes? % "reason is required")) (s/err-lines r))
                (str "it does not say a reason is required\n" (s/show r)))
      (s/check! (= (:before ctx) (snapshot (:dir ctx))) "a file under dhf/harness/oracle/ changed")
      nil)))

(defn- finding-lines [r]
  (remove #(or (str/starts-with? % "cannot-run: ") (str/starts-with? % "event: ") (str/includes? % "warning: "))
          (s/err-lines r)))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx does-nothing]
    (let [r (kc/exits! ctx 1)
          oracle (kc/run! ctx (:dir ctx) ["oracle"])
          want (finding-lines oracle)]
      (s/check! (= 1 (:exit oracle)) (str "the oracle alone does not fail\n" (s/show oracle)))
      (s/check! (seq want) "the oracle names no finding")
      (s/check! (= want (finding-lines r))
                (str "the findings differ from the oracle's\noracle:\n" (str/join "\n" want) "\n" (s/show r)))
      (case does-nothing
        "builds no room" (s/check! (not (fs/exists? (:room ctx))) (str "a room was built at " (:room ctx)))
        "writes no ratified.edn" (s/check! (not (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn"))))
                                           "a ratified.edn was written"))
      nil)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (re-find #"no oracle to run" (str (:out r) (:err r))) (str "it does not say there is no oracle to run\n" (s/show r)))
      nil)))
