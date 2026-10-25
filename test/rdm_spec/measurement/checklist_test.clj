(ns rdm-spec.measurement.checklist-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def model
  {:containers [{:contexts [{:id :verification :name "verification"
                             :components [{:id :verification/status :name "Status"}]}
                            {:id :spec :name "spec" :components [{:id :spec/parser :name "Parser"}]}]}]})

(deftest scopes
  (testing "the three accepted shapes"
    (doseq [s [nil :project {:context :verification} {:component :spec/parser}]]
      (is (nil? (checklist/scope-finding "c.edn" s model)) (pr-str s))))
  (testing "anything else, or a name the model does not hold"
    (doseq [s [:everything {:context "verification"} {:component :parser} {:context :nowhere}
               {:component :spec/nothing} {:context :spec :component :spec/parser}]]
      (is (= "cycle-scope" (:rule (checklist/scope-finding "c.edn" s model))) (pr-str s)))))

(deftest checklist-json-shape
  (let [scs [{:id "spec-0013" :title "B" :context "architecture" :component "Lint" :level "e2e" :wip? true}
             {:id "spec-0014" :title "C" :context "architecture" :component "Lint" :level "contract"}
             {:id "spec-0012" :title "A" :context "architecture" :component "Lint" :level "e2e"}
             {:id "spec-0011" :title "P" :context "kernel" :component "Command" :level "e2e" :wip? true}]
        text (checklist/checklist-json "03" "spec-3" scs)
        data (json/parse-string text true)]
    (is (= ["spec-0012" "spec-0014"] (map :id (:items data))))
    (is (= [{:id "spec-0011" :title "P"} {:id "spec-0013" :title "B"}] (:planned data)))
    (is (apply < (map #(str/index-of text (str "\"" % "\"")) ["cycle" "cut" "items" "planned"])))
    (let [item (re-find #"\{[^{}]*\"level\"[^{}]*\}" text)]
      (is (= ["id" "title" "context" "component" "level"] (map second (re-seq #"\"(\w+)\" :" item)))
          "item keys in order"))))
