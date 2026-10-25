(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature.

  A scorable cycle is a fixture project (steps.measurement-cycle) whose cycle
  03 has a checklist derived by `rdm-spec checklist 03` and a ratified.edn
  recording a passing ratification (written here: ratification is another
  context's, and the scorer meets it only through that file). The verdicts
  file is verdicts.edn in the project, where `rdm-spec score 03 --verdicts
  verdicts.edn` reads it; every Given that sets up a cycle also writes a
  valid one, which a later Given may replace."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def evidence
  {"spec-0012" "check exited 1 naming the view"
   "spec-0014" "no finding was printed"})

(defn write-verdicts!
  "Writes `text` (or the EDN of a value) as the project's verdicts.edn."
  [ctx v]
  (s/write! (:dir ctx) "verdicts.edn" (if (string? v) v (pr-str v)))
  ctx)

(defn ratified!
  "Writes cycle 03's ratified.edn: a ratification of the cut spec-3 with
  `outcome` on the room tree `room-tree`."
  [ctx outcome room-tree]
  (s/write! (:dir ctx) (mc/cycle-path "03" "ratified.edn")
            (str "{:cut \"spec-3\"\n :room-tree " (pr-str room-tree) "\n :outcome " outcome
                 "\n :checkout \"dhf/cycles/03/build\""
                 (when (not= :passed outcome) "\n :reason \"the test command bb test.clj failed with exit status 1\"")
                 "}\n"))
  ctx)

(defn scorable!
  "ctx with a scorable cycle 03 over the scenarios `scs` (default: spec-0012
  and spec-0014 to grade, spec-0013 planned), declared with `cycle-opts`,
  and a verdicts file passing every item."
  ([ctx] (scorable! ctx mc/default-scenarios {}))
  ([ctx scs cycle-opts]
   (let [ctx (mc/cycle-project! ctx {mc/lint-feature (mc/feature-text scs)} cycle-opts)
         r (s/rdm-spec! (:dir ctx) "checklist" "03")]
     (s/check! (zero? (:exit r)) (str "the checklist could not be derived\n" (s/show r)))
     (let [items (mapv :id (:items (mc/read-checklist ctx "03")))]
       (-> ctx
           (assoc :items items)
           (ratified! :passed tree)
           (write-verdicts! (vec (for [id items] {:id id :verdict :pass :evidence (get evidence id "it passed")}))))))))

(defn- ensure-cycle [ctx] (if (:items ctx) ctx (scorable! ctx)))

(defn read-score [ctx]
  (let [r (:run ctx)
        sc (mc/read-cycle-edn ctx "03" "score.edn")]
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    (s/check! sc (str "no dhf/cycles/03/score.edn\n" (s/show r)))
    sc))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [ctx doc]
    (let [lint (mc/feature-text mc/default-scenarios)
          status (mc/feature-text "Status" "UN-003" [{:id "spec-0040" :title "A status of the scope"}])]
      (assoc (mc/cycle-project! ctx {mc/lint-feature lint mc/status-feature status} doc)
             :scope-items ["spec-0040"]))))

(defstep #"\"rdm-spec (checklist 03)\" and \"rdm-spec (ratify ROOM 03)\" are run"
  (fn [ctx checklist ratify]
    (let [c (kc/run-line ctx checklist)
          r (kc/run-line c ratify)]
      (assoc r :checklist-run (:run c) :ratify-run (:run r)))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [c (:checklist-run ctx)
          r (:ratify-run ctx)]
      (s/check! (zero? (:exit c)) (str "the checklist failed\n" (s/show c)))
      (s/check! (#{0 1} (:exit r)) (str "ratify did not run\n" (s/show r)))
      (let [{:keys [cut items]} (mc/read-checklist ctx "03")
            ratified (mc/read-cycle-edn ctx "03" "ratified.edn")]
        (s/check! (= "spec-3" cut) (str "the checklist names the cut " (pr-str cut)))
        (s/check! (= "spec-3" (:cut ratified)) (str "ratified.edn names the cut " (pr-str (:cut ratified)) "\n" (s/show r)))
        (doseq [id (:scope-items ctx)]
          (s/check! (some #(= id (:id %)) items) (str "the checklist holds no item " id " of the scope")))))))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [ctx scope]
    (mc/cycle-project! ctx {mc/lint-feature (mc/feature-text mc/default-scenarios)}
                       {:scope (edn/read-string scope)})))

(defstep #"it accepts the scope"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (mc/read-checklist ctx "03"))))

(defstep #"cycle 03's checklist holds 2 items and its ratification passed on the room tree ([0-9a-f]{40})"
  (fn [ctx room-tree]
    (let [ctx (scorable! ctx)]
      (s/check! (= ["spec-0012" "spec-0014"] (:items ctx)) (str "the checklist holds " (:items ctx)))
      (ratified! ctx :passed room-tree))))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (write-verdicts! (ensure-cycle ctx)
                     [{:id "spec-0012" :verdict :pass :evidence (evidence "spec-0012")}
                      {:id "spec-0014" :verdict :fail :evidence (evidence "spec-0014")}])))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx doc]
    (let [want (edn/read-string doc)
          got (read-score ctx)]
      (s/check! (= want got) (str "score.edn holds " (pr-str got) "\nexpected " (pr-str want))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc]
    (write-verdicts! (ensure-cycle ctx) doc)))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (let [want (edn/read-string (slurp (str (fs/file (:dir ctx) "verdicts.edn"))))
          got (read-score ctx)]
      (s/check! (= want (:items got)) (str "score.edn files " (pr-str (:items got)))))))

(def malformed
  "Each malformed verdicts file of the outline: its text, and what the error
  stream must say."
  {"is a map, not a vector"
   ["{:id \"spec-0012\" :verdict :pass :evidence \"it passed\"}" [#"vector"]]
   "grades spec-0012 twice"
   ["[{:id \"spec-0012\" :verdict :pass :evidence \"it passed\"}\n {:id \"spec-0014\" :verdict :pass :evidence \"it passed\"}\n {:id \"spec-0012\" :verdict :fail :evidence \"it failed\"}]"
    [#"spec-0012" #"twice"]]
   "gives spec-0012 the verdict :maybe"
   ["[{:id \"spec-0012\" :verdict :maybe :evidence \"unsure\"}]" [#"spec-0012" #":maybe"]]
   "gives spec-0012 no evidence"
   ["[{:id \"spec-0012\" :verdict :pass}]" [#"spec-0012" #"evidence"]]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx what]
    (let [[text res] (or (get malformed (str/trim what)) (s/fail! (str "no fixture for a verdicts file that " what)))]
      (assoc (write-verdicts! (ensure-cycle ctx) text) :malformed res))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (apply mc/refused! ctx 1 "score.edn" #"verdicts\.edn" (:malformed ctx))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (write-verdicts! (ensure-cycle ctx) "[]\n")))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (mc/refused! ctx 2 "score.edn" #"grading did not happen")))

(defstep #"cycle 03's checklist holds (\d+) items"
  (fn [ctx n]
    (let [n (parse-long n)
          scs (vec (for [i (range n)] {:id (format "spec-%04d" (+ 101 i)) :title (str "Item " (inc i))}))
          ctx (scorable! ctx scs {})]
      (s/check! (= n (count (:items ctx))) (str "the checklist holds " (:items ctx)))
      ctx)))

(defstep #"a verdicts file grading (\d+) of them, all passed"
  (fn [ctx n]
    (let [graded (vec (take (parse-long n) (:items ctx)))]
      (s/check! (< (count graded) (count (:items ctx))) "every item is graded")
      (-> ctx
          (write-verdicts! (mapv #(hash-map :id % :verdict :pass :evidence "it passed") graded))
          (assoc :ungraded (vec (drop (count graded) (:items ctx))))))))

(defstep #"the score is (\d+) of (\d+), and the ungraded item is recorded as failed"
  (fn [ctx passed scored]
    (let [sc (read-score ctx)]
      (s/check! (= [(parse-long passed) (parse-long scored)] [(:passed sc) (:scored sc)])
                (str "the score is " (:passed sc) " of " (:scored sc)))
      (doseq [id (:ungraded ctx)
              :let [item (first (filter #(= id (:id %)) (:items sc)))]]
        (s/check! (= :fail (:verdict item)) (str "the ungraded " id " is recorded as " (pr-str item)))))))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx]
    (let [ctx (ensure-cycle ctx)]
      (s/check! (not (some #{"spec-0999"} (:items ctx))) "spec-0999 is on the checklist")
      (write-verdicts! ctx [{:id "spec-0012" :verdict :pass :evidence "it passed"}
                            {:id "spec-0999" :verdict :pass :evidence "it passed"}]))))

(defstep #"it exits 1, writes nothing, and names (spec-\d{4})"
  (fn [ctx id] (mc/refused! ctx 1 "score.edn" (re-pattern id))))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx]
    (scorable! ctx mc/default-scenarios {:builder {:name "builder-a" :actor :agent}
                                         :grader {:name "grader-b" :actor :agent}
                                         :spec-owner {:name "Sami" :actor :human}})))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (let [sc (read-score ctx)]
      (doseq [[role who] [[:builder {:name "builder-a" :actor :agent}]
                          [:grader {:name "grader-b" :actor :agent}]
                          [:spec-owner {:name "Sami" :actor :human}]]]
        (s/check! (= who (get sc role)) (str "score.edn names the " (name role) " " (pr-str (get sc role))))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (scorable! ctx mc/default-scenarios {:grader {:name "builder-a" :actor :agent}})))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (mc/refused! ctx 1 "score.edn" #"builder-a" #"may not grade or read its own score")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (scorable! ctx mc/default-scenarios {:spec-owner nil})))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (mc/refused! ctx 1 "score.edn" #"spec owner|:spec-owner")))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (ensure-cycle ctx)]
      (if (= "does not exist" state)
        (do (fs/delete (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn"))) ctx)
        (ratified! ctx :refused tree)))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx] (mc/refused! ctx 1 "score.edn" #"no passing ratification")))
