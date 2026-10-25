(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature: `rdm-spec check` on a
  small project the scenario makes (`rdm-spec init`, then one bounded context
  demo with one component, Alpha, owning dhf/spec/demo/alpha.feature, its
  views generated so the whole check can pass), or, where a step needs the
  room's spec, on a copy of it.

  The shared When (\"rdm-spec LINE\" is run), \"it passes\" and \"the
  configured stack words include W\" are the kernel's (steps.kernel-command).
  spec-0047's Then reads trace's result only as its contract scenario states
  it (dhf/spec/verification/trace.feature, spec-0279):
  {:trace {:kind :need :id .. :text .. :scenarios [{:id ..} ..]}}.

  Context keys: :dir the project; :run the last run; :file the fixture
  feature file (relative to :dir); :id the scenario's id; :ids its ids; :line
  its line; :word the stack word's line; :need a need id."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as f]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The small project

(def feature-rel "dhf/spec/demo/alpha.feature")

(def ^:private model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any"
                 :description "The demo program"
                 :contexts [{:id :demo :name "demo" :description "The demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha"
                                           :description "The demo component"}]}]}]
   :relationships []
   :views [{:key "demo-components" :level :component :context :demo
            :description "The demo context's components"}]})

(def ^:private citation "demo:README.md@abc1234")

(defn needs-of
  "Declared needs UN-001 to UN-00n, each citing the fixture's source."
  [n]
  (into (sorted-map) (for [i (range 1 (inc n))]
                       [(format "UN-%03d" i) {:text (str "Need number " i ".") :sources [citation]}])))

(defn- write-edn! [dir rel v] (s/write! dir rel (str (pr-str v) "\n")))

(defn project!
  "A project set up by rdm-spec whose spec is one component, Alpha, owning
  `text`; ledger at 100, needs UN-001 to UN-005. Its views are generated."
  [ctx text]
  (let [parent (kc/tmp-dir)
        r (kc/run! ctx parent ["init" "project"])
        _ (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
        dir (str (fs/file parent "project"))]
    (write-edn! dir "dhf/spec/model.edn" model)
    (s/write! dir "dhf/spec/ids.edn" "{:issued 100}\n")
    (write-edn! dir "dhf/spec/needs.edn" (needs-of 5))
    (write-edn! dir "dhf/raw/sources.edn" {:demo {:repository "https://example.invalid/demo" :record "demo.md"
                                                  :pins ["abc1234"] :fenced []}})
    (s/write! dir "dhf/raw/demo.md" "# Demo\n\nA demo source.\n")
    (s/write! dir feature-rel text)
    (when (s/built? 'rdm-spec.architecture.views)
      (let [v (kc/run! ctx dir ["views"])]
        (s/check! (zero? (:exit v)) (str "rdm-spec views failed\n" (s/show v)))))
    (assoc ctx :dir dir :file feature-rel)))

(defn feature
  "A feature file: `feature-tags` on the feature, then each scenario
  [title tags] under one rule; `leading` comment lines before it all."
  [{:keys [feature-tags scenarios leading description]}]
  (str (str/join (map #(str "# " % "\n") leading))
       (when (seq feature-tags) (str feature-tags "\n"))
       "Feature: Alpha\n"
       "  The demo component.\n"
       (str/join (map #(str "  " % "\n") description))
       "\n  Rule: The demo rule\n"
       (str/join (for [[title tags] scenarios]
                   (str "\n" (when (seq tags) (str "    " tags "\n"))
                        "    Scenario: " title "\n"
                        "      Given a step\n"
                        "      Then another\n")))))

(defn- one!
  "The small project holding one scenario titled `title` with `tags` under a
  feature tagged `feature-tags` (default @UN-001)."
  ([ctx tags] (one! ctx tags "@UN-001"))
  ([ctx tags feature-tags]
   (let [text (feature {:feature-tags feature-tags :scenarios [["The demo scenario" tags]]})]
     (assoc (project! ctx text)
            :line (f/scenario-line text "The demo scenario")
            :text text))))

(defn- run [ctx] (s/check! (:run ctx) "the command was not run") (:run ctx))

(defn- fails-with!
  "The run exited 1 and one finding line satisfies `pred`."
  [ctx pred what]
  (let [r (run ctx)]
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (some pred (f/finding-lines r)) (str "no finding " what "\n" (s/show r)))
    nil))

(defn- has-all [& parts] (fn [l] (every? #(str/includes? l %) parts)))

;; ---------------------------------------------------------------------------
;; One id per scenario

(defstep #"a scenario tagged both @(\S+) and @(\S+)"
  (fn [ctx a b]
    (assoc (one! ctx (str "@" a " @" b " @e2e")) :ids [a b])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx]
    (let [[a b] (:ids ctx)]
      (fails-with! ctx (has-all (str (:file ctx) ":" (:line ctx) ":") "The demo scenario" a b)
                   (str "names the scenario at " (:file ctx) ":" (:line ctx) ", " a " and " b)))))

(defstep #"two scenarios both tagged @(\S+)"
  (fn [ctx id]
    (let [text (feature {:feature-tags "@UN-001" :scenarios [["The first scenario" (str "@" id " @e2e")]]})
          ctx (project! ctx text)
          dir (:dir ctx)
          other "dhf/spec/demo/beta.feature"]
      ;; The second file belongs to a second component of the same context.
      (f/update-edn! dir "dhf/spec/model.edn"
                     #(update-in % [:containers 0 :contexts 0 :components] conj
                                 {:id :demo/beta :name "Beta" :spec "demo/beta" :description "A second component"}))
      (s/write! dir other (str/replace (feature {:feature-tags "@UN-001"
                                                 :scenarios [["The second scenario" (str "@" id " @e2e")]]})
                                       "Feature: Alpha" "Feature: Beta"))
      (assoc ctx :id id :files [feature-rel other]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (let [[a b] (:files ctx)]
      (fails-with! ctx (has-all a b (:id ctx)) (str "names " a ", " b " and " (:id ctx))))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx n]
    (let [n (parse-long n)
          ;; the scenario's keyword line is 9 lines after the leading comments
          probe (feature {:feature-tags "@UN-001" :scenarios [["An unstamped scenario" "@e2e"]]})
          at (f/scenario-line probe "An unstamped scenario")
          pad (- n at)
          _ (s/check! (>= pad 0) (str "the scenario cannot sit on line " n))
          text (feature {:feature-tags "@UN-001" :scenarios [["An unstamped scenario" "@e2e"]]
                         :leading (repeat pad "A comment line, to place the scenario.")})]
      (s/check! (= n (f/scenario-line text "An unstamped scenario")) "the fixture's scenario is misplaced")
      (assoc (project! ctx text) :line n))))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx n]
    (fails-with! ctx (has-all (str (:file ctx) ":" n ":") "rdm-spec stamp")
                 (str "names " (:file ctx) ":" n " and says to run rdm-spec stamp"))))

(defstep #"a scenario tagged @(\S+), an id above every id the ledger has issued"
  (fn [ctx id]
    (let [ctx (one! ctx (str "@" id " @e2e"))
          issued (:issued (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/ids.edn")))))]
      (s/check! (> (parse-long (re-find #"\d+$" id)) issued) (str id " is not above the ledger's " issued))
      (assoc ctx :id id))))

(defstep #"it fails, names (\S+) and says the ledger never issued it"
  (fn [ctx id]
    (fails-with! ctx #(and (str/includes? % id) (re-find #"(?i)ledger" %) (re-find #"(?i)never issued" %))
                 (str "names " id " and says the ledger never issued it"))))

;; ---------------------------------------------------------------------------
;; One level per scenario

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [ctx level]
    (assoc (one! ctx (str "@spec-0007 " level)) :id "spec-0007" :level level)))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (not-any? #(str/includes? % (:file ctx)) (f/finding-lines r))
                (str "a finding names the fixture\n" (s/show r)))
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      nil)))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (assoc (one! ctx "@spec-0007") :id "spec-0007")))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx]
    (fails-with! ctx (has-all (str (:file ctx) ":" (:line ctx) ":") (:id ctx) "@e2e" "@contract")
                 (str "names " (:id ctx) ", @e2e and @contract at its line"))))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (assoc (one! ctx "@spec-0007 @e2e @contract") :id "spec-0007")))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx]
    (fails-with! ctx (has-all (str (:file ctx) ":" (:line ctx) ":") (:id ctx) "@e2e" "@contract")
                 (str "names " (:id ctx) " and both levels at its line"))))

(defstep #"a stamped scenario tagged (@persona)"
  (fn [ctx tag] (assoc (one! ctx (str "@spec-0007 @e2e " tag)) :id "spec-0007" :tag tag)))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx]
    (fails-with! ctx (has-all (:id ctx) (:tag ctx) "@e2e" "@contract" "@wip" "@UN-" "@spec-")
                 (str "names " (:id ctx) ", " (:tag ctx) " and the tags a scenario may carry"))))

;; ---------------------------------------------------------------------------
;; Every scenario traces to a user need

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need]
    (let [text (feature {:feature-tags (str "@" need) :scenarios [["An inheriting scenario" "@spec-0007 @e2e"]]})]
      (assoc (project! ctx text) :need need :id "spec-0007" :text text))))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (let [sc (first (filter #(= "An inheriting scenario" (:name %))
                            (:scenarios (gherkin/parse-feature (:file ctx) (:text ctx)))))]
      (s/check! sc "the feature holds no scenario")
      (s/check! (not-any? #(re-matches #"@UN-\d+" %) (:tags sc)) "the scenario carries a need tag of its own")
      nil)))

(defstep #"no results directory exists"
  (fn [ctx]
    (let [dir (:dir ctx)
          rd (:results-dir (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))]
      (when (and rd (fs/exists? (fs/file dir rd))) (fs/delete-tree (fs/file dir rd)))
      (assoc-in ctx [:env "RESULTS_DIR"] nil))))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          ids (map :id (get-in (kc/stdout-edn r) [:trace :scenarios]))]
      (s/check! (some #{(:id ctx)} ids) (str "the trace lists " (pr-str ids) ", not " (:id ctx) "\n" (s/show r)))
      nil)))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (assoc (one! ctx "@spec-0007 @e2e" nil) :id "spec-0007")))

(defstep #"it fails and names the scenario's id"
  (fn [ctx]
    (fails-with! ctx (has-all (str (:file ctx) ":" (:line ctx) ":") (:id ctx))
                 (str "names " (:id ctx) " at its line"))))

(defstep #"the declared needs are UN-001 to UN-(\d+)"
  (fn [ctx n]
    (let [ctx (one! ctx "@spec-0007 @e2e")]
      (f/update-edn! (:dir ctx) "dhf/spec/needs.edn" (fn [_] (needs-of (parse-long n))))
      ctx)))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need]
    (let [text (feature {:feature-tags "@UN-001" :scenarios [["The demo scenario" (str "@spec-0007 @e2e @" need)]]})]
      (s/write! (:dir ctx) feature-rel text)
      (assoc ctx :id "spec-0007" :need need :line (f/scenario-line text "The demo scenario")))))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need]
    (fails-with! ctx (has-all need (:id ctx)) (str "names " need " and " (:id ctx)))))

;; ---------------------------------------------------------------------------
;; Every user need rests on a source

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (let [ctx (one! ctx "@spec-0007 @e2e")]
      (f/update-edn! (:dir ctx) "dhf/spec/needs.edn" #(assoc-in % [need :sources] []))
      (assoc ctx :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need]
    (fails-with! ctx (has-all need) (str "names " need))))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx] (assoc (one! ctx "@spec-0007 @e2e @wip") :id "spec-0007")))

;; ---------------------------------------------------------------------------
;; No stack word

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (s/check! (:dir ctx) "no project: configure the stack words first")
    (let [text (f/fixture-feature {:tags "@spec-0051 @e2e"
                                   :description [(str "The parser is written in " word ".")]})
          file (f/feature! (:dir ctx) "spec/parser" text)
          line (some (fn [[i l]] (when (str/includes? l word) (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (assoc ctx :file file :line line :word word))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx]
    (fails-with! ctx (has-all (str (:file ctx) ":" (:line ctx) ":") (:word ctx))
                 (str "names " (:file ctx) ":" (:line ctx) " and " (:word ctx)))))
