(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature.

  A scored cycle is a project (the room's configuration) holding cycle 03:
  cycle.edn, checklist.json (written as its contract states, spec-0210),
  ratified.edn and, from a Given, verdicts.edn in the project's root. A Given
  that finds no project (:dir) makes the default one: spec-0012 and spec-0014
  to grade, a passing ratification. The command is the kernel's shared When."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def score-rel (mc/cycle-path "03" "score.edn"))

(def tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def evidence {"spec-0012" "check exited 1 naming the view" "spec-0014" "no finding was printed"})

(defn- scored-cycle!
  "ctx with a project holding cycle 03, its checklist holding `items`, its
  ratification passed; the cycle.edn has `overrides`."
  ([ctx] (scored-cycle! ctx (vec (take 2 mc/default-scenarios)) {}))
  ([ctx items overrides]
   (let [dir (s/project!)]
     (mc/write-cycle! dir "03" overrides)
     (mc/write-checklist! dir "03" "spec-3" items [(nth mc/default-scenarios 2)])
     (mc/write-ratified! dir "03" :passed tree)
     (assoc ctx :dir dir :cycle "03" :checklist-ids (mapv :id items)))))

(defn- ensure-cycle [ctx] (if (:dir ctx) ctx (scored-cycle! ctx)))

(defn- verdicts! [ctx text]
  (let [ctx (ensure-cycle ctx)]
    (s/write! (:dir ctx) "verdicts.edn" text)
    ctx))

(defn- read-score [ctx]
  (let [f (fs/file (:dir ctx) score-rel)]
    (s/check! (fs/exists? f) (str "no " score-rel "\n" (s/show (kc/the-run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- refused!
  "Checks the run exited `code` and wrote no score; returns the run."
  [ctx code]
  (let [r (kc/exits! ctx code)]
    (s/check! (not (fs/exists? (fs/file (:dir ctx) score-rel))) (str score-rel " was written\n" (s/show r)))
    r))

(defn- says! [r lines & words]
  (s/check! (some (fn [l] (every? #(str/includes? (str/lower-case l) (str/lower-case %)) words)) lines)
            (str "no line says " (str/join ", " (map pr-str words)) "\n" (s/show r))))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(def ^:private scope-features
  {mc/lint-feature [{:id "spec-0012" :title "A view with no description fails the lint"}]
   "dhf/spec/verification/status.feature" [{:id "spec-0031" :title "A passing result verifies its scenario"}
                                           {:id "spec-0032" :title "A failing result fails its scenario"}]
   "dhf/spec/knowledge/sources.feature" [{:id "spec-0041" :title "A pinned source is read"}]
   "dhf/spec/spec/parser.feature" [{:id "spec-0051" :title "A feature file is read"}]})

(defn- scope-project! [ctx]
  (let [dir (mc/project! scope-features)]
    (mc/cut! dir "spec-3")
    (assoc ctx :dir dir :cycle "03")))

(defstep #"dhf/cycles/03/cycle\.edn holds:"
  (fn [ctx doc]
    (let [ctx (scope-project! ctx)
          ids (sort (mapcat #(map :id %) (vals scope-features)))]
      (s/write! (:dir ctx) (mc/cycle-path "03" "cycle.edn") doc)
      (assoc ctx :room (mc/passing-room! ids) :scope (:scope (edn/read-string doc))))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec ratify ROOM 03\" are run"
  (fn [ctx]
    (let [c (kc/run! ctx (:dir ctx) ["checklist" "03"])
          r (kc/run! ctx (:dir ctx) ["ratify" (:room ctx) "03"])]
      (assoc ctx :checklist-run c :ratify-run r :run r))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [{c :checklist-run r :ratify-run dir :dir} ctx]
      (s/check! (zero? (:exit c)) (str "the checklist failed\n" (s/show c)))
      (s/check! (zero? (:exit r)) (str "ratify failed\n" (s/show r)))
      (let [cl (json/parse-string (slurp (str (fs/file dir (mc/cycle-path "03" "checklist.json")))) true)
            rat (mc/read-edn dir (mc/cycle-path "03" "ratified.edn"))]
        (s/check! (= "spec-3" (:cut cl)) (str "the checklist's cut is " (pr-str (:cut cl))))
        (s/check! (= "spec-3" (:cut rat)) (str "ratified.edn's cut is " (pr-str (:cut rat))))
        (s/check! (= ["spec-0031" "spec-0032"] (map :id (:items cl)))
                  (str "the checklist holds " (pr-str (map :id (:items cl))) ", not verification's"))
        (s/check! (every? #(= "verification" (:context %)) (:items cl)) "an item is not verification's")))))

(def ^:private scope-ids
  {":project" ["spec-0012" "spec-0031" "spec-0032" "spec-0041" "spec-0051"]
   "{:context :verification}" ["spec-0031" "spec-0032"]
   "{:component :spec/parser}" ["spec-0051"]
   "[{:context :knowledge} {:component :spec/parser}]" ["spec-0041" "spec-0051"]})

(defstep #"dhf/cycles/03/cycle\.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [ctx (scope-project! ctx)]
      (mc/write-cycle! (:dir ctx) "03" {:scope (edn/read-string scope)})
      (assoc ctx :scope-text scope))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          cl (json/parse-string (slurp (str (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))) true)
          want (get scope-ids (:scope-text ctx))]
      (s/check! want (str "no expectation for the scope " (:scope-text ctx)))
      (s/check! (= want (map :id (:items cl)))
                (str "the scope " (:scope-text ctx) " gave the items " (pr-str (map :id (:items cl))) "\n" (s/show r))))))

(defstep #"cycle 03's checklist holds 2 items and its ratification passed on the room tree ([0-9a-f]{40})"
  (fn [ctx h]
    (s/check! (= tree h) "the fixture's tree is another")
    (scored-cycle! ctx)))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (verdicts! ctx (pr-str [{:id "spec-0012" :verdict :pass :evidence (evidence "spec-0012")}
                            {:id "spec-0014" :verdict :fail :evidence (evidence "spec-0014")}]))))

(defstep #"dhf/cycles/03/score\.edn holds:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [got (read-score ctx)
          want (edn/read-string doc)]
      (s/check! (= want got) (str "score.edn holds\n" (pr-str got) "\nnot\n" (pr-str want))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (assoc (verdicts! ctx doc) :verdicts (edn/read-string doc))))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [{:keys [items]} (read-score ctx)]
      (s/check! (= (:verdicts ctx) items) (str "the score's items are " (pr-str items))))))

(def ^:private malformed
  {"is a map, not a vector" [{:id "spec-0012" :verdict :pass :evidence "e"} ["vector"]]
   "grades spec-0012 twice" [[{:id "spec-0012" :verdict :pass :evidence "e"} {:id "spec-0012" :verdict :fail :evidence "f"}]
                             ["spec-0012" "twice"]]
   "gives spec-0012 the verdict :maybe" [[{:id "spec-0012" :verdict :maybe :evidence "e"}] ["spec-0012" ":maybe"]]
   "gives spec-0012 no evidence" [[{:id "spec-0012" :verdict :pass}] ["spec-0012" "evidence"]]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx what]
    (let [[x words] (or (get malformed what) (s/fail! (str "no such malformed file: " what)))]
      (assoc (verdicts! ctx (pr-str x)) :malformed-words words))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (let [r (refused! ctx 1)]
      (apply says! r (kc/finding-lines r) (:malformed-words ctx)))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (verdicts! ctx "[]\n")))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx]
    (let [r (refused! ctx 2)]
      (says! r (kc/cannot-run-lines r) "grading did not happen"))))

(defstep #"cycle 03's checklist holds (\d+) items"
  (fn [ctx n]
    (scored-cycle! ctx (mc/numbered 101 (parse-long n)) {})))

(defstep #"a verdicts file grading (\d+) of them, all passed"
  (fn [ctx n]
    (let [ids (take (parse-long n) (:checklist-ids ctx))]
      (assoc (verdicts! ctx (pr-str (vec (for [id ids] {:id id :verdict :pass :evidence "it passed"}))))
             :ungraded (vec (drop (parse-long n) (:checklist-ids ctx)))))))

(defstep #"the score is (\d+) of (\d+), and the ungraded item is recorded as failed"
  (fn [ctx passed scored]
    (kc/exits! ctx 0)
    (let [{:keys [items] :as sc} (read-score ctx)
          by-id (into {} (map (juxt :id identity)) items)]
      (s/check! (= [(parse-long passed) (parse-long scored)] [(:passed sc) (:scored sc)])
                (str "the score is " (:passed sc) " of " (:scored sc)))
      (s/check! (= (:checklist-ids ctx) (map :id items)) (str "the items are " (pr-str (map :id items))))
      (doseq [id (:ungraded ctx)]
        (s/check! (= :fail (:verdict (by-id id))) (str id " is recorded as " (pr-str (by-id id))))))))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx]
    (verdicts! ctx (pr-str [{:id "spec-0012" :verdict :pass :evidence "e"}
                            {:id "spec-0999" :verdict :pass :evidence "e"}]))))

(defstep #"it exits 1, writes nothing, and names (spec-\d+)"
  (fn [ctx id]
    (let [r (refused! ctx 1)]
      (says! r (kc/finding-lines r) id))))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defn- graded! [ctx]
  (verdicts! ctx (pr-str [{:id "spec-0012" :verdict :pass :evidence "e"}
                          {:id "spec-0014" :verdict :pass :evidence "e"}])))

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx] (graded! (scored-cycle! ctx))))

(defstep #"score\.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [sc (read-score ctx)]
      (s/check! (= {:builder {:name "builder-a" :actor :agent}
                    :grader {:name "grader-b" :actor :agent}
                    :spec-owner {:name "Sami" :actor :human}}
                   (select-keys sc [:builder :grader :spec-owner]))
                (str "score.edn names " (pr-str (select-keys sc [:builder :grader :spec-owner])))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx] (graded! (scored-cycle! ctx (vec (take 2 mc/default-scenarios)) {:grader {:name "builder-a" :actor :agent}}))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx]
    (let [r (refused! ctx 1)]
      (says! r (kc/finding-lines r) "builder-a" "may not grade or read its own score"))))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx] (graded! (scored-cycle! ctx (vec (take 2 mc/default-scenarios)) {:spec-owner nil}))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx]
    (let [r (refused! ctx 1)]
      (says! r (kc/finding-lines r) "spec owner"))))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified\.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (graded! (scored-cycle! ctx))
          rel (mc/cycle-path "03" "ratified.edn")]
      (if (= state "does not exist")
        (fs/delete (fs/file (:dir ctx) rel))
        (mc/write-ratified! (:dir ctx) "03" :refused tree))
      ctx)))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx]
    (let [r (refused! ctx 1)]
      (says! r (kc/finding-lines r) "no passing ratification"))))
