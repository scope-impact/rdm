(ns steps.measurement-scorer
  "Steps of the component Scorer (dhf/spec/07-measure/scorer.feature)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep doc-string]]
            [steps.kernel-shared :as k]))

(def roles {:builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent} :spec-owner {:name "Sami" :actor :human}})

(defn checklist! [ctx ids]
  (k/write! ctx "dhf/cycles/03/checklist.json"
            (json/generate-string {:cycle "03" :cut "spec-3"
                                   :items (for [id ids] {:id id :title (str "Title of " id) :context "demo" :component "Thing" :level "e2e"})
                                   :planned []})))

(defn score-project!
  "A project with cycle 03: its cycle.edn (with M), a checklist of IDS, and a passing verification on TREE."
  [ctx ids & [{:keys [cycle tree ratified]}]]
  (let [ctx (if (:dir ctx) ctx (k/spec-project! ctx))]
    (k/write-edn! ctx "dhf/cycles/03/cycle.edn" (merge {:cut "spec-3"} roles cycle))
    (checklist! ctx ids)
    (when-not (= ratified :none)
      (k/write-edn! ctx "dhf/cycles/03/ratified.edn"
                    (merge {:cut "spec-3" :room-tree (or tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904") :outcome :passed
                            :checkout "dhf/cycles/03/build" :checks []} ratified)))
    ctx))

(defn verdicts! [ctx file vs] (k/write! ctx file (if (string? vs) vs (pr-str vs))))

(defn v [id verdict & [evidence]] {:id id :verdict verdict :evidence (or evidence (str "graded " id))})

(defn score [ctx] (some-> (k/slurp-rel ctx "dhf/cycles/03/score.edn") edn/read-string))

(defn writes-nothing! [ctx] (k/check! (not (fs/exists? (k/path ctx "dhf/cycles/03/score.edn"))) "score.edn was written") ctx)

;; ------------------------------------------------------------------ cycles

(def scope-model
  (k/context-model [[:verification [[:status "Status"]]] [:knowledge [[:sources "Sources"]]] [:spec [[:parser "Parser"]]]]))

(defn scope-project! [ctx]
  (let [features (into {} (map-indexed (fn [i [c n t]] [(str "dhf/spec/" c "/" n ".feature")
                                                       (k/feature-text t ["UN-001"] [{:id (format "spec-%04d" (inc i)) :title (str t " works")}])])
                                       [["verification" "status" "Status"] ["knowledge" "sources" "Sources"] ["spec" "parser" "Parser"]]))]
    (k/verify-project! ctx {:model scope-model :features features})))

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (scope-project! ctx))]
      (k/write! ctx "dhf/cycles/03/cycle.edn" (doc-string)))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec verify ROOM 03\" are run"
  (fn [ctx]
    (let [c (k/run-rdm ctx "checklist 03")
          v (k/run-rdm c "verify ROOM 03")]
      (assoc v ::checklist c))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (k/exits! (::checklist ctx) 0)
    (k/exits! ctx 0)
    (k/check! (= "spec-3" (get-in ctx [:result :verify :cut])) "verify worked on " (get-in ctx [:result :verify :cut]))
    (let [cl (json/parse-string (k/slurp-rel ctx "dhf/cycles/03/checklist.json"))]
      (k/check! (= "spec-3" (get cl "cut")) "the checklist's cut is " (get cl "cut"))
      (k/check! (= ["spec-0001"] (map #(get % "id") (get cl "items"))) "the checklist holds " (pr-str (get cl "items"))))
    ctx))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [ctx (scope-project! ctx)]
      (k/write-edn! ctx "dhf/cycles/03/cycle.edn" (merge {:cut "spec-3" :scope (edn/read-string scope)} roles)))))

(defstep #"it accepts the scope"
  (fn [ctx] (k/exits! ctx 0) (k/check! (fs/exists? (k/path ctx "dhf/cycles/03/checklist.json")) "no checklist was written") ctx))

(defstep #"cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [ctx] (score-project! ctx ["spec-0012" "spec-0014"] {:cycle {:scope :project}})))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass "check exited 1 naming the view") (v "spec-0014" :fail "no finding was printed")])
    ctx))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (edn/read-string (doc-string)) (score ctx)) "score.edn holds " (pr-str (score ctx)))
    ctx))

(def slice-plan "# Slice\n\n## Goal\n\nA goal.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- a\n\n**Out:**\n\n- b\n\n## Decisions\n\nNone.\n")

(defn slices-cycle! [ctx scope]
  (let [ctx (k/spec-project! ctx {:model scope-model
                                  :features {"dhf/spec/12-several-specs/parser.feature" (k/feature-text "Parser" ["UN-001"] [{:id "spec-0001" :title "Parser in the slice"}])
                                             "dhf/spec/02-write-the-spec/parser.feature" (k/feature-text "Parser" ["UN-001"] [{:id "spec-0002" :title "Parser elsewhere"}])
                                             "dhf/spec/02-write-the-spec/status.feature" (k/feature-text "Status" ["UN-001"] [{:id "spec-0003" :title "Status"}])
                                             "dhf/spec/02-write-the-spec/sources.feature" (k/feature-text "Sources" ["UN-001"] [{:id "spec-0004" :title "Sources"}])}})]
    (k/config! ctx assoc :spec-layout :slices)
    (k/write! ctx "dhf/spec/12-several-specs/plan.md" slice-plan)
    (k/write! ctx "dhf/spec/02-write-the-spec/plan.md" slice-plan)
    (k/cut-project! ctx)
    (k/write-edn! ctx "dhf/cycles/03/cycle.edn" (merge {:cut "spec-3" :scope scope} roles))
    ctx))

(defstep #"a configuration with :spec-layout :slices, and dhf/cycles/03/cycle.edn whose :scope is \[\{:slice \"12-several-specs\"\}\]"
  (fn [ctx] (slices-cycle! ctx [{:slice "12-several-specs"}])))

(defstep #"it accepts the scope, and a slice the cut does not hold is refused with exit 2, naming it"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/write-edn! ctx "dhf/cycles/03/cycle.edn" (merge {:cut "spec-3" :scope [{:slice "99-nothing"}]} roles))
    (let [r (k/run-rdm ctx "checklist 03")] (k/exits! r 2) (k/err-has! r "99-nothing"))
    ctx))

;; ------------------------------------------------------------------ verdicts

(defstep #"a verdicts file holding:"
  (fn [ctx]
    (let [ctx (score-project! ctx ["spec-0012" "spec-0014"])]
      (verdicts! ctx "verdicts.edn" (doc-string))
      ctx)))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= [[:pass false] [:fail false]] (map (juxt :verdict #(boolean (:ungraded %))) (:items (score ctx)))) "score.edn holds " (pr-str (score ctx)))
    ctx))

(defstep #"two verdicts files, one grading spec-0012 and spec-0013, the other spec-0014"
  (fn [ctx]
    (let [ctx (score-project! ctx ["spec-0012" "spec-0013" "spec-0014"])]
      (verdicts! ctx "a.edn" [(v "spec-0012" :pass) (v "spec-0013" :fail)])
      (verdicts! ctx "b.edn" [(v "spec-0014" :pass)])
      ctx)))

(defstep #"all three verdicts are filed in one score.edn"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [s (score ctx)]
      (k/check! (= [["spec-0012" :pass] ["spec-0013" :fail] ["spec-0014" :pass]] (map (juxt :id :verdict) (:items s))) "score.edn holds " (pr-str s))
      (k/check! (not-any? :ungraded (:items s)) "an item is ungraded")
      ctx)))

(defstep #"two verdicts files where (both grade spec-0012|b.edn's third line holds a string with an escape EDN refuses)"
  (fn [ctx wrong]
    (let [ctx (score-project! ctx ["spec-0012" "spec-0013" "spec-0014"])]
      (verdicts! ctx "a.edn" [(v "spec-0012" :pass)])
      (if (str/starts-with? wrong "both")
        (verdicts! ctx "b.edn" [(v "spec-0012" :fail)])
        (verdicts! ctx "b.edn" "[{:id \"spec-0013\" :verdict :pass :evidence \"fine\"}\n {:id \"spec-0014\" :verdict :pass\n  :evidence \"a bad \\q escape\"}]\n"))
      ctx)))

(defstep #"it exits 1, writes nothing, and says (spec-0012 is graded twice, naming both files|b.edn is not readable EDN, naming its line, as FILE:LINE)"
  (fn [ctx says]
    (k/exits! ctx 1)
    (writes-nothing! ctx)
    (if (str/starts-with? says "spec-0012")
      (k/err-line-has! ctx "spec-0012" "twice" "a.edn" "b.edn")
      (k/check! (some #(str/starts-with? % "b.edn:3:") (k/err-lines ctx)) "no line names b.edn:3: " (k/shows ctx)))
    ctx))

(defstep #"a verdicts file that (is a map, not a vector|grades spec-0012 twice|gives spec-0012 the verdict :maybe|gives spec-0012 no evidence)"
  (fn [ctx malformed]
    (let [ctx (score-project! ctx ["spec-0012" "spec-0014"])]
      (verdicts! ctx "verdicts.edn"
                 (case malformed
                   "is a map, not a vector" {:id "spec-0012" :verdict :pass :evidence "x"}
                   "grades spec-0012 twice" [(v "spec-0012" :pass) (v "spec-0012" :fail)]
                   "gives spec-0012 the verdict :maybe" [(v "spec-0012" :maybe)]
                   "gives spec-0012 no evidence" [{:id "spec-0012" :verdict :pass}]))
      (assoc ctx ::malformed malformed))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (k/exits! ctx 1)
    (writes-nothing! ctx)
    (k/err-has! ctx (case (::malformed ctx)
                      "is a map, not a vector" "vector"
                      "grades spec-0012 twice" "twice"
                      "gives spec-0012 the verdict :maybe" ":maybe"
                      "gives spec-0012 no evidence" "evidence"))
    ctx))

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (let [ctx (score-project! ctx ["spec-0012"])] (verdicts! ctx "verdicts.edn" []) ctx)))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (k/exits! ctx 2) (writes-nothing! ctx) (k/err-has! ctx "the grading did not happen") ctx))

(def twelve (for [i (range 1 13)] (format "spec-%04d" i)))

(defstep #"cycle 03's checklist holds 12 items" (fn [ctx] (score-project! ctx twelve)))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx] (verdicts! ctx "verdicts.edn" (vec (for [id (take 11 twelve)] (v id :pass)))) ctx))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= [11 12] [(:passed (score ctx)) (:scored (score ctx))]) "the score is " (:passed (score ctx)) " of " (:scored (score ctx)))
    ctx))

(defstep #"score.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (k/check! (= {:id "spec-0012" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true} (last (:items (score ctx))))
              "the item is " (pr-str (last (:items (score ctx)))))
    ctx))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx] (let [ctx (score-project! ctx ["spec-0012"])] (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass) (v "spec-0999" :pass)]) ctx)))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "spec-0999") ctx))

;; ------------------------------------------------------------------ roles

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx] (let [ctx (score-project! ctx ["spec-0012"])] (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass)]) ctx)))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= roles (select-keys (score ctx) [:builder :grader :spec-owner])) "score.edn names " (pr-str (select-keys (score ctx) [:builder :grader :spec-owner])))
    ctx))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (let [ctx (score-project! ctx ["spec-0012"] {:cycle {:grader {:name "builder-a" :actor :agent}}})]
      (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass)]) ctx)))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "the builder may not grade or read its own score") ctx))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (let [ctx (score-project! ctx ["spec-0012"])]
      (k/write-edn! ctx "dhf/cycles/03/cycle.edn" (dissoc (merge {:cut "spec-3"} roles) :spec-owner))
      (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass)]) ctx)))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "spec-owner") ctx))

(defstep #"dhf/cycles/03/ratified.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (score-project! ctx ["spec-0012"] (if (str/starts-with? state "holds") {:ratified {:outcome :refused}} {:ratified :none}))]
      (verdicts! ctx "verdicts.edn" [(v "spec-0012" :pass)]) ctx)))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing verification"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "the cycle has no passing verification") ctx))
