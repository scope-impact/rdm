(ns rdm-spec.spec.check-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn- spec-of
  "A read spec holding the feature `text` at dhf/spec/a/b.feature."
  [text]
  (let [parsed (parser/parse-file {:id-prefix "spec" :spec-dir "dhf/spec"} "dhf/spec/a/b.feature" text)]
    {:files ["dhf/spec/a/b.feature"]
     :texts {"dhf/spec/a/b.feature" text}
     :features (if (:feature parsed) [(:feature parsed)] [])
     :scenarios (:scenarios parsed)
     :refusals (:refusals parsed)
     :needs [{:id "UN-001" :text "x" :sources ["s:p@abcd"] :line 1}]
     :needs-file "dhf/spec/needs.edn"
     :needs-error nil}))

(def opts {:id-prefix "spec" :issued 50 :ledger-file "dhf/spec/ids.edn" :stack-words ["Cobalt"]})

(defn- rules [findings] (set (map :rule findings)))

(defn- feature [tags & more]
  (str "@UN-001\nFeature: B\n  About b.\n\n" (when tags (str "  " tags "\n"))
       "  Scenario: X\n    Given a\n" (str/join more)))

(deftest a-good-scenario-has-no-finding
  (is (= [] (check/spec-findings (spec-of (feature "@spec-0007 @e2e")) opts)))
  (is (= [] (check/spec-findings (spec-of (feature "@spec-0007 @contract @wip @UN-001")) opts))))

(deftest ids
  (testing "none: names the line and says to stamp"
    (let [[f :as fs] (check/spec-findings (spec-of (feature "@e2e")) opts)]
      (is (= ["scenario-no-id"] (map :rule fs)))
      (is (= ["dhf/spec/a/b.feature" 6] [(:file f) (:line f)]))
      (is (str/includes? (:message f) "rdm-spec stamp"))))
  (testing "two: names the scenario and both ids"
    (let [[f] (check/spec-findings (spec-of (feature "@spec-0007 @spec-0008 @e2e")) opts)]
      (is (= "scenario-ids" (:rule f)))
      (is (every? #(str/includes? (:message f) %) ["\"X\"" "spec-0007" "spec-0008"]))))
  (testing "twice in the spec: names both places and the id"
    (let [fs (check/spec-findings (spec-of (feature "@spec-0007 @e2e" "\n  @spec-0007 @e2e\n  Scenario: Y\n    Given b\n")) opts)]
      (is (= ["id-duplicate"] (map :rule fs)))
      (is (= 10 (:line (first fs))))
      (is (every? #(str/includes? (:message (first fs)) %) ["spec-0007" "dhf/spec/a/b.feature:6" "dhf/spec/a/b.feature:10"]))))
  (testing "above the ledger"
    (let [[f] (check/spec-findings (spec-of (feature "@spec-0051 @e2e")) opts)]
      (is (= "id-not-issued" (:rule f)))
      (is (str/includes? (:message f) "spec-0051"))
      (is (str/includes? (:message f) "never issued"))))
  (testing "no ledger read: no id is judged against it"
    (is (= [] (check/spec-findings (spec-of (feature "@spec-0051 @e2e")) (dissoc opts :issued))))))

(deftest levels
  (let [[f] (check/spec-findings (spec-of (feature "@spec-0007")) opts)]
    (is (= "scenario-no-level" (:rule f)))
    (is (every? #(str/includes? (:message f) %) ["spec-0007" "@e2e" "@contract"])))
  (let [[f] (check/spec-findings (spec-of (feature "@spec-0007 @e2e @contract")) opts)]
    (is (= "scenario-levels" (:rule f)))
    (is (every? #(str/includes? (:message f) %) ["spec-0007" "@e2e" "@contract"]))))

(deftest tags
  (let [[f] (check/spec-findings (spec-of (feature "@spec-0007 @e2e @persona")) opts)]
    (is (= "tag-unknown" (:rule f)))
    (is (every? #(str/includes? (:message f) %) ["spec-0007" "@persona" "@e2e" "@contract" "@UN-" "@wip" "@spec-"])))
  (testing "an id with another prefix is no id"
    (is (contains? (rules (check/spec-findings (spec-of (feature "@spec-0007 @e2e @loyly-0001")) opts)) "tag-unknown")))
  (testing "a level on the feature is misplaced"
    (let [fs (check/spec-findings (spec-of (str "@e2e " (feature "@spec-0007 @e2e"))) opts)]
      (is (= ["tag-misplaced"] (map :rule fs)))
      (is (= 2 (:line (first fs)))))))

(deftest needs
  (testing "none on the scenario, its rule or its feature"
    (let [text (str/replace (feature "@spec-0007 @e2e") "@UN-001\n" "")
          [f] (check/spec-findings (spec-of text) opts)]
      (is (= "scenario-no-need" (:rule f)))
      (is (str/includes? (:message f) "spec-0007"))))
  (testing "one nobody declared"
    (let [[f] (check/spec-findings (spec-of (feature "@spec-0007 @e2e @UN-009")) opts)]
      (is (= "need-undeclared" (:rule f)))
      (is (every? #(str/includes? (:message f) %) ["UN-009" "spec-0007"]))))
  (testing "a need citing no source"
    (let [spec (assoc (spec-of (feature "@spec-0007 @e2e")) :needs [{:id "UN-001" :sources [] :line 4}])
          [f] (check/spec-findings spec opts)]
      (is (= ["need-no-source" "dhf/spec/needs.edn" 4] [(:rule f) (:file f) (:line f)]))
      (is (str/includes? (:message f) "UN-001"))))
  (testing "a needs file that cannot be read is one finding, and no scenario's need is judged"
    (let [spec (assoc (spec-of (feature "@spec-0007 @e2e @UN-009")) :needs [] :needs-error "the needs file x does not exist")]
      (is (= ["needs-unreadable"] (map :rule (check/spec-findings spec opts)))))))

(deftest stack-words
  (let [text (feature "@spec-0007 @e2e")
        text (str/replace text "About b." "About b, written in cobalt.")
        [f] (check/spec-findings (spec-of text) opts)]
    (is (= ["spec-stack-word" "dhf/spec/a/b.feature" 3] [(:rule f) (:file f) (:line f)]))
    (is (str/includes? (:message f) "Cobalt")))
  (testing "a whole word only"
    (is (= [] (check/stack-word-findings "f" "Cobalts and xCobalt" ["Cobalt"])))))

(deftest parser-refusals-are-relayed
  (let [fs (check/spec-findings (spec-of (feature "@spec-0007 @e2e" "\n  Scenario Template: Z\n")) opts)]
    (is (some #(= "dhf/spec/a/b.feature" (:file %)) fs))
    (is (seq fs))))

(deftest the-ledger
  (let [dir (str (fs/create-temp-dir))]
    (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger dir "ids.edn")))))
    (spit (str (fs/file dir "ids.edn")) "{:issued")
    (is (= "ledger-unreadable" (:rule (:finding (check/read-ledger dir "ids.edn")))))
    (spit (str (fs/file dir "ids.edn")) ";; c\n{:issued 12}\n")
    (is (= {:issued 12} (check/read-ledger dir "ids.edn")))))

(deftest the-stage-publishes-SpecParsed-then-SpecGated
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file dir "dhf/spec/a"))
    (spit (str (fs/file dir "dhf/spec/ids.edn")) "{:issued 9}")
    (spit (str (fs/file dir "dhf/spec/needs.edn")) "{\"UN-001\" {:text \"x\" :sources [\"s:p@abcd\"]}}")
    (spit (str (fs/file dir "dhf/spec/a/b.feature")) (feature "@spec-0007"))
    (let [b (bus/make-bus {:catalogue nil})
          req {:root dir :bus b :state (atom {})
               :config {:spec-dir "dhf/spec" :id-prefix "spec" :ledger "dhf/spec/ids.edn"
                        :needs "dhf/spec/needs.edn" :stack-words []}}
          out (check/check req)]
      (is (= ["scenario-no-level"] (map :rule (:findings out))))
      (is (false? (get-in out [:result :spec-check :passed?])))
      (is (= [:SpecParsed :SpecGated] (map :event (bus/events b)))))))
