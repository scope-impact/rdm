(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature: `rdm-spec
  oracle` (and fence and verify, whose gate it is) in a project whose
  configuration's :oracle names an artifact in a local layout the scenario
  makes (ORACLE_LAYOUT, set in :env), pushed with the stack's tool.

  The oracle.edn: :project, a fixture project (the room's configuration
  without :oracle, the room's model, and the lint's feature file holding
  spec-0012, spec-0014 and spec-0013 tagged @wip); :cycles 01 (cut spec-1)
  and 02 (cut spec-2), each verified, graded 0012 pass and 0014 fail, then
  both pass; and goldens written by hand from the contracts the spec states
  (checklist.json, spec-0210; score.edn, spec-0174; the scoreboard,
  spec-0283), never by the instrument under test.

  Context keys set here: :dir, :env (ORACLE_LAYOUT), :layout, :digest, :oracle
  (the oracle.edn pushed), :config-before (the configuration's text)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The fixture oracle

(def ^:private titles
  {"spec-0012" "A view with no description fails the lint"
   "spec-0014" "A view over the limit fails the lint"
   "spec-0013" "A component that owns no feature file fails the lint"})

(defn- fixture-project []
  {"dhf/harness/config.edn" (str (pr-str (dissoc (s/room-config) :oracle)) "\n")
   "dhf/spec/model.edn" (slurp (str (fs/file s/room-root "dhf/spec/model.edn")))
   "dhf/spec/needs.edn" "{}\n"
   "dhf/spec/ids.edn" "{:issued 14}\n"
   mc/lint-feature (mc/feature-text mc/default-scenarios)})

(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")
(def ^:private roles {:builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                      :spec-owner {:name "Sami" :actor :human}})

(defn- verdicts [v12 v14]
  [{:id "spec-0012" :verdict v12 :evidence "the lint names the view"}
   {:id "spec-0014" :verdict v14 :evidence "the limit is checked"}])

(defn- cycle-of [nn cut v]
  {:cycle (merge {:cut cut :scope :project} roles)
   :ratified {:cut cut :room-tree tree :outcome :passed :checkout (str "dhf/cycles/" nn "/build")}
   :verdicts v})

(defn- checklist-golden [nn cut]
  {:cycle nn :cut cut
   :items [{:id "spec-0012" :title (titles "spec-0012") :context "architecture" :component "Lint" :level "e2e"}
           {:id "spec-0014" :title (titles "spec-0014") :context "architecture" :component "Lint" :level "contract"}]
   :planned [{:id "spec-0013" :title (titles "spec-0013")}]})

(defn- score-golden [nn cut v]
  (merge {:cycle nn :cut cut :scope :project :room-tree tree} roles
         {:items v :passed (count (filter #(= :pass (:verdict %)) v)) :scored 2}))

(defn oracle-edn
  "The fixture oracle.edn."
  []
  (let [v1 (verdicts :pass :fail) v2 (verdicts :pass :pass)]
    {:project (fixture-project)
     :cycles {"01" (cycle-of "01" "spec-1" v1) "02" (cycle-of "02" "spec-2" v2)}
     :checklist {"01" (checklist-golden "01" "spec-1") "02" (checklist-golden "02" "spec-2")}
     :scorer {"01" (score-golden "01" "spec-1" v1) "02" (score-golden "02" "spec-2" v2)}
     :scoreboard {"01-02" {:cycles ["01" "02"] :scored {"01" 2 "02" 2} :compared 2 :better 1 :worse 0
                           :better-items ["spec-0014"] :worse-items [] :only-in {"01" [] "02" []} :statements []}}
     :recorded [{:date "2026-10-25" :reason "the fixture's first goldens" :changed [:checklist :scorer :scoreboard]}]}))

(def ^:private golden-keys
  #{[:checklist "01"] [:checklist "02"] [:scorer "01"] [:scorer "02"] [:scoreboard "01-02"]})

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" args)]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (str (:out r) (:err r))))

(defn push!
  "Pushes `oracle` as oracle.edn into the layout `layout`: its digest."
  [layout oracle]
  (let [d (s/tmp-dir)]
    (spit (str (fs/file d "oracle.edn")) (str (pr-str oracle) "\n"))
    (let [out (oras! d "push" "--oci-layout" layout "--artifact-type" "application/vnd.scope-impact.rdm-spec.oracle.v1"
                     "oracle.edn:application/edn")]
      (or (second (re-find #"Digest: (sha256:[0-9a-f]{64})" out)) (s/fail! (str "oras printed no digest: " out))))))

(defn pull
  "The oracle.edn of the artifact `digest` in the layout `layout`."
  [layout digest]
  (let [d (s/tmp-dir)]
    (oras! d "pull" "--oci-layout" (str layout "@" digest) "-o" d)
    (edn/read-string (slurp (str (fs/file d "oracle.edn"))))))

(defn- project-with!
  "ctx with a project whose configuration's :oracle names `oracle`, pushed
  into a new layout."
  [ctx oracle]
  (let [layout (str (fs/file (s/tmp-dir) "layout"))
        digest (push! layout oracle)
        dir (s/project! {:oracle {:repository "registry.invalid/rdm-spec-oracle" :digest digest}})]
    (assoc ctx :dir dir :layout layout :digest digest :oracle oracle
           :env (assoc (:env ctx) "ORACLE_LAYOUT" layout)
           :config-before (slurp (str (fs/file dir "dhf/harness/config.edn"))))))

(defn- out [ctx] (:oracle (kc/stdout-edn (kc/the-run ctx))))

(defn- compared! [ctx]
  (let [r (kc/exits! ctx 0)
        got (set (map (juxt :component :key) (:compared (out ctx))))]
    (s/check! (= golden-keys got) (str "it compared " (pr-str (sort got)) "\n" (s/show r)))
    nil))

;; ---------------------------------------------------------------------------
;; The oracle is one EDN file in one artifact, pinned by digest

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (project-with! ctx (oracle-edn))))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx _]
    (kc/exits! ctx 0)
    (s/check! (= (:digest ctx) (:digest (out ctx))) (str "it read " (pr-str (:digest (out ctx)))))
    nil))

(defstep #"it exits 0 and names each golden it compared(?:, as its component and key)?"
  (fn [ctx] (compared! ctx)))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx] (project-with! ctx (assoc (oracle-edn) :cycles {} :checklist {} :scorer {} :scoreboard {}))))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % "holds no fixture cycle, so it judged nothing") (kc/finding-lines r)) (s/show r))
      nil)))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx]
    (let [ctx (project-with! ctx (oracle-edn))
          bogus (str "sha256:" (apply str (repeat 64 "0")))
          dir (:dir ctx)]
      (s/write! dir "dhf/harness/config.edn"
                (str/replace (slurp (str (fs/file dir "dhf/harness/config.edn"))) (:digest ctx) bogus))
      (assoc ctx :digest bogus))))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx] (kc/refused-naming! ctx "registry.invalid/rdm-spec-oracle" (:digest ctx)) nil))

;; ---------------------------------------------------------------------------
;; The instrument is judged against recorded goldens

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx] (project-with! ctx (assoc-in (oracle-edn) [:scorer "01" :items 1 :verdict] :pass))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some (fn [l] (every? #(str/includes? l %) [":scorer" "01" ":items 1" ":pass" ":fail"])) (kc/finding-lines r))
                (str "no finding names the golden, the item and both values\n" (s/show r)))
      nil)))

(def ^:private moved ["the denominator moved, said aloud"])

(defstep #"the configured oracle holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx] (project-with! ctx (assoc-in (oracle-edn) [:scoreboard "01-02" :statements] moved))))

(defn- digest-now [ctx]
  (get-in (kc/config-of (:dir ctx)) [:oracle :digest]))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and the component changed"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          d (digest-now ctx)
          _ (s/check! (and d (not= d (:digest ctx))) (str "the configuration names " d "\n" (s/show r)))
          new (pull (:layout ctx) d)
          today (str (java.time.LocalDate/now))]
      (s/check! (= [] (get-in new [:scoreboard "01-02" :statements])) "the golden was not rewritten")
      (s/check! (= (dissoc (:oracle ctx) :scoreboard :recorded) (dissoc new :scoreboard :recorded)) "other goldens changed")
      (s/check! (= (conj (:recorded (:oracle ctx)) {:date today :reason "the scoreboard now says the denominator moved"
                                                    :changed [:scoreboard]})
                   (:recorded new))
                (str ":recorded is " (pr-str (:recorded new))))
      nil)))

(defstep #"the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged"
  (fn [ctx]
    (s/check! (str/includes? (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn"))) (digest-now ctx)) "no new digest")
    (s/check! (= (:oracle ctx) (pull (:layout ctx) (:digest ctx))) "the old artifact changed")
    nil))

(defn- manifests [layout] (slurp (str (fs/file layout "index.json"))))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx "a reason is required")
    (s/check! (= (:config-before ctx) (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn")))) "the configuration changed")
    (s/check! (= 1 (count (re-seq #"\"digest\"" (manifests (:layout ctx))))) "an artifact was stored")
    nil))

;; ---------------------------------------------------------------------------
;; Nothing is fenced or verified while the oracle is red

(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx]
    (let [ctx (project-with! ctx (assoc-in (oracle-edn) [:checklist "01" :items 0 :level] "contract"))]
      (s/write! (:dir ctx) (mc/cycle-path "03" "cycle.edn") (pr-str (merge {:cut "spec-3" :scope :project} roles)))
      ctx)))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx what]
    (let [r (kc/exits! ctx 1)
          o (kc/run! ctx (:dir ctx) ["oracle"])]
      (s/check! (= 1 (:exit o)) (str "the oracle does not fail\n" (s/show o)))
      (s/check! (= (kc/finding-lines o) (kc/finding-lines r)) (str "the findings are not the oracle's\n" (s/show r) "\n" (s/show o)))
      (if (= what "builds no room")
        (s/check! (not (fs/exists? (:room ctx))) "a room was built")
        (s/check! (not (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn")))) "ratified.edn was written"))
      nil)))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx] (assoc ctx :dir (s/project!))))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (:out r) "no oracle to run") (s/show r))
      (s/check! (empty? (s/err-lines r)) (str "the error stream is not quiet\n" (s/show r)))
      nil)))
