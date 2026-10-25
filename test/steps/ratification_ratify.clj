(ns steps.ratification-ratify
  "Step definitions for dhf/spec/ratification/ratify.feature.

  A ratify fixture is a cycle's project (the shared cycle steps' shape, see
  steps.measurement-cycle): a copy of the room's allow-list whose spec is the
  feature file of the component Lint holding spec-0012 and spec-0014 (and
  spec-0013, planned), made a git repository and cut as spec-3, with
  dhf/cycles/03/cycle.edn written after the cut. ROOM is a directory of the
  scenario's own holding the builder's implementation: here a test.clj that
  stands for the builder's tests (`room-test!`). It records the RESULTS_DIR it
  was given in results-dir.txt, writes the Allure results the scenario asks
  for into it and exits with the scenario's status.

  Context keys, besides the shared ones (:dir :room :cycle :run):
    :tc {:exit n :results [{:id :status}]}  what the room's test command does
    :unstamped {:file :line}                 set by the shared Given
    :edited [path ...]                       files the builder edited in ROOM

  The helpers below are copied from steps.measurement-cycle, which owns the
  shared cycle Givens; this file defines none of their texts."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- needs-ratify! [] (s/needs-built! 'rdm-spec.ratification.ratify))

;; ---------------------------------------------------------------------------
;; Copied from steps.measurement-cycle

(def lint-feature "dhf/spec/architecture/lint.feature")

(defn cycle-path [cycle & parts]
  (str/join "/" (concat ["dhf/cycles" cycle] parts)))

(defn scenario-text [{:keys [id title level wip?]}]
  (str "\n    " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n    Scenario: " (or title (str "Scenario " id))
       "\n      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n"))

(defn feature-text
  ([scenarios] (feature-text "Lint" scenarios))
  ([feature scenarios]
   (str "@UN-005\nFeature: " feature "\n  A feature for the measurement's tests.\n\n  Rule: One rule\n"
        (str/join (map scenario-text scenarios)))))

(def git-env
  {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null" "GIT_TERMINAL_PROMPT" "0"
   "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
   "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"})

(defn git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut! [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q"))
  (git! dir "add" "-A" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the spec at " tag))
  (git! dir "tag" "-f" tag)
  dir)

(def default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn write-cycle! [dir cycle overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (s/write! dir (cycle-path cycle "cycle.edn") (pr-str m))))

(def default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(defn project!
  "A copy of the room's allow-list whose spec is only `files` ({path text}),
  with `extra` ({path text}) beside it, cut as spec-3."
  ([files] (project! files {}))
  ([files extra]
   (let [dir (s/copy-of-room!)]
     (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
     (doseq [[f t] (merge files extra)] (s/write! dir f t))
     (cut! dir "spec-3")
     dir)))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- exits! [ctx code]
  (s/check! (= code (:exit (the-run ctx))) (str "expected exit " code "\n" (s/show (the-run ctx)))))

(defn- says! [ctx re what]
  (s/check! (re-find re (or (:err (the-run ctx)) ""))
            (str "the error stream does not say " what "\n" (s/show (the-run ctx)))))

(defn- read-edn-file [ctx rel]
  (let [f (fs/file (:dir ctx) rel)]
    (s/check! (fs/exists? f) (str "no " rel "\n" (s/show (the-run ctx))))
    (edn/read-string (slurp (str f)))))

;; ---------------------------------------------------------------------------
;; Fixtures

(def stamped-ids
  "The default spec's stamped scenarios not tagged @wip."
  (mapv :id (remove :wip? default-scenarios)))

(defn- all-passed [] (mapv (fn [id] {:id id :status "passed"}) stamped-ids))

(defn test-script
  "A test.clj that records RESULTS_DIR in results-dir.txt, writes `results`
  ([{:id :status}]) there as Allure results and exits `exit`."
  [{:keys [exit results]}]
  (str "(require '[cheshire.core :as json])\n"
       "(let [dir (System/getenv \"RESULTS_DIR\")\n"
       "      results " (pr-str (vec results)) "]\n"
       "  (spit \"results-dir.txt\" (str dir))\n"
       "  (when dir\n"
       "    (.mkdirs (java.io.File. dir))\n"
       "    (doseq [[i {:keys [id status]}] (map-indexed vector results)]\n"
       "      (spit (str dir \"/fixture-\" i \"-result.json\")\n"
       "            (json/generate-string {:uuid (str \"fixture-\" i) :name (str \"test of \" id) :status status\n"
       "                                   :stage \"finished\" :steps []\n"
       "                                   :statusDetails (if (= \"passed\" status) {} {:message \"it did not pass\"})\n"
       "                                   :labels [{:name \"spec\" :value id}]}))))\n"
       "  (System/exit " (or exit 0) "))\n"))

(defn room-test!
  "Writes the room's test.clj from ctx :tc; returns ctx."
  [ctx]
  (s/write! (:room ctx) "test.clj" (test-script (:tc ctx)))
  ctx)

(defn ratify-project!
  "A cut project (the default spec, plus `extra` files at the cut), its
  cycle.edn and a room whose tests do `tc`."
  ([ctx tc] (ratify-project! ctx tc {}))
  ([ctx tc extra]
   (needs-ratify!)
   (let [dir (project! {lint-feature (feature-text default-scenarios)} extra)]
     (write-cycle! dir "03" {})
     (room-test! (assoc ctx :dir dir :room (s/tmp-dir) :cycle "03" :tc tc)))))

(defn- ensure-project
  "The context's project, made with an empty test plan when there is none."
  [ctx]
  (if (:dir ctx) ctx (ratify-project! ctx {:exit 0 :results []})))

(defn- update-tc [ctx f & args]
  (let [ctx (ensure-project ctx)]
    (room-test! (apply update ctx :tc f args))))

(defn- ratify! [ctx] (kc/run-line ctx "ratify ROOM 03"))

(defn- tree
  "{relative-path bytes-as-vector} of every file under `base`/`rel`."
  [base rel]
  (let [f (fs/file base rel)]
    (cond
      (fs/directory? f) (into {} (for [x (fs/glob f "**" {:hidden true}) :when (fs/regular-file? x)]
                                   [(str/replace (str (fs/relativize base x)) "\\" "/") (vec (fs/read-all-bytes x))]))
      (fs/regular-file? f) {rel (vec (fs/read-all-bytes f))}
      :else {})))

(defn- at-cut [ctx path] (git! (:dir ctx) "show" (str "spec-3:" path)))

(def build-path (cycle-path "03" "build"))

(defn- build-file [ctx rel] (fs/file (:dir ctx) build-path rel))

(defn- cut-config [ctx] (edn/read-string (at-cut ctx "dhf/harness/config.edn")))

;; ---------------------------------------------------------------------------
;; Rule: Ratify trusts only the cut

(defstep #"it ratifies against a fresh checkout of spec-3"
  (fn [ctx]
    (needs-ratify!)
    (s/check! (#{0 1} (:exit (the-run ctx))) (str "ratify did not run\n" (s/show (the-run ctx))))
    (let [r (read-edn-file ctx (cycle-path "03" "ratified.edn"))
          files (str/split-lines (git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3"))]
      (s/check! (= "spec-3" (:cut r)) (str "ratified.edn names the cut " (pr-str (:cut r))))
      (s/check! (= build-path (:checkout r)) (str "ratified.edn names the checkout " (pr-str (:checkout r))))
      (doseq [f files]
        (s/check! (fs/exists? (build-file ctx f)) (str "the checkout lacks " f " of spec-3"))
        (s/check! (= (at-cut ctx f) (str/trim (slurp (build-file ctx f))))
                  (str "the checkout's " f " is not spec-3's")))
      (s/check! (not (fs/exists? (build-file ctx (cycle-path "03" "cycle.edn"))))
                "the checkout holds cycle.edn, written after the cut: it is no fresh checkout"))))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (let [ctx (ratify-project! ctx {:exit 0 :results (all-passed)})]
      (fs/delete (fs/file (:dir ctx) (cycle-path "03" "cycle.edn")))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx]
    (exits! ctx 2)
    (says! ctx #"dhf/cycles/03/cycle\.edn" "dhf/cycles/03/cycle.edn")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) build-path))) "it made a checkout anyway")))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (ratify-project! ctx {:exit 0 :results (all-passed)}
                               {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"
                                "test/rdm_spec/old_test.clj" "(ns rdm-spec.old-test)\n"})]
      (s/write! (:room ctx) "src/rdm_spec/new.clj" "(ns rdm-spec.new)\n")
      (s/write! (:room ctx) "bb.edn" "{:paths [\"src\"]}\n")
      ctx)))

(defstep #"the checkout it ratifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (exits! ctx 0)
    (s/check! (fs/exists? (build-file ctx "src/rdm_spec/new.clj")) "the checkout lacks the room's src/rdm_spec/new.clj")
    (s/check! (not (fs/exists? (build-file ctx "src/rdm_spec/old.clj"))) "the checkout still holds src/rdm_spec/old.clj")))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [build (fs/file (:dir ctx) build-path)
          paths (:implementation (cut-config ctx))]
      (s/check! (seq paths) "the cut's configuration names no implementation path")
      (doseq [p paths
              :let [want (tree (:room ctx) p)
                    ;; the room's tests write results-dir.txt beside them, outside every path
                    got (tree build p)]]
        (s/check! (= (set (keys want)) (set (keys got)))
                  (str p ": the checkout holds " (pr-str (sort (keys got))) ", the room " (pr-str (sort (keys want)))))
        (doseq [[f bytes] want]
          (s/check! (= bytes (get got f)) (str f " differs from the room's")))))))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (ratify-project! ctx {:exit 0 :results (all-passed)})
          room (:room ctx)
          config (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn"))))]
      ;; a clean room holds the cut's allowed paths; the builder edits two of them
      (fs/copy-tree (fs/file (:dir ctx) "dhf") (fs/file room "dhf"))
      (fs/delete-tree (fs/file room "dhf/cycles"))
      ;; a test command that does not exist, and a planned scenario made due
      (s/write! room "dhf/harness/config.edn"
                (pr-str (assoc config :test-command ["bb" "edited.clj"] :implementation ["src" "test.clj" "dhf/spec"])))
      (s/write! room lint-feature
                (feature-text (conj (mapv #(dissoc % :wip?) default-scenarios)
                                    {:id "spec-0999" :title "A scenario the builder added"})))
      (assoc ctx :edited ["dhf/harness/config.edn" lint-feature]))))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (doseq [f (:edited ctx)]
      (s/check! (= (at-cut ctx f) (str/trim (slurp (build-file ctx f))))
                (str "the checkout's " f " is not spec-3's")))
    (s/check! (fs/exists? (build-file ctx "results-dir.txt"))
              (str "the cut's test command (bb test.clj) did not run\n" (s/show (the-run ctx))))
    (s/check! (not (re-find #"spec-0013|spec-0999" (str (:err (the-run ctx)))))
              (str "ratify read the room's spec\n" (s/show (the-run ctx))))
    (exits! ctx 0)))

;; ---------------------------------------------------------------------------
;; Rule: Ratify passes only when the tests pass and every scenario passed

(defstep #"\"rdm-spec ratify ROOM 03\" runs the declared test command"
  (fn [ctx]
    (ratify! (ratify-project! ctx {:exit 0 :results (all-passed)}))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in"
  (fn [ctx]
    (let [f (build-file ctx "results-dir.txt")]
      (s/check! (fs/exists? f) (str "the test command did not run\n" (s/show (the-run ctx))))
      (let [given (str/trim (slurp (str f)))
            want (fs/file (:dir ctx) (cycle-path "03" "results"))]
        (s/check! (fs/absolute? given) (str "RESULTS_DIR is not absolute: " (pr-str given)))
        (s/check! (= (str (fs/canonicalize want)) (str (fs/canonicalize given)))
                  (str "RESULTS_DIR was " given ", not " want))
        (s/check! (seq (fs/glob want "*-result.json")) "no result was written there")
        (exits! ctx 0)))))

(defstep #"the declared test command exits (\d+)"
  (fn [ctx code]
    (update-tc ctx assoc :exit (parse-long code))))

(defstep #"the declared test command exits 0 without writing any Allure result"
  (fn [ctx]
    (update-tc ctx assoc :exit 0 :results [])))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (update-tc ctx assoc :results (all-passed))))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx]
    (update-tc ctx update :results
               (fn [rs] (vec (concat rs
                                     ;; a second passing result for one, and none for the planned one
                                     [{:id "spec-0012" :status "passed"}]
                                     (for [id stamped-ids :when (not (some #(= id (:id %)) rs))]
                                       {:id id :status "passed"})))))))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"test command .*fail.*exit status 1\b" "the test command failed with exit status 1")))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"no (Allure )?results?\b.*not a run" "a green exit with no results is not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (ensure-project ctx)
          text (at-cut ctx lint-feature)]
      (s/check! (re-find #"(?m)^\s*@spec-0012 @e2e$" text) "spec-0012 is not stamped, or is planned")
      ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx]
    (update-tc ctx assoc :exit 0 :results (vec (remove #(= "spec-0012" (:id %)) (all-passed))))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"(?m)^dhf/spec/architecture/lint\.feature:\d+: \S+: .*spec-0012" "spec-0012, where it is")
    (s/check! (not (re-find #"spec-0014" (:err (the-run ctx)))) "it names spec-0014, which passed")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (update-tc ctx assoc :results [{:id "spec-0012" :status "passed"}
                                   {:id "spec-0012" :status "failed"}
                                   {:id "spec-0014" :status "passed"}])))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"(?m)^\S+: \S+: .*spec-0012.*\bfailed\b" "spec-0012 and failed")))

;; ---------------------------------------------------------------------------
;; Rule: Ratify records what it ratified

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defstep #"the room's implementation has the tree hash ([0-9a-f]{40})"
  (fn [ctx hash]
    (s/check! (= empty-tree hash) (str "the fixture can make only the empty tree, not " hash))
    ;; an empty implementation: the cut's own test command writes the results
    (let [script (str "(require '[cheshire.core :as json])"
                      "(let [d (System/getenv \"RESULTS_DIR\")] (.mkdirs (java.io.File. d))"
                      " (doseq [id " (pr-str stamped-ids) "]"
                      "  (spit (str d \"/\" id \"-result.json\")"
                      "   (json/generate-string {:name id :status \"passed\" :labels [{:name \"spec\" :value id}]}))))")
          config (assoc (s/room-config) :test-command ["bb" "-e" script])
          ctx (ratify-project! ctx {:exit 0 :results []}
                               {"dhf/harness/config.edn" (pr-str config)})]
      (fs/delete-tree (:room ctx))
      (fs/create-dirs (:room ctx))
      ctx)))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (ratify! ctx)]
      (exits! ctx 0)
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds:"
  (fn [ctx doc]
    (let [want (edn/read-string doc)
          got (read-edn-file ctx (cycle-path "03" "ratified.edn"))]
      (s/check! (= want got) (str "ratified.edn holds " (pr-str got) ", not " (pr-str want))))))

(defstep #"dhf/cycles/03/build is the ratified checkout, which the grader grades"
  (fn [ctx]
    (let [files (str/split-lines (git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3"))]
      (doseq [f files]
        (s/check! (= (at-cut ctx f) (str/trim (slurp (build-file ctx f))))
                  (str "dhf/cycles/03/build/" f " is not spec-3's")))
      (s/check! (fs/exists? (fs/file (:dir ctx) (cycle-path "03" "results")))
                "the ratified run's results are not beside it"))))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and the test command exits 1"
  (fn [ctx]
    (ratify! (ratify-project! ctx {:exit 1 :results (all-passed)}))))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (exits! ctx 1)
    (let [r (read-edn-file ctx (cycle-path "03" "ratified.edn"))]
      (s/check! (= :refused (:outcome r)) (str "ratified.edn holds " (pr-str r)))
      (s/check! (= "spec-3" (:cut r)) (str "ratified.edn names the cut " (pr-str (:cut r))))
      (s/check! (re-find #"test command .*exit status 1\b" (str (:reason r)))
                (str "the reason does not say the test command failed: " (pr-str (:reason r)))))))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (needs-ratify!)
    (exits! ctx 1)
    (let [{:keys [file line]} (:unstamped ctx)]
      (says! ctx (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (str file ":" line ":")) " "))
             (str file ":" line))
      (s/check! (= :refused (:outcome (read-edn-file ctx (cycle-path "03" "ratified.edn"))))
                "ratified.edn does not record the refusal"))))
