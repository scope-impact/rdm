(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature: `rdm-spec check` in a
  spec project (steps.spec-support) holding the scenario the step describes.
  Context keys: see steps.spec-support."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as sp]
            [steps.support :as s]))

(defn- one-scenario!
  "ctx with a spec project whose desk feature holds one scenario: `tags`
  and the feature's own `feature-tags`."
  ([ctx tags] (one-scenario! ctx tags ["@UN-001"]))
  ([ctx tags feature-tags]
   (let [title "The one scenario"
         text (sp/feature-text "Desk" feature-tags [{:tags tags :title title}])
         dir (sp/spec-project! {:features {"desk" text}})
         rel (sp/feature-rel "desk")]
     (assoc ctx :dir dir :file rel
            :scenario {:id (some #(when (re-matches #"@spec-\d{4,}" %) (subs % 1)) tags)
                       :title title :file rel :line (sp/line-of text "Scenario:")}))))

(defn- fails-naming!
  "Checks the run found something (exit 1) and a finding line names every
  one of `parts`."
  [ctx & parts]
  (let [r (kc/exits! ctx 1)
        ls (kc/finding-lines r)]
    (s/check! (sp/finding-naming ls parts)
              (str "no finding names " (str/join ", " (map pr-str parts)) "\n" (s/show r)))
    nil))

;; --- one id ----------------------------------------------------------------------

(defstep #"a scenario tagged both @(spec-\d+) and @(spec-\d+)"
  (fn [ctx a b] (assoc (one-scenario! ctx [(str "@" a) (str "@" b) "@e2e"]) :ids [a b])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx]
    (let [{:keys [file line]} (:scenario ctx)]
      (apply fails-naming! ctx (str file ":" line ":") (:ids ctx)))))

(defstep #"two scenarios both tagged @(spec-\d+)"
  (fn [ctx id]
    (let [dir (sp/spec-project!
               {:features {"desk" (sp/feature-text "Desk" [{:tags [(str "@" id) "@e2e"] :title "At the desk"}])
                           "door" (sp/feature-text "Door" [{:tags [(str "@" id) "@e2e"] :title "At the door"}])}})]
      (assoc ctx :dir dir :id id :files [(sp/feature-rel "desk") (sp/feature-rel "door")]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx] (apply fails-naming! ctx (:id ctx) (:files ctx))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx n]
    (let [n (parse-long n)
          head (sp/feature-text "Desk" [])
          pad (- n (count (filter #{\newline} head)) 3)
          _ (s/check! (pos? pad) "the line is too early for a fixture feature")
          text (str head (apply str (repeat pad "  # padding\n"))
                    "\n" (sp/scenario-text {:tags ["@e2e"] :title "An unstamped scenario"}))
          dir (sp/spec-project! {:features {"desk" text}})
          rel (sp/feature-rel "desk")]
      (s/check! (= n (sp/line-of text "Scenario:")) (str "the fixture's scenario is not on line " n))
      (assoc ctx :dir dir :file rel :line n))))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx n] (fails-naming! ctx (str (:file ctx) ":" n ":") "rdm-spec stamp")))

(defstep #"a scenario tagged @(spec-\d+), an id above every id the ledger has issued"
  (fn [ctx id]
    (let [ctx (one-scenario! ctx [(str "@" id) "@e2e"])]
      (s/check! (< (sp/ledger-of (:dir ctx)) (parse-long (re-find #"\d+" id))) "the ledger issued the id")
      (assoc ctx :id id))))

(defstep #"it fails, names (spec-\d+) and says the ledger never issued it"
  (fn [ctx id] (fails-naming! ctx id "never issued")))

;; --- one level -------------------------------------------------------------------

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [ctx level] (one-scenario! ctx ["@spec-0001" level])))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          id (get-in ctx [:scenario :id])]
      (s/check! (not-any? #(and (str/includes? % id) (re-find #"level" %)) (s/err-lines r))
                (str "a finding names " id "'s level\n" (s/show r)))
      (kc/exits! ctx 0)
      nil)))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (one-scenario! ctx ["@spec-0001"])))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx] (fails-naming! ctx (get-in ctx [:scenario :id]) "@e2e" "@contract")))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (one-scenario! ctx ["@spec-0001" "@e2e" "@contract"])))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx] (fails-naming! ctx (get-in ctx [:scenario :id]) "@e2e" "@contract")))

(defstep #"a stamped scenario tagged (@(?!e2e$|contract$|wip$)[a-z]+)"
  (fn [ctx tag] (assoc (one-scenario! ctx ["@spec-0001" "@e2e" tag]) :tag tag)))

(defstep #"it fails, names the scenario's id and (@[a-z]+), and lists the tags a scenario may carry"
  (fn [ctx tag]
    (fails-naming! ctx (get-in ctx [:scenario :id]) tag "@spec-" "@e2e" "@contract" "@UN-" "@wip")))

;; --- needs -----------------------------------------------------------------------

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need] (assoc (one-scenario! ctx ["@spec-0001" "@e2e"] [(str "@" need)]) :need need)))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (let [text (slurp (str (fs/file (:dir ctx) (:file ctx))))
          sc (first (:scenarios (sp/parse (:dir ctx) (:file ctx))))]
      (s/check! sc (str "no scenario in " (:file ctx) ":\n" text))
      (s/check! (not-any? #(str/starts-with? % "@UN-") (:tags sc)) "the scenario carries a need tag")
      nil)))

(defstep #"no results directory exists"
  (fn [ctx]
    (let [rd (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results")]
      (fs/delete-tree (fs/file (:dir ctx) rd))
      (assoc-in ctx [:env "RESULTS_DIR"] nil))))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          t (:trace (kc/stdout-edn r))
          id (get-in ctx [:scenario :id])]
      (s/check! (map? t) (str "standard output holds no {:trace ..}\n" (s/show r)))
      (s/check! (some #(= id (:id %)) (:scenarios t)) (str "the trace does not list " id "\n" (s/show r)))
      nil)))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx]
    (let [title "A scenario of no need"
          text (str "# A fixture feature file.\n\nFeature: Desk\n  A fixture.\n\n  Rule: A rule with no tag\n\n"
                    (sp/scenario-text {:tags ["@spec-0001" "@e2e"] :title title}))
          dir (sp/spec-project! {:features {"desk" text}})]
      (assoc ctx :dir dir :scenario {:id "spec-0001" :title title}))))

(defstep #"it fails and names the scenario's id"
  (fn [ctx] (fails-naming! ctx (get-in ctx [:scenario :id]))))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [ctx]
    (s/check! (= (map #(format "UN-%03d" %) (range 1 6)) (keys sp/default-needs))
              "the fixture's needs are not UN-001 to UN-005")
    nil))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc (one-scenario! ctx ["@spec-0001" "@e2e" (str "@" need)]) :need need)))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need] (fails-naming! ctx need (get-in ctx [:scenario :id]))))

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (let [ctx (one-scenario! ctx ["@spec-0001" "@e2e"])]
      (sp/write-needs! (:dir ctx) (assoc sp/default-needs need {:text "A need resting on nothing." :sources []}))
      ctx)))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need] (fails-naming! ctx need)))

;; --- planned work ----------------------------------------------------------------

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx] (one-scenario! ctx ["@spec-0001" "@e2e" "@wip"])))

;; --- no stack word ---------------------------------------------------------------

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/fail! "no project to write the feature file in"))
          f (or (first (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))
                (s/fail! "the project holds no feature file"))
          text (slurp f)
          line (sp/line-of text "Feature:")
          lines (str/split-lines text)
          changed (str/join "\n" (concat (take line lines) [(str "  The parser is written in " word ".")] (drop line lines)))]
      (spit f (str changed "\n"))
      (assoc ctx :file (str (fs/relativize dir f)) :line (inc line) :word word))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx] (fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":") (:word ctx))))
