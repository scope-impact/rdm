(ns rdm-spec.measurement.checklist-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

(defn- refusal [f] (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(def scs
  [{:id "spec-0100" :title "C" :level :e2e :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}
   {:id "spec-0012" :title "A" :level :contract :context :verification :component :verification/status
    :context-name "verification" :component-name "Status"}
   {:id "spec-0013" :title "B" :level :e2e :wip? true :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}])

(def clauses {:clauses [{:key "STD:5.1" :description "Plan" :clause-list "dhf/clauses/std.txt" :line 1 :named-at []}]})

(deftest ids-in-natural-order
  (is (= ["STD:5.1" "STD:5.1.a" "STD:10.1" "spec-0012" "spec-0100"]
         (checklist/id-order ["STD:10.1" "spec-0100" "STD:5.1.a" "spec-0012" "STD:5.1"]))))

(deftest deriving
  (testing "items by id, then the clauses; planned apart"
    (let [{:keys [checklist findings]} (checklist/derive-checklist "03" "spec-3" scs clauses :project [])]
      (is (empty? findings))
      (is (= ["cycle" "cut" "items" "planned"] (keys checklist)))
      (is (= ["spec-0012" "spec-0100" "STD:5.1"] (map #(get % "id") (get checklist "items"))))
      (is (= {"id" "STD:5.1" "title" "Plan" "context" "compliance" "component" "dhf/clauses/std.txt" "level" "clause"}
             (last (get checklist "items"))))
      (is (= ["id" "title" "context" "component" "level"] (keys (first (get checklist "items")))))
      (is (= [{"id" "spec-0013" "title" "B"}] (get checklist "planned")))))
  (testing "a scope keeps its items; clauses only with the compliance context"
    (let [items (fn [parts] (map #(get % "id") (get-in (checklist/derive-checklist "03" "spec-3" scs clauses parts [])
                                                        [:checklist "items"])))]
      (is (= ["spec-0012"] (items [{:context :verification}])))
      (is (= ["spec-0100"] (items [{:component :architecture/lint}])))
      (is (= ["STD:5.1"] (items [{:context :compliance}])))))
  (testing "an unstamped scenario stops it"
    (let [{:keys [checklist findings]} (checklist/derive-checklist "03" "spec-3" (conj scs {:title "X" :file "f" :line 9}) nil :project [])]
      (is (nil? checklist))
      (is (= [["f" 9 "scenario-unstamped"]] (map (juxt :file :line :rule) findings))))))

(deftest the-stage
  (let [root (str (fs/create-temp-dir))
        req (fn [] {:root root :task "checklist" :args ["03"] :config {:cycles-dir "dhf/cycles"} :state (atom {})})]
    (try
      (is (re-find #"no dhf/cycles/03/cycle\.edn" (refusal #(checklist/checklist (req)))))
      (fs/create-dirs (fs/file root "dhf/cycles/03"))
      (spit (str (fs/file root "dhf/cycles/03/cycle.edn")) (pr-str {:cut "spec-3" :scope {:context :verification}}))
      (is (re-find #"not a commit" (refusal #(checklist/checklist (req)))) "no SpecParsed: the cut is not held")
      (let [r (req)]
        (doseq [[e h] (checklist/subscriptions r)]
          (h (if (= :SpecParsed e) {:scenarios scs :refusals []} clauses)))
        (let [{:keys [result findings]} (checklist/checklist r)
              written (json/parse-string (slurp (str (fs/file root "dhf/cycles/03/checklist.json"))))]
          (is (empty? findings))
          (is (= 1 (get-in result [:checklist :items])))
          (is (= ["spec-0012"] (map #(get % "id") (get written "items"))))))
      (spit (str (fs/file root "dhf/cycles/03/cycle.edn")) (pr-str {:cut "spec-3" :scope "everything"}))
      (is (re-find #"scope" (refusal #(checklist/checklist (req)))))
      (finally (fs/delete-tree root)))))
