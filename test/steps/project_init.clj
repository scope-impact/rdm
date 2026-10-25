(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature. Every scenario runs
  `rdm-spec init` as its own process in a new temporary directory (a
  fixture), never in the room. The shared When, \"it passes\" and \"it exits
  N\" are the kernel's (steps.kernel-command).

  The skeleton's oracle is the table of @spec-0086 (steps.project-support/
  skeleton-table): exactly its paths, each holding what its row says.

  Context keys: :dir where the command runs, :run the last run, :before a
  snapshot of the existing path, :placeholders {\"DIR\" dir}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading a written project

(defn- files-of
  "Every file and link under `dir`, .git excluded, relative to it."
  [dir]
  (set (keys (ps/snapshot dir false))))

(defn- text [dir rel] (slurp (str (fs/file dir rel))))

(defn- edn-of [dir rel] (edn/read-string (text dir rel)))

(def ^:private expected-allow
  ["dhf/spec" "dhf/harness/config.edn" "dhf/harness/stack.md" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"])

(defn- holds!
  "Checks the file `rel` of the project `dir` holds what the skeleton
  table's row says it holds."
  [dir rel]
  (let [t (text dir rel)
        ok! (fn [ok what] (s/check! ok (str rel " does not hold " what ":\n" t)))]
    (case rel
      "dhf/spec/model.edn"
      (let [m (edn-of dir rel)]
        (ok! (map? (:system m)) "the system")
        (ok! (every? empty? (vals (dissoc m :system))) "nothing but the system"))
      "dhf/spec/events.edn" (ok! (= {} (edn-of dir rel)) "an empty event catalogue")
      "dhf/spec/needs.edn" (ok! (= {} (edn-of dir rel)) "no needs")
      "dhf/spec/ids.edn" (ok! (= {:issued 0} (edn-of dir rel)) "the ledger at 0")
      "dhf/spec/glossary.md" (ok! (not (re-find #"(?m)^\*\*[^*]+\*\*:" t)) "an empty glossary")
      "dhf/spec/README.md" (ok! (and (str/includes? t "Conventions") (str/includes? t "@wip")) "the spec's conventions")
      "dhf/harness/config.edn"
      (let [c (edn-of dir rel)]
        (ok! (string? (:id-prefix c)) "the id prefix")
        (doseq [k [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir :steps-dir
                   :results-dir :cycles-dir]]
          (ok! (string? (get c k)) (str "the directory " k)))
        (ok! (= (set expected-allow) (set (get-in c [:clean-room :allow]))) "the allow-list")
        (ok! (not-any? #(str/starts-with? % "dhf/harness/oracle") (get-in c [:clean-room :allow])) "no oracle")
        (ok! (empty? (:implementation c)) "no implementation paths"))
      "dhf/harness/stack.md"
      (let [rows (filter #(str/starts-with? (str/trim %) "|") (str/split-lines t))]
        (ok! (= 2 (count rows)) "a stack table with no rows")
        (ok! (re-matches #"\|[\s:-]+\|[\s:-]+\|" (str/trim (second rows))) "a table's header"))
      "dhf/raw/README.md" (ok! (str/includes? t "## Rules") "the rules for raw sources")
      "dhf/raw/sources.edn" (ok! (= {} (edn-of dir rel)) "no sources")
      "dhf/wiki/index.md" (ok! (and (str/includes? t "## Rules") (re-find #"## Pages\s+None yet\.?\s*$" t))
                               "the wiki's rules and an empty list of pages")
      "dhf/decisions/README.md" (ok! (str/includes? t "decision") "how decisions are recorded")
      ".githooks/pre-commit"
      (do (ok! (re-find #"rdm-spec\"?\s+\"?check" t) "a hook that runs rdm-spec check")
          (ok! (fs/executable? (fs/file dir rel)) "an executable hook")
          (ok! (= ".githooks" (:out (ps/git dir "config" "--local" "--get" "core.hooksPath")))
               "core.hooksPath set to .githooks"))
      ".github/workflows/cut.yml"
      (ok! (and (str/includes? t "rdm-spec cut") (re-find #"(?m)^\s+push:" t) (str/includes? t "default_branch"))
           "a workflow cutting every push to the default branch")
      ".gitignore"
      (ok! (= #{"dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"}
              (set (remove str/blank? (str/split-lines t))))
           "the ignored paths")
      (s/fail! (str "the skeleton table names " rel ", which this step does not know")))))

(defn- skeleton!
  "Checks `dir` is a git repository holding exactly the skeleton."
  [dir]
  (s/check! (fs/directory? dir) (str dir " does not exist"))
  (s/check! (= (str (fs/canonicalize dir)) (ps/repo-root dir)) (str dir " is not a git repository's top"))
  (let [want (set (ps/skeleton-paths))
        have (files-of dir)]
    (s/check! (= want have) (str "the skeleton differs: missing " (pr-str (sort (remove have want)))
                                 ", extra " (pr-str (sort (remove want have)))))
    (doseq [rel (sort want)] (holds! dir rel))))

(defn- refused-saying! [ctx & phrases]
  (let [r (kc/exits! ctx 2)]
    (s/check! (str/blank? (:out r)) (str "a refusal printed a result\n" (s/show r)))
    (s/check! (some (fn [l] (and (str/starts-with? l "cannot-run: ") (every? #(str/includes? l %) phrases)))
                    (s/err-lines r))
              (str "no cannot-run line says " (pr-str phrases) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; Init starts a project from nothing

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx path]
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir path))) (str path " exists"))
      (assoc ctx :dir dir))))

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx path table]
    (kc/exits! ctx 0)
    (let [dir (str (fs/file (:dir ctx) path))
          rows (map first (rest table))]
      (s/check! (= (set rows) (set (ps/skeleton-paths))) "the table is not the skeleton")
      (skeleton! dir)
      nil)))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (:init (kc/stdout-edn r))]
      (s/check! (= (set (ps/skeleton-paths)) (set (:wrote out)))
                (str "init lists " (pr-str (:wrote out)) "\n" (s/show r)))
      (s/check! (and (sequential? (:next-steps out)) (seq (:next-steps out)) (every? string? (:next-steps out)))
                (str "init lists no next steps\n" (s/show r)))
      nil)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx]
    (let [d (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file d "sauna"))) "DIR/sauna exists")
      (-> ctx
          (assoc :dir (s/tmp-dir))
          (assoc-in [:placeholders "DIR"] d)))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          d (get-in ctx [:placeholders "DIR"])]
      (s/check! (some #(fs/absolute? %) (:args r)) (str "the path given was not absolute: " (:args r)))
      (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote under the working directory")
      (skeleton! (str (fs/file d "sauna")))
      nil)))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx path]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str path "/notes.txt") "Already here.\n")
      (assoc ctx :dir dir :existing path :before (ps/snapshot (fs/file dir path))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx]
    (kc/refused-naming! ctx (:existing ctx))
    nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx path]
    (let [d (ps/diff (:before ctx) (ps/snapshot (fs/file (:dir ctx) path)))]
      (s/check! (empty? d) (str "changed: " (pr-str d)))
      nil)))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (kc/exits! ctx 0)
    (let [c (edn-of (str (fs/file (:dir ctx) "sauna")) "dhf/harness/config.edn")]
      (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
      nil)))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (kc/exits! ctx 0)
    (let [c (edn-of (str (fs/file (:dir ctx) "sauna")) "dhf/harness/config.edn")]
      (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
      nil)))

;; Only init's two refusals: "it exits 2 and says ..." is worded alike in
;; other contexts' features, with their own meanings.
(defstep #"it exits 2 and says (a prefix starts with a letter and holds letters, digits and single hyphens|the prefix UN is taken by need tags)"
  (fn [ctx phrase]
    (refused-saying! ctx phrase)
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote sauna after refusing")
    nil))

;; ---------------------------------------------------------------------------
;; A new project is green before anything is written in it

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx path]
    (let [parent (s/tmp-dir)
          r (kc/run! ctx parent ["init" path])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :parent parent :project path))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line path]
    (kc/run-line (assoc ctx :dir (str (fs/file (or (:parent ctx) (:dir ctx)) path))) line)))
