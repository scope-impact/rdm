(ns steps.spec-parser
  "Step definitions for dhf/spec/spec/parser.feature: `rdm-spec check` and
  `rdm-spec checklist` on spec projects (steps.spec-support) the scenario
  makes. The When and \"it passes\" are the kernel's.

  Context keys set here: :dir the project; :file the feature file a step
  wrote (relative to :dir); :line the line a finding must name; :keyword the
  keyword refused; :cut-ids the ids the spec held at the cut."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as sp]
            [steps.support :as s]))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- numbered [n]
  (vec (for [i (range 1 (inc n))]
         {:tags [(format "@spec-%04d" i) "@e2e"] :title (str "Scenario number " i)})))

;; ---------------------------------------------------------------------------
;; The spec is read at a revision

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3 and the scope :project"
  (fn [ctx]
    (let [dir (or (:dir ctx) (sp/spec-project! {:features {"lint" (sp/feature-text "Lint" [])} :ledger 100}))]
      (s/write! dir "dhf/cycles/03/cycle.edn"
                (pr-str {:cut "spec-3" :scope :project
                         :builder {:name "builder-a" :actor :agent}
                         :grader {:name "grader-b" :actor :agent}
                         :spec-owner {:name "Sami" :actor :human}}))
      (assoc ctx :dir dir :cycle "03"))))

(defstep #"the spec at spec-3 holds (\d+) stamped scenarios and the working tree's spec holds (\d+)"
  (fn [ctx at-cut now]
    (let [dir (or (:dir ctx) (sp/spec-project! {:features {"lint" (sp/feature-text "Lint" [])} :ledger 100}))
          at-cut (numbered (parse-long at-cut))
          now (numbered (parse-long now))]
      (sp/write-feature! dir "lint" (sp/feature-text "Lint" at-cut))
      (git! dir "init" "-q")
      (git! dir "add" "-A" "-f" "--" ".")
      (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
      (git! dir "tag" "spec-3")
      (sp/write-feature! dir "lint" (sp/feature-text "Lint" now))
      (assoc ctx :dir dir :cut-ids (mapv #(subs (first (:tags %)) 1) at-cut)))))

(defstep #"the checklist holds the (\d+) scenarios of spec-3"
  (fn [ctx n]
    (let [r (kc/exits! ctx 0)
          f (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")
          _ (s/check! (fs/exists? f) (str "no checklist.json was written\n" (s/show r)))
          items (get (json/parse-string (slurp (str f))) "items")]
      (s/check! (= (parse-long n) (count items)) (str "the checklist holds " (count items) " items"))
      (s/check! (= (:cut-ids ctx) (mapv #(get % "id") items))
                (str "the checklist holds " (pr-str (mapv #(get % "id") items)) ", not spec-3's scenarios"))
      nil)))

;; ---------------------------------------------------------------------------
;; The declared subset

(def ^:private every-construct
  (str "# A comment before the feature.\n"
       "@UN-001\n"
       "Feature: Every construct\n"
       "  A description of the feature.\n\n"
       "  Background:\n"
       "    Given a fixture\n\n"
       "  @spec-0001 @e2e\n"
       "  Scenario: A plain scenario\n"
       "    Given a fixture\n"
       "    When \"rdm-spec check\" is run\n"
       "    Then it passes\n"
       "    But nothing else happens\n\n"
       "  # A comment between scenarios.\n"
       "  Rule: A rule groups scenarios\n\n"
       "    Background:\n"
       "      Given the rule's fixture\n\n"
       "    @spec-0002 @contract @UN-002\n"
       "    Scenario: A scenario with a doc string and a data table\n"
       "      Given a file holding:\n"
       "        \"\"\"\n"
       "        some text\n"
       "        \"\"\"\n"
       "      And a table:\n"
       "        | key | value |\n"
       "        | a   | 1     |\n"
       "      When \"rdm-spec check\" is run\n"
       "      Then it passes\n\n"
       "    @spec-0003 @e2e\n"
       "    Scenario Outline: An outline over <n>\n"
       "      Given the number <n>\n"
       "      Then it passes\n\n"
       "      Examples:\n"
       "        | n |\n"
       "        | 1 |\n"
       "        | 2 |\n"))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx]
    (let [dir (sp/spec-project! {:features {"every" every-construct}})]
      (assoc ctx :dir dir :file (sp/feature-rel "every")))))

(defn- with-line-after
  "A valid feature file with `extra` inserted after its first scenario:
  [text line-of-extra]."
  [extra]
  (let [base (sp/feature-text "Refused" [{:tags ["@spec-0001" "@e2e"] :title "A good scenario"}])
        lines (vec (str/split-lines base))]
    [(str (str/join "\n" (concat lines ["" extra "    Given a fixture"])) "\n") (+ 2 (count lines))]))

(defstep #"a feature file containing a line starting with \"([^\"]+)\""
  (fn [ctx kw]
    (let [[text line] (with-line-after (str "  " kw " Another scenario"))
          dir (sp/spec-project! {:features {"refused" text}})]
      (assoc ctx :dir dir :file (sp/feature-rel "refused") :line line :keyword (str/replace kw #":$" "")))))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          at (str (:file ctx) ":" (:line ctx) ": ")]
      (s/check! (some #(and (str/starts-with? % at) (str/includes? % (:keyword ctx))) (kc/finding-lines r))
                (str "no finding starts " at " and names " (:keyword ctx) "\n" (s/show r)))
      nil)))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (let [[text line] (with-line-after "Feature: A second feature")
          dir (sp/spec-project! {:features {"twice" text}})]
      (assoc ctx :dir dir :file (sp/feature-rel "twice") :line line))))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          at (str (:file ctx) ":" (:line ctx) ": ")]
      (s/check! (some #(str/starts-with? % at) (kc/finding-lines r))
                (str "no finding starts " at "\n" (s/show r)))
      nil)))
