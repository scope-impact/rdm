(ns rdm-spec.measurement.oracle
  "Oracle (dhf/spec/measurement/oracle.feature): the instrument's own judge.
  `rdm-spec oracle` runs the checklist, the scorer and the scoreboard on the
  recorded fixture cycles under dhf/harness/oracle/ and compares what they
  produce with the recorded goldens; `rdm-spec oracle record --reason R`
  re-records the goldens the instrument no longer reproduces and appends the
  reason, with the date, to dhf/harness/oracle/RECORDED.md. It is also the
  first stage, a gate, of fence and ratify: nothing is fenced or ratified
  while it has findings.

  The oracle directory (the configured :oracle-dir, else dhf/harness/oracle)
  holds one directory per fixture cycle, its name the cycle's name:

    <cycle>/project/        the fixture project's files at the cut (its
                            dhf/harness/config.edn, its spec, ...)
    <cycle>/cycle.edn       the cycle as declared; its :cut is the tag the
                            oracle puts on project/
    <cycle>/ratified.edn    optional; else a passed ratification of the cut
                            on the empty tree
    <cycle>/verdicts.edn    the grader's verdicts
    <cycle>/checklist.json  golden: what `checklist CYCLE` writes
    <cycle>/score.edn       golden: what `score CYCLE --verdicts` writes
    <cycle>/scoreboard.edn  golden: the :scoreboard `scoreboard P CYCLE`
                            gives, P the fixture cycle before it by name (the
                            first's P is the last; with one cycle, itself)

  Each fixture cycle runs in a throwaway git repository of its own, through
  the command itself (rdm-spec.kernel.command/execute), so what is judged is
  the instrument as installed. Goldens are compared as data (JSON or EDN):
  items of a sequence that carry an id are matched by id. Each difference is
  a finding `GOLDEN:LINE: golden-differs: item ID KEY: golden G, produced P`;
  a golden that is missing is golden-missing; a fixture that cannot be run
  is fixture-malformed or fixture-run-failed. An oracle directory with no
  fixture cycle is no oracle.

  The result: {:oracle {:fixtures [cycle] :compared [golden] :differs n}},
  with record also :recorded [golden]. No oracle directory: exit 0, saying
  there is no oracle to run. `oracle record` with no reason cannot run and
  rewrites nothing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def default-dir "dhf/harness/oracle")

(def recorded-file "RECORDED.md")

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def goldens
  "The goldens of a fixture cycle, in the order they are compared."
  ["checklist.json" "score.edn" "scoreboard.edn"])

(defn oracle-dir [req]
  (str/replace (str (or (get-in req [:config :oracle-dir]) default-dir)) #"/+$" ""))

;; ---------------------------------------------------------------------------
;; Comparing

(defn- id-of [x]
  (when (map? x) (or (get x :id) (get x "id"))))

(defn- id-seq? [xs]
  (and (sequential? xs) (seq xs) (every? id-of xs)
       (apply distinct? (map id-of xs))))

(defn diff
  "Pure: every difference between the golden `g` and the produced `p`:
  [{:path [segment] :golden :produced}], a segment a map key, an index, or
  {:item id} for a sequence item matched by its id. A value absent on one
  side is ::absent."
  ([g p] (diff [] g p))
  ([path g p]
   (cond
     (= g p) []

     (and (map? g) (map? p))
     (vec (mapcat (fn [k] (diff (conj path k) (get g k ::absent) (get p k ::absent)))
                  (distinct (concat (keys g) (keys p)))))

     (and (sequential? g) (sequential? p) (or (id-seq? g) (empty? g)) (or (id-seq? p) (empty? p))
          (or (seq g) (seq p)))
     (let [gm (into {} (map (juxt id-of identity) g))
           pm (into {} (map (juxt id-of identity) p))
           ids (distinct (concat (map id-of g) (map id-of p)))
           moved (when (and (= (set (keys gm)) (set (keys pm))) (not= (map id-of g) (map id-of p)))
                   [{:path (conj path :order) :golden (mapv id-of g) :produced (mapv id-of p)}])]
       (vec (concat (mapcat (fn [id] (diff (conj path {:item id}) (get gm id ::absent) (get pm id ::absent))) ids)
                    moved)))

     (and (sequential? g) (sequential? p) (= (count g) (count p)))
     (vec (mapcat (fn [i] (diff (conj path i) (nth g i) (nth p i))) (range (count g))))

     :else [{:path path :golden g :produced p}])))

(defn- show [v] (if (= ::absent v) "nothing" (pr-str v)))

(defn path-text
  "Pure: a difference's path as a finding names it."
  [path]
  (if (empty? path)
    "the whole file"
    (str/join " " (for [s path]
                    (cond (map? s) (str "item " (:item s))
                          (= :order s) "the order of the items"
                          (integer? s) (str "[" s "]")
                          :else (pr-str s))))))

(defn- line-of [text needle]
  (when (and text needle)
    (some (fn [[i l]] (when (str/includes? l needle) (inc i)))
          (map-indexed vector (str/split-lines text)))))

(defn difference-findings
  "Pure: a finding per difference, at the golden `file` holding `text`."
  [file text diffs]
  (vec (for [{:keys [path golden produced]} diffs
             :let [item (some #(when (map? %) (:item %)) path)
                   line (when item (line-of text (pr-str (str item))))]]
         (command/finding file line "golden-differs"
                          (str (path-text path) ": golden " (show golden) ", produced " (show produced))))))

;; ---------------------------------------------------------------------------
;; Running a fixture cycle

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
                                      "GIT_CONFIG_NOSYSTEM" "1"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed: " (str/trim (str (:err r))))))
    (str/trim (:out r))))

(defn- read-edn-text [text]
  (try (edn/read-string text) (catch Exception _ ::unreadable)))

(defn- read-json-text [text]
  (try (json/parse-string text) (catch Exception _ ::unreadable)))

(defn- run-lines [outcome]
  (let [{:keys [err]} (command/render outcome false)]
    (str/join "; " err)))

(defn- execute [argv dir]
  (let [execute (requiring-resolve 'rdm-spec.kernel.command/execute)]
    (execute argv {:root (str dir) :env (dissoc (into {} (System/getenv)) "RESULTS_DIR")})))

(defn- fixture-cycles
  "The fixture cycles: [name] of every directory under the oracle directory."
  [dir]
  (vec (sort (for [d (fs/list-dir dir) :when (fs/directory? d)] (str (fs/file-name d))))))

(defn- cycles-dir-of [project]
  (let [f (fs/file project command/config-file)
        c (when (fs/regular-file? f) (read-edn-text (slurp (str f))))]
    (str/replace (str (or (when (map? c) (:cycles-dir c)) "dhf/cycles")) #"/+$" "")))

(defn- scrub
  "`text` with the throwaway directory `tmp` named as `as`."
  [text tmp as]
  (-> (str text)
      (str/replace (str (fs/canonicalize tmp)) as)
      (str/replace (str tmp) as)))

(defn run-fixture
  "Runs the checklist and the scorer on the fixture cycle `name` (its files
  under `fixture`, a directory): {:name :produced {golden text} :findings}.
  Leaves nothing behind."
  [rel-fixture fixture name]
  (let [project (fs/file fixture "project")
        cycle-f (fs/file fixture "cycle.edn")
        bad (fn [msg] {:name name :produced {}
                       :findings [(command/finding rel-fixture nil "fixture-malformed" msg)]})]
    (cond
      (not (fs/directory? project)) (bad "the fixture cycle holds no project/: the fixture project at its cut")
      (not (fs/regular-file? cycle-f)) (bad "the fixture cycle holds no cycle.edn")
      (not (fs/regular-file? (fs/file fixture "verdicts.edn"))) (bad "the fixture cycle holds no verdicts.edn")
      :else
      (let [cycle (read-edn-text (slurp (str cycle-f)))]
        (if-not (and (map? cycle) (string? (:cut cycle)))
          (bad "its cycle.edn is not a map naming a :cut")
          (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
            (try
              (let [dir (fs/file tmp "project")
                    as (str rel-fixture "/project")
                    _ (fs/copy-tree project dir)
                    cycles (cycles-dir-of dir)
                    cyc (fn [f] (fs/file dir cycles name f))]
                (git! dir "init" "-q")
                (git! dir "add" "-A" "-f" ".")
                (git! dir "commit" "-q" "--allow-empty" "-m" (str "the fixture cut " (:cut cycle)))
                (git! dir "tag" "-f" (:cut cycle))
                (fs/create-dirs (cyc ""))
                (fs/copy cycle-f (cyc "cycle.edn") {:replace-existing true})
                (if (fs/regular-file? (fs/file fixture "ratified.edn"))
                  (fs/copy (fs/file fixture "ratified.edn") (cyc "ratified.edn") {:replace-existing true})
                  (spit (str (cyc "ratified.edn"))
                        (pr-str {:cut (:cut cycle) :room-tree empty-tree :outcome :passed})))
                (fs/copy (fs/file fixture "verdicts.edn") (fs/file dir "verdicts.edn") {:replace-existing true})
                (let [failed (fn [task outcome]
                               (command/finding rel-fixture nil "fixture-run-failed"
                                                (scrub (str "rdm-spec " task " exited " (:exit outcome) ": "
                                                            (run-lines outcome))
                                                       dir as)))
                      cl (execute ["checklist" name] dir)
                      cl-text (when (zero? (:exit cl)) (slurp (str (cyc "checklist.json"))))
                      sc (when cl-text (execute ["score" name "--verdicts" "verdicts.edn"] dir))
                      sc-text (when (and sc (zero? (:exit sc))) (slurp (str (cyc "score.edn"))))]
                  {:name name
                   :cycle-text (slurp (str cycle-f))
                   :config-text (let [f (fs/file project command/config-file)] (when (fs/regular-file? f) (slurp (str f))))
                   :produced (cond-> {}
                               cl-text (assoc "checklist.json" cl-text)
                               sc-text (assoc "score.edn" sc-text))
                   :findings (vec (concat (when-not (zero? (:exit cl)) [(failed (str "checklist " name) cl)])
                                          (when (and sc (not (zero? (:exit sc)))) [(failed (str "score " name) sc)])))}))
              (finally (fs/delete-tree tmp)))))))))

(defn- scoreboard-text [m]
  (with-out-str (binding [*print-namespace-maps* false] (pprint/pprint m))))

(defn run-scoreboards
  "Runs the scoreboard of each fixture cycle against the one before it, in
  one throwaway project holding every cycle's cycle.edn and produced score:
  {name text}, plus :findings."
  [rel-dir runs]
  (let [scored (filter #(get-in % [:produced "score.edn"]) runs)
        names (mapv :name runs)]
    (if (or (empty? scored) (nil? (:config-text (first scored))))
      {:texts {} :findings []}
      (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
        (try
          (let [dir (fs/file tmp "scoreboard")
                _ (fs/create-dirs (fs/file dir "dhf/harness"))
                _ (spit (str (fs/file dir command/config-file)) (:config-text (first scored)))
                cycles (cycles-dir-of dir)
                by-name (into {} (map (juxt :name identity) runs))]
            (doseq [r scored]
              (fs/create-dirs (fs/file dir cycles (:name r)))
              (spit (str (fs/file dir cycles (:name r) "cycle.edn")) (:cycle-text r))
              (spit (str (fs/file dir cycles (:name r) "score.edn")) (get-in r [:produced "score.edn"])))
            (reduce (fn [acc [i n]]
                      (let [prev (nth names (mod (dec i) (count names)))]
                        (if (and (get-in by-name [n :produced "score.edn"]) (get-in by-name [prev :produced "score.edn"]))
                          (let [o (execute ["scoreboard" prev n] dir)]
                            (if (zero? (:exit o))
                              (assoc-in acc [:texts n] (scoreboard-text (get-in o [:result :scoreboard])))
                              (update acc :findings conj
                                      (command/finding (str rel-dir "/" n) nil "fixture-run-failed"
                                                       (scrub (str "rdm-spec scoreboard " prev " " n " exited " (:exit o)
                                                                   ": " (run-lines o))
                                                              dir rel-dir)))))
                          acc)))
                    {:texts {} :findings []}
                    (map-indexed vector names)))
          (finally (fs/delete-tree tmp)))))))

;; ---------------------------------------------------------------------------
;; Judging

(defn- read-golden [file text]
  (if (str/ends-with? file ".json") (read-json-text text) (read-edn-text text)))

(defn judge-golden
  "Pure: the findings of one golden: `rel` its project path, `golden-text`
  nil when it does not exist, `produced-text` nil when nothing was produced."
  [rel golden-text produced-text]
  (cond
    (nil? produced-text) []
    (nil? golden-text) [(command/finding rel nil "golden-missing"
                                         "no golden is recorded: record it with rdm-spec oracle record --reason R")]
    :else (let [g (read-golden rel golden-text)
                p (read-golden rel produced-text)]
            (if (= ::unreadable g)
              [(command/finding rel nil "golden-unreadable" "the golden is not readable")]
              (difference-findings rel golden-text (diff g p))))))

(defn- judge
  "Runs every fixture cycle and compares: {:fixtures :compared :findings
  :stale [{:rel :file :text}]}."
  [req dir]
  (let [rel-dir (oracle-dir req)
        names (fixture-cycles dir)
        runs (mapv #(run-fixture (str rel-dir "/" %) (fs/file dir %) %) names)
        boards (run-scoreboards rel-dir runs)
        runs (mapv #(if-let [t (get-in boards [:texts (:name %)])] (assoc-in % [:produced "scoreboard.edn"] t) %) runs)
        per (for [r runs
                  g goldens
                  :let [rel (str rel-dir "/" (:name r) "/" g)
                        f (fs/file dir (:name r) g)
                        golden-text (when (fs/regular-file? f) (slurp (str f)))
                        produced (get-in r [:produced g])]
                  :when produced]
              {:rel rel :file f :text produced :findings (judge-golden rel golden-text produced)})]
    {:fixtures names
     :compared (vec (for [x per :when (fs/regular-file? (:file x))] (:rel x)))
     :findings (vec (concat (mapcat :findings runs) (:findings boards) (mapcat :findings per)))
     :stale (vec (filter #(seq (:findings %)) per))}))

(defn- record! [req dir reason stale]
  (doseq [{:keys [file text]} stale]
    (spit (str file) text))
  (when (seq stale)
    (let [f (fs/file dir recorded-file)
          head (if (fs/regular-file? f) "" "# Recorded goldens\n\nEach re-recording of a golden, with its date and reason.\n\n")]
      (spit (str f)
            (str head "- " (str (java.time.LocalDate/now)) ": " (str/trim reason)
                 " (re-recorded " (str/join ", " (map :rel stale)) ")\n")
            :append true)))
  (vec (map :rel stale)))

(defn oracle
  "The oracle stage: the oracle task, and the gate of fence and ratify."
  [req]
  (let [task? (= "oracle" (:task req))
        [sub & more] (when task? (:args req))
        reason (get-in req [:opts :reason])
        record? (= "record" sub)
        _ (when task?
            (cond
              (and sub (not record?)) (command/cannot-run! (str "unknown argument " sub "; usage: rdm-spec oracle [record --reason R]"))
              (seq more) (command/cannot-run! (str "unexpected argument " (str/join " " more)))
              (and record? (or (not (string? reason)) (str/blank? reason)))
              (command/cannot-run! "a reason is required to record goldens: rdm-spec oracle record --reason R; nothing was rewritten")
              (and (not record?) (some? reason))
              (command/cannot-run! "--reason is given only with record: rdm-spec oracle record --reason R")))
        rel-dir (oracle-dir req)
        dir (command/path req rel-dir)]
    (cond
      (not (fs/directory? dir))
      (cond
        record? (command/cannot-run! (str "there is no oracle to record: " rel-dir "/ does not exist"))
        task? {:result {:oracle {:fixtures [] :compared [] :message (str "there is no oracle to run: " rel-dir "/ does not exist")}}
               :findings [(command/warning rel-dir nil "no-oracle" "there is no oracle to run")]}
        :else {:result {} :findings []})

      ;; an oracle with no fixture cycle is no oracle, as a project without one is not refused
      (empty? (fixture-cycles dir))
      (cond
        record? (command/cannot-run! (str "there is no oracle to record: " rel-dir "/ holds no fixture cycle"))
        task? {:result {:oracle {:fixtures [] :compared [] :message (str "there is no oracle to run: " rel-dir "/ holds no fixture cycle")}}
               :findings [(command/warning rel-dir nil "no-oracle" "there is no oracle to run: it holds no fixture cycle")]}
        :else {:result {} :findings []})

      :else
      (let [{:keys [fixtures compared findings stale]} (judge req dir)]
        (if record?
          (let [recorded (record! req dir reason stale)
                rest-findings (vec (remove #(#{"golden-differs" "golden-missing" "golden-unreadable"} (:rule %)) findings))]
            {:result {:oracle {:fixtures fixtures :compared compared :recorded recorded
                               :reason (str/trim reason)}}
             :findings rest-findings})
          {:result (if task? {:oracle {:fixtures fixtures :compared compared
                                       :differs (count (remove command/warning? findings))}} {})
           :findings findings})))))
