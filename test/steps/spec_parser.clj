(ns steps.spec-parser
  "Step definitions for dhf/spec/spec/parser.feature: a copy of the room's
  spec with the parser's own feature file replaced by a fixture, and, for the
  spec read at a cut, a throwaway git repository whose tag spec-3 holds one
  spec while its working tree holds another."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(def parser-file "dhf/spec/spec/parser.feature")

(defn- room
  "ctx with a copy of the room's spec in :dir (kept when it has one)."
  [ctx]
  (f/ensure-room! ctx))

(defn- fixture!
  "Replaces the parser's feature file in ctx's room with `text`."
  [ctx text]
  (let [ctx (room ctx)]
    (f/feature! (:dir ctx) "spec/parser" text)
    (assoc ctx :file parser-file :text text)))

(defn- line-of
  "The 1-based line of `text` whose trimmed content starts with `prefix`;
  the `nth` such line (0-based) when given."
  ([text prefix] (line-of text prefix 0))
  ([text prefix nth*]
   (nth (keep-indexed (fn [i l] (when (str/starts-with? (str/trim l) prefix) (inc i)))
                      (str/split-lines text))
        nth* nil)))

;; ---------------------------------------------------------------------------
;; The spec is read at a revision

(defn- git!
  "Runs git in `dir`, isolated from any user or system configuration."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
                                      "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (:out r)))

(defn- lint-feature
  "The feature file of the component Lint holding a stamped scenario for each
  of `ids`."
  [ids]
  (str "@UN-005\nFeature: Lint\n  A feature for the parser's tests.\n\n  Rule: The lint holds the model\n"
       (str/join
        (for [id ids]
          (str "\n    @" id " @e2e\n    Scenario: Scenario " id
               "\n      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n")))))

(defstep #"the spec at spec-3 holds (\d+) stamped scenarios and the working tree's spec holds (\d+)"
  (fn [ctx at-cut in-tree]
    (s/needs-built! 'rdm-spec.measurement.checklist)
    (let [dir (or (:dir ctx) (s/fail! "no project: the cycle was not set up"))
          at-cut (parse-long at-cut)
          in-tree (parse-long in-tree)
          ids (mapv #(format "spec-%04d" (+ 301 %)) (range in-tree))
          cut-ids (subvec ids 0 at-cut)
          lint "dhf/spec/architecture/lint.feature"]
      (s/check! (> in-tree at-cut) "the working tree must hold more scenarios than the cut")
      (doseq [g (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete g))
      (s/write! dir lint (lint-feature cut-ids))
      (git! dir "add" "-A")
      (git! dir "commit" "-q" "-m" "the spec at spec-3")
      (git! dir "tag" "-f" "spec-3")
      ;; after the cut: the working tree gains scenarios the cut never held
      (s/write! dir lint (lint-feature ids))
      (assoc ctx :cut-ids cut-ids :tree-ids ids))))

(defstep #"the checklist holds the (\d+) scenarios of spec-3"
  (fn [ctx n]
    (let [r (:run ctx)
          f (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (fs/exists? f) (str "no dhf/cycles/03/checklist.json\n" (s/show r)))
      (let [items (:items (json/parse-string (slurp (str f)) true))
            ids (sort (map :id items))]
        (s/check! (= (parse-long n) (count items)) (str (count items) " items: " (pr-str ids)))
        (s/check! (= (sort (:cut-ids ctx)) ids) (str "items " (pr-str ids) "\nexpected " (pr-str (:cut-ids ctx))))))))

;; ---------------------------------------------------------------------------
;; The declared subset of Gherkin

(def every-construct
  (str "# A comment before the feature.\n"
       "@UN-002\n"
       "Feature: Parser\n"
       "  A fixture using every construct of the subset.\n"
       "\n"
       "  Background:\n"
       "    Given a background step\n"
       "\n"
       "  @spec-0051 @e2e\n"
       "  Scenario: A plain scenario\n"
       "    Given a step with a doc string\n"
       "      \"\"\"\n"
       "      Some text, kept as it is.\n"
       "      \"\"\"\n"
       "    And a step with a table\n"
       "      | name  | value |\n"
       "      | one   | 1     |\n"
       "    When something happens\n"
       "    Then it holds\n"
       "    But nothing else\n"
       "\n"
       "  # A comment before a rule.\n"
       "  @UN-001\n"
       "  Rule: A rule with its own background\n"
       "\n"
       "    Background:\n"
       "      Given a rule's background step\n"
       "\n"
       "    @spec-0052 @contract\n"
       "    Scenario Outline: An outline over <thing>\n"
       "      Given a <thing>\n"
       "      Then it is read\n"
       "\n"
       "      Examples:\n"
       "        | thing |\n"
       "        | cat   |\n"
       "        | dog   |\n"))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx]
    (apply s/needs-built! f/check-stages)
    (f/ensure-shared-steps!)
    (let [ctx (fixture! ctx every-construct)]
      (f/generate-views! (:dir ctx))
      ctx)))

(defstep #"a feature file containing a line starting with \"([^\"]+)\""
  (fn [ctx kw]
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [text (str "@UN-002\nFeature: Parser\n  A fixture with a keyword outside the subset.\n\n"
                    "  @spec-0052 @e2e\n"
                    "  " kw " Not in the subset\n"
                    "    Given a step\n")]
      (assoc (fixture! ctx text) :keyword kw :line (line-of text kw)))))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx]
    (f/fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":") (str/replace (:keyword ctx) #":$" ""))))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [text (str "@UN-002\nFeature: Parser\n  The first feature.\n\n"
                    "  @spec-0053 @e2e\n  Scenario: One\n    Given a step\n\n"
                    "Feature: Another\n  The second feature.\n\n"
                    "  @spec-0054 @e2e\n  Scenario: Two\n    Given a step\n")]
      (assoc (fixture! ctx text) :line (line-of text "Feature:" 1)))))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx]
    (f/fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":") "Feature")))
