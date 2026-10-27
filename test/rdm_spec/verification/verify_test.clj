(ns rdm-spec.verification.verify-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.verify :as verify]))

(def scenario
  {:id "spec-0003" :title "Lint names the view" :file "dhf/spec/architecture/lint.feature" :line 8
   :feature "Lint" :rule "Every view is described" :context :architecture :component :architecture/lint
   :component-name "Lint" :tags ["spec-0003" "e2e"] :all-tags ["UN-001" "spec-0003" "e2e"] :level :e2e
   :needs ["UN-001"] :steps [{:keyword "Given" :text "a view"} {:keyword "Then" :text "it is named"}]})

(def result
  {"name" "Lint names the view" "status" "passed"
   "steps" [{"name" "Given a view"} {"name" "Then it is named"}]
   "labels" [{"name" "epic" "value" "architecture"} {"name" "feature" "value" "Lint"}
             {"name" "story" "value" "Every view is described"} {"name" "spec" "value" "spec-0003"}
             {"name" "component" "value" "Lint"} {"name" "tag" "value" "spec-0003"} {"name" "tag" "value" "e2e"}]
   "links" [{"type" "issue" "name" "UN-001" "url" "dhf/spec/needs.edn#UN-001"}]})

(defn- rules [m r]
  (map (juxt :rule #(re-find #"^\S+" (:message %)))
       (verify/mapping-findings (merge verify/default-mapping m) scenario nil r "f.json" "{}" "dhf/spec/needs.edn")))

(deftest a-result-that-follows-the-mapping-has-no-finding
  (is (empty? (rules {} result))))

(deftest a-result-that-breaks-the-mapping-names-the-element
  (is (= ["steps-differ-from-scenario"] (map first (rules {} (assoc-in result ["steps" 1 "name"] "Then x")))))
  (is (= [["mapping-differs" "story:"]] (rules {} (assoc result "labels" (remove #(= "story" (get % "name")) (get result "labels"))))))
  (is (= [["mapping-differs" "links:"]] (rules {} (assoc result "links" []))))
  (is (= [["evidence-missing" "browser-trace:"]] (rules {:evidence {:e2e ["browser-trace"]}} result))))

(deftest a-mapping-verify-cannot-read-is-refused
  (is (nil? (verify/mapping-problem verify/default-mapping)))
  (is (re-find #":aggregate is not an element of the spec" (verify/mapping-problem (assoc verify/default-mapping :epic :aggregate))))
  (is (re-find #":colour is not an element of an Allure result" (verify/mapping-problem (assoc verify/default-mapping :colour :context))))
  (is (re-find #"the label spec" (verify/mapping-problem (assoc verify/default-mapping :labels {"component" :component})))))

(deftest a-need-link-s-url-is-the-needs-file-and-the-id
  (is (= "dhf/needs.edn#UN-001" (verify/link-url "N#{id}" "dhf/needs.edn" "UN-001"))))
