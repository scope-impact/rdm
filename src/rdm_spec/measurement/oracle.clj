(ns rdm-spec.measurement.oracle
  "The Oracle (dhf/spec/measurement/oracle.feature; glossary, \"Oracle
  (instrument)\"): every component that judges (the checklist, the scorer,
  the scoreboard, the oracle itself, verify, status, scopes, fence and the
  spec check) is run on recorded fixtures and its answer compared with its
  recorded golden. It judges the instrument; the Allure results remain the
  oracle of a project's scenarios.

  The oracle is one file, oracle.edn, in one artifact, read by the digest
  the configuration's :oracle {:repository R :digest D} names: from the
  local artifact layout ORACLE_LAYOUT names when it is set, else from the
  registry repository R. oracle.edn is flat:

    {:project {path text}            the one fixture project
     :cycles  {\"NN\" {:cycle :ratified :verdicts}}
     :results {\"set\" {file text}}    fixture Allure result sets
     :cuts    {\"set\" {\"spec-N\" {path text} ...}}   fixture cuts
     :<context>/<component> {\"case\" golden ...}       one key per judging component
     :recorded [{:date :reason :changed [{:component :case}] [:added [..]]}]}

  A golden is the component's whole answer on its case's fixture, exactly
  {:exit N :out <standard output read as data> :err \"<the error stream>\"}
  plus :wrote {path content} for a component that writes its record (an
  .edn file read as data, any other as text), every :seconds held as
  :measured, and the fixture's own path written FIXTURE. How each component
  is run on its case (decided by the builder, cycle 18; the spec names the
  fixtures, not the runs):
    :measurement/checklist NN    `checklist NN` on the project, every
                                 fixture cycle's cut tagged on it; wrote
                                 checklist.json
    :measurement/scorer NN       the checklist, the cycle's :ratified as
                                 ratified.edn and its :verdicts, then
                                 `score NN --verdicts ...`; wrote score.edn
    :measurement/scoreboard A-B  both cycles scored so, then `scoreboard A B`
    :measurement/oracle CASE     the oracle itself on the oracle holding the
                                 first two other goldens, its repository and
                                 digest written \"self\"
    :verification/verify CASE    `verify ROOM NN`, NN the first fixture
                                 cycle, the room's tests writing the result
                                 set CASE; wrote ratified.edn
    :verification/status CASE    `status --results DIR`, DIR holding the
                                 result set CASE
    :regeneration/scopes CASE    `scopes FIRST LAST` on a repository holding
                                 each cut of the cut set CASE (the project's
                                 files, the cut's over them) tagged in order
    :regeneration/fence CASE     `fence ROOM LAST --component C,...` there, C
                                 every component the first cut's model names
                                 and the last's does not
    :spec/check CASE             the spec check's stage on the project with
                                 the last cut of the cut set CASE over it
  The fixture's commits are made at a fixed time by a fixed author, so the
  same fixture gives the same commits.

  `oracle` compares every golden (findings oracle-golden-differs, naming the
  component, the case, where, and both values; oracle-golden-missing); an
  oracle holding no fixture cycle is a finding (oracle-no-cycle), since it
  judged nothing. Result: {:oracle {:repository R :digest D :compared
  [{:component :case}] :differences n}}, the goldens ordered by component
  id, then by case, both as text. No :oracle in the configuration is no
  oracle, said on standard output only. One that cannot be read cannot run.

  `oracle record --reason R [--changes C:CASE,...]` adds the goldens of new
  cases (a case with no golden) freely, and changes an existing golden only
  when --changes names it; otherwise it records nothing and names each
  golden it would change (oracle-record-changes), exit 1. It stores a new
  artifact, appends {:date :reason :changed [..]} to :recorded (with :added
  for new cases), and writes the new digest into the configuration, for a
  person to commit. Without a reason it cannot run.

  `gate`: the oracle as the gate stage of fence and verify (the task
  table's): its findings, printed unchanged, stop the task."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.command :as command]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(def judging
  #{:measurement/checklist :measurement/scorer :measurement/scoreboard :measurement/oracle
    :verification/verify :verification/status :regeneration/scopes :regeneration/fence :spec/check})

(defn- one-line [s] (str/replace (str/trim (str s)) #"\s*\r?\n\s*" " "))

;; ---------------------------------------------------------------------------
;; Pure

(defn hold-seconds
  "`x` with every :seconds held as :measured."
  [x]
  (walk/postwalk (fn [y] (if (and (map? y) (some #(= :seconds %) (keys y))) (assoc y :seconds :measured) y)) x))

(defn- component-key? [k] (and (keyword? k) (namespace k)))

(defn cases
  "[[component case]] of the oracle `data`, by component id, then by case,
  both as text."
  [data]
  (vec (sort-by (fn [[c k]] [(subs (str c) 1) (str k)])
                (for [[c m] data :when (and (component-key? c) (map? m)) k (keys m)] [c k]))))

(defn differences
  "[[path golden actual]] where `actual` differs from `golden`, at the
  deepest place both are maps or vectors of one length."
  ([g a] (differences [] g a))
  ([path g a]
   (cond
     (= g a) []
     (and (map? g) (map? a))
     (vec (mapcat #(differences (conj path %) (get g %) (get a %))
                  (sort-by str (distinct (concat (keys g) (keys a))))))
     (and (sequential? g) (sequential? a) (= (count g) (count a)))
     (vec (mapcat #(differences (conj path %) (nth g %) (nth a %)) (range (count g))))
     :else [[path g a]])))

(defn- item-of
  "The :id of the item a path passes through, when one does."
  [golden path]
  (loop [x golden [k & more] path found nil]
    (if (nil? k)
      found
      (let [y (try (get x k) (catch Exception _ nil))]
        (recur y more (if (and (integer? k) (map? y) (:id y)) (:id y) found))))))

(defn difference-findings
  "A finding per difference of the golden `g` of `component` `case` from
  the answer `a` (the first five)."
  [component case g a]
  (let [ds (differences g a)]
    (vec (for [[path gv av] (take 5 ds)
               :let [item (item-of g path)]]
           (command/finding nil nil "oracle-golden-differs"
                            (str (pr-str component) " " (pr-str case) ": at " (pr-str path)
                                 (when item (str " (the item " item ")"))
                                 " the golden holds " (one-line (pr-str gv))
                                 " and the instrument gives " (one-line (pr-str av))))))))

(defn parse-changes
  "The goldens --changes names, C/N:CASE separated by commas: #{[component case]}."
  [s]
  (set (for [x (remove str/blank? (map str/trim (str/split (str s) #",")))]
         (let [[_ c k] (re-matches #"([^:\s/]+/[^:\s/]+):(.+)" x)]
           (when-not c
             (command/cannot-run! (str "--changes names " (pr-str x) ": name each golden COMPONENT:CASE, as measurement/scoreboard:01-02")))
           [(keyword c) k]))))

(defn with-digest
  "The configuration's text `text` with the digest `old` replaced by `new`."
  [text old new]
  (str/replace text (str "\"" old "\"") (str "\"" new "\"")))

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras [dir args]
  (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "oras" (map str args))
       (catch java.io.IOException _
         (command/cannot-run! "oras is not installed: the oracle's artifact is read and stored with it (dhf/harness/stack.md)"))))

(defn- layout [req] (not-empty (get (:env req) "ORACLE_LAYOUT")))

(defn- configured [req]
  (let [o (get-in req [:config :oracle])]
    (when (some? o)
      (when-not (and (map? o) (string? (:repository o)) (string? (:digest o)))
        (command/cannot-run! "the configuration's :oracle is not {:repository R :digest D}"))
      o)))

(defn fetch
  "The oracle.edn the configuration names, read; cannot run when the store
  does not hold it."
  [req {:keys [repository digest]}]
  (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-"}))]
    (try
      (let [lay (layout req)
            ref (if lay (str lay "@" digest) (str repository "@" digest))
            r (oras d (concat ["pull"] (when lay ["--oci-layout"]) [ref "-o" d]))
            f (fs/file d "oracle.edn")]
        (when-not (and (zero? (:exit r)) (fs/regular-file? f))
          (command/cannot-run! (str "the oracle " repository "@" digest " cannot be read"
                                    (if lay (str " from the artifact layout ORACLE_LAYOUT names") "")
                                    ": " (one-line (str (:err r) " " (when (zero? (:exit r)) "it holds no oracle.edn"))))))
        (let [v (try (edn/read-string (slurp (str f)))
                     (catch Exception e (command/cannot-run! (str "the oracle " repository "@" digest "'s oracle.edn is not readable EDN: " (ex-message e)))))]
          (when-not (map? v) (command/cannot-run! (str "the oracle " repository "@" digest "'s oracle.edn is not a map")))
          v))
      (finally (fs/delete-tree d)))))

(defn- store!
  "Stores `data` as a new artifact: its digest."
  [req {:keys [repository]} data]
  (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-"}))]
    (try
      (spit (str (fs/file d "oracle.edn")) (binding [*print-namespace-maps* false] (str (pr-str data) "\n")))
      (let [lay (layout req)
            r (oras d (concat ["push"] (if lay ["--oci-layout" lay] [(str repository ":oracle")])
                              ["--artifact-type" artifact-type "oracle.edn:application/edn"]))
            digest (second (re-find #"Digest:\s*(sha256:[0-9a-f]{64})" (str (:out r))))]
        (when-not (and (zero? (:exit r)) digest)
          (command/cannot-run! (str "the new oracle cannot be stored in " (or lay repository) ": " (one-line (str (:err r) (:out r))))))
        digest)
      (finally (fs/delete-tree d)))))

;; ---------------------------------------------------------------------------
;; Running a component on its fixture

(def ^:private fixed-git
  {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
   "GIT_AUTHOR_DATE" "2026-01-01T00:00:00Z" "GIT_COMMITTER_DATE" "2026-01-01T00:00:00Z"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env (assoc fixed-git "GIT_CEILING_DIRECTORIES" (str (fs/parent (fs/absolutize dir))))}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" "-c" "core.hooksPath=/dev/null"
                 "-c" "init.defaultBranch=main" (map str args))]
    (when-not (zero? (:exit r)) (command/cannot-run! (str "the oracle's fixture cannot be made: git " (first args) ": " (one-line (:err r)))))
    (str/trim (:out r))))

(defn- write-files! [dir files]
  (doseq [[rel text] files]
    (let [f (fs/file dir rel)]
      (fs/create-dirs (fs/parent f))
      (spit (str f) (str text)))))

(defn- cut-number [tag] (or (some-> (re-find #"(\d+)$" (str tag)) second parse-long) 0))

(defn- cycles-dir [cfg] (str/replace (str (or (:cycles-dir cfg) "dhf/cycles")) #"/+$" ""))

(defn- read-cfg [dir]
  (let [f (fs/file dir command/config-file)]
    (or (when (fs/regular-file? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil))) {})))

(defn- run-in
  "Runs `rdm-spec argv` (`stages` instead of the task's, when given) in
  `dir`, the oracle's gate left out: {:exit :out :err}."
  ([ctx dir argv] (run-in ctx dir argv nil))
  ([{:keys [env root]} dir argv stages]
   (let [t (or (command/task-of (first argv)) (command/cannot-run! (str "no task " (first argv))))
         t (cond-> (update t :stages (fn [ss] (vec (remove #(= `gate (:fn %)) ss))))
             stages (assoc :stages stages))
         r (try (let [{:keys [args opts]} (command/parse-args t (rest argv))]
                  (command/run-task t {:root (str dir) :config (read-cfg dir) :opts opts :args args :env env}))
                (catch clojure.lang.ExceptionInfo e
                  (if (command/cannot-run? e)
                    {:exit 2 :err-lines [(str "cannot-run: " (one-line (ex-message e)))]}
                    (throw e))))
         out (command/output r)
         fixture (str (fs/absolutize root))
         clean #(str/replace (str %) fixture "FIXTURE")]
     {:exit (:exit r)
      :out (when-not (str/blank? out) (edn/read-string (clean out)))
      :err (clean (str/join (map #(str % "\n") (:err-lines r))))})))

(defn- wrote [dir rels]
  (into {}
        (for [rel rels :let [f (fs/file dir rel)]]
          [rel (when (fs/regular-file? f)
                 (if (str/ends-with? rel ".edn")
                   (edn/read-string (slurp (str f)))
                   (slurp (str f))))])))

(defn- golden-of [g] (-> g (update :out hold-seconds) (cond-> (:wrote g) (update :wrote hold-seconds))))

(defn- project!
  "The fixture project under the fixture root: its files, one commit, each
  fixture cycle's cut tagged on it and its cycle.edn written."
  [{:keys [root data]}]
  (let [dir (str (fs/file root "project"))]
    (fs/create-dirs dir)
    (write-files! dir (:project data))
    (git! dir "init" "-q")
    (git! dir "add" "-A" "-f" ".")
    (git! dir "commit" "-q" "--allow-empty" "-m" "the oracle's fixture project")
    (let [cd (cycles-dir (read-cfg dir))]
      (doseq [[n c] (:cycles data)]
        (when-let [cut (get-in c [:cycle :cut])] (git! dir "tag" "-f" cut))
        (write-files! dir {(str cd "/" n "/cycle.edn") (pr-str (:cycle c))})))
    dir))

(defn- scored! [ctx dir n]
  (let [cd (cycles-dir (read-cfg dir))
        c (get-in ctx [:data :cycles n])]
    (run-in ctx dir ["checklist" n])
    (write-files! dir {(str cd "/" n "/ratified.edn") (pr-str (:ratified c))
                       (str cd "/" n "/verdicts.edn") (pr-str (:verdicts c))})
    (run-in ctx dir ["score" n "--verdicts" (str cd "/" n "/verdicts.edn")])))

(defn- cut-repo!
  "A repository holding each cut of the cut set `k` in order, the project's
  files with the cut's over them: [dir first-tag last-tag]."
  [{:keys [root data]} k]
  (let [cuts (or (get-in data [:cuts k]) (command/cannot-run! (str "the oracle holds no cut set " (pr-str k))))
        tags (sort-by cut-number (keys cuts))
        dir (str (fs/file root (str "cuts-" (count (fs/list-dir root)))))]
    (fs/create-dirs dir)
    (git! dir "init" "-q")
    (doseq [t tags]
      (doseq [f (fs/list-dir dir) :when (not= ".git" (str (fs/file-name f)))] (fs/delete-tree f))
      (write-files! dir (merge (:project data) (get cuts t)))
      (git! dir "add" "-A" "-f" ".")
      (git! dir "commit" "-q" "--allow-empty" "-m" (str "the cut " t))
      (git! dir "tag" "-f" t))
    [dir (first tags) (last tags)]))

(defn- model-components [m]
  (set (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x) comp (:components x) :when (map? comp)] (:id comp))))

(declare judge)

(defn- run-case
  "The answer of `component` on its case `k`, as a golden holds it."
  [ctx component k]
  (let [data (:data ctx)
        cd (fn [dir] (cycles-dir (read-cfg dir)))
        first-cycle (first (sort (keys (:cycles data))))]
    (golden-of
     (case component
       :measurement/checklist
       (let [dir (project! ctx)
             g (run-in ctx dir ["checklist" k])]
         (assoc g :wrote (wrote dir [(str (cd dir) "/" k "/checklist.json")])))

       :measurement/scorer
       (let [dir (project! ctx)
             g (scored! ctx dir k)]
         (assoc g :wrote (wrote dir [(str (cd dir) "/" k "/score.edn")])))

       :measurement/scoreboard
       (let [[a b] (str/split (str k) #"-" 2)
             dir (project! ctx)]
         (scored! ctx dir a)
         (scored! ctx dir b)
         (run-in ctx dir ["scoreboard" a b]))

       :measurement/oracle
       (let [others (take 2 (remove #(= :measurement/oracle (first %)) (cases data)))
             inner (reduce (fn [m [c kk]] (assoc-in m [c kk] (get-in (:goldens ctx) [c kk] (get-in data [c kk]))))
                           (select-keys data [:project :cycles :results :cuts :recorded])
                           others)
             {:keys [result findings]} (judge (assoc ctx :data inner) {:repository "self" :digest "self"})]
         {:exit (if (some (complement command/warning?) findings) 1 0)
          :out result
          :err (str/join (map #(str (command/format-finding %) "\n") findings))})

       :verification/verify
       (let [dir (project! ctx)
             rs (or (get-in data [:results k]) (command/cannot-run! (str "the oracle holds no result set " (pr-str k))))
             room (str (fs/file (:root ctx) (str "room-" k)))
             _ (write-files! room (into {"test.clj" (str "(require '[babashka.fs :as fs])\n"
                                                         "(doseq [f (fs/glob \"test/oracle-results\" \"*\")]\n"
                                                         "  (fs/copy f (fs/file (System/getenv \"RESULTS_DIR\") (fs/file-name f)) {:replace-existing true}))\n")}
                                        (for [[n text] rs] [(str "test/oracle-results/" n) text])))
             g (run-in ctx dir ["verify" (str "../room-" k) first-cycle])]
         (assoc g :wrote (wrote dir [(str (cd dir) "/" first-cycle "/ratified.edn")])))

       :verification/status
       (let [dir (project! ctx)
             rs (or (get-in data [:results k]) (command/cannot-run! (str "the oracle holds no result set " (pr-str k))))]
         (write-files! dir (into {} (for [[n text] rs] [(str "oracle-results/" k "/" n) text])))
         (run-in ctx dir ["status" "--results" (str "oracle-results/" k)]))

       :regeneration/scopes
       (let [[dir a b] (cut-repo! ctx k)]
         (run-in ctx dir ["scopes" a b]))

       :regeneration/fence
       (let [[dir a b] (cut-repo! ctx k)
             model-rel (or (:model (read-cfg dir)) "dhf/spec/model.edn")
             m-of (fn [tag] (try (edn/read-string (str (get (merge (:project data) (get-in data [:cuts k tag])) model-rel)))
                                 (catch Exception _ {})))
             removed (sort (remove (model-components (m-of b)) (model-components (m-of a))))
             argv (cond-> ["fence" (str "../room-" (fs/file-name dir)) b]
                    (seq removed) (into ["--component" (str/join "," (map #(subs (str %) 1) removed))]))]
         (run-in ctx dir argv))

       :spec/check
       (let [cuts (or (get-in data [:cuts k]) (command/cannot-run! (str "the oracle holds no cut set " (pr-str k))))
             dir (str (fs/file (:root ctx) (str "check-" k)))]
         (write-files! dir (merge (:project data) (get cuts (last (sort-by cut-number (keys cuts))))))
         (run-in ctx dir ["check"] [{:fn 'rdm-spec.spec.check/check}]))

       (command/cannot-run! (str (pr-str component) " is not a component that judges: the oracle runs "
                                 (str/join ", " (sort (map #(subs (str %) 1) judging)))))))))

(defn- answer
  "The answer of `component` on case `k`, in a fixture root of its own."
  [ctx component k]
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-fixture-"}))]
    (try (run-case (assoc ctx :root root :env (merge (:env ctx) fixed-git)) component k)
         (finally (fs/delete-tree root)))))

(defn- answers
  "{[component case] answer} of every case, the oracle's own last, so its
  case reads the answers of the others."
  [ctx]
  (let [cs (cases (:data ctx))
        [own others] ((juxt filter remove) #(= :measurement/oracle (first %)) cs)
        got (into {} (for [[c k] others] [[c k] (answer ctx c k)]))
        goldens (reduce (fn [m [[c k] g]] (assoc-in m [c k] g)) {} got)]
    (into got (for [[c k] own] [[c k] (answer (assoc ctx :goldens goldens) c k)]))))

;; ---------------------------------------------------------------------------
;; Judging

(defn judge
  "Compares every golden of the oracle `data` (in `ctx`) with the
  instrument's answer: {:result :findings}."
  [ctx {:keys [repository digest]}]
  (let [data (:data ctx)
        cs (cases data)
        got (answers (assoc ctx :goldens nil))
        no-cycle (when (empty? (:cycles data))
                   [(command/finding nil nil "oracle-no-cycle"
                                     "the oracle holds no fixture cycle, so it judged nothing: a judge needs a cycle to judge")])
        per (for [[c k] cs
                  :let [g (get-in data [c k])]]
              (if (nil? g)
                [(command/finding nil nil "oracle-golden-missing"
                                  (str (pr-str c) " " (pr-str k) " has no golden: record it with rdm-spec oracle record --reason R"))]
                (difference-findings c k g (get got [c k]))))]
    {:result {:oracle {:repository repository :digest digest
                       :compared (mapv (fn [[c k]] {:component c :case k}) cs)
                       :differences (count (filter seq per))}}
     :findings (vec (concat no-cycle (apply concat per)))}))

(defn- record! [req o data]
  (let [reason (get-in req [:opts :reason])
        _ (when (str/blank? reason)
            (command/cannot-run! "a reason is required: rdm-spec oracle record --reason R (a golden changes only for a stated reason)"))
        named (parse-changes (get-in req [:opts :changes]))
        got (answers {:data data :env (:env req)})
        cs (cases data)
        added (vec (for [[c k] cs :when (nil? (get-in data [c k]))] [c k]))
        changed (vec (for [[c k] cs :let [g (get-in data [c k])] :when (and (some? g) (not= g (get got [c k])))] [c k]))
        unnamed (remove named changed)]
    (if (seq unnamed)
      {:result {}
       :findings (vec (for [[c k] unnamed]
                        (command/finding nil nil "oracle-record-changes"
                                         (str (pr-str c) " " (pr-str k) " is an existing golden the record would change: "
                                              "nothing was recorded; name it with --changes " (subs (str c) 1) ":" k
                                              " when the behaviour changed on purpose"))))}
      (let [entry (cond-> {:date (str (java.time.LocalDate/now)) :reason reason
                           :changed (mapv (fn [[c k]] {:component c :case k}) changed)}
                    (seq added) (assoc :added (mapv (fn [[c k]] {:component c :case k}) added)))
            new-data (-> (reduce (fn [m [c k]] (assoc-in m [c k] (get got [c k]))) data (concat added changed))
                         (update :recorded #(conj (vec %) entry)))
            digest (store! req o new-data)
            cf (command/path req command/config-file)
            text (slurp (str cf))]
        (spit (str cf) (if (str/includes? text (str "\"" (:digest o) "\""))
                         (with-digest text (:digest o) digest)
                         (str (pr-str (assoc-in (edn/read-string text) [:oracle :digest] digest)) "\n")))
        {:result {:oracle {:recorded {:repository (:repository o) :digest digest
                                      :changed (:changed entry) :added (vec (:added entry))}}}
         :findings []}))))

(def ^:private none "there is no oracle to run: the configuration names no :oracle")

(defn oracle
  "The oracle stage: `rdm-spec oracle [record --reason R]`."
  [req]
  (let [[sub] (:args req)
        _ (when (and sub (not= "record" sub))
            (command/cannot-run! (str "rdm-spec oracle takes record or nothing, not " sub ": oracle [record --reason R]")))
        o (configured req)]
    (cond
      (nil? o) (if sub
                 (command/cannot-run! (str none ", so there is nothing to record into"))
                 {:result {:oracle {:note none}} :findings []})
      sub (record! req o (fetch req o))
      :else (judge {:data (fetch req o) :env (:env req)} o))))

(defn gate
  "The gate before fence and verify: the oracle's findings, if it has any."
  [req]
  (if-let [o (configured req)]
    {:result {} :findings (:findings (judge {:data (fetch req o) :env (:env req)} o))}
    {:result {} :findings []}))
