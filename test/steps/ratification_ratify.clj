(ns steps.ratification-ratify
  "Step definitions for dhf/spec/ratification/ratify.feature.

  A ratify fixture is a cycle project (steps.measurement-cycle: a copy of the
  room's allow-list whose spec is the default scenarios, spec-0012 and
  spec-0014 stamped, spec-0013 planned; cut spec-3; cycle 03) and a room
  whose test.clj is written from ctx :plan:
    :exit     the exit status the test command ends with (0)
    :results  [{:spec id :status s}] the Allure results it writes into
              $RESULTS_DIR (each non-planned default scenario passed)
    :say      a line it prints, so its output can be found in test.log
  The test command also writes the RESULTS_DIR it saw into seen-results-dir
  in the directory it runs in (the checkout).

  Uses the kernel's shared When and `it exits N`, and the measurement
  context's shared cycle Givens."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-ratify! [] (s/needs-built! 'rdm-spec.ratification.ratify))

(def build "dhf/cycles/03/build")
(def ratified "dhf/cycles/03/ratified.edn")
(def test-log "dhf/cycles/03/test.log")

(def default-plan
  {:exit 0
   :results [{:spec "spec-0012" :status "passed"} {:spec "spec-0014" :status "passed"}]
   :say "the fixture's tests ran"})

(defn test-clj
  "The room's test.clj for `plan`."
  [{:keys [exit results say]}]
  (str "(require '[cheshire.core :as json])\n"
       "(def results-dir (System/getenv \"RESULTS_DIR\"))\n"
       "(spit \"seen-results-dir\" (str results-dir))\n"
       "(println " (pr-str say) ")\n"
       "(binding [*out* *err*] (println \"stderr of the fixture's tests\"))\n"
       "(doseq [[i {:keys [spec status]}] (map-indexed vector " (pr-str results) ")]\n"
       "  (spit (str results-dir \"/r\" i \"-result.json\")\n"
       "        (json/generate-string {:uuid (str \"r\" i) :name (str \"scenario \" spec) :status status\n"
       "                               :steps [] :labels [{:name \"spec\" :value spec}]})))\n"
       "(System/exit " exit ")\n"))

(defn- room-with! [room plan]
  (s/write! room "test.clj" (test-clj plan))
  (s/write! room "test/rdm_spec/demo_test.clj" "(ns rdm-spec.demo-test)\n")
  room)

(defn fixture!
  "ctx with a cycle project (when it has none) and its room's test.clj
  written from :plan changed by `f`."
  ([ctx] (fixture! ctx identity))
  ([ctx f]
   (needs-ratify!)
   (let [ctx (if (:dir ctx)
               ctx
               (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})]
                 (mc/cut! dir "spec-3")
                 (mc/write-cycle! dir "03" {})
                 (assoc ctx :dir dir :cycle "03")))
         room (or (:room ctx) (mc/room!))
         plan (f (or (:plan ctx) default-plan))]
     (room-with! room plan)
     (assoc ctx :room room :plan plan))))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- file [ctx rel] (fs/file (:dir ctx) rel))

(defn- read-ratified [ctx]
  (let [f (file ctx ratified)]
    (s/check! (fs/exists? f) (str "no " ratified "\n" (s/show (run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- exits-saying! [ctx code re what]
  (let [r (run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    (s/check! (re-find re (:err r)) (str "the error stream does not say " what "\n" (s/show r)))
    r))

(defn- files-under
  "{relative path -> content bytes as a string} of every file under the
  path `rel` of `dir` (a file or a directory)."
  [dir rel]
  (let [f (fs/file dir rel)]
    (cond
      (fs/regular-file? f) {rel (slurp f)}
      (fs/directory? f) (into {} (for [x (fs/glob f "**") :when (fs/regular-file? x)]
                                   [(str rel "/" (str/replace (str (fs/relativize f x)) "\\" "/")) (slurp (str x))]))
      :else {})))

(defn- at-cut
  "The text of `rel` at the cut spec-3 of the project."
  [ctx rel]
  (mc/git! (:dir ctx) "show" (str "spec-3:" rel)))

(defn tree-of
  "The git tree hash of the implementation paths of `room`, computed in a
  throwaway repository holding a copy of them."
  [room paths]
  (let [repo (s/tmp-dir)]
    (doseq [p paths
            :let [src (fs/file room p)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/file repo p))
        (do (fs/create-dirs (fs/parent (fs/file repo p))) (fs/copy src (fs/file repo p)))))
    (mc/git! repo "init" "-q")
    (mc/git! repo "add" "-A" "-f" ".")
    (mc/git! repo "write-tree")))

;; ---------------------------------------------------------------------------
;; Ratify trusts only the cut

(defn- cut-files
  "{path -> text} of every file of the cut spec-3 outside the implementation."
  [ctx impl]
  (let [names (str/split-lines (mc/git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3"))]
    (into {} (for [n names
                   :when (not-any? #(or (= n %) (str/starts-with? n (str % "/"))) impl)]
               [n (slurp (:out (p/shell {:dir (str (:dir ctx)) :out :bytes :err :string}
                                        "git" "show" (str "spec-3:" n))))]))))

(defstep #"it ratifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (let [r (run ctx)
          b (file ctx build)
          impl (:implementation (s/room-config))]
      (s/check! (fs/directory? b) (str "no checkout at " build "\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file b ".git"))) "the checkout carries history (.git)")
      (let [want (cut-files ctx impl)
            got (into {} (for [x (fs/glob b "**" {:hidden true})
                               :when (fs/regular-file? x)
                               :let [n (str/replace (str (fs/relativize b x)) "\\" "/")]
                               :when (not-any? #(or (= n %) (str/starts-with? n (str % "/"))) impl)]
                           [n (slurp (str x))]))]
        (s/check! (seq want) "the cut holds no files")
        (s/check! (= (set (keys want)) (set (keys got)))
                  (str "the checkout's files are not the cut's: extra "
                       (pr-str (remove (set (keys want)) (keys got))) ", missing "
                       (pr-str (remove (set (keys got)) (keys want)))))
        (doseq [[n t] want]
          (s/check! (= t (get got n)) (str "the checkout's " n " is not the cut's"))))
      ctx)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (let [ctx (fixture! ctx)]
      (fs/delete (file ctx (mc/cycle-path "03" "cycle.edn")))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx]
    (let [r (exits-saying! ctx 2 #"dhf/cycles/03/cycle\.edn" "dhf/cycles/03/cycle.edn")]
      (s/check! (str/blank? (:out r)) "a refusal to run printed a result")
      (s/check! (not (fs/exists? (file ctx ratified))) "a ratified.edn was written"))))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (needs-ratify!)
    (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})]
      (s/write! dir "src/rdm_spec/old.clj" "(ns rdm-spec.old)\n")
      (s/write! dir "src/rdm_spec/demo.clj" "(ns rdm-spec.demo) ;; the cut's\n")
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (fixture! (assoc ctx :dir dir :cycle "03")))))

(defstep #"the checkout it ratifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (s/check! (fs/directory? (file ctx build)) (str "no checkout at " build "\n" (s/show (run ctx))))
    (s/check! (not (fs/exists? (file ctx (str build "/src/rdm_spec/old.clj"))))
              "the checkout still holds the cut's src/rdm_spec/old.clj")))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [paths (:implementation (s/room-config))
          b (file ctx build)]
      (doseq [p paths]
        (s/check! (= (files-under (:room ctx) p) (files-under b p))
                  (str "the checkout's " p " is not the room's: "
                       (pr-str (sort (keys (files-under b p)))) " vs the room's "
                       (pr-str (sort (keys (files-under (:room ctx) p))))))))))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          room (:room ctx)
          cfg (assoc (s/room-config) :test-command ["bb" "-e" "(spit \"room-config-used\" \"yes\")"])]
      (s/write! room "dhf/harness/config.edn" (pr-str cfg))
      (s/write! room mc/lint-feature (mc/feature-text [{:id "spec-0012" :title "Edited by the builder"}]))
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (let [r (run ctx)
          b (file ctx build)]
      (s/check! (zero? (:exit r)) (str "expected the cut's test command to pass\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file b "room-config-used"))) "the room's configuration was used")
      (s/check! (fs/exists? (fs/file b "seen-results-dir")) "the cut's test command did not run")
      (doseq [rel ["dhf/harness/config.edn" mc/lint-feature]]
        (s/check! (= (at-cut ctx rel) (str/trim (slurp (fs/file b rel))))
                  (str "the checkout's " rel " is not the cut's"))))))

;; ---------------------------------------------------------------------------
;; Ratify passes only when the tests pass and every scenario passed

(defstep #"\"rdm-spec (ratify ROOM 03)\" runs the declared test command"
  (fn [ctx line]
    (kc/run-line (fixture! ctx) line)))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in"
  (fn [ctx]
    (let [seen (fs/file (:dir ctx) build "seen-results-dir")
          want (str (fs/file (:dir ctx) "dhf/cycles/03/results"))]
      (s/check! (fs/exists? seen) (str "the test command did not run\n" (s/show (run ctx))))
      (let [got (slurp seen)]
        (s/check! (fs/absolute? got) (str "RESULTS_DIR is not absolute: " got))
        (s/check! (= (str (fs/canonicalize want)) (str (fs/canonicalize got)))
                  (str "RESULTS_DIR was " got ", not " want))))))

(defstep #"the declared test command exits (\d+)"
  (fn [ctx n] (fixture! ctx #(assoc % :exit (parse-long n)))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (fixture! ctx #(assoc % :results (:results default-plan)))))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx]
    (fixture! ctx #(assoc % :results (into (vec (:results default-plan))
                                           [{:spec "spec-0012" :status "passed"}
                                            {:spec "spec-0013" :status "skipped"}])))))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (exits-saying! ctx 1 #"test command .*failed.*exit status 1|exit status 1.*test command.*failed" "that the test command failed with exit status 1")
    (s/check! (= :refused (:outcome (read-ratified ctx))) "ratified.edn does not record a refusal")))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (let [r (run ctx)
          f (file ctx test-log)]
      (s/check! (fs/exists? f) (str "no " test-log "\n" (s/show r)))
      (let [log (slurp f)]
        (s/check! (str/includes? log (:say (:plan ctx))) (str "test.log lacks the test command's output:\n" log))
        (s/check! (str/includes? log "stderr of the fixture's tests") (str "test.log lacks its error stream:\n" log)))
      (s/check! (some #(and (re-find #"test command" %) (str/includes? % test-log)) (s/err-lines r))
                (str "the refusal does not name " test-log "\n" (s/show r))))))

(defstep #"the declared test command exits 0 without writing any Allure result"
  (fn [ctx] (fixture! ctx #(assoc % :exit 0 :results []))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (exits-saying! ctx 1 #"no results is not a run" "a green exit with no results is not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          text (at-cut ctx mc/lint-feature)]
      (s/check! (re-find #"@spec-0012 @e2e\n" (str text "\n")) "spec-0012 is not stamped, or is planned, at the cut")
      ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (fixture! ctx #(update % :results (fn [rs] (vec (remove (comp #{"spec-0012"} :spec) rs)))))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (let [r (exits-saying! ctx 1 #"spec-0012" "spec-0012")]
      (s/check! (not (re-find #"spec-0014" (:err r))) (str "it named spec-0014, which passed\n" (s/show r))))))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (fixture! ctx #(update % :results conj {:spec "spec-0012" :status "failed"}))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (exits-saying! ctx 1 #"spec-0012.*failed" "spec-0012 and its status failed")))

;; ---------------------------------------------------------------------------
;; Ratify records what it ratified

(defstep #"a room whose implementation has a tree hash H"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          room (:room ctx)]
      (s/write! room "bb.edn" "{:paths [\"src\" \"test\"]}\n")
      (s/write! room "src/rdm_spec/more/deep.clj" "(ns rdm-spec.more.deep)\n")
      ;; outside the implementation: never part of its tree
      (s/write! room "notes.txt" "the builder's notes\n")
      (assoc ctx :tree (tree-of room (:implementation (s/room-config)))))))

(defstep #"\"rdm-spec (ratify ROOM 03)\" is run and passes"
  (fn [ctx line]
    (let [ctx (kc/run-line (fixture! ctx) line)]
      (s/check! (zero? (:exit (:run ctx))) (str "expected exit 0\n" (s/show (:run ctx))))
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed and :checkout \"dhf/cycles/03/build\""
  (fn [ctx]
    (let [got (read-ratified ctx)
          want {:cut "spec-3" :room-tree (:tree ctx) :outcome :passed :checkout build}]
      (s/check! (re-matches #"[0-9a-f]{40}" (str (:tree ctx))) (str "H is no full hash: " (:tree ctx)))
      (s/check! (= want got) (str "ratified.edn holds " (pr-str got) ", not " (pr-str want))))))

(defstep #"dhf/cycles/03/build is the ratified checkout, which the grader grades"
  (fn [ctx]
    (let [b (file ctx build)]
      (s/check! (fs/directory? b) (str "no " build))
      (s/check! (not (fs/exists? (fs/file b ".git"))) "the checkout carries history")
      (s/check! (= (slurp (fs/file (:room ctx) "test.clj")) (slurp (fs/file b "test.clj")))
                "the checkout does not hold the room's implementation")
      (s/check! (= (at-cut ctx mc/lint-feature) (str/trim (slurp (fs/file b mc/lint-feature))))
                "the checkout does not hold the cut's spec")
      (s/check! (not (fs/exists? (fs/file b "notes.txt"))) "the checkout holds the room's files outside the implementation"))))

(defstep #"\"rdm-spec (ratify ROOM 03)\" is run and the test command exits 1"
  (fn [ctx line]
    (let [ctx (kc/run-line (fixture! ctx #(assoc % :exit 1)) line)]
      (s/check! (= 1 (:exit (:run ctx))) (str "expected exit 1\n" (s/show (:run ctx))))
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (let [got (read-ratified ctx)]
      (s/check! (= :refused (:outcome got)) (str "ratified.edn holds " (pr-str got)))
      (s/check! (= "spec-3" (:cut got)) (str "ratified.edn names the cut " (pr-str (:cut got))))
      (s/check! (re-find #"test command.*fail" (str (:reason got)))
                (str "ratified.edn gives no reason naming the failed test command: " (pr-str got))))))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)
          r (exits-saying! ctx 1 (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (str file ":" line ":"))))
                           (str file ":" line))]
      (s/check! (= :refused (:outcome (read-ratified ctx))) (str "ratified.edn records no refusal\n" (s/show r))))))
