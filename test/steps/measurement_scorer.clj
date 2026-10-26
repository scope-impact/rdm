(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature. The command is
  the kernel's shared When (\"rdm-spec score 03 --verdicts verdicts.edn\" is
  run, \"rdm-spec checklist 03\" is run), run in the project these Givens
  make (:dir).

  A scorable cycle is a project holding only the configuration, cycle 03's
  cycle.edn (the default cycle: cut spec-3, scope :project, builder-a,
  grader-b, Sami), its checklist.json, a passing ratified.edn and a verdicts
  file, verdicts.edn, grading every item pass. Each Given builds on the
  project an earlier Given made.

  Context keys: :dir, :items (the checklist's items), :verdicts (what
  verdicts.edn holds), :malformed (the words a refusal must say)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def ^:private score-rel (mc/cycle-path "03" "score.edn"))

(def ^:private default-items (vec (remove :wip? mc/default-scenarios)))

(defn- pass-all [items]
  (mapv (fn [{:keys [id]}] {:id id :verdict :pass :evidence (str id " did what it says")}) items))

(defn- write-verdicts! [ctx vs]
  (s/write! (:dir ctx) "verdicts.edn" (if (string? vs) vs (str (pr-str vs) "\n")))
  (assoc ctx :verdicts vs))

(defn- scorable!
  "ctx with :dir, a scorable cycle 03 whose checklist holds `items`
  (default spec-0012 and spec-0014), its cycle.edn the default with
  `overrides`; reuses :dir when an earlier Given made one."
  ([ctx] (scorable! ctx default-items {}))
  ([ctx items overrides]
   (let [dir (or (:dir ctx) (s/project!))]
     (mc/write-cycle! dir "03" overrides)
     (mc/write-checklist! dir "03" "spec-3" items [])
     (mc/write-ratified! dir "03" :passed tree)
     (-> (assoc ctx :dir dir :items items)
         (write-verdicts! (pass-all items))))))

(defn- ensure-cycle [ctx] (if (:dir ctx) ctx (scorable! ctx)))

(defn- score-of [ctx]
  (mc/read-edn (:dir ctx) score-rel))

(defn- scored!
  "Checks the last run passed and wrote score.edn; returns it."
  [ctx]
  (let [r (kc/exits! ctx 0)
        sc (score-of ctx)]
    (s/check! sc (str "no " score-rel "\n" (s/show r)))
    sc))

(defn- wrote-nothing! [ctx]
  (s/check! (not (fs/exists? (fs/file (:dir ctx) score-rel)))
            (str score-rel " was written: " (pr-str (score-of ctx)))))

(defn- refused! [ctx code & words]
  (let [r (kc/exits! ctx code)
        lines (if (= 2 code) (kc/cannot-run-lines r) (kc/finding-lines r))]
    (wrote-nothing! ctx)
    (s/check! (some (fn [l] (every? #(str/includes? l %) words)) lines)
              (str "no " (if (= 2 code) "cannot-run" "finding") " line says " (str/join ", " (map pr-str words))
                   "\n" (s/show r)))
    nil))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(def ^:private scope-features
  "One scenario in each of four components of three contexts."
  {mc/lint-feature [{:id "spec-0012" :title "A view with no description fails the lint"}]
   "dhf/spec/verification/status.feature" [{:id "spec-0040" :title "A passing result verifies a scenario"}]
   "dhf/spec/spec/parser.feature" [{:id "spec-0050" :title "A feature file is read"}]
   "dhf/spec/knowledge/sources.feature" [{:id "spec-0060" :title "A source is pinned"}]})

(defn- scope-project! [ctx]
  (let [dir (mc/project! scope-features)]
    (mc/cut! dir "spec-3")
    ;; the working tree moves on after the cut: neither task may see it
    (s/write! dir "dhf/spec/verification/status.feature"
              (mc/feature-text-of "Status" [{:id "spec-0040" :title "A passing result verifies a scenario"}
                                           {:id "spec-0041" :title "Written after the cut"}]))
    (assoc ctx :dir dir)))

(defstep #"dhf/cycles/03/cycle\.edn holds:"
  (fn [ctx doc]
    (let [ctx (if (:dir ctx) ctx (scope-project! ctx))]
      (s/write! (:dir ctx) (mc/cycle-path "03" "cycle.edn") doc)
      (assoc ctx :cycle-edn (edn/read-string doc)))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec ratify ROOM 03\" are run"
  (fn [ctx]
    (let [ids (sort (map :id (mapcat val scope-features)))
          room (str (mc/passing-room! ids))
          c (kc/run! ctx (:dir ctx) ["checklist" "03"])
          r (kc/run! ctx (:dir ctx) ["ratify" room "03"])]
      (assoc ctx :checklist-run c :ratify-run r :run r))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (let [{c :checklist-run r :ratify-run} ctx]
      (s/check! (zero? (:exit c)) (str "the checklist failed\n" (s/show c)))
      (s/check! (zero? (:exit r)) (str "ratify failed\n" (s/show r)))
      (let [cl (json/parse-string (slurp (str (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))) true)
            rat (mc/read-edn (:dir ctx) (mc/cycle-path "03" "ratified.edn"))]
        (s/check! (= "spec-3" (:cut cl)) (str "the checklist's cut is " (pr-str (:cut cl))))
        (s/check! (= "spec-3" (:cut rat)) (str "the ratification's cut is " (pr-str (:cut rat))))
        (s/check! (= :passed (:outcome rat)) (str "ratified.edn: " (pr-str rat)))
        (s/check! (= ["spec-0040"] (map :id (:items cl)))
                  (str "the checklist's items are " (pr-str (map :id (:items cl))) ", not verification's spec-0040"))))))

(defstep #"dhf/cycles/03/cycle\.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [ctx (if (:dir ctx) ctx (scope-project! ctx))]
      (mc/write-cycle! (:dir ctx) "03" {:scope (edn/read-string scope)})
      (assoc ctx :scope (edn/read-string scope)))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          f (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json"))]
      (s/check! (fs/exists? f) (str "no checklist.json\n" (s/show r)))
      (s/check! (seq (:items (json/parse-string (slurp (str f)) true))) "the checklist holds no item"))))

(defstep #"cycle 03's checklist holds 2 items and its ratification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [ctx] (scorable! ctx)))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (write-verdicts! (ensure-cycle ctx)
                     [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
                      {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}])))

(defstep #"dhf/cycles/03/score\.edn holds:"
  (fn [ctx doc]
    (let [sc (scored! ctx)
          want (edn/read-string doc)]
      (s/check! (= want sc) (str "score.edn holds\n" (pr-str sc) "\nnot\n" (pr-str want))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (write-verdicts! (ensure-cycle ctx) doc)))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (let [sc (scored! ctx)
          want (edn/read-string (:verdicts ctx))]
      (s/check! (= want (:items sc)) (str "the items filed are " (pr-str (:items sc))))
      (s/check! (= [1 2] [(:passed sc) (:scored sc)]) (str "passed " (:passed sc) " of " (:scored sc))))))

(def ^:private malformed
  {"is a map, not a vector"
   [{:id "spec-0012" :verdict :pass :evidence "e"} ["vector"]]
   "grades spec-0012 twice"
   [[{:id "spec-0012" :verdict :pass :evidence "e"} {:id "spec-0012" :verdict :fail :evidence "f"}
     {:id "spec-0014" :verdict :pass :evidence "g"}]
    ["spec-0012" "twice"]]
   "gives spec-0012 the verdict :maybe"
   [[{:id "spec-0012" :verdict :maybe :evidence "e"} {:id "spec-0014" :verdict :pass :evidence "g"}]
    ["spec-0012" ":maybe"]]
   "gives spec-0012 no evidence"
   [[{:id "spec-0012" :verdict :pass} {:id "spec-0014" :verdict :pass :evidence "g"}]
    ["spec-0012" "evidence"]]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx how]
    (let [[vs words] (or (get malformed how) (s/fail! (str "no such malformed verdicts file: " how)))]
      (assoc (write-verdicts! (ensure-cycle ctx) vs) :malformed words))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx] (apply refused! ctx 1 (:malformed ctx))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (write-verdicts! (ensure-cycle ctx) [])))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (refused! ctx 2 "grading did not happen")))

(defstep #"cycle 03's checklist holds 12 items"
  (fn [ctx] (scorable! ctx (mc/numbered 101 12) {})))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx]
    (let [graded (vec (butlast (:items ctx)))]
      (assoc (write-verdicts! ctx (pass-all graded)) :ungraded (:id (last (:items ctx)))))))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx]
    (let [sc (scored! ctx)
          item (first (filter #(= (:ungraded ctx) (:id %)) (:items sc)))]
      (s/check! (= [11 12] [(:passed sc) (:scored sc)]) (str "passed " (:passed sc) " of " (:scored sc)))
      (s/check! (= 12 (count (:items sc))) (str (count (:items sc)) " items filed"))
      (s/check! (= :fail (:verdict item)) (str "the ungraded item is filed as " (pr-str item))))))

(defstep #"score\.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (let [sc (score-of ctx)
          item (first (filter #(= (:ungraded ctx) (:id %)) (:items sc)))
          want {:id (:ungraded ctx) :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}]
      (s/check! (= want item) (str "the ungraded item is filed as " (pr-str item))))))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx]
    (write-verdicts! (ensure-cycle ctx)
                     [{:id "spec-0012" :verdict :pass :evidence "e"} {:id "spec-0999" :verdict :pass :evidence "f"}])))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (refused! ctx 1 "spec-0999")))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx]
    (scorable! ctx default-items {:builder {:name "builder-a" :actor :agent}
                                  :grader {:name "grader-b" :actor :agent}
                                  :spec-owner {:name "Sami" :actor :human}})))

(defstep #"score\.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (let [sc (scored! ctx)]
      (s/check! (= {:builder {:name "builder-a" :actor :agent}
                    :grader {:name "grader-b" :actor :agent}
                    :spec-owner {:name "Sami" :actor :human}}
                   (select-keys sc [:builder :grader :spec-owner]))
                (str "score.edn names " (pr-str (select-keys sc [:builder :grader :spec-owner])))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx] (scorable! ctx default-items {:grader {:name "builder-a" :actor :agent}})))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (refused! ctx 1 "builder-a" "may not grade or read its own score")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx] (scorable! ctx default-items {:spec-owner nil})))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (refused! ctx 1 ":spec-owner")))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified\.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (ensure-cycle ctx)
          rel (mc/cycle-path "03" "ratified.edn")]
      (if (= "does not exist" state)
        (fs/delete-if-exists (fs/file (:dir ctx) rel))
        (mc/write-ratified! (:dir ctx) "03" :refused tree))
      ctx)))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx] (refused! ctx 1 "no passing ratification")))
