(ns steps.project-adopt
  "Step definitions of dhf/spec/project/adopt.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.project-init :as pi]))

(defn snapshot [d]
  (into {} (for [p (pi/tree d)]
             (let [f (fs/path d p)]
               [p (if (fs/sym-link? f) [:link (str (fs/read-link f))] (slurp (fs/file f)))]))))

(defn repo!
  "A git repository the scenario makes, hooks off, holding files; the project of its commands."
  [ctx files]
  (let [ctx (k/base! ctx)
        d (k/path-in ctx "repo")]
    (k/git-init! d)
    (k/write-files! d files)
    (assoc ctx :dir d)))

(def code {"src/app.py" "print('hello')\n" "README.md" "# My app\n"})

(defstep #"a git repository holding source code and a README"
  (fn [ctx] (let [ctx (repo! ctx code)] (assoc ctx ::before (snapshot (:dir ctx))))))

(defstep #"\"rdm-spec adopt\" is run in it"
  (fn [ctx] (k/rdm ctx ["adopt"])))

(defstep #"\"rdm-spec adopt\" is run in it again"
  (fn [ctx] (let [ctx (assoc ctx ::before (snapshot (:dir ctx)))] (k/rdm ctx ["adopt"]))))

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [ref (k/path-in ctx "reference")
          r (k/rdm ctx (:base ctx) ["init" "reference"])]
      (k/check-exit r 0)
      (doseq [p pi/skeleton]
        (let [a (fs/file (k/guard! (:dir ctx)) p) b (fs/file ref p)]
          (assert (fs/exists? a) (str "adopt did not write " p))
          (when-not (= p "dhf/spec/model.edn")
            (assert (= (slurp b) (slurp a)) (str p " differs from what init writes")))))
      (assert (= ".githooks" (k/git-config (:dir ctx) "core.hooksPath")))
      ctx)))

(defstep #"its source code and README are unchanged"
  (fn [ctx]
    (doseq [[p t] code] (assert (= t (slurp (fs/file (k/guard! (:dir ctx)) p)))))
    ctx))

(defstep #"adopt lists every file it wrote"
  (fn [ctx]
    (assert (= (sort pi/skeleton) (sort (get-in (k/edn-out ctx) [:adopt :wrote]))) (:out ctx))
    ctx))

(defstep #"a git repository that already holds dhf/spec/glossary.md"
  (fn [ctx] (repo! ctx {"dhf/spec/glossary.md" "# Our words\n"})))

(defstep #"dhf/spec/glossary.md is unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "# Our words\n" (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/glossary.md"))))
    ctx))

(defstep #"adopt names it as kept"
  (fn [ctx]
    (assert (some #{"dhf/spec/glossary.md"} (get-in (k/edn-out ctx) [:adopt :kept])) (:out ctx))
    (assert (not (some #{"dhf/spec/glossary.md"} (get-in (k/edn-out ctx) [:adopt :wrote]))))
    ctx))

(defstep #"a git repository where dhf/harness/config.edn is a symbolic link"
  (fn [ctx]
    (let [ctx (repo! ctx {"shared/config.edn" "{:id-prefix \"ours\"}\n"})]
      (fs/create-dirs (fs/path (k/guard! (:dir ctx)) "dhf/harness"))
      (fs/create-sym-link (fs/path (k/guard! (:dir ctx)) "dhf/harness/config.edn") "../../shared/config.edn")
      ctx)))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [l (fs/path (k/guard! (:dir ctx)) "dhf/harness/config.edn")]
      (assert (fs/sym-link? l))
      (assert (= "../../shared/config.edn" (str (fs/read-link l))))
      (assert (= "{:id-prefix \"ours\"}\n" (slurp (fs/file (k/guard! (:dir ctx)) "shared/config.edn")))))
    ctx))

(defstep #"adopt names the link as kept"
  (fn [ctx]
    (assert (some #{"dhf/harness/config.edn"} (get-in (k/edn-out ctx) [:adopt :kept])) (:out ctx))
    ctx))

(defstep #"a git repository with its own .gitignore"
  (fn [ctx] (repo! ctx {".gitignore" "node_modules/\n"})))

(defstep #"the .gitignore is unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "node_modules/\n" (slurp (fs/file (k/guard! (:dir ctx)) ".gitignore"))))
    ctx))

(defstep #"adopt names the lines it would have added, those init writes to .gitignore"
  (fn [ctx]
    (let [lines (get-in (k/edn-out ctx) [:adopt :gitignore-lines])]
      (assert (= #{"dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"} (set lines)) (:out ctx))
      ctx)))

(defstep #"a repository that has been adopted"
  (fn [ctx]
    (let [ctx (repo! ctx code)
          r (k/rdm ctx ["adopt"])]
      (k/check-exit r 0)
      ctx)))

(defstep #"no file changes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (::before ctx) (snapshot (:dir ctx))))
    ctx))

(defstep #"adopt names every destination as kept"
  (fn [ctx]
    (let [r (:adopt (k/edn-out ctx))]
      (assert (empty? (:wrote r)) (pr-str r))
      (assert (= (set pi/skeleton) (set (:kept r))) (pr-str r))
      ctx)))

(defstep #"a directory that is not a git repository"
  (fn [ctx]
    (let [ctx (k/base! ctx)
          d (k/path-in ctx "plain")]
      (k/write-files! d {"notes.txt" "x\n"})
      (assoc ctx :dir d))))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "hook" "git repository")
    (assert (= ["notes.txt"] (pi/tree (:dir ctx))))
    ctx))

(defstep #"a git repository holding source code and tests"
  (fn [ctx] (repo! ctx (merge code {"tests/test_app.py" "def test_it():\n    assert True\n"
                                    "features/app.feature" "Feature: App\n  Scenario: Runs\n    Given it runs\n"}))))

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [m (pi/read-edn-file (:dir ctx) "dhf/spec/model.edn")]
      (assert (every? empty? (vals (dissoc m :system))))
      (assert (= {} (pi/read-edn-file (:dir ctx) "dhf/spec/needs.edn")))
      (assert (= {:issued 0} (pi/read-edn-file (:dir ctx) "dhf/spec/ids.edn"))))
    ctx))

(defstep #"no feature file is written"
  (fn [ctx]
    (assert (empty? (filter #(and (str/ends-with? % ".feature") (str/starts-with? % "dhf/")) (pi/tree (:dir ctx)))))
    ctx))
