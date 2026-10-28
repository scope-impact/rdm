(ns steps.project-adopt
  "Steps of the component Adopt (dhf/spec/01-start-a-project/adopt.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn files
  "Every file and link under the project but .git, with its content (a link as its target)."
  [ctx]
  (let [d (:dir ctx)]
    (into (sorted-map)
          (for [f (fs/glob d "**" {:hidden true})
                :let [rel (str (fs/relativize d f))]
                :when (and (not (str/starts-with? rel ".git/")) (not= rel ".git")
                           (or (fs/sym-link? f) (fs/regular-file? f)))]
            [rel (if (fs/sym-link? f) [:link (str (fs/read-link f))] (slurp (str f)))]))))

(defn repo! [ctx files]
  (let [b (k/scratch! "scenario-") ctx (assoc ctx :base b :dir (str b "/repo"))]
    (fs/create-dirs (:dir ctx))
    (k/git! ctx "init" "-q")
    (doseq [[f t] files] (k/write! ctx f t))
    (when (seq files) (k/commit! ctx "what was there"))
    ctx))

(def source {"src/main.py" "print('hello')\n" "README.md" "# Mine\n"})

(defn skeleton
  "What init writes, as a fresh init writes it."
  [ctx]
  (let [b (k/scratch! "init-")
        r ((requiring-resolve 'rdm-spec.kernel.command/run) {:dir b :env (k/base-env ctx) :args ["init" "x"]})]
    (k/check! (zero? (:exit r)) "init failed: " (:err r))
    (files {:dir (str b "/x")})))

(defstep #"a git repository holding source code and a README"
  (fn [ctx] (let [ctx (repo! ctx source)] (assoc ctx ::before (files ctx)))))

(defstep #"\"rdm-spec adopt\" is run in it" (fn [ctx] (k/run-rdm ctx "adopt")))

(defstep #"\"rdm-spec adopt\" is run in it again" (fn [ctx] (assoc (k/run-rdm ctx "adopt") ::before (::before ctx))))

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [sk (skeleton ctx) now (files ctx)]
      (doseq [[f t] sk]
        (k/check! (contains? now f) f " was not added")
        (when-not (#{"dhf/spec/model.edn" "dhf/harness/config.edn"} f)
          (k/check! (= t (now f)) f " is not as init writes it"))))
    (k/check! (= ".githooks" (str/trim (:out (k/sh ctx (:dir ctx) "git" "config" "core.hooksPath")))) "the hook is not installed")
    ctx))

(defstep #"its source code and README are unchanged"
  (fn [ctx]
    (doseq [[f t] source] (k/check! (= t (k/slurp-rel ctx f)) f " changed"))
    ctx))

(defstep #"adopt lists every file it wrote"
  (fn [ctx]
    (let [sk (set (keys (skeleton ctx)))]
      (k/check! (= sk (set (get-in ctx [:result :adopt :wrote]))) "adopt lists " (pr-str (get-in ctx [:result :adopt :wrote])))
      ctx)))

(defstep #"a git repository that already holds dhf/spec/glossary.md"
  (fn [ctx] (repo! ctx {"dhf/spec/glossary.md" "# My glossary\n"})))

(defstep #"dhf/spec/glossary.md is unchanged"
  (fn [ctx] (k/check! (= "# My glossary\n" (k/slurp-rel ctx "dhf/spec/glossary.md")) "the glossary changed") ctx))

(defn kept [ctx] (set (get-in ctx [:result :adopt :kept])))

(defstep #"adopt names it as kept"
  (fn [ctx] (k/check! ((kept ctx) "dhf/spec/glossary.md") "adopt keeps " (pr-str (kept ctx))) ctx))

(defstep #"a git repository where dhf/harness/config.edn is a symbolic link"
  (fn [ctx]
    (let [ctx (repo! ctx {"elsewhere/config.edn" "{:id-prefix \"mine\"}\n"})]
      (fs/create-dirs (k/path ctx "dhf/harness"))
      (fs/create-sym-link (k/path ctx "dhf/harness/config.edn") "../../elsewhere/config.edn")
      ctx)))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (let [l (k/path ctx "dhf/harness/config.edn")]
      (k/check! (and (fs/sym-link? l) (= "../../elsewhere/config.edn" (str (fs/read-link l)))) "the link changed")
      (k/check! (= "{:id-prefix \"mine\"}\n" (k/slurp-rel ctx "elsewhere/config.edn")) "the target changed")
      ctx)))

(defstep #"adopt names the link as kept"
  (fn [ctx] (k/check! ((kept ctx) "dhf/harness/config.edn") "adopt keeps " (pr-str (kept ctx))) ctx))

(defstep #"a git repository with its own .gitignore"
  (fn [ctx] (repo! ctx {".gitignore" "node_modules/\n"})))

(defstep #"the .gitignore is unchanged"
  (fn [ctx] (k/check! (= "node_modules/\n" (k/slurp-rel ctx ".gitignore")) ".gitignore changed") ctx))

(defstep #"adopt names the lines it would have added, those init writes to .gitignore"
  (fn [ctx]
    (let [lines (get-in ctx [:result :adopt :gitignore-lines])]
      (k/check! (every? (set lines) ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"])
                "adopt names " (pr-str lines))
      ctx)))

(defstep #"a repository that has been adopted"
  (fn [ctx]
    (let [ctx (repo! ctx source)
          ctx (k/exits! (k/run-rdm ctx "adopt") 0)]
      (assoc ctx ::before (files ctx)))))

(defstep #"no file changes"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= (::before ctx) (files ctx)) "a file changed") ctx))

(defstep #"adopt names every destination as kept"
  (fn [ctx]
    (k/check! (empty? (get-in ctx [:result :adopt :wrote])) "adopt wrote " (pr-str (get-in ctx [:result :adopt :wrote])))
    (k/check! (= (set (keys (skeleton ctx))) (kept ctx)) "adopt keeps " (pr-str (kept ctx)))
    ctx))

(defstep #"a directory that is not a git repository"
  (fn [ctx] (let [b (k/scratch! "scenario-") ctx (assoc ctx :base b :dir (str b "/plain"))] (k/write! ctx "notes.txt" "notes\n"))))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-has! ctx "the hook needs a git repository")
    (k/check! (= {"notes.txt" "notes\n"} (files ctx)) "adopt wrote " (pr-str (keys (files ctx))))
    ctx))

(defstep #"a git repository holding source code and tests"
  (fn [ctx] (repo! ctx (assoc source "tests/test_main.py" "def test_main():\n    assert True\n"
                              "features/old.feature" "Feature: Old\n  Scenario: x\n    Given y\n"))))

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= #{:system} (set (keys (edn/read-string (k/slurp-rel ctx "dhf/spec/model.edn"))))) "the model is not empty")
    (k/check! (= {} (edn/read-string (k/slurp-rel ctx "dhf/spec/needs.edn"))) "the needs are not empty")
    (k/check! (= {:issued 0} (edn/read-string (k/slurp-rel ctx "dhf/spec/ids.edn"))) "the ledger is not at 0")
    ctx))

(defstep #"no feature file is written"
  (fn [ctx]
    (k/check! (empty? (fs/glob (k/path ctx "dhf") "**.feature")) "a feature file was written")
    ctx))
