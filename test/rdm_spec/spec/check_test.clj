(ns rdm-spec.spec.check-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(def needs "{\"UN-001\" {:text \"one\" :sources [\"a:b@1234567\"]}\n \"UN-002\" {:text \"two\" :sources []}}\n")

(defn- project [features]
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file dir "dhf/spec"))
    (spit (str (fs/file dir "dhf/spec/needs.edn")) needs)
    (doseq [[rel text] features]
      (fs/create-dirs (fs/parent (fs/file dir "dhf/spec" rel)))
      (spit (str (fs/file dir "dhf/spec" rel)) text))
    dir))

(def config {:spec-dir "dhf/spec" :id-prefix "spec" :needs "dhf/spec/needs.edn" :stack-words ["Cobalt"]})

(defn- findings [features]
  (let [dir (project features)
        spec (parser/read-spec dir config)]
    (check/check-spec spec (parser/text-files dir spec) config)))

(defn- rules [fs] (set (map :rule fs)))

(defn- feature [& scenarios]
  (str "@UN-001\nFeature: F\n  Prose.\n"
       (str/join (for [[tags name] scenarios]
                   (str "\n  " tags "\n  Scenario: " name "\n    Given a step\n")))))

(deftest a-good-scenario-has-no-finding
  (is (= ["dhf/spec/needs.edn"] (distinct (map :file (findings {"a/b.feature" (feature ["@spec-0001 @e2e" "Good"])})))))
  (is (= #{"check-need-unsourced"} (rules (findings {"a/b.feature" (feature ["@spec-0001 @e2e @wip" "Planned"])})))))

(deftest ids
  (let [fs (findings {"a/b.feature" (feature ["@e2e" "None"] ["@spec-0007 @spec-0008 @e2e" "Two"])
                      "a/c.feature" (feature ["@spec-0007 @e2e" "Again"])})
        by (group-by :rule fs)]
    (is (= [["dhf/spec/a/b.feature" 6]] (map (juxt :file :line) (by "check-no-id"))))
    (is (str/includes? (:message (first (by "check-no-id"))) "rdm-spec stamp"))
    (is (re-find #"@spec-0007 and @spec-0008" (:message (first (by "check-ids")))))
    (let [m (:message (first (by "check-duplicate-id")))]
      (is (str/includes? m "@spec-0007"))
      (is (str/includes? m "dhf/spec/a/b.feature:10"))
      (is (str/includes? m "dhf/spec/a/c.feature:6")))))

(deftest levels-and-tags
  (let [fs (findings {"a/b.feature" (feature ["@spec-0001" "No level"] ["@spec-0002 @e2e @contract" "Both"]
                                             ["@spec-0003 @e2e @persona" "Persona"] ["@spec-12 @e2e" "Short id"])})
        msgs (fn [rule] (map :message (filter #(= rule (:rule %)) fs)))]
    (is (some #(and (str/includes? % "spec-0001") (str/includes? % "no level")) (msgs "check-level")))
    (is (some #(and (str/includes? % "spec-0002") (str/includes? % "@e2e and @contract")) (msgs "check-level")))
    (is (some #(and (str/includes? % "spec-0003") (str/includes? % "@persona") (str/includes? % "@wip")
                    (str/includes? % "@UN-nnn") (str/includes? % "@spec-NNNN"))
              (msgs "check-unknown-tag")))
    (is (some #(str/includes? % "@spec-12") (msgs "check-unknown-tag")))))

(deftest tag-places
  (let [fs (findings {"a/b.feature" (str "@UN-001 @e2e\nFeature: F\n\n  @spec-0001 @wip\n  Rule: R\n\n"
                                         "    @spec-0002 @e2e\n    Scenario Outline: O <x>\n      Given <x>\n\n"
                                         "      @UN-001\n      Examples:\n        | x |\n        | a |\n")})]
    (is (= #{["check-tag-place" 2] ["check-tag-place" 5] ["check-tag-place" 12]}
           (set (map (juxt :rule :line) (filter #(= "check-tag-place" (:rule %)) fs)))))))

(deftest need-rules
  (let [fs (findings {"a/b.feature" (str "Feature: F\n\n  @spec-0001 @e2e\n  Scenario: Needless\n    Given a\n\n"
                                         "  @spec-0002 @e2e @UN-009\n  Scenario: Undeclared\n    Given a\n")})
        by (group-by :rule fs)]
    (is (str/includes? (:message (first (by "check-no-need"))) "spec-0001"))
    (is (= 1 (count (by "check-no-need"))) "UN-009 is a need tag, though undeclared")
    (is (re-find #"spec-0002.*@UN-009" (:message (first (by "check-unknown-need")))))
    (is (= [["dhf/spec/needs.edn" 2]] (map (juxt :file :line) (by "check-need-unsourced"))))
    (is (str/includes? (:message (first (by "check-need-unsourced"))) "UN-002"))))

(deftest a-feature-tag-naming-no-need-names-the-scenarios-under-it
  (let [fs (findings {"a/b.feature" (str/replace (feature ["@spec-0001 @e2e" "S"]) "@UN-001" "@UN-009")})
        f (first (filter #(= "check-unknown-need" (:rule %)) fs))]
    (is (= ["dhf/spec/a/b.feature" 2] [(:file f) (:line f)]))
    (is (re-find #"@UN-009.*spec-0001" (:message f)))))

(deftest stack-words-are-whole-words-in-any-file-of-the-spec-whatever-their-case
  (is (= [["dhf/spec/x.md" 2 "check-stack-word"]]
         (map (juxt :file :line :rule)
              (check/stack-word-findings [{:file "dhf/spec/x.md" :text "fine\nwritten in cobalt.\nCobaltish is fine"}]
                                         ["Cobalt"]))))
  (is (some #(= ["dhf/spec/a/b.feature" 3] [(:file %) (:line %)])
            (filter #(= "check-stack-word" (:rule %))
                    (findings {"a/b.feature" (str/replace (feature ["@spec-0001 @e2e" "S"]) "Prose." "Written in Cobalt.")})))))

(deftest refusals-are-findings-and-a-missing-needs-file-is-one
  (let [dir (project {"a/b.feature" "Feature: A\nFeature: B\n"})
        _ (fs/delete (fs/file dir "dhf/spec/needs.edn"))
        spec (parser/read-spec dir config)
        fs (check/check-spec spec (parser/text-files dir spec) config)]
    (is (contains? (rules fs) "gherkin-one-feature"))
    (is (contains? (rules fs) "check-needs"))))

(deftest the-stage-publishes-spec-parsed-then-spec-gated
  (let [dir (project {"a/b.feature" (feature ["@spec-0001 @e2e" "S"])})
        catalogue {:SpecParsed {:published-by :spec/parser :carries [:revision :scenarios :needs :refusals]}
                   :SpecGated {:published-by :spec/check :carries [:revision :passed? :findings]}
                   :KnowledgeIndexed {:published-by :knowledge/sources :carries [:revision]}}
        b (bus/make-bus {:catalogue catalogue})
        seen (atom [])
        req {:task "check" :root dir :config config :bus b :state (atom {})}]
    (doseq [[e h] (check/subscriptions req)] (bus/subscribe! b e :spec/check h))
    (bus/subscribe! b :SpecGated :t/t #(swap! seen conj %))
    (bus/publish! b :knowledge/sources :KnowledgeIndexed {:revision nil})
    (let [out (check/check req)]
      (is (= [:KnowledgeIndexed :SpecParsed :SpecGated] (map :event (bus/events b))))
      (is (= #{"check-need-unsourced"} (rules (:findings out))))
      (is (= {:revision :working-tree :passed? false} (select-keys (first @seen) [:revision :passed?])))
      (is (= 1 (get-in out [:result :spec :scenarios]))))))
