(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature.

  A scoring fixture is a project holding only the configuration and cycle 03:
  its cycle.edn (steps.measurement-cycle's default, changed by :cycle-overrides),
  its checklist.json (the items :items), its ratified.edn (:ratified, or none
  when it is :none) and the verdicts file verdicts.edn (:verdicts, written as
  EDN, or :verdicts-text as is). Each Given changes the context and rebuilds
  the fixture (`fixture!`), so Givens compose. The scope scenarios cut a copy
  of the room's allow-list holding one small feature file per component."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-scorer! [] (s/needs-built! 'rdm-spec.measurement.scorer))

(def score-path (mc/cycle-path "03" "score.edn"))

(def evidence
  {"spec-0012" "check exited 1 naming the view"
   "spec-0014" "no finding was printed"})

(defn item-ids [n] (mapv #(format "spec-%04d" (+ 601 %)) (range n)))

(def defaults
  {:items ["spec-0012" "spec-0014"]
   :ratified {:cut "spec-3" :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904" :outcome :passed
              :checkout "dhf/cycles/03/build"}
   :verdicts [{:id "spec-0012" :verdict :pass :evidence (evidence "spec-0012")}
              {:id "spec-0014" :verdict :fail :evidence (evidence "spec-0014")}]
   :cycle-overrides {}})

(defn- fixture!
  [ctx]
  (needs-scorer!)
  (let [ctx (merge defaults ctx)
        dir (or (:dir ctx) (s/project!))]
    (mc/write-cycle! dir "03" (:cycle-overrides ctx))
    (s/write! dir (mc/cycle-path "03" "checklist.json")
              (json/generate-string
               (array-map "cycle" "03" "cut" "spec-3"
                          "items" (vec (for [id (:items ctx)]
                                         (array-map "id" id "title" (str "The scenario " id) "context" "architecture"
                                                    "component" "Lint" "level" "e2e")))
                          "planned" [])
               {:pretty true}))
    (let [rf (fs/file dir (mc/cycle-path "03" "ratified.edn"))]
      (if (= :none (:ratified ctx))
        (fs/delete-if-exists rf)
        (s/write! dir (mc/cycle-path "03" "ratified.edn") (pr-str (:ratified ctx)))))
    (s/write! dir "verdicts.edn" (or (:verdicts-text ctx) (pr-str (:verdicts ctx))))
    (fs/delete-if-exists (fs/file dir score-path))
    (assoc ctx :dir dir)))

(defn- score [ctx]
  (mc/exits! ctx 0)
  (mc/read-edn-file ctx score-path))

(defn- refused!
  "The run exited `code`, wrote no score.edn and says `re`."
  [ctx code re what]
  (mc/exits! ctx code)
  (mc/wrote-nothing! ctx score-path)
  (mc/says! ctx re what))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(def scope-files
  "One small feature file per component, so each scope selects its own items."
  {"dhf/spec/architecture/lint.feature" ["Lint" ["spec-0012"]]
   "dhf/spec/verification/status.feature" ["Status" ["spec-0501" "spec-0502"]]
   "dhf/spec/knowledge/wiki.feature" ["Wiki" ["spec-0511"]]
   "dhf/spec/spec/parser.feature" ["Parser" ["spec-0521" "spec-0522"]]
   "dhf/spec/spec/check.feature" ["Spec check" ["spec-0531"]]})

(defn- ids-of [& files] (vec (sort (mapcat #(second (scope-files %)) files))))

(def scope-items
  {:project (vec (sort (mapcat second (vals scope-files))))
   {:context :verification} (ids-of "dhf/spec/verification/status.feature")
   {:component :spec/parser} (ids-of "dhf/spec/spec/parser.feature")
   [{:context :knowledge} {:component :spec/parser}] (ids-of "dhf/spec/knowledge/wiki.feature"
                                                             "dhf/spec/spec/parser.feature")})

(defn- scope-project! [ctx]
  (mc/cycle-project! ctx (into {} (for [[f [feature ids]] scope-files]
                                    [f (mc/feature-text feature (for [id ids] {:id id :title (str "Scenario " id)}))]))))

(defn- checklist-ids [ctx]
  (let [f (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json"))]
    (s/check! (fs/exists? f) (str "no checklist.json\n" (s/show (mc/the-run ctx))))
    (mapv :id (:items (json/parse-string (slurp (str f)) true)))))

(defstep #"dhf/cycles/03/cycle\.edn holds:"
  (fn [ctx doc]
    (s/needs-built! 'rdm-spec.measurement.checklist 'rdm-spec.ratification.ratify)
    (let [ctx (scope-project! ctx)]
      (mc/write-cycle-text! (:dir ctx) "03" doc)
      (assoc ctx :scope (:scope (edn/read-string doc))))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec ratify ROOM 03\" are run"
  (fn [ctx]
    (let [c (kc/run-line ctx "checklist 03")
          r (kc/run-line c "ratify ROOM 03")]
      (assoc r :runs {:checklist (:run c) :ratify (:run r)}))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [{:keys [checklist ratify]} (:runs ctx)
          ratified (mc/read-edn-file ctx (mc/cycle-path "03" "ratified.edn"))
          c (json/parse-string (slurp (str (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))) true)]
      (s/check! (zero? (:exit checklist)) (str "checklist:\n" (s/show checklist)))
      (s/check! (#{0 1} (:exit ratify)) (str "ratify did not run:\n" (s/show ratify)))
      (s/check! (= "spec-3" (:cut c)) (str "the checklist's cut is " (:cut c)))
      (s/check! (= "spec-3" (:cut ratified)) (str "ratified.edn's cut is " (:cut ratified)))
      (s/check! (= (scope-items (:scope ctx)) (mapv :id (:items c)))
                (str "the checklist holds " (pr-str (mapv :id (:items c))) ", the scope "
                     (pr-str (scope-items (:scope ctx))))))))

(defstep #"dhf/cycles/03/cycle\.edn holds the scope (.+)"
  (fn [ctx scope]
    (s/needs-built! 'rdm-spec.measurement.checklist)
    (let [scope (edn/read-string scope)
          ctx (scope-project! ctx)]
      (mc/write-cycle! (:dir ctx) "03" {:scope scope})
      (assoc ctx :scope scope))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (mc/exits! ctx 0)
    (s/check! (= (scope-items (:scope ctx)) (checklist-ids ctx))
              (str "the checklist holds " (pr-str (checklist-ids ctx)) ", the scope " (pr-str (scope-items (:scope ctx)))))))

(defstep #"cycle 03's checklist holds 2 items and its ratification passed on the room tree ([0-9a-f]{40})"
  (fn [ctx tree]
    (fixture! (assoc ctx :items ["spec-0012" "spec-0014"]
                     :ratified (assoc (:ratified defaults) :room-tree tree)))))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx] (fixture! (assoc ctx :verdicts (:verdicts defaults)))))

(defstep #"dhf/cycles/03/score\.edn holds:"
  (fn [ctx doc]
    (let [got (score ctx)
          want (edn/read-string doc)
          text (slurp (str (fs/file (:dir ctx) score-path)))
          order #(mapv second (re-seq #"(?m)(?:^\{|^ )(:[a-z-]+) " %))]
      (s/check! (= want got) (str "score.edn holds\n" (pr-str got) "\nexpected\n" doc))
      (s/check! (= (order doc) (order text)) (str "score.edn's keys are in the order " (pr-str (order text)))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (fixture! (assoc ctx :verdicts-text doc :verdicts (edn/read-string doc)))))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (let [sc (score ctx)]
      (s/check! (= (:verdicts ctx) (:items sc)) (str "score.edn's items: " (pr-str (:items sc)))))))

(def malformed
  {"is a map, not a vector" [(pr-str {"spec-0012" :pass}) #"(?i)vector"]
   "grades spec-0012 twice" [(pr-str [{:id "spec-0012" :verdict :pass :evidence "a"}
                                      {:id "spec-0012" :verdict :fail :evidence "b"}])
                             #"spec-0012.*(twice|2 times)"]
   "gives spec-0012 the verdict :maybe" [(pr-str [{:id "spec-0012" :verdict :maybe :evidence "a"}]) #"spec-0012.*:maybe"]
   "gives spec-0012 no evidence" [(pr-str [{:id "spec-0012" :verdict :pass}]) #"spec-0012.*evidence"]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx what]
    (let [[text re] (or (malformed what) (s/fail! (str "no such malformed verdicts file: " what)))]
      (fixture! (assoc ctx :verdicts-text text :malformed re)))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx] (refused! ctx 1 (:malformed ctx) (str "what is malformed (" (:malformed ctx) ")"))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (fixture! (assoc ctx :verdicts-text "[]\n"))))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (refused! ctx 2 #"grading did not happen" "that the grading did not happen")))

(defstep #"cycle 03's checklist holds (\d+) items"
  (fn [ctx n] (fixture! (assoc ctx :items (item-ids (parse-long n))))))

(defstep #"a verdicts file grading (\d+) of them, all passed"
  (fn [ctx n]
    (let [graded (take (parse-long n) (:items ctx))]
      (fixture! (assoc ctx :verdicts (vec (for [id graded] {:id id :verdict :pass :evidence (str id " passed")}))
                       :ungraded (vec (drop (parse-long n) (:items ctx))))))))

(defstep #"the score is (\d+) of (\d+), and the ungraded item is recorded as failed"
  (fn [ctx passed scored]
    (let [sc (score ctx)]
      (s/check! (= [(parse-long passed) (parse-long scored)] [(:passed sc) (:scored sc)])
                (str "the score is " (:passed sc) " of " (:scored sc)))
      (doseq [id (:ungraded ctx)]
        (s/check! (some #(and (= id (:id %)) (= :fail (:verdict %))) (:items sc))
                  (str "the ungraded " id " is not recorded as failed: " (pr-str (:items sc))))))))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx]
    (fixture! (assoc ctx :verdicts (conj (:verdicts defaults) {:id "spec-0999" :verdict :pass :evidence "it passed"})))))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (refused! ctx 1 #"spec-0999" "spec-0999")))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx]
    (fixture! (assoc ctx :cycle-overrides {:builder {:name "builder-a" :actor :agent}
                                           :grader {:name "grader-b" :actor :agent}
                                           :spec-owner {:name "Sami" :actor :human}}))))

(defstep #"score\.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (let [sc (score ctx)
          want {:builder {:name "builder-a" :actor :agent}
                :grader {:name "grader-b" :actor :agent}
                :spec-owner {:name "Sami" :actor :human}}]
      (s/check! (= want (select-keys sc (keys want))) (str "score.edn names " (pr-str (select-keys sc (keys want))))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (fixture! (assoc ctx :cycle-overrides {:builder {:name "builder-a" :actor :agent}
                                           :grader {:name "builder-a" :actor :agent}}))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (refused! ctx 1 #"builder may not grade or read its own score" "the builder may not grade its own score")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx] (fixture! (assoc ctx :cycle-overrides {:spec-owner nil}))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (refused! ctx 1 #"(?i)spec.owner" "the spec owner")))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified\.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (fixture! (assoc ctx :ratified (if (str/starts-with? state "holds")
                                     (assoc (:ratified defaults) :outcome :refused :reason "the test command failed")
                                     :none)))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx] (refused! ctx 1 #"no passing ratification" "that the cycle has no passing ratification")))
