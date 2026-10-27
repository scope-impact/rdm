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
;; (read as data), error stream and the files it names with the case's golden.
;; It never calls rdm-spec's code: it only runs its commands.
;;
;; ONLY WHAT THE SPEC STATES. A golden holds nothing the spec does not state
;; (README.md, "Recording"), so two things are left out before comparing and
;; recording. Of each error line, only its head is kept: FILE:LINE: RULE, and
;; the element a rule names (`mapping-differs: story`), as the spec's
;; FILE:LINE: RULE: what states it; the words after are each build's own. And
;; the paths in `unstated` below are dropped from the answer, each with the
;; reason no scenario states it. A path joins `unstated` only by a reviewed
;; commit (cycle 19: the goldens held cycle 17's wording, an unstated
;; :reason and an unstated count, and so turned red on a correct build).
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
;;   machine nor the clock moves a golden. :err is the error stream's heads,
;;   one per line.
;;
;; USE
;;   bb dhf/harness/oracle/oracle.clj [--from CUT --to CUT]   judge (exit 0 none differ, 1 some differ, 2 cannot run)
;;   bb dhf/harness/oracle/oracle.clj record --reason R [--changes COMPONENT:CASE,...] [--from CUT --to CUT]
;;   RDM_SPEC="bb -cp <src> -m rdm-spec.kernel.command" names the rdm-spec judged (default: rdm-spec on PATH).
;;
;; ONLY WHAT THE SPEC CHANGE DID NOT TOUCH. With --from (the cut of the release
;; that judges the cycle) and --to (the cycle's cut), the cases of the
;; components the spec change reaches are skipped, each reported as "in scope,
;; not judged": they are meant to change, and the graders judge them. Every
;; other case is judged, so a difference outside the scopes is a regression.
;; The scopes are asked of the accepted release (`rdm-spec scopes FROM TO`, the
;; rdm-spec on PATH, or RDM_SPEC_SCOPES), never of the build under judgement,
;; so a build cannot widen its own scope. Scopes come from the spec's diff,
;; not from what was rebuilt: in a whole-project cycle, a component rebuilt from
;; nothing whose spec did not change must still match its goldens. Without
;; --from and --to every case is judged (the gate: the installed release
;; against its own goldens). With them, `record` moves only in-scope goldens:
;; a change outside the scopes is refused as a regression.
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

;; Paths in a component's answers that no scenario states, so never compared.
(def unstated
  {:verification/verify [[[:out :verify :checks 0 :reason]
                          "spec-0315 puts a failed check's :reason in ratified.edn; its standard output's checks are stated by spec-0314 without it"]]
   :spec/check          [[[:out :lint :findings]
                          "no scenario states check's :lint summary beyond its questions for a person (spec-0010)"]
                         [[:out :knowledge] "no scenario states check's standard output beyond the lint's questions (cycle 20)"]
                         [[:out :spec] "no scenario states check's standard output beyond the lint's questions (cycle 20)"]
                         [[:out :views] "no scenario states check's standard output beyond the lint's questions (cycle 20)"]
                         [[:out :sources] "no scenario states check's standard output beyond the lint's questions (cycle 22)"]]
   :measurement/scorer  [[[:out :score :ungraded] "no scenario states score's standard output; score.edn is what spec-0123 and its kin state (cycle 20)"]]
   :regeneration/fence  [[[:out] "no scenario states fence's standard output; what it builds is stated, and judged by :wrote and the room (cycle 20)"]]
   :verification/status [[[:err] "no scenario states status's warning line; spec-0060 states the unknown result in its standard output (cycle 20)"]]})

;; Components whose scenarios state where a finding is (its file and line) but
;; not its rule's name: of their error lines only FILE:LINE (or FILE) is kept
;; (cycle 20: check's rule names were each build's own, as scenario-no-id and
;; scenario-unstamped).
(def location-only #{:spec/check})

(defn dissoc-in [m [k & ks]]
  (cond (nil? ks) (if (map? m) (dissoc m k) m)
        (and (map? m) (contains? m k)) (update m k dissoc-in ks)
        (and (vector? m) (integer? k) (< k (count m))) (update m k dissoc-in ks)
        :else m))

;; FILE:LINE: [warning: ]RULE, and for the rules whose finding the spec states
;; with an element (spec-0331: `mapping-differs: story`, `evidence-missing:
;; browser-trace`) that element too; a line of any other form is kept whole.
(def element-rules #{"mapping-differs" "evidence-missing"})
(def head-re #"^((?:\S+:\d+: )?(?:warning: )?([a-z][a-z0-9-]*))(?:(: [^\s:]+)(?=:))?(?=:|$)")

(defn heads [err]
  (if (string? err)
    (vec (for [l (str/split-lines err) :when (not (str/blank? l))]
           (if-let [[_ h rule e] (re-find head-re l)] (str h (when (element-rules rule) e)) l)))
    err))

(defn location [l] (or (second (re-find #"^(\S+?:(?:\d+:)?) " l)) l))

;; The order of findings is stated nowhere, so heads are compared sorted.
(defn stated [c x]
  (when x
    (let [x (if (contains? x :err)
              (update x :err (fn [e] (let [h (heads e)]
                                      (if (sequential? h)
                                        (vec (sort (if (location-only c)
                                                     (map location (if (sequential? e) e (str/split-lines (str e))))
                                                     h)))
                                        h))))
              x)]
      (reduce (fn [x [path _]] (dissoc-in x path)) x (unstated c)))))

(defn component-keys [data] (sort-by str (filter #(and (keyword? %) (namespace %)) (keys data))))

(defn cases [data]
  (for [c (component-keys data) k (sort (keys (get data c)))] [c k]))

(defn diffs [path g a]
  (cond (= g a) []
        (and (map? g) (map? a)) (mapcat #(diffs (conj path %) (get g %) (get a %)) (sort-by str (distinct (concat (keys g) (keys a)))))
        (and (sequential? g) (sequential? a) (= (count g) (count a))) (mapcat #(diffs (conj path %) (nth g %) (nth a %)) (range (count g)))
        :else [[path g a]]))

(defn short [x] (let [s (pr-str x)] (if (> (count s) 160) (str (subs s 0 157) "...") s)))

(defn judge
  ([data] (judge data #{}))
  ([data in-scope]
  (let [tmp (fs/create-temp-dir {:prefix "oracle-"})]
    (try
      (reduce (fn [acc [c k]]
                (let [{:keys [fixture golden]} (get-in data [c k])
                      answer (stated c (run-case tmp fixture))
                      ds (if (nil? golden) :new (diffs [] (stated c golden) answer))]
                  (-> acc
                      (update :compared conj {:component c :case k})
                      (assoc-in [:answers [c k]] answer)
                      (update :findings into
                              (cond (= ds :new) [(str "oracle-no-golden: " c " " (pr-str k) " has no golden: record it")]
                                    :else (for [[path g a] ds]
                                            (str "oracle-differs: " c " " (pr-str k) ": at " (pr-str path)
                                                 " the golden holds " (short g) " and the instrument gives " (short a))))))))
              {:compared [] :findings [] :answers {}}
              (remove (fn [[c _]] (in-scope c)) (cases data)))
      (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------- the scope

(defn opt [args k] (second (drop-while #(not= k %) args)))

(defn scopes-cmd [] (str/split (or (System/getenv "RDM_SPEC_SCOPES") "rdm-spec") #"\s+"))

(defn in-scope
  "The components the spec change between the cuts --from and --to reaches, as
  the accepted release lists them; #{} when no cuts are given."
  [args]
  (let [from (opt args "--from") to (opt args "--to")]
    (cond
      (and (nil? from) (nil? to)) #{}
      (not (and from to)) (die! 2 "cannot-run: --from and --to name the two cuts of the spec change, both or neither")
      :else
      (let [r (apply p/shell {:out :string :err :string :continue true :in ""} (concat (scopes-cmd) ["scopes" from to]))
            data (try (edn/read-string (:out r)) (catch Exception _ nil))]
        (when-not (and (zero? (:exit r)) (map? (:scopes data)))
          (die! 2 (str "cannot-run: " (str/join " " (scopes-cmd)) " scopes " from " " to ": " (str/trim (str (:err r) (:out r))))))
        (set (map :component (get-in data [:scopes :scopes])))))))

(defn skipped [data in-scope]
  (vec (for [[c k] (cases data) :when (in-scope c)] {:component c :case k :why "in scope, not judged"})))

;; ---------------------------------------------------------------- the tasks

(defn result [p compared differences scope skip]
  {:oracle (cond-> {:repository (:repository p) :digest (:digest p) :compared compared :differences differences}
             (seq scope) (assoc :scope (vec (sort-by str scope)) :skipped skip))})

(defn judge! [args]
  (let [p (or (pin) (do (println (pr-str {:oracle {:says "there is no oracle to run: no pin.edn"}})) (System/exit 0)))
        scope (in-scope args)
        data (read-oracle p)
        {:keys [compared findings]} (judge data scope)]
    (when (and (empty? compared) (empty? scope)) (die! 1 "oracle-empty: the oracle holds no case, so it judged nothing"))
    (println (pr-str (result p compared (count (filter #(str/starts-with? % "oracle-differs") findings)) scope (skipped data scope))))
    (binding [*out* *err*] (doseq [f findings] (println f)))
    (System/exit (if (seq findings) 1 0))))

(defn record! [args]
  (let [opt (fn [k] (opt args k))
        scope (in-scope args)
        reason (or (opt "--reason") (die! 2 "cannot-run: record needs --reason R: a golden changes only for a stated reason"))
        changes (set (for [s (some-> (opt "--changes") (str/split #",")) :let [[c k] (str/split s #":" 2)]]
                       [(keyword c) k]))
        p (or (pin) (die! 2 "cannot-run: no pin.edn names an oracle to record into"))
        data (read-oracle p)
        {:keys [answers]} (judge data)
        added (vec (for [[c k] (cases data) :when (nil? (get-in data [c k :golden]))] {:component c :case k}))
        changed (vec (for [[c k] (cases data) :let [g (get-in data [c k :golden])]
                           :when (and g (not= (stated c g) (answers [c k])))] [c k]))
        unnamed (remove changes changed)
        outside (when (seq scope) (remove (fn [[c _]] (scope c)) changed))]
    (when (seq outside)
      (die! 1 (str "oracle-record-refused: these goldens are outside the spec change's scopes, so a difference there is a regression, never a recording: "
                   (str/join ", " (map (fn [[c k]] (str (subs (str c) 1) ":" k)) outside)))))
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
    (nil "--from" "--to") (judge! (or (seq (cons task args)) []))
    "record" (record! args)
    (die! 2 (str "cannot-run: unknown task " task ": oracle.clj [--from CUT --to CUT], or oracle.clj record --reason R [--changes C:K,...] [--from CUT --to CUT]"))))
