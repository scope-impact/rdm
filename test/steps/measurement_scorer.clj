(ns steps.measurement-scorer
  "Step definitions of dhf/spec/measurement/scorer.feature: a cycle's
  cycle.edn, checklist.json and ratified.edn written as rdm-spec writes them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")
(defn id [i] (format "spec-%04d" i))

(defn scorable!
  "Cycle 03: its cycle.edn, a checklist of the ids and a passing verification."
  [ctx ids & {:keys [cycle ratified]}]
  (let [ctx (k/project! ctx)]
    (k/write-files! (:dir ctx)
                    (cond-> {"dhf/cycles/03/cycle.edn" (k/pr-edn (or cycle (merge {:cut "spec-3" :scope :project} k/roles)))
                             "dhf/cycles/03/checklist.json"
                             (json/generate-string {"cycle" "03" "cut" "spec-3"
                                                    "items" (for [i ids] {"id" i "title" (str "About " i) "context" "kernel" "component" "Core" "level" "e2e"})
                                                    "planned" []})}
                      (not= ratified :none)
                      (assoc "dhf/cycles/03/ratified.edn" (k/pr-edn (or ratified {:cut "spec-3" :room-tree tree :outcome :passed
                                                                                 :checkout "dhf/cycles/03/build" :checks []})))))
    (assoc ctx ::ids ids)))

(defn verdicts! [ctx f vs] (k/write-files! (:dir ctx) {f (if (string? vs) vs (k/pr-edn vs))}) ctx)
(defn v [i verdict] {:id i :verdict verdict :evidence (str "graded " i)})

(defn score [ctx] (let [f (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/score.edn")] (when (fs/exists? f) (edn/read-string (slurp f)))))

(defn wrote-nothing [ctx] (assert (nil? (score ctx)) "score.edn was written") ctx)

;; ---------------------------------------------------------------------------
;; A cycle

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [ctx doc]
    (let [ctx (k/verifiable! ctx {:model-spec {:contexts [["verification" ["status"]]] :kernel ["core"]}
                                  :features {"dhf/spec/verification/status.feature"
                                             (k/feature-text {:name "Status" :tags ["UN-001"] :scenarios [(k/sc 2 "Derives")]})}
                                  :extra {"dhf/spec/ids.edn" "{:issued 2}\n"}})]
      (k/write-files! (:dir ctx) {"dhf/cycles/03/cycle.edn" doc})
      ctx)))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec verify ROOM 03\" are run"
  (fn [ctx]
    (let [c (k/rdm ctx ["checklist" "03"])]
      (-> (k/rdm-line c "rdm-spec verify ROOM 03") (assoc ::checklist c)))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (k/check-exit (::checklist ctx) 0)
    (k/check-exit ctx 0)
    (assert (= "spec-3" (get-in (k/edn-out ctx) [:verify :cut])))
    (let [cl (json/parse-string (slurp (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")))]
      (assert (= "spec-3" (get cl "cut")))
      (assert (= ["spec-0002"] (map #(get % "id") (get cl "items"))) (pr-str cl)))
    ctx))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [ctx (k/verifiable! ctx {:model-spec {:contexts [["verification" ["status"]] ["knowledge" ["sources"]] ["spec" ["parser"]]]
                                               :kernel ["core"]}})]
      (k/cycle! ctx "03" (merge {:cut "spec-3" :scope (edn/read-string scope)} k/roles)))))

(defstep #"it accepts the scope"
  (fn [ctx] (k/check-exit ctx 0) (assert (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/checklist.json"))) ctx))

;; ---------------------------------------------------------------------------
;; The score

(defstep #"cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [ctx] (scorable! ctx ["spec-0012" "spec-0014"])))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (verdicts! ctx "verdicts.edn" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
                                   {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}])))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx doc]
    (k/check-exit ctx 0)
    (assert (= (edn/read-string doc) (score ctx)) (k/pr-edn (score ctx)))
    ctx))

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (-> (scorable! ctx ["spec-0012" "spec-0014"]) (verdicts! "verdicts.edn" doc))))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [[:pass "check exited 1 naming the view"] [:fail "no finding was printed"]]
               (map (juxt :verdict :evidence) (:items (score ctx)))))
    ctx))

(defstep #"two verdicts files, one grading spec-0012 and spec-0013, the other spec-0014"
  (fn [ctx]
    (-> (scorable! ctx ["spec-0012" "spec-0013" "spec-0014"])
        (verdicts! "a.edn" [(v "spec-0012" :pass) (v "spec-0013" :fail)])
        (verdicts! "b.edn" [(v "spec-0014" :pass)]))))

(defstep #"all three verdicts are filed in one score.edn"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [s (score ctx)]
      (assert (= [["spec-0012" :pass] ["spec-0013" :fail] ["spec-0014" :pass]] (map (juxt :id :verdict) (:items s))) (pr-str s))
      (assert (= [2 3] [(:passed s) (:scored s)])))
    ctx))

(defstep #"two verdicts files where (both grade spec-0012|b.edn's third line holds a string with an escape EDN refuses)"
  (fn [ctx wrong]
    (let [ctx (scorable! ctx ["spec-0012" "spec-0013"])]
      (if (str/starts-with? wrong "both")
        (-> ctx (verdicts! "a.edn" [(v "spec-0012" :pass)]) (verdicts! "b.edn" [(v "spec-0012" :fail) (v "spec-0013" :pass)])
            (k/names+ "spec-0012" "twice" "a.edn" "b.edn"))
        (-> ctx (verdicts! "a.edn" [(v "spec-0012" :pass)])
            (verdicts! "b.edn" "[{:id \"spec-0013\"\n  :verdict :pass\n  :evidence \"a \\q escape\"}]\n")
            (k/names+ "b.edn:3"))))))

(defstep #"it exits 1, writes nothing, and says (spec-0012 is graded twice, naming both files|b.edn is not readable EDN, naming its line, as FILE:LINE)"
  (fn [ctx _]
    (k/check-exit ctx 1)
    (apply k/err-line-with ctx (:names ctx))
    (wrote-nothing ctx)))

(def malformed
  {"is a map, not a vector" ["{:id \"spec-0012\" :verdict :pass :evidence \"x\"}" "vector"]
   "grades spec-0012 twice" [[(v "spec-0012" :pass) (v "spec-0012" :fail)] "spec-0012"]
   "gives spec-0012 the verdict :maybe" [[{:id "spec-0012" :verdict :maybe :evidence "x"}] ":maybe"]
   "gives spec-0012 no evidence" [[{:id "spec-0012" :verdict :pass}] "evidence"]})

(defstep #"a verdicts file that (is a map, not a vector|grades spec-0012 twice|gives spec-0012 the verdict :maybe|gives spec-0012 no evidence)"
  (fn [ctx what]
    (let [[vs says] (malformed what)]
      (-> (scorable! ctx ["spec-0012"]) (verdicts! "verdicts.edn" vs) (k/names+ "verdicts.edn" says)))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx)) (wrote-nothing ctx)))

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (-> (scorable! ctx ["spec-0012"]) (verdicts! "verdicts.edn" []))))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "grading did not happen") (wrote-nothing ctx)))

(defstep #"cycle 03's checklist holds 12 items"
  (fn [ctx] (scorable! ctx (map id (range 1 13)))))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx] (verdicts! ctx "verdicts.edn" (vec (for [i (range 1 12)] (v (id i) :pass))))))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [11 12] ((juxt :passed :scored) (score ctx))) (pr-str (score ctx)))
    ctx))

(defstep #"score.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (assert (= {:id "spec-0012" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true} (last (:items (score ctx)))))
    ctx))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx] (-> (scorable! ctx ["spec-0012"]) (verdicts! "verdicts.edn" [(v "spec-0012" :pass) (v "spec-0999" :pass)]))))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0999") (wrote-nothing ctx)))

;; ---------------------------------------------------------------------------
;; Roles

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx] (-> (scorable! ctx ["spec-0012"]) (verdicts! "verdicts.edn" [(v "spec-0012" :pass)]))))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= k/roles (select-keys (score ctx) [:builder :grader :spec-owner])))
    ctx))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (-> (scorable! ctx ["spec-0012"] :cycle (merge {:cut "spec-3" :scope :project} k/roles {:grader {:name "builder-a" :actor :agent}}))
        (verdicts! "verdicts.edn" [(v "spec-0012" :pass)]))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "builder-a" "may not grade or read its own score") (wrote-nothing ctx)))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (-> (scorable! ctx ["spec-0012"] :cycle (merge {:cut "spec-3" :scope :project} (dissoc k/roles :spec-owner)))
        (verdicts! "verdicts.edn" [(v "spec-0012" :pass)]))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec owner") (wrote-nothing ctx)))

(defstep #"dhf/cycles/03/ratified.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (-> (scorable! ctx ["spec-0012"] :ratified (if (= state "does not exist") :none
                                                   {:cut "spec-3" :room-tree tree :outcome :refused :reason "the tests failed" :checks []}))
        (verdicts! "verdicts.edn" [(v "spec-0012" :pass)]))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing verification"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "no passing verification") (wrote-nothing ctx)))
