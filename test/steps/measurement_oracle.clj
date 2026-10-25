(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature.

  The configured oracle is a real artifact: oracle.edn pushed into an OCI
  layout the scenario makes (`oras push --oci-layout`), named by the
  scenario project's :oracle {:repository :digest}; the command reads it
  through ORACLE_LAYOUT, which the scenario sets (:env), so no registry is
  ever reached. Its goldens are recorded by running the instrument itself
  (`rdm-spec checklist`, `score` and `scoreboard`) on the fixture cycles in a
  scratch repository, never by the oracle under test.

  Context keys of this file: :dir the scenario's project (a git repository
  whose configuration is committed), :layout the artifact layout, :digest
  the configured digest, :oracle the oracle.edn pushed, :changed the golden
  the instrument no longer reproduces ([component key]), :item the id of
  the score item whose golden differs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def repository "registry.invalid/rdm-spec-oracle")
(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

;; ---------------------------------------------------------------------------
;; The fixture cycles

(def project-files
  "The fixture project's paths, read from a copy of the room."
  ["dhf/harness/config.edn" "dhf/spec/model.edn" "dhf/spec/needs.edn" "dhf/spec/ids.edn"
   "dhf/spec/events.edn"])

(defn- fixture-project
  "{path text}: the room's configuration (no oracle), model, needs, ledger
  and catalogue, and one feature file holding mc/default-scenarios."
  []
  (let [dir (kc/copy-of-room!)]
    (assoc (into (sorted-map) (for [p project-files] [p (slurp (str (fs/file dir p)))]))
           mc/lint-feature (mc/feature-text mc/default-scenarios {:feature "Lint"}))))

(def verdicts
  {"01" [{:id "spec-0012" :verdict :pass :evidence "the lint names the view"}
         {:id "spec-0014" :verdict :fail :evidence "no finding over the limit"}]
   "02" [{:id "spec-0012" :verdict :pass :evidence "the lint names the view"}
         {:id "spec-0014" :verdict :pass :evidence "the finding names the limit"}]})

(defn- fixture-cycles []
  (into (sorted-map)
        (for [[n cut] [["01" "spec-1"] ["02" "spec-2"]]]
          [n {:cycle (assoc mc/default-cycle :cut cut)
              :ratified {:cut cut :room-tree (apply str (repeat 40 "a")) :outcome :passed
                         :checkout (str "dhf/cycles/" n "/build")}
              :verdicts (get verdicts n)}])))

(defn- run-ok! [dir & args]
  (let [r (kc/run! {} dir args)]
    (s/check! (zero? (:exit r)) (str "rdm-spec " (str/join " " args) " failed while recording the fixture's goldens\n"
                                     (s/show r)))
    r))

(defn- recorded-goldens
  "The goldens of `project` and `cycles`, as the instrument gives them in a
  scratch repository: {:checklist {n ..} :scorer {n ..} :scoreboard {\"01-02\" ..}}."
  [project cycles]
  (let [dir (kc/tmp-dir)]
    (doseq [[path text] project] (s/write! dir path text))
    (mc/git! dir "init" "-q")
    (mc/git! dir "add" "-A")
    (mc/git! dir "-c" "core.hooksPath=/dev/null" "commit" "-q" "--no-verify" "-m" "the fixture project")
    (doseq [c (distinct (map (comp :cut :cycle) (vals cycles)))] (mc/git! dir "tag" c))
    (doseq [[n {:keys [cycle ratified verdicts]}] cycles]
      (s/write! dir (mc/cycle-path n "cycle.edn") (pr-str cycle))
      (s/write! dir (mc/cycle-path n "ratified.edn") (pr-str ratified))
      (s/write! dir (mc/cycle-path n "verdicts.edn") (pr-str verdicts)))
    (let [out (reduce (fn [out n]
                        (run-ok! dir "checklist" n)
                        (run-ok! dir "score" n "--verdicts" (mc/cycle-path n "verdicts.edn"))
                        (-> out
                            (assoc-in [:checklist n] (json/parse-string (slurp (str (fs/file dir (mc/cycle-path n "checklist.json")))) true))
                            (assoc-in [:scorer n] (edn/read-string (slurp (str (fs/file dir (mc/cycle-path n "score.edn"))))))))
                      {} (keys cycles))
          board (run-ok! dir "scoreboard" "01" "02")]
      (assoc-in out [:scoreboard "01-02"] (:scoreboard (kc/stdout-edn board))))))

(defn oracle-edn
  "An oracle.edn of the two fixture cycles with the goldens the instrument
  gives on them."
  []
  (let [project (fixture-project)
        cycles (fixture-cycles)]
    (merge {:project project :cycles cycles}
           (recorded-goldens project cycles)
           {:recorded [{:date "2026-10-25" :reason "the first recording" :changed [:checklist :scorer :scoreboard]}]})))

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" args)]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (:out r)))

(defn push!
  "Pushes oracle.edn holding `oracle` into the layout `layout`, untagged:
  its digest."
  [layout oracle]
  (let [d (kc/tmp-dir)]
    (spit (str (fs/file d "oracle.edn")) (pr-str oracle))
    (:digest (json/parse-string (oras! d "push" "--oci-layout" (str layout) "oracle.edn:application/edn"
                                       "--artifact-type" artifact-type "--format" "json")
                                true))))

(defn pull
  "The oracle.edn the artifact `digest` of the layout holds, as text."
  [layout digest]
  (let [d (kc/tmp-dir)]
    (oras! d "pull" "--oci-layout" (str layout "@" digest) "-o" d)
    (slurp (str (fs/file d "oracle.edn")))))

(defn- git! [dir & args] (apply mc/git! dir "-c" "core.hooksPath=/dev/null" args))

(defn project!
  "ctx with :dir, a git repository holding the room's configuration with
  `oracle-config` as its :oracle (none when nil), committed."
  [ctx oracle-config]
  (let [dir (kc/tmp-dir)
        cfg (cond-> (dissoc (s/room-config) :oracle) oracle-config (assoc :oracle oracle-config))]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str cfg) "\n"))
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "--no-verify" "-m" "the scenario's project")
    (assoc ctx :dir dir)))

(defn configured!
  "ctx whose project's :oracle names `oracle`, pushed into a new layout
  that ORACLE_LAYOUT names."
  [ctx oracle]
  (let [layout (str (fs/file (kc/tmp-dir) "layout"))
        digest (push! layout oracle)]
    (-> (project! ctx {:repository repository :digest digest})
        (assoc :layout layout :digest digest :oracle oracle)
        (assoc-in [:env "ORACLE_LAYOUT"] layout))))

(defn- config-oracle [ctx] (:oracle (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn"))))))

;; ---------------------------------------------------------------------------
;; The oracle is one EDN file in one artifact, pinned by digest

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (configured! ctx (oracle-edn))))

(def components [:checklist :scorer :scoreboard])

(defn- golden-keys [oracle] (set (for [c components k (keys (get oracle c))] {:component c :key k})))

(defn- compared [r] (set (get-in (kc/stdout-edn r) [:oracle :compared])))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx shape]
    (let [r (kc/exits! ctx 0)
          out (:oracle (kc/stdout-edn r))
          wanted (set (map (comp keyword second) (re-seq #"(?m)^\s*\{?\s*:([a-z-]+)\s" shape)))]
      ;; the layout holds the artifact untagged: only its digest names it
      (s/check! (= (:digest ctx) (:digest out)) (str "the oracle read " (pr-str (:digest out)) "\n" (s/show r)))
      (s/check! (= repository (:repository out)) (str "the oracle names the repository " (pr-str (:repository out))))
      (s/check! (= wanted (set (keys (edn/read-string (pull (:layout ctx) (:digest ctx))))))
                (str "the artifact's oracle.edn does not hold " wanted))
      (s/check! (= (set (map :component (golden-keys (:oracle ctx)))) (set (map :component (compared r))))
                (str "the oracle did not compare one golden of each component\n" (s/show r)))
      nil)))

(defstep #"it exits 0 and names each golden it compared(?:, as its component and key)?"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (= (golden-keys (:oracle ctx)) (compared r))
                (str "expected the goldens " (pr-str (golden-keys (:oracle ctx))) "\n" (s/show r)))
      nil)))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx]
    (configured! ctx {:project {} :cycles {} :checklist {} :scorer {} :scoreboard {}
                      :recorded [{:date "2026-10-25" :reason "empty" :changed []}]})))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "no fixture cycle") (str/includes? % "judged nothing"))
                      (kc/finding-lines r))
                (str "no finding says the oracle holds no fixture cycle\n" (s/show r)))
      nil)))

(def unheld-digest (str "sha256:" (apply str (repeat 64 "0"))))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx]
    (let [ctx (configured! ctx {:project {} :cycles {}})]
      (-> (project! ctx {:repository repository :digest unheld-digest})
          (assoc :digest unheld-digest)))))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx] (kc/refused-naming! ctx repository (:digest ctx)) nil))

;; ---------------------------------------------------------------------------
;; The instrument is judged against recorded goldens

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (let [o (oracle-edn)
          item (get-in o [:scorer "01" :items 1])]
      (s/check! (= :fail (:verdict item)) (str "the scorer files " (pr-str item)))
      (-> (configured! ctx (assoc-in o [:scorer "01" :items 1 :verdict] :pass))
          (assoc :item (:id item))))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(every? (fn [w] (str/includes? % w)) [":scorer" "\"01\"" (:item ctx) ":pass" ":fail"])
                      (kc/finding-lines r))
                (str "no finding names the golden :scorer \"01\", " (:item ctx) ", :pass and :fail\n" (s/show r)))
      nil)))

(defn- unreproduced
  "The fixture oracle with its scoreboard golden changed."
  []
  (update-in (oracle-edn) [:scoreboard "01-02" :better] #(+ 7 (or % 0))))

(defstep #"the configured oracle holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (-> (configured! ctx (unreproduced))
        (assoc :changed [:scoreboard "01-02"]))))

(defn- today [] (str (java.time.LocalDate/now)))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and the component changed"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          new-digest (:digest (config-oracle ctx))
          _ (s/check! (and new-digest (not= new-digest (:digest ctx)))
                      (str "the configuration names " (pr-str new-digest) "\n" (s/show r)))
          stored (edn/read-string (pull (:layout ctx) new-digest))
          [c k] (:changed ctx)
          reproduced (get-in (oracle-edn) [c k])]
      (s/check! (= reproduced (get-in stored [c k]))
                (str "the new golden " c " " k " is " (pr-str (get-in stored [c k])) ", not " (pr-str reproduced)))
      (s/check! (= (dissoc (:oracle ctx) c :recorded) (dissoc stored c :recorded))
                "the new artifact changed more than that golden")
      (s/check! (= (:recorded (:oracle ctx)) (pop (vec (:recorded stored))))
                "the new artifact did not keep the earlier :recorded entries")
      (s/check! (= {:date (today) :reason "the scoreboard now says the denominator moved" :changed [c]}
                   (select-keys (peek (vec (:recorded stored))) [:date :reason :changed]))
                (str "the new :recorded entry is " (pr-str (peek (vec (:recorded stored))))))
      nil)))

(defstep #"the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged"
  (fn [ctx]
    (let [o (config-oracle ctx)
          status (git! (:dir ctx) "status" "--porcelain")]
      (s/check! (= repository (:repository o)) "the repository changed")
      (s/check! (not= (:digest ctx) (:digest o)) "the configuration names the old digest")
      (s/check! (= "M dhf/harness/config.edn" (str/trim status)) (str "the working tree is " (pr-str status)))
      (s/check! (= "the scenario's project" (git! (:dir ctx) "log" "-1" "--format=%s")) "something was committed")
      (s/check! (= (:oracle ctx) (edn/read-string (pull (:layout ctx) (:digest ctx)))) "the old artifact changed")
      nil)))

(defn- layout-state [layout]
  (into (sorted-map) (for [f (fs/glob layout "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize layout f)) (slurp (str f))])))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "reason")]
      (s/check! (some #(str/includes? % "required") (kc/cannot-run-lines r)) (str "it does not say a reason is required\n" (s/show r)))
      (s/check! (= {:repository repository :digest (:digest ctx)} (config-oracle ctx)) "the configuration's :oracle changed")
      (s/check! (= "" (git! (:dir ctx) "status" "--porcelain")) "the working tree changed")
      (s/check! (= 1 (count (:manifests (json/parse-string (slurp (str (fs/file (:layout ctx) "index.json"))) true))))
                "the layout holds another artifact")
      nil)))

;; ---------------------------------------------------------------------------
;; Nothing is fenced or ratified while the oracle is red

(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx] (configured! ctx (unreproduced))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx does-nothing]
    (let [r (kc/exits! ctx 1)
          oracle (kc/run! ctx (:dir ctx) ["oracle"])]
      (s/check! (= 1 (:exit oracle)) (str "the oracle passes on its own\n" (s/show oracle)))
      (s/check! (seq (kc/finding-lines oracle)) "the oracle names no finding")
      (s/check! (= (kc/finding-lines oracle) (kc/finding-lines r))
                (str "the task did not print the oracle's findings unchanged\n" (s/show r) "\noracle:\n" (s/show oracle)))
      (case does-nothing
        "builds no room" (let [room (:room ctx)]
                           (s/check! (or (not (fs/exists? room)) (empty? (fs/list-dir room))) "a room was built"))
        (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
      nil)))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx] (project! ctx nil)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (:out r) "no oracle to run") (str "it does not say there is no oracle to run\n" (s/show r)))
      nil)))
