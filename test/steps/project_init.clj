(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature: `rdm-spec init` in a
  new empty directory of the scenario's own (:dir). Context keys: see
  steps.project-support; :placeholders {\"DIR\" dir} for an absolute path."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- empty-dir [ctx]
  (if (:dir ctx) ctx (assoc ctx :dir (s/tmp-dir))))

(defn- at [ctx p] (str (fs/file (:dir ctx) p)))

;; ---------------------------------------------------------------------------
;; Init starts a project from nothing

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx p]
    (let [ctx (empty-dir ctx)]
      (s/check! (not (fs/exists? (at ctx p))) (str p " exists"))
      (assoc ctx :target p))))

(def ^:private default-mapping-forms
  "rdm-spec's default Allure mapping, as spec-0333 states it."
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(defn- readme-states-the-mapping! [dir]
  (let [text (slurp (str (fs/file dir "dhf/spec/README.md")))
        forms (for [[_ body] (re-seq #"(?s)```edn\n(.*?)```" text)]
                (try (edn/read-string body) (catch Exception _ nil)))]
    (s/check! (str/includes? text "`epic`") "dhf/spec/README.md does not name the Allure element epic")
    (s/check! (some #{default-mapping-forms} forms)
              (str "dhf/spec/README.md does not state rdm-spec's default Allure mapping, as an edn block:\n"
                   (pr-str default-mapping-forms)))
    (s/check! (str/includes? text ":allure") "dhf/spec/README.md does not say a configuration's :allure may replace it")))

(defstep #"\"([^\"]+)\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx p _table]
    (kc/exits! ctx 0)
    (let [dir (at ctx p)]
      (ps/skeleton-at! dir)
      (s/check! (not (zero? (:exit (ps/git dir {} "rev-parse" "-q" "--verify" "HEAD")))) (str p " has a commit"))
      (readme-states-the-mapping! dir)
      nil)))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [out (ps/result-of (kc/exits! ctx 0) :init)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote out))) (str "init lists " (pr-str (:wrote out))))
      (s/check! (and (seq (:next-steps out)) (every? string? (:next-steps out)))
                (str "init names no next steps: " (pr-str out)))
      nil)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx]
    (let [d (s/tmp-dir)]
      (assoc-in (empty-dir ctx) [:placeholders "DIR"] d))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (kc/exits! ctx 0)
    (ps/skeleton-at! (str (fs/file (get-in ctx [:placeholders "DIR"]) "sauna")))
    nil))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx p]
    (let [ctx (empty-dir ctx)]
      (s/write! (at ctx p) "notes.md" "kept\n")
      (assoc ctx :target p :before (ps/files-of (at ctx p))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx p]
    (s/check! (= (:before ctx) (ps/files-of (at ctx p))) (str p " changed"))
    nil))

(defn- prefix-of [ctx]
  (let [r (kc/exits! ctx 0)]
    (:id-prefix (edn/read-string (slurp (at ctx "sauna/dhf/harness/config.edn"))))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx)) (str "the id prefix is " (pr-str (prefix-of ctx))))
    nil))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx)) (str "the id prefix is " (pr-str (prefix-of ctx))))
    nil))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (kc/refused-naming! ctx "starts with a letter" "letters, digits and single hyphens")
    (s/check! (not (fs/exists? (at ctx "sauna"))) "init wrote sauna")
    nil))

(defstep #"it exits 2 and says the prefix UN is taken by need tags"
  (fn [ctx]
    (kc/refused-naming! ctx "UN" "need tags")
    (s/check! (not (fs/exists? (at ctx "sauna"))) "init wrote sauna")
    nil))

;; ---------------------------------------------------------------------------
;; A new project is green before anything is written in it

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx p]
    (let [parent (s/tmp-dir)
          r (kc/run! ctx parent ["init" p])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! (str (fs/file parent p)))
      (assoc ctx :dir parent :target p))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line p]
    (kc/run-line (assoc ctx :dir (at ctx p)) line)))

;; ---------------------------------------------------------------------------
;; The project context's When in a repository a Given made (adopt.feature)

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx line] (kc/run-line ctx line)))
