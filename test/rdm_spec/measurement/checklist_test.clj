(ns rdm-spec.measurement.checklist-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

(defn- sc [id & {:as more}]
  (merge {:id id :ids [id] :title (str "Title " id) :file "dhf/spec/a/b.feature" :line 3 :level :e2e
          :context :architecture :component :architecture/lint :context-name "architecture" :component-name "Lint"}
         more))

(deftest a-scope-holds-its-scenarios
  (let [a (sc "spec-0001")
        k (sc "spec-0002" :context :kernel :component :kernel/bus)]
    (is (checklist/in-scope? :project a))
    (is (checklist/in-scope? {:context :architecture} a))
    (is (not (checklist/in-scope? {:context :architecture} k)))
    (is (checklist/in-scope? {:context :kernel} k))
    (is (checklist/in-scope? {:component :kernel/bus} k))
    (is (checklist/in-scope? [{:context :knowledge} {:component :kernel/bus}] k))
    (is (not (checklist/in-scope? [{:context :knowledge}] a))))
  (doseq [bad [:everything {:context "architecture"} {:module :x} [] [:project]]]
    (is (command/cannot-run? (try (checklist/check-scope! bad) nil (catch Exception e e))) (pr-str bad))))

(deftest the-checklist-is-one-item-per-stamped-scenario
  (let [{:keys [items planned]} (checklist/checklist-of :project [(sc "spec-0012") (sc "spec-0003" :level :contract)
                                                                  (sc "spec-0005" :wip? true)]
                                                        nil)]
    (is (= ["spec-0003" "spec-0012"] (map #(get % "id") items)))
    (is (= ["id" "title" "context" "component" "level"] (keys (first items))))
    (is (= {"id" "spec-0003" "title" "Title spec-0003" "context" "architecture" "component" "Lint" "level" "contract"}
           (first items)))
    (is (= [{"id" "spec-0005" "title" "Title spec-0005"}] planned))))

(deftest clauses-come-after-the-scenarios
  (let [clauses [{:key "STD:4.1" :description "Quality" :clause-list "dhf/clauses/base.txt"}
                 {:key "STD:5.1" :description "Plan" :clause-list "dhf/clauses/std.txt"}]
        {:keys [items]} (checklist/checklist-of :project [(sc "spec-0001")] clauses)]
    (is (= ["spec-0001" "STD:4.1" "STD:5.1"] (map #(get % "id") items)))
    (is (= {"id" "STD:5.1" "title" "Plan" "context" "compliance" "component" "dhf/clauses/std.txt" "level" "clause"}
           (last items))))
  (testing "a scope away from compliance carries no clause"
    (is (= ["spec-0001"] (map #(get % "id") (:items (checklist/checklist-of {:context :architecture} [(sc "spec-0001")]
                                                                             [{:key "STD:5.1" :description "P" :clause-list "c"}])))))))

(deftest an-unstamped-scenario-stops-the-checklist
  (let [fs* (checklist/stops [(sc "spec-0001") (sc nil :ids []) (sc nil :ids ["spec-0002" "spec-0003"] :line 9)] [])]
    (is (= ["checklist-unstamped" "checklist-ids"] (map :rule fs*)))
    (is (str/includes? (command/format-finding (first fs*)) "dhf/spec/a/b.feature:3:")))
  (is (= ["checklist-empty"] (map :rule (checklist/stops [] [])))))
