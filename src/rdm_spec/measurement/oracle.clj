(ns rdm-spec.measurement.oracle
  "The Oracle (dhf/spec/measurement/oracle.feature; dhf/spec/glossary.md,
  \"Oracle (instrument)\"): `rdm-spec oracle [record --reason R]` judges
  the instrument (the checklist, the scorer and the scoreboard) on recorded
  fixture cycles against recorded goldens; it is also the gate stage of
  fence and verify (`gate`), so nothing is fenced or verified while it is
  red.

  The oracle is one file, oracle.edn, in one artifact named by the
  configuration's :oracle {:repository R :digest D}, read by its digest
  only: from the local artifact layout ORACLE_LAYOUT names when it is set,
  else from the registry repository R (the stack's artifact tool, oras).

    {:project    {path text}     the one fixture project, committed in a
                                 scratch repository tagged with every cut
                                 the cycles name
     :cycles     {\"NN\" {:cycle cycle.edn :ratified ratified.edn :verdicts [..]}}
     :checklist  {\"NN\" checklist.json, as data}
     :scorer     {\"NN\" score.edn}
     :scoreboard {\"NN-MM\" the scoreboard's :scoreboard}
     :recorded   [{:date :reason :changed [component]}]}

  For every cycle the checklist and then the scorer run in the scratch
  project, and the scoreboard for every pair a golden names; each golden is
  compared as data with what the instrument gives. A golden that differs is
  a finding (oracle-golden-differs) naming the golden, the first place it
  differs and both values; an oracle with no fixture cycle is a finding
  (oracle-empty), since it judged nothing. No :oracle in the configuration
  is no oracle, said on standard output only. An oracle that cannot be read
  cannot run (exit 2), naming the repository and the digest.

  `record --reason R` (a reason is required, else exit 2, storing
  nothing) stores a new artifact whose goldens that differ are rewritten,
  :recorded gaining {:date :reason :changed}, and rewrites the
  configuration's digest, for a person to commit; the old artifact is
  never touched. With nothing to rewrite, nothing is stored.

  Result: {:oracle {:repository :digest :compared [{:component :key}]
  :differs n}}, or {:oracle {:note ..}}, or {:oracle {:recorded {..}}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def artifact-type "application/vnd.scope-impact.rdm-spec.oracle.v1")
(def no-oracle "the configuration names no :oracle: there is no oracle to run")

;; ---------------------------------------------------------------------------
;; Pure

(defn difference
  "The first place `golden` and `actual` differ: {:path :golden :actual}, or nil."
  ([golden actual] (difference golden actual []))
  ([golden actual path]
   (cond
     (= golden actual) nil
     (and (map? golden) (map? actual))
     (or (first (keep #(difference (get golden %) (get actual %) (conj path %))
                      (sort-by pr-str (distinct (concat (keys golden) (keys actual))))))
         {:path path :golden golden :actual actual})
     (and (sequential? golden) (sequential? actual) (= (count golden) (count actual)))
     (or (first (keep (fn [[i g a]] (difference g a (conj path i))) (map vector (range) golden actual)))
         {:path path :golden golden :actual actual})
     :else {:path path :golden golden :actual actual})))

(defn cuts
  "Every cut the fixture cycles name, sorted."
  [cycles]
  (vec (sort (distinct (keep #(get-in % [:cycle :cut]) (vals cycles))))))

(defn recorded
  "The oracle `o` with the goldens of `moved` ({component {key value}})
  rewritten and :recorded gaining the record of `date` and `reason`."
  [o moved date reason]
  (-> (reduce-kv (fn [acc comp ks] (update acc comp merge ks)) o moved)
      (update :recorded (fnil conj []) {:date date :reason reason :changed (vec (sort (keys moved)))})))

;; ---------------------------------------------------------------------------
;; The artifact

(defn- oras [dir env & args]
  (try
    (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :env env} "oras" args)
    (catch Exception e
      (command/cannot-run! (str "the artifact tool oras cannot be run (" (ex-message e) "), so the oracle cannot be read")))))

(defn- layout [req] (not-empty (get (:env req) "ORACLE_LAYOUT")))

(defn- where [req {:keys [repository digest]}]
  (str repository "@" digest (when-let [l (layout req)] (str " (from the local layout " l ")"))))

(defn- pull!
  "oracle.edn of the configured artifact."
  [req {:keys [repository digest] :as o}]
  (when-not (and (string? repository) (string? digest) (re-matches #"sha256:[0-9a-f]{64}" digest))
    (command/cannot-run! (str "the configuration's :oracle is " (pr-str o) ": it is {:repository R :digest \"sha256:...\"}")))
  (let [d (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (let [ref (if-let [l (layout req)] (str l "@" digest) (str repository "@" digest))
            r (apply oras d (:env req) (concat ["pull"] (when (layout req) ["--oci-layout"]) [ref "-o" (str d)]))
            f (fs/file d "oracle.edn")]
        (when-not (and (zero? (:exit r)) (fs/exists? f))
          (command/cannot-run! (str "the oracle " (where req o) " cannot be read: "
                                    (str/trim (str (:err r) (:out r))))))
        (let [v (try (edn/read-string (slurp (str f))) (catch Exception e {::error (ex-message e)}))]
          (when (or (not (map? v)) (::error v))
            (command/cannot-run! (str "the oracle " (where req o) " holds no readable oracle.edn map")))
          v))
      (finally (fs/delete-tree d)))))

(defn- push!
  "Stores `oracle` as a new artifact: its digest."
  [req {:keys [repository] :as o} oracle]
  (let [d (fs/create-temp-dir {:prefix "rdm-spec-oracle-"})]
    (try
      (spit (str (fs/file d "oracle.edn")) (binding [*print-namespace-maps* false] (str (pr-str oracle) "\n")))
      (let [r (apply oras d (:env req)
                     (concat ["push"] (if-let [l (layout req)] ["--oci-layout" l] [repository])
                             ["--artifact-type" artifact-type "oracle.edn:application/edn"]))
            digest (second (re-find #"Digest: (sha256:[0-9a-f]{64})" (str (:out r) (:err r))))]
        (when-not (and (zero? (:exit r)) digest)
          (command/cannot-run! (str "the new oracle cannot be stored in " (where req o) ": " (str/trim (str (:err r) (:out r))))))
        digest)
      (finally (fs/delete-tree d)))))

;; ---------------------------------------------------------------------------
;; Running the instrument on the fixture cycles

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"}}
                 "git" args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the oracle's fixture project cannot be built: git " (str/join " " args) ": " (str/trim (:err r)))))
    r))

(defn- write-edn! [f x]
  (fs/create-dirs (fs/parent f))
  (spit (str f) (binding [*print-namespace-maps* false] (str (pr-str x) "\n"))))

(defn- run! [dir env argv]
  ((requiring-resolve 'rdm-spec.kernel.command/execute) argv {:root (str dir) :env env}))

(defn- outcome [r what]
  (if (:cannot-run r)
    {::failed (str what " cannot run: " (:cannot-run r))}
    (when (pos? (:exit r))
      {::failed (str what " has findings: " (str/join "; " (map command/format-finding (:findings r))))})))

(defn instrument
  "What the instrument gives on the fixture of `oracle`: {component {key value}}."
  [req oracle]
  (let [dir (fs/create-temp-dir {:prefix "rdm-spec-oracle-project-"})
        env (dissoc (:env req) "RESULTS_DIR" "ORACLE_LAYOUT")]
    (try
      (doseq [[path text] (:project oracle)]
        (let [f (fs/file dir path)] (fs/create-dirs (fs/parent f)) (spit (str f) (str text))))
      (git! dir "init" "-q")
      (git! dir "config" "commit.gpgsign" "false")
      (git! dir "add" "-A" "-f" "--" ".")
      (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" "the oracle's fixture project")
      (doseq [c (cuts (:cycles oracle))] (git! dir "tag" "-f" c))
      (let [cfg (try (edn/read-string (str (get (:project oracle) command/config-file))) (catch Exception _ {}))
            cycles-dir (str/replace (command/config-value {:config cfg} :cycles-dir) #"/+$" "")
            rel #(str cycles-dir "/" %1 "/" %2)
            per-cycle (into {}
                            (for [nn (sort (keys (:cycles oracle)))
                                  :let [{c :cycle r :ratified v :verdicts} (get-in oracle [:cycles nn])
                                        _ (write-edn! (fs/file dir (rel nn "cycle.edn")) c)
                                        _ (when r (write-edn! (fs/file dir (rel nn "ratified.edn")) r))
                                        vf (str "verdicts-" nn ".edn")
                                        _ (write-edn! (fs/file dir vf) v)
                                        cl (run! dir env ["checklist" nn])
                                        cl-v (or (outcome cl (str "the checklist of cycle " nn))
                                                 (json/parse-string (slurp (str (fs/file dir (rel nn "checklist.json")))) true))
                                        sc (run! dir env ["score" nn "--verdicts" vf])
                                        sc-v (or (outcome sc (str "the scorer of cycle " nn))
                                                 (edn/read-string (slurp (str (fs/file dir (rel nn "score.edn"))))))]]
                              [nn {:checklist cl-v :scorer sc-v}]))
            boards (into {}
                         (for [k (sort (keys (:scoreboard oracle)))
                               :let [[a b] (str/split k #"-" 2)
                                     r (run! dir env ["scoreboard" a b])]]
                           [k (or (outcome r (str "the scoreboard of " k)) (get-in r [:result :scoreboard]))]))]
        {:checklist (into {} (for [[nn m] per-cycle] [nn (:checklist m)]))
         :scorer (into {} (for [[nn m] per-cycle] [nn (:scorer m)]))
         :scoreboard boards})
      (finally (fs/delete-tree dir)))))

(def components [:checklist :scorer :scoreboard])

(defn judge
  "{:compared [{:component :key}] :findings :moved {component {key actual}}}
  of `oracle` against what the instrument gives."
  [req oracle]
  (if (empty? (:cycles oracle))
    {:compared [] :moved {}
     :findings [(command/finding "oracle-empty" "the oracle holds no fixture cycle, so it judged nothing")]}
    (let [actual (instrument req oracle)
          rows (for [c components [k golden] (sort-by key (get oracle c))]
                 (let [a (get-in actual [c k])]
                   {:component c :key k :actual a :diff (difference golden a)}))]
      {:compared (mapv #(select-keys % [:component :key]) rows)
       :moved (reduce (fn [m {:keys [component key actual diff]}] (if diff (assoc-in m [component key] actual) m)) {} rows)
       :findings (vec (for [{:keys [component key diff]} rows :when diff]
                        (command/finding "oracle-golden-differs"
                                         (str "the golden " component " " (pr-str key) " differs at " (pr-str (:path diff))
                                              ": the golden holds " (pr-str (:golden diff)) ", the " (name component)
                                              " gives " (pr-str (if (map? (:actual diff)) (or (::failed (:actual diff)) (:actual diff)) (:actual diff)))))))})))

;; ---------------------------------------------------------------------------
;; The stages

(defn gate
  "The gate stage of fence and verify: the oracle's findings, if any."
  [req]
  (if-let [o (get-in req [:config :oracle])]
    {:result {} :findings (:findings (judge req (pull! req o)))}
    {:result {} :findings []}))

(defn- record! [req o oracle reason]
  (let [{:keys [moved findings]} (judge req oracle)]
    (when (some #(= "oracle-empty" (:rule %)) findings)
      (command/cannot-run! "the oracle holds no fixture cycle: there is nothing to record"))
    (if (empty? moved)
      {:result {:oracle {:note "every golden is reproduced: nothing was recorded" :digest (:digest o)}} :findings []}
      (let [new (recorded oracle moved (str (java.time.LocalDate/now)) reason)
            digest (push! req o new)
            cf (command/path req command/config-file)
            text (slurp (str cf))]
        (when-not (str/includes? text (:digest o))
          (command/cannot-run! (str command/config-file " does not hold the digest " (:digest o) " as written: it cannot be rewritten")))
        (spit (str cf) (str/replace text (:digest o) digest))
        {:result {:oracle {:recorded {:digest digest :was (:digest o) :changed (vec (sort (keys moved))) :reason reason
                                      :note (str "the configuration's :oracle names the new digest: commit "
                                                 command/config-file " to keep it")}}}
         :findings []}))))

(defn oracle
  "The oracle stage: `rdm-spec oracle [record --reason R]`."
  [req]
  (let [[sub] (:args req)
        reason (get-in req [:opts :reason])
        o (get-in req [:config :oracle])]
    (when (and sub (not= "record" sub))
      (command/cannot-run! (str "oracle takes record or nothing, not " sub ": rdm-spec oracle [record --reason R]")))
    (when (and (= "record" sub) (str/blank? reason))
      (command/cannot-run! "a reason is required: rdm-spec oracle record --reason R; nothing was stored"))
    (when (and (nil? sub) reason)
      (command/cannot-run! "--reason is given only with record: rdm-spec oracle record --reason R"))
    (cond
      (nil? o) {:result {:oracle {:note no-oracle}} :findings []}
      (= "record" sub) (record! req o (pull! req o) reason)
      :else (let [{:keys [compared findings]} (judge req (pull! req o))]
              {:result {:oracle {:repository (:repository o) :digest (:digest o) :compared compared
                                 :differs (count (filter #(= "oracle-golden-differs" (:rule %)) findings))}}
               :findings findings}))))
