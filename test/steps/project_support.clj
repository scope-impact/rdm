(ns steps.project-support
  "Fixtures and checks for the project context's step definitions
  (project_init, project_adopt, project_hooks).

  The skeleton is the spec's: the table of dhf/spec/project/init.feature's
  spec-0086, read from the room's spec, never restated here.

  Context keys set by this context's steps:
    :dir        where the command runs (a new directory, or a repository)
    :target     the path init is given, as given
    :before     {path text} of a directory before the run
    :repo-files the files a repository held before adopt, {path text}
    :env        PATH and git's identity, for the hook's scenario
    :hook-log   the file the rdm-spec shim logs each run to"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [steps.support :as s]))

(def project-ns ['rdm-spec.project.init 'rdm-spec.project.adopt 'rdm-spec.project.hooks])

(defn needs-project! [] (apply s/needs-built! project-ns))

(defn skeleton-table
  "The skeleton as spec-0086 states it: [{\"path\" \"holds\"}]."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/project/init.feature"))
        sc (first (filter #(some #{"@spec-0086"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))
        table (some :data-table (:steps sc))]
    (s/check! table "the spec holds no skeleton table (spec-0086)")
    (vec (for [row (rest table)] (zipmap (first table) row)))))

(defn skeleton-paths [] (set (map #(get % "path") (skeleton-table))))

(def git-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn git
  "Runs git in `dir`: {:exit :out :err}."
  [dir env & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                  :extra-env (merge git-env env)}
         "git" args))

(defn git! [dir & args]
  (let [r (apply git dir {} args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn files-of
  "{path text} of every file under `dir` but .git/, symbolic links as
  {:link target}."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true :follow-links false})
              :let [rel (str (fs/relativize dir f))]
              :when (and (not (str/starts-with? rel ".git/")) (not= rel ".git")
                         (or (fs/sym-link? f) (fs/regular-file? f {:nofollow-links true})))]
          [rel (if (fs/sym-link? f) {:link (str (fs/read-link f))} (slurp (str f)))])))

(defn repository!
  "A new git repository holding `files` {path text}, committed."
  [files]
  (let [dir (s/tmp-dir)]
    (git! dir "init" "-q")
    (doseq [[rel text] files] (s/write! dir rel text))
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" "a repository of its own")
    dir))

(defn result-of
  "The run's result under `k` (:init or :adopt); fails when there is none."
  [run k]
  (let [v (try (edn/read-string (:out run)) (catch Exception _ nil))]
    (s/check! (map? (get v k)) (str "standard output holds no {" k " ..}\n" (s/show run)))
    (get v k)))

(defn- lines [text] (vec (remove str/blank? (map str/trim (str/split-lines (str text))))))

(defn- edn-of [dir rel] (edn/read-string (slurp (str (fs/file dir rel)))))

(defn kept-paths
  "The paths spec-0086 says the skeleton's configuration keeps: its row of
  dhf/harness/config.edn names them as \"kept paths (P, P, ...)\"."
  []
  (let [row (first (filter #(= "dhf/harness/config.edn" (get % "path")) (skeleton-table)))
        [_ listed] (re-find #"kept paths \(([^)]*)\)" (str (get row "holds")))]
    (s/check! listed "spec-0086 names no kept paths for dhf/harness/config.edn")
    (mapv str/trim (str/split listed #","))))

(def gitignore-lines
  ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"])

(defn holds!
  "Checks the file `rel` of the project `dir` holds what spec-0086 says it does."
  [dir rel]
  (let [f (fs/file dir rel)
        text (slurp (str f))
        ok! (fn [ok what] (s/check! ok (str rel " does not hold " what ":\n" text)))]
    (case rel
      "dhf/spec/model.edn" (let [m (edn-of dir rel)]
                             (ok! (and (map? m) (= #{:system} (set (keys m)))
                                       (every? #(get-in m [:system %]) [:id :name :description]))
                                  "a model with the system and nothing else"))
      "dhf/spec/events.edn" (ok! (= {} (edn-of dir rel)) "an empty event catalogue")
      "dhf/spec/needs.edn" (ok! (= {} (edn-of dir rel)) "no needs")
      "dhf/spec/ids.edn" (ok! (= {:issued 0} (edn-of dir rel)) "the ledger at 0")
      "dhf/spec/glossary.md" (ok! (and (str/starts-with? text "#") (not-any? #(str/starts-with? % "**") (lines text)))
                                  "an empty glossary")
      "dhf/spec/README.md" (ok! (and (str/includes? text "Conventions") (str/includes? text "@wip")
                                     (str/includes? text "rdm-spec stamp"))
                                "the spec's conventions")
      "dhf/harness/config.edn"
      (let [c (edn-of dir rel)]
        (ok! (string? (:id-prefix c)) "the id prefix")
        (ok! (every? #(string? (get c %)) [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir
                                            :results-dir :cycles-dir])
             "every directory the tasks read")
        (ok! (= {:implementation true :measurements ["dhf/cycles"]} (select-keys (get-in c [:clean-room :deny]) [:implementation :measurements]))
             "a deny-list of the implementation and the measurements dhf/cycles")
        (ok! (some #{"site"} (get-in c [:clean-room :deny :generated])) "the generated site denied")
        (let [kept (kept-paths)]
          (ok! (every? (set (get-in c [:clean-room :keep])) kept) (str "the kept paths " kept)))
        (ok! (= {:watch ["/" "~"] :tmp "/tmp"} (:agency c)) "the watched directories \"/\" and \"~\" and the temporary directory \"/tmp\"")
        (ok! (not (str/includes? text "dhf/harness/oracle")) "no dhf/harness/oracle")
        (ok! (not-any? #(contains? c %) [:implementation :code-layout :test-command]) "no implementation paths"))
      "dhf/harness/stack.md" (ok! (= 2 (count (filter #(str/starts-with? % "|") (lines text))))
                                  "a stack table with no rows yet")
      "dhf/raw/README.md" (ok! (str/includes? text "source") "the rules for raw sources")
      "dhf/raw/sources.edn" (ok! (= {} (edn-of dir rel)) "no sources")
      "dhf/wiki/index.md" (ok! (and (str/includes? text "## Rules") (str/includes? text "## Pages")
                                    (not (re-find #"\]\(" (last (str/split text #"## Pages")))))
                               "the wiki's rules and an empty list of pages")
      "dhf/decisions/README.md" (ok! (str/includes? text "decision") "how decisions are recorded")
      ".githooks/pre-commit" (do (ok! (str/includes? text "rdm-spec check") "a hook that runs rdm-spec check")
                                 (ok! (fs/executable? f) "an executable hook")
                                 (ok! (= ".githooks" (git! dir "config" "--get" "core.hooksPath"))
                                      "core.hooksPath set to .githooks"))
      ".github/workflows/cut.yml" (ok! (and (str/includes? text "rdm-spec cut") (str/includes? text "push")
                                            (str/includes? text "default_branch"))
                                       "a workflow running rdm-spec cut on every push to the default branch")
      ".gitignore" (ok! (every? (set (lines text)) gitignore-lines) (str "the lines " gitignore-lines))
      (s/fail! (str "the skeleton names no " rel)))
    nil))

(defn skeleton-at!
  "Checks `dir` is a git repository holding exactly the skeleton, each file
  holding what the spec says."
  [dir]
  (s/check! (fs/directory? (fs/file dir ".git")) (str dir " is not a git repository"))
  (let [have (set (keys (files-of dir)))
        want (skeleton-paths)]
    (s/check! (= want have) (str dir " holds " (sort have) "\nmissing: " (sort (remove have want))
                                 "\nbeyond the skeleton: " (sort (remove want have))))
    (doseq [rel (sort want)] (holds! dir rel))
    nil))

(defn shim!
  "A directory holding an rdm-spec that logs each run's arguments to `log`
  and runs the room's code by path; returns the directory."
  [log]
  (let [bin (s/tmp-dir)
        f (s/write! bin "rdm-spec"
                    (str "#!/bin/sh\n"
                         "printf '%s\\n' \"$*\" >> '" log "'\n"
                         "exec '" s/bb "' '-Djava.io.tmpdir=" (s/fixture-root) "' -cp '" (fs/file s/room-root "src")
                         "' -m rdm-spec.kernel.command \"$@\"\n"))]
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    bin))
