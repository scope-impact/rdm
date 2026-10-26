(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature: `rdm-spec score
  03 --verdicts verdicts.edn` in a scored project (the room's configuration
  only) holding cycle 03's cycle.edn, checklist.json and ratified.edn as
  their contracts state them, and a verdicts file the step writes; and, for
  the cycle's declaration, `checklist` and `verify` on a cycle project
  (steps.measurement-cycle) at the cut spec-3.

  Context keys set here: :dir, :room, :items (the checklist's item ids),
  :said (what a refusal must say), :checklist-run (the checklist's run)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def ^:private score-rel (mc/cycle-path "03" "score.edn"))
(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def ^:private two-items
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}])

(def ^:private two-verdicts
  [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
   {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}])

(defn- verdicts! [ctx v]
  (s/write! (:dir ctx) "verdicts.edn" (if (string? v) v (str (pr-str v) "\n")))
  ctx)

(defn- scored-project!
  "ctx with :dir: cycle 03 declared, its checklist holding `items`, its
  verification passed on the room tree `tree`, and a verdicts file grading
  the two default items."
  ([ctx] (scored-project! ctx two-items))
  ([ctx items]
   (if (:dir ctx)
     ctx
     (let [dir (s/project!)]
       (mc/write-cycle! dir "03" {})
       (mc/write-checklist! dir "03" "spec-3" items [])
       (mc/write-ratified! dir "03" :passed tree)
       (verdicts! (assoc ctx :dir dir :items (mapv :id items)) two-verdicts)))))

(defn- score [ctx]
  (let [f (fs/file (:dir ctx) score-rel)]
    (s/check! (fs/exists? f) (str "no " score-rel "\n" (s/show (kc/the-run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- refused! [ctx code & parts]
  (let [r (kc/exits! ctx code)
        ls (if (= 2 code) (kc/cannot-run-lines r) (kc/finding-lines r))]
    (s/check! (not (fs/exists? (fs/file (:dir ctx) score-rel))) (str score-rel " was written"))
    (s/check! (some (fn [l] (every? #(str/includes? l %) parts)) ls)
              (str "no line says all of " (pr-str parts) "\n" (s/show r)))
    nil))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is verified

(defn- cycle-project!
  "A cycle project at the cut spec-3 holding the lint's feature file and
  one scenario of the verification context, its :verify the tests check."
  []
  (let [dir (mc/project! {mc/lint-feature mc/default-scenarios
                          "dhf/spec/verification/status.feature" [{:id "spec-0020" :title "A status"}]})
        cfg (kc/config-of dir)]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (assoc cfg :verify [{:kind :tests :command ["bb" "test.clj"]}])) "\n"))
    (mc/cut! dir "spec-3")))

(defstep #"dhf/cycles/03/cycle\.edn holds:"
  (fn [ctx doc]
    (let [dir (cycle-project!)]
      (s/write! dir (mc/cycle-path "03" "cycle.edn") doc)
      (assoc ctx :dir dir :room (mc/passing-room! ["spec-0012" "spec-0014" "spec-0020"])))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec verify ROOM 03\" are run"
  (fn [ctx]
    (let [a (kc/run-line ctx "checklist 03")]
      (assoc (kc/run-line a "verify ROOM 03") :checklist-run (:run a)))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [c (:checklist-run ctx)
          v (kc/exits! ctx 0)]
      (s/check! (zero? (:exit c)) (str "the checklist failed\n" (s/show c)))
      (let [cl (json/parse-string (slurp (str (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))) true)
            rat (mc/read-edn (:dir ctx) (mc/cycle-path "03" "ratified.edn"))]
        (s/check! (= "spec-3" (:cut cl) (get-in (kc/stdout-edn v) [:verify :cut]) (:cut rat))
                  (str "the cuts are " (:cut cl) ", " (get-in (kc/stdout-edn v) [:verify :cut]) " and " (:cut rat)))
        (s/check! (= ["spec-0020"] (map :id (:items cl))) (str "the items are " (pr-str (map :id (:items cl))))))
      nil)))

(defstep #"dhf/cycles/03/cycle\.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [dir (cycle-project!)]
      (mc/write-cycle! dir "03" {:scope (edn/read-string scope)})
      (assoc ctx :dir dir))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json"))) "no checklist.json")
    nil))

(defstep #"cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [ctx] (scored-project! ctx)))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx] (verdicts! (scored-project! ctx) two-verdicts)))

(defstep #"dhf/cycles/03/score\.edn holds:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [got (score ctx)]
      (s/check! (= (edn/read-string doc) got) (str "score.edn holds " (pr-str got)))
      nil)))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (verdicts! (scored-project! ctx) doc)))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= two-verdicts (:items (score ctx))) (str "the items filed are " (pr-str (:items (score ctx)))))
    nil))

(def ^:private malformed
  {"is a map, not a vector" [{:id "spec-0012" :verdict :pass :evidence "e"} "vector"]
   "grades spec-0012 twice" [(conj two-verdicts (first two-verdicts)) "twice"]
   "gives spec-0012 the verdict :maybe" [(assoc-in two-verdicts [0 :verdict] :maybe) ":maybe"]
   "gives spec-0012 no evidence" [(update two-verdicts 0 dissoc :evidence) "evidence"]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx which]
    (let [[v said] (or (get malformed which) (s/fail! (str "no such verdicts file: " which)))]
      (assoc (verdicts! (scored-project! ctx) v) :said said))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx] (refused! ctx 1 "verdicts.edn" (:said ctx))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (verdicts! (scored-project! ctx) [])))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (refused! ctx 2 "the grading did not happen")))

(defstep #"cycle 03's checklist holds 12 items"
  (fn [ctx] (scored-project! ctx (mc/numbered 1 12))))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx]
    (verdicts! ctx (vec (for [id (take 11 (:items ctx))] {:id id :verdict :pass :evidence "it holds"})))))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [sc (score ctx)]
      (s/check! (= [11 12] [(:passed sc) (:scored sc)]) (str "the score is " (:passed sc) " of " (:scored sc)))
      (s/check! (= :fail (:verdict (last (:items sc)))) (str "the last item is " (pr-str (last (:items sc)))))
      nil)))

(defstep #"score\.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (let [it (last (:items (score ctx)))]
      (s/check! (= {:id (last (:items ctx)) :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true} it)
                (str "the item is " (pr-str it)))
      nil)))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx] (verdicts! (scored-project! ctx) (conj two-verdicts {:id "spec-0999" :verdict :pass :evidence "e"}))))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (refused! ctx 1 "spec-0999")))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx] (scored-project! ctx)))

(defstep #"score\.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [sc (score ctx)]
      (s/check! (= [{:name "builder-a" :actor :agent} {:name "grader-b" :actor :agent} {:name "Sami" :actor :human}]
                   [(:builder sc) (:grader sc) (:spec-owner sc)])
                (str "score.edn names " (pr-str (select-keys sc [:builder :grader :spec-owner]))))
      nil)))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (let [ctx (scored-project! ctx)]
      (mc/write-cycle! (:dir ctx) "03" {:grader {:name "builder-a" :actor :agent}})
      ctx)))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (refused! ctx 1 "the builder may not grade or read its own score")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (let [ctx (scored-project! ctx)]
      (mc/write-cycle! (:dir ctx) "03" {:spec-owner nil})
      ctx)))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (refused! ctx 1 ":spec-owner")))

;; ---------------------------------------------------------------------------
;; Only a verified build is scored

(defstep #"dhf/cycles/03/ratified\.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (scored-project! ctx)
          dir (:dir ctx)]
      (if (= state "does not exist")
        (fs/delete (fs/file dir (mc/cycle-path "03" "ratified.edn")))
        (mc/write-ratified! dir "03" :refused tree))
      ctx)))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing verification"
  (fn [ctx] (refused! ctx 1 "no passing verification")))
