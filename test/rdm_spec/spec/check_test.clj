(ns rdm-spec.spec.check-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(def model
  (pr-str {:containers [{:id :c :contexts [{:id :alpha :name "alpha"
                                             :components [{:id :alpha/one :name "One" :spec "alpha/one"}]}]}]}))

(def needs-text
  (str ";; needs\n"
       "{\"UN-001\" {:text \"one\" :sources [\"a:b@1234567\"]}\n"
       " \"UN-002\" {:text \"two\" :sources []}}\n"))

(defn- feature [& scenarios]
  (str "@UN-001\nFeature: One\n  Prose.\n\n  Rule: R\n\n"
       (str/join "\n" (for [[tags title] scenarios]
                        (str (when tags (str "    " tags "\n")) "    Scenario: " title "\n      Given a\n")))))

(defn- project [files]
  (let [dir (str (fs/create-temp-dir {:prefix "check-test-"}))]
    (doseq [[rel text] files]
      (let [f (fs/file dir rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) text)))
    dir))

(def config {:spec-dir "dhf/spec" :id-prefix "spec" :model "dhf/spec/model.edn" :needs "dhf/spec/needs.edn"
             :ledger "dhf/spec/ids.edn"})

(defn- findings-of
  ([feature-text] (findings-of feature-text {}))
  ([feature-text opts]
   (let [dir (project {"dhf/spec/model.edn" model
                       "dhf/spec/needs.edn" needs-text
                       "dhf/spec/alpha/one.feature" feature-text})
         spec (parser/read-spec dir config)]
     (check/findings spec (merge {:id-prefix "spec" :issued 100 :ledger "dhf/spec/ids.edn"} opts)))))

(defn- rules [fs*] (set (map :rule fs*)))

(defn- of-rule [fs* rule] (filter #(= rule (:rule %)) fs*))

(deftest a-scenario-keeping-every-rule-has-no-finding-but-the-needs
  (is (= #{"need-no-source"} (rules (findings-of (feature ["@spec-0001 @e2e" "Fine"]))))))

(deftest ids
  (testing "none: names the line and says to stamp"
    (let [[f] (of-rule (findings-of (feature ["@e2e" "Unstamped"])) "scenario-no-id")]
      (is (= ["dhf/spec/alpha/one.feature" 8] [(:file f) (:line f)]))
      (is (str/includes? (:message f) "rdm-spec stamp"))))
  (testing "two"
    (let [[f] (of-rule (findings-of (feature ["@spec-0007 @spec-0008 @e2e" "Two"])) "scenario-many-ids")]
      (is (every? #(str/includes? (:message f) %) ["spec-0007" "spec-0008"]))))
  (testing "carried twice: both places named"
    (let [[f :as fs*] (of-rule (findings-of (feature ["@spec-0007 @e2e" "A"] ["@spec-0007 @e2e" "B"])) "id-duplicate")]
      (is (= 1 (count fs*)))
      (is (str/includes? (:message f) "one.feature:8"))
      (is (str/includes? (:message f) "one.feature:12"))))
  (testing "above the ledger"
    (let [[f] (of-rule (findings-of (feature ["@spec-0901 @e2e" "High"]) {:issued 238}) "id-unissued")]
      (is (str/includes? (:message f) "spec-0901"))
      (is (str/includes? (:message f) "never issued"))
      (is (str/includes? (:message f) "spec-0238"))))
  (testing "at the ledger: issued"
    (is (empty? (of-rule (findings-of (feature ["@spec-0100 @e2e" "Top"])) "id-unissued"))))
  (testing "no ledger known: not checked"
    (is (empty? (of-rule (findings-of (feature ["@spec-0901 @e2e" "High"]) {:issued nil}) "id-unissued")))))

(deftest levels-and-tags
  (is (seq (of-rule (findings-of (feature ["@spec-0001" "None"])) "scenario-no-level")))
  (let [[f] (of-rule (findings-of (feature ["@spec-0001 @e2e @contract" "Both"])) "scenario-many-levels")]
    (is (every? #(str/includes? (:message f) %) ["spec-0001" "@e2e" "@contract"])))
  (let [[f] (of-rule (findings-of (feature ["@spec-0001 @e2e @persona" "Persona"])) "tag-unknown")]
    (is (every? #(str/includes? (:message f) %) ["spec-0001" "@persona" "@spec-NNNN" "@e2e" "@contract" "@UN-nnn" "@wip"])))
  (testing "@wip is accepted on a scenario, refused on a feature"
    (is (empty? (remove #(= "need-no-source" (:rule %))
                        (findings-of (feature ["@spec-0001 @e2e @wip" "Planned"])))))
    (is (seq (of-rule (findings-of (str "@wip " (feature ["@spec-0001 @e2e" "X"]))) "tag-misplaced")))))

(deftest needs
  (testing "a feature's need covers its scenarios"
    (is (empty? (of-rule (findings-of (feature ["@spec-0001 @e2e" "X"])) "scenario-no-need"))))
  (testing "no need anywhere"
    (is (seq (of-rule (findings-of (str/replace (feature ["@spec-0001 @e2e" "X"]) "@UN-001\n" ""))
                      "scenario-no-need"))))
  (testing "an undeclared need"
    (let [[f] (of-rule (findings-of (feature ["@spec-0001 @e2e @UN-009" "X"])) "need-undeclared")]
      (is (every? #(str/includes? (:message f) %) ["UN-009" "spec-0001"]))))
  (testing "a need citing no source, at its line"
    (let [[f] (of-rule (findings-of (feature ["@spec-0001 @e2e" "X"])) "need-no-source")]
      (is (= ["dhf/spec/needs.edn" 3] [(:file f) (:line f)]))
      (is (str/includes? (:message f) "UN-002"))))
  (testing "a need whose every citation failed to resolve"
    (let [k {:citations [{:need "UN-001" :status :unresolved}]}]
      (is (seq (of-rule (findings-of (feature ["@spec-0001 @e2e" "X"]) {:knowledge k}) "need-unresolved"))))
    (let [k {:citations [{:need "UN-001" :status :unchecked}]}]
      (is (empty? (of-rule (findings-of (feature ["@spec-0001 @e2e" "X"]) {:knowledge k}) "need-unresolved"))))))

(deftest stack-words
  (let [fs* (check/stack-word-findings "f.feature" "one\nwritten in Cobalt.\ncobaltic\nCOBALT" ["Cobalt"])]
    (is (= [2 4] (map :line fs*)))
    (is (every? #(str/includes? (:message %) "Cobalt") fs*)))
  (is (= [] (vec (check/stack-word-findings "f" "anything" [])))))

(deftest the-ledger
  (let [dir (project {"ids.edn" ";; c\n{:issued 7}\n" "bad.edn" "{:issued" "none.edn" "{}"})]
    (is (= {:issued 7} (check/read-ledger dir "ids.edn")))
    (is (= {:issued 0} (check/read-ledger dir "missing.edn")))
    (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger dir "bad.edn")))))
    (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger dir "none.edn")))))))

(deftest the-stage-publishes-spec-parsed-then-spec-gated
  (let [dir (project {"dhf/spec/model.edn" model
                      "dhf/spec/needs.edn" needs-text
                      "dhf/spec/ids.edn" "{:issued 1}"
                      "dhf/spec/alpha/one.feature" (feature ["@spec-0001 @e2e" "X"])})
        b (bus/make-bus)
        req {:root dir :config config :state (atom {}) :bus b}
        out (check/check req)]
    (is (= [:SpecParsed :SpecGated] (map :event (bus/events b))))
    (is (= #{"need-no-source"} (rules (:findings out))))
    (is (false? (get-in out [:result :spec :passed?])))
    (is (= 1 (get-in out [:result :spec :scenarios])))))
