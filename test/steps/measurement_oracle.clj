(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature.

  An oracle fixture is an oracle.edn written here, by hand, pushed with
  `oras push --oci-layout` into a local artifact layout in a new temporary
  directory (never a registry), and a project whose configuration's :oracle
  names it by its digest, under a repository no registry answers for. The
  scenario's commands get ORACLE_LAYOUT, naming that layout, through the
  context's :env, as a step sets an environment variable for its commands
  (steps.kernel-command).

  The fixture's instrument inputs: a three-scenario spec (one component,
  demo/Alpha; spec-0003 planned) cut as spec-1 and spec-2, cycle 01 grading
  spec-0002 fail and cycle 02 grading both items pass. Its goldens are what
  the spec says the checklist, the scorer and the scoreboard give on it.

  The shared When, \"it exits N\" and the cycle project's helpers are the
  kernel's (steps.kernel-command) and the measurement context's
  (steps.measurement-cycle).

  Context keys: :dir the project, :run the last run, :env, :layout,
  :oracle (the oracle.edn data pushed), :oracle-text, :digest,
  :repository, :broken-golden."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(def repository
  "A repository no registry answers for: the fixture's artifact is only ever
  read from its layout."
  "registry.invalid/rdm-spec-oracle")

(defn needs-oracle! [] (s/needs-built! 'rdm-spec.measurement.oracle))

;; ---------------------------------------------------------------------------
;; The fixture project inside oracle.edn

(def ^:private model
  (str "{:system {:id :demo-system :name \"Demo\" :description \"A fixture system\"}\n"
       " :people [{:id :user :name \"User\" :actor #{:human} :description \"Uses the fixture\"}]\n"
       " :external []\n"
       " :containers\n"
       " [{:id :app :name \"app\" :kind :runs :technology \"A fixture\" :description \"The fixture's one container\"\n"
       "   :contexts\n"
       "   [{:id :demo :name \"demo\" :description \"The fixture's one bounded context\"\n"
       "     :components\n"
       "     [{:id :demo/alpha :name \"Alpha\" :spec \"demo/alpha\" :description \"The fixture component\"}]}]}]\n"
       " :relationships [{:from :user :to :app :description \"Uses\"}]\n"
       " :views [{:key \"demo-components\" :level :component :context :demo\n"
       "          :description \"The fixture's components\"}]}\n"))

(def ^:private feature
  (str "@UN-001\nFeature: Alpha\n  The oracle's fixture component.\n\n"
       "  Rule: The fixture's rule\n\n"
       "    @spec-0001 @e2e\n    Scenario: First\n      Given a fixture\n      Then it holds\n\n"
       "    @spec-0002 @contract\n    Scenario: Second\n      Given a fixture\n      Then it holds\n\n"
       "    @spec-0003 @e2e @wip\n    Scenario: Third\n      Given a fixture\n      Then it holds\n"))

(def project-files
  {"dhf/harness/config.edn" (pr-str {:id-prefix "spec" :spec-dir "dhf/spec" :ledger "dhf/spec/ids.edn"
                                     :model "dhf/spec/model.edn" :events "dhf/spec/events.edn"
                                     :needs "dhf/spec/needs.edn" :cycles-dir "dhf/cycles"})
   "dhf/spec/model.edn" model
   "dhf/spec/events.edn" "{}\n"
   "dhf/spec/needs.edn" "{\"UN-001\" {:text \"A fixture need.\" :sources [\"demo:README.md@abc1234\"]}}\n"
   "dhf/spec/ids.edn" "{:issued 3}\n"
   "dhf/spec/demo/alpha.feature" feature})

(def ^:private roles
  {:builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn- fixture-cycle [n cut verdicts]
  {:cycle (merge {:cut cut :scope :project} roles)
   :ratified {:cut cut :room-tree tree :outcome :passed :checkout (str "dhf/cycles/" n "/build")}
   :verdicts (vec (for [[id v] verdicts] {:id id :verdict v :evidence (str "graded " (name v))}))})

(defn- golden-checklist [n cut]
  {:cycle n :cut cut
   :items [{:id "spec-0001" :title "First" :context "demo" :component "Alpha" :level "e2e"}
           {:id "spec-0002" :title "Second" :context "demo" :component "Alpha" :level "contract"}]
   :planned [{:id "spec-0003" :title "Third"}]})

(defn- golden-score [n cut verdicts]
  (let [items (vec (for [[id v] verdicts] {:id id :verdict v :evidence (str "graded " (name v))}))]
    (merge {:cycle n :cut cut :scope :project :room-tree tree}
           roles
           {:items items
            :passed (count (filter #(= :pass (:verdict %)) items))
            :scored (count items)})))

(def ^:private verdicts-01 [["spec-0001" :pass] ["spec-0002" :fail]])
(def ^:private verdicts-02 [["spec-0001" :pass] ["spec-0002" :pass]])

(def good-scoreboard
  {:cycles ["01" "02"] :compared 2 :better 1 :worse 0 :better-items ["spec-0002"] :worse-items []
   :scored {"01" 2 "02" 2} :only-in {"01" [] "02" []} :statements []})

(def good-oracle
  "Two fixture cycles, their verdicts, and the goldens the spec gives."
  {:project project-files
   :cycles {"01" (fixture-cycle "01" "spec-1" verdicts-01)
            "02" (fixture-cycle "02" "spec-2" verdicts-02)}
   :checklist {"01" (golden-checklist "01" "spec-1") "02" (golden-checklist "02" "spec-2")}
   :scorer {"01" (golden-score "01" "spec-1" verdicts-01) "02" (golden-score "02" "spec-2" verdicts-02)}
   :scoreboard {"01-02" good-scoreboard}
   :recorded [{:date "2026-10-25" :reason "the first goldens, written from the spec" :changed [:checklist :scorer :scoreboard]}]})

(def stale-scoreboard
  "A scoreboard golden the instrument does not reproduce: it misses what got better."
  (assoc good-scoreboard :better 0 :better-items []))

;; ---------------------------------------------------------------------------
;; Layouts

(defn oracle-text [m] (binding [*print-namespace-maps* false] (str (pr-str m) "\n")))

(defn- oras [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" args)]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (str (:out r))))

(defn push!
  "Pushes `text` as oracle.edn into the layout `layout`: its digest."
  [layout text]
  (let [src (s/tmp-dir)]
    (spit (str (fs/file src "oracle.edn")) text)
    (let [out (oras src "push" "--oci-layout" (str layout) "--artifact-type" artifact-type "oracle.edn:application/edn")
          digest (second (re-find #"Digest: (sha256:[0-9a-f]{64})" out))]
      (fs/delete-tree src)
      (s/check! digest (str "oras printed no digest: " out))
      digest)))

(defn pull
  "The text of oracle.edn in the artifact `digest` of the layout, or nil."
  [layout digest]
  (let [out (s/tmp-dir)
        r (p/shell {:out :string :err :string :continue true :in ""}
                   "oras" "pull" "--oci-layout" (str layout "@" digest) "-o" (str out))
        f (fs/file out "oracle.edn")
        text (when (and (zero? (:exit r)) (fs/exists? f)) (slurp (str f)))
        files (when (fs/exists? out) (vec (map #(str (fs/file-name %)) (fs/list-dir out))))]
    (fs/delete-tree out)
    (when text {:text text :files files})))

(defn manifests [layout]
  (count (re-seq #"\"digest\"" (slurp (str (fs/file layout "index.json"))))))

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- set-oracle! [dir oracle]
  (let [c (config-of dir)]
    (spit (str (fs/file dir "dhf/harness/config.edn"))
          (pr-str (if oracle (assoc c :oracle oracle) (dissoc c :oracle))))))

(defn with-oracle
  "ctx whose project (a new one when it has none) configures the oracle
  `m`, pushed into a new layout that ORACLE_LAYOUT names; `digest` (when
  given) replaces the pushed one in the configuration."
  ([ctx m] (with-oracle ctx m nil))
  ([ctx m digest]
   (needs-oracle!)
   (let [dir (or (:dir ctx) (s/project! {}))
         layout (str (fs/file (s/tmp-dir) "layout"))
         text (oracle-text m)
         pushed (push! layout text)
         d (or digest pushed)]
     (set-oracle! dir {:repository repository :digest d})
     (-> ctx
         (assoc :dir dir :layout layout :oracle m :oracle-text text :digest d :pushed pushed :repository repository)
         (assoc-in [:env "ORACLE_LAYOUT"] layout)))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (with-oracle ctx good-oracle)))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx] (with-oracle ctx (assoc good-oracle :cycles {} :checklist {} :scorer {} :scoreboard {}))))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx] (with-oracle ctx good-oracle (str "sha256:" (apply str (repeat 64 "0"))))))

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (let [m (assoc-in good-oracle [:scorer "01" :items 1 :verdict] :pass)]
      (s/check! (= :fail (get-in good-oracle [:scorer "01" :items 1 :verdict])) "the scorer files pass")
      (assoc (with-oracle ctx m) :broken-golden {:component "scorer" :key "01" :item "spec-0002"}))))

(defstep #"the configured oracle holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (assoc (with-oracle ctx (assoc-in good-oracle [:scoreboard "01-02"] stale-scoreboard))
           :broken-golden {:component :scoreboard :key "01-02"})))

(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})
          room (s/tmp-dir)
          ctx (with-oracle (assoc ctx :dir dir) (assoc-in good-oracle [:scoreboard "01-02"] stale-scoreboard))]
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (assoc ctx :room room :cycle "03"))))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx]
    (needs-oracle!)
    (let [dir (s/project! {})]
      (set-oracle! dir nil)
      (assoc ctx :dir dir))))

;; ---------------------------------------------------------------------------
;; Thens

(defn- result [ctx] (:oracle (kc/stdout-edn (kc/the-run ctx))))

(defn- all-goldens [m]
  (set (for [k [:checklist :scorer :scoreboard] g (keys (get m k))] [k g])))

(defn- compared! [ctx]
  (let [r (kc/exits! ctx 0)
        got (:compared (result ctx))]
    (s/check! (sequential? got) (str "the result names no :compared goldens\n" (s/show r)))
    (s/check! (= (all-goldens (:oracle ctx)) (set got))
              (str "it compared " (pr-str got) ", not every golden " (pr-str (sort (all-goldens (:oracle ctx))))))
    nil))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx doc]
    (let [r (kc/exits! ctx 0)
          res (result ctx)
          pulled (pull (:layout ctx) (:digest ctx))
          shape-keys (set (map keyword (map second (re-seq #"(?m)^[{\s]*:([a-z-]+)\s" doc))))]
      (s/check! (= (:digest ctx) (:digest res)) (str "it read " (pr-str (:digest res)) ", not " (:digest ctx) "\n" (s/show r)))
      (s/check! (= (:repository ctx) (:repository res)) (str "it names the repository " (pr-str (:repository res))))
      (s/check! (= ["oracle.edn"] (:files pulled)) (str "the artifact holds " (pr-str (:files pulled))))
      (s/check! (= #{:project :cycles :checklist :scorer :scoreboard :recorded} shape-keys)
                (str "the spec's shape names " (pr-str shape-keys)))
      (s/check! (= shape-keys (set (keys (edn/read-string (:text pulled))))) "oracle.edn is not of the spec's shape")
      nil)))

(defstep #"it exits 0 and names each golden it compared(?:, as its component and key)?"
  (fn [ctx] (compared! ctx)))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "holds no fixture cycle") (str/includes? % "judged nothing")) (s/err-lines r))
                (str "no line says the oracle holds no fixture cycle, so it judged nothing\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx]
    (kc/refused-naming! ctx (:repository ctx) (:digest ctx))
    nil))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          {:keys [component key item]} (:broken-golden ctx)]
      (s/check! (some #(and (str/includes? % (str ":" component)) (str/includes? % (pr-str key))
                            (str/includes? % item) (str/includes? % ":pass") (str/includes? % ":fail"))
                      (s/err-lines r))
                (str "no line names :" component " " (pr-str key) ", " item ", :pass and :fail\n" (s/show r)))
      nil)))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and the component changed"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          digest (get-in (config-of (:dir ctx)) [:oracle :digest])
          _ (s/check! (not= digest (:digest ctx)) (str "the configuration still names the old digest\n" (s/show r)))
          pulled (pull (:layout ctx) digest)
          _ (s/check! pulled (str "the layout holds no artifact " digest))
          m (edn/read-string (:text pulled))
          reason (second (re-find #"--reason (.+)$" (str/join " " (:args r))))
          {:keys [component key]} (:broken-golden ctx)]
      (s/check! (= good-scoreboard (get-in m [component key])) (str "the golden was not rewritten: " (pr-str (get-in m [component key]))))
      (s/check! (= (dissoc (:oracle ctx) :scoreboard :recorded) (dissoc m :scoreboard :recorded)) "another golden changed")
      (s/check! (= (conj (:recorded (:oracle ctx))
                         {:date (str (java.time.LocalDate/now)) :reason reason :changed [component]})
                   (:recorded m))
                (str ":recorded is " (pr-str (:recorded m))))
      (assoc ctx :new-digest digest))))

(defstep #"the configuration's :oracle names the new digest, and the old artifact is unchanged"
  (fn [ctx]
    (let [c (config-of (:dir ctx))
          old (pull (:layout ctx) (:digest ctx))]
      (s/check! (= {:repository repository :digest (:new-digest ctx)} (:oracle c)) (str "the configuration names " (pr-str (:oracle c))))
      (s/check! (= (:digest (:recorded (result ctx))) (:new-digest ctx)) "the result names another digest")
      (s/check! (= (:oracle-text ctx) (:text old)) "the old artifact changed")
      nil)))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "a reason is required")]
      (s/check! (= 1 (manifests (:layout ctx))) (str "the layout holds another artifact\n" (s/show r)))
      (s/check! (= {:repository repository :digest (:digest ctx)} (:oracle (config-of (:dir ctx))))
                "the configuration's :oracle changed")
      nil)))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx what]
    (let [r (kc/exits! ctx 1)
          oracle (kc/run! ctx (:dir ctx) ["oracle"])
          want (remove #(str/starts-with? % "event: ") (s/err-lines oracle))
          got (s/err-lines r)]
      (s/check! (= 1 (:exit oracle)) (str "the oracle itself does not fail\n" (s/show oracle)))
      (s/check! (seq want) "the oracle names no finding")
      (s/check! (= want (filter (set want) got)) (str "the oracle's findings are not printed unchanged\n" (s/show r)))
      (if (= what "builds no room")
        (s/check! (empty? (fs/list-dir (:room ctx))) "a room was built")
        (s/check! (not (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn")))) "ratify wrote ratified.edn"))
      nil)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (re-find #"no oracle" (str (:err r) (:out r))) (str "it does not say there is no oracle\n" (s/show r)))
      nil)))
