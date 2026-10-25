(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature.

  A fixture oracle is an oracle.edn pushed into a new local artifact layout
  (`oras push --oci-layout`, under the fixture root), the project's
  configuration naming it by digest under a repository that does not exist,
  and ORACLE_LAYOUT set in the scenario's environment (:env). A decoy
  artifact holding no fixture cycle is pushed after it, tagged latest, so
  only a read by digest finds the fixture.

  The fixture's goldens: the checklist and score of each cycle, written here
  as their contracts state them (spec-0210, spec-0174); the scoreboard's,
  recorded by running the scoreboard on those scores. The oracle's result
  ({:oracle ..} on standard output) is the shape
  rdm-spec.measurement.oracle states."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def repository "registry.invalid/scope-impact/rdm-spec-oracle")

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

;; ---------------------------------------------------------------------------
;; Artifacts

(defn- oras! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" (map str args))]
    (s/check! (zero? (:exit r)) (str "oras " (str/join " " args) " failed: " (:err r) (:out r)))
    (str/trim (:out r))))

(defn- push!
  "Pushes `text` as oracle.edn into the layout `layout` (tagged `tag` when
  given): its digest."
  ([layout text] (push! layout text nil))
  ([layout text tag]
   (let [d (s/tmp-dir)]
     (try
       (spit (str (fs/file d "oracle.edn")) text)
       (oras! d "push" "--oci-layout" (str layout (when tag (str ":" tag))) "--artifact-type" artifact-type
              "oracle.edn:application/edn" "--format" "go-template={{.digest}}")
       (finally (fs/delete-tree d))))))

(defn- pull
  "{file-name text} of the artifact `digest` in the layout `layout`."
  [layout digest]
  (let [d (s/tmp-dir)]
    (try
      (oras! d "pull" "--oci-layout" (str layout "@" digest) "-o" (str d "/out"))
      (into {} (for [f (fs/list-dir (fs/file d "out"))] [(str (fs/file-name f)) (slurp (str f))]))
      (finally (fs/delete-tree d)))))

(defn- manifests [layout]
  (count (re-seq #"\"digest\"" (slurp (str (fs/file layout "index.json"))))))

(defn- blobs [layout]
  (set (map str (fs/glob (fs/file layout "blobs") "**"))))

;; ---------------------------------------------------------------------------
;; The fixture oracle

(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn- room-file [rel] (slurp (str (fs/file s/room-root rel))))

(defn- fixture-project []
  (into (sorted-map)
        (concat {"dhf/harness/config.edn" (str (pr-str (dissoc (s/room-config) :oracle :regulated)) "\n")
                 mc/lint-feature (mc/feature-text mc/default-scenarios)}
                (for [rel ["dhf/spec/model.edn" "dhf/spec/needs.edn" "dhf/spec/events.edn"]]
                  [rel (room-file rel)]))))

(def ^:private verdicts
  {"01" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]
   "02" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
         {:id "spec-0014" :verdict :pass :evidence "the view over the limit was named"}]})

(defn- fixture-cycle [nn cut]
  {:cycle (merge mc/default-cycle {:cut cut})
   :ratified {:cut cut :room-tree tree :outcome :passed :checkout (str "dhf/cycles/" nn "/build")}
   :verdicts (verdicts nn)})

(defn- golden-checklist [nn cut]
  {:cycle nn :cut cut
   :items [{:id "spec-0012" :title "A view with no description fails the lint"
            :context "architecture" :component "Lint" :level "e2e"}
           {:id "spec-0014" :title "A view over the limit fails the lint"
            :context "architecture" :component "Lint" :level "contract"}]
   :planned [{:id "spec-0013" :title "A component that owns no feature file fails the lint"}]})

(defn- golden-score [nn cut]
  (let [vs (verdicts nn)]
    (merge {:cycle nn :cut cut :scope :project :room-tree tree}
           (select-keys mc/default-cycle [:builder :grader :spec-owner])
           {:items vs :passed (count (filter #(= :pass (:verdict %)) vs)) :scored (count vs)})))

(defn- golden-scoreboard
  "The scoreboard of the two golden scores, recorded by running it."
  [scores]
  (let [dir (s/project!)]
    (try
      (doseq [[nn sc] scores]
        (mc/write-cycle! dir nn {:cut (:cut sc)})
        (mc/write-edn! dir (mc/cycle-path nn "score.edn") sc))
      (let [r (s/rdm-spec! dir "scoreboard" "01" "02")]
        (s/check! (zero? (:exit r)) (str "recording the scoreboard's golden failed\n" (s/show r)))
        (:scoreboard (edn/read-string (:out r))))
      (finally (fs/delete-tree dir)))))

(defn- fixture-oracle []
  (let [scores {"01" (golden-score "01" "spec-1") "02" (golden-score "02" "spec-2")}]
    {:project (fixture-project)
     :cycles {"01" (fixture-cycle "01" "spec-1") "02" (fixture-cycle "02" "spec-2")}
     :checklist {"01" (golden-checklist "01" "spec-1") "02" (golden-checklist "02" "spec-2")}
     :scorer scores
     :scoreboard {"01-02" (golden-scoreboard scores)}
     :recorded [{:date "2026-10-25" :reason "the first goldens" :changed [:checklist :scorer :scoreboard]}]}))

(defn- oracle-text [o] (str (pr-str o) "\n"))

(defn- configured!
  "ctx with the oracle `o` pushed into a new layout and the project (:dir,
  else a new one) configured to read it."
  [ctx o]
  (let [layout (str (fs/file (s/tmp-dir) "layout"))
        text (oracle-text o)
        digest (push! layout text)
        _ (push! layout (oracle-text (assoc o :cycles {} :checklist {} :scorer {} :scoreboard {})) "latest")
        dir (or (:dir ctx) (s/project!))
        cfg (assoc (kc/config-of dir) :oracle {:repository repository :digest digest})]
    (mc/write-edn! dir "dhf/harness/config.edn" cfg)
    (-> ctx
        (assoc :dir dir :layout layout :digest digest :oracle o :oracle-text text :blobs (blobs layout))
        (assoc-in [:env "ORACLE_LAYOUT"] layout))))

(defn- oracle-result [ctx]
  (let [r (kc/the-run ctx)
        out (kc/stdout-edn r)]
    (s/check! (map? (:oracle out)) (str "the result holds no :oracle\n" (s/show r)))
    (:oracle out)))

;; ---------------------------------------------------------------------------
;; One EDN file in one artifact, pinned by digest

(defstep #"the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each"
  (fn [ctx] (configured! ctx (fixture-oracle))))

(defn- keys-of-doc [doc]
  (set (map (comp keyword second) (re-seq #"(?m)^\{?\s*:([a-z-]+)" doc))))

(defstep #"it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle\.edn:"
  (fn [ctx doc]
    (let [r (kc/exits! ctx 0)
          res (oracle-result ctx)
          files (pull (:layout ctx) (:digest ctx))
          o (edn/read-string (get files "oracle.edn"))]
      (s/check! (= ["oracle.edn"] (keys files)) (str "the artifact holds " (pr-str (keys files))))
      (s/check! (= (keys-of-doc doc) (set (keys o))) (str "oracle.edn's keys are " (pr-str (keys o))))
      (s/check! (= {:repository repository :digest (:digest ctx)} (select-keys res [:repository :digest]))
                (str "the result names " (pr-str (select-keys res [:repository :digest])) "\n" (s/show r))))))

(def ^:private all-goldens
  #{[:checklist "01"] [:checklist "02"] [:scorer "01"] [:scorer "02"] [:scoreboard "01-02"]})

(defstep #"it exits 0 and names each golden it compared(?:, as its component and key)?"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          compared (:compared (oracle-result ctx))]
      (s/check! (= all-goldens (set compared)) (str "it compared " (pr-str compared) "\n" (s/show r)))
      (s/check! (= (count all-goldens) (count compared)) (str "a golden is named twice: " (pr-str compared))))))

(defstep #"the configured oracle's oracle\.edn holds no fixture cycle"
  (fn [ctx]
    (configured! ctx (assoc (fixture-oracle) :cycles {} :checklist {} :scorer {} :scoreboard {}))))

(defstep #"it exits 1 and says the oracle holds no fixture cycle, so it judged nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "no fixture cycle") (str/includes? % "judged nothing")) (kc/finding-lines r))
                (str "no finding says the oracle judged nothing\n" (s/show r))))))

(defstep #"the configuration's :oracle names a digest the store does not hold"
  (fn [ctx]
    (let [ctx (configured! ctx (fixture-oracle))
          missing (str "sha256:" (apply str (repeat 64 "e")))]
      (mc/write-edn! (:dir ctx) "dhf/harness/config.edn"
                     (assoc-in (kc/config-of (:dir ctx)) [:oracle :digest] missing))
      (assoc ctx :digest missing))))

(defstep #"it exits 2 and names the repository and the digest"
  (fn [ctx] (kc/refused-naming! ctx repository (:digest ctx)) nil))

;; ---------------------------------------------------------------------------
;; The instrument is judged against recorded goldens

(defstep #"the configured oracle holds a golden score whose second item says pass where the scorer files fail"
  (fn [ctx]
    (let [o (fixture-oracle)]
      (s/check! (= :fail (get-in o [:scorer "01" :items 1 :verdict])) "the fixture's second item is no fail")
      (configured! ctx (assoc-in o [:scorer "01" :items 1 :verdict] :pass)))))

(defstep #"it exits 1 and names the golden, the item and both values"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some (fn [l] (every? #(str/includes? l %) [":scorer" "\"01\"" "spec-0014" ":pass" ":fail"]))
                      (kc/finding-lines r))
                (str "no finding names the golden :scorer \"01\", the item spec-0014 and both values\n" (s/show r))))))

(defstep #"the configured oracle holds goldens, one of which the instrument no longer reproduces"
  (fn [ctx]
    (let [dir (s/project!)
          o (fixture-oracle)
          ctx (configured! (assoc ctx :dir dir) (assoc-in o [:scoreboard "01-02" :compared] 99))]
      ;; the configuration is committed, so what record changes is left for a person
      (mc/cut! dir "spec-1")
      (assoc ctx :reproduced (get-in o [:scoreboard "01-02"])))))

(defn- new-digest [ctx] (get-in (kc/config-of (:dir ctx)) [:oracle :digest]))

(defstep #"it stores a new artifact whose oracle\.edn rewrites that golden and appends to :recorded the date, the reason and the component changed"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          d (new-digest ctx)
          _ (s/check! (not= d (:digest ctx)) (str "the configuration still names the old digest\n" (s/show r)))
          o (edn/read-string (get (pull (:layout ctx) d) "oracle.edn"))
          old (:oracle ctx)]
      (s/check! (= (:reproduced ctx) (get-in o [:scoreboard "01-02"]))
                (str "the scoreboard golden is " (pr-str (get-in o [:scoreboard "01-02"]))))
      (doseq [k [:project :cycles :checklist :scorer]]
        (s/check! (= (get old k) (get o k)) (str k " changed")))
      (s/check! (= (conj (:recorded old) {:date (str (java.time.LocalDate/now))
                                          :reason "the scoreboard now says the denominator moved"
                                          :changed [:scoreboard]})
                   (:recorded o))
                (str ":recorded is " (pr-str (:recorded o)))))))

(defstep #"the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged"
  (fn [ctx]
    (let [dir (:dir ctx)
          d (new-digest ctx)]
      (s/check! (= {:repository repository :digest d} (:oracle (kc/config-of dir))) "the configuration's :oracle")
      (s/check! (= "M dhf/harness/config.edn" (mc/git! dir "status" "--porcelain" "--" "dhf/harness/config.edn"))
                "the configuration is not left changed and uncommitted")
      (s/check! (= "the cut spec-1" (mc/git! dir "log" "-1" "--format=%s")) "record made a commit")
      (s/check! (= {"oracle.edn" (:oracle-text ctx)} (pull (:layout ctx) (:digest ctx))) "the old artifact changed"))))

(defstep #"it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (some #(str/includes? % "reason is required") (kc/cannot-run-lines r)) (str "no line says a reason is required\n" (s/show r)))
      (s/check! (= 2 (manifests (:layout ctx))) "a new artifact was stored")
      (s/check! (= (:blobs ctx) (blobs (:layout ctx))) "the store changed")
      (s/check! (= {:repository repository :digest (:digest ctx)} (:oracle (kc/config-of (:dir ctx))))
                "the configuration's :oracle changed"))))

;; ---------------------------------------------------------------------------
;; Nothing is fenced or ratified while the oracle is red

(defn- files-under [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)] [(str (fs/relativize d f)) (slurp (str f))])))

;; ROOM is an existing room ratify could ratify; fence would refuse it as it
;; exists, so the exit 1 and the oracle's findings are the gate's.
(defstep #"a project whose configured oracle holds a golden the instrument does not reproduce"
  (fn [ctx]
    (let [room (mc/passing-room! ["spec-0012" "spec-0014"])
          dir (mc/project! {mc/lint-feature mc/default-scenarios})
          ctx (configured! (assoc ctx :dir dir) (assoc-in (fixture-oracle) [:checklist "02" :items 0 :title] "Another title"))]
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (assoc ctx :cycle "03" :room room :room-files (files-under room)))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx what]
    (let [r (kc/exits! ctx 1)
          o (kc/run! ctx (:dir ctx) ["oracle"])
          fl (kc/finding-lines r)]
      (s/check! (= 1 (:exit o)) (str "the oracle itself does not fail\n" (s/show o)))
      (s/check! (seq fl) (str "no finding was printed\n" (s/show r)))
      (s/check! (= (kc/finding-lines o) fl) (str "the findings are not the oracle's\n" (s/show r) "\n" (s/show o)))
      (case what
        "builds no room" (s/check! (= (:room-files ctx) (files-under (:room ctx))) "the room changed")
        (s/check! (not (fs/exists? (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn")))) "ratified.edn was written")))))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx]
    (let [dir (s/project!)]
      (s/check! (not (contains? (kc/config-of dir) :oracle)) "the project's configuration names an oracle")
      (assoc ctx :dir dir))))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (:out r) "no oracle to run") (str "the result does not say there is no oracle to run\n" (s/show r)))
      (s/check! (empty? (s/err-lines r)) (str "the error stream is not quiet\n" (s/show r))))))
