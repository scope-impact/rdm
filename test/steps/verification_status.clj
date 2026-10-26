(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature and
  dhf/spec/verification/results.feature (the Results reader is read through
  `rdm-spec status`): a small project (steps.verification-support) holding
  the scenarios a step gives, its results in the configured results
  directory unless a step names another (DIR, :results).

  Status's result is printed under its own name (spec-0307):

    {:status {:results DIR :scenarios [{:id :title :file :line :status}]
              :counts {:verified :failed :unverified :planned}
              :unknown-results [{:file :spec}]}}

  The outline Givens build on the kernel runner's shared \"a stamped
  scenario outline with three example rows\" (:dir, :id, rows n = 1, 2, 3).

  Context keys set here: :dir, :id (the scenario a Then is about), :results
  (a results directory outside the project), :unknown-file (the file of the
  result naming spec-0099), :unreadable (the file that is not JSON), :ids."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- status-out
  "The last run's {:status ..}, exit 0."
  [ctx]
  (let [r (kc/exits! ctx 0)
        out (:status (kc/stdout-edn r))]
    (s/check! (map? out) (str "standard output holds no {:status ..}\n" (s/show r)))
    out))

(defn- sc [id & {:as more}] (merge {:id id :title (str "Scenario " id)} more))

(defn- rd [ctx] (v/results-dir (:dir ctx)))

;; ---------------------------------------------------------------------------
;; The result's shape (spec-0307)

(def ^:private b-feature
  (str "@UN-001\n"
       "Feature: B\n"
       "  @spec-0001 @e2e\n"
       "  Scenario: One\n"
       "    Given a step\n"
       "    Then it holds\n"
       "  @spec-0002 @e2e\n"
       "  Scenario: Two\n"
       "    Given a step\n"
       "    Then it holds\n"
       "  @spec-0003 @e2e @wip\n"
       "  Scenario: Three\n"
       "    Given a step\n"
       "    Then it holds\n"))

(defstep #"a project with the stamped scenarios @spec-0001 \"One\", @spec-0002 \"Two\" and @spec-0003 \"Three\", in dhf/spec/a/b\.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip"
  (fn [ctx]
    (let [ctx (v/project! ctx {})
          dir (:dir ctx)]
      (s/write! dir "dhf/spec/model.edn"
                (pr-str (update-in v/model [:containers 0 :contexts] conj
                                   {:id :a :name "a" :description "The a context"
                                    :components [{:id :a/b :name "B" :spec "a/b" :description "B"}]})))
      (s/write! dir "dhf/spec/a/b.feature" b-feature)
      ctx)))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [d (str (fs/file (s/tmp-dir) "results"))
          _ (v/result! d "spec-0001" "passed")
          f (v/result! d "spec-0099" "passed")]
      (assoc ctx :results d :unknown-file (str d "/" (fs/file-name f))))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status \{:results DIR :scenarios \[\{:id \"spec-0001\" :title \"One\" :file \"dhf/spec/a/b\.feature\" :line 4 :status :verified\} \{:id \"spec-0002\" :title \"Two\" :file \"dhf/spec/a/b\.feature\" :line 8 :status :unverified\} \{:id \"spec-0003\" :title \"Three\" :file \"dhf/spec/a/b\.feature\" :line 12 :status :planned\}\] :counts \{:verified 1 :failed 0 :unverified 1 :planned 1\} :unknown-results \[\{:file F :spec \"spec-0099\"\}\]\}\}, F the name of that result's file within DIR, DIR as given"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          f "dhf/spec/a/b.feature"
          want {:status {:results (:results ctx)
                         :scenarios [{:id "spec-0001" :title "One" :file f :line 4 :status :verified}
                                     {:id "spec-0002" :title "Two" :file f :line 8 :status :unverified}
                                     {:id "spec-0003" :title "Three" :file f :line 12 :status :planned}]
                         :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                         :unknown-results [{:file (:unknown-file ctx) :spec "spec-0099"}]}}
          got (kc/stdout-edn r)]
      (s/check! (= want got) (str "standard output is\n" (pr-str got) "\nnot\n" (pr-str want) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [ctx]
    (-> (v/one-feature! ctx [(sc "spec-0001" :wip? true) (sc "spec-0002")])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (-> (v/one-feature! ctx [(sc "spec-0001" :wip? true) (sc "spec-0002")])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (v/project-results! ctx [[(:id ctx) "skipped"] [(:id ctx) "passed"]])))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (status-out ctx) (v/status-of! ctx (:id ctx) :planned)))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx]
    (let [out (status-out ctx)]
      (v/status-of! ctx (:id ctx) :planned)
      (s/check! (= 1 (count (filter #(= (:id ctx) (:id %)) (:scenarios out)))) "the scenario is listed more than once")
      nil)))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [(sc "spec-0001") (sc "spec-0002") (sc "spec-0003")])
        (v/project-results! [["spec-0001" "passed"] ["spec-0002" "failed"]]))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (status-out ctx)
    (s/check! (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified} (v/statuses ctx))
              (str "the statuses are " (pr-str (v/statuses ctx))))
    nil))

;; ---------------------------------------------------------------------------
;; Outlines

(defn- row-result! [ctx n status]
  (v/write-result! (rd ctx) (assoc (v/result-map (:id ctx) status)
                                   :name (str "An outline over " n " [n=" n "]")
                                   :parameters [{:name "n" :value (str n)}])))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (row-result! ctx 1 "passed") (row-result! ctx 2 "passed") (row-result! ctx 3 "failed")
    ctx))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx]
    (dotimes [_ 3] (v/result! (rd ctx) (:id ctx) "passed"))
    ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx]
    (row-result! ctx 1 "passed") (row-result! ctx 2 "passed")
    ctx))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx st]
    (status-out ctx)
    (v/status-of! ctx (:id ctx) (keyword st))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle

(defstep #"a stamped scenario @(spec-\d+)"
  (fn [ctx id] (assoc (v/one-feature! ctx [(sc id)]) :id id)))

(defstep #"an Allure result labelled spec=(\S+) with status (\S+)"
  (fn [ctx id st] (v/project-results! ctx [[id st]])))

(defstep #"two Allure results labelled spec=(\S+), one passed and one failed"
  (fn [ctx id] (v/project-results! ctx [[id "passed"] [id "failed"]])))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [ids (mapv #(format "spec-%04d" %) (range 1 11))]
      (assoc (v/one-feature! ctx (mapv sc ids)) :ids ids))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (v/project-results! ctx (for [id (butlast (:ids ctx))] [id "passed"]))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (= 10 (count (:scenarios out))) (str "the report lists " (count (:scenarios out)) " scenarios"))
      (s/check! (= 10 (reduce + (vals (:counts out)))) (str "the report counts " (pr-str (:counts out))))
      nil)))

(defstep #"the tenth is unverified"
  (fn [ctx] (v/status-of! ctx (last (:ids ctx)) :unverified)))

;; ---------------------------------------------------------------------------
;; dhf/spec/verification/results.feature

(defn- with-one-passing [ctx]
  (-> (v/one-feature! ctx [(sc "spec-0001")])
      (v/project-results! [["spec-0001" "passed"]])
      (assoc :id "spec-0001")))

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [ctx]
    (-> (with-one-passing ctx) (v/project-results! [["spec-9999" "passed"]]))))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx]
    (s/check! (not-any? #(str/includes? (slurp (str %)) "@spec-9999") (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature"))
              "a feature file holds @spec-9999")
    nil))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (some #(= "spec-9999" (:spec %)) (:unknown-results out))
                (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (= ["spec-0001"] (map :id (:scenarios out))) (str "the report lists " (pr-str (map :id (:scenarios out)))))
      (s/check! (= {:verified 1 :failed 0 :unverified 0 :planned 0} (:counts out)) (str "the counts are " (pr-str (:counts out))))
      nil)))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (let [ctx (with-one-passing ctx)]
      (v/result! (rd ctx) nil "failed")
      ctx)))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (= [] (:unknown-results out)) (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [(sc "spec-0001")])]
      (fs/create-dirs (rd ctx))
      (s/write! (rd ctx) "a-container.json" "{}\n")
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (kc/refused-naming! ctx "no test run wrote results there") nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (with-one-passing ctx)
          f (s/write! (rd ctx) "broken-result.json" "{\"status\": \"passed\", \"labels\": [")]
      (assoc ctx :unreadable (str (fs/file-name f))))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:unreadable ctx)) (s/err-lines r))]
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:unreadable ctx) "\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls)))
      nil)))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [(sc "spec-0001") (sc "spec-0002")])]
      (doseq [[id st] [["spec-0001" "passed"] ["spec-0002" "failed"]]]
        (v/write-result! (rd ctx)
                         {:uuid (str (random-uuid)) :historyId "a1b2" :fullName (str "suite > " id)
                          :name (str "verifies " id) :status st :stage "finished" :start 1 :stop 2
                          :labels [{:name "language" :value "javascript"} {:name "framework" :value "jest"}
                                   {:name "spec" :value id} {:name "suite" :value "demo.test.js"}]
                          :steps [] :attachments []}))
      (assoc ctx :expected {"spec-0001" :verified "spec-0002" :failed}))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (status-out ctx)
    (s/check! (= (:expected ctx) (v/statuses ctx)) (str "the statuses are " (pr-str (v/statuses ctx))))
    nil))
