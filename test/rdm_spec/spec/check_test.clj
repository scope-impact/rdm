(ns rdm-spec.spec.check-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "check-test-"})))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(def needs-text
  "{\"UN-001\" {:text \"One\" :sources [\"loyly:README.md@17d68d2\"]}\n \"UN-002\" {:text \"Two\" :sources []}}\n")

(defn- feature [& scenarios]
  (str "@UN-001\nFeature: Demo\n  A demo.\n\n"
       (str/join "\n" (for [[name tags] scenarios]
                        (str "  " tags "\n  Scenario: " name "\n    Given a step\n")))))

(defn- spec-of
  "The spec read from a project holding `files` {rel text}."
  [files]
  (let [dir (tmp)]
    (write! dir "dhf/spec/needs.edn" needs-text)
    (doseq [[rel text] files] (write! dir rel text))
    (parser/read-spec dir {})))

(defn- rules [findings] (frequencies (map :rule findings)))

(defn- run [files & [opts]]
  (check/check-spec (spec-of files) (merge {:id-prefix "spec" :ledger "dhf/spec/ids.edn" :issued 10} opts)))

(deftest a-clean-spec-has-only-the-unsourced-need
  (is (= {"check-need-source" 1}
         (rules (run {"dhf/spec/demo/a.feature" (feature ["One" "@spec-0001 @e2e"] ["Two" "@spec-0002 @contract @wip"])})))))

(deftest ids
  (testing "two ids, no id, a duplicate and an unissued id"
    (let [fs (run {"dhf/spec/demo/a.feature" (feature ["Both" "@spec-0001 @spec-0002 @e2e"]
                                                      ["None" "@e2e"]
                                                      ["Again" "@spec-0003 @e2e"])
                   "dhf/spec/demo/b.feature" (feature ["Copy" "@spec-0003 @e2e"]
                                                      ["Far" "@spec-0901 @e2e"])})
          by (group-by :rule fs)]
      (is (= 1 (count (by "check-id-count"))))
      (is (str/includes? (:message (first (by "check-id-count"))) "@spec-0001 and @spec-0002"))
      (is (= 1 (count (by "check-id-missing"))))
      (is (str/includes? (:message (first (by "check-id-missing"))) "rdm-spec stamp"))
      (let [[d] (by "check-id-duplicate")]
        (is (= "dhf/spec/demo/b.feature" (:file d)))
        (is (str/includes? (:message d) "dhf/spec/demo/a.feature")))
      (let [[u] (by "check-id-unissued")]
        (is (str/includes? (:message u) "spec-0901"))
        (is (str/includes? (:message u) "never issued")))))
  (testing "no ledger check when issued is nil"
    (is (empty? (filter #(= "check-id-unissued" (:rule %))
                        (run {"dhf/spec/demo/a.feature" (feature ["Far" "@spec-0901 @e2e"])} {:issued nil}))))))

(deftest levels-and-tags
  (let [fs (run {"dhf/spec/demo/a.feature" (feature ["No level" "@spec-0001"]
                                                    ["Two levels" "@spec-0002 @e2e @contract"]
                                                    ["Persona" "@spec-0003 @e2e @persona"])})
        by (group-by :rule fs)]
    (is (= 2 (count (by "check-level"))))
    (is (every? #(and (str/includes? (:message %) "@e2e") (str/includes? (:message %) "@contract")) (by "check-level")))
    (let [[t] (by "check-tag")]
      (is (str/includes? (:message t) "spec-0003"))
      (is (str/includes? (:message t) "@persona"))
      (is (str/includes? (:message t) "@wip"))))
  (testing "only need tags on a feature or a rule"
    (let [fs (run {"dhf/spec/demo/a.feature"
                   "@UN-001 @wip\nFeature: Demo\n\n  @e2e\n  Rule: R\n\n    @spec-0001 @e2e\n    Scenario: S\n      Given a step\n"})]
      (is (= 2 (get (rules fs) "check-tag-placement"))))))

(deftest needs
  (let [fs (run {"dhf/spec/demo/a.feature"
                 (str "Feature: Demo\n\n  @spec-0001 @e2e\n  Scenario: Untraced\n    Given a step\n\n"
                      "  @spec-0002 @e2e @UN-009\n  Scenario: Unknown need\n    Given a step\n")})
        by (group-by :rule fs)]
    (is (= ["check-need-missing"] (map :rule (filter #(str/includes? (:message %) "spec-0001") fs))))
    (is (str/includes? (:message (first (by "check-need-unknown"))) "UN-009"))
    (let [[s] (by "check-need-source")]
      (is (= "dhf/spec/needs.edn" (:file s)))
      (is (= 2 (:line s)))
      (is (str/includes? (:message s) "UN-002")))))

(deftest stack-words
  (is (= [["dhf/spec/x.md" 2]]
         (map (juxt :file :line)
              (check/stack-word-findings [{:file "dhf/spec/x.md" :text "fine\nwritten in cobalt.\ncobaltic is not it\n"}]
                                         ["Cobalt"])))))

(deftest the-stage
  (let [dir (tmp)]
    (write! dir "dhf/spec/needs.edn" "{\"UN-001\" {:text \"One\" :sources [\"loyly:README.md@17d68d2\"]}}")
    (write! dir "dhf/spec/ids.edn" "{:issued 1}")
    (write! dir "dhf/spec/demo/a.feature" (feature ["One" "@spec-0001 @e2e"]))
    (let [b (bus/make-bus {})
          req {:root dir :config {:spec-dir "dhf/spec" :ledger "dhf/spec/ids.edn" :stack-words ["Cobalt"]}
               :bus b :state (atom {})}
          seen (atom [])]
      (bus/subscribe! b :SpecParsed :test (fn [_] (swap! seen conj :SpecParsed) nil))
      (bus/subscribe! b :SpecGated :test (fn [p] (swap! seen conj [:SpecGated (:passed? p)]) nil))
      (let [{:keys [result findings]} (check/check req)]
        (is (empty? findings))
        (is (true? (get-in result [:spec :passed?])))
        (is (= [:SpecParsed [:SpecGated true]] @seen))))))
