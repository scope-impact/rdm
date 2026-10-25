(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature, in a copy of the room's
  spec whose check feature file (and, where a scenario needs two, the stamper
  feature file) is replaced by a fixture. Fixture scenarios carry the ids of
  the scenarios they stand for, which the replaced file held, so they collide
  with no other file's.

  Two steps share their wording with other features: \"it passes\"
  (dhf/spec/project/init.feature, dhf/spec/spec/parser.feature, ...) and
  \"the configured stack words include ...\" (dhf/spec/knowledge/wiki.feature).
  Each is defined only when no other step file defines it, with the same
  meaning, so never twice."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(def check-file "dhf/spec/spec/check.feature")

(defn- fixture!
  "ctx with a copy of the room's spec whose check feature file is `text`."
  [ctx text]
  (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
  (let [ctx (f/ensure-room! ctx)]
    (f/feature! (:dir ctx) "spec/check" text)
    (assoc ctx :file check-file :text text)))

(defn- scenario!
  "A fixture of one scenario, `id` among its tags `tags`."
  [ctx id tags & [opts]]
  (let [text (f/fixture-feature (merge {:tags tags :name "A fixture scenario" :feature-name "Spec check"} opts))]
    (assoc (fixture! ctx text) :id id :line (f/scenario-line text "A fixture scenario"))))

(defn- findings
  "The run's finding lines (no warnings, no events)."
  [ctx]
  (remove #(re-find #"(^|: )warning: " %) (f/finding-lines (:run ctx))))

(defn- fails-with!
  "Checks the run exited 1 and one finding line (never a warning) holds every
  one of `parts`."
  [ctx & parts]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l %) parts)) (findings ctx))
              (str "no finding names all of " (pr-str parts) "\n" (s/show r)))))

;; ---------------------------------------------------------------------------
;; Ids

(defstep #"a scenario tagged both @(\S+) and @(\S+)"
  (fn [ctx a b]
    (assoc (scenario! ctx a (str "@" a " @" b " @e2e")) :ids [a b] :title "A fixture scenario")))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx]
    (apply fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") (:title ctx) (:ids ctx))))

(defstep #"two scenarios both tagged @(\S+)"
  (fn [ctx id]
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [ctx (f/ensure-room! ctx)
          tags (str "@" id " @e2e")
          files (f/features! (:dir ctx)
                             {"spec/check" (f/fixture-feature {:tags tags :name "The first carrier"})
                              "spec/stamper" (f/fixture-feature {:tags tags :name "The second carrier"})})]
      (assoc ctx :files files :id id))))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (apply fails-with! ctx (:id ctx) (:files ctx))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx n]
    (let [n (parse-long n)
          base (f/scenario-line (f/fixture-feature {:tags "@e2e"}) "A fixture scenario")
          _ (s/check! (>= n base) (str "a fixture scenario cannot sit above line " base))
          leading (repeat (- n base) "# A comment that moves the scenario down.")
          text (f/fixture-feature {:tags "@e2e" :leading leading})]
      (s/check! (= n (f/scenario-line text "A fixture scenario")) "the fixture is not on the line asked")
      (assoc (fixture! ctx text) :line n))))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx n]
    (fails-with! ctx (str (:file ctx) ":" n ":") "rdm-spec stamp")))

(defstep #"the ledger has issued ids up to (\d+) and a scenario is tagged @(\S+)"
  (fn [ctx n id]
    (let [ctx (scenario! ctx id (str "@" id " @e2e"))]
      (f/write-ledger! (:dir ctx) (parse-long n))
      ctx)))

(defstep #"it fails, names (\S+) and says the ledger never issued it"
  (fn [ctx id]
    (fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") id "the ledger never issued it")))

;; ---------------------------------------------------------------------------
;; Levels and tags

(def ^:private levels #{"@e2e" "@contract"})

(defstep #"a stamped scenario tagged (@\S+)"
  ;; A tag that is not a level comes with a level, so the tag is all that is wrong.
  (fn [ctx tag]
    (scenario! ctx "spec-0044" (str "@spec-0044 " tag (when-not (levels tag) " @e2e")))))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! r "no command was run")
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (not-any? #(and (str/starts-with? % (str (:file ctx) ":")) (str/includes? % "check-level"))
                          (findings ctx))
                (str "a level finding names " (:file ctx) "\n" (s/show r)))
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r))))))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (scenario! ctx "spec-0045" "@spec-0045")))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (scenario! ctx "spec-0046" "@spec-0046 @e2e @contract")))

(defstep #"it fails and names the scenario's id and (the two|both) levels"
  (fn [ctx _]
    (fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") (:id ctx) "@e2e" "@contract")))

(defstep #"it fails, names the scenario's id and (@\S+), and lists the tags a scenario may carry"
  (fn [ctx tag]
    (fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") (:id ctx) tag "@e2e" "@contract" "@UN-" "@wip")))

;; ---------------------------------------------------------------------------
;; Needs

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc (scenario! ctx "spec-0047" "@spec-0047 @e2e" {:feature-tags (str "@" need)}) :need need)))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (s/check! (:text ctx) "no feature was written")
    (s/check! (not-any? #(re-find #"^\s+@.*@UN-" %) (str/split-lines (:text ctx)))
              "the fixture scenario carries a need tag")
    ctx))

(defn- maps-in [v] (filter map? (tree-seq coll? seq v)))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (:run ctx)
          res (f/result r)]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (map? res) (str "the result is not EDN\n" (s/show r)))
      (s/check! (some #(= (:id ctx) (:id %)) (mapcat :scenarios (maps-in res)))
                (str (:id ctx) " is not listed\n" (s/show r))))))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (scenario! ctx "spec-0048" "@spec-0048 @e2e" {:feature-tags ""})))

(defstep #"it fails and names the scenario's id"
  (fn [ctx]
    (fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") (:id ctx))))

(defstep #"the declared needs are (UN-\d+) to (UN-\d+)"
  (fn [ctx from to]
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [ctx (f/ensure-room! ctx)
          n #(parse-long (subs % 3))
          [lo hi] [(n from) (n to)]]
      (f/needs! (:dir ctx) (fn [m] (into {} (filter (fn [[k _]] (<= lo (n (str k)) hi)) m))))
      ctx)))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc (scenario! ctx "spec-0049" (str "@spec-0049 @e2e @" need) {:feature-tags "@UN-002"}) :need need)))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need]
    (fails-with! ctx (str (:file ctx) ":" (:line ctx) ":") need (:id ctx))))

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [ctx (f/ensure-room! ctx)]
      (f/needs! (:dir ctx) #(assoc-in % [need :sources] []))
      (assoc ctx :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need]
    (fails-with! ctx "dhf/spec/needs.edn:" need)))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx]
    (apply s/needs-built! f/check-stages)
    (f/ensure-shared-steps!)
    (let [ctx (scenario! ctx "spec-0050" "@spec-0050 @e2e @wip")]
      (f/generate-views! (:dir ctx))
      ctx)))

;; ---------------------------------------------------------------------------
;; The stack

;; Shared in wording with dhf/spec/knowledge/wiki.feature: the same meaning
;; there (the word is added to the configured stack words of a copy of the
;; room), defined here only when no file loaded earlier defines it.
(when-not (some #(re-matches (:pattern %) "the configured stack words include \"Cobalt\"")
                @rdm-spec.kernel.runner/*registry*)
  (defstep #"the configured stack words include \"([^\"]+)\""
    (fn [ctx word]
      (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))]
        (f/config! (:dir ctx) (fn [c] (update c :stack-words #(vec (distinct (conj (vec %) word))))))
        (assoc ctx :stack-word word)))))

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [sentence (str "The parser is written in " word ".")
          text (f/fixture-feature {:tags "@spec-0209 @e2e" :description [sentence]})
          line (some (fn [[i l]] (when (str/includes? l sentence) (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (assoc (fixture! ctx text) :word word :word-line line))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx]
    (fails-with! ctx (str (:file ctx) ":" (:word-line ctx) ":") (:word ctx))))

;; "it passes" is shared with other features: registered at run time by
;; f/ensure-shared-steps! (in the @wip Given), unless another step file
;; defines it.
