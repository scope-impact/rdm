(ns steps.spec-parser
  "Step definitions for dhf/spec/spec/parser.feature: `rdm-spec check` on a
  spec project (steps.spec-support) holding the feature file a step writes,
  and `rdm-spec checklist` on a cycle project whose cut and working tree
  differ.

  A cycle project here is a copy of the room (its configuration without
  :oracle) whose spec holds only the lint's feature file, a git repository
  tagged spec-3 at the cut. Its helpers are this file's own (copied, as the
  spec asks of a helper needed from elsewhere).

  Context keys set here: :dir, :file (the feature file written), :line (the
  line a Then names), :keyword (the keyword refused), :cut-ids (the ids of
  the scenarios at the cut)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as sp]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The spec is read at a revision

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- lint-text [n]
  (str "# A fixture feature file of the parser's steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (for [i (range 1 (inc n))]
                   (format "\n  @spec-%04d @e2e\n  Scenario: Scenario number %d\n    Given a model\n    Then it passes\n" i i)))))

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3 and the scope :project"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
      (s/write! dir "dhf/cycles/03/cycle.edn"
                (pr-str {:cut "spec-3" :scope :project
                         :builder {:name "builder-a" :actor :agent}
                         :grader {:name "grader-b" :actor :agent}
                         :spec-owner {:name "Sami" :actor :human}}))
      (assoc ctx :dir dir))))

(defstep #"the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13"
  (fn [ctx]
    (let [dir (:dir ctx)]
      (s/write! dir lint-feature (lint-text 12))
      (git! dir "init" "-q")
      (git! dir "config" "commit.gpgsign" "false")
      (git! dir "add" "-A" "-f" "--" ".")
      (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
      (git! dir "tag" "spec-3")
      (s/write! dir lint-feature (lint-text 13))
      (assoc ctx :cut-ids (mapv #(format "spec-%04d" %) (range 1 13))))))

(defstep #"the checklist holds the 12 scenarios of spec-3"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          f (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")
          _ (s/check! (fs/exists? f) (str "no checklist.json\n" (s/show r)))
          cl (json/parse-string (slurp (str f)) true)
          ids (sort (map :id (concat (:items cl) (:planned cl))))]
      (s/check! (= (:cut-ids ctx) ids) (str "the checklist holds " (pr-str ids)))
      nil)))

;; ---------------------------------------------------------------------------
;; The declared subset

(def ^:private every-construct
  (str "# Every construct of the subset.\n\n"
       "@UN-001\nFeature: Desk\n  Every construct the subset declares.\n\n"
       "  Background:\n    Given a desk\n\n"
       "  @spec-0001 @e2e\n  Scenario: A plain scenario\n    Given a thing\n    When it is used\n"
       "    Then it holds\n    And it stays\n    But it never breaks\n\n"
       "  @UN-002\n  Rule: A rule of the desk\n\n"
       "    Background:\n      Given a rule's desk\n\n"
       "    # A comment in a rule.\n"
       "    @spec-0002 @contract\n    Scenario: A doc string and a table\n"
       "      Given a text:\n        \"\"\"\n        some text\n        \"\"\"\n"
       "      And a table:\n        | a | b |\n        | 1 | 2 |\n\n"
       "    @spec-0003 @e2e\n    Scenario Outline: An outline over <n>\n      Given the number <n>\n\n"
       "      Examples:\n        | n |\n        | 1 |\n        | 2 |\n"))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx]
    (let [dir (sp/spec-project! {:features {"desk" every-construct} :ledger 3})]
      (assoc ctx :dir dir :file (sp/feature-rel "desk")))))

(defstep #"a feature file containing a line starting with \"([^\"]+):\""
  (fn [ctx kw]
    (let [text (str "@UN-001\nFeature: Desk\n  A fixture.\n\n"
                    "  @spec-0001 @e2e\n  Scenario: A known one\n    Given a thing\n\n"
                    "  " kw ": An unknown one\n    Given a thing\n")
          dir (sp/spec-project! {:features {"desk" text} :ledger 1})]
      (assoc ctx :dir dir :file (sp/feature-rel "desk") :line (sp/line-of text (str kw ":")) :keyword kw))))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          l (sp/finding-naming (kc/finding-lines r) [(str (:file ctx) ":" (:line ctx) ": ") (:keyword ctx)])]
      (s/check! l (str "no finding names " (:file ctx) ":" (:line ctx) " and " (:keyword ctx) "\n" (s/show r)))
      nil)))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (let [text (str "@UN-001\nFeature: Desk\n  A fixture.\n\n"
                    "  @spec-0001 @e2e\n  Scenario: One\n    Given a thing\n\n"
                    "Feature: Another desk\n  A second feature.\n")
          dir (sp/spec-project! {:features {"desk" text} :ledger 1})]
      (assoc ctx :dir dir :file (sp/feature-rel "desk") :line (sp/line-of text "Feature: Another")))))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": ")) (kc/finding-lines r))
                (str "no finding names " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      nil)))
