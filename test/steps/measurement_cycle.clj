(ns steps.measurement-cycle
  "The measurement context's shared cycle steps and helpers, used by its own
  step files and by others (ratification, parser): one step text, one
  meaning.

  A cycle project is a throwaway git repository holding a copy of the room's
  allow-list (its test command the real one) with every feature file
  removed and the fixture's own written instead; its tag spec-3 is the cut,
  and dhf/cycles/03/cycle.edn is written after the cut, never committed.

  Helpers (a contract for the step files using them):
    lint-feature           the fixture feature file of the architecture
                           context's Lint component
    (feature-text scs)     a feature file holding scenarios [{:id :title
                           [:level \"contract\"] [:wip? true] [:no-id? true]}]
    (feature-text scs o)   o: {:feature name :need \"UN-005\"}
    (git! dir & args)      git in dir, with an identity; fails on an error
    (cut! dir tag)         commits everything outside dhf/cycles and (re)tags it
    (write-cycle! dir cycle overrides)  cycle.edn: default-cycle merged with
                           overrides; a nil value removes the key
    (cycle-path cycle f)   \"dhf/cycles/<cycle>/<f>\"
    (project! files)       a cycle project whose spec is `files`
                           {feature-path [scenario]}, not yet cut
    (room!)                a small room

  Shared Givens:
    dhf/cycles/03/cycle.edn names the cut spec-3[ and the scope S]
      in the scenario's project when it has one, else in a new one whose
      spec is default-scenarios, cut spec-3; ctx :dir :room :cycle
    the spec at cut spec-3 holds a scenario with no id
      a new project cut spec-3 with cycle 03; ctx :unstamped {:file :line}"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

(def lint-feature "dhf/spec/architecture/lint.feature")

(def default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn cycle-path [cycle f] (str "dhf/cycles/" cycle "/" f))

(defn- feature-name [file]
  (let [base (str/replace (fs/file-name file) #"\.feature$" "")]
    (str/join " " (map str/capitalize (str/split base #"_")))))

(defn scenario-text [{:keys [id title level wip? no-id?]}]
  (str "\n  " (str/join " " (remove nil? [(when-not no-id? (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn feature-text
  ([scenarios] (feature-text scenarios {}))
  ([scenarios {:keys [feature need] :or {feature "Lint" need "UN-005"}}]
   (str "# A fixture feature file of the measurement steps.\n\n@" need "\nFeature: " feature
        "\n  A fixture.\n"
        (str/join (map scenario-text scenarios)))))

(defn git!
  "Runs git in `dir` with an identity; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut!
  "Commits everything in `dir` outside dhf/cycles and tags it `tag` (moving
  the tag when it exists)."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid"))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn write-cycle!
  "Writes cycle `cycle`'s cycle.edn: default-cycle merged with `overrides`,
  a nil value removing its key."
  [dir cycle overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (s/write! dir (cycle-path cycle "cycle.edn") (pr-str m))
    m))

(defn project!
  "A copy of the room's allow-list whose spec holds only `files`
  {feature-path [scenario]} (or {feature-path text}); not cut."
  [files]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (doseq [[f scs] files]
      (s/write! dir f (if (string? scs) scs (feature-text scs {:feature (feature-name f)}))))
    dir))

(defn room!
  "A small room: an implementation whose tests exit 0."
  []
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj" "(System/exit 0)\n")
    r))

(defn line-of
  "The line of `text` that, trimmed, starts with `start`."
  [text start]
  (some (fn [[i l]] (when (str/starts-with? (str/trim l) start) (inc i)))
        (map-indexed vector (str/split-lines text))))

(defn read-scope [s] (edn/read-string s))

;; ---------------------------------------------------------------------------
;; Shared Givens

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3(?: and the scope (.+))?"
  (fn [ctx scope]
    (let [ctx (if (:dir ctx)
                ctx
                (let [dir (project! {lint-feature default-scenarios})]
                  (cut! dir "spec-3")
                  (assoc ctx :dir dir)))]
      (write-cycle! (:dir ctx) "03" (if scope {:scope (read-scope scope)} {}))
      (assoc ctx :room (or (:room ctx) (room!)) :cycle "03"))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj (vec (remove :wip? default-scenarios))
                    {:no-id? true :title "A scenario nobody stamped"})
          text (feature-text scs)
          dir (project! {lint-feature text})]
      (cut! dir "spec-3")
      (write-cycle! dir "03" {})
      (assoc ctx :dir dir :room (room!) :cycle "03"
             :unstamped {:file lint-feature :line (line-of text "Scenario: A scenario nobody stamped")}))))
