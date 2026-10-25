(ns rdm-spec.measurement.oracle
  "Oracle (dhf/spec/measurement/oracle.feature): `rdm-spec oracle [record
  --reason R]` judges the instrument (the checklist, the scorer and the
  scoreboard) against recorded goldens. It is also the gate stage the kernel
  runs before fence and ratify: with findings it stops the task. The stage
  only judges, reports and, when asked, records: what stops a task is the
  kernel's task table.

  The stage is `oracle`, a function of the request. It reads :root, :task,
  :args (empty, or [\"record\"] on the oracle task), :opts :reason, and
  :config (read from the root when the request carries none, as fence's
  does). Run as the oracle task it returns {:result {:oracle {...}}}; run
  for any other task (a gate) its result is {} and only its findings count.

  The oracle lives in dhf/harness/oracle/:

    project/              a project tree (the spec, the model; a
                          dhf/harness/config.edn of its own, else the
                          project's): committed in a scratch repository and
                          tagged with every cut the fixture cycles name
    cycles/NN/cycle.edn   a fixture cycle, as written by hand
    cycles/NN/ratified.edn  its ratification record
    cycles/NN/verdicts.edn  a grader's verdicts (optional)
    goldens/NN/checklist.json  what `rdm-spec checklist NN` must write
    goldens/NN/score.edn       what `rdm-spec score NN --verdicts ...` must
                               write (for a cycle with verdicts)
    goldens/scoreboard-A-B.edn the :scoreboard `rdm-spec scoreboard A B`
                               must give, for each pair of consecutive
                               fixture cycles that were scored or are
                               bootstrap, and any other pair a golden names
    RECORDED.md           why each golden was re-recorded, dated

  Goldens are compared as data (JSON or EDN), so their layout may differ; a
  difference names the golden, where (an item by its id) and both values.

  `record --reason R` rewrites every golden the instrument no longer
  reproduces (or that is missing), names each, and appends the date, the
  reason and those names to RECORDED.md. Without a reason it cannot run and
  rewrites nothing; when the instrument cannot produce an output it records
  nothing. No dhf/harness/oracle/: nothing to judge, a pass."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def oracle-dir "dhf/harness/oracle")

(defn- rel-in [& parts] (str/join "/" (cons oracle-dir parts)))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- read-edn-safe [f]
  (try {:value (edn/read-string (slurp (str f)))}
       (catch Exception e {:error (ex-message e)})))

(defn fixture-cycles
  "The fixture cycles, sorted: [{:cycle \"01\" :dir file :cycle-edn map
  :verdicts? bool}], and the findings on unreadable ones."
  [root]
  (let [dir (fs/file root oracle-dir "cycles")
        names (if (fs/directory? dir)
                (sort (for [d (fs/list-dir dir) :when (fs/directory? d)] (fs/file-name d)))
                [])]
    (reduce (fn [acc n]
              (let [d (fs/file dir n)
                    f (fs/file d "cycle.edn")
                    rel (rel-in "cycles" n "cycle.edn")
                    {:keys [value error]} (when (fs/regular-file? f) (read-edn-safe f))]
                (cond
                  (not (fs/regular-file? f))
                  (update acc :findings conj (command/finding rel nil "oracle-fixture" "the fixture cycle has no cycle.edn"))
                  (or error (not (map? value)))
                  (update acc :findings conj (command/finding rel nil "oracle-fixture"
                                                              (str "not an EDN map" (when error (str ": " error)))))
                  :else
                  (update acc :cycles conj {:cycle n :dir d :cycle-edn value
                                            :verdicts? (fs/regular-file? (fs/file d "verdicts.edn"))}))))
            {:cycles [] :findings []}
            names)))

(defn golden-names
  "Every golden the fixture cycles call for: [{:kind :checklist|:score|:scoreboard
  :cycles [..] :rel path}]."
  [root cycles]
  (let [comparable (filter #(or (:verdicts? %) (true? (get-in % [:cycle-edn :bootstrap]))) cycles)
        pairs (map (fn [[a b]] [(:cycle a) (:cycle b)]) (partition 2 1 comparable))
        known (set (map :cycle cycles))
        named (for [f (fs/glob (fs/file root oracle-dir "goldens") "scoreboard-*.edn")
                    :let [[_ a b] (re-matches #"scoreboard-([^-]+)-([^-]+)\.edn" (fs/file-name f))]
                    :when (and a (known a) (known b))]
                [a b])]
    (vec (concat
          (for [{c :cycle v? :verdicts?} cycles
                g (cons {:kind :checklist :cycles [c] :rel (rel-in "goldens" c "checklist.json")}
                        (when v? [{:kind :score :cycles [c] :rel (rel-in "goldens" c "score.edn")}]))]
            g)
          (for [[a b] (distinct (concat pairs (sort named)))]
            {:kind :scoreboard :cycles [a b] :rel (rel-in "goldens" (str "scoreboard-" a "-" b ".edn"))})))))

;; ---------------------------------------------------------------------------
;; Running the instrument in a scratch project

(def ^:private identity-env
  {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env identity-env} "git" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim (:err r))) {})))
    r))

(defn- run-env []
  (dissoc (into {} (System/getenv)) "RESULTS_DIR"))

(defn- scratch!
  "A scratch project: the project's configuration, the fixture project tree
  over it, committed and tagged with every cut the cycles name, then the
  fixture cycles copied under its cycles directory."
  [root config cycles]
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-"}))
        cfg (fs/file dir command/config-file)
        tree (fs/file root oracle-dir "project")]
    (fs/create-dirs (fs/parent cfg))
    (spit (str cfg) (pr-str (or config {})))
    (when (fs/directory? tree)
      (fs/copy-tree tree dir {:replace-existing true}))
    (git! dir "init" "-q")
    (git! dir "add" "-A" ".")
    (git! dir "commit" "-q" "--allow-empty" "-m" "the oracle's fixture project")
    (doseq [cut (distinct (keep #(get-in % [:cycle-edn :cut]) cycles)) :when (string? cut)]
      (git! dir "tag" "-f" cut))
    (let [sconfig (or (command/read-config dir) {})
          cycles-dir (str/replace (str (or (:cycles-dir sconfig) "dhf/cycles")) #"/+$" "")]
      (doseq [{c :cycle d :dir} cycles]
        (fs/copy-tree d (fs/file dir cycles-dir c) {:replace-existing true}))
      {:dir dir :cycles-dir cycles-dir})))

(defn- first-lines [s] (str/join " | " (take 3 (remove str/blank? (str/split-lines (str s))))))

(defn- instrument! [dir argv]
  (let [r (command/run-task argv dir (run-env))]
    (assoc r :line (str "rdm-spec " (str/join " " argv)))))

(defn- failed [r]
  {:error (str (:line r) " exited " (:exit r) ": " (first-lines (str/join "\n" (:err r))))})

(defn produce
  "What the instrument produces for each golden: {rel {:text :value} or
  {:error why}}."
  [root config cycles goldens]
  (let [{:keys [dir cycles-dir]} (scratch! root config cycles)]
    (try
      (let [out (atom {})
            put! (fn [rel v] (swap! out assoc rel v))
            by-cycle (group-by :cycles goldens)]
        (doseq [{c :cycle v? :verdicts?} cycles]
          (let [cl-rel (:rel (first (filter #(= :checklist (:kind %)) (get by-cycle [c]))))
                r (instrument! dir ["checklist" c])
                f (fs/file dir cycles-dir c "checklist.json")]
            (put! cl-rel (if (and (zero? (:exit r)) (fs/regular-file? f))
                           (let [t (slurp (str f))] {:text t :value (json/parse-string t)})
                           (failed r)))
            (when v?
              (let [sc-rel (:rel (first (filter #(= :score (:kind %)) (get by-cycle [c]))))
                    r (if (zero? (:exit r))
                        (instrument! dir ["score" c "--verdicts" (str cycles-dir "/" c "/verdicts.edn")])
                        r)
                    f (fs/file dir cycles-dir c "score.edn")]
                (put! sc-rel (if (and (zero? (:exit r)) (fs/regular-file? f))
                               (let [t (slurp (str f))] {:text t :value (edn/read-string t)})
                               (failed r)))))))
        (doseq [{:keys [cycles rel]} (filter #(= :scoreboard (:kind %)) goldens)]
          (let [r (instrument! dir (into ["scoreboard"] cycles))]
            (put! rel (if (zero? (:exit r))
                        (let [v (:scoreboard (edn/read-string (:out r)))]
                          {:value v :text (with-out-str (pprint/pprint v))})
                        (failed r)))))
        @out)
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; Comparing

(defn- id-of [x] (when (map? x) (or (:id x) (get x "id"))))

(defn- by-id? [v] (and (sequential? v) (seq v) (every? #(string? (id-of %)) v)
                       (apply distinct? (map id-of v))))

(defn differences
  "The differences between `golden` and `actual`: [{:path [..] :golden :actual}].
  Vectors of maps with ids are aligned by id; a path element {:item id}
  names one."
  ([golden actual] (differences [] golden actual))
  ([path g a]
   (cond
     (= g a) []
     (and (map? g) (map? a))
     (vec (mapcat (fn [k] (differences (conj path k) (get g k ::absent) (get a k ::absent)))
                  (sort-by pr-str (distinct (concat (keys g) (keys a))))))
     (and (sequential? g) (sequential? a) (or (by-id? g) (by-id? a)) (every? id-of (concat g a)))
     (let [gm (into {} (map (juxt id-of identity) g))
           am (into {} (map (juxt id-of identity) a))
           ids (distinct (concat (map id-of g) (map id-of a)))]
       (vec (mapcat (fn [id] (differences (conj path {:item id}) (get gm id ::absent) (get am id ::absent))) ids)))
     (and (sequential? g) (sequential? a) (= (count g) (count a)))
     (vec (mapcat (fn [i] (differences (conj path i) (nth g i) (nth a i))) (range (count g))))
     :else [{:path path :golden g :actual a}])))

(defn- show [v] (if (= ::absent v) "nothing" (pr-str v)))

(defn- path-text [path]
  (str/join " " (for [p path] (if (map? p) (str "item " (:item p)) (pr-str p)))))

(defn- line-in [text path]
  (when-let [id (some #(when (map? %) (:item %)) (reverse path))]
    (some (fn [[i l]] (when (str/includes? l (pr-str id)) (inc i)))
          (map-indexed vector (str/split-lines (str text))))))

(defn- read-golden [root {:keys [rel kind]}]
  (let [f (fs/file root rel)]
    (when (fs/regular-file? f)
      (let [t (slurp (str f))]
        (try {:text t :value (if (= :checklist kind) (json/parse-string t) (edn/read-string t))}
             (catch Exception e {:text t :error (ex-message e)}))))))

(defn judge
  "Findings for every golden the instrument does not reproduce."
  [root goldens produced]
  (vec (for [{:keys [rel] :as g} goldens
             :let [golden (read-golden root g)
                   actual (get produced rel)]
             f (cond
                 (nil? golden)
                 [(command/finding rel nil "golden-missing"
                                   "no golden is recorded; record one with rdm-spec oracle record --reason R")]
                 (:error golden)
                 [(command/finding rel nil "golden-unreadable" (:error golden))]
                 (:error actual)
                 [(command/finding rel nil "golden-not-reproduced" (str "the instrument produced nothing: " (:error actual)))]
                 :else
                 (for [{:keys [path] gv :golden av :actual} (differences (:value golden) (:value actual))]
                   (command/finding rel (line-in (:text golden) path) "golden-differs"
                                    (str (if (seq path) (path-text path) "the whole output")
                                         ": the golden says " (show gv) ", the instrument gives " (show av)))))]
         f)))

;; ---------------------------------------------------------------------------
;; Recording

(defn- today [] (str (java.time.LocalDate/now)))

(defn- append-record! [root reason rels]
  (let [f (fs/file root oracle-dir "RECORDED.md")
        head (when-not (fs/exists? f)
               "# Recorded goldens\n\nEvery re-recording of a golden, with the date and the stated reason.\n")
        entry (str "\n## " (today) "\n\n" (str/trim reason) "\n\nRe-recorded:\n"
                   (str/join (for [r rels] (str "- " r "\n"))))]
    (spit (str f) (str head entry) :append true)))

(defn record!
  "Rewrites every golden the instrument does not reproduce; returns the
  rewritten paths."
  [root goldens produced reason]
  (let [changed (vec (for [{:keys [rel] :as g} goldens
                           :let [golden (read-golden root g)
                                 actual (get produced rel)]
                           :when (or (nil? golden) (:error golden) (not= (:value golden) (:value actual)))]
                       rel))]
    (doseq [rel changed]
      (let [f (fs/file root rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (:text (get produced rel)))))
    (when (seq changed) (append-record! root reason changed))
    changed))

;; ---------------------------------------------------------------------------
;; The stage

(defn oracle
  "The oracle stage: judges (or, on `oracle record`, records) the instrument
  against the goldens in dhf/harness/oracle/."
  [req]
  (let [root (:root req)
        own? (= "oracle" (:task req))
        [mode & more] (when own? (:args req))
        record? (= "record" mode)
        reason (get-in req [:opts :reason])
        _ (when (and own? (or (seq more) (and mode (not record?))))
            (command/cannot-run! (str "unknown argument " (str/join " " (cons mode more)) "; usage: rdm-spec oracle [record --reason R]")))
        _ (when (and record? (or (not (string? reason)) (str/blank? reason)))
            (command/cannot-run! "a reason is required to record goldens: rdm-spec oracle record --reason R; nothing was rewritten"))
        _ (when (and own? (not record?) reason)
            (command/cannot-run! "--reason is given only with record: rdm-spec oracle record --reason R"))
        wrap (fn [m findings] {:result (if own? {:oracle m} {}) :findings (vec findings)})]
    (if-not (fs/directory? (fs/file root oracle-dir))
      (if record?
        (command/cannot-run! (str "there is no oracle to record: no " oracle-dir "/"))
        (wrap {:compared [] :statement (str "there is no oracle to run: no " oracle-dir "/")} []))
      (let [config (or (:config req) (command/read-config root))
            {:keys [cycles findings]} (fixture-cycles root)
            goldens (golden-names root cycles)]
        (if (seq findings)
          (wrap {:compared []} findings)
          (let [produced (produce root config cycles goldens)
                errors (vec (for [{:keys [rel]} goldens
                                  :let [e (:error (get produced rel))]
                                  :when e]
                              (command/finding rel nil "golden-not-reproduced" (str "the instrument produced nothing: " e))))]
            (if record?
              (if (seq errors)
                (wrap {:recorded []} errors)
                (let [changed (record! root goldens produced reason)]
                  (wrap {:recorded changed
                         :unchanged (vec (remove (set changed) (map :rel goldens)))
                         :reason reason
                         :statement (if (seq changed)
                                      (str "re-recorded " (count changed) " golden(s); the reason is appended to "
                                           (rel-in "RECORDED.md"))
                                      "every golden is reproduced: nothing was re-recorded")}
                        [])))
              (wrap {:compared (mapv :rel goldens)} (judge root goldens produced)))))))))
