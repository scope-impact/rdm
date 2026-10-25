(ns steps.spec-parser
  "Step definitions for dhf/spec/spec/parser.feature: the declared Gherkin
  subset, read by `rdm-spec check`, and the spec read at a cut by
  `rdm-spec checklist`. Context keys: see steps.spec-support."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as sp]
            [steps.support :as s]))

(def every-construct
  "A stamped feature file using every construct of the subset."
  (str "# A comment before the feature.\n"
       "@UN-001\n"
       "Feature: Desk\n"
       "  A description of the feature,\n"
       "  on two lines.\n"
       "\n"
       "  Background:\n"
       "    Given a desk\n"
       "\n"
       "  # A comment before a rule.\n"
       "  @UN-002\n"
       "  Rule: A rule groups scenarios\n"
       "\n"
       "    Background:\n"
       "      Given a booking book\n"
       "\n"
       "    @spec-0001 @e2e\n"
       "    Scenario: A scenario with a doc string and a data table\n"
       "      Given the bookings:\n"
       "        | name | turn |\n"
       "        | Aino | 1    |\n"
       "      When a note is written:\n"
       "        \"\"\"\n"
       "        Bring a towel.\n"
       "        \"\"\"\n"
       "      Then the note is kept\n"
       "      And the desk is open\n"
       "      But the door is closed\n"
       "\n"
       "    @spec-0002 @contract @UN-003\n"
       "    Scenario Outline: An outline over <n>\n"
       "      Given <n> bookings\n"
       "      Then there are <n> turns\n"
       "\n"
       "      Examples:\n"
       "        | n |\n"
       "        | 1 |\n"
       "        | 2 |\n"))

(defn- with-feature [ctx text]
  (let [dir (sp/spec-project! {:features {"desk" text}})]
    (assoc ctx :dir dir :file (sp/feature-rel "desk") :text text)))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx] (with-feature ctx every-construct)))

(defstep #"a feature file containing a line starting with \"([^\"]+)\""
  (fn [ctx kw]
    (let [base (sp/feature-text "Desk" [{:tags ["@spec-0001" "@e2e"] :title "A scenario"}])
          text (str base "\n  " kw " A scenario written another way\n    Given a desk\n")]
      (assoc (with-feature ctx text) :line (sp/line-of text kw) :keyword kw))))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          want (str (:file ctx) ":" (:line ctx) ":")
          kw (str/replace (:keyword ctx) #":$" "")]
      (s/check! (sp/finding-naming (kc/finding-lines r) [want kw])
                (str "no finding names " want " and " (pr-str kw) "\n" (s/show r)))
      nil)))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (let [base (sp/feature-text "Desk" [{:tags ["@spec-0001" "@e2e"] :title "A scenario"}])
          text (str base "\nFeature: Another desk\n  A second feature in one file.\n")
          second-line (->> (str/split-lines text)
                           (keep-indexed (fn [i l] (when (str/starts-with? (str/trim l) "Feature:") (inc i))))
                           second)]
      (assoc (with-feature ctx text) :line second-line))))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          want (str (:file ctx) ":" (:line ctx) ":")]
      (s/check! (sp/finding-naming (kc/finding-lines r) [want])
                (str "no finding names " want "\n" (s/show r)))
      nil)))

;; --- the spec at a cut -----------------------------------------------------------

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- numbered-scenarios [from n]
  (vec (for [i (range from (+ from n))]
         {:tags [(format "@spec-%04d" i) "@e2e"] :title (str "Scenario number " i)})))

(defstep #"the spec at spec-3 holds (\d+) stamped scenarios and the working tree's spec holds (\d+)"
  (fn [ctx at-cut now]
    (let [dir (or (:dir ctx) (s/fail! "no cycle project: the cycle step comes first"))
          at-cut (parse-long at-cut)
          now (parse-long now)
          ;; the cycle project's spec is the room's with one feature file: rewrite that one
          f (or (first (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))
                (s/fail! "the cycle project holds no feature file"))
          rel (str (fs/relativize dir f))
          head (fn [scs] (sp/feature-text "Lint" ["@UN-005"] scs))]
      (spit f (head (numbered-scenarios 1 at-cut)))
      (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
      (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3, again")
      (git! dir "tag" "-f" "spec-3")
      (spit f (head (numbered-scenarios 1 now)))
      (assoc ctx :file rel :at-cut (mapv #(subs (first (:tags %)) 1) (numbered-scenarios 1 at-cut))))))

(defstep #"the checklist holds the (\d+) scenarios of spec-3"
  (fn [ctx n]
    (let [r (kc/exits! ctx 0)
          f (fs/file (:dir ctx) "dhf/cycles" (:cycle ctx) "checklist.json")
          _ (s/check! (fs/exists? f) (str "no checklist was written\n" (s/show r)))
          ids (mapv #(get % "id") (get (json/parse-string (slurp (str f))) "items"))]
      (s/check! (= (parse-long n) (count ids)) (str "the checklist holds " (count ids) " items: " ids))
      (s/check! (= (sort (:at-cut ctx)) (sort ids)) (str "the checklist holds " ids ", not " (:at-cut ctx)))
      nil)))
