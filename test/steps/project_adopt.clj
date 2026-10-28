(ns steps.project-adopt
  "Steps of the component Adopt (01-start-a-project/adopt.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def skeleton
  ["dhf/spec/model.edn" "dhf/spec/events.edn" "dhf/spec/needs.edn" "dhf/spec/ids.edn" "dhf/spec/glossary.md"
   "dhf/spec/README.md" "dhf/harness/config.edn" "dhf/harness/stack.md" "dhf/raw/README.md" "dhf/raw/sources.edn"
   "dhf/wiki/index.md" "dhf/decisions/README.md" ".githooks/pre-commit" ".github/workflows/cut.yml" ".gitignore"])

(defn repo!
  "A git repository in a new fixture holding the given files."
  [files]
  (let [top (k/top-dir!) d (k/path top "repo")]
    (fs/create-dirs d)
    (k/git! d "init" "-q")
    (doseq [[f t] files] (k/write! d f t))
    {:dir d :top top :vars {}}))

(def code {"src/app.py" "print('hello')\n" "README.md" "# The app\n"})

(defn kept [ctx] (set (get-in ctx [:edn :adopt :kept])))
(defn wrote [ctx] (set (get-in ctx [:edn :adopt :wrote])))

(defstep #"a git repository holding source code and a README"
  (fn [_] (let [ctx (repo! code)] (assoc ctx :before (k/tree-snapshot (:dir ctx))))))

(defstep #"a git repository holding source code and tests"
  (fn [_] (repo! (assoc code "tests/test_app.py" "def test_it(): assert True\n" "features/app.feature" "Feature: x\n"))))

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [f skeleton] (k/expect! (fs/exists? (k/path (:dir ctx) f)) ctx (str "no " f)))
    (k/expect! (= ".githooks" (k/git! (:dir ctx) "config" "--file" ".git/config" "core.hooksPath")) ctx "the hook is not installed")
    ctx))

(defstep #"its source code and README are unchanged"
  (fn [ctx]
    (let [after (k/tree-snapshot (:dir ctx))]
      (doseq [f (keys code)] (k/expect! (= (get (:before ctx) f) (get after f)) ctx (str f " changed"))))
    ctx))

(defstep #"adopt lists every file it wrote"
  (fn [ctx] (k/expect! (= (set skeleton) (wrote ctx)) ctx "adopt does not list every file it wrote") ctx))

(defstep #"a git repository that already holds dhf/spec/glossary.md"
  (fn [_] (repo! {"dhf/spec/glossary.md" "# Our words\n"})))

(defstep #"dhf/spec/glossary.md is unchanged"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= "# Our words\n" (k/slurp-in (:dir ctx) "dhf/spec/glossary.md")) ctx "the glossary changed") ctx))

(defstep #"adopt names it as kept"
  (fn [ctx] (k/expect! ((kept ctx) "dhf/spec/glossary.md") ctx "the glossary is not named as kept") ctx))

(defstep #"a git repository where dhf/harness/config.edn is a symbolic link"
  (fn [_]
    (let [ctx (repo! {"elsewhere/config.edn" "{:mine true}\n"})]
      (fs/create-dirs (k/path (:dir ctx) "dhf/harness"))
      (fs/create-sym-link (k/path (:dir ctx) "dhf/harness/config.edn") "../../elsewhere/config.edn")
      ctx)))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [l (fs/path (:dir ctx) "dhf/harness/config.edn")]
      (k/expect! (fs/sym-link? l) ctx "the link is gone")
      (k/expect! (= "../../elsewhere/config.edn" (str (fs/read-link l))) ctx "the link changed")
      (k/expect! (= "{:mine true}\n" (k/slurp-in (:dir ctx) "elsewhere/config.edn")) ctx "the target changed"))
    ctx))

(defstep #"adopt names the link as kept"
  (fn [ctx] (k/expect! ((kept ctx) "dhf/harness/config.edn") ctx "the link is not named as kept") ctx))

(defstep #"a git repository with its own .gitignore"
  (fn [_] (repo! {".gitignore" "node_modules/\n"})))

(defstep #"the .gitignore is unchanged"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= "node_modules/\n" (k/slurp-in (:dir ctx) ".gitignore")) ctx ".gitignore changed") ctx))

(defstep #"adopt names the lines it would have added, those init writes to .gitignore"
  (fn [ctx]
    (let [lines (set (get-in ctx [:edn :adopt :gitignore-lines]))]
      (k/expect! (= #{"dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"} lines) ctx
                 "adopt does not name init's .gitignore lines"))
    ctx))

(defstep #"a repository that has been adopted"
  (fn [_]
    (let [ctx (repo! code)
          r (k/run-rdm ["adopt"] {:dir (:dir ctx) :ceiling (:top ctx)})]
      (when-not (zero? (:exit r)) (throw (ex-info (:err r) {})))
      (assoc ctx :before (k/tree-snapshot (:dir ctx))))))

(defstep #"no file changes"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= (:before ctx) (k/tree-snapshot (:dir ctx))) ctx "a file changed") ctx))

(defstep #"adopt names every destination as kept"
  (fn [ctx] (k/expect! (= (set skeleton) (kept ctx)) ctx "not every destination is named as kept")
    (k/expect! (empty? (wrote ctx)) ctx "adopt wrote something") ctx))

(defstep #"a directory that is not a git repository"
  (fn [_] (let [top (k/top-dir!) d (k/path top "plain")] (k/write! d "a.txt" "a\n") {:dir d :top top :vars {}})))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (k/exits! ctx 2) (k/err-has! ctx "git repository")
    (k/expect! (= ["a.txt"] (keys (k/tree-snapshot (:dir ctx)))) ctx "it wrote something")
    ctx))

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [m (edn/read-string (k/slurp-in (:dir ctx) "dhf/spec/model.edn"))]
      (k/expect! (= #{:system} (set (keys m))) ctx "the model is not empty"))
    (k/expect! (= {} (edn/read-string (k/slurp-in (:dir ctx) "dhf/spec/needs.edn"))) ctx "the needs are not empty")
    (k/expect! (= {:issued 0} (edn/read-string (k/slurp-in (:dir ctx) "dhf/spec/ids.edn"))) ctx "the ledger is not empty")
    ctx))

(defstep #"no feature file is written"
  (fn [ctx] (k/expect! (empty? (fs/glob (k/path (:dir ctx) "dhf") "**.feature")) ctx "a feature file was written") ctx))
