(ns steps.ratification-support
  "Helpers the ratification context's step files share. No step is defined
  here. (The kernel's cycle helpers are private to it, so what is needed is
  copied in: a step file's helpers are its own.)

  A cycle project is a copy of the room (its configuration without :oracle,
  its test command the real one), every feature file removed and the
  fixture's lint feature written instead (spec-0012, spec-0014, and
  spec-0013 tagged @wip), committed and tagged spec-3, with
  dhf/cycles/03/cycle.edn written after the cut.

  A room is a directory holding src/rdm_spec/demo.clj and a test.clj that,
  run by the declared test command, prints a line, writes RESULTS_DIR's
  value to ratify-env.txt in its working directory, writes the Allure
  results it is given into RESULTS_DIR and exits with the code it is given.

  Helpers: git!, lint-feature, scenario-ids, cycle-project!, room!,
  write-tests!, tree-hash, implementation-files, files-under."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [steps.support :as s]))

(def lint-feature "dhf/spec/architecture/lint.feature")

(def scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def stamped-not-planned
  "The fixture's stamped scenarios not tagged @wip."
  ["spec-0012" "spec-0014"])

(defn git!
  "git `args` in `dir`, with an identity; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- scenario-text [{:keys [id title level wip?]}]
  (str "\n  " (str/join " " (remove nil? [(str "@" id) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn feature-text []
  (str "# A fixture feature file of the ratification steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn cycle-project!
  "A cycle project; `edit!` is applied to it before the cut. Returns its
  directory."
  ([] (cycle-project! (fn [_])))
  ([edit!]
   (let [dir (s/copy-of-room!)]
     (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
     (s/write! dir lint-feature (feature-text))
     (edit! dir)
     (git! dir "init" "-q")
     (git! dir "config" "user.name" "steps")
     (git! dir "config" "user.email" "steps@rdm-spec.invalid")
     (git! dir "add" "-A" "-f" "--" ".")
     (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
     (git! dir "tag" "spec-3")
     (s/write! dir "dhf/cycles/03/cycle.edn"
               (pr-str {:cut "spec-3" :scope :project
                        :builder {:name "builder-a" :actor :agent}
                        :grader {:name "grader-b" :actor :agent}
                        :spec-owner {:name "Sami" :actor :human}}))
     dir)))

(defn- result-json [id status]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       "\"steps\":[],"
       (when-not (= "passed" status) "\"statusDetails\":{\"message\":\"it did not pass\"},")
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defn write-tests!
  "Writes the room's test.clj: it exits `exit` after writing `results`
  ([[id status]]) into RESULTS_DIR."
  [room {:keys [exit results]}]
  (s/write! room "test.clj"
            (str ";; the fixture's tests\n"
                 "(require '[clojure.java.io :as io])\n"
                 "(println \"the fixture's tests ran, exiting " exit "\")\n"
                 "(spit \"ratify-env.txt\" (str (System/getenv \"RESULTS_DIR\")))\n"
                 "(let [d (System/getenv \"RESULTS_DIR\")]\n"
                 "  (.mkdirs (io/file d))\n"
                 (str/join (for [[id status] results]
                             (str "  (spit (io/file d (str (random-uuid) \"-result.json\")) "
                                  (pr-str (result-json id status)) ")\n")))
                 ")\n"
                 "(System/exit " exit ")\n")))

(defn room!
  "A new room, its tests as `tests` ({:exit :results})."
  [tests]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (write-tests! r tests)
    r))

(defn files-under
  "{rel text} of every file under `dir`, outside .git."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [r (str/replace (str (fs/relativize dir f)) "\\" "/")]
              :when (and (fs/regular-file? f) (not (or (= ".git" r) (str/starts-with? r ".git/"))))]
          [r (slurp (str f))])))

(defn implementation?
  "Whether `rel` lies under one of the implementation paths `impl`."
  [impl rel]
  (boolean (some #(or (= rel %) (str/starts-with? rel (str % "/"))) impl)))

(defn tree-hash
  "The git tree hash of the implementation paths `impl` of the room: a
  repository holding exactly those files, committed."
  [room impl]
  (let [d (s/tmp-dir)]
    (doseq [[r t] (files-under room) :when (implementation? impl r)]
      (s/write! d r t))
    (git! d "init" "-q")
    (git! d "add" "-A" "-f" "--" ".")
    (git! d "write-tree")))
