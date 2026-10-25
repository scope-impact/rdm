(ns rdm-spec.measurement.oracle
  "The Oracle component (dhf/spec/measurement/oracle.feature): `rdm-spec
  oracle` judges the instrument (the checklist, the scorer and the
  scoreboard) by running it on recorded fixture cycles and comparing what it
  produces with recorded goldens; `rdm-spec oracle record --reason R`
  re-records the goldens it no longer reproduces. The command also runs
  `oracle` as the gate before fence and ratify, which know nothing of it.

  The oracle is one file, oracle.edn, in one artifact named by the
  configuration's :oracle {:repository R :digest D} and read by its digest
  only: from the local artifact layout ORACLE_LAYOUT names when it is set
  (`oras pull --oci-layout \"$ORACLE_LAYOUT@D\"`), else from the registry
  repository R (`oras pull R@D`). oracle.edn:

    {:project    {path text}         ; the one fixture project
     :cycles     {\"NN\" {:cycle {cycle.edn} :ratified {ratified.edn} :verdicts [verdict]}}
     :checklist  {\"NN\" checklist}    ; checklist.json, keys as keywords
     :scorer     {\"NN\" score}        ; score.edn
     :scoreboard {\"NN-MM\" sb}        ; the scoreboard's :scoreboard
     :recorded   [{:date \"YYYY-MM-DD\" :reason R :changed [component]}]}

  Running it: the project is written into a scratch directory, committed in
  a new repository and tagged with every cut the cycles name; each cycle's
  cycle.edn and ratified.edn are written, then `checklist NN`, `score NN
  --verdicts` (when the cycle has verdicts) and every `scoreboard NN MM` a
  golden names run there, as the command runs them. Each output is compared
  with its golden as data; a difference is a finding naming the golden (its
  component and key), the place (an item by its id) and both values
  (oracle-golden-differs); a golden with no output, oracle-output-missing.
  An oracle with no fixture cycle judged nothing (oracle-empty). None of
  these findings has a file: oracle.edn is not in the project.

  No :oracle is no oracle: the result says there is none to run, and
  nothing else is said. An :oracle of another shape, an artifact the store
  does not hold, one holding no oracle.edn or an unreadable one, cannot run
  (exit 2), naming the repository and the digest.

  record: a reason is required (else exit 2, storing nothing). Every golden
  the instrument no longer reproduces is replaced with what it produces, a
  cycle's missing checklist or score golden added, and {:date :reason
  :changed} appended to :recorded; the new oracle.edn is pushed as a new
  artifact (`oras push`, to ORACLE_LAYOUT when set, else to R) and the
  configuration's :oracle :digest rewritten in place, for a person to
  commit. The old artifact is left as it was. When nothing changed, nothing
  is stored. An output the instrument cannot produce stops it (exit 1).

  Results: {:oracle {:repository :digest :compared [[component key]]
  :differences n}}; {:oracle {:says \"there is no oracle to run ...\"}};
  record: {:oracle {:repository :digest :previous :changed :reason}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")

(def components
  "The instrument's components, in the order the oracle runs and names them."
  [:checklist :scorer :scoreboard])

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras
  "Runs oras in `dir` with `env`: {:exit :out :err}; cannot run without oras."
  [dir env & args]
  (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :env env}
              "oras" (map str args))
       (catch Exception e
         (command/cannot-run! (str "oras, which reads the oracle's artifact, cannot be run: " (ex-message e))))))

(defn- env-of [req] (or (:env req) (into {} (System/getenv))))

(defn- layout-of [req] (not-empty (get (env-of req) "ORACLE_LAYOUT")))

(defn- where [req {:keys [repository digest]}]
  (str repository "@" digest (when-let [l (layout-of req)] (str " (from the artifact layout " l ")"))))

(defn configured
  "Pure: the configuration's :oracle, checked: nil when there is none."
  [config]
  (when (contains? config :oracle)
    (let [o (:oracle config)]
      (when-not (and (map? o) (string? (:repository o)) (not (str/blank? (:repository o)))
                     (string? (:digest o)) (re-matches #"sha256:[0-9a-f]{64}" (:digest o)))
        (command/cannot-run! (str "the configuration " command/config-file " holds :oracle " (pr-str o)
                                  ", not {:repository R :digest \"sha256:...\"}")))
      o)))

(defn fetch
  "The text of oracle.edn in the artifact `o` names, read by its digest."
  [req o]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-pull-"})]
    (try
      (let [out (str (fs/file tmp "out"))
            layout (layout-of req)
            r (if layout
                (oras tmp (env-of req) "pull" "--oci-layout" (str layout "@" (:digest o)) "-o" out)
                (oras tmp (env-of req) "pull" (str (:repository o) "@" (:digest o)) "-o" out))]
        (when-not (zero? (:exit r))
          (command/cannot-run! (str "the oracle " (where req o) " cannot be read: "
                                    (str/trim (str (:err r) " " (:out r))))))
        (let [f (fs/file out "oracle.edn")]
          (when-not (fs/regular-file? f)
            (command/cannot-run! (str "the oracle " (where req o) " holds no oracle.edn")))
          (slurp (str f))))
      (finally (fs/delete-tree tmp)))))

(defn read-oracle
  "Pure but for cannot-run: oracle.edn's text, read and checked."
  [text label]
  (let [v (try (edn/read-string text)
               (catch Exception e (command/cannot-run! (str "the oracle " label " holds an oracle.edn that is not"
                                                            " readable EDN: " (ex-message e)))))
        bad #(command/cannot-run! (str "the oracle " label " holds an oracle.edn whose " %))]
    (when-not (map? v) (bad "value is not a map"))
    (doseq [k (concat [:project :cycles] components)]
      (when-not (or (nil? (get v k)) (map? (get v k))) (bad (str k " is not a map"))))
    (doseq [[path text] (:project v)]
      (when-not (and (string? path) (string? text) (not (fs/absolute? path))
                     (not-any? #{".." ""} (str/split path #"/")))
        (bad (str ":project names " (pr-str path) ", not a relative path with its text"))))
    (doseq [[nn c] (:cycles v)]
      (when-not (and (string? nn) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" nn) (map? c) (map? (:cycle c)))
        (bad (str ":cycles holds " (pr-str nn) ", not a cycle's name with its :cycle"))))
    (doseq [k (keys (:scoreboard v))]
      (when-not (and (string? k) (re-matches #"[A-Za-z0-9._]+-[A-Za-z0-9._]+" k))
        (bad (str ":scoreboard holds the key " (pr-str k) ", not NN-MM"))))
    (when-not (or (nil? (:recorded v)) (vector? (:recorded v))) (bad ":recorded is not a vector"))
    v))

(defn- store!
  "Pushes `text` as oracle.edn, a new artifact: its digest."
  [req o text]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-oracle-push-"})]
    (try
      (spit (str (fs/file tmp "oracle.edn")) text)
      (let [layout (layout-of req)
            r (apply oras tmp (env-of req) "push"
                     (concat (if layout ["--oci-layout" layout] [])
                             ["--artifact-type" artifact-type]
                             (if layout [] [(:repository o)])
                             ["oracle.edn:application/edn" "--format" "go-template={{.digest}}"]))
            d (str/trim (str (:out r)))]
        (when-not (and (zero? (:exit r)) (re-matches #"sha256:[0-9a-f]{64}" d))
          (command/cannot-run! (str "the new oracle could not be stored in " (or layout (:repository o)) ": "
                                    (str/trim (str (:err r) " " (:out r))))))
        d)
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; Differences, as data

(defn- id-keyed? [xs]
  (and (sequential? xs) (seq xs) (every? #(and (map? %) (contains? % :id)) xs)
       (apply distinct? (map :id xs))))

(defn differences
  "Pure: where `produced` differs from `golden`: [{:path :golden :produced}].
  Maps are compared key by key, vectors of maps with :id item by item (the
  path naming the item's id), other vectors of one length place by place."
  ([golden produced] (differences [] golden produced))
  ([path g p]
   (cond
     (= g p) []

     (and (map? g) (map? p))
     (vec (mapcat #(differences (conj path %) (get g %) (get p %))
                  (distinct (concat (keys g) (keys p)))))

     (and (id-keyed? g) (id-keyed? p) (= (map :id g) (map :id p)))
     (vec (mapcat (fn [a b] (differences (conj path (:id a)) a b)) g p))

     (and (id-keyed? g) (id-keyed? p))
     (let [gi (into {} (map (juxt :id identity)) g)
           pi (into {} (map (juxt :id identity)) p)
           ids (distinct (concat (map :id g) (map :id p)))
           each (vec (mapcat #(differences (conj path %) (gi %) (pi %)) ids))]
       (if (seq each) each [{:path path :golden (map :id g) :produced (map :id p)}]))

     (and (sequential? g) (sequential? p) (= (count g) (count p)))
     (vec (mapcat (fn [i a b] (differences (conj path i) a b)) (range) g p))

     :else [{:path path :golden g :produced p}])))

(defn- show [x] (binding [*print-namespace-maps* false] (pr-str x)))

(defn difference-finding
  "Pure: the finding of one difference of the golden `component` `k`."
  [component k {:keys [path golden produced]}]
  (command/finding "oracle-golden-differs"
                   (str "the golden " component " " (show k) " differs"
                        (when (seq path) (str " at " (str/join " " (map show path))))
                        ": the golden says " (show golden) ", the instrument produced " (show produced))))

;; ---------------------------------------------------------------------------
;; Running the instrument on the fixture

(def ^:private git-vars
  #{"GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_OBJECT_DIRECTORY" "GIT_ALTERNATE_OBJECT_DIRECTORIES"
    "GIT_COMMON_DIR" "GIT_NAMESPACE" "GIT_CEILING_DIRECTORIES"})

(defn- git! [dir env & args]
  (let [r (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                               :env (into {} (remove (comp git-vars key)) env)}
                      "git" "-c" "user.name=rdm-spec oracle" "-c" "user.email=oracle@rdm-spec.invalid"
                      "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" "-c" "init.defaultBranch=main"
                      (map str args))
               (catch Exception e (command/cannot-run! (str "git cannot be run: " (ex-message e)))))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the oracle's fixture project could not be committed: git " (str/join " " args)
                                ": " (str/trim (:err r)))))
    (:out r)))

(defn- fixture!
  "Writes the fixture project into `dir`, commits it and tags every cut."
  [dir env oracle]
  (doseq [[path text] (:project oracle)]
    (let [f (fs/file dir path)]
      (fs/create-dirs (fs/parent f))
      (spit (str f) text)))
  (git! dir env "init" "-q")
  (git! dir env "add" "-A")
  (git! dir env "commit" "-q" "--no-verify" "--allow-empty" "-m" "the oracle's fixture project")
  (doseq [cut (sort (distinct (keep #(get-in % [:cycle :cut]) (vals (:cycles oracle)))))
          :when (and (string? cut) (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" cut))]
    (git! dir env "tag" "-f" cut)))

(defn- run-task
  "Runs `rdm-spec words` in the fixture: the execution, and why it gave no
  output when it failed."
  [dir env words]
  (let [x (command/execute words {:root dir :env env})]
    (assoc x :why (str "rdm-spec " (str/join " " words) " "
                       (if (:cannot-run x)
                         (str "could not run: " (:cannot-run x))
                         (str "exited " (:exit x)
                              (when-let [f (first (remove command/warning? (:findings x)))]
                                (str ": " (command/format-finding f)))))))))

(defn produce
  "Runs the instrument on every fixture cycle of `oracle`:
  {[component key] {:value v-or-nil :why why-not}}."
  [env oracle]
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-oracle-fixture-"}))]
    (try
      (fixture! dir env oracle)
      (let [cfg (or (command/read-config dir) {})
            cycles-dir (str/replace (str (command/config-value {:config cfg} :cycles-dir)) #"/+$" "")
            at #(fs/file dir cycles-dir %1 %2)
            outputs (atom {})]
        (doseq [[nn {:keys [cycle ratified verdicts]}] (sort-by key (:cycles oracle))]
          (fs/create-dirs (fs/file dir cycles-dir nn))
          (spit (str (at nn "cycle.edn")) (str (show cycle) "\n"))
          (when ratified (spit (str (at nn "ratified.edn")) (str (show ratified) "\n")))
          (let [c (run-task dir env ["checklist" nn])
                f (at nn "checklist.json")]
            (swap! outputs assoc [:checklist nn]
                   (if (and (zero? (:exit c)) (fs/regular-file? f))
                     {:value (json/parse-string (slurp (str f)) true)}
                     {:why (:why c)})))
          (when (some? verdicts)
            (let [vf (str "oracle-verdicts-" nn ".edn")
                  _ (spit (str (fs/file dir vf)) (str (show verdicts) "\n"))
                  s (run-task dir env ["score" nn "--verdicts" vf])
                  f (at nn "score.edn")]
              (swap! outputs assoc [:scorer nn]
                     (if (and (zero? (:exit s)) (fs/regular-file? f))
                       {:value (edn/read-string (slurp (str f)))}
                       {:why (:why s)})))))
        (doseq [k (sort (keys (:scoreboard oracle)))
                :let [[a b] (str/split k #"-" 2)
                      s (run-task dir env ["scoreboard" a b])]]
          (swap! outputs assoc [:scoreboard k]
                 (if (and (zero? (:exit s)) (contains? (:result s) :scoreboard))
                   {:value (:scoreboard (:result s))}
                   {:why (:why s)})))
        @outputs)
      (finally (fs/delete-tree dir)))))

(defn goldens
  "Pure: every golden of `oracle`, [[component key golden]], in the order
  the oracle names them."
  [oracle]
  (vec (for [c components [k g] (sort-by key (get oracle c))] [c k g])))

(defn judge
  "Pure: {:compared [[component key]] :findings} of the goldens against the
  outputs `produce` gave."
  [oracle outputs]
  (if (empty? (:cycles oracle))
    {:compared []
     :findings [(command/finding "oracle-empty"
                                 "the oracle holds no fixture cycle, so it judged nothing: the instrument is unchecked")]}
    (let [gs (goldens oracle)]
      {:compared (mapv (fn [[c k _]] [c k]) gs)
       :findings (vec (mapcat (fn [[c k g]]
                                (let [{:keys [value why]} (get outputs [c k])]
                                  (if (nil? value)
                                    [(command/finding "oracle-output-missing"
                                                      (str "the golden " c " " (show k) " has no output to compare: "
                                                           (or why (str "the oracle holds no fixture cycle " (show k)))))]
                                    (map #(difference-finding c k %) (differences g value)))))
                              gs))})))

;; ---------------------------------------------------------------------------
;; Recording

(defn rerecord
  "Pure: {:oracle new-oracle :changed [component]} with every golden the
  outputs no longer match replaced, and a cycle's missing checklist or score
  golden added; :missing [[component key why]] for goldens with no output."
  [oracle outputs]
  (let [missing (vec (for [[c k _] (goldens oracle)
                           :let [o (get outputs [c k])]
                           :when (nil? (:value o))]
                       [c k (:why o)]))
        produced (into {} (for [[ck o] outputs :when (some? (:value o))] [ck (:value o)]))
        candidates (concat (for [[c k _] (goldens oracle)] [c k])
                           (for [[c k] (keys produced) :when (#{:checklist :scorer} c)] [c k]))
        new (reduce (fn [o [c k]]
                      (if-let [v (get produced [c k])] (assoc-in o [c k] v) o))
                    oracle (distinct candidates))
        changed (vec (filter #(not= (get oracle %) (get new %)) components))]
    {:oracle new :changed changed :missing missing}))

(defn- edn-text [x]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint x))))

(defn- rewrite-digest!
  "Rewrites the configuration's :oracle :digest from `old` to `new`, in place."
  [req old new]
  (let [f (command/path req command/config-file)
        text (slurp (str f))
        quoted (str "\"" old "\"")]
    (when-not (str/includes? text quoted)
      (command/cannot-run! (str command/config-file " does not hold the digest " old " as written; the new artifact is "
                                new ": set :oracle :digest to it by hand")))
    (spit (str f) (str/replace-first text quoted (str "\"" new "\"")))))

(defn- record!
  [req o oracle reason]
  (let [outputs (produce (env-of req) oracle)
        {new :oracle changed :changed missing :missing} (rerecord oracle outputs)]
    (cond
      (seq missing)
      {:result {}
       :findings (vec (for [[c k why] missing]
                        (command/finding "oracle-output-missing"
                                         (str "the golden " c " " (show k) " cannot be recorded, the instrument produced"
                                              " nothing: " (or why (str "the oracle holds no fixture cycle " (show k)))))))}

      (empty? changed)
      {:result {:oracle {:repository (:repository o) :digest (:digest o) :changed []
                         :says "the instrument reproduces every golden: nothing was recorded"}}
       :findings []}

      :else
      (let [entry {:date (str (java.time.LocalDate/now)) :reason reason :changed changed}
            new (update new :recorded (fnil conj []) entry)
            d (store! req o (edn-text new))]
        (rewrite-digest! req (:digest o) d)
        {:result {:oracle {:repository (:repository o) :digest d :previous (:digest o)
                           :changed changed :reason reason
                           :says (str "a new artifact holds the goldens; " command/config-file
                                      " names its digest, for a person to commit")}}
         :findings []}))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- record-request
  "For the oracle task: nil to judge, or the reason to record with. Cannot
  run on another argument, or record without a reason."
  [req]
  (when (= "oracle" (:task req))
    (let [args (:args req)
          reason (get-in req [:opts :reason])]
      (cond
        (empty? args)
        (do (when reason (command/cannot-run! "--reason is given to record: rdm-spec oracle record --reason R"))
            nil)

        (= ["record"] args)
        (if (str/blank? reason)
          (command/cannot-run! (str "a reason is required to record goldens: rdm-spec oracle record --reason R;"
                                    " nothing was stored"))
          reason)

        :else (command/cannot-run! (str "oracle takes record or nothing, not " (str/join " " args)
                                        ": rdm-spec oracle [record --reason R]"))))))

(defn oracle
  "The oracle stage: judges the instrument (the oracle task, and the gate of
  fence and ratify), or records its goldens (oracle record)."
  [req]
  (let [reason (record-request req)
        o (configured (:config req))]
    (cond
      (nil? o)
      (if reason
        (command/cannot-run! (str "the configuration " command/config-file " names no :oracle to record"))
        {:result {:oracle {:says "there is no oracle to run: the configuration names no :oracle"}}
         :findings []})

      :else
      (let [oracle (read-oracle (fetch req o) (where req o))]
        (if reason
          (record! req o oracle reason)
          (let [outputs (if (empty? (:cycles oracle)) {} (produce (env-of req) oracle))
                {:keys [compared findings]} (judge oracle outputs)]
            {:result {:oracle {:repository (:repository o) :digest (:digest o)
                               :compared compared :differences (count findings)}}
             :findings findings}))))))
