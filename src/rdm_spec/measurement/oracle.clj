(ns rdm-spec.measurement.oracle
  "Oracle (dhf/spec/measurement/oracle.feature; glossary \"Oracle
  (instrument)\"): `rdm-spec oracle` judges the instrument (the checklist,
  the scorer and the scoreboard) on recorded fixture cycles against their
  recorded goldens. It is also the gate the task table runs before fence and
  ratify.

  The oracle is one file, oracle.edn, in one artifact kept outside the
  repository and named by the configuration's :oracle {:repository R :digest
  D}. It is read by its digest only: from the local artifact layout
  ORACLE_LAYOUT names when it is set, else from the repository R (the
  artifact tool, dhf/harness/stack.md). oracle.edn holds

    {:project    {path text}            ; one fixture project
     :cycles     {\"NN\" {:cycle {..} :ratified {..} :verdicts [..]}}
     :checklist  {\"NN\" checklist.json, keywordized}
     :scorer     {\"NN\" score.edn}
     :scoreboard {\"NN-MM\" the :scoreboard of rdm-spec scoreboard NN MM}
     :recorded   [{:date \"YYYY-MM-DD\" :reason \"..\" :changed [component]}]}

  Judging: the project is committed in a scratch repository and tagged with
  every cut the cycles name; each cycle's cycle.edn, ratified.edn and
  verdicts.edn are written under the project's cycles directory; then the
  instrument runs (checklist NN, score NN --verdicts .., scoreboard NN MM)
  and each output is compared, as data, with its golden.

    no :oracle configured     exit 0, nothing to run (the result says so)
    an :oracle not {:repository :digest}, an artifact the store does not
    hold, no artifact tool, an oracle.edn that is not an EDN map
                              cannot run (exit 2), naming repository and digest
    oracle-empty              the oracle holds no fixture cycle: it judged nothing
    golden-differs            an output differs from its golden: the golden
                              (component and key), where, and both values
    instrument-failed         the instrument could not produce an output
  The result: {:oracle {:repository :digest :compared [{:component :key}]}}.

  `rdm-spec oracle record --reason R` reproduces every golden, stores a new
  artifact whose oracle.edn holds the new goldens and appends {:date :reason
  :changed} to :recorded, and writes the new digest into the configuration,
  for a person to commit: it never commits. Without a reason it cannot run
  and stores nothing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")
(def media-type "application/edn")
(def file-name "oracle.edn")

(def components
  "Each instrument component with its golden key in oracle.edn, in the order
  they run."
  [:checklist :scorer :scoreboard])

(def component-names {:checklist "the checklist" :scorer "the scorer" :scoreboard "the scoreboard"})

;; ---------------------------------------------------------------------------
;; Pure: comparing outputs with goldens

(defn differences
  "Every place the output `actual` differs from the golden `golden`:
  [{:path [k|i ..] :golden v :actual v}], maps compared by key, sequences
  by position (a different length is one difference at the sequence)."
  ([golden actual] (differences [] golden actual))
  ([path golden actual]
   (cond
     (= golden actual) []
     (and (map? golden) (map? actual))
     (vec (mapcat #(differences (conj path %) (get golden % ::absent) (get actual % ::absent))
                  (sort-by pr-str (distinct (concat (keys golden) (keys actual))))))
     (and (sequential? golden) (sequential? actual))
     (let [g (vec golden) a (vec actual)]
       (if (= (count g) (count a))
         (vec (mapcat #(differences (conj path %) (g %) (a %)) (range (count g))))
         [{:path path :golden golden :actual actual}]))
     :else [{:path path :golden golden :actual actual}])))

(defn- value-text [v] (if (= ::absent v) "nothing" (pr-str v)))

(defn path-text
  "The path of a difference as a finding names it: a position in a sequence
  of items is followed by the item's id, when it has one."
  [golden path]
  (if (empty? path)
    "the whole"
    (str/join " "
              (loop [[k & more] path at golden out []]
                (if (nil? k)
                  out
                  (let [nxt (cond (map? at) (get at k)
                                  (and (sequential? at) (integer? k) (< -1 k (count at))) (nth (vec at) k)
                                  :else nil)
                        id (when (and (integer? k) (map? nxt)) (or (:id nxt) (get nxt "id")))]
                    (recur more nxt (conj out (if id (str (pr-str k) " (" id ")") (pr-str k))))))))))

(defn golden-findings
  "The findings for the golden `component` `key` against the output: one per
  difference (at most `limit`, then one saying how many more)."
  ([component key golden actual] (golden-findings component key golden actual 10))
  ([component key golden actual limit]
   (let [ds (differences golden actual)
         label (str "the golden " (pr-str component) " " (pr-str key))]
     (vec (concat
           (for [{:keys [path] g :golden a :actual} (take limit ds)]
             (command/finding file-name nil "golden-differs"
                              (str label " differs at " (path-text golden path) ": the golden holds " (value-text g)
                                   ", " (component-names component) " gives " (value-text a))))
           (when (> (count ds) limit)
             [(command/finding file-name nil "golden-differs"
                               (str label " differs in " (- (count ds) limit) " more places"))]))))))

(defn compare-goldens
  "{:compared [{:component :key}] :findings [..]}: every golden of the
  oracle `o` against the outputs {[component key] value}; a golden with no
  output is not compared (its instrument failed, and said so)."
  [o outputs]
  (let [pairs (for [c components k (sort (keys (get o c)))] [c k])]
    {:compared (vec (for [[c k] pairs :when (contains? outputs [c k])] {:component c :key k}))
     :findings (vec (mapcat (fn [[c k]]
                              (when (contains? outputs [c k])
                                (golden-findings c k (get-in o [c k]) (get outputs [c k]))))
                            pairs))}))

(defn recorded
  "The oracle `o` with every golden the outputs reproduce rewritten, and a
  :recorded entry naming the components whose goldens changed."
  [o outputs date reason]
  (let [o' (reduce (fn [m [[c k] v]] (assoc-in m [c k] v)) o (sort-by (comp pr-str key) outputs))
        changed (vec (filter #(not= (get o %) (get o' %)) components))]
    (update o' :recorded (fnil conj []) {:date date :reason reason :changed changed})))

(defn with-digest
  "The configuration text `text` with its :oracle's digest `old` replaced by
  `new`, its comments and layout kept."
  [text old new]
  (if (and old (str/includes? text old))
    (str/replace text old new)
    (let [c (edn/read-string text)]
      (str (pr-str (assoc-in c [:oracle :digest] new)) "\n"))))

;; ---------------------------------------------------------------------------
;; The artifact

(defn- env-of [req] (or (:env req) (into {} (System/getenv))))

(defn- oras!
  "Runs the artifact tool in `dir`: {:exit :out :err}; cannot run without it."
  [req dir & args]
  (try
    (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :env (env-of req)} "oras" args)
    (catch java.io.IOException e
      (command/cannot-run! (str "the artifact tool oras could not be run (is it on PATH?): " (ex-message e))))))

(defn- first-line [s] (first (remove str/blank? (str/split-lines (str s)))))

(defn configured
  "The configuration's :oracle, checked: {:repository R :digest D}, or nil
  when there is none."
  [req]
  (let [o (get-in req [:config :oracle])]
    (cond
      (nil? o) nil
      (and (map? o) (string? (:repository o)) (not (str/blank? (:repository o)))
           (string? (:digest o)) (re-matches #"[a-z0-9]+:[0-9a-f]{32,}" (:digest o))) o
      :else (command/cannot-run! (str "the configuration " command/config-file " has :oracle " (pr-str o)
                                      ": it is {:repository R :digest \"sha256:...\"}")))))

(defn layout [req] (not-empty (get (env-of req) "ORACLE_LAYOUT")))

(defn fetch
  "The text of oracle.edn in the artifact `digest` of `repository`, read by
  its digest from ORACLE_LAYOUT when set, else from the repository."
  [req {:keys [repository digest]}]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-pull-"})]
    (try
      (let [lay (layout req)
            r (apply oras! req tmp (concat ["pull"] (when lay ["--oci-layout"])
                                           [(str (or lay repository) "@" digest) "-o" (str tmp)]))
            where (str repository "@" digest (when lay (str " (read from the layout ORACLE_LAYOUT names, " lay ")")))
            f (fs/file tmp file-name)]
        (when-not (zero? (:exit r))
          (command/cannot-run! (str "the oracle " where " cannot be read: the store does not hold it ("
                                    (or (first-line (:err r)) (first-line (:out r)) "no reason given") ")")))
        (when-not (fs/regular-file? f)
          (command/cannot-run! (str "the oracle " where " holds no " file-name)))
        (slurp (str f)))
      (finally (fs/delete-tree tmp)))))

(defn read-oracle
  "The oracle.edn text, read: a map, or cannot run."
  [where text]
  (let [o (try (edn/read-string text)
               (catch Exception e (command/cannot-run! (str "the oracle " where "'s " file-name " is not EDN: "
                                                            (ex-message e)))))]
    (when-not (map? o)
      (command/cannot-run! (str "the oracle " where "'s " file-name " is not an EDN map")))
    o))

(defn store!
  "Stores `o` as a new artifact (in ORACLE_LAYOUT when set, else in the
  repository): its digest."
  [req {:keys [repository]} o]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-push-"})]
    (try
      (spit (str (fs/file tmp file-name)) (str (pr-str o) "\n"))
      (let [lay (layout req)
            r (apply oras! req tmp (concat ["push"] (when lay ["--oci-layout"])
                                           [(or lay repository) (str file-name ":" media-type)
                                            "--artifact-type" artifact-type "--format" "json"]))]
        (when-not (zero? (:exit r))
          (command/cannot-run! (str "the new oracle could not be stored in " (or lay repository) ": "
                                    (or (first-line (:err r)) "no reason given"))))
        (or (:digest (try (json/parse-string (:out r) true) (catch Exception _ nil)))
            (second (re-find #"Digest: (\S+)" (str (:out r))))
            (command/cannot-run! "the artifact tool named no digest for the new oracle")))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; Reproducing the goldens

(defn- git! [dir env & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (merge env {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
                                           "GIT_COMMITTER_NAME" "rdm-spec oracle"
                                           "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
                                           "GIT_CONFIG_NOSYSTEM" "1"})}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                 "-c" "init.defaultBranch=main" args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the oracle's fixture project could not be committed: git " (first args) ": "
                                (first-line (:err r)))))
    (str/trim (:out r))))

(defn- cycles-dir [project]
  (let [c (try (edn/read-string (get project command/config-file "{}")) (catch Exception _ nil))]
    (str/replace (str (or (:cycles-dir c) "dhf/cycles")) #"/+$" "")))

(defn materialize!
  "Writes the oracle's project into `dir`, commits it, tags it with every
  cut its cycles name, and writes each cycle's files: {cycle {:verdicts
  rel-or-nil}} and the cycles directory."
  [o dir env]
  (doseq [[path text] (:project o)]
    (let [f (fs/file dir (str path))]
      (fs/create-dirs (fs/parent f))
      (spit (str f) (str text))))
  (git! dir env "init" "-q")
  (git! dir env "add" "-A")
  (git! dir env "commit" "-q" "--no-verify" "--allow-empty" "-m" "the oracle's fixture project")
  (doseq [cut (sort (distinct (keep #(get-in % [:cycle :cut]) (vals (:cycles o)))))]
    (git! dir env "tag" (str cut)))
  (let [cd (cycles-dir (:project o))]
    (into (sorted-map)
          (for [[n {:keys [cycle ratified verdicts] :as c}] (:cycles o)
                :let [at #(str cd "/" n "/" %)
                      write! (fn [rel v] (let [f (fs/file dir rel)]
                                           (fs/create-dirs (fs/parent f))
                                           (spit (str f) (str (pr-str v) "\n"))))]]
            (do (when cycle (write! (at "cycle.edn") cycle))
                (when (contains? c :ratified) (write! (at "ratified.edn") ratified))
                (when (contains? c :verdicts) (write! (at "verdicts.edn") verdicts))
                [n {:dir (str cd "/" n) :verdicts (when (contains? c :verdicts) (at "verdicts.edn"))}])))))

(defn- outcome-text [out]
  (or (:cannot-run out)
      (str/join "; " (map command/format-finding (remove command/warning? (:findings out))))))

(defn reproduce
  "Runs the instrument on the oracle's fixture cycles: {:outputs {[component
  key] value} :findings [instrument-failed ..]}."
  [req o]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})
        env (env-of req)]
    (try
      (let [dir (str (fs/file tmp "project"))
            _ (fs/create-dirs dir)
            cycles (materialize! o dir env)
            run (fn [& words] (command/execute (vec words) {:root dir :env env}))
            failed (fn [component k out]
                     (command/finding file-name nil "instrument-failed"
                                      (str (component-names component) " gave no output for the golden "
                                           (pr-str component) " " (pr-str k) ": " (outcome-text out))))
            per-cycle (for [[n {cd :dir verdicts :verdicts}] cycles]
                        (let [cl (run "checklist" n)
                              cl-out (when (zero? (:exit cl))
                                       {[:checklist n] (json/parse-string (slurp (str (fs/file dir cd "checklist.json"))) true)})
                              sc (when (and verdicts (zero? (:exit cl))) (run "score" n "--verdicts" verdicts))]
                          {:outputs (merge cl-out
                                           (when (and sc (zero? (:exit sc)))
                                             {[:scorer n] (edn/read-string (slurp (str (fs/file dir cd "score.edn"))))}))
                           :findings (concat (when-not (zero? (:exit cl)) [(failed :checklist n cl)])
                                             (when (and sc (not (zero? (:exit sc)))) [(failed :scorer n sc)]))}))
            boards (for [k (sort (keys (:scoreboard o)))
                         :let [[a b] (str/split (str k) #"-" 2)
                               out (run "scoreboard" (str a) (str b))]]
                     (if (zero? (:exit out))
                       {:outputs {[:scoreboard k] (get-in out [:result :scoreboard])}}
                       {:findings [(failed :scoreboard k out)]}))
            all (concat per-cycle boards)]
        {:outputs (apply merge {} (map :outputs all))
         :findings (vec (mapcat :findings all))})
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The stage

(def no-oracle "the configuration names no :oracle: there is no oracle to run")

(defn- where [{:keys [repository digest]}] (str repository "@" digest))

(defn judge
  "Judges the instrument against the configured oracle."
  [req]
  (if-let [cfg (configured req)]
    (let [o (read-oracle (where cfg) (fetch req cfg))
          head {:repository (:repository cfg) :digest (:digest cfg)}]
      (if (empty? (:cycles o))
        {:result {:oracle (assoc head :compared [])}
         :findings [(command/finding file-name nil "oracle-empty"
                                     (str "the oracle " (where cfg) " holds no fixture cycle, so it judged nothing"))]}
        (let [{:keys [outputs findings]} (reproduce req o)
              cmp (compare-goldens o outputs)]
          {:result {:oracle (assoc head :compared (:compared cmp))}
           :findings (into (vec findings) (:findings cmp))})))
    {:result {:oracle {:ran? false :note no-oracle}} :findings []}))

(defn record!
  "Records the goldens the instrument gives now, for the stated reason."
  [req reason]
  (when (str/blank? reason)
    (command/cannot-run! (str "a reason is required: rdm-spec oracle record --reason R"
                              " (a golden changes only for a stated reason); nothing was stored")))
  (let [cfg (or (configured req)
                (command/cannot-run! "the configuration names no :oracle: there is no oracle to record into"))
        o (read-oracle (where cfg) (fetch req cfg))
        _ (when (empty? (:cycles o))
            (command/cannot-run! (str "the oracle " (where cfg) " holds no fixture cycle: there is nothing to record")))
        {:keys [outputs findings]} (reproduce req o)]
    (if (seq findings)
      {:result {} :findings findings}
      (let [date (str (java.time.LocalDate/now))
            o' (recorded o outputs date reason)
            digest (store! req cfg o')
            f (command/path req command/config-file)]
        (spit (str f) (with-digest (slurp (str f)) (:digest cfg) digest))
        {:result {:oracle {:recorded {:repository (:repository cfg) :previous (:digest cfg) :digest digest
                                      :date date :reason reason :changed (:changed (peek (:recorded o')))
                                      :note (str command/config-file " names the new digest; commit it with the reason")}}}
         :findings []}))))

(defn oracle
  "The oracle stage: `rdm-spec oracle [record --reason R]`, and the gate of
  fence and ratify (which judges, whatever their arguments)."
  [req]
  (if (not= "oracle" (:task req))
    (judge req)
    (let [[sub] (:args req)
          reason (get-in req [:opts :reason])]
      (cond
        (and sub (not= "record" sub))
        (command/cannot-run! (str "rdm-spec oracle takes no argument " sub ": rdm-spec oracle [record --reason R]"))
        (= "record" sub) (record! req reason)
        (some? reason)
        (command/cannot-run! "--reason goes with record: rdm-spec oracle record --reason R")
        :else (judge req)))))
