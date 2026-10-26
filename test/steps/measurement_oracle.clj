(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature: `rdm-spec
  oracle` on fixture oracles these steps build, never the real artifact.

  A fixture oracle is an oracle.edn (`fixture`) pushed into a local OCI
  layout under the fixture root (`push!`); the scenario's project (`:dir`)
  names it by digest in its :oracle, and its commands run with
  ORACLE_LAYOUT set to the layout (`:env`). Goldens are recorded by the
  oracle itself: a fixture is pushed with every case's golden nil (a new
  case), then `rdm-spec oracle record --reason` fills them (`recorded!`).

  The fixtures (`fixture`): :project, a small project (the room's
  configuration without :oracle, model, events and needs, and one feature
  file, dhf/spec/architecture/lint.feature, holding spec-0012, spec-0014,
  the outline spec-0015 of three rows, and spec-0013 tagged @wip);
  :cycles \"01\" and \"02\" at the cut spec-3; :results, result sets
  written as rdm-spec's default mapping fills them; :cuts, pairs of cuts.

  Context keys set here: :dir, :env, :layout, :digest (the digest the
  project names before the When), :oracle-before (the oracle.edn it names
  before the When), :config-before (the configuration's text), :changed
  ([component case] the Given broke)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(def repository "ghcr.io/scope-impact/rdm-spec-oracle-fixture")

;; ---------------------------------------------------------------------------
;; The store: a local OCI layout

(defn- oras! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"TMPDIR" (s/fixture-root)}}
                 "oras" (map str args))]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (:out r)))

(defn push!
  "Pushes `oracle` (data) as oracle.edn into the layout `layout`; its digest."
  [layout oracle]
  (let [d (s/tmp-dir)]
    (spit (str (fs/file d "oracle.edn")) (binding [*print-namespace-maps* false] (pr-str oracle)))
    (:digest (json/parse-string (oras! d "push" "--oci-layout" layout "--artifact-type" artifact-type
                                       "--format" "json" "oracle.edn:application/edn")
                                true))))

(defn pull
  "The oracle.edn of `digest` in `layout`, read."
  [layout digest]
  (let [d (s/tmp-dir)]
    (oras! d "pull" "--oci-layout" (str layout "@" digest) "-o" "out")
    (s/check! (= ["oracle.edn"] (mapv #(str (fs/file-name %)) (fs/list-dir (fs/file d "out"))))
              "the artifact does not hold the one file oracle.edn")
    (edn/read-string (slurp (str (fs/file d "out" "oracle.edn"))))))

;; ---------------------------------------------------------------------------
;; The fixture project

(def ^:private lint-rel "dhf/spec/architecture/lint.feature")

(def ^:private lint-steps ["Given a model" "When \"rdm-spec check\" is run" "Then it passes"])

(def ^:private lint-text
  (str "# A fixture feature file of the oracle's steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       "\n  @spec-0012 @e2e\n  Scenario: A view with no description fails the lint\n"
       (str/join (map #(str "    " % "\n") lint-steps))
       "\n  @spec-0014 @contract\n  Scenario: A view over the limit fails the lint\n"
       (str/join (map #(str "    " % "\n") lint-steps))
       "\n  @spec-0015 @e2e\n  Scenario Outline: A view of <n> elements\n    Given a model of <n> elements\n    Then it passes\n"
       "\n    Examples:\n      | n |\n      | 1 |\n      | 2 |\n      | 3 |\n"
       "\n  @spec-0013 @e2e @wip\n  Scenario: A component that owns no feature file fails the lint\n"
       (str/join (map #(str "    " % "\n") lint-steps))))

(defn- room-file [rel] (slurp (str (fs/file s/room-root rel))))

(defn- fixture-config []
  (-> (kc/config-of s/room-root)
      (dissoc :oracle)
      (assoc :verify [{:kind :tests :command ["bb" "test.clj"]}])))

(defn project-files []
  {"dhf/harness/config.edn" (pr-str (fixture-config))
   "dhf/spec/model.edn" (room-file "dhf/spec/model.edn")
   "dhf/spec/events.edn" (room-file "dhf/spec/events.edn")
   "dhf/spec/needs.edn" (room-file "dhf/spec/needs.edn")
   "dhf/spec/ids.edn" "{:issued 15}\n"
   lint-rel lint-text})

(def ^:private roles
  {:builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- cycle-of [n verdicts]
  {:cycle (merge {:cut "spec-3" :scope :project} roles)
   :ratified {:cut "spec-3" :room-tree (apply str (repeat 40 n)) :outcome :passed :checkout (str "dhf/cycles/0" n "/build")}
   :verdicts verdicts})

(def cycles
  {"01" (cycle-of 1 [{:id "spec-0012" :verdict :pass :evidence "it fails the lint"}
                     {:id "spec-0014" :verdict :fail :evidence "it passes the lint"}
                     {:id "spec-0015" :verdict :pass :evidence "every row"}])
   "02" (cycle-of 2 [{:id "spec-0012" :verdict :pass :evidence "it fails the lint"}
                     {:id "spec-0014" :verdict :pass :evidence "it fails the lint now"}
                     {:id "spec-0015" :verdict :fail :evidence "a row is wrong"}])})

;; --- result sets -------------------------------------------------------------

(defn- result-of
  "A result of `id` (and an outline row `n`) as rdm-spec's default mapping fills it."
  [id title steps tags status & [n]]
  {:uuid (str id (when n (str "-" n))) :historyId (str id (when n (str "|" n))) :name title
   :fullName (str lint-rel ":" title) :status status :stage "finished" :start 1700000000000 :stop 1700000000100
   :labels (vec (concat [{:name "feature" :value "Lint"} {:name "epic" :value "architecture"}
                         {:name "suite" :value "architecture"} {:name "subSuite" :value "architecture/lint"}]
                        (for [t tags] {:name "tag" :value t})
                        [{:name "framework" :value "gherkin"} {:name "spec" :value id}
                         {:name "context" :value "architecture"} {:name "component" :value "Lint"}]))
   :links [{:type "issue" :name "UN-005" :url "dhf/spec/needs.edn#UN-005"}]
   :parameters (if n [{:name "n" :value (str n)}] [])
   :attachments []
   :steps (vec (for [st steps] {:name st :status status :start 1700000000000 :stop 1700000000100}))})

(defn- row-steps [n] [(str "Given a model of " n " elements") "Then it passes"])

(defn passing-results
  "{id-or-row result} of every stamped scenario not tagged @wip, passing."
  []
  (into (sorted-map)
        (concat [["spec-0012" (result-of "spec-0012" "A view with no description fails the lint" lint-steps ["UN-005" "spec-0012" "e2e"] "passed")]
                 ["spec-0014" (result-of "spec-0014" "A view over the limit fails the lint" lint-steps ["UN-005" "spec-0014" "contract"] "passed")]]
                (for [n [1 2 3]]
                  [(str "spec-0015-" n) (result-of "spec-0015" "A view of <n> elements" (row-steps n) ["UN-005" "spec-0015" "e2e"] "passed" n)]))))

(defn- relabel [r n v] (update r :labels (fn [ls] (mapv #(if (= n (:name %)) (assoc % :value v) %) ls))))

(def result-sets
  {"steps-match" (passing-results)
   "steps-differ" (update (passing-results) "spec-0012" #(assoc-in % [:steps 1 :name] "When it is linted again"))
   "wrong-story" (update (passing-results) "spec-0012" #(update % :labels conj {:name "story" :value "Something else"}))
   "no-need-link" (update (passing-results) "spec-0012" #(assoc % :links []))
   "no-trace" (passing-results)
   "outline-rows" (dissoc (passing-results) "spec-0015-3")
   "unknown-result" (assoc (passing-results) "spec-9999" (relabel (result-of "spec-9999" "Nothing" [] [] "passed") "spec" "spec-9999"))})

(defn- files-of-results [rs]
  (into (sorted-map) (for [[k r] rs] [(str k "-result.json") (json/generate-string r {:pretty true})])))

;; --- cut pairs -----------------------------------------------------------------

(def ^:private events (edn/read-string (room-file "dhf/spec/events.edn")))

(defn- base-cut []
  {"dhf/harness/config.edn" (pr-str (fixture-config))
   "dhf/spec/model.edn" (room-file "dhf/spec/model.edn")
   "dhf/spec/events.edn" (pr-str events)})

(defn- feature [nm] (str "Feature: " nm "\n  A fixture.\n\n  @spec-0001 @e2e @UN-001\n  Scenario: One\n    Given a model\n"))

(def ^:private ratify-model
  (let [m (edn/read-string (room-file "dhf/spec/model.edn"))]
    (pr-str (update-in m [:containers 0 :contexts] conj
                       {:id :ratification :name "ratification" :description "The old gate"
                        :components [{:id :ratification/ratify :name "Ratify" :spec "ratification/ratify" :description "Ratifies"}]}))))

(def cut-pairs
  {"flat-read-by" {"spec-3" (base-cut)
                   "spec-4" (assoc (base-cut) "dhf/spec/events.edn"
                                   (pr-str (update-in events [:SpecParsed :shape :needs] str/replace ":sources [citation]}" ":sources [citation] :x}")))}
   "first-read-by" {"spec-3" (assoc (base-cut) "dhf/spec/events.edn" (pr-str (update events :SpecGated dissoc :read-by)))
                    "spec-4" (base-cut)}
   "order" {"spec-3" (merge (base-cut) {"dhf/spec/kernel/command.feature" (feature "Command")
                                         "dhf/spec/spec/parser.feature" (feature "Parser")
                                         "dhf/spec/verification/status.feature" (feature "Status")})
            "spec-4" (merge (base-cut) {"dhf/spec/kernel/command.feature" (feature "Command, again")
                                         "dhf/spec/spec/parser.feature" (feature "Parser, again")
                                         "dhf/spec/verification/status.feature" (feature "Status, again")})}
   "removed-component" (let [impl {"src/rdm_spec/ratification/ratify.clj" ";; ratify\n"
                                   "test/rdm_spec/ratification/ratify_test.clj" ";; ratify's tests\n"
                                   "src/rdm_spec/spec/parser.clj" ";; the parser\n"}]
                         {"spec-3" (merge (base-cut) impl {"dhf/spec/model.edn" ratify-model})
                          "spec-4" (merge (base-cut) impl)})
   "refusals" {"spec-3" (merge (base-cut)
                               {"dhf/spec/needs.edn" (room-file "dhf/spec/needs.edn")
                                "dhf/spec/ids.edn" "{:issued 3}\n"
                                "dhf/spec/architecture/lint.feature"
                                (str "@UN-001\nFeature: Lint\n  A fixture written in Clojure.\n\n"
                                     "  @e2e\n  Scenario: No id\n    Given a model\n\n"
                                     "  @spec-0001 @e2e @contract\n  Scenario: Two levels\n    Given a model\n\n"
                                     "  @spec-0002 @e2e @shiny\n  Scenario: An unknown tag\n    Given a model\n\n"
                                     "  @spec-0003 @e2e @UN-999\n  Scenario: An unknown need\n    Given a model\n")})}})

;; ---------------------------------------------------------------------------
;; Fixture oracles

(def judging
  [:measurement/checklist :measurement/scorer :measurement/scoreboard :measurement/oracle
   :verification/verify :verification/status :regeneration/scopes :regeneration/fence :spec/check])

(defn fixture
  "An oracle.edn holding the shared fixtures and, for each [component case]
  of `cases`, a case with no golden yet. `opts` :evidence adds rdm-spec's
  default mapping with that :evidence to the project's configuration."
  ([cases] (fixture cases {}))
  ([cases {:keys [evidence no-cycles?]}]
   (let [project (cond-> (project-files)
                   evidence (assoc "dhf/harness/config.edn"
                                   (pr-str (assoc (fixture-config) :allure
                                                  {:epic :context :feature :feature :story :rule :name :scenario
                                                   :step :gherkin-step :parameter :example-row
                                                   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                                                   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}}
                                                   :evidence evidence}))))]
     (merge {:project project
             :cycles (if no-cycles? {} cycles)
             :results (into (sorted-map) (for [[k v] result-sets] [k (files-of-results v)]))
             :cuts cut-pairs}
            (reduce (fn [m [c k]] (assoc-in m [c k] nil)) {} cases)
            {:recorded []}))))

(defn oracle-project!
  "ctx with a project (:dir) whose configuration names `oracle`, pushed into
  a new layout (:layout), and the environment that reaches it (:env)."
  [ctx oracle]
  (let [layout (str (fs/file (s/tmp-dir) "layout"))
        digest (push! layout oracle)
        dir (s/project! {:oracle {:repository repository :digest digest}})]
    (s/write! dir "dhf/spec/model.edn" (room-file "dhf/spec/model.edn"))
    (assoc ctx :dir dir :layout layout :env (assoc (:env ctx) "ORACLE_LAYOUT" layout))))

(defn- digest-of [ctx] (get-in (kc/config-of (:dir ctx)) [:oracle :digest]))

(defn recorded!
  "ctx after the oracle records every new case's golden."
  [ctx]
  (let [r (kc/run! ctx (:dir ctx) ["oracle" "record" "--reason" "the first goldens"])]
    (s/check! (zero? (:exit r)) (str "recording the first goldens failed\n" (s/show r)))
    ctx))

(defn- current [ctx] (pull (:layout ctx) (digest-of ctx)))

(defn- republish!
  "ctx after the project's oracle is `f` of what it was, pushed anew."
  [ctx f]
  (let [d (push! (:layout ctx) (f (current ctx)))]
    (s/write! (:dir ctx) "dhf/harness/config.edn" (pr-str (assoc-in (kc/config-of (:dir ctx)) [:oracle :digest] d)))
    ctx))

(defn- before-when [ctx]
  (assoc ctx :digest (digest-of ctx) :oracle-before (current ctx)
         :config-before (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))))

(defn- built!
  "ctx with an oracle project holding `cases` with their goldens recorded."
  ([ctx cases] (built! ctx cases {}))
  ([ctx cases opts] (before-when (recorded! (oracle-project! ctx (fixture cases opts))))))

(defn- compared [ctx]
  (let [r (kc/the-run ctx)]
    (set (map (juxt :component :case) (get-in (kc/stdout-edn r) [:oracle :compared])))))

(defn- line-naming! [ctx & parts]
  (let [r (kc/the-run ctx)]
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (kc/finding-lines r))
              (str "no finding names all of " (pr-str parts) "\n" (s/show r)))))

;; ---------------------------------------------------------------------------
;; The oracle is one EDN file in one artifact, pinned by digest

(def ^:private every-case
  [[:measurement/checklist "01"] [:measurement/checklist "02"] [:measurement/scorer "01"] [:measurement/scorer "02"]
   [:measurement/scoreboard "01-02"] [:measurement/oracle "self"] [:verification/verify "steps-match"]
   [:verification/status "outline-rows"] [:regeneration/scopes "flat-read-by"] [:regeneration/fence "removed-component"]
   [:spec/check "refusals"]])

(defstep #"the configured oracle holds fixture cycles, fixture result sets, fixture cuts, and goldens for every judging component"
  (fn [ctx] (built! ctx every-case)))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx _doc]
    (kc/exits! ctx 0)
    (let [out (:oracle (kc/stdout-edn (kc/the-run ctx)))
          o (pull (:layout ctx) (:digest ctx))]
      (s/check! (= [repository (:digest ctx)] [(:repository out) (:digest out)]) (str "it read " (pr-str (select-keys out [:repository :digest]))))
      (s/check! (= (set (concat [:project :cycles :results :cuts :recorded] judging)) (set (keys o)))
                (str "oracle.edn holds the keys " (sort (keys o))))
      (s/check! (every? #(and (string? (key %)) (string? (val %))) (:project o)) ":project is not {path text}")
      (s/check! (every? (fn [[k c]] (and (re-matches #"\d\d" k) (= #{:cycle :ratified :verdicts} (set (keys c))))) (:cycles o))
                ":cycles is not {\"NN\" {:cycle :ratified :verdicts}}")
      (s/check! (every? (fn [[_ fs*]] (every? #(str/ends-with? % "-result.json") (keys fs*))) (:results o))
                ":results is not {name {\"<uuid>-result.json\" text}}")
      (s/check! (every? (fn [[_ cs]] (every? #(re-matches #"spec-\d+" %) (keys cs))) (:cuts o)) ":cuts is not {name {cut {path text}}}")
      (s/check! (every? (fn [c] (every? map? (vals (get o c)))) judging) "a golden is missing")
      (s/check! (and (vector? (:recorded o)) (every? #(and (:date %) (:reason %) (vector? (:changed %))) (:recorded o)))
                ":recorded is not [{:date :reason :changed}]")
      nil)))

(defstep #"it exits 0 and names each golden it compared, as its component and case"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= (set every-case) (compared ctx)) (str "it compared " (sort (compared ctx))))
    nil))

(defstep #"the configured oracle holds goldens for the components :measurement/scorer and :regeneration/scopes, one case each, both reproduced"
  (fn [ctx] (built! ctx [[:measurement/scorer "01"] [:regeneration/scopes "flat-read-by"]])))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:oracle .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          want {:oracle {:repository repository :digest (:digest ctx)
                         :compared [{:component :measurement/scorer :case "01"}
                                    {:component :regeneration/scopes :case "flat-read-by"}]
                         :differences 0}}]
      (s/check! (= want (kc/stdout-edn r)) (str "standard output is not exactly " (pr-str want) "\n" (s/show r)))
      nil)))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx]
    (let [ctx (oracle-project! ctx (assoc-in (fixture [[:regeneration/scopes "order"]] {:no-cycles? true})
                                             [:regeneration/scopes "order"] {:out {} :exit 0 :err ""}))]
      (before-when ctx))))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx "the oracle holds no fixture cycle, so it judged nothing")
    nil))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx]
    (let [ctx (oracle-project! ctx (fixture []))
          missing (str "sha256:" (apply str (repeat 64 "0")))]
      (s/write! (:dir ctx) "dhf/harness/config.edn" (pr-str (assoc-in (kc/config-of (:dir ctx)) [:oracle :digest] missing)))
      (assoc ctx :digest missing))))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx] (kc/refused-naming! ctx repository (:digest ctx)) nil))

;; ---------------------------------------------------------------------------
;; The instrument is judged against recorded goldens

(def ^:private measurement-cases
  [[:measurement/checklist "01"] [:measurement/checklist "02"] [:measurement/scorer "01"] [:measurement/scorer "02"]
   [:measurement/scoreboard "01-02"]])

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (built! ctx measurement-cases)))

(defstep #"it exits 0 and names each golden it compared"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= (set measurement-cases) (compared ctx)) (str "it compared " (sort (compared ctx))))
    nil))

(defn- score-file [o] (first (filter #(str/ends-with? % "score.edn") (keys (get-in o [:measurement/scorer "01" :wrote])))))

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (let [ctx (built! ctx [[:measurement/scorer "01"]])
          o (current ctx)
          f (score-file o)
          item (get-in o [:measurement/scorer "01" :wrote f :items 1])]
      (s/check! (= :fail (:verdict item)) (str "the scorer files " (pr-str item)))
      (before-when (republish! ctx #(assoc-in % [:measurement/scorer "01" :wrote f :items 1 :verdict] :pass))))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx ":measurement/scorer" "\"01\"" "spec-0014" ":pass" ":fail")
    nil))

(defstep #"the configured oracle holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (let [ctx (built! ctx [[:measurement/scoreboard "01-02"] [:regeneration/scopes "order"]])]
      (before-when (republish! (assoc ctx :changed [:measurement/scoreboard "01-02"])
                               #(assoc-in % [:measurement/scoreboard "01-02" :out :scoreboard :statements] ["an old statement"]))))))

(defn- new-oracle [ctx]
  (let [d (digest-of ctx)]
    (s/check! (not= d (:digest ctx)) "the configuration names the old digest")
    (pull (:layout ctx) d)))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and the component changed"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [o (new-oracle ctx)
          [c k] (:changed ctx)
          entry (last (:recorded o))]
      (s/check! (not= (get-in (:oracle-before ctx) [c k]) (get-in o [c k])) "the golden was not rewritten")
      (s/check! (not= ["an old statement"] (get-in o [c k :out :scoreboard :statements])) "the golden still holds the old statement")
      (s/check! (= (inc (count (:recorded (:oracle-before ctx)))) (count (:recorded o))) ":recorded did not gain one entry")
      (s/check! (re-matches #"\d{4}-\d\d-\d\d" (str (:date entry))) (str "the entry's date is " (pr-str (:date entry))))
      (s/check! (= "the scoreboard now says the denominator moved" (:reason entry)) (str "the entry's reason is " (pr-str (:reason entry))))
      (s/check! (some #(= c (:component %)) (:changed entry)) (str "the entry names " (pr-str (:changed entry))))
      nil)))

(defstep #"the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged"
  (fn [ctx]
    (s/check! (re-matches #"sha256:[0-9a-f]{64}" (str (digest-of ctx))) "the configuration names no digest")
    (s/check! (= (:oracle-before ctx) (pull (:layout ctx) (:digest ctx))) "the old artifact changed")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) ".git"))) "something was committed")
    nil))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx "a reason is required")
    (s/check! (= (:config-before ctx) (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))) "the configuration changed")
    (s/check! (= (:oracle-before ctx) (current ctx)) "the oracle changed")
    nil))

;; ---------------------------------------------------------------------------
;; Nothing is fenced or verified while the oracle is red

(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx]
    (let [ctx (built! ctx [[:regeneration/scopes "order"]])
          ctx (republish! ctx #(assoc-in % [:regeneration/scopes "order" :exit] 1))
          dir (:dir ctx)
          g (fn [& args] (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                                                 :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                                             "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                                          "git" args)]
                           (s/check! (zero? (:exit r)) (str "git failed: " (:err r)))))]
      (g "init" "-q")
      (s/hooks-off! dir)
      (g "add" "-A")
      (g "commit" "-q" "--no-verify" "-m" "the cut spec-3")
      (g "tag" "spec-3")
      (s/write! dir "dhf/cycles/03/cycle.edn" (pr-str (merge {:cut "spec-3" :scope :project} roles)))
      (before-when (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx does]
    (let [r (kc/exits! ctx 1)
          o (kc/run! ctx (:dir ctx) ["oracle"])]
      (s/check! (= 1 (:exit o)) (str "the oracle does not fail\n" (s/show o)))
      (s/check! (seq (kc/finding-lines o)) "the oracle names no finding")
      (s/check! (= (kc/finding-lines o) (kc/finding-lines r)) (str "the findings differ from the oracle's\n" (s/show r) "\n" (s/show o)))
      (if (= does "builds no room")
        (s/check! (not (fs/exists? (:room ctx))) "a room was built")
        (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
      nil)))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx]
    (let [dir (s/project!)]
      (s/check! (not (contains? (kc/config-of dir) :oracle)) "the configuration names an :oracle")
      (assoc ctx :dir dir))))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (:out r) "no oracle to run") (str "standard output does not say there is no oracle to run\n" (s/show r)))
      (s/check! (str/blank? (:err r)) (str "the error stream is not empty\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every component that judges has its goldens

(defstep #"the configured oracle holds, for (\S+), the case (\S+): (.+)"
  (fn [ctx component k _fixture]
    (let [c (keyword (subs component 1))
          opts (if (= "no-trace" k) {:evidence {:e2e ["browser-trace"]}} {})]
      (s/check! (some #{c} judging) (str component " is not a judging component"))
      (s/check! (contains? (merge result-sets cut-pairs {"self" 1 "01" 1 "01-02" 1}) k) (str "no fixture for the case " k))
      (assoc (built! ctx (cond-> [[c k]] (= c :measurement/oracle) (into [[:measurement/scorer "01"] [:regeneration/scopes "order"]])) opts)
             :changed [c k]))))

(defstep #"it runs (\S+) on that fixture and compares its standard output, exit and error stream with the golden, naming (\S+) and (\S+)"
  (fn [ctx _ _ _]
    (kc/exits! ctx 0)
    (let [[c k] (:changed ctx)
          g (get-in (current ctx) [c k])]
      (s/check! (contains? (compared ctx) [c k]) (str "it did not compare " c " " k))
      (s/check! (and (contains? g :out) (integer? (:exit g)) (string? (:err g))) (str "the golden is " (pr-str g)))
      (let [ctx (republish! ctx #(update-in % [c k] (fn [g] (-> g (assoc :out {:another :answer})
                                                                 (update :exit inc) (assoc :err "another line")))))
            r (kc/run! ctx (:dir ctx) ["oracle"])
            ls (kc/finding-lines r)
            names? (fn [what] (some #(and (str/includes? % (str c)) (str/includes? % (pr-str k)) (str/includes? % what)) ls))]
        (s/check! (= 1 (:exit r)) (str "a golden that differs passed\n" (s/show r)))
        (doseq [what ["standard output" "exit" "error stream"]]
          (s/check! (names? what) (str "no finding names " c " " k " and its " what "\n" (s/show r)))))
      nil)))

(defn- scopes-order-broken! [ctx]
  (republish! ctx #(assoc-in % [:regeneration/scopes "order" :out :scopes :scopes] [{:component :kernel/bus}])))

(defstep #"the configured oracle holds the golden of :regeneration/scopes \"order\", which the instrument no longer reproduces, and a new case \"flat-read-by\" with no golden"
  (fn [ctx]
    (let [ctx (scopes-order-broken! (built! ctx [[:regeneration/scopes "order"]]))]
      (before-when (republish! ctx #(assoc-in % [:regeneration/scopes "flat-read-by"] nil))))))

(defstep #"it exits 1, records nothing, and names :regeneration/scopes \"order\" as an existing golden the record would change"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx ":regeneration/scopes" "\"order\"" "existing golden")
    (s/check! (= (:digest ctx) (digest-of ctx)) "the configuration names a new digest")
    nil))

(defstep #"the configured oracle holds the golden of :regeneration/scopes \"order\", which the instrument no longer reproduces"
  (fn [ctx]
    (before-when (scopes-order-broken! (built! ctx [[:regeneration/scopes "order"] [:regeneration/scopes "flat-read-by"]
                                                    [:measurement/scorer "01"]])))))

(defstep #"it stores a new artifact whose :recorded gains the date, the reason and \{:component :regeneration/scopes :case \"order\"\}"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [o (new-oracle ctx)
          entry (last (:recorded o))]
      (s/check! (re-matches #"\d{4}-\d\d-\d\d" (str (:date entry))) (str "the entry is " (pr-str entry)))
      (s/check! (= "the kernel is listed first" (:reason entry)) (str "the entry is " (pr-str entry)))
      (s/check! (= [{:component :regeneration/scopes :case "order"}] (:changed entry)) (str "the entry is " (pr-str entry)))
      (s/check! (not= [{:component :kernel/bus}] (get-in o [:regeneration/scopes "order" :out :scopes :scopes])) "the golden was not rewritten")
      nil)))

(defstep #"every other golden is byte for byte what it was"
  (fn [ctx]
    (let [before (:oracle-before ctx)
          after (new-oracle ctx)
          pr #(binding [*print-namespace-maps* false] (pr-str %))]
      (doseq [c judging [k g] (get before c) :when (not= [c k] [:regeneration/scopes "order"])]
        (s/check! (= (pr g) (pr (get-in after [c k]))) (str "the golden " c " " k " changed")))
      (doseq [k [:project :cycles :results :cuts]]
        (s/check! (= (pr (get before k)) (pr (get after k))) (str "the fixtures " k " changed")))
      nil)))
