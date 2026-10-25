(ns rdm-spec.measurement.oracle
  "Oracle (dhf/spec/measurement/oracle.feature): `rdm-spec oracle [record
  --reason R]` judges the instrument (the checklist, the scorer and the
  scoreboard) by running it on recorded fixture cycles and comparing what it
  gives with recorded goldens. It is also the gate before fence and ratify
  (`gate`, the command's task table).

  The oracle is one file, oracle.edn, in one artifact kept outside the
  repository, named by the configuration's :oracle {:repository R :digest
  D} and read by its digest only: from the local artifact layout
  ORACLE_LAYOUT names when it is set (`oras pull --oci-layout
  \"$ORACLE_LAYOUT@D\"`), else from the registry repository R (`oras pull
  R@D`). oracle.edn holds

    {:project    {path text}          ; the fixture project, committed in a
                                      ; scratch repository and tagged with
                                      ; every cut its cycles name
     :cycles     {\"NN\" {:cycle {..} :ratified {..} :verdicts [..]}}
     :checklist  {\"NN\" checklist.json, keys as keywords}
     :scorer     {\"NN\" score.edn}
     :scoreboard {\"NN-MM\" the :scoreboard}
     :recorded   [{:date :reason :changed [component]}]}

  Goldens are compared as data. A run, in the scratch repository: for each
  cycle (by name) its cycle.edn, ratified.edn and verdicts are written under
  the fixture's cycles directory, `checklist NN` runs and, for a cycle with
  verdicts, `score NN --verdicts F`; then `scoreboard NN MM` for each
  scoreboard golden. Each task runs in process through the command.

  Outcomes: no :oracle in the configuration is no oracle (exit 0, a
  warning says so); an artifact that cannot be read cannot run (exit 2,
  naming the repository and the digest); findings (exit 1):
    oracle-no-cycle        oracle.edn holds no fixture cycle: it judged nothing
    oracle-malformed       oracle.edn lacks the shape above
    golden-differs         a golden the instrument does not reproduce: the
                           golden, the place (an item by its id) and both values
    golden-orphan          a golden for a fixture cycle oracle.edn does not hold
    golden-unproduced      a golden the fixture gives nothing to compare with
                           (a score golden of a cycle with no verdicts)
    instrument-failed      a task of the instrument that did not pass on a fixture
  The result: {:oracle {:repository :digest :compared [[component key] ..]}}.

  `oracle record --reason R` runs the instrument the same way, rewrites
  every golden it gives differently (and adds the ones missing for a fixture
  cycle), appends {:date :reason :changed} to :recorded, stores the new
  oracle.edn as a new artifact where it read the old one (`oras push`, the
  artifact type below, oracle.edn as application/edn), and rewrites :digest
  in the configuration. The old artifact is left as it was. Without a reason
  it stores nothing (exit 2); when nothing changed it stores nothing either."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")
(def media-type "application/edn")
(def file-name "oracle.edn")
(def components [:checklist :scorer :scoreboard])

;; ---------------------------------------------------------------------------
;; The artifact

(defn- env-of [req] (or (:env req) (into {} (System/getenv))))

(defn- layout-of [req] (not-empty (str/trim (str (get (env-of req) "ORACLE_LAYOUT")))))

(defn- oras!
  "Runs oras in `dir`: {:exit :out :err}; cannot run without oras."
  [req dir & args]
  (let [env (env-of req)]
    (when-not (fs/which "oras" {:paths (str/split (str (get env "PATH")) #":")})
      (command/cannot-run! "the tool oras is not on PATH: the oracle is an artifact read and stored with it"))
    (let [r (apply p/shell {:dir (str dir) :env env :out :string :err :string :continue true :in ""} "oras" args)]
      {:exit (:exit r) :out (str (:out r)) :err (str (:err r))})))

(defn configured
  "The configuration's :oracle {:repository :digest}, nil when it names
  none; cannot run when it is malformed."
  [req]
  (when-let [o (get-in req [:config :oracle])]
    (when-not (and (map? o) (string? (:repository o)) (not (str/blank? (:repository o)))
                   (string? (:digest o)) (re-matches #"sha256:[0-9a-f]{64}" (:digest o)))
      (command/cannot-run! (str "the configuration " command/config-file "'s :oracle " (pr-str o)
                                " is not {:repository R :digest \"sha256:<hex>\"}")))
    o))

(defn- where [req {:keys [repository digest]}]
  (str repository "@" digest (when-let [l (layout-of req)] (str " (from the local artifact layout " l ")"))))

(defn fetch
  "The text of oracle.edn in the artifact the configuration names, read by
  its digest; cannot run when it cannot be read."
  [req {:keys [repository digest] :as o}]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (let [layout (layout-of req)
            r (if layout
                (oras! req tmp "pull" "--oci-layout" (str layout "@" digest) "-o" (str tmp))
                (oras! req tmp "pull" (str repository "@" digest) "-o" (str tmp)))
            f (fs/file tmp file-name)]
        (when-not (zero? (:exit r))
          (command/cannot-run! (str "the oracle " (where req o) " cannot be read: "
                                    (str/trim (str/replace (str (:err r) " " (:out r)) #"\s+" " ")))))
        (when-not (fs/regular-file? f)
          (command/cannot-run! (str "the oracle " (where req o) " holds no " file-name)))
        (slurp (str f)))
      (finally (fs/delete-tree tmp)))))

(defn- read-oracle [req o text]
  (let [m (try (edn/read-string text)
               (catch Exception e (command/cannot-run! (str "the oracle " (where req o) "'s " file-name
                                                            " is not readable EDN: " (ex-message e)))))]
    (when-not (map? m)
      (command/cannot-run! (str "the oracle " (where req o) "'s " file-name " is not an EDN map")))
    m))

(defn- store!
  "Stores `text` as a new artifact where the oracle was read from: its digest."
  [req {:keys [repository] :as o} text]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (spit (str (fs/file tmp file-name)) text)
      (let [layout (layout-of req)
            target (or layout repository)
            r (apply oras! req tmp "push" (concat (when layout ["--oci-layout"])
                                                  [target "--artifact-type" artifact-type
                                                   (str file-name ":" media-type)]))
            digest (second (re-find #"Digest: (sha256:[0-9a-f]{64})" (:out r)))]
        (when-not (and (zero? (:exit r)) digest)
          (command/cannot-run! (str "the new oracle could not be stored in " target ": "
                                    (str/trim (str/replace (str (:err r) " " (:out r)) #"\s+" " ")))))
        digest)
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; Comparing data

(defn- show [v] (binding [*print-namespace-maps* false] (pr-str v)))

(defn- by-id? [xs] (and (seq xs) (every? #(and (map? %) (contains? % :id)) xs)
                        (apply distinct? (map :id xs))))

(defn differences
  "Pure: where `golden` and `actual` differ: [{:at [step ..] :golden :actual}];
  a vector of maps with distinct :id is compared item by item, an item named
  by its id."
  [golden actual]
  (letfn [(walk [at g a]
            (cond
              (= g a) []
              (and (map? g) (map? a))
              (vec (mapcat #(walk (conj at %) (get g % ::absent) (get a % ::absent))
                           (sort-by show (distinct (concat (keys g) (keys a))))))
              (and (sequential? g) (sequential? a) (or (by-id? g) (by-id? a))
                   (or (empty? g) (by-id? g)) (or (empty? a) (by-id? a)))
              (let [ids (distinct (concat (map :id g) (map :id a)))
                    gi (into {} (map (juxt :id identity) g))
                    ai (into {} (map (juxt :id identity) a))]
                (if (and (= (map :id g) (map :id a)) (seq ids))
                  (vec (mapcat #(walk (conj at %) (get gi %) (get ai %)) ids))
                  (into (vec (mapcat #(walk (conj at %) (get gi % ::absent) (get ai % ::absent)) ids))
                        (when (and (= (set (map :id g)) (set (map :id a))) (not= (map :id g) (map :id a)))
                          [{:at (conj at :order) :golden (mapv :id g) :actual (mapv :id a)}]))))
              (and (sequential? g) (sequential? a) (= (count g) (count a)))
              (vec (mapcat #(walk (conj at %) (nth g %) (nth a %)) (range (count g))))
              :else [{:at at :golden g :actual a}]))]
    (walk [] golden actual)))

(defn- show-value [v] (if (= ::absent v) "nothing" (show v)))

(defn- difference-finding [component k {:keys [at golden actual]}]
  (command/finding file-name nil "golden-differs"
                   (str "the golden " (show component) " " (show k) " differs at "
                        (if (seq at) (str/join " " (map #(if (string? %) % (show %)) at)) "the top")
                        ": the golden holds " (show-value golden) ", the "
                        (name component) " gives " (show-value actual))))

;; ---------------------------------------------------------------------------
;; Running the instrument on the fixture

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
                                      "GIT_CONFIG_NOSYSTEM" "1"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                 "-c" "init.defaultBranch=main" args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the oracle's fixture project could not be made: git " (str/join " " args)
                                ": " (str/trim (:err r)))))
    (str/trim (:out r))))

(defn- safe-rel? [rel]
  (let [r (str rel)]
    (and (not (str/blank? r)) (not (fs/absolute? r))
         (not-any? #{".." ".git"} (str/split r #"/")))))

(defn shape-findings
  "What keeps oracle.edn from being judged."
  [m]
  (let [bad (fn [why] (command/finding file-name nil "oracle-malformed" why))]
    (vec (concat
          (when-not (and (map? (:project m)) (seq (:project m))
                         (every? (fn [[k v]] (and (string? k) (safe-rel? k) (string? v))) (:project m)))
            [(bad ":project is not {path text} of the fixture project's files, each path relative and inside it")])
          (when-not (or (nil? (:cycles m)) (map? (:cycles m)))
            [(bad ":cycles is not {\"NN\" {:cycle :ratified :verdicts}}")])
          (for [[n c] (:cycles m)
                :when (not (and (string? n) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" n)
                                (map? c) (map? (:cycle c))))]
            (bad (str "the fixture cycle " (show n) " is not a cycle name with {:cycle {..}}")))
          (for [k (conj components :recorded)
                :let [v (get m k)]
                :when (not (or (nil? v) (if (= :recorded k) (vector? v) (map? v))))]
            (bad (str (show k) " is not " (if (= :recorded k) "a vector" "a map of goldens"))))
          (for [k (keys (:scoreboard m))
                :when (not (and (string? k) (re-matches #"[^-]+-[^-]+" k)))]
            (bad (str "the scoreboard golden " (show k) " is not named \"NN-MM\"")))))))

(defn- fixture!
  "The scratch repository: the fixture project committed and tagged with
  every cut its cycles name, each cycle's files written. Its directory."
  [m]
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-fixture-"}))]
    (doseq [[rel text] (:project m)]
      (let [f (fs/file dir rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) text)))
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "--allow-empty" "-m" "the oracle's fixture project")
    (doseq [cut (sort (distinct (keep #(get-in % [:cycle :cut]) (vals (:cycles m)))))
            :when (and (string? cut) (not (str/blank? cut)))]
      (git! dir "tag" "-f" cut))
    dir))

(defn- cycles-dir [dir]
  (let [c (try (edn/read-string (slurp (str (fs/file dir command/config-file)))) (catch Exception _ nil))]
    (str/replace (str (or (:cycles-dir c) "dhf/cycles")) #"/+$" "")))

(defn- run-instrument!
  "Runs `args` in the scratch `dir` through the command: {:exit :lines :result}."
  [req dir args]
  (let [out (command/execute args {:root dir :env (dissoc (env-of req) "RESULTS_DIR")})]
    (assoc (command/render out) :result (:result out))))

(defn- failed-finding [args {:keys [exit err]}]
  (command/finding file-name nil "instrument-failed"
                   (str "rdm-spec " (str/join " " args) " exited " exit " on the oracle's fixture"
                        (when (seq err) (str ": " (str/join " | " (take 5 err)))))))

(defn produce
  "Runs the instrument on the fixture of `m`: {:goldens {component {key
  value}} :failed #{[component key]} :findings [..]}, the goldens being
  what the instrument gives."
  [req m]
  (let [dir (fixture! m)
        cd (cycles-dir dir)
        out (atom {:goldens {} :failed #{} :findings []})
        fail! (fn [c k args r] (swap! out #(-> % (update :findings conj (failed-finding args r))
                                                (update :failed conj [c k]))))
        put! (fn [c k v] (swap! out assoc-in [:goldens c k] v))]
    (try
      (doseq [[n {:keys [cycle ratified verdicts]}] (sort-by key (:cycles m))
              :let [at #(str cd "/" n "/" %)
                    write! (fn [rel v] (let [f (fs/file dir rel)]
                                         (fs/create-dirs (fs/parent f))
                                         (spit (str f) (show v))))]]
        (write! (at "cycle.edn") cycle)
        (when ratified (write! (at "ratified.edn") ratified))
        (when (some? verdicts) (write! (at "verdicts.edn") verdicts))
        (let [args ["checklist" n]
              r (run-instrument! req dir args)
              f (fs/file dir (at "checklist.json"))]
          (if (and (zero? (:exit r)) (fs/regular-file? f))
            (put! :checklist n (json/parse-string (slurp (str f)) true))
            (fail! :checklist n args r)))
        (when (some? verdicts)
          (let [args ["score" n "--verdicts" (at "verdicts.edn")]
                r (run-instrument! req dir args)
                f (fs/file dir (at "score.edn"))]
            (if (and (zero? (:exit r)) (fs/regular-file? f))
              (put! :scorer n (edn/read-string (slurp (str f))))
              (fail! :scorer n args r)))))
      (doseq [k (sort (distinct (concat (keys (:scoreboard m)) (::pairs m))))
              :let [[a b] (str/split k #"-" 2)
                    args ["scoreboard" a b]
                    r (run-instrument! req dir args)]]
        (if (and (#{0} (:exit r)) (:scoreboard (:result r)))
          (put! :scoreboard k (:scoreboard (:result r)))
          (fail! :scoreboard k args r)))
      @out
      (finally (fs/delete-tree dir)))))

(defn- goldens-of [m] (into {} (for [c components] [c (or (get m c) {})])))

(defn judge
  "Judges the instrument against oracle.edn `m`: {:compared [[c k] ..]
  :findings [..]}."
  [req m]
  (let [shape (shape-findings m)]
    (cond
      (seq shape) {:compared [] :findings shape}

      (empty? (:cycles m))
      {:compared []
       :findings [(command/finding file-name nil "oracle-no-cycle"
                                   "the oracle holds no fixture cycle, so it judged nothing")]}

      :else
      (let [cycles (set (keys (:cycles m)))
            orphans (vec (for [c components
                               k (sort (keys (get m c)))
                               :let [names (if (= :scoreboard c) (str/split k #"-" 2) [k])]
                               :when (not-every? cycles names)]
                           (command/finding file-name nil "golden-orphan"
                                            (str "the golden " (show c) " " (show k)
                                                 " is of a cycle the oracle holds no fixture of"))))
            {:keys [goldens failed findings]} (produce req m)
            compared (vec (for [c components k (sort (keys (get m c)))] [c k]))
            unproduced (vec (for [[c k] compared
                                  :when (and (not (contains? (get goldens c) k)) (not (failed [c k])))
                                  :let [names (if (= :scoreboard c) (str/split k #"-" 2) [k])]
                                  :when (every? cycles names)]
                              (command/finding file-name nil "golden-unproduced"
                                               (str "the golden " (show c) " " (show k) " has nothing to compare"
                                                    " with: its fixture cycle holds no verdicts to score"))))
            diffs (vec (for [[c k] compared
                             :when (contains? (get goldens c) k)
                             d (let [ds (differences (get-in m [c k]) (get-in goldens [c k]))]
                                 (take 20 ds))]
                         (difference-finding c k d)))]
        {:compared compared :findings (vec (concat orphans findings unproduced diffs))}))))

;; ---------------------------------------------------------------------------
;; Recording

(defn- edn-text [m]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint m))))

(defn- today [] (str (java.time.LocalDate/now)))

(defn- rewrite-digest!
  "Rewrites the configuration's :oracle :digest from `old` to `new`, keeping
  the rest of the file as it is."
  [req old new]
  (let [f (command/path req command/config-file)
        text (slurp (str f))
        quoted (str "\"" old "\"")]
    (when-not (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote quoted)) text)))
      (command/cannot-run! (str "the configuration " command/config-file " does not name the digest " old " once")))
    (spit (str f) (str/replace text quoted (str "\"" new "\"")))))

(defn record
  "`rdm-spec oracle record --reason R`."
  [req]
  (let [reason (some-> (get-in req [:opts :reason]) str str/trim)
        _ (when (str/blank? reason)
            (command/cannot-run! (str "a reason is required: rdm-spec oracle record --reason R;"
                                      " a golden changes only for a stated reason, so nothing was stored")))
        o (or (configured req)
              (command/cannot-run! (str "the configuration " command/config-file " names no :oracle, so there is no oracle to record")))
        m (read-oracle req o (fetch req o))
        shape (shape-findings m)
        _ (when (empty? (:cycles m))
            (command/cannot-run! "the oracle holds no fixture cycle: there is nothing to record goldens from"))]
    (if (seq shape)
      {:result {} :findings shape}
      (let [cycles (sort (keys (:cycles m)))
            pairs (map #(str %1 "-" %2) cycles (rest cycles))
            {:keys [goldens findings]} (produce req (assoc m ::pairs (when (empty? (:scoreboard m)) pairs)))]
        (if (seq findings)
          {:result {} :findings findings}
          (let [old (goldens-of m)
                new (into {} (for [c components] [c (merge (get old c) (get goldens c))]))
                changed (vec (filter #(not= (get old %) (get new %)) components))]
            (if (empty? changed)
              {:result {:oracle {:repository (:repository o) :digest (:digest o) :recorded nil}}
               :findings [(command/warning "oracle-unchanged"
                                           "the instrument reproduces every golden: nothing was recorded")]}
              (let [m' (-> (merge m new)
                           (update :recorded (fnil conj []) {:date (today) :reason reason :changed changed}))
                    digest (store! req o (edn-text m'))]
                (rewrite-digest! req (:digest o) digest)
                {:result {:oracle {:repository (:repository o)
                                   :recorded {:digest digest :previous (:digest o) :reason reason
                                              :changed changed}}}
                 :findings []}))))))))

;; ---------------------------------------------------------------------------
;; The stages

(defn- judged [req]
  (when-let [o (configured req)]
    (let [m (read-oracle req o (fetch req o))]
      (assoc (judge req m) :oracle o))))

(defn gate
  "The oracle as the gate before fence and ratify: its findings stop the
  task; a project with no oracle passes."
  [req]
  (if-let [{:keys [findings]} (judged req)]
    {:findings findings}
    {:findings []}))

(defn oracle
  "The oracle task: `rdm-spec oracle [record --reason R]`."
  [req]
  (cond
    (= ["record"] (vec (:args req))) (record req)

    (some? (get-in req [:opts :reason]))
    (command/cannot-run! "--reason goes with record: rdm-spec oracle record --reason R")

    :else
    (if-let [{:keys [oracle compared findings]} (judged req)]
      {:result {:oracle {:repository (:repository oracle) :digest (:digest oracle) :compared compared}}
       :findings findings}
      {:result {:oracle {:compared [] :none true}}
       :findings [(command/warning "oracle-none"
                                   (str "the configuration " command/config-file
                                        " names no :oracle, so there is no oracle to run"))]})))
