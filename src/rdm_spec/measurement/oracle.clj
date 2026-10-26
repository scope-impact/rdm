(ns rdm-spec.measurement.oracle
  "The Oracle (dhf/spec/measurement/oracle.feature; glossary, \"Oracle
  (instrument)\"): `rdm-spec oracle [record --reason R [--changes
  COMPONENT:CASE,...]]` runs every judging component on each case the
  artifact holds and compares its answer with the case's golden; it is also
  the gate stage of fence and verify (`gate`).

  The artifact: the configuration's :oracle {:repository R :digest D}, read
  by its digest only, from the OCI layout ORACLE_LAYOUT names when set,
  else from the registry repository R (`oras pull`); one file, oracle.edn,
  flat:

    {:project {path text}                          the fixture project
     :cycles  {\"NN\" {:cycle :ratified :verdicts}}  fixture cycles
     :results {NAME {\"<uuid>-result.json\" text}}  fixture result sets
     :cuts    {NAME {\"spec-N\" {path text}}}        fixture pairs of cuts
     COMPONENT {CASE golden-or-nil}                  per judging component
     :recorded [{:date :reason :changed [{:component :case}] :added [..]}]}

  A component's cases are the keys of its map; nil is a new case with no
  golden yet. Each case runs on its fixture, in this process, through the
  command's own task (rdm-spec.kernel.command/execute) in a fixture directory
  (the configuration's :oracle removed):

    :measurement/checklist  CYCLE  checklist CYCLE (the project, committed and
                                   tagged with the cycle's cut)
    :measurement/scorer     CYCLE  checklist, then score CYCLE --verdicts
    :measurement/scoreboard A-B    both cycles scored, then scoreboard A B
    :measurement/oracle     self   the oracle on the artifact's fixtures and its
                                   first two goldens of other components, its
                                   :repository and :digest \"self\"
    :verification/verify    NAME   verify ROOM 00, the project tagged spec-1, the
                                   room's tests writing the result set NAME
    :verification/status    NAME   status, the result set in the results directory
    :regeneration/scopes    NAME   scopes A B over the pair of cuts NAME
    :regeneration/fence     NAME   fence room B, scoped to the components A's
                                   model names and B's no longer does
    :spec/check             NAME   check, in the files of NAME's last cut

  A golden is the whole answer: {:out (standard output, read; nil when it
  cannot run) :exit :err (the error stream, word for word)}, and :wrote
  {path data} for the record the checklist (checklist.json) and the scorer
  (score.edn) write. Timing (:seconds) is :measured, never a value.

  Findings: an answer that differs from its golden (oracle-differs, each
  difference with both values), a case with no golden (oracle-no-golden),
  a case whose fixture is missing (oracle-no-fixture), no fixture cycle
  (oracle-empty: it judged nothing). Cannot run: the artifact cannot be
  read (naming R and D).

  `record --reason R`: runs every case; a case with no golden gains its
  answer freely; an existing golden that differs is changed only when
  --changes names it, unless the record adds no case (then every change is
  named in :recorded), else nothing is recorded (oracle-change-unnamed).
  It pushes a new artifact (to the layout, else to R), appends to
  :recorded, and rewrites the configuration's :oracle :digest for a person
  to commit; the old artifact is never touched.

  Result {:oracle {:repository R :digest D :compared [{:component :case}]
  :differences n}}, the goldens in the order of `judging`, then by case
  (record: {:oracle {:repository :digest :previous :recorded {..}}}; no
  :oracle: {:oracle {:none why}})."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.command :as command]))

(def judging
  "The judging components, in the order goldens are judged and listed."
  [:measurement/checklist :measurement/scorer :measurement/scoreboard :measurement/oracle
   :verification/verify :verification/status :regeneration/scopes :regeneration/fence :spec/check])

(def fixture-keys [:project :cycles :results :cuts])

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn- pr [x] (binding [*print-namespace-maps* false] (pr-str x)))

;; ---------------------------------------------------------------------------
;; Pure parts

(defn normalize
  "`x` read back as EDN, every :seconds :measured."
  [x]
  (walk/postwalk (fn [v] (if (and (map? v) (contains? v :seconds)) (assoc v :seconds :measured) v))
                 (edn/read-string (pr x))))

(defn cases
  "[[component case]] of the oracle `data`, in judging order, then by case."
  [data]
  (vec (for [c judging :when (map? (get data c)) k (sort (keys (get data c)))] [c k])))

(defn- diff-paths [g a path]
  (cond
    (= g a) []
    (and (map? g) (map? a))
    (vec (mapcat #(diff-paths (get g %) (get a %) (conj path %)) (distinct (concat (keys g) (keys a)))))
    (and (sequential? g) (sequential? a) (= (count g) (count a)))
    (vec (mapcat #(diff-paths (nth g %) (nth a %) (conj path %)) (range (count g))))
    :else [[path g a]]))

(defn- item-of [golden path]
  (some (fn [n]
          (let [x (get-in golden (take n path))]
            (when (and (map? x) (:id x) (integer? (nth path (dec n) nil))) (:id x))))
        (range (count path) 0 -1)))

(defn differences
  "How the answer `actual` differs from `golden`: [{:what :path :golden
  :actual :item}]."
  [golden actual]
  (vec (concat
        (for [[path g a] (diff-paths (normalize (:out golden)) (normalize (:out actual)) [])]
          {:what "standard output" :path path :golden g :actual a :item (item-of (:out golden) path)})
        (when (not= (:exit golden) (:exit actual))
          [{:what "exit" :golden (:exit golden) :actual (:exit actual)}])
        (when (not= (str (:err golden)) (str (:err actual)))
          (let [gl (str/split-lines (str (:err golden))) al (str/split-lines (str (:err actual)))
                n (or (first (filter #(not= (nth gl % nil) (nth al % nil)) (range (max (count gl) (count al))))) 0)]
            ;; the lines are not quoted: a relayed warning would read as one
            [{:what "error stream" :line (inc n)
              :golden (str (count gl) " lines") :actual (str (count al) " lines, the first that differs line " (inc n))}]))
        (for [[path g a] (diff-paths (normalize (:wrote golden)) (normalize (:wrote actual)) [])]
          {:what (str "record " (first path)) :path (vec (rest path)) :golden g :actual a
           :item (item-of (get (:wrote golden) (first path)) (vec (rest path)))}))))

(defn parse-changes
  "#{[component case]} of --changes COMPONENT:CASE,..."
  [s]
  (set (for [part (str/split (str s) #",") :when (not (str/blank? part))
             :let [[c k] (str/split (str/trim part) #":" 2)]]
         [(keyword (str/replace c #"^:" "")) k])))

(defn record-refusals
  "The changed goldens a record may not change: those --changes does not
  name, when the record also adds a case."
  [changed added named]
  (if (seq added) (vec (remove named changed)) []))

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras [dir env & args]
  (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env env} "oras" (map str args))
       (catch java.io.IOException e {:exit 127 :err (ex-message e) :out ""})))

(defn- tmp-env [] {"TMPDIR" (System/getProperty "java.io.tmpdir")})

(defn read-artifact
  "oracle.edn of the artifact `o` ({:repository :digest}), read."
  [req {:keys [repository digest]}]
  (let [layout (not-empty (get (:env req) "ORACLE_LAYOUT"))
        d (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (let [r (if layout
                (oras d (tmp-env) "pull" "--oci-layout" (str layout "@" digest) "-o" "out")
                (oras d (tmp-env) "pull" (str repository "@" digest) "-o" "out"))
            f (fs/file d "out" "oracle.edn")]
        (when-not (and (zero? (:exit r)) (fs/regular-file? f))
          (command/cannot-run! (one-line (str "the oracle " repository "@" digest " cannot be read"
                                              (when layout (str " from the layout " layout)) ": "
                                              (if (zero? (:exit r)) "it holds no oracle.edn" (str (:err r) (:out r)))))))
        (let [v (try (edn/read-string (slurp (str f)))
                     (catch Exception e (command/cannot-run! (one-line (str "the oracle " repository "@" digest
                                                                            " is not readable EDN: " (ex-message e))))))]
          (when-not (map? v) (command/cannot-run! (str "the oracle " repository "@" digest " is not an EDN map")))
          v))
      (finally (fs/delete-tree d)))))

(defn- push! [req data repository]
  (let [layout (not-empty (get (:env req) "ORACLE_LAYOUT"))
        d (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (spit (str (fs/file d "oracle.edn")) (pr data))
      (let [r (oras d (tmp-env) "push" (if layout "--oci-layout" "--disable-path-validation")
                    (or layout repository) "--artifact-type" artifact-type "--format" "json"
                    "oracle.edn:application/edn")
            digest (when (zero? (:exit r)) (try (:digest (json/parse-string (:out r) true)) (catch Exception _ nil)))]
        (or digest
            (command/cannot-run! (one-line (str "the new oracle cannot be stored in " (or layout repository) ": " (:err r) (:out r))))))
      (finally (fs/delete-tree d)))))

;; ---------------------------------------------------------------------------
;; Running a case

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
   "GIT_AUTHOR_DATE" "2026-01-01T00:00:00Z" "GIT_COMMITTER_DATE" "2026-01-01T00:00:00Z"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" "-c" "core.hooksPath=/dev/null" (map str args))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (one-line (str "the oracle's fixture cannot be made: git " (str/join " " args) ": " (:err r)))))
    (:out r)))

(defn- write-files! [dir files]
  (doseq [[rel text] files]
    (let [f (fs/file dir rel)]
      (fs/create-dirs (fs/parent f))
      (spit (str f) (str text)))))

(defn- fixture-config [dir f]
  (let [cf (fs/file dir command/config-file)
        c (if (fs/regular-file? cf) (try (edn/read-string (slurp (str cf))) (catch Exception _ {})) {})]
    (fs/create-dirs (fs/parent cf))
    (spit (str cf) (pr (f (dissoc (if (map? c) c {}) :oracle))))))

(defn- answer
  "The answer of `argv` run in `dir`."
  [env dir argv]
  (let [{:keys [exit result findings cannot-run]} (command/execute (vec argv) {:root (str dir) :env env})]
    {:out (when-not cannot-run (or result {}))
     :exit exit
     :err (str/join "\n" (concat (map command/format-finding findings)
                                 (when cannot-run [(str "cannot-run: " cannot-run)])))}))

(defn- cycle-dir [dir] (str/replace (str (or (:cycles-dir (edn/read-string (slurp (str (fs/file dir command/config-file))))) "dhf/cycles")) #"/+$" ""))

(defn- project! [data dir]
  (write-files! dir (:project data))
  (fixture-config dir identity)
  dir)

(defn- cycle! [data dir c]
  (let [cy (or (get-in data [:cycles c]) (throw (ex-info "no fixture" {::missing (str "no fixture cycle " c)})))
        cd (str (cycle-dir dir) "/" c)]
    (write-files! dir {(str cd "/cycle.edn") (pr (:cycle cy))
                       (str cd "/ratified.edn") (pr (:ratified cy))
                       (str cd "/verdicts.edn") (pr (:verdicts cy))})
    cd))

(defn- committed-project! [data dir]
  (project! data dir)
  (git! dir "init" "-q")
  (git! dir "add" "-A" "-f" "--" ".")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" "the oracle's fixture project")
  (doseq [cut (distinct (keep #(get-in % [:cycle :cut]) (vals (:cycles data))))]
    (git! dir "tag" "-f" cut))
  dir)

(defn- read-json [f] (json/parse-string (slurp (str f))))

(defn- scored! [env data dir c]
  (let [cd (cycle! data dir c)]
    (answer env dir ["checklist" c])
    [cd (answer env dir ["score" c "--verdicts" (str cd "/verdicts.edn")])]))

(defn- cut-pair [data k]
  (or (get-in data [:cuts k]) (throw (ex-info "no fixture" {::missing (str "no fixture pair of cuts " k)}))))

(defn- cut-number [t] (or (some-> (re-find #"\d+" (str t)) parse-long) 0))

(defn- cuts-repo! [data dir k]
  (let [pair (cut-pair data k)
        order (sort-by cut-number (keys pair))]
    (fs/create-dirs dir)
    (git! dir "init" "-q")
    (doseq [t order]
      (doseq [f (fs/list-dir dir) :when (not= ".git" (str (fs/file-name f)))] (fs/delete-tree f))
      (write-files! dir (get pair t))
      (fixture-config dir identity)
      (git! dir "add" "-A" "-f" "--" ".")
      (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "the cut " t))
      (git! dir "tag" t))
    order))

(defn- component-ids [model-text]
  (let [m (try (edn/read-string (str model-text)) (catch Exception _ {}))]
    (set (concat (for [c (:containers m) x (:contexts c) comp (:components x) :when (:id comp)] (:id comp))
                 (keep :id (:kernel m))))))

(declare judge)

(defn- run-case
  "The answer of the component `c` on its case `k` of `data`, in a new
  directory under `tmp`."
  [env data c k tmp]
  (let [dir (str (fs/create-temp-dir {:dir tmp :prefix "case-"}))]
    (case c
      :measurement/checklist
      (do (committed-project! data dir)
          (let [cd (cycle! data dir k)
                a (answer env dir ["checklist" k])
                f (fs/file dir cd "checklist.json")]
            (cond-> a (fs/exists? f) (assoc :wrote {(str cd "/checklist.json") (read-json f)}))))

      :measurement/scorer
      (do (committed-project! data dir)
          (let [[cd a] (scored! env data dir k)
                f (fs/file dir cd "score.edn")]
            (cond-> a (fs/exists? f) (assoc :wrote {(str cd "/score.edn") (edn/read-string (slurp (str f)))}))))

      :measurement/scoreboard
      (let [[a b] (str/split k #"-" 2)]
        (committed-project! data dir)
        (scored! env data dir a)
        (scored! env data dir b)
        (answer env dir ["scoreboard" a b]))

      :measurement/oracle
      (let [others (take 2 (remove (fn [[cc kk]] (or (= cc :measurement/oracle) (nil? (get-in data [cc kk])))) (cases data)))
            sub (reduce (fn [m [cc kk]] (assoc-in m [cc kk] (get-in data [cc kk]))) (select-keys data fixture-keys) others)
            {:keys [compared findings]} (judge env sub (str (fs/create-temp-dir {:dir tmp :prefix "self-"})))]
        {:out {:oracle {:repository "self" :digest "self" :compared compared
                        :differences (count (filter #(= "oracle-differs" (:rule %)) findings))}}
         :exit (if (seq findings) 1 0)
         :err (str/join "\n" (map command/format-finding findings))})

      :verification/verify
      (let [rs (or (get-in data [:results k]) (throw (ex-info "no fixture" {::missing (str "no fixture result set " k)})))
            room (fs/file dir "room")]
        (project! data dir)
        (fixture-config dir #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]}] :implementation ["test.clj" "test"]))
        (git! dir "init" "-q")
        (git! dir "add" "-A" "-f" "--" ".")
        (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-1")
        (git! dir "tag" "spec-1")
        (write-files! dir {(str (cycle-dir dir) "/00/cycle.edn")
                           (pr {:cut "spec-1" :scope :project :builder {:name "builder" :actor :agent}
                                :grader {:name "grader" :actor :agent} :spec-owner {:name "owner" :actor :human}})})
        (write-files! room (into {"test.clj" (str "(require '[babashka.fs :as fs])\n"
                                                  "(let [d (System/getenv \"RESULTS_DIR\")]\n"
                                                  "  (doseq [f (fs/glob \"test/results\" \"*.json\")]\n"
                                                  "    (fs/copy f (fs/file d (fs/file-name f)) {:replace-existing true})))\n")}
                                 (for [[n text] rs] [(str "test/results/" n) text])))
        (answer env dir ["verify" "room" "00"]))

      :verification/status
      (let [rs (or (get-in data [:results k]) (throw (ex-info "no fixture" {::missing (str "no fixture result set " k)})))]
        (project! data dir)
        (let [rd (or (:results-dir (edn/read-string (slurp (str (fs/file dir command/config-file))))) "dhf/allure-results")]
          (write-files! (fs/file dir rd) rs))
        (answer env dir ["status"]))

      :regeneration/scopes
      (let [[a b] (cuts-repo! data dir k)]
        (answer env dir ["scopes" a b]))

      :regeneration/fence
      (let [pair (cut-pair data k)
            [a b] (cuts-repo! data dir k)
            model-rel (fn [files] (or (some-> (get files command/config-file) edn/read-string :model) "dhf/spec/model.edn"))
            gone (sort (map #(str (namespace %) "/" (name %))
                            (remove (component-ids (get (get pair b) (model-rel (get pair b))))
                                    (component-ids (get (get pair a) (model-rel (get pair a)))))))]
        (answer env dir (cond-> ["fence" "room" b] (seq gone) (into ["--component" (str/join "," gone)]))))

      :spec/check
      (let [pair (cut-pair data k)
            last-cut (last (sort-by cut-number (keys pair)))]
        (write-files! dir (get pair last-cut))
        (fixture-config dir identity)
        (answer env dir ["check"])))))

(defn judge
  "Runs every case of `data` and compares each answer with its golden:
  {:compared [{:component :case}] :findings :answers {[c k] answer}}."
  [env data tmp]
  (if (empty? (:cycles data))
    {:compared [] :answers {}
     :findings [(command/finding "oracle-empty" "the oracle holds no fixture cycle, so it judged nothing")]}
    (reduce
     (fn [acc [c k]]
       (let [golden (get-in data [c k])
             a (try (run-case env data c k tmp)
                    (catch clojure.lang.ExceptionInfo e
                      (if-let [why (::missing (ex-data e))] {::missing why} (throw e))))
             at (str c " " (pr-str k))]
         (cond
           (::missing a)
           (update acc :findings conj (command/finding "oracle-no-fixture" (str at ": " (::missing a))))

           (nil? golden)
           (-> acc (assoc-in [:answers [c k]] a)
               (update :findings conj (command/finding "oracle-no-golden"
                                                       (str at " has no golden: record it with rdm-spec oracle record --reason R"))))

           :else
           (let [ds (differences golden a)]
             (-> acc
                 (assoc-in [:answers [c k]] a)
                 (update :compared conj {:component c :case k})
                 (update :findings into
                         (for [d (concat (remove :path ds) (take 5 (filter :path ds)))]
                           (command/finding "oracle-differs"
                                            (one-line (str "the golden " at ": its " (:what d) " differs"
                                                           (when (seq (:path d)) (str " at " (pr (:path d))))
                                                           (when (:item d) (str " (item " (:item d) ")"))
                                                           ": the golden says " (if (:line d) (:golden d) (pr (:golden d)))
                                                           ", the instrument gives " (if (:line d) (:actual d) (pr (:actual d)))))))))))))
     {:compared [] :findings [] :answers {}}
     (cases data))))

;; ---------------------------------------------------------------------------
;; The stages

(defn- env-of [req] (dissoc (or (:env req) (into {} (System/getenv))) "RESULTS_DIR"))

(defn- with-tmp [f]
  (let [tmp (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-run-"}))]
    (try (f tmp) (finally (fs/delete-tree tmp)))))

(defn run-oracle
  "Judges the artifact the configuration names: {:result :findings :data :answers}."
  [req]
  (let [o (get-in req [:config :oracle])
        _ (when-not (and (map? o) (string? (:repository o)) (string? (:digest o)))
            (command/cannot-run! (str "the configuration's :oracle is not {:repository R :digest D}")))
        data (read-artifact req o)
        {:keys [compared findings answers]} (with-tmp #(judge (env-of req) data %))]
    {:data data :answers answers
     :result {:oracle {:repository (:repository o) :digest (:digest o) :compared compared
                       :differences (count (filter #(= "oracle-differs" (:rule %)) findings))}}
     :findings findings}))

(def ^:private none "there is no oracle to run: the configuration names no :oracle")

(defn gate
  "The gate stage of fence and verify: the oracle's findings, or nothing
  when the configuration names no :oracle."
  [req]
  (if (contains? (:config req) :oracle)
    (select-keys (run-oracle req) [:result :findings])
    {:result {} :findings []}))

(defn- record! [req]
  (let [reason (get-in req [:opts :reason])
        _ (when (or (not (string? reason)) (str/blank? reason))
            (command/cannot-run! "a reason is required: rdm-spec oracle record --reason R (a golden changes only for a stated reason)"))
        _ (when-not (contains? (:config req) :oracle)
            (command/cannot-run! (str none ", so there is nothing to record into")))
        o (get-in req [:config :oracle])
        {:keys [data answers]} (run-oracle req)
        ;; the oracle's own cases judge other goldens: run them again on
        ;; the goldens this record would write
        answers (let [selfs (filter #(= :measurement/oracle (first %)) (cases data))]
                  (if (empty? selfs)
                    answers
                    (let [data' (reduce (fn [m [ck a]] (if (= :measurement/oracle (first ck)) m
                                                           (assoc-in m ck (select-keys a [:out :exit :err :wrote]))))
                                        data answers)]
                      (with-tmp (fn [tmp] (reduce (fn [m ck] (assoc m ck (run-case (env-of req) data' (first ck) (second ck) tmp)))
                                                  answers selfs))))))
        named (parse-changes (get-in req [:opts :changes]))
        added (vec (for [[c k] (cases data) :when (and (nil? (get-in data [c k])) (get answers [c k]))] [c k]))
        changed (vec (for [[c k] (cases data)
                           :let [g (get-in data [c k]) a (get answers [c k])]
                           :when (and g a (seq (differences g a)))]
                       [c k]))
        refused (record-refusals changed added named)]
    (cond
      (seq refused)
      {:result {}
       :findings (vec (for [[c k] refused]
                        (command/finding "oracle-change-unnamed"
                                         (str c " " (pr-str k) " is an existing golden the record would change, beside the new cases "
                                              (str/join ", " (map (fn [[c2 k2]] (str c2 " " (pr-str k2))) added))
                                              ": name it with --changes " (subs (str c) 1) ":" k "; nothing was recorded"))))}

      (and (empty? added) (empty? changed))
      {:result {:oracle {:repository (:repository o) :digest (:digest o) :recorded nil}} :findings []}

      :else
      (let [entry {:date (str (java.time.LocalDate/now)) :reason reason
                   :changed (mapv (fn [[c k]] {:component c :case k}) changed)
                   :added (mapv (fn [[c k]] {:component c :case k}) added)}
            new-data (-> (reduce (fn [m ck] (assoc-in m ck (let [a (get answers ck)]
                                                              (cond-> (select-keys a [:out :exit :err])
                                                                (:wrote a) (assoc :wrote (:wrote a))))))
                                 data (concat changed added))
                         (update :recorded (fnil conj []) entry))
            digest (push! req new-data (:repository o))
            cf (command/path req command/config-file)
            text (slurp (str cf))]
        (spit (str cf) (if (str/includes? text (:digest o))
                         (str/replace text (:digest o) digest)
                         (str (pr (assoc-in (:config req) [:oracle :digest] digest)) "\n")))
        {:result {:oracle {:repository (:repository o) :digest digest :previous (:digest o) :recorded entry}}
         :findings []}))))

(defn oracle
  "The oracle task's stage."
  [req]
  (let [[sub & more] (:args req)]
    (cond
      (and sub (not= "record" sub))
      (command/cannot-run! (str "rdm-spec oracle takes record or nothing, not " sub ": rdm-spec oracle [record --reason R]"))
      (seq more) (command/cannot-run! "rdm-spec oracle [record --reason R]")
      (= "record" sub) (record! req)
      (not (contains? (:config req) :oracle)) {:result {:oracle {:none none}} :findings []}
      :else (select-keys (run-oracle req) [:result :findings]))))
