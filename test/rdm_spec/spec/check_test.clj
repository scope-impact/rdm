(ns rdm-spec.spec.check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn- spec-of [files]
  (let [fs* (for [[rel text] files] (assoc (parser/parse-text rel text {:prefix "spec"}) :file rel))]
    {:scenarios (vec (mapcat :scenarios fs*))
     :refusals (vec (mapcat :refusals fs*))
     :features (mapv #(select-keys % [:file :feature :examples-tags]) fs*)
     :files (vec (for [[rel text] files] {:file rel :text text}))
     :needs [{:id "UN-001" :text "a" :sources ["s:p@1234"]}]
     :needs-findings [] :needs-lines {"UN-001" 1}}))

(defn- rules [files & [issued words]]
  (map :rule (check/findings (spec-of files) {:issued (or issued 10)} "spec" words "dhf/spec/needs.edn")))

(defn- feature [& scenario-tags]
  (str "@UN-001\nFeature: F\n  A fixture.\n"
       (apply str (map-indexed (fn [i tags] (str "\n  " tags "\n  Scenario: S" i "\n    Given x\n")) scenario-tags))))

(deftest a-clean-spec-has-no-finding
  (is (empty? (rules {"a.feature" (feature "@spec-0001 @e2e" "@spec-0002 @contract @wip @UN-001")}))))

(deftest ids
  (is (= ["scenario-no-id"] (rules {"a.feature" (feature "@e2e")})))
  (is (= ["scenario-ids"] (rules {"a.feature" (feature "@spec-0001 @spec-0002 @e2e")})))
  (is (= ["id-duplicate" "id-duplicate"] (rules {"a.feature" (feature "@spec-0001 @e2e") "b.feature" (feature "@spec-0001 @e2e")})))
  (is (= ["id-not-issued"] (rules {"a.feature" (feature "@spec-0011 @e2e")} 10)))
  (testing "a duplicate names every file"
    (let [f (first (check/id-findings (:scenarios (spec-of {"a.feature" (feature "@spec-0001 @e2e")
                                                            "b.feature" (feature "@spec-0001 @e2e")}))
                                      10))]
      (is (str/includes? (:message f) "a.feature:"))
      (is (str/includes? (:message f) "b.feature:")))))

(deftest levels-and-tags
  (is (= ["scenario-no-level"] (rules {"a.feature" (feature "@spec-0001")})))
  (is (= ["scenario-levels"] (rules {"a.feature" (feature "@spec-0001 @e2e @contract")})))
  (is (= ["tag-unknown"] (rules {"a.feature" (feature "@spec-0001 @e2e @persona")})))
  (is (= ["tag-misplaced"] (rules {"a.feature" (str/replace (feature "@spec-0001 @e2e") "@UN-001\n" "@UN-001 @wip\n")}))))

(deftest needs
  (is (= ["scenario-no-need"] (rules {"a.feature" (str/replace (feature "@spec-0001 @e2e") "@UN-001\n" "")})))
  (is (= ["need-undeclared"] (rules {"a.feature" (feature "@spec-0001 @e2e @UN-009")})))
  (is (= ["need-no-source"]
         (map :rule (check/need-findings [] [{:id "UN-004" :sources []}] {"UN-004" 3} "n.edn" true)))))

(deftest stack-words
  (let [[f] (check/stack-word-findings "a.feature" "x\nWritten in cobalt.\nCobalts" ["Cobalt"])]
    (is (= ["a.feature" 2 "spec-stack-word"] [(:file f) (:line f) (:rule f)]))
    (is (str/includes? (:message f) "Cobalt"))))

(deftest the-ledger
  (is (= {:issued 3} (check/read-ledger "ids.edn" ";; c\n{:issued 3}")))
  (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger "ids.edn" nil)))))
  (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger "ids.edn" "{:x 1}"))))))
