(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature.

  Declaring a cycle (its scope, the checklist and ratify on it) uses cycle
  projects (steps.measurement-cycle). Scoring needs no repository: a scoring
  fixture is a project holding the room's configuration and cycle 03, built
  from ctx :scoring, which the Givens change before `scoring!` writes it:
    :cycle     cycle.edn (steps.measurement-cycle/default-cycle)
    :items     the checklist's item ids (spec-0012, spec-0014)
    :ratified  ratified.edn, or nil for none (passed on the empty tree)
    :verdicts  the verdicts file's text (each item passed)"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-checklist :as mcl]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-scorer! [] (s/needs-built! 'rdm-spec.measurement.scorer))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def evidence
  {"spec-0012" "check exited 1 naming the view"
   "spec-0014" "no finding was printed"})

(defn verdicts-text [vs] (pr-str (vec vs)))

(defn- passing [ids]
  (verdicts-text (for [id ids] {:id id :verdict :pass :evidence (get evidence id (str "the build did what " id " says"))})))

(def default-scoring
  {:cycle mc/default-cycle
   :items ["spec-0012" "spec-0014"]
   :ratified {:cut "spec-3" :room-tree empty-tree :outcome :passed :checkout "dhf/cycles/03/build"}})

(defn- scoring [ctx] (merge default-scoring {:verdicts (passing (:items (merge default-scoring (:scoring ctx))))}
                            (:scoring ctx)))

(defn scoring!
  "ctx with its scoring fixture (re)written from :scoring changed by `f`."
  ([ctx] (scoring! ctx identity))
  ([ctx f]
   (needs-scorer!)
   (let [sc (f (scoring ctx))
         dir (or (:dir ctx) (s/project!))
         cdir (fs/file dir "dhf/cycles/03")]
     (when (fs/exists? cdir) (fs/delete-tree cdir))
     (s/write! dir (mc/cycle-path "03" "cycle.edn") (pr-str (:cycle sc)))
     (s/write! dir (mc/cycle-path "03" "checklist.json")
               (json/generate-string {:cycle "03" :cut (get-in sc [:cycle :cut]) :planned []
                                      :items (for [id (:items sc)]
                                               {:id id :title (str "The scenario " id) :context "architecture"
                                                :component "Lint" :level "e2e"})}
                                     {:pretty true}))
     (when-let [r (:ratified sc)] (s/write! dir (mc/cycle-path "03" "ratified.edn") (pr-str r)))
     (s/write! dir "verdicts.edn" (:verdicts sc))
     (assoc ctx :dir dir :scoring sc :cycle "03"))))

(defn- score-file [ctx] (fs/file (:dir ctx) (mc/cycle-path "03" "score.edn")))

(defn read-score [ctx]
  (let [f (score-file ctx)]
    (s/check! (fs/exists? f) (str "no " (mc/cycle-path "03" "score.edn") "\n" (s/show (run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- refused! [ctx code re what]
  (let [r (run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    (s/check! (re-find re (:err r)) (str "the error stream does not say " what "\n" (s/show r)))
    (s/check! (not (fs/exists? (score-file ctx))) "a score.edn was written")))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(def scoped-files
  "A spec over four contexts, for the scope scenarios."
  {mc/lint-feature [{:id "spec-0301" :title "A lint scenario"}]
   "dhf/spec/verification/status.feature" [{:id "spec-0302" :title "A status scenario"}
                                           {:id "spec-0303" :title "A planned status scenario" :wip? true}]
   "dhf/spec/verification/trace.feature" [{:id "spec-0304" :title "A trace scenario" :level "contract"}]
   "dhf/spec/spec/parser.feature" [{:id "spec-0305" :title "A parser scenario"}]
   "dhf/spec/knowledge/wiki.feature" [{:id "spec-0306" :title "A wiki scenario"}]})

(def owners
  {"spec-0301" [:architecture :architecture/lint] "spec-0302" [:verification :verification/status]
   "spec-0303" [:verification :verification/status] "spec-0304" [:verification :verification/trace]
   "spec-0305" [:spec :spec/parser] "spec-0306" [:knowledge :knowledge/wiki]})

(defn- in-scope [scope]
  (let [parts (cond (= :project scope) nil (map? scope) [scope] :else scope)]
    (sort (for [[id [c k]] owners
                :when (or (= :project scope)
                          (some #(or (= c (:context %)) (= k (:component %))) parts))]
            id))))

(defn- scoped-project! [ctx cycle-text]
  (s/needs-built! 'rdm-spec.measurement.checklist)
  (let [dir (mc/project! scoped-files)
        cycle (edn/read-string cycle-text)]
    (mc/cut! dir (:cut cycle))
    (s/write! dir (mc/cycle-path "03" "cycle.edn") cycle-text)
    (assoc ctx :dir dir :room (mc/room!) :cycle "03" :declared cycle)))

(defstep #"dhf/cycles/03/cycle\.edn holds:"
  (fn [ctx doc] (scoped-project! ctx doc)))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec ratify ROOM 03\" are run"
  (fn [ctx]
    (let [ctx (kc/run-line ctx "checklist 03")
          checklist-run (:run ctx)
          ctx (kc/run-line ctx "ratify ROOM 03")]
      (assoc ctx :runs {:checklist checklist-run :ratify (:run ctx)}))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [{:keys [checklist ratify]} (:runs ctx)
          _ (s/check! (zero? (:exit checklist)) (str "the checklist failed\n" (s/show checklist)))
          {:keys [cut items planned]} (mcl/read-checklist (assoc ctx :run checklist))
          rf (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn"))]
      (s/check! (= "spec-3" cut) (str "the checklist is of the cut " cut))
      (s/check! (= (in-scope (:scope (:declared ctx))) (sort (map :id (concat items planned))))
                (str "the checklist holds " (pr-str (map :id (concat items planned))) ", not the scope's"))
      (s/check! (fs/exists? rf) (str "ratify wrote no ratified.edn\n" (s/show ratify)))
      (s/check! (= "spec-3" (:cut (edn/read-string (slurp (str rf))))) "ratify did not work on spec-3"))))

(defstep #"dhf/cycles/03/cycle\.edn holds the scope (.+)"
  (fn [ctx scope]
    (scoped-project! ctx (pr-str (assoc mc/default-cycle :scope (edn/read-string scope))))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (let [{:keys [items planned]} (mcl/read-checklist ctx)]
        (s/check! (= (in-scope (:scope (:declared ctx))) (sort (map :id (concat items planned))))
                  (str "the checklist holds " (pr-str (map :id (concat items planned)))))))))

(defstep #"cycle 03's checklist holds 2 items and its ratification passed on the room tree ([0-9a-f]{40})"
  (fn [ctx tree]
    (scoring! ctx #(-> % (assoc :items ["spec-0012" "spec-0014"]) (assoc-in [:ratified :room-tree] tree)))))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (scoring! ctx #(assoc % :verdicts (verdicts-text [{:id "spec-0012" :verdict :pass :evidence (evidence "spec-0012")}
                                                      {:id "spec-0014" :verdict :fail :evidence (evidence "spec-0014")}])))))

(defstep #"dhf/cycles/03/score\.edn holds:"
  (fn [ctx doc]
    (let [r (run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (let [got (read-score ctx) want (edn/read-string doc)]
        (s/check! (= want got) (str "score.edn holds\n" (pr-str got) "\nexpected\n" (pr-str want)))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (scoring! ctx #(assoc % :verdicts doc))))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (let [vs (edn/read-string (get-in ctx [:scoring :verdicts]))
            items (:items (read-score ctx))]
        (s/check! (= vs (map #(select-keys % [:id :verdict :evidence]) items))
                  (str "score.edn's items are " (pr-str items)))))))

(def malformed
  {"is a map, not a vector" [(pr-str {:id "spec-0012" :verdict :pass :evidence "it passed"}) #"not a map" "that it is a map"]
   "grades spec-0012 twice" [(verdicts-text [{:id "spec-0012" :verdict :pass :evidence "it passed"}
                                             {:id "spec-0012" :verdict :fail :evidence "it failed"}])
                             #"spec-0012 is graded 2 times" "that spec-0012 is graded twice"]
   "gives spec-0012 the verdict :maybe" [(verdicts-text [{:id "spec-0012" :verdict :maybe :evidence "unsure"}])
                                         #"spec-0012 has the verdict :maybe" "the verdict :maybe"]
   "gives spec-0012 no evidence" [(verdicts-text [{:id "spec-0012" :verdict :pass}])
                                  #"spec-0012 gives no evidence" "that spec-0012 gives no evidence"]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx how]
    (let [[text re what] (or (get malformed how) (s/fail! (str "no fixture for a verdicts file that " how)))]
      (assoc (scoring! ctx #(assoc % :verdicts text)) :says [re what]))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (let [[re what] (:says ctx)]
      (refused! ctx 1 (re-pattern (str "(?m)^verdicts\\.edn:\\d+: verdicts-malformed: .*" re)) what))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (scoring! ctx #(assoc % :verdicts "[]"))))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx]
    (refused! ctx 2 #"cannot-run: .*the grading did not happen" "that the grading did not happen")
    (s/check! (str/blank? (:out (run ctx))) "a refusal printed a result")))

(defstep #"cycle 03's checklist holds (\d+) items"
  (fn [ctx n]
    (scoring! ctx #(assoc % :items (mapv :id (mcl/numbered 12 (parse-long n)))))))

(defstep #"a verdicts file grading (\d+) of them, all passed"
  (fn [ctx n]
    (scoring! ctx #(assoc % :verdicts (passing (take (parse-long n) (:items %)))))))

(defstep #"the score is (\d+) of (\d+), and the ungraded item is recorded as failed"
  (fn [ctx passed scored]
    (let [r (run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (let [{:keys [items] :as sc} (read-score ctx)
            graded (set (map :id (edn/read-string (get-in ctx [:scoring :verdicts]))))
            ungraded (remove #(graded (:id %)) items)]
        (s/check! (= [(parse-long passed) (parse-long scored)] [(:passed sc) (:scored sc)])
                  (str "the score is " (:passed sc) " of " (:scored sc)))
        (s/check! (and (= 1 (count ungraded)) (= :fail (:verdict (first ungraded))))
                  (str "the ungraded items are recorded as " (pr-str ungraded)))))))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx]
    (scoring! ctx #(assoc % :verdicts (verdicts-text [{:id "spec-0012" :verdict :pass :evidence "it passed"}
                                                      {:id "spec-0999" :verdict :pass :evidence "it passed"}])))))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (refused! ctx 1 #"(?m)^verdicts\.edn:\d+: \S+: spec-0999 is not on the cycle's checklist" "spec-0999")))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx]
    (scoring! ctx #(assoc % :cycle (merge mc/default-cycle {:builder {:name "builder-a" :actor :agent}
                                                            :grader {:name "grader-b" :actor :agent}
                                                            :spec-owner {:name "Sami" :actor :human}})))))

(defstep #"score\.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (let [sc (read-score ctx)
            want {:builder {:name "builder-a" :actor :agent}
                  :grader {:name "grader-b" :actor :agent}
                  :spec-owner {:name "Sami" :actor :human}}]
        (s/check! (= want (select-keys sc (keys want))) (str "score.edn names " (pr-str (select-keys sc (keys want)))))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (scoring! ctx #(assoc-in % [:cycle :grader] {:name "builder-a" :actor :agent}))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (refused! ctx 1 #"the builder may not grade or read its own score" "that the builder may not grade")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (scoring! ctx #(update % :cycle dissoc :spec-owner))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (refused! ctx 1 #"names no spec owner \(:spec-owner\)" "the missing spec owner")))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused"
  (fn [ctx]
    (scoring! ctx #(update % :ratified assoc :outcome :refused :reason "the test command failed"))))

(defstep #"dhf/cycles/03/ratified\.edn does not exist"
  (fn [ctx] (scoring! ctx #(assoc % :ratified nil))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx] (refused! ctx 1 #"the cycle has no passing ratification" "that the cycle has no passing ratification")))
