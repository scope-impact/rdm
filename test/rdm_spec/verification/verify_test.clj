(ns rdm-spec.verification.verify-test
  "One test per rule Verify enforces (06-regenerate/verify.feature, 08, 11, 12 and 13)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.verification.verify :as verify]))

(def sc {:id "spec-0003" :title "Three" :feature "Cart" :rule "A rule" :context :shop :component :shop/cart :component-name "Cart"
         :tags ["UN-001" "spec-0003" "e2e"] :needs ["UN-001"] :level :e2e
         :background [] :steps [{:keyword "Given" :text "a thing"} {:keyword "Then" :text "it holds"}]})

(def good {"name" "Three" "status" "passed"
           "labels" [{"name" "feature" "value" "Cart"} {"name" "story" "value" "A rule"} {"name" "epic" "value" "shop"}
                     {"name" "tag" "value" "UN-001"} {"name" "tag" "value" "spec-0003"} {"name" "tag" "value" "e2e"}
                     {"name" "spec" "value" "spec-0003"} {"name" "component" "value" "Cart"}]
           "links" [{"type" "issue" "name" "UN-001" "url" "dhf/spec/needs.edn#UN-001"}]
           "steps" [{"name" "Given a thing"} {"name" "Then it holds"}] "parameters" []})

(defn rules [r & [mapping]] (map :rule (verify/mapping-findings (or mapping (verify/default-mapping "dhf/spec/needs.edn")) sc r "f-result.json")))

(deftest verify-trusts-only-the-cut
  (is (= ["src" "test"] (verify/impl-roots {:implementation ["src" "test"]}))))

(deftest the-tests-check-passes-only-when-the-tests-pass-and-every-scenario-passed
  (is (= {:missing ["spec-0012"] :not-passed []} (verify/coverage [{:id "spec-0012"}] {"spec-0012" :unverified}))))

(deftest verify-records-what-it-verified
  (is (= #{:cut :room-tree :outcome :checkout :checks} (set (keys (verify/record "spec-3" "t" :passed "c" [] nil))))))

(deftest a-project-declares-its-checks-and-verify-runs-every-one-the-same-way
  (is (str/includes? (verify/verify-problem {:implementation ["src"]} [{:kind :renders}]) ":renders is not a kind of check"))
  (is (str/includes? (verify/verify-problem {:implementation ["src"]} nil) "declares no :verify"))
  (is (nil? (verify/verify-problem {:implementation ["src"]} [{:kind :tests :command ["x"]}]))))

(deftest a-serves-check-proves-the-app-comes-up
  (is (= [500 1000] (take 2 (verify/poll-times 10 20)))))

(deftest a-runs-check-proves-a-command-answers
  (is (= 18.75 (verify/margin 20 1.25))))

(deftest the-tests-check-holds-every-result-to-the-mapping
  (is (empty? (rules good)))
  (is (= ["mapping-differs: story"] (rules (assoc-in good ["labels" 1 "value"] "Else"))))
  (is (= ["steps-differ-from-scenario"] (rules (update good "steps" pop))))
  (is (= ["mapping-differs: links"] (rules (assoc good "links" []))))
  (is (str/includes? (verify/mapping-problem {:epic :aggregate}) ":aggregate is not an element of the spec")))

(deftest a-plugin-check-asks-the-projects-own-judge
  (is (str/includes? (verify/verify-problem {:implementation ["src"] :plugins []} [{:kind :tests :command ["x"]} {:kind :plugin :name "judge"}])
                     "names no plugin answering :Check")))

(deftest the-rooms-implementation-reaches-the-checkout-as-committed
  (is (= ["src" "test"] (verify/impl-roots {:implementation ["src" "test"]}))))

(deftest a-spec-or-slice-scoped-verify-requires-its-scenarios
  (is (= ["a"] (map :id (verify/in-scope [{:id "a" :spec "auth"} {:id "b" :spec "infra"}] [{:spec "auth"}] nil))))
  (is (= ["a"] (map :id (verify/in-scope [{:id "a" :component :spec/parser} {:id "b" :component :x/y}] [{:slice "12"}] {"12" [:spec/parser]})))))
