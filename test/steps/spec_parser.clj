(ns steps.spec-parser
  "Step definitions for dhf/spec/spec/parser.feature. The Gherkin subset is
  tested in a copy of the room's spec whose parser feature file is replaced by
  a fixture; reading the spec at a cut, in a fixture project whose cut spec-3
  holds fewer scenarios than its working tree (steps.measurement-cycle's
  projects)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.spec-support :as f]
            [steps.support :as s]))

(def parser-file "dhf/spec/spec/parser.feature")

(defn- line-of [text needle]
  (some (fn [[i l]] (when (str/starts-with? (str/trim l) needle) (inc i)))
        (map-indexed vector (str/split-lines text))))

(defn- fixture!
  "ctx with a copy of the room's spec whose parser feature file is `text`."
  [ctx text]
  (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
  (let [ctx (f/ensure-room! ctx)]
    (f/feature! (:dir ctx) "spec/parser" text)
    (assoc ctx :file parser-file :text text)))

;; ---------------------------------------------------------------------------
;; The subset

(def every-construct
  (str "# A comment before the feature.\n"
       "@UN-001\n"
       "Feature: Parser\n"
       "  Every construct of the subset, once.\n"
       "\n"
       "  Background:\n"
       "    Given a step every scenario of the feature runs first\n"
       "\n"
       "  # A comment between blocks.\n"
       "  Rule: A rule with its own background\n"
       "    The rule's description.\n"
       "\n"
       "    Background:\n"
       "      Given a step every scenario of the rule runs first\n"
       "\n"
       "    @spec-0051 @e2e\n"
       "    Scenario: A scenario with a doc string and a data table\n"
       "      Given a doc string:\n"
       "        \"\"\"\n"
       "        Some text, # not a comment.\n"
       "        \"\"\"\n"
       "      And a data table:\n"
       "        | name | value |\n"
       "        | a    | 1     |\n"
       "      When it is read\n"
       "      Then it holds both\n"
       "      But nothing else\n"
       "\n"
       "    @spec-0052 @contract @UN-002\n"
       "    Scenario Outline: An outline over <thing>\n"
       "      Given the thing <thing>\n"
       "      Then it is read\n"
       "\n"
       "      Examples:\n"
       "        | thing |\n"
       "        | one   |\n"
       "        | two   |\n"))

(defn- read-checklist
  "The cycle's checklist.json, keyword keys; fails when absent. Local to this
  file: another scope's step helpers are not a contract."
  [ctx cycle]
  (let [rel (str "dhf/cycles/" cycle "/checklist.json")
        f (fs/file (:dir ctx) rel)]
    (s/check! (fs/exists? f) (str "no " rel "\n" (s/show (:run ctx))))
    (json/parse-string (slurp (str f)) true)))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx]
    (apply s/needs-built! f/check-stages)
    (f/ensure-shared-steps!)
    (let [ctx (fixture! ctx every-construct)]
      (f/generate-views! (:dir ctx))
      ctx)))

(defstep #"a feature file containing a line starting with \"([^\"]+)\""
  (fn [ctx start]
    (let [kw (str/replace start #":$" "")
          text (f/fixture-feature {:tags "@spec-0052 @e2e"})
          text (str text "\n    @spec-0053 @e2e\n    " start " A scenario in an unknown keyword\n      Given a step\n")]
      (assoc (fixture! ctx text) :keyword kw :line (line-of text start)))))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx]
    (f/fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":") (:keyword ctx))))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (let [text (str (f/fixture-feature {:tags "@spec-0053 @e2e"})
                    "\n@UN-002\nFeature: A second feature\n\n  @spec-0054 @e2e\n  Scenario: Under the second\n    Given a step\n")
          second-line (second (keep-indexed (fn [i l] (when (str/starts-with? (str/trim l) "Feature:") (inc i)))
                                            (str/split-lines text)))]
      (assoc (fixture! ctx text) :line second-line))))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx]
    (f/fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":"))))

;; ---------------------------------------------------------------------------
;; The spec at a revision

(defn- numbered [from n]
  (vec (for [i (range n)]
         {:id (format "spec-%04d" (+ from i)) :title (str "Scenario number " (inc i))})))

;; "dhf/cycles/03/cycle.edn names the cut spec-3 and the scope S" is the
;; measurement context's shared cycle step (steps.measurement-cycle): one step
;; text, one meaning.

(defstep #"the spec at spec-3 holds (\d+) stamped scenarios and the working tree's spec holds (\d+)"
  (fn [ctx at-cut in-tree]
    (let [n (parse-long at-cut)
          m (parse-long in-tree)
          _ (s/check! (< n m) "the working tree must hold more scenarios than the cut")
          dir (:dir ctx)
          _ (s/check! dir "no project: name the cycle first")
          cut-scs (numbered 401 n)
          tree-scs (numbered 401 m)]
      (s/write! dir mc/lint-feature (mc/feature-text cut-scs))
      (mc/cut! dir "spec-3")
      ;; the working tree, committed after the cut, holds more
      (s/write! dir mc/lint-feature (mc/feature-text tree-scs))
      (mc/git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
      (mc/git! dir "commit" "-q" "-m" "after the cut")
      (assoc ctx :cut-ids (mapv :id cut-scs) :tree-ids (mapv :id tree-scs)))))

(defstep #"the checklist holds the (\d+) scenarios of spec-3"
  (fn [ctx n]
    (let [r (:run ctx)
          _ (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
          {:keys [cut items planned]} (read-checklist ctx "03")
          ids (sort (map :id (concat items planned)))]
      (s/check! (= "spec-3" cut) (str "the checklist names the cut " (pr-str cut)))
      (s/check! (= (parse-long n) (count ids)) (str (count ids) " scenarios: " (pr-str ids)))
      (s/check! (= (sort (:cut-ids ctx)) ids)
                (str "the checklist holds " (pr-str ids) ", not the cut's " (pr-str (:cut-ids ctx))))
      (s/check! (fs/exists? (fs/file (:dir ctx) mc/lint-feature)) "the working tree lost its feature file"))))
