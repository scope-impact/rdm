(ns steps.measurement-scorer
  "Step definitions for dhf/spec/measurement/scorer.feature.

  A scored cycle is built by hand in a temporary project: cycle.edn (the
  three roles of steps.measurement-cycle), checklist.json, ratified.edn and a
  verdicts file, verdicts.edn, at the project's root. Each Given leaves a
  complete, scorable cycle with only its own defect, and ends with a snapshot
  of the project, so \"writes nothing\" compares the project after the run
  with it. The checklist's first two items are always spec-0012 and
  spec-0014, the ids the scenarios grade.

  The cycle declaration scenarios (spec-0173, spec-0200) need a cut: a git
  repository tagging spec-3, from steps.measurement-cycle, whose declared
  test command is a fake writing a passing result for each stamped scenario."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def graded-pass {:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"})
(def graded-fail {:id "spec-0014" :verdict :fail :evidence "no finding was printed"})

(defn item-ids
  "`n` checklist ids: spec-0012 and spec-0014 first, then spec-0101 on."
  [n]
  (vec (take n (concat ["spec-0012" "spec-0014"] (for [i (range)] (format "spec-%04d" (+ 101 i)))))))

(defn write-checklist! [dir ids]
  (s/write! dir (mc/cycle-path "03" "checklist.json")
            (json/generate-string
             (array-map "cycle" "03" "cut" "spec-3"
                        "items" (for [id (sort ids)]
                                  (array-map "id" id "title" (str "Scenario " id) "context" "architecture"
                                             "component" "Lint" "level" "e2e"))
                        "planned" [])
             {:pretty true})))

(defn write-ratified! [dir ratified]
  (let [f (fs/file dir (mc/cycle-path "03" "ratified.edn"))]
    (if ratified
      (s/write! dir (mc/cycle-path "03" "ratified.edn") (pr-str ratified))
      (fs/delete-if-exists f))))

(defn write-verdicts! [dir text]
  (s/write! dir "verdicts.edn" text))

(defn passed-ratification [room-tree]
  {:cut "spec-3" :room-tree room-tree :outcome :passed :checkout "dhf/cycles/03/build"})

(defn scorable!
  "A project with cycle 03 ready to score. Options: :ids (the checklist),
  :room-tree, :cycle (merged into cycle.edn, a nil value removing the key),
  :ratified (nil for none), :verdicts (the verdicts file's text)."
  [{:keys [ids room-tree cycle verdicts] :as opts
    :or {ids (item-ids 2) room-tree "4b825dc"}}]
  (let [dir (s/project!)
        ratified (if (contains? opts :ratified) (:ratified opts) (passed-ratification room-tree))]
    (mc/write-cycle! dir "03" {})
    (when cycle
      (let [c (merge {:cut "spec-3" :scope :project} mc/roles cycle)]
        (s/write! dir (mc/cycle-path "03" "cycle.edn") (pr-str (into {} (remove (comp nil? val) c))))))
    (write-checklist! dir ids)
    (write-ratified! dir ratified)
    (write-verdicts! dir (or verdicts (pr-str [graded-pass graded-fail])))
    dir))

(defn- with-project
  "The context's project, made scorable with `opts` when it has none yet."
  [ctx opts]
  (or (:dir ctx) (scorable! opts)))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- exits! [ctx code]
  (s/check! (= code (:exit (run ctx))) (str "expected exit " code "\n" (s/show (run ctx)))))

(defn- says! [ctx re what]
  (s/check! (re-find re (:err (run ctx))) (str "the error stream does not say " what "\n" (s/show (run ctx)))))

(defn- score [ctx]
  (let [f (fs/file (:dir ctx) (mc/cycle-path "03" "score.edn"))]
    (s/check! (fs/exists? f) (str "no " (mc/cycle-path "03" "score.edn") "\n" (s/show (run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- refused! [ctx code re what]
  (exits! ctx code)
  (mc/wrote-nothing! ctx)
  (says! ctx re what))

;; ---------------------------------------------------------------------------
;; A cycle is one directory, declared before it is ratified

(defn- fake-tests
  "A test command writing a passing Allure result for each of `ids` into
  $RESULTS_DIR."
  [ids]
  [s/bb "-e"
   (str "(require '[babashka.fs :as fs] '[cheshire.core :as json])"
        "(let [d (System/getenv \"RESULTS_DIR\")]"
        " (fs/create-dirs d)"
        " (doseq [id " (pr-str (vec ids)) "]"
        "  (spit (str d \"/\" id \"-result.json\")"
        "        (json/generate-string {:uuid id :name id :status \"passed\" :stage \"finished\""
        "                               :labels [{:name \"spec\" :value id}]}))))")])

(defn- cut-project!
  "A git repository tagging spec-3: the room's configuration (its test command
  a fake), model and a Lint feature holding spec-0012 and spec-0014; with
  cycle 03's cycle.edn holding `cycle-text`, written after the cut."
  [cycle-text]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir mc/lint-feature (mc/feature-text [{:id "spec-0012" :title "A view with no description fails the lint"}
                                                    {:id "spec-0014" :title "A view over the limit fails the lint"}]))
    (s/write! dir "dhf/harness/config.edn" (pr-str (assoc (s/room-config) :test-command (fake-tests ["spec-0012" "spec-0014"]))))
    (mc/cut! dir "spec-3")
    (s/write! dir (mc/cycle-path "03" "cycle.edn") cycle-text)
    dir))

(defn- room!
  "A fresh room holding a small implementation."
  []
  (let [room (s/tmp-dir)]
    (s/write! room "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! room "test.clj" ";; the room's tests\n")
    room))

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [ctx doc]
    (mc/settle (assoc ctx :dir (cut-project! doc) :room (room!) :cycle-decl (edn/read-string doc)))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec ratify ROOM 03\" are run"
  (fn [ctx]
    (let [checklist (s/rdm-spec! (:dir ctx) "checklist" "03")
          ratify (s/rdm-spec! (:dir ctx) "ratify" (:room ctx) "03")]
      (assoc ctx :runs {:checklist checklist :ratify ratify} :run ratify))))

(defstep #"both work on the cut spec-3, for that scope"
  (fn [ctx]
    (let [{:keys [checklist ratify]} (:runs ctx)
          decl (:cycle-decl ctx)
          dir (:dir ctx)]
      (mc/ran-clean! checklist)
      (let [r (edn/read-string (:out checklist))
            cl (mc/read-checklist-json ctx "03")]
        (s/check! (= "spec-3" (:cut r) (get-in cl [:data :cut])) (str "the checklist's cut:\n" (s/show checklist)))
        (s/check! (= (:scope decl) (:scope r)) (str "the checklist's scope is not " (pr-str (:scope decl)) "\n" (s/show checklist))))
      (s/check! (zero? (:exit ratify)) (s/show ratify))
      (let [ratified (edn/read-string (slurp (str (fs/file dir (mc/cycle-path "03" "ratified.edn")))))
            build (fs/file dir (mc/cycle-path "03" "build"))
            head (fn [d] (str/trim (:out (p/shell {:dir (str d) :out :string :err :string} "git" "rev-parse" "HEAD"))))
            cut (str/trim (:out (p/shell {:dir (str dir) :out :string :err :string} "git" "rev-parse" "spec-3^{commit}")))]
        (s/check! (= {:cut "spec-3" :outcome :passed} (select-keys ratified [:cut :outcome]))
                  (str "ratified.edn: " (pr-str ratified)))
        (s/check! (= cut (head build)) "ratify's checkout is not the commit tagged spec-3")
        (s/check! (fs/exists? (fs/file build "src/rdm_spec/demo.clj")) "ratify's checkout does not hold the room's implementation")))))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [dir (mc/spec-project! {mc/lint-feature (mc/feature-text [{:id "spec-0012" :title "A view with no description fails the lint"}])})]
      (mc/write-cycle! dir "03" {:scope (edn/read-string scope)})
      (mc/settle (assoc ctx :dir dir :scope (edn/read-string scope))))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (mc/ran-clean! (run ctx))
    (let [r (mc/run-result ctx)]
      (s/check! (= (:scope ctx) (:scope r)) (str "the checklist's scope is " (pr-str (:scope r)) ", not " (pr-str (:scope ctx))))
      (mc/read-checklist-json ctx "03"))))

(defstep #"cycle 03's checklist holds (\d+) items and its ratification passed on the room tree (\S+)"
  (fn [ctx n tree]
    (mc/settle (assoc ctx :dir (scorable! {:ids (item-ids (parse-long n)) :room-tree tree})))))

(defstep #"cycle 03's checklist holds (\d+) items"
  (fn [ctx n]
    (mc/settle (assoc ctx :dir (scorable! {:ids (item-ids (parse-long n))}) :items (item-ids (parse-long n))))))

(defstep #"a verdicts file grading (\S+) pass and (\S+) fail"
  (fn [ctx pass fail]
    (let [dir (with-project ctx {})]
      (write-verdicts! dir (pr-str [(assoc graded-pass :id pass) (assoc graded-fail :id fail)]))
      (mc/settle (assoc ctx :dir dir)))))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx doc]
    (exits! ctx 0)
    (let [f (fs/file (:dir ctx) (mc/cycle-path "03" "score.edn"))
          text (do (score ctx) (slurp (str f)))
          expected (edn/read-string doc)
          actual (edn/read-string text)]
      (s/check! (= expected actual) (str "score.edn holds\n" text "\nexpected\n" doc))
      ;; the docstring's key order is the file's
      (let [order (map str (re-seq #"(?m)^[{ ]:[a-z-]+" doc))
            positions (map #(str/index-of text (str/trim (subs % 1))) order)]
        (s/check! (and (every? some? positions) (apply < positions))
                  (str "score.edn's keys are not in the docstring's order:\n" text))))))

;; ---------------------------------------------------------------------------
;; Verdicts have one shape

(defstep #"a verdicts file holding:"
  (fn [ctx doc]
    (let [dir (with-project ctx {})]
      (write-verdicts! dir doc)
      (mc/settle (assoc ctx :dir dir :verdicts (edn/read-string doc))))))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (exits! ctx 0)
    (let [sc (score ctx)]
      (s/check! (= (:verdicts ctx) (:items sc)) (str "score.edn's items: " (pr-str (:items sc))))
      (s/check! (= [1 2] [(:passed sc) (:scored sc)]) (str "passed " (:passed sc) " of " (:scored sc))))))

(def malformed
  "Each malformed verdicts file: its text, and what the error stream must say."
  {"is a map, not a vector"
   [(pr-str {"spec-0012" graded-pass}) #"(?i)map, not a vector" "that the file is a map, not a vector"]
   "grades spec-0012 twice"
   [(pr-str [graded-pass (assoc graded-pass :verdict :fail)]) #"spec-0012.*(twice|2 times)" "that spec-0012 is graded twice"]
   "gives spec-0012 the verdict :maybe"
   [(pr-str [(assoc graded-pass :verdict :maybe) graded-fail]) #"spec-0012.*:maybe" "that spec-0012's verdict :maybe is no verdict"]
   "gives spec-0012 no evidence"
   [(pr-str [(dissoc graded-pass :evidence) graded-fail]) #"spec-0012.*no evidence" "that spec-0012 gives no evidence"]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx what]
    (let [[text re says] (or (get malformed what) (s/fail! (str "no malformed verdicts file that " what)))
          dir (with-project ctx {:verdicts text})]
      (write-verdicts! dir text)
      (mc/settle (assoc ctx :dir dir :malformed [re says])))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (let [[re says] (:malformed ctx)]
      (refused! ctx 1 re says)
      (says! ctx #"verdicts\.edn:\d+: " "the verdicts file and line"))))

;; ---------------------------------------------------------------------------
;; The instrument refuses to record what it did not measure

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx]
    (let [dir (with-project ctx {})]
      (write-verdicts! dir "[]\n")
      (mc/settle (assoc ctx :dir dir)))))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx]
    (refused! ctx 2 #"grading did not happen" "that the grading did not happen")))

(defstep #"a verdicts file grading (\d+) of them, all passed"
  (fn [ctx n]
    (let [dir (with-project ctx {})
          graded (take (parse-long n) (:items ctx))]
      (write-verdicts! dir (pr-str (vec (for [id graded] {:id id :verdict :pass :evidence (str "graded " id)}))))
      (mc/settle (assoc ctx :dir dir :ungraded (vec (drop (parse-long n) (:items ctx))))))))

(defstep #"the score is (\d+) of (\d+), and the ungraded item is recorded as failed"
  (fn [ctx passed scored]
    (exits! ctx 0)
    (let [sc (score ctx)
          by-id (into {} (map (juxt :id identity) (:items sc)))]
      (s/check! (= [(parse-long passed) (parse-long scored)] [(:passed sc) (:scored sc)])
                (str "the score is " (:passed sc) " of " (:scored sc)))
      (s/check! (= (parse-long scored) (count (:items sc))) (str (count (:items sc)) " items recorded"))
      (s/check! (seq (:ungraded ctx)) "the fixture left no item ungraded")
      (doseq [id (:ungraded ctx)]
        (s/check! (= :fail (:verdict (by-id id))) (str "the ungraded item " id " is recorded as " (pr-str (by-id id))))))))

(defstep #"a verdicts file grading (\S+), which is not on cycle 03's checklist"
  (fn [ctx id]
    (let [dir (with-project ctx {})]
      (write-verdicts! dir (pr-str [graded-pass graded-fail {:id id :verdict :pass :evidence "graded anyway"}]))
      (mc/settle (assoc ctx :dir dir)))))

(defstep #"it exits 1, writes nothing, and names (spec-\d+)"
  (fn [ctx id]
    (refused! ctx 1 (re-pattern id) id)))

;; ---------------------------------------------------------------------------
;; The builder neither grades nor reads its own score

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx]
    (mc/settle (assoc ctx :dir (scorable! {:cycle {:builder {:name "builder-a" :actor :agent}
                                                   :grader {:name "grader-b" :actor :agent}
                                                   :spec-owner {:name "Sami" :actor :human}}})))))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (exits! ctx 0)
    (let [sc (score ctx)]
      (s/check! (= {:builder {:name "builder-a" :actor :agent}
                    :grader {:name "grader-b" :actor :agent}
                    :spec-owner {:name "Sami" :actor :human}}
                   (select-keys sc [:builder :grader :spec-owner]))
                (str "score.edn's roles: " (pr-str (select-keys sc [:builder :grader :spec-owner])))))))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx]
    (mc/settle (assoc ctx :dir (scorable! {:cycle {:grader {:name "builder-a" :actor :agent}}})))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx]
    (refused! ctx 1 #"builder may not grade or read its own score" "that the builder may not grade or read its own score")))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx]
    (mc/settle (assoc ctx :dir (scorable! {:cycle {:spec-owner nil}})))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx]
    (refused! ctx 1 #"spec owner|:spec-owner" "the missing spec owner")))

;; ---------------------------------------------------------------------------
;; Only a ratified build is scored

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused, or does not exist"
  (fn [ctx]
    ;; Both cases: the scenario's command runs on the refused one; the Then
    ;; runs the same command on a cycle with no ratified.edn.
    (let [absent (scorable! {:ratified nil})]
      (mc/settle (assoc ctx
                        :dir (scorable! {:ratified {:cut "spec-3" :room-tree "4b825dc" :outcome :refused
                                                    :reason "the test command bb test.clj failed with exit status 1"}})
                        :absent {:dir absent :before (mc/snapshot absent)})))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing ratification"
  (fn [ctx]
    (refused! ctx 1 #"no passing ratification" "that the cycle has no passing ratification")
    (when-let [{:keys [dir before]} (:absent ctx)]
      (let [actx {:dir dir :before before
                  :run (apply s/rdm-spec! dir (:args (run ctx)))}]
        (refused! actx 1 #"no passing ratification" "that the cycle with no ratified.edn has no passing ratification")))))
