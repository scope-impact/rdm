;; Step definitions for dhf/spec/project/init.feature. They drive the rdm-spec
;; command as its own process (the kernel's shared When, or
;; steps.kernel-command/run! with the scenario's environment) in a fresh
;; temporary directory, and read what init wrote from disk.
;;
;; The skeleton's table in @spec-0086 is the oracle for what init writes
;; (steps.project-support/skeleton-table): every row's path must be written,
;; nothing else, and every row's "holds" is checked by the check this file
;; keeps for its path. A row this file has no check for fails the step, so a
;; row added to the spec cannot pass unread.
(ns steps.project-init
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- edn-file [root rel]
  (let [f (fs/file root rel)]
    (s/check! (fs/regular-file? f) (str rel " is not a file"))
    (try (edn/read-string (slurp (str f)))
         (catch Exception e (s/fail! (str rel " is not EDN: " (ex-message e)))))))

(defn- text-file [root rel]
  (let [f (fs/file root rel)]
    (s/check! (fs/regular-file? f) (str rel " is not a file"))
    (slurp (str f))))

(defn- lines [text] (vec (remove str/blank? (map str/trim (str/split-lines text)))))

(defn- section
  "The lines of the Markdown `text` under the heading `heading`, up to the
  next heading of any level."
  [text heading]
  (->> (str/split-lines text)
       (drop-while #(not= heading (str/trim %)))
       rest
       (take-while #(not (str/starts-with? % "#")))
       (remove str/blank?)
       vec))

(def allow-list
  "The clean room's allow-list init.feature names for a new project."
  #{"dhf/spec" "dhf/harness/config.edn" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"})

(def directory-keys
  "Every directory or file the tasks read from the configuration."
  [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir
   :steps-dir :results-dir :cycles-dir])

(defn- check-config [root prefix]
  (let [c (edn-file root "dhf/harness/config.edn")
        text (text-file root "dhf/harness/config.edn")]
    (s/check! (map? c) "the configuration is not a map")
    (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
    (doseq [k directory-keys]
      (s/check! (and (string? (get c k)) (not (str/blank? (get c k))))
                (str "the configuration does not set " k)))
    (s/check! (and (string? (get-in c [:site :dir])) (string? (get-in c [:site :source])))
              "the configuration does not set the site's directories")
    (let [allow (get-in c [:clean-room :allow])]
      (s/check! (= allow-list (set allow)) (str "the allow-list is " (pr-str allow)))
      (s/check! (= (count allow) (count (set allow))) (str "the allow-list repeats a path: " (pr-str allow))))
    (s/check! (not (str/includes? (pr-str c) "dhf/harness/oracle"))
              "the configuration allows dhf/harness/oracle")
    (s/check! (not (some #{"dhf/harness"} (get-in c [:clean-room :allow])))
              "the allow-list admits the whole of dhf/harness")
    (s/check! (empty? (:implementation c)) (str "implementation paths: " (pr-str (:implementation c))))
    (s/check! (empty? (:code-layout c)) (str "a code layout: " (pr-str (:code-layout c))))
    (s/check! (nil? (:test-command c)) (str "a test command: " (pr-str (:test-command c))))
    (s/check! (re-find #"oracle" text) "the configuration does not say why dhf/harness/oracle is never allowed")))

(defn- check-model [root]
  (let [m (edn-file root "dhf/spec/model.edn")]
    (s/check! (map? m) "the model is not a map")
    (s/check! (keyword? (get-in m [:system :id])) (str "the system has no id: " (pr-str (:system m))))
    (s/check! (string? (get-in m [:system :name])) "the system has no name")
    (s/check! (every? empty? (vals (dissoc m :system))) (str "the model holds " (pr-str (dissoc m :system))))))

(defn- check-glossary [root]
  (let [t (text-file root "dhf/spec/glossary.md")]
    (s/check! (re-find #"(?m)^# " t) "the glossary has no title")
    (s/check! (not (re-find #"(?m)^\*\*[^*]+\*\*:" t)) "the glossary defines a term")))

(defn- check-hook [root]
  (let [f (fs/file root ".githooks/pre-commit")
        t (text-file root ".githooks/pre-commit")]
    (s/check! (fs/executable? f) "the hook is not executable")
    (s/check! (str/starts-with? t "#!") "the hook has no interpreter line")
    (s/check! (re-find #"\"rdm-spec\"\s+\"check\"|rdm-spec\s+check" t) "the hook does not run rdm-spec check")
    (s/check! (= ".githooks" (:out (ps/git root "config" "--local" "--get" "core.hooksPath")))
              "core.hooksPath is not .githooks")))

(defn- check-workflow [root]
  (let [t (text-file root ".github/workflows/cut.yml")]
    (s/check! (re-find #"(?m)^\s*run:\s*rdm-spec cut\s*$" t) "the workflow does not run rdm-spec cut")
    (s/check! (re-find #"(?m)^\s*push:" t) "the workflow does not run on push")
    (s/check! (re-find #"default_branch" t) "the workflow does not keep to the default branch")))

(def checks
  "path -> (fn [root prefix]): what the row of that path says the file holds."
  {"dhf/spec/model.edn" (fn [root _] (check-model root))
   "dhf/spec/events.edn" (fn [root _] (s/check! (= {} (edn-file root "dhf/spec/events.edn")) "events are declared"))
   "dhf/spec/needs.edn" (fn [root _] (s/check! (= {} (edn-file root "dhf/spec/needs.edn")) "needs are declared"))
   "dhf/spec/ids.edn" (fn [root _] (s/check! (= {:issued 0} (edn-file root "dhf/spec/ids.edn")) "the ledger is not at 0"))
   "dhf/spec/glossary.md" (fn [root _] (check-glossary root))
   "dhf/spec/README.md" (fn [root prefix]
                          (let [t (text-file root "dhf/spec/README.md")]
                            (s/check! (seq (section t "## Conventions")) "the spec's README has no conventions")
                            (s/check! (str/includes? t (str "@" prefix "-NNNN"))
                                      (str "the conventions do not name the ids @" prefix "-NNNN"))))
   "dhf/harness/config.edn" check-config
   "dhf/raw/README.md" (fn [root _]
                         (s/check! (seq (section (text-file root "dhf/raw/README.md") "## Rules"))
                                   "the raw README has no rules"))
   "dhf/raw/sources.edn" (fn [root _] (s/check! (= {} (edn-file root "dhf/raw/sources.edn")) "sources are recorded"))
   "dhf/wiki/index.md" (fn [root _]
                         (let [t (text-file root "dhf/wiki/index.md")]
                           (s/check! (seq (section t "## Rules")) "the wiki index has no rules")
                           (s/check! (not-any? #(re-find #"^\s*[-*]\s|\]\(" %) (section t "## Pages"))
                                     "the wiki index lists a page")))
   "dhf/decisions/README.md" (fn [root _]
                               (s/check! (re-find #"(?i)decision" (text-file root "dhf/decisions/README.md"))
                                         "the decisions README does not say how decisions are recorded"))
   ".githooks/pre-commit" (fn [root _] (check-hook root))
   ".github/workflows/cut.yml" (fn [root _] (check-workflow root))
   ".gitignore" (fn [root _]
                  (s/check! (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]
                               (lines (text-file root ".gitignore")))
                            (str ".gitignore holds " (pr-str (lines (text-file root ".gitignore"))))))})

(defn- passed! [ctx]
  (let [r (kc/the-run ctx)]
    (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
    r))

(defn- project-path [ctx rel] (str (fs/file (:dir ctx) rel)))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir rel))) (str rel " exists"))
      (assoc ctx :dir dir :path rel))))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str rel "/notes.md") "Ours.\n")
      (assoc ctx :dir dir :path rel :before (ps/snapshot (fs/file dir rel) true)))))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)
          r (kc/run! ctx dir ["init" rel])]
      (s/check! (= 0 (:exit r)) (str "init: " (s/show r)))
      (assoc ctx :dir dir :path rel))))

;; ---------------------------------------------------------------------------
;; When (the init itself runs through the kernel's shared When)

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line rel]
    (assoc ctx :run (kc/run! ctx (project-path ctx rel) (kc/split-line line)))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx rel table]
    (passed! ctx)
    (let [root (project-path ctx rel)
          rows (vec (rest table))
          paths (mapv first rows)
          held (set (keys (ps/snapshot root false)))]
      (s/check! (= (str (fs/canonicalize root)) (ps/repo-root root)) (str rel " is not a git repository"))
      (s/check! (= (set paths) held)
                (str "missing: " (sort (remove held paths)) "; not in the table: " (sort (remove (set paths) held))))
      (doseq [[p holds] rows]
        (let [check (or (get checks p) (s/fail! (str "no check reads the row " p ": " holds)))]
          (try (check root "spec")
               (catch clojure.lang.ExceptionInfo e
                 (s/fail! (str p ", which should hold " holds ": " (ex-message e)))))))
      (assoc ctx :paths paths))))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (passed! ctx)
          res (kc/stdout-edn r)
          written (keys (ps/snapshot (project-path ctx (:path ctx)) false))]
      (s/check! (= (sort written) (sort (:wrote res))) (str "init lists " (pr-str (:wrote res))))
      (s/check! (= (count (:wrote res)) (count (set (:wrote res)))) "init lists a file twice")
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res))
                     (every? #(and (string? %) (not (str/blank? %))) (:next-steps res)))
                (str "next steps: " (pr-str (:next-steps res))))
      (s/check! (some #(str/includes? % "rdm-spec") (:next-steps res))
                (str "no next step names an rdm-spec task: " (pr-str (:next-steps res)))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (= "" (:out r)) (str "standard output is not empty\n" (s/show r)))
      (s/check! (re-find #"^cannot-run: " (:err r)) (s/show r))
      (s/check! (str/includes? (:err r) (:path ctx)) (str "the refusal does not name " (:path ctx) "\n" (s/show r))))))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx rel]
    (let [d (ps/diff (:before ctx) (ps/snapshot (fs/file (:dir ctx) rel) true))]
      (s/check! (empty? d) (str "changed: " d)))))

(defn- prefix-set! [ctx prefix]
  (passed! ctx)
  (let [root (project-path ctx "sauna")
        c (edn-file root "dhf/harness/config.edn")]
    (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
    (s/check! (str/includes? (text-file root "dhf/spec/README.md") (str "@" prefix "-NNNN"))
              (str "the spec's conventions do not name @" prefix "-NNNN"))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix] (prefix-set! ctx prefix)))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix] (prefix-set! ctx prefix)))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (re-find #"starts with a letter and holds letters, digits and single hyphens" (:err r)) (s/show r))
      (s/check! (not (fs/exists? (project-path ctx "sauna"))) "init wrote sauna"))))
