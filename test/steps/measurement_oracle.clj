(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature: `rdm-spec
  oracle` in a project whose configuration's :oracle names a fixture
  oracle the steps build (never the room's): an oracle.edn pushed into a
  local artifact layout the scenario makes, which ORACLE_LAYOUT names.

  The fixture oracle holds :project (a small cycle project: the room's
  model, needs, ledger and catalogue, the lint's feature file with the
  default scenarios and an outline, its :verify the tests check, its
  :allure rdm-spec's default mapping requiring a browser trace at @e2e),
  :cycles (\"01\" and \"02\", their cuts spec-1 and spec-2), :results and
  :cuts as a step asks, and a case per golden a step names. A case is first
  written with no golden and its golden recorded by `rdm-spec oracle record`
  (cases are added freely), so every golden is the instrument's own answer;
  a step that wants a golden the instrument does not reproduce changes it
  afterwards and pushes the changed oracle.edn itself.

  Context keys set here: :dir, :env (ORACLE_LAYOUT), :layout, :data (the
  oracle.edn the configuration names, read), :digest-before, :index-before
  (the layout's index before the run), :config-before, :cases ([component
  case] a Then expects compared), :tampered {:component :case :path :golden
  :actual}, :room (fence and verify's ROOM, a path that does not exist)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The artifact layout

(def ^:private repository "ghcr.io/example/sauna-oracle")
(def ^:private artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(defn- oras! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" (map str args))]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (:out r)))

(defn- push!
  "Pushes `data` as oracle.edn into the layout `layout`: its digest."
  [layout data]
  (let [d (s/tmp-dir)]
    (spit (str (fs/file d "oracle.edn")) (binding [*print-namespace-maps* false] (str (pr-str data) "\n")))
    (let [out (oras! d "push" "--oci-layout" layout "--artifact-type" artifact-type "oracle.edn:application/edn")]
      (or (second (re-find #"Digest:\s*(sha256:[0-9a-f]{64})" out)) (s/fail! (str "oras printed no digest: " out))))))

(defn- pull
  "The files of the artifact `digest` in `layout`: {name text}."
  [layout digest]
  (let [d (s/tmp-dir)]
    (oras! d "pull" "--oci-layout" (str layout "@" digest) "-o" (str d))
    (into {} (for [f (fs/list-dir d) :when (fs/regular-file? f)] [(str (fs/file-name f)) (slurp (str f))]))))

(defn- digest-of [ctx] (get-in (kc/config-of (:dir ctx)) [:oracle :digest]))

(defn- data-of [ctx] (edn/read-string (get (pull (:layout ctx) (digest-of ctx)) "oracle.edn")))

(defn- set-digest! [ctx digest]
  (let [dir (:dir ctx)]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (assoc (kc/config-of dir) :oracle {:repository repository :digest digest})) "\n"))
    ctx))

(defn- publish!
  "Pushes `data` and names it in the configuration."
  [ctx data]
  (set-digest! ctx (push! (:layout ctx) data)))

;; ---------------------------------------------------------------------------
;; The fixtures

(def ^:private outline-scenario
  (str "\n  @spec-0015 @contract\n  Scenario Outline: A view of a level holds its elements\n"
       "    Given a model with a view of level <level>\n    When \"rdm-spec views\" is run\n    Then the view holds <what>\n\n"
       "    Examples:\n      | level     | what        |\n      | context   | the system  |\n"
       "      | container | the program |\n      | component | its parts   |\n"))

(defn- room-file [rel] (slurp (str (fs/file s/room-root rel))))

(def ^:private evidence-mapping (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]}))

(defn- project-files []
  (let [cfg (-> (s/room-config) (dissoc :oracle)
                (assoc :verify [{:kind :tests :command ["bb" "test.clj"]}] :allure evidence-mapping))]
    {"dhf/harness/config.edn" (str (pr-str cfg) "\n")
     "dhf/spec/model.edn" (room-file "dhf/spec/model.edn")
     "dhf/spec/needs.edn" (room-file "dhf/spec/needs.edn")
     "dhf/spec/ids.edn" (room-file "dhf/spec/ids.edn")
     "dhf/spec/events.edn" (room-file "dhf/spec/events.edn")
     kc/lint-rel (str (kc/lint-text kc/default-scenarios) outline-scenario)}))

(defn- cycle-of [n]
  {:cycle (assoc kc/default-cycle :cut (str "spec-" (parse-long n)))
   :ratified {:cut (str "spec-" (parse-long n)) :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
              :outcome :passed :checkout (str "dhf/cycles/" n "/build")}
   :verdicts [{:id "spec-0012" :verdict :pass :evidence "it fails the lint as it says"}
              {:id "spec-0014" :verdict (if (= "01" n) :fail :pass) :evidence "read the view"}
              {:id "spec-0015" :verdict :pass :evidence "every row holds"}]})

(defn- pickles-of-project
  "The pickles of the fixture project's spec."
  []
  (let [dir (s/tmp-dir)]
    (doseq [[rel text] (project-files)] (s/write! dir rel text))
    (:pickles (runner/plan dir {:spec-dir "dhf/spec" :id-prefix "spec" :model "dhf/spec/model.edn"}))))

(defn- faithful
  "A result per pickle of the project, as a faithful test writes it."
  []
  (vec (for [[i pk] (map-indexed vector (pickles-of-project))]
         (runner/allure-result pk {:status "passed" :start 1 :stop 2 :uuid (format "r%02d" i)
                                   :steps (mapv (fn [st] {:name (runner/step-name st) :status "passed"}) (:steps pk))
                                   :attachments (if (= "e2e" (:level pk)) [{:name "browser-trace" :source "t.zip"}] [])}
                                 evidence-mapping))))

(defn- relabel [r n v] (update r :labels (fn [ls] (mapv #(if (= n (:name %)) (assoc % :value v) %) ls))))

(defn- result-set
  "{file text} of the results `rs`, the first result naming spec-0012 changed by `f`."
  ([rs] (result-set rs identity))
  ([rs f]
   (let [i (first (keep-indexed (fn [i r] (when (some #(= {:name "spec" :value "spec-0012"} %) (:labels r)) i)) rs))]
     (into (sorted-map) (for [[j r] (map-indexed vector rs)]
                          [(str (:uuid r) "-result.json") (json/generate-string (if (= i j) (f r) r))])))))

(def ^:private result-sets
  {"steps-match" #(result-set (faithful))
   "steps-differ" #(result-set (faithful) (fn [r] (assoc-in r [:steps 1 :name] "When \"rdm-spec lint\" is run")))
   "wrong-story" #(result-set (faithful) (fn [r] (update r :labels conj {:name "story" :value "Something else"})))
   "no-need-link" #(result-set (faithful) (fn [r] (assoc r :links [])))
   "no-trace" #(result-set (faithful) (fn [r] (assoc r :attachments [])))
   "outline-rows" #(result-set (vec (remove (fn [r] (= [{:name "level" :value "component"} {:name "what" :value "its parts"}]
                                                       (:parameters r)))
                                            (faithful))))
   "unknown-result" #(result-set (conj (faithful) (assoc (first (faithful)) :uuid "r99"
                                                         :labels [{:name "spec" :value "spec-9999"}])))})

(def ^:private model-rel "dhf/spec/model.edn")

(defn- room-model [] (edn/read-string (room-file model-rel)))

(defn- room-events [] (edn/read-string (room-file "dhf/spec/events.edn")))

(defn- pr-edn [x] (str (pr-str x) "\n"))

(def ^:private cut-sets
  {"flat-read-by"
   #(let [ev (room-events)]
      {"spec-3" {"dhf/spec/events.edn" (pr-edn (assoc-in ev [:SpecGated :read-by] [:passed?]))}
       "spec-4" {"dhf/spec/events.edn" (pr-edn (-> ev (assoc-in [:SpecGated :read-by] [:passed?])
                                                  (assoc-in [:SpecGated :shape :findings] "[{:file :line :rule :message :severity}]")))}})
   "first-read-by"
   #(let [ev (room-events)]
      {"spec-3" {"dhf/spec/events.edn" (pr-edn ev)}
       "spec-4" {"dhf/spec/events.edn" (pr-edn (assoc-in ev [:VerificationDerived :read-by] [:statuses]))}})
   "order"
   #(let [lint (str (kc/lint-text kc/default-scenarios) outline-scenario)]
      {"spec-3" {"dhf/spec/kernel/command.feature" "Feature: Command\n  The command.\n"
                 "dhf/spec/spec/parser.feature" "Feature: Parser\n  The parser.\n"
                 kc/lint-rel lint}
       "spec-4" {"dhf/spec/kernel/command.feature" "Feature: Command\n  The command, changed.\n"
                 "dhf/spec/spec/parser.feature" "Feature: Parser\n  The parser, changed.\n"
                 kc/lint-rel (str lint "\n# changed\n")}})
   "removed-component"
   #(let [m (room-model)
          with-archive (update-in m [:containers 0 :contexts] conj
                                  {:id :archive :name "archive" :description "Keeps old records"
                                   :components [{:id :archive/ledger :name "Ledger" :spec "archive/ledger" :description "The old ledger"}]})]
      {"spec-3" {model-rel (pr-edn with-archive) "src/rdm_spec/archive/ledger.clj" "(ns rdm-spec.archive.ledger)\n"
                 "src/rdm_spec/spec/parser.clj" "(ns rdm-spec.spec.parser)\n" "test.clj" "(System/exit 0)\n"}
       "spec-4" {model-rel (pr-edn m) "src/rdm_spec/archive/ledger.clj" "(ns rdm-spec.archive.ledger)\n"
                 "src/rdm_spec/spec/parser.clj" "(ns rdm-spec.spec.parser)\n" "test.clj" "(System/exit 0)\n"}})
   "refusals"
   #(let [needs (edn/read-string (room-file "dhf/spec/needs.edn"))]
      {"spec-5" {kc/lint-rel (str "# A spec breaking each rule of the spec check once.\n\n@UN-005\nFeature: Lint\n  Written in Babashka.\n"
                                  "\n  Scenario: No id\n    Given a model\n"
                                  "\n  @spec-0012 @spec-0016 @e2e\n  Scenario: Two ids\n    Given a model\n"
                                  "\n  @spec-0017 @e2e\n  Scenario: First of a duplicate\n    Given a model\n"
                                  "\n  @spec-0017 @contract\n  Scenario: Second of a duplicate\n    Given a model\n"
                                  "\n  @spec-9999 @e2e\n  Scenario: Never issued\n    Given a model\n"
                                  "\n  @spec-0018\n  Scenario: No level\n    Given a model\n"
                                  "\n  @spec-0019 @e2e @contract\n  Scenario: Two levels\n    Given a model\n"
                                  "\n  @spec-0020 @e2e @shiny\n  Scenario: An unknown tag\n    Given a model\n"
                                  "\n  @spec-0021 @e2e @UN-999\n  Scenario: An undeclared need\n    Given a model\n")
                 "dhf/spec/needs.edn" (pr-edn (assoc needs "UN-099" {:text "A need resting on nothing." :sources []}))}})})

(def ^:private component-cases
  "What each judging component's case needs: :results, :cuts or the cycles."
  {:measurement/checklist :cycles :measurement/scorer :cycles :measurement/scoreboard :cycles :measurement/oracle :self
   :verification/verify :results :verification/status :results :regeneration/scopes :cuts :regeneration/fence :cuts
   :spec/check :cuts})

(defn- oracle-data
  "An oracle.edn holding the fixtures `cases` ([component case]) need, each
  case with no golden; `cycles` the fixture cycles (default 01 and 02)."
  ([cases] (oracle-data cases ["01" "02"]))
  ([cases cycles]
   (let [need (fn [kind] (distinct (for [[c k] cases :when (= kind (component-cases c))] k)))]
     (merge {:project (project-files)
             :cycles (into (sorted-map) (for [n cycles] [n (cycle-of n)]))
             :results (into (sorted-map) (for [k (need :results)] [k ((get result-sets k))]))
             :cuts (into (sorted-map) (for [k (need :cuts)] [k ((get cut-sets k))]))}
            (reduce (fn [m [c k]] (assoc-in m [c k] nil)) {} cases)
            {:recorded []}))))

(defn- oracle-project!
  "ctx with :dir, a project (a git repository, everything committed) whose
  configuration names the fixture oracle `data` in a new layout."
  [ctx data]
  (let [dir (s/project!)
        layout (str (fs/file (s/tmp-dir) "layout"))
        ctx (assoc ctx :dir dir :layout layout :env (assoc (:env ctx) "ORACLE_LAYOUT" layout))]
    (publish! ctx data)
    (kc/cut! dir "base")
    ctx))

(defn- record!
  "Records the goldens of the cases with none; fails the step unless it can."
  [ctx]
  (let [r (kc/run! ctx (:dir ctx) ["oracle" "record" "--reason" "the fixture's goldens"])]
    (s/check! (zero? (:exit r)) (str "the goldens could not be recorded\n" (s/show r)))
    (assoc ctx :data (data-of ctx))))

(defn- recorded-project! [ctx cases]
  (record! (oracle-project! ctx (oracle-data cases))))

(defn- before [ctx]
  (assoc ctx :digest-before (digest-of ctx)
         :config-before (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))
         :index-before (slurp (str (fs/file (:layout ctx) "index.json")))
         :data-before (data-of ctx)))

(defn- tamper!
  "Changes the golden of `component` `case` at `path` to `v`, pushes the
  oracle and names it in the configuration."
  [ctx component case path v]
  (let [data (data-of ctx)
        actual (get-in data (concat [component case] path))]
    (s/check! (some? actual) (str "the golden " component " " case " holds nothing at " (pr-str path)))
    (s/check! (not= v actual) "the change changes nothing")
    (-> (publish! ctx (assoc-in data (concat [component case] path) v))
        (assoc :tampered {:component component :case case :path path :golden v :actual actual})
        before)))

(defn- oracle-out [r] (:oracle (kc/stdout-edn r)))

(defn- compared [r] (mapv (juxt :component :case) (:compared (oracle-out r))))

;; ---------------------------------------------------------------------------
;; One file in one artifact

(def ^:private every-case
  [[:measurement/checklist "01"] [:measurement/scorer "01"] [:measurement/scoreboard "01-02"] [:measurement/oracle "self"]
   [:verification/verify "steps-match"] [:verification/status "outline-rows"] [:regeneration/scopes "flat-read-by"]
   [:regeneration/fence "removed-component"] [:spec/check "refusals"]])

(defstep #"the configured oracle holds fixture cycles, fixture result sets, fixture cuts, and goldens for every judging component"
  (fn [ctx] (assoc (recorded-project! ctx every-case) :cases (vec (sort-by (fn [[c k]] [(str c) k]) every-case)))))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx doc]
    (let [r (kc/exits! ctx 0)
          files (pull (:layout ctx) (digest-of ctx))
          data (edn/read-string (get files "oracle.edn"))
          want-keys (set (keys (edn/read-string (str/replace doc "..." ""))))]
      (s/check! (= ["oracle.edn"] (keys files)) (str "the artifact holds " (pr-str (keys files))))
      (s/check! (= want-keys (set (keys data))) (str "oracle.edn holds the keys " (pr-str (sort-by str (keys data)))))
      (s/check! (= (digest-of ctx) (:digest (oracle-out r))) "the run read another digest")
      (doseq [k [:project :cycles :results :cuts]] (s/check! (map? (get data k)) (str k " is not a map")))
      (s/check! (vector? (:recorded data)) ":recorded is not a vector")
      nil)))

(defstep #"it exits 0 and names each golden it compared, as its component and case"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (= (:cases ctx) (compared r)) (str "it compared " (pr-str (compared r)) ", not " (pr-str (:cases ctx))))
      nil)))

(defstep #"the configured oracle holds goldens for the components :measurement/scorer and :regeneration/scopes, one case each, both reproduced"
  (fn [ctx] (recorded-project! ctx [[:regeneration/scopes "flat-read-by"] [:measurement/scorer "01"]])))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:oracle .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          want {:oracle {:repository repository :digest (digest-of ctx)
                         :compared [{:component :measurement/scorer :case "01"} {:component :regeneration/scopes :case "flat-read-by"}]
                         :differences 0}}]
      (s/check! (= want (kc/stdout-edn r)) (str "standard output is " (pr-str (kc/stdout-edn r)) ", not " (pr-str want)))
      nil)))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx] (oracle-project! ctx (assoc (oracle-data [] []) :cycles {}))))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "no fixture cycle") (str/includes? % "judged nothing")) (kc/finding-lines r))
                (str "it does not say so\n" (s/show r)))
      nil)))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx]
    (let [ctx (oracle-project! ctx (oracle-data [] ["01"]))]
      (set-digest! ctx (str "sha256:" (apply str (repeat 64 "7")))))))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx] (kc/refused-naming! ctx repository (digest-of ctx)) nil))

;; ---------------------------------------------------------------------------
;; Judged against recorded goldens

(def ^:private cycle-cases
  [[:measurement/checklist "01"] [:measurement/checklist "02"] [:measurement/scoreboard "01-02"]
   [:measurement/scorer "01"] [:measurement/scorer "02"]])

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (before (assoc (recorded-project! ctx cycle-cases) :cases cycle-cases))))

(defstep #"it exits 0 and names each golden it compared"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (= (:cases ctx) (compared r)) (str "it compared " (pr-str (compared r))))
      nil)))

(def ^:private score-rel "dhf/cycles/01/score.edn")

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (let [ctx (recorded-project! ctx [[:measurement/scorer "01"]])
          item (get-in (data-of ctx) [:measurement/scorer "01" :wrote score-rel :items 1])]
      (s/check! (= ["spec-0014" :fail] [(:id item) (:verdict item)]) (str "the second item is " (pr-str item)))
      (tamper! ctx :measurement/scorer "01" [:wrote score-rel :items 1 :verdict] :pass))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(every? (fn [w] (str/includes? % w)) [":measurement/scorer" "\"01\"" "spec-0014" ":pass" ":fail"])
                      (kc/finding-lines r))
                (str "no finding names the golden, the item and both values\n" (s/show r)))
      nil)))

(defn- record-line [ctx line]
  (kc/run-line ctx line))

(defstep #"the configured oracle holds goldens, one of which, :measurement/scoreboard \"01-02\", the instrument no longer reproduces"
  (fn [ctx]
    (let [ctx (recorded-project! ctx cycle-cases)]
      (tamper! ctx :measurement/scoreboard "01-02" [:out :scoreboard :better] 99))))

(defn- new-data [ctx]
  (s/check! (not= (:digest-before ctx) (digest-of ctx)) (str "the configuration names the old digest\n" (s/show (kc/the-run ctx))))
  (data-of ctx))

(defn- recorded-entry! [ctx data reason change]
  (let [e (last (:recorded data))]
    (s/check! (= (str (java.time.LocalDate/now)) (:date e)) (str "the entry's date is " (pr-str (:date e))))
    (s/check! (= reason (:reason e)) (str "the entry's reason is " (pr-str (:reason e))))
    (s/check! (= [change] (:changed e)) (str "the entry's changes are " (pr-str (:changed e))))
    (s/check! (= (inc (count (:recorded (:data-before ctx)))) (count (:recorded data))) "the entry was not appended")))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and \{:component :measurement/scoreboard :case \"01-02\"\}"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [data (new-data ctx)
          {:keys [component case path actual]} (:tampered ctx)]
      (s/check! (= actual (get-in data (concat [component case] path))) "the golden was not rewritten")
      (recorded-entry! ctx data "the scoreboard now says the denominator moved" {:component :measurement/scoreboard :case "01-02"})
      nil)))

(defstep #"the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged"
  (fn [ctx]
    (let [old (pull (:layout ctx) (:digest-before ctx))
          st (:out (p/shell {:dir (str (:dir ctx)) :out :string :err :string} "git" "status" "--porcelain"))]
      (s/check! (= (:data-before ctx) (edn/read-string (get old "oracle.edn"))) "the old artifact changed")
      (s/check! (str/includes? st "dhf/harness/config.edn") (str "the configuration is not left changed: " st))
      (s/check! (= "the cut base" (str/trim (:out (p/shell {:dir (str (:dir ctx)) :out :string :err :string}
                                                   "git" "log" "-1" "--format=%s" "--" "dhf/harness/config.edn"))))
                "a commit was made")
      (s/check! (= repository (get-in (kc/config-of (:dir ctx)) [:oracle :repository])) "the repository changed")
      nil)))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx "a reason is required")
    (s/check! (= (:index-before ctx) (slurp (str (fs/file (:layout ctx) "index.json")))) "something was stored")
    (s/check! (= (:config-before ctx) (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))) "the configuration changed")
    nil))

;; ---------------------------------------------------------------------------
;; The gate

(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx]
    (let [dir (kc/cycle-project! kc/default-scenarios)
          layout (str (fs/file (s/tmp-dir) "layout"))
          ctx (assoc ctx :dir dir :layout layout :env (assoc (:env ctx) "ORACLE_LAYOUT" layout)
                     :room (str (fs/file (s/tmp-dir) "room")))]
      (kc/write-cycle! dir)
      (publish! ctx (oracle-data [[:measurement/scorer "01"]] ["01"]))
      (let [ctx (record! ctx)]
        (tamper! ctx :measurement/scorer "01" [:exit] 7)))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx what]
    (let [r (kc/exits! ctx 1)
          o (kc/run! ctx (:dir ctx) ["oracle"])]
      (s/check! (= 1 (:exit o)) (str "the oracle does not fail\n" (s/show o)))
      (s/check! (seq (kc/finding-lines o)) "the oracle names no finding")
      (s/check! (= (kc/finding-lines o) (kc/finding-lines r)) (str "the findings are not the oracle's\n" (s/show r) "\n" (s/show o)))
      (case what
        "builds no room" (s/check! (not (fs/exists? (:room ctx))) "a room was built")
        (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
      nil)))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx]
    (let [ctx (kc/project-set-up! ctx)]
      (s/check! (not (contains? (kc/config-of (:dir ctx)) :oracle)) "the configuration names an oracle")
      ctx)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (:out r) "there is no oracle to run") (str "it does not say so\n" (s/show r)))
      (s/check! (empty? (s/err-lines r)) (str "it said so on the error stream\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every judging component

(defstep #"the configured oracle holds, for (:[a-z]+/[a-z]+), the case (\S+): (.+)"
  (fn [ctx component case _]
    (let [c (keyword (subs component 1))
          cases (if (= :measurement/oracle c)
                  [[:measurement/checklist "01"] [:measurement/scorer "01"] [c case]]
                  [[c case]])]
      (assoc (recorded-project! ctx cases) :component c :case case))))

(defstep #"it runs (:[a-z]+/[a-z]+) on that fixture and compares its standard output, exit and error stream with the golden, naming \1 and (\S+)"
  (fn [ctx _ _]
    (let [{:keys [component case]} ctx
          r (kc/exits! ctx 0)]
      (s/check! (some #{[component case]} (compared r)) (str "it did not compare " component " " case "\n" (s/show r)))
      (let [g (get-in (data-of ctx) [component case])]
        (s/check! (= #{:exit :out :err} (disj (set (keys g)) :wrote)) (str "the golden is " (pr-str (keys g))))
        ;; the fixture is one the component can judge: it ran, it did not refuse
        (s/check! (not= 2 (:exit g)) (str "the component could not run on its fixture: " (pr-str g)))
        (doseq [[path v] [[[:err] (str (:err g) "a word more")] [[:exit] (+ 40 (:exit g))] [[:out] {:another :answer}]]]
          (let [c2 (tamper! ctx component case path v)
                r2 (kc/run! c2 (:dir c2) ["oracle"])]
            (s/check! (= 1 (:exit r2)) (str "a changed " (pr-str path) " is not found\n" (s/show r2)))
            (s/check! (some #(and (str/includes? % (str component)) (str/includes? % (pr-str case))) (kc/finding-lines r2))
                      (str "no finding names " component " and " case "\n" (s/show r2)))
            (publish! c2 (:data ctx)))))
      nil)))

(defstep #"the configured oracle holds the golden of :regeneration/scopes \"order\", which the instrument no longer reproduces, and a new case \"flat-read-by\" with no golden"
  (fn [ctx]
    (let [ctx (recorded-project! ctx [[:regeneration/scopes "order"]])
          ctx (tamper! ctx :regeneration/scopes "order" [:out] {:scopes {:from "spec-3" :to "spec-4" :scopes [] :shared []}})
          data (-> (data-of ctx)
                   (assoc-in [:regeneration/scopes "flat-read-by"] nil)
                   (assoc-in [:cuts "flat-read-by"] ((get cut-sets "flat-read-by"))))]
      (before (publish! ctx data)))))

(defstep #"it exits 1, records nothing, and names :regeneration/scopes \"order\" as an existing golden the record would change"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % ":regeneration/scopes") (str/includes? % "\"order\"")) (kc/finding-lines r))
                (str "no finding names the golden\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % "flat-read-by") (kc/finding-lines r)) "a new case is named as a change")
      (s/check! (= (:index-before ctx) (slurp (str (fs/file (:layout ctx) "index.json")))) "something was stored")
      (s/check! (= (:config-before ctx) (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))) "the configuration changed")
      nil)))

(defstep #"the configured oracle holds the golden of :regeneration/scopes \"order\", which the instrument no longer reproduces"
  (fn [ctx]
    (let [ctx (recorded-project! ctx [[:regeneration/scopes "order"] [:measurement/checklist "01"] [:verification/status "unknown-result"]])]
      (tamper! ctx :regeneration/scopes "order" [:out] {:scopes {:from "spec-3" :to "spec-4" :scopes [] :shared []}}))))

(defstep #"it stores a new artifact whose :recorded gains the date, the reason and \{:component :regeneration/scopes :case \"order\"\}"
  (fn [ctx]
    (kc/exits! ctx 0)
    (recorded-entry! ctx (new-data ctx) "the kernel is listed first" {:component :regeneration/scopes :case "order"})
    nil))

(defstep #"every other golden is byte for byte what it was"
  (fn [ctx]
    (let [old (:data-before ctx) new (data-of ctx)
          goldens (fn [d] (into {} (for [[c cases] d :when (and (keyword? c) (namespace c)) [k g] cases
                                         :when (not= [c k] [:regeneration/scopes "order"])]
                                     [[c k] (pr-str g)])))]
      (s/check! (= (goldens old) (goldens new)) "another golden changed")
      (s/check! (= (dissoc old :recorded :regeneration/scopes) (dissoc new :recorded :regeneration/scopes)) "a fixture changed")
      nil)))
