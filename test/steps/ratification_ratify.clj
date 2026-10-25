(ns steps.ratification-ratify
  "Step definitions for dhf/spec/ratification/ratify.feature.

  The fixture is a throwaway git repository (the project ratify is run in)
  whose tag spec-3 holds the room's allow-listed paths with one small feature
  file (spec-0012, spec-0014, and spec-0013 tagged @wip), and a cycle 03
  naming that cut; and a room, a fresh directory per scenario (:room), whose
  test.clj is the builder's test command (the configured `bb test.clj`). The
  room's test.clj is written from the scenario's fixture options (:fx), so each
  Given that changes what the test command does rewrites it:

    :exit     the status it exits with (0)
    :write?   whether it writes any result (true)
    :omit     ids it writes no result for (#{})
    :status   {id status} for results that do not pass ({})
    :env-file a file it records its RESULTS_DIR and working directory in
    :in-cut?  the test command lives in the cut (ratify-fixture.clj), not in
              the room, so the room's implementation can be empty

  Two Givens this feature uses are defined in steps.measurement-cycle (\"dhf/
  cycles/03/cycle.edn names the cut spec-3\" and \"the spec at cut spec-3 holds
  a scenario with no id\"); the command is run by steps.kernel-command."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}
   {:id "spec-0014" :title "A view with a label fails the lint"}])

(def ids ["spec-0012" "spec-0013" "spec-0014"])

(defn test-script
  "The builder's test command for the fixture: writes one Allure result per
  id into RESULTS_DIR as the options say, then exits."
  [{:keys [exit write? omit status env-file] :or {exit 0 write? true omit #{} status {}}}]
  (let [written (if write? (vec (remove (set omit) ids)) [])]
    (str "(require '[babashka.fs :as fs] '[cheshire.core :as json])\n"
         "(let [dir (System/getenv \"RESULTS_DIR\")]\n"
         (when env-file
           (str "  (spit " (pr-str env-file) " (pr-str {:results-dir dir :cwd (str (fs/cwd))}))\n"))
         "  (when dir (fs/create-dirs dir))\n"
         "  (doseq [[id st] " (pr-str (mapv #(vector % (get status % "passed")) written)) "]\n"
         "    (spit (str (fs/file dir (str (java.util.UUID/randomUUID) \"-result.json\")))\n"
         "          (json/generate-string {:uuid id :name (str \"a test of \" id) :status st :stage \"finished\"\n"
         "                                 :statusDetails (if (= \"passed\" st) {} {:message \"it did not pass\"})\n"
         "                                 :steps [] :labels [{:name \"spec\" :value id}]}))))\n"
         "(System/exit " exit ")\n")))

(defn- set-test-command!
  "Declares the fixture's test command in the cut's configuration."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")
        c (edn/read-string (slurp (str f)))]
    (spit (str f) (pr-str (assoc c :test-command ["bb" "ratify-fixture.clj"])))))

(defn fx!
  "The scenario's fixture with the options `opts` added: the project in :dir
  (made, cut at spec-3 and given cycle 03 when the scenario has none yet), the
  room in :room, and the test command written from every option so far."
  [ctx opts]
  (let [fx (merge (:fx ctx) opts)
        new? (nil? (:dir ctx))
        dir (or (:dir ctx) (mc/spec-project! {mc/lint-feature (mc/feature-text scenarios)}))
        room (or (:room ctx) (s/tmp-dir))]
    (if (:in-cut? fx)
      (do (when (or new? (not= (:fx ctx) fx))
            (set-test-command! dir)
            (s/write! dir "ratify-fixture.clj" (test-script fx))
            (mc/cut! dir "spec-3")))
      (do (s/write! room "test.clj" (test-script fx))
          (s/write! room "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")))
    (when new? (mc/write-cycle! dir "03" {}))
    (assoc ctx :dir (str dir) :room (str room) :fx fx :cycle "03")))

(defn- git-out [dir & args]
  (str/trim (apply mc/git! dir args)))

(defn- cycle-file [ctx name] (fs/file (:dir ctx) (mc/cycle-path "03" name)))

(defn- build [ctx] (cycle-file ctx "build"))

(defn read-ratified [ctx]
  (let [f (cycle-file ctx "ratified.edn")]
    (s/check! (fs/exists? f) (str "no dhf/cycles/03/ratified.edn\n" (s/show (:run ctx))))
    (edn/read-string (slurp (str f)))))

(defn tree
  "Every file under `root`/`p` (a file or a directory) but .git, relative to
  `root`, with its content."
  [root p]
  (let [f (fs/file root p)]
    (cond (fs/regular-file? f) {p (slurp (str f))}
          (fs/directory? f) (into {} (for [x (fs/glob f "**" {:hidden true})
                                           :let [rel (str (fs/relativize root x))]
                                           :when (and (fs/regular-file? x)
                                                      (not (re-find #"(^|/)\.git/" rel)))]
                                       [rel (slurp (str x))]))
          :else {})))

(defn implementation [ctx]
  (:implementation (edn/read-string (git-out (:dir ctx) "show" "spec-3:dhf/harness/config.edn"))))

(defn holds-the-room!
  "Fails unless every configured implementation path of the build holds
  exactly what the room holds."
  [ctx]
  (doseq [p (implementation ctx)
          :let [in-build (tree (str (build ctx)) p)
                in-room (tree (:room ctx) p)]]
    (s/check! (= in-room in-build)
              (str "the checkout's " p " holds " (vec (sort (keys in-build)))
                   ", the room's " (vec (sort (keys in-room)))))))

(defn checkout-of-spec-3!
  "Fails unless dhf/cycles/03/build is a checkout of the commit spec-3 tags,
  whose every file outside the implementation is the cut's, unedited."
  [ctx]
  (let [b (str (build ctx))
        impl (implementation ctx)
        in-impl? (fn [rel] (some #(or (= rel %) (str/starts-with? rel (str % "/"))) impl))]
    (s/check! (fs/directory? b) (str "no dhf/cycles/03/build\n" (s/show (:run ctx))))
    (s/check! (= (git-out (:dir ctx) "rev-parse" "spec-3^{commit}") (git-out b "rev-parse" "HEAD"))
              "the checkout's HEAD is not the commit spec-3 tags")
    (doseq [rel (remove in-impl? (str/split-lines (git-out (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))]
      (let [f (fs/file b rel)]
        (s/check! (fs/exists? f) (str "the checkout holds no " rel))
        (s/check! (= (git-out (:dir ctx) "show" (str "spec-3:" rel)) (str/trim (slurp (str f))))
                  (str "the checkout's " rel " is not the cut's"))))))

(defn- refused-with! [ctx code & res]
  (let [r (:run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    (doseq [re res]
      (s/check! (re-find re (:err r)) (str "the error stream does not say " re "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Ratify trusts only the cut

(defstep #"it ratifies against a fresh checkout of spec-3"
  (fn [ctx]
    (s/check! (:room ctx) "the scenario made no room (:room): ROOM would be a fixed path")
    (s/check! (#{0 1} (:exit (:run ctx))) (s/show (:run ctx)))
    (s/check! (= "spec-3" (:cut (read-ratified ctx))) "ratified.edn names another cut")
    (checkout-of-spec-3! ctx)))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx]
    (let [ctx (fx! ctx {})]
      (fs/delete-tree (fs/file (:dir ctx) "dhf/cycles/03"))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx]
    (refused-with! ctx 2 #"dhf/cycles/03/cycle\.edn")
    (s/check! (not (fs/exists? (build ctx))) "ratify made a checkout")))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (fx! ctx {})]
      (s/write! (:dir ctx) "src/rdm_spec/old.clj" "(ns rdm-spec.old)\n")
      (s/write! (:dir ctx) "test/rdm_spec/old_test.clj" "(ns rdm-spec.old-test)\n")
      (mc/cut! (:dir ctx) "spec-3")
      (s/check! (not (fs/exists? (fs/file (:room ctx) "src/rdm_spec/old.clj"))) "the room holds old.clj")
      ;; an earlier, stale build of the cycle: ratify starts again from the cut
      (s/write! (:dir ctx) "dhf/cycles/03/build/src/rdm_spec/stale.clj" "(ns rdm-spec.stale)\n")
      ctx)))

(defstep #"the checkout it ratifies holds no src/rdm_spec/old.clj"
  (fn [ctx]
    (s/check! (zero? (:exit (:run ctx))) (s/show (:run ctx)))
    (checkout-of-spec-3! ctx)
    (doseq [p ["src/rdm_spec/old.clj" "test/rdm_spec/old_test.clj" "src/rdm_spec/stale.clj"]]
      (s/check! (not (fs/exists? (fs/file (build ctx) p))) (str "the checkout holds " p)))))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx] (holds-the-room! ctx)))

(def edited-feature
  "The room's edit to the lint feature: a stamped scenario the cut does not
  hold, so a run that read the room's spec would find no result for it."
  "\n    @spec-0099 @e2e\n    Scenario: A scenario only the room holds\n      Given a model\n")

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (fx! ctx {})
          room (:room ctx)
          config (edn/read-string (git-out (:dir ctx) "show" "spec-3:dhf/harness/config.edn"))]
      ;; the room's configuration declares a test command that writes no result
      (s/write! room "dhf/harness/config.edn"
                (pr-str (assoc config :test-command ["bb" "-e" "(System/exit 0)"] :implementation ["src"])))
      (s/write! room mc/lint-feature (str (git-out (:dir ctx) "show" (str "spec-3:" mc/lint-feature)) "\n" edited-feature))
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (s/check! (zero? (:exit (:run ctx))) (str "ratify did not pass on the cut's configuration and spec\n" (s/show (:run ctx))))
    (checkout-of-spec-3! ctx)
    (doseq [rel ["dhf/harness/config.edn" mc/lint-feature]]
      (s/check! (not= (slurp (str (fs/file (:room ctx) rel))) (slurp (str (fs/file (build ctx) rel))))
                (str "the checkout's " rel " is the room's")))))

;; ---------------------------------------------------------------------------
;; Ratify passes only when the tests pass and every scenario passed

(defstep #"\"rdm-spec (ratify ROOM 03)\" runs the declared test command"
  (fn [ctx line]
    (let [env-file (str (fs/file (s/tmp-dir) "env.edn"))
          ctx (fx! ctx {:env-file env-file})]
      (kc/run-line ctx line))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in"
  (fn [ctx]
    (let [f (get-in ctx [:fx :env-file])]
      (s/check! (fs/exists? f) (str "the test command did not run\n" (s/show (:run ctx))))
      (let [{:keys [results-dir cwd]} (edn/read-string (slurp f))
            want (fs/file (:dir ctx) "dhf/cycles/03/results")]
        (s/check! (string? results-dir) "the test command ran with no RESULTS_DIR")
        (s/check! (fs/absolute? results-dir) (str "RESULTS_DIR is not absolute: " results-dir))
        (s/check! (= (str (fs/canonicalize want)) (str (fs/canonicalize results-dir)))
                  (str "RESULTS_DIR is " results-dir ", not " want))
        (s/check! (= (str (fs/canonicalize (build ctx))) (str (fs/canonicalize cwd)))
                  (str "the test command ran in " cwd ", not in the checkout"))))))

(defstep #"the declared test command exits (\d+)"
  (fn [ctx code] (fx! ctx {:exit (parse-long code)})))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (fx! ctx {:write? true})))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (fx! ctx {:omit #{} :status {}})))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (refused-with! ctx 1 #"test command" #"failed" #"exit status 1\b")))

(defstep #"the declared test command exits 0 without writing any Allure result"
  (fn [ctx] (fx! ctx {:exit 0 :write? false})))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx]
    (refused-with! ctx 1 #"no (Allure )?results?" #"not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (fx! ctx {})
          text (git-out (:dir ctx) "show" (str "spec-3:" mc/lint-feature))]
      (s/check! (re-find #"@spec-0012 @e2e\n" text) (str "spec-0012 is not a stamped scenario without @wip:\n" text))
      ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (fx! ctx {:omit (conj (get-in ctx [:fx :omit] #{}) "spec-0012")})))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (refused-with! ctx 1 #"spec-0012")
    (s/check! (not (re-find #"spec-0014" (:err (:run ctx)))) "it names spec-0014, which passed")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (fx! ctx {:status (assoc (get-in ctx [:fx :status] {}) "spec-0012" "failed")})))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (refused-with! ctx 1 #"spec-0012.*\bfailed\b")))

;; ---------------------------------------------------------------------------
;; Ratify records what it ratified

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defstep #"the room's implementation has the tree hash 4b825dc"
  (fn [ctx]
    (let [ctx (fx! ctx {:in-cut? true})]
      (s/check! (= empty-tree (str/trim (:out (p/shell {:out :string :in ""} "git" "hash-object" "-t" "tree" "--stdin"))))
                "the empty tree's hash is not 4b825dc")
      (doseq [p (implementation ctx)]
        (s/check! (not (fs/exists? (fs/file (:room ctx) p))) (str "the room holds " p)))
      ctx)))

(defstep #"\"rdm-spec (ratify ROOM 03)\" is run and passes"
  (fn [ctx line]
    (let [ctx (kc/run-line ctx line)]
      (s/check! (zero? (:exit (:run ctx))) (s/show (:run ctx)))
      ctx)))

(defstep #"dhf/cycles/03/ratified.edn holds:"
  (fn [ctx doc]
    (let [want (edn/read-string doc)
          got (read-ratified ctx)]
      (s/check! (= want got) (str "ratified.edn holds " (pr-str got) "\nexpected " (pr-str want))))))

(defstep #"dhf/cycles/03/build is the ratified checkout, which the grader grades"
  (fn [ctx]
    (s/check! (= "dhf/cycles/03/build" (:checkout (read-ratified ctx))) "ratified.edn names another checkout")
    (checkout-of-spec-3! ctx)
    (holds-the-room! ctx)
    (s/check! (seq (fs/glob (cycle-file ctx "results") "*-result.json")) "the cycle holds no results")))

(defstep #"\"rdm-spec (ratify ROOM 03)\" is run and the test command exits 1"
  (fn [ctx line]
    (kc/run-line (fx! ctx {:exit 1}) line)))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (let [r (read-ratified ctx)]
      (s/check! (= 1 (:exit (:run ctx))) (s/show (:run ctx)))
      (s/check! (= :refused (:outcome r)) (str "ratified.edn holds " (pr-str r)))
      (s/check! (and (string? (:reason r)) (re-find #"test command.*exit status 1\b" (:reason r)))
                (str "the reason does not say the test command exited 1: " (pr-str (:reason r))))
      (s/check! (= "spec-3" (:cut r)) (str "ratified.edn names the cut " (pr-str (:cut r)))))))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)]
      (s/check! (:room ctx) "the scenario made no room (:room): ROOM would be a fixed path")
      (refused-with! ctx 1 (re-pattern (str "(^|\\s|/)" (java.util.regex.Pattern/quote (str file ":" line ":")))))
      (s/check! (= :refused (:outcome (read-ratified ctx))) "ratified.edn does not record the refusal"))))
