(ns rdm-spec.verification.verify-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.verify :as verify]))

(def outline
  "@UN-001\nFeature: F\n  @spec-0001 @e2e\n  Scenario Outline: A title with no placeholder\n    Given <x>\n\n    Examples:\n      | x |\n      | 1 |\n")

(deftest an-outline-whose-title-holds-no-placeholder-is-held-to-the-mapping
  (let [sc (assoc (first (:scenarios (parser/parse-feature outline "f.feature"))) :id "spec-0001" :needs ["UN-001"] :level "e2e")
        r {:file "r.json" :spec "spec-0001" :status :passed :parameters {"x" "1"}
           :raw {"name" "A title with no placeholder" "steps" [{"name" "Given 1"}]
                 "labels" [{"name" "spec" "value" "spec-0001"}]
                 "links" [{"type" "issue" "url" "n#UN-001"}]}}]
    (is (empty? (verify/mapping-findings {:name :scenario :step :gherkin-step :links {:need {:type "issue" :url "N#{id}"}}} sc r "n")))))
