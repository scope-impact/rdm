(ns steps.measurement-cycle
  "Fixtures and steps the measurement features share with ratify and the
  parser: a cycle's project is a copy of the room's allow-list, made a git
  repository whose tag spec-3 holds a small spec (the feature file of the
  component Lint, `lint-feature`), with dhf/cycles/03/cycle.edn written after
  the cut, so a checkout of the cut never holds it. Its test command is the
  real one; the scenario's room (:room) is an empty directory.

  Helpers others use: `lint-feature`, `feature-text`, `cut!` (makes the
  directory a git repository when it is not one, commits everything and tags
  it, moving the tag), `write-cycle!` and `cycle-path`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

(def lint-feature "dhf/spec/architecture/lint.feature")

(defn cycle-path
  "\"dhf/cycles/03/build\" for (cycle-path \"03\" \"build\")."
  [cycle & parts]
  (str/join "/" (concat ["dhf/cycles" cycle] parts)))

(defn scenario-text
  "One scenario: {:id :title :level (e2e) :wip?}; no :id, an unstamped one."
  [{:keys [id title level wip?]}]
  (str "\n    " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n    Scenario: " (or title (str "Scenario " id))
       "\n      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n"))

(defn feature-text
  "The text of the feature file of the component Lint holding `scenarios`."
  ([scenarios] (feature-text "Lint" scenarios))
  ([feature scenarios]
   (str "@UN-005\nFeature: " feature "\n  A feature for the measurement's tests.\n\n  Rule: One rule\n"
        (str/join (map scenario-text scenarios)))))

(defn line-of
  "The line of `title`'s Scenario in `text`."
  [text title]
  (some (fn [[i l]] (when (= (str "    Scenario: " title) l) (inc i)))
        (map-indexed vector (str/split-lines text))))

;; --- git --------------------------------------------------------------------

(def git-env
  {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null" "GIT_TERMINAL_PROMPT" "0"
   "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
   "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"})

(defn git!
  "Runs git in `dir`, isolated from any user configuration; its output."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut!
  "Commits everything in `dir` (making it a git repository first when it is
  not one) and tags the commit `tag`, moving the tag when it exists."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q"))
  (git! dir "add" "-A" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the spec at " tag))
  (git! dir "tag" "-f" tag)
  dir)

;; --- cycles -------------------------------------------------------------------

(def default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn write-cycle!
  "Writes dhf/cycles/CYCLE/cycle.edn: the default cycle (cut spec-3, scope
  :project, builder-a, grader-b, Sami) merged with `overrides`; an override
  of nil leaves the key out. Returns the file."
  [dir cycle overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (s/write! dir (cycle-path cycle "cycle.edn") (pr-str m))))

(defn write-cycle-text!
  [dir cycle text]
  (s/write! dir (cycle-path cycle "cycle.edn") text))

(def default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(defn project!
  "A copy of the room's allow-list whose spec is only `files` ({path text}),
  cut as spec-3; cycle.edn is not written. Returns the directory."
  [files]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (doseq [[f t] files] (s/write! dir f t))
    (cut! dir "spec-3")
    dir))

(defn cycle-project!
  "The context with a cut project holding `files` (default: Lint's default
  scenarios) and an empty room."
  ([ctx] (cycle-project! ctx {lint-feature (feature-text default-scenarios)}))
  ([ctx files]
   (assoc ctx :dir (project! files) :room (s/tmp-dir) :cycle "03")))

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn wrote-nothing!
  "Fails when `rel` exists in the scenario's project."
  [ctx rel]
  (s/check! (not (fs/exists? (fs/file (:dir ctx) rel))) (str rel " was written\n" (s/show (the-run ctx)))))

(defn exits!
  [ctx code]
  (s/check! (= code (:exit (the-run ctx))) (str "expected exit " code "\n" (s/show (the-run ctx)))))

(defn says!
  [ctx re what]
  (s/check! (re-find re (or (:err (the-run ctx)) "")) (str "the error stream does not say " what "\n" (s/show (the-run ctx)))))

(defn read-edn-file [ctx rel]
  (let [f (fs/file (:dir ctx) rel)]
    (s/check! (fs/exists? f) (str "no " rel "\n" (s/show (the-run ctx))))
    (edn/read-string (slurp (str f)))))

;; ---------------------------------------------------------------------------
;; Shared Givens

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3(?: and the scope (\S+))?"
  (fn [ctx scope]
    (let [ctx (if (:dir ctx) ctx (cycle-project! ctx))]
      (write-cycle! (:dir ctx) "03" (cond-> {} scope (assoc :scope (edn/read-string scope))))
      (assoc ctx :cycle "03"))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj default-scenarios {:title "A scenario nobody stamped"})
          text (feature-text scs)
          ctx (cycle-project! ctx {lint-feature text})]
      (write-cycle! (:dir ctx) "03" {})
      (assoc ctx :unstamped {:file lint-feature :line (line-of text "A scenario nobody stamped")}))))
