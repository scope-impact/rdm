;; The oracle: the judge of rdm-spec's instrument, held still.
;;
;; WHY IT IS HERE, AND HAND-WRITTEN. The spec regenerates the instrument (the
;; checklist, the scorer, the scoreboard, verify, status, scopes, fence, the
;; spec check). A judge regenerated from the same spec agrees with whatever the
;; spec got wrong, and its goldens break each time it is rebuilt (cycles 16 to
;; 18). So, as Löyly holds its harness and its oracle still
;; (loyly:harness/README.md@17d68d2, "The goldens, and the three reasons one
;; can change"), this file is written by hand, changed only by a reviewed
;; commit, kept in every clean room and rebuilt by no cycle.
;;
;; WHAT IT JUDGES. rdm-spec as a black box: for each case it writes the case's
;; files, commits them as the case says, runs the case's command lines with the
;; rdm-spec under judgement, and compares the last one's exit, standard output
;; (read as data), error stream (word for word) and the files it names with the
;; case's golden. It never calls rdm-spec's code: it only runs its commands.
;;
;; THE ARTIFACT. oracle.edn, in one OCI artifact pinned by digest in pin.edn
;; beside this file, read from the layout ORACLE_LAYOUT names, else pulled from
;; the pin's repository. Its shape:
;;
;;   {:format 2
;;    :<component-id> {"<case>" {:fixture F :golden G-or-nil} ...} ...
;;    :recorded [{:date :reason :changed [{:component :case}] :added [...]}]}
;;
;;   F = {:commits [{:files {path text} :tags ["spec-1"]} ...]  ; each commit is the whole tree
;;        :files   {path text}                                  ; written after the commits, uncommitted
;;        :steps   [["checklist" "01"] ...]                     ; rdm-spec command lines, in order
;;        :wrote   ["dhf/cycles/01/score.edn"]}                 ; files the golden records
;;   G = {:exit N :out <stdout as data> :err "<stderr>" :wrote {path content}}
;;
;;   Every step but the last must exit 0. In :out and :err the fixture's own
;;   path reads FIXTURE, and every :seconds reads :measured, so neither the
;;   machine nor the clock moves a golden.
;;
;; USE
;;   bb dhf/harness/oracle/oracle.clj                    judge (exit 0 none differ, 1 some differ, 2 cannot run)
;;   bb dhf/harness/oracle/oracle.clj record --reason R [--changes COMPONENT:CASE,...]
;;   RDM_SPEC="bb -cp <src> -m rdm-spec.kernel.command" names the rdm-spec judged (default: rdm-spec on PATH).
;;
;; A golden changes only by `record`, with a reason. A case with no golden is
;; added freely; an existing golden that changes must be named in --changes,
;; or nothing is recorded (Löyly: a spec that grew is additions only; a
;; changed behaviour is an alarm that needs its reason).
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as json]
         '[clojure.edn :as edn] '[clojure.string :as str] '[clojure.walk :as walk])

(def here (fs/parent (fs/absolutize *file*)))
(def pin-file (fs/file here "pin.edn"))

(defn die! [code msg] (binding [*out* *err*] (println msg)) (System/exit code))

;; ---------------------------------------------------------------- the artifact

(defn pin [] (when (fs/exists? pin-file) (edn/read-string (slurp (str pin-file)))))

(defn oras! [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "oras" args)]
    (when-not (zero? (:exit r)) (die! 2 (str "cannot-run: oras " (str/join " " args) ": " (str/trim (:err r)))))
    (:out r)))

(defn layout [] (System/getenv "ORACLE_LAYOUT"))

(defn read-oracle [{:keys [repository digest]}]
  (let [d (fs/create-temp-dir {:prefix "oracle-pull-"})]
    (try
      (if (layout)
        (oras! "pull" "--oci-layout" (str (layout) "@" digest) "-o" (str d))
        (oras! "pull" (str repository "@" digest) "-o" (str d)))
      (let [f (fs/file d "oracle.edn")]
        (when-not (fs/exists? f) (die! 2 (str "cannot-run: the artifact " digest " holds no oracle.edn")))
        (edn/read-string (slurp (str f))))
      (finally (fs/delete-tree d)))))

(defn push-oracle! [data {:keys [repository]}]
  (let [d (fs/create-temp-dir {:prefix "oracle-push-"})]
    (try
      (spit (str (fs/file d "oracle.edn")) (with-out-str (clojure.pprint/pprint data)))
      (let [target (if (layout) (str (layout) ":recorded") repository)
            out (p/shell {:dir (str d) :out :string :err :string :continue true}
                         "oras" "push" (if (layout) "--oci-layout" "--disable-path-validation") target
                         "--artifact-type" "application/vnd.scope-impact.rdm-spec.oracle.v1"
                         "oracle.edn:application/edn")
            digest (re-find #"sha256:[0-9a-f]{64}" (str (:out out) (:err out)))]
        (when-not (and (zero? (:exit out)) digest) (die! 2 (str "cannot-run: oras push: " (str/trim (str (:err out))))))
        digest)
      (finally (fs/delete-tree d)))))

;; ---------------------------------------------------------------- running a case

(def git-env {"GIT_AUTHOR_NAME" "rdm-spec oracle" "GIT_AUTHOR_EMAIL" "oracle@rdm-spec.invalid"
              "GIT_COMMITTER_NAME" "rdm-spec oracle" "GIT_COMMITTER_EMAIL" "oracle@rdm-spec.invalid"
              "GIT_AUTHOR_DATE" "2026-01-01T00:00:00Z" "GIT_COMMITTER_DATE" "2026-01-01T00:00:00Z"})

(defn git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env (assoc git-env "GIT_CEILING_DIRECTORIES" (str (fs/parent dir)))}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" "-c" "core.hooksPath=/dev/null" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (str/trim (:err r))) {})))))

(defn write-files! [dir files]
  (doseq [[rel text] files]
    (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit (str f) (str text)))))

(defn rdm-spec-cmd [] (str/split (or (System/getenv "RDM_SPEC") "rdm-spec") #"\s+"))

(defn run-step [dir argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_CEILING_DIRECTORIES" (str (fs/parent dir))}}
                 (concat (rdm-spec-cmd) argv))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn read-data [s]
  (let [s (str/trim (str s))]
    (if (str/blank? s) nil (try (edn/read-string s) (catch Exception _ s)))))

(defn read-written [f]
  (when (fs/exists? f)
    (let [t (slurp (str f))]
      (cond (str/ends-with? (str f) ".json") (json/parse-string t)
            (str/ends-with? (str f) ".edn") (edn/read-string t)
            :else t))))

(defn normal [dir x]
  (let [root (str dir)]
    (walk/postwalk (fn [v] (cond (and (map? v) (some #{:seconds} (keys v))) (assoc v :seconds :measured)
                                 (string? v) (str/replace v root "FIXTURE")
                                 :else v))
                   x)))

(defn run-case [tmp {:keys [commits files steps wrote]}]
  (let [dir (fs/create-temp-dir {:dir tmp :prefix "case-"})]
    (when (seq commits)
      (git! dir "init" "-q")
      (doseq [{tree :files tags :tags} commits]
        (doseq [f (fs/list-dir dir) :when (not= ".git" (str (fs/file-name f)))] (fs/delete-tree f))
        (write-files! dir tree)
        (git! dir "add" "-A" "-f" "--" ".")
        (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" "the oracle's fixture")
        (doseq [t tags] (git! dir "tag" "-f" t))))
    (write-files! dir files)
    (let [results (mapv #(run-step dir %) steps)
          setup-failed (some (fn [[i r]] (when (and (< i (dec (count results))) (not (zero? (:exit r)))) [i r]))
                             (map-indexed vector results))
          r (last results)]
      (if-let [[i fr] setup-failed]
        {:setup-failed {:step (nth steps i) :exit (:exit fr) :err (normal dir (str/trim (:err fr)))}}
        (normal dir (cond-> {:exit (:exit r) :out (read-data (:out r)) :err (str/trim (:err r))}
                      (seq wrote) (assoc :wrote (into (sorted-map) (for [w wrote] [w (read-written (fs/file dir w))])))))))))

;; ---------------------------------------------------------------- comparing

(defn component-keys [data] (sort-by str (filter #(and (keyword? %) (namespace %)) (keys data))))

(defn cases [data]
  (for [c (component-keys data) k (sort (keys (get data c)))] [c k]))

(defn diffs [path g a]
  (cond (= g a) []
        (and (map? g) (map? a)) (mapcat #(diffs (conj path %) (get g %) (get a %)) (sort-by str (distinct (concat (keys g) (keys a)))))
        (and (sequential? g) (sequential? a) (= (count g) (count a))) (mapcat #(diffs (conj path %) (nth g %) (nth a %)) (range (count g)))
        :else [[path g a]]))

(defn short [x] (let [s (pr-str x)] (if (> (count s) 160) (str (subs s 0 157) "...") s)))

(defn judge [data]
  (let [tmp (fs/create-temp-dir {:prefix "oracle-"})]
    (try
      (reduce (fn [acc [c k]]
                (let [{:keys [fixture golden]} (get-in data [c k])
                      answer (run-case tmp fixture)
                      ds (if (nil? golden) :new (diffs [] golden answer))]
                  (-> acc
                      (update :compared conj {:component c :case k})
                      (assoc-in [:answers [c k]] answer)
                      (update :findings into
                              (cond (= ds :new) [(str "oracle-no-golden: " c " " (pr-str k) " has no golden: record it")]
                                    :else (for [[path g a] ds]
                                            (str "oracle-differs: " c " " (pr-str k) ": at " (pr-str path)
                                                 " the golden holds " (short g) " and the instrument gives " (short a))))))))
              {:compared [] :findings [] :answers {}}
              (cases data))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------- the tasks

(defn result [p data compared differences]
  {:oracle {:repository (:repository p) :digest (:digest p) :compared compared :differences differences}})

(defn judge! []
  (let [p (or (pin) (do (println (pr-str {:oracle {:says "there is no oracle to run: no pin.edn"}})) (System/exit 0)))
        data (read-oracle p)
        {:keys [compared findings]} (judge data)]
    (when (empty? compared) (die! 1 "oracle-empty: the oracle holds no case, so it judged nothing"))
    (println (pr-str (result p data compared (count (filter #(str/starts-with? % "oracle-differs") findings)))))
    (binding [*out* *err*] (doseq [f findings] (println f)))
    (System/exit (if (seq findings) 1 0))))

(defn record! [args]
  (let [opt (fn [k] (second (drop-while #(not= k %) args)))
        reason (or (opt "--reason") (die! 2 "cannot-run: record needs --reason R: a golden changes only for a stated reason"))
        changes (set (for [s (some-> (opt "--changes") (str/split #",")) :let [[c k] (str/split s #":" 2)]]
                       [(keyword c) k]))
        p (or (pin) (die! 2 "cannot-run: no pin.edn names an oracle to record into"))
        data (read-oracle p)
        {:keys [answers]} (judge data)
        added (vec (for [[c k] (cases data) :when (nil? (get-in data [c k :golden]))] {:component c :case k}))
        changed (vec (for [[c k] (cases data) :let [g (get-in data [c k :golden])]
                           :when (and g (not= g (answers [c k])))] [c k]))
        unnamed (remove changes changed)]
    (when (seq unnamed)
      (die! 1 (str "oracle-record-refused: these existing goldens would change and --changes does not name them: "
                   (str/join ", " (map (fn [[c k]] (str (subs (str c) 1) ":" k)) unnamed)))))
    (let [new (reduce (fn [d [c k]] (assoc-in d [c k :golden] (answers [c k]))) data
                      (concat (map (juxt :component :case) added) changed))
          new (update new :recorded (fnil conj [])
                      {:date (str (java.time.LocalDate/now)) :reason reason
                       :changed (mapv (fn [[c k]] {:component c :case k}) changed) :added added})
          digest (push-oracle! new p)]
      (spit (str pin-file) (pr-str (assoc p :digest digest)))
      (println (pr-str {:oracle {:recorded {:reason reason :changed (count changed) :added (count added)}
                                 :previous (:digest p) :digest digest :pin (str pin-file)}})))))

(require 'clojure.pprint)
(let [[task & args] *command-line-args*]
  (case task
    nil (judge!)
    "record" (record! args)
    (die! 2 (str "cannot-run: unknown task " task ": oracle.clj, or oracle.clj record --reason R [--changes C:K,...]"))))
