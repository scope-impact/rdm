(ns rdm-spec.measurement.oracle-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest differences-name-the-item
  (is (empty? (oracle/differences {:a 1} {:a 1})))
  (is (= [{:path [:items {:item "b"} :verdict] :golden :pass :actual :fail}]
         (oracle/differences {:items [{:id "a" :verdict :pass} {:id "b" :verdict :pass}]}
                             {:items [{:id "a" :verdict :pass} {:id "b" :verdict :fail}]})))
  (testing "JSON items are aligned by id too, an item missing on one side"
    (is (= [{:path ["items" {:item "c"}] :golden {"id" "c"} :actual :rdm-spec.measurement.oracle/absent}]
           (oracle/differences {"items" [{"id" "a"} {"id" "c"}]} {"items" [{"id" "a"}]}))))
  (is (= [{:path [:statements 0] :golden "x" :actual "y"}] (oracle/differences {:statements ["x"]} {:statements ["y"]}))))

(defn- req [root & {:as m}] (merge {:root root :task "oracle" :args [] :opts {} :config {}} m))

(deftest no-oracle-passes
  (let [root (str (fs/create-temp-dir))]
    (try
      (let [{:keys [result findings]} (oracle/oracle (req root))]
        (is (empty? findings))
        (is (re-find #"no oracle to run" (get-in result [:oracle :statement]))))
      (testing "as a gate it adds nothing to the task's result"
        (is (= {} (:result (oracle/oracle (req root :task "fence"))))))
      (testing "recording needs a reason, before anything else"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a reason is required" (oracle/oracle (req root :args ["record"]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no oracle to record"
                              (oracle/oracle (req root :args ["record"] :opts {:reason "r"})))))
      (testing "an unknown argument cannot run"
        (is (thrown? clojure.lang.ExceptionInfo (oracle/oracle (req root :args ["frob"])))))
      (finally (fs/delete-tree root)))))

(deftest goldens-are-named-from-the-fixture-cycles
  (let [root (str (fs/create-temp-dir))]
    (try
      (doseq [c ["01" "02" "03"]]
        (fs/create-dirs (fs/file root oracle/oracle-dir "cycles" c))
        (spit (str (fs/file root oracle/oracle-dir "cycles" c "cycle.edn")) (pr-str {:cut "spec-1"}))
        (when-not (= "03" c) (spit (str (fs/file root oracle/oracle-dir "cycles" c "verdicts.edn")) "[]")))
      (let [{:keys [cycles findings]} (oracle/fixture-cycles root)
            gs (oracle/golden-names root cycles)]
        (is (empty? findings))
        (is (= ["goldens/01/checklist.json" "goldens/01/score.edn" "goldens/02/checklist.json" "goldens/02/score.edn"
                "goldens/03/checklist.json" "goldens/scoreboard-01-02.edn"]
               (map #(subs (:rel %) (inc (count oracle/oracle-dir))) gs))))
      (testing "an unreadable fixture cycle is a finding"
        (spit (str (fs/file root oracle/oracle-dir "cycles" "03" "cycle.edn")) "{:cut")
        (is (= ["oracle-fixture"] (map :rule (:findings (oracle/fixture-cycles root))))))
      (finally (fs/delete-tree root)))))

(deftest record-then-judge
  (let [root (str (fs/create-temp-dir))
        o (fn [& parts] (apply fs/file root oracle/oracle-dir parts))
        w (fn [f text] (fs/create-dirs (fs/parent f)) (spit (str f) text))]
    (try
      (w (o "project/dhf/spec/model.edn") (slurp (str (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
                                                        fs/parent fs/parent fs/parent fs/parent (fs/file "dhf/spec/model.edn")))))
      (w (o "project/dhf/spec/architecture/lint.feature")
         (str "@UN-005\nFeature: Lint\n\n  @spec-0012 @e2e\n  Scenario: One\n    Given a step\n"
              "\n  @spec-0013 @e2e @wip\n  Scenario: Two\n    Given a step\n"))
      (doseq [[c v] [["01" :fail] ["02" :pass]]]
        (w (o "cycles" c "cycle.edn") (pr-str {:cut "spec-1" :scope :project :builder {:name "b" :actor :agent}
                                               :grader {:name "g" :actor :agent} :spec-owner {:name "s" :actor :human}}))
        (w (o "cycles" c "ratified.edn") (pr-str {:cut "spec-1" :room-tree "t" :outcome :passed}))
        (w (o "cycles" c "verdicts.edn") (pr-str [{:id "spec-0012" :verdict v :evidence "e"}])))
      (let [config {:spec-dir "dhf/spec" :model "dhf/spec/model.edn" :cycles-dir "dhf/cycles" :id-prefix "spec"}
            req {:root root :task "oracle" :args [] :opts {} :config config}]
        (testing "recording writes every missing golden and says why"
          (let [{:keys [result findings]} (oracle/oracle (assoc req :args ["record"] :opts {:reason "first"}))]
            (is (empty? findings))
            (is (= 5 (count (get-in result [:oracle :recorded]))))
            (is (re-find #"first" (slurp (str (o "RECORDED.md")))))))
        (testing "then the oracle passes"
          (is (empty? (:findings (oracle/oracle req))))
          (is (= 1 (:better (clojure.edn/read-string (slurp (str (o "goldens/scoreboard-01-02.edn"))))))))
        (testing "a golden the instrument does not reproduce is named"
          (w (o "goldens/01/score.edn") (pr-str (assoc (clojure.edn/read-string (slurp (str (o "goldens/01/score.edn")))) :passed 7)))
          (let [fs* (:findings (oracle/oracle req))]
            (is (= [["dhf/harness/oracle/goldens/01/score.edn" "golden-differs"]] (map (juxt :file :rule) fs*)))
            (is (re-find #":passed: the golden says 7, the instrument gives 0" (:message (first fs*)))))))
      (finally (fs/delete-tree root)))))
