(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature, in a small project the
  scenario makes (a fixture): the room's configuration, a model with one
  bounded context of two components (demo/alpha and demo/beta) and its
  generated view, five declared needs citing a recorded source, a ledger at
  10, and the two components' feature files, which the Givens rewrite. A
  Given that finds a project already made (the kernel's stack-word Given
  makes a copy of the room) changes that one instead.

  The shared When, \"it passes\", \"it exits N\" and \"the configured stack
  words include W\" are the kernel's (steps.kernel-command).

  Context keys: :dir the project, :run the last run, :scenario {:file :line
  :id} the scenario a Given wrote, :scenarios both of them (two files),
  :word and :word-at {:file :line} (a stack word)."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as f]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The small project

(def ^:private model
  (str "{:system {:id :demo-system :name \"Demo\" :description \"A fixture system\"}\n"
       " :people [{:id :user :name \"User\" :actor #{:human} :description \"Uses the fixture\"}]\n"
       " :external []\n"
       " :containers\n"
       " [{:id :app :name \"app\" :kind :runs :technology \"A fixture\" :description \"The fixture's one container\"\n"
       "   :contexts\n"
       "   [{:id :demo :name \"demo\" :description \"The fixture's one bounded context\"\n"
       "     :components\n"
       "     [{:id :demo/alpha :name \"Alpha\" :spec \"demo/alpha\" :description \"The first fixture component\"}\n"
       "      {:id :demo/beta :name \"Beta\" :spec \"demo/beta\" :description \"The second fixture component\"}]}]}]\n"
       " :relationships [{:from :user :to :app :description \"Uses\"}]\n"
       " :views [{:key \"demo-components\" :level :component :context :demo\n"
       "          :description \"The fixture's components\"}]}\n"))

(defn- needs-text [ids]
  (str "{" (str/join "\n " (for [id ids]
                             (str (pr-str id) " {:text \"A fixture need.\" :sources [\"demo:README.md@abc1234\"]}")))
       "}\n"))

(def ^:private default-needs ["UN-001" "UN-002" "UN-003" "UN-004" "UN-005"])

(defn- scenario-text [{:keys [tags name]}]
  (str (when (seq tags) (str "    " tags "\n"))
       "    Scenario: " name "\n"
       "      Given a fixture\n"
       "      When the fixture runs\n"
       "      Then the fixture holds\n"))

(defn- feature-text
  "A feature file: `feature-tags` on the feature, `scenarios` under one rule,
  `padding` comment lines before the rule."
  [{:keys [feature-tags name scenarios padding] :or {feature-tags "@UN-001" padding 0}}]
  (str (when (seq feature-tags) (str feature-tags "\n"))
       "Feature: " name "\n"
       "  A fixture of the spec check's tests.\n\n"
       (str/join (repeat padding "  # padding, to place the scenario\n"))
       (when (seq scenarios)
         (str "  Rule: The fixture's rule\n\n"
              (str/join "\n" (map scenario-text scenarios))))))

(defn- line-of [text name]
  (some (fn [[i l]] (when (str/ends-with? (str/trim l) (str ": " name)) (inc i)))
        (map-indexed vector (str/split-lines text))))

(def ^:private alpha "dhf/spec/demo/alpha.feature")
(def ^:private beta "dhf/spec/demo/beta.feature")

(defn- write-feature!
  "Writes the feature file `rel` of the project; returns {:file :line} of
  its scenario named `name` (when given)."
  [dir rel opts & [name]]
  (let [text (feature-text opts)]
    (s/write! dir rel text)
    {:file rel :line (when name (line-of text name))}))

(defn- small-project!
  "The small project (see the namespace doc), its view generated."
  []
  (let [dir (s/project!)]
    (s/write! dir "dhf/spec/model.edn" model)
    (s/write! dir "dhf/spec/events.edn" "{}\n")
    (s/write! dir "dhf/spec/needs.edn" (needs-text default-needs))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 10}\n")
    (s/write! dir "dhf/raw/sources.edn"
              (pr-str {:demo {:repository "https://example.org/demo" :record "demo.md" :pins ["abc1234"] :fenced []}}))
    (s/write! dir "dhf/raw/demo.md" "# demo\n\nA fixture source.\n")
    (write-feature! dir alpha {:name "Alpha" :scenarios [{:tags "@spec-0001 @e2e" :name "The first fixture scenario"}]})
    (write-feature! dir beta {:name "Beta" :scenarios [{:tags "@spec-0002 @e2e" :name "The second fixture scenario"}]})
    (f/generate-views! dir)
    dir))

(defn- project [ctx] (or (:dir ctx) (small-project!)))

(defn- one-scenario!
  "A project whose alpha feature holds one scenario with `tags` (beta holds
  none); ctx with :dir and :scenario."
  [ctx tags & [feature-opts]]
  (let [dir (project ctx)
        name "A fixture scenario"
        at (write-feature! dir alpha (merge {:name "Alpha" :scenarios [{:tags tags :name name}]} feature-opts) name)]
    (write-feature! dir beta {:name "Beta"})
    (assoc ctx :dir dir :scenario (assoc at :id (second (re-find #"@(spec-\d+)" (str tags)))))))

;; ---------------------------------------------------------------------------
;; Reading the run

(defn- finding-lines [ctx] (f/finding-lines (kc/the-run ctx)))

(defn- fails-naming!
  "Checks the run exited 1 and one finding line holds every one of `parts`
  (and starts with `prefix`, when given)."
  [ctx prefix & parts]
  (let [r (kc/exits! ctx 1)]
    (s/check! (some (fn [l] (and (or (nil? prefix) (str/starts-with? l prefix))
                                 (every? #(str/includes? l (str %)) parts)))
                    (finding-lines ctx))
              (str "no finding" (when prefix (str " at " prefix)) " names all of " (pr-str parts) "\n" (s/show r)))
    nil))

(defn- read-edn [dir rel] (clojure.edn/read-string (slurp (str (fs/file dir rel)))))

(defn- at [{:keys [file line]}] (str file ":" line ":"))

;; ---------------------------------------------------------------------------
;; A scenario carries exactly one id

(defstep #"a scenario tagged both @spec-0007 and @spec-0008"
  (fn [ctx] (one-scenario! ctx "@spec-0007 @spec-0008 @e2e")))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0007" "spec-0008")))

(defstep #"two scenarios both tagged @spec-0007"
  (fn [ctx]
    (let [dir (project ctx)
          a (write-feature! dir alpha {:name "Alpha" :scenarios [{:tags "@spec-0007 @e2e" :name "One"}]} "One")
          b (write-feature! dir beta {:name "Beta" :scenarios [{:tags "@spec-0007 @e2e" :name "Two"}]} "Two")]
      (assoc ctx :dir dir :scenarios [a b]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (let [[a b] (:scenarios ctx)]
      (fails-naming! ctx nil (:file a) (:file b) "spec-0007"))))

(defstep #"a scenario on line 30 of a feature file with no @spec- tag"
  (fn [ctx]
    (let [ctx (one-scenario! ctx "@e2e")
          line (get-in ctx [:scenario :line])
          ctx (one-scenario! ctx "@e2e" {:padding (- 30 line)})]
      (s/check! (= 30 (get-in ctx [:scenario :line])) (str "the scenario is on line " (get-in ctx [:scenario :line])))
      ctx)))

(defstep #"it fails, names the file and line 30, and says to run \"rdm-spec stamp\""
  (fn [ctx] (fails-naming! ctx (str alpha ":30:") "rdm-spec stamp")))

(defstep #"a scenario tagged @spec-0901, an id above every id the ledger has issued"
  (fn [ctx]
    (let [ctx (one-scenario! ctx "@spec-0901 @e2e")
          issued (:issued (read-edn (:dir ctx) "dhf/spec/ids.edn"))]
      (s/check! (< issued 901) (str "the ledger has issued " issued))
      ctx)))

(defstep #"it fails, names spec-0901 and says the ledger never issued it"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0901" "never issued")))

;; ---------------------------------------------------------------------------
;; A scenario carries exactly one verification level

(defstep #"a stamped scenario tagged (@\S+)"
  (fn [ctx tag]
    (one-scenario! ctx (if (#{"@e2e" "@contract"} tag) (str "@spec-0007 " tag) (str "@spec-0007 @e2e " tag)))))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (not-any? #(str/starts-with? % (at (:scenario ctx))) (finding-lines ctx))
                (str "a finding names the scenario\n" (s/show r)))
      (kc/exits! ctx 0)
      nil)))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (one-scenario! ctx "@spec-0007")))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0007" "@e2e" "@contract")))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (one-scenario! ctx "@spec-0007 @e2e @contract")))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0007" "@e2e" "@contract")))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0007" "@persona" "@e2e" "@contract" "@UN-" "@wip")))

;; ---------------------------------------------------------------------------
;; Every scenario traces to a user need

(defstep #"a feature tagged @UN-003"
  (fn [ctx] (one-scenario! ctx "@spec-0007 @e2e" {:feature-tags "@UN-003"})))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (let [text (slurp (str (fs/file (:dir ctx) (get-in ctx [:scenario :file]))))
          tag-line (get (str/split-lines text) (- (get-in ctx [:scenario :line]) 2))]
      (s/check! (not (re-find #"@UN-" (str tag-line))) (str "the scenario carries a need tag: " tag-line))
      ctx)))

(defstep #"no results directory exists"
  (fn [ctx]
    (let [rd (:results-dir (read-edn (:dir ctx) "dhf/harness/config.edn"))]
      (s/check! rd "the configuration names no results directory")
      (when (fs/exists? (fs/file (:dir ctx) rd)) (fs/delete-tree (fs/file (:dir ctx) rd)))
      (assoc-in ctx [:env "RESULTS_DIR"] nil))))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          ids (map :id (:scenarios (:trace (kc/stdout-edn r))))]
      (s/check! (some #{(get-in ctx [:scenario :id])} ids)
                (str "the trace does not list " (get-in ctx [:scenario :id]) "\n" (s/show r)))
      nil)))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (one-scenario! ctx "@spec-0007 @e2e" {:feature-tags ""})))

(defstep #"it fails and names the scenario's id"
  (fn [ctx] (fails-naming! ctx (at (:scenario ctx)) "spec-0007")))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [ctx]
    (let [dir (project ctx)]
      (s/write! dir "dhf/spec/needs.edn" (needs-text default-needs))
      (assoc ctx :dir dir))))

(defstep #"a scenario tagged (@UN-\d+)"
  (fn [ctx tag] (assoc (one-scenario! ctx (str "@spec-0007 @e2e " tag)) :need (subs tag 1))))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need] (fails-naming! ctx (at (:scenario ctx)) need "spec-0007")))

;; ---------------------------------------------------------------------------
;; Every user need rests on a source

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (let [dir (project ctx)]
      (s/write! dir "dhf/spec/needs.edn"
                (str (str/replace (needs-text default-needs)
                                  (re-pattern (str "\"" need "\" \\{:text \"A fixture need.\" :sources \\[[^\\]]*\\]\\}"))
                                  (str "\"" need "\" {:text \"A fixture need.\" :sources []}"))))
      (assoc ctx :dir dir :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need] (fails-naming! ctx "dhf/spec/needs.edn" need)))

;; ---------------------------------------------------------------------------
;; Planned work is a scenario tagged @wip

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx] (one-scenario! ctx "@spec-0007 @e2e @wip")))

;; ---------------------------------------------------------------------------
;; The spec never names an implementation

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [dir (project ctx)
          file (first (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))
          lines (str/split-lines (slurp file))
          i (first (keep-indexed (fn [i l] (when (str/starts-with? (str/trim l) "Feature:") i)) lines))]
      (s/check! i (str file " holds no Feature line"))
      (spit file (str (str/join "\n" (concat (take (inc i) lines) [(str "  The parser is written in " word ".")]
                                             (drop (inc i) lines)))
                      "\n"))
      (assoc ctx :dir dir :word word :word-at {:file (str (fs/relativize dir file)) :line (+ i 2)}))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx] (fails-naming! ctx (at (:word-at ctx)) (:word ctx))))
