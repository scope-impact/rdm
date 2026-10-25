(ns steps.measurement-cycle
  "Fixtures the measurement steps share, and the cycle Givens other features
  use too (dhf/spec/spec/parser.feature, dhf/spec/ratification/ratify.feature):

    \"dhf/cycles/03/cycle.edn names the cut spec-3\"
    \"the spec at cut spec-3 holds a scenario with no id\"

  A fixture project is a throwaway git repository in a temporary directory:
  the room's harness configuration, its C4 model and needs, and only the
  feature files a scenario gives, committed and tagged as the cut spec-3.
  Its cycle 03 is dhf/cycles/03/cycle.edn, written beside the cut (never in
  it). Each such Given also makes a room (:room), an empty directory of its
  own, so ROOM is never a fixed path.

  The context keys these steps set: :dir (the project), :room, :cycle,
  :unstamped {:file :line} (the scenario with no id)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; git

(def git-env
  {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
   "GIT_AUTHOR_NAME" "Fixture" "GIT_AUTHOR_EMAIL" "fixture@example.invalid"
   "GIT_COMMITTER_NAME" "Fixture" "GIT_COMMITTER_EMAIL" "fixture@example.invalid"
   "GIT_TERMINAL_PROMPT" "0"})

(defn git!
  "Runs git in `dir` and returns its output; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env git-env}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                 "-c" "init.defaultBranch=main" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (:out r)))

;; ---------------------------------------------------------------------------
;; Paths and feature files

(def lint-feature "dhf/spec/architecture/lint.feature")
(def status-feature "dhf/spec/verification/status.feature")

(defn cycle-path
  "The path of `name` in cycle `cycle`: dhf/cycles/<cycle>/<name>."
  [cycle name]
  (str "dhf/cycles/" cycle "/" name))

(defn feature-text
  "A feature file holding one scenario per entry of `scenarios`
  ({:id :title :wip?}; an entry with no :id is an unstamped scenario).
  With one argument, the Lint feature of the context architecture."
  ([scenarios] (feature-text "Lint" "UN-005" scenarios))
  ([feature need scenarios]
   (str "@" need "\nFeature: " feature "\n  A feature for the measurement tests.\n\n"
        "  Rule: One rule\n"
        (str/join
         (for [{:keys [id title wip?]} scenarios]
           (str "\n    " (str/join " " (cond-> [] id (conj (str "@" id)) true (conj "@e2e") wip? (conj "@wip")))
                "\n    Scenario: " (or title (str "Scenario " id))
                "\n      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n"))))))

(defn line-of
  "The 1-based line of `text` on which `needle` first appears."
  [text needle]
  (let [i (str/index-of text needle)]
    (s/check! i (str "the text holds no " (pr-str needle)))
    (inc (count (filter #{\newline} (subs text 0 i))))))

(def default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}
   {:id "spec-0014" :title "A view with a label fails the lint"}])

;; ---------------------------------------------------------------------------
;; Projects, cuts and cycles

(defn cut!
  "Commits everything but the cycles (making `dir` a repository first, when
  it is not one) and tags the commit `tag` (moving the
  tag when it exists)."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git")) (git! dir "init" "-q"))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the spec at " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn spec-project!
  "A fixture project whose cut spec-3 holds the room's configuration, model
  and needs, and the feature files `features` ({path text}) and no other.
  Returns its directory."
  [features]
  (let [dir (s/tmp-dir)]
    (git! dir "init" "-q")
    (s/write! dir "dhf/harness/config.edn" (pr-str (s/room-config)))
    (doseq [rel ["dhf/spec/model.edn" "dhf/spec/needs.edn" "dhf/spec/events.edn" "dhf/spec/ids.edn"]
            :let [f (fs/file s/room-root rel)]
            :when (fs/exists? f)]
      (s/write! dir rel (slurp (str f))))
    (doseq [[rel text] features] (s/write! dir rel text))
    (cut! dir "spec-3")
    dir))

(def default-cycle
  [[:cut "spec-3"]
   [:scope :project]
   [:builder {:name "builder-a" :actor :agent}]
   [:grader {:name "grader-b" :actor :agent}]
   [:spec-owner {:name "Sami" :actor :human}]])

(defn cycle-text
  "cycle.edn's text: the default cycle with `opts` merged in (a nil value
  leaves its key out), one key per line."
  [opts]
  (let [ks (distinct (concat (map first default-cycle) (keys opts)))
        m (merge (into {} default-cycle) opts)
        kvs (for [k ks :let [v (get m k)] :when (some? v)] (str k " " (pr-str v)))]
    (str "{" (str/join "\n " kvs) "}\n")))

(defn write-cycle!
  "Writes cycle `cycle`'s cycle.edn into `dir`: `opts` merged into the
  default cycle (cut spec-3, the project scope, builder-a and grader-b as
  agents, Sami as a human spec owner), or the text `opts` when a string."
  [dir cycle opts]
  (s/write! dir (cycle-path cycle "cycle.edn") (if (string? opts) opts (cycle-text opts))))

(defn cycle-project!
  "ctx with a fixture project holding `features` at the cut spec-3, cycle 03
  declared with `cycle-opts`, and an empty room."
  ([ctx features] (cycle-project! ctx features {}))
  ([ctx features cycle-opts]
   (let [dir (spec-project! features)]
     (write-cycle! dir "03" cycle-opts)
     (assoc ctx :dir (str dir) :room (s/tmp-dir) :cycle "03"))))

(defn read-checklist
  "The cycle's checklist.json, parsed with keyword keys; fails when absent."
  [ctx cycle]
  (let [f (fs/file (:dir ctx) (cycle-path cycle "checklist.json"))]
    (s/check! (fs/exists? f) (str "no " (cycle-path cycle "checklist.json") "\n" (s/show (:run ctx))))
    (json/parse-string (slurp (str f)) true)))

(defn read-cycle-edn
  "The EDN file `name` of the cycle, or nil when absent."
  [ctx cycle name]
  (let [f (fs/file (:dir ctx) (cycle-path cycle name))]
    (when (fs/exists? f) (edn/read-string (slurp (str f))))))

(defn refused!
  "Fails unless the run exited `code`, wrote no `written` file of cycle 03
  and its error stream matches every pattern of `res`."
  [ctx code written & res]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    (s/check! (not (fs/exists? (fs/file (:dir ctx) (cycle-path "03" written))))
              (str "it wrote " (cycle-path "03" written) "\n" (s/show r)))
    (doseq [re res]
      (s/check! (re-find re (:err r)) (str "the error stream does not say " re "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; The shared cycle Givens

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx]
    (if (:dir ctx)
      (do (write-cycle! (:dir ctx) "03" {})
          (assoc ctx :room (or (:room ctx) (s/tmp-dir)) :cycle "03"))
      (cycle-project! ctx {lint-feature (feature-text default-scenarios)}))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj default-scenarios {:title "A scenario nobody stamped"})
          text (feature-text scs)
          ctx (cycle-project! ctx {lint-feature text})]
      (assoc ctx :unstamped {:file lint-feature :line (line-of text "Scenario: A scenario nobody stamped")}))))
