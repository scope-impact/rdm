(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature. Each scenario works in a
  copy of the room's spec (steps.spec-support) whose spec check feature file
  is replaced by a fixture, so the room's own scenarios are the rest of the
  spec, and runs the whole `rdm-spec check`.

  Steps shared in wording with other features keep one meaning: \"it passes\"
  (steps.spec-support/ensure-shared-steps!) and \"the configured stack words
  include ...\" (defined here unless the knowledge context's step file
  defined it first, as that file does for this one)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(f/ensure-shared-steps!)

(def fixture-path "spec/check")
(def fixture-file "dhf/spec/spec/check.feature")

;; An id only the replaced spec check feature file carried, so the fixture
;; moves no other file's id (steps.spec-support/features!).
(def fixture-id "spec-0042")

(defn- fixture!
  "ctx with a copy of the room's spec whose spec check feature file is the
  fixture `text` (and `more`, {spec-path text}, beside it)."
  ([ctx text] (fixture! ctx text {}))
  ([ctx text more]
   (apply s/needs-built! f/check-stages)
   (let [ctx (f/ensure-room! ctx)]
     (f/features! (:dir ctx) (assoc more fixture-path text))
     (assoc ctx :file fixture-file :text text))))

(defn- one-scenario!
  "ctx with a fixture holding one scenario tagged `tags`, named `name`."
  [ctx tags & [opts]]
  (let [name (or (:name opts) "The fixture scenario")
        text (f/fixture-feature (merge {:tags tags :name name :feature-name "Spec check"} opts))]
    (assoc (fixture! ctx text) :name name :line (f/scenario-line text name))))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- fails-at!
  "The run exited 1 and a finding line of the fixture's scenario names every
  one of `parts`."
  [ctx & parts]
  (apply f/fails-naming! ctx (str (:file ctx) ":" (:line ctx) ":") parts))

;; ---------------------------------------------------------------------------
;; One id per scenario

(defstep #"a scenario tagged both @(spec-\d+) and @(spec-\d+)"
  (fn [ctx a b] (assoc (one-scenario! ctx (str "@" a " @" b " @e2e")) :ids [a b])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx] (apply fails-at! ctx (:name ctx) (:ids ctx))))

(defstep #"two scenarios both tagged @(spec-\d+)"
  (fn [ctx id]
    (let [tags (str "@" id " @e2e")
          other (f/fixture-feature {:tags tags :name "The other scenario" :feature-name "Parser"})
          ctx (fixture! ctx (f/fixture-feature {:tags tags :feature-name "Spec check"}) {"spec/parser" other})]
      (assoc ctx :id id :files [fixture-file "dhf/spec/spec/parser.feature"]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx] (apply f/fails-naming! ctx (:id ctx) (:files ctx))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx n]
    (let [n (parse-long n)
          base (f/fixture-feature {:tags "@e2e" :feature-name "Spec check"})
          pad (- n (f/scenario-line base "A fixture scenario"))
          _ (s/check! (not (neg? pad)) (str "the fixture's scenario cannot sit on line " n))
          text (f/fixture-feature {:tags "@e2e" :feature-name "Spec check"
                                   :leading (repeat pad "# A line to place the scenario.")})]
      (s/check! (= n (f/scenario-line text "A fixture scenario")) "the fixture's scenario is misplaced")
      (assoc (fixture! ctx text) :line n))))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx n] (f/fails-naming! ctx (str (:file ctx) ":" n ":") "rdm-spec stamp")))

(defstep #"a scenario tagged @(spec-(\d+)), an id above every id the ledger has issued"
  (fn [ctx id n]
    (let [ctx (one-scenario! ctx (str "@" id " @e2e"))
          issued (:issued (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/ids.edn")))))]
      (s/check! (< issued (parse-long n)) (str "the ledger has issued " issued ", not fewer than " n))
      (assoc ctx :id id))))

(defstep #"it fails, names (spec-\d+) and says the ledger never issued it"
  (fn [ctx id] (fails-at! ctx id "never issued")))

;; ---------------------------------------------------------------------------
;; One level per scenario

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [ctx level] (assoc (one-scenario! ctx (str "@" fixture-id " " level)) :id fixture-id)))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (run ctx)
          about (filter #(str/starts-with? % (str (:file ctx) ":")) (f/finding-lines r))]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (empty? about) (str "the check names the scenario: " (pr-str about))))))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (assoc (one-scenario! ctx (str "@" fixture-id)) :id fixture-id)))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx] (fails-at! ctx (:id ctx) "@e2e" "@contract")))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (assoc (one-scenario! ctx (str "@" fixture-id " @e2e @contract")) :id fixture-id)))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx] (fails-at! ctx (:id ctx) "@e2e" "@contract")))

(defstep #"a stamped scenario tagged @persona"
  (fn [ctx] (assoc (one-scenario! ctx (str "@" fixture-id " @e2e @persona")) :id fixture-id)))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx] (fails-at! ctx (:id ctx) "@persona" "@spec-" "@e2e" "@contract" "@UN-" "@wip")))

;; ---------------------------------------------------------------------------
;; Every scenario traces to a need

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc ctx :feature-tags (str "@" need) :need need)))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (s/check! (:feature-tags ctx) "no feature tag was given")
    (let [ctx (one-scenario! ctx (str "@" fixture-id " @e2e") {:feature-tags (:feature-tags ctx)})]
      (s/check! (not (re-find #"@UN-" (str/join " " (f/tags-of (:dir ctx) (:name ctx)))))
                "the scenario carries a need tag")
      (assoc ctx :id fixture-id))))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (run ctx)
          out (f/result r)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some #(= (:id ctx) (:id %)) (:scenarios out))
                (str (:need ctx) "'s trace does not list " (:id ctx) "\n" (s/show r))))))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (assoc (one-scenario! ctx (str "@" fixture-id " @e2e") {:feature-tags nil}) :id fixture-id)))

(defstep #"it fails and names the scenario's id"
  (fn [ctx] (fails-at! ctx (:id ctx))))

(defstep #"the declared needs are (UN-\d+) to (UN-\d+)"
  (fn [ctx from to]
    (let [ctx (f/ensure-room! ctx)
          n (fn [id] (parse-long (subs id 3)))
          keep? (fn [id] (<= (n from) (n id) (n to)))]
      (f/needs! (:dir ctx) (fn [m] (into {} (filter (comp keep? key)) m)))
      ctx)))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need] (assoc (one-scenario! ctx (str "@" fixture-id " @e2e @" need)) :id fixture-id :need need)))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need] (fails-at! ctx need (:id ctx))))

;; ---------------------------------------------------------------------------
;; Every need rests on a source

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (apply s/needs-built! f/check-stages)
    (let [ctx (f/ensure-room! ctx)]
      (f/needs! (:dir ctx) #(assoc-in % [need :sources] []))
      (assoc ctx :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need] (f/fails-naming! ctx "dhf/spec/needs.edn:" need)))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx]
    (f/ensure-shared-steps!)
    (let [ctx (one-scenario! ctx (str "@" fixture-id " @e2e @wip"))]
      (f/generate-views! (:dir ctx))
      ctx)))

;; ---------------------------------------------------------------------------
;; No stack word

;; Shared in wording with dhf/spec/knowledge/wiki.feature: the same meaning there.
(when-not (some #(re-matches (:pattern %) "the configured stack words include \"Cobalt\"")
                @rdm-spec.kernel.runner/*registry*)
  (defstep #"the configured stack words include \"([^\"]+)\""
    (fn [ctx word]
      (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))]
        (f/config! (:dir ctx) #(update % :stack-words (fn [ws] (vec (distinct (conj (vec ws) word))))))
        (assoc ctx :stack-word word)))))

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [sentence (str "The parser is written in " word ".")
          text (f/fixture-feature {:tags (str "@" fixture-id " @e2e") :feature-name "Spec check" :description [sentence]})
          line (some (fn [[i l]] (when (str/includes? l sentence) (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (assoc (fixture! ctx text) :line line :word word))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx] (fails-at! ctx (:word ctx))))
