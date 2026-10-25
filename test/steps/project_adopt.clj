(ns steps.project-adopt
  "Step definitions for dhf/spec/project/adopt.feature. Every scenario makes
  its own git repository (a fixture) and runs `rdm-spec adopt` in it as its
  own process. \"it exits N\" is the kernel's (steps.kernel-command).

  What \"the skeleton, as rdm-spec init writes it\" is, is read from init
  itself: init is run into a directory of the same name elsewhere, and every
  skeleton file must be byte for byte the same.

  Context keys: :dir the repository, :run the last run, :before a snapshot
  of the repository (.git included) before adopt, :kept-path the path a
  Given placed, :link-target the target of a symbolic link."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(def ^:private source-files
  {"src/app.c" "int main(void) { return 0; }\n"
   "README.md" "# An app\n\nIt was here first.\n"})

(def ^:private test-files
  {"tests/app_test.c" "int test(void) { return 0; }\n"})

(defn- repo! [ctx files]
  (let [dir (ps/git-repo! files)]
    (assoc ctx :dir dir :before (ps/snapshot dir))))

(defn- adopt-result [ctx]
  (let [r (kc/the-run ctx)]
    (or (:adopt (kc/stdout-edn r)) (s/fail! (str "adopt printed no :adopt result\n" (s/show r))))))

(defn- unchanged! [ctx paths]
  (let [now (ps/snapshot (:dir ctx))
        changed (filter #(not= (get (:before ctx) %) (get now %)) paths)]
    (s/check! (empty? changed) (str "changed: " (pr-str changed)))
    nil))

(defn- init-reference
  "The skeleton init writes for a project named like `dir`: {rel content}."
  [ctx dir]
  (let [parent (s/tmp-dir)
        name (str (fs/file-name dir))
        r (kc/run! ctx parent ["init" name])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (into {} (for [rel (ps/skeleton-paths)]
               [rel (slurp (str (fs/file parent name rel)))]))))

;; ---------------------------------------------------------------------------
;; Adopt adds and never overwrites

(defstep #"a git repository holding source code and a README"
  (fn [ctx] (repo! ctx source-files)))

(defstep #"a git repository holding source code and tests"
  (fn [ctx] (repo! ctx (merge source-files test-files))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx line] (kc/run-line ctx line)))

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [want (init-reference ctx (:dir ctx))]
      (doseq [[rel content] want
              :let [f (fs/file (:dir ctx) rel)]]
        (s/check! (fs/regular-file? f) (str rel " was not written"))
        (s/check! (= content (slurp (str f))) (str rel " differs from what init writes")))
      (s/check! (fs/executable? (fs/file (:dir ctx) ".githooks/pre-commit")) "the hook is not executable")
      (s/check! (= ".githooks" (:out (ps/git (:dir ctx) "config" "--local" "--get" "core.hooksPath")))
                "core.hooksPath is not .githooks")
      nil)))

(defstep #"its source code and README are unchanged"
  (fn [ctx] (unchanged! ctx (keys source-files))))

(defstep #"adopt lists every file it wrote"
  (fn [ctx]
    (let [res (adopt-result ctx)]
      (s/check! (= (set (ps/skeleton-paths)) (set (:wrote res))) (str "adopt lists " (pr-str (:wrote res))))
      nil)))

(defstep #"a git repository that already holds (\S+)"
  (fn [ctx rel]
    (assoc (repo! ctx (assoc source-files rel "# Our own glossary\n")) :kept-path rel)))

(defstep #"(dhf/\S+) is unchanged"
  (fn [ctx rel] (unchanged! ctx [rel])))

(defstep #"adopt names it as kept"
  (fn [ctx]
    (let [res (adopt-result ctx)]
      (s/check! (some #{(:kept-path ctx)} (:kept res)) (str "adopt keeps " (pr-str (:kept res))))
      (s/check! (not (some #{(:kept-path ctx)} (:wrote res))) "adopt lists it as written")
      nil)))

(defstep #"a git repository where (\S+) is a symbolic link"
  (fn [ctx rel]
    (let [ctx (repo! ctx (assoc source-files "config/ours.edn" "{:ours true}\n"))
          link (fs/file (:dir ctx) rel)]
      (fs/create-dirs (fs/parent link))
      (fs/create-sym-link link (fs/relativize (fs/parent link) (fs/file (:dir ctx) "config/ours.edn")))
      (assoc ctx :kept-path rel :link-target "config/ours.edn" :before (ps/snapshot (:dir ctx))))))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (s/check! (fs/sym-link? (fs/file (:dir ctx) (:kept-path ctx))) "the link is gone")
    (unchanged! ctx [(:kept-path ctx) (:link-target ctx)])))

(defstep #"adopt names the link as kept"
  (fn [ctx]
    (let [res (adopt-result ctx)]
      (s/check! (some #{(:kept-path ctx)} (:kept res)) (str "adopt keeps " (pr-str (:kept res))))
      nil)))

(defstep #"a git repository with its own \.gitignore"
  (fn [ctx] (repo! ctx (assoc source-files ".gitignore" "build/\n*.o\n"))))

(defstep #"the \.gitignore is unchanged"
  (fn [ctx] (unchanged! ctx [".gitignore"])))

(defstep #"adopt names the lines it would have added, those init writes to \.gitignore"
  (fn [ctx]
    (let [res (adopt-result ctx)
          init-lines (-> (init-reference ctx (:dir ctx)) (get ".gitignore") str/split-lines)
          init-lines (vec (remove str/blank? init-lines))]
      (s/check! (= init-lines (vec (:gitignore-lines res)))
                (str "adopt names " (pr-str (:gitignore-lines res)) ", init writes " (pr-str init-lines)))
      (s/check! (some #{".gitignore"} (:kept res)) "adopt does not name .gitignore as kept")
      nil)))

(defstep #"a repository that has been adopted"
  (fn [ctx]
    (let [dir (ps/git-repo! source-files)
          r (kc/run! ctx dir ["adopt"])]
      (s/check! (zero? (:exit r)) (str "the first adopt failed\n" (s/show r)))
      (assoc ctx :dir dir :before (ps/snapshot dir)))))

(defstep #"no file changes"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [d (ps/diff (:before ctx) (ps/snapshot (:dir ctx)))]
      (s/check! (empty? d) (str "changed: " (pr-str d)))
      nil)))

(defstep #"adopt names every destination as kept"
  (fn [ctx]
    (let [res (adopt-result ctx)]
      (s/check! (= (set (ps/skeleton-paths)) (set (:kept res))) (str "adopt keeps " (pr-str (:kept res))))
      (s/check! (empty? (:wrote res)) (str "adopt wrote " (pr-str (:wrote res))))
      nil)))

(defstep #"a directory that is not a git repository"
  (fn [ctx]
    (let [dir (s/tmp-dir)]
      (s/write! dir "notes.txt" "Not a repository.\n")
      (s/check! (nil? (ps/repo-root dir)) (str dir " is in a git repository"))
      (assoc ctx :dir dir :before (ps/snapshot dir)))))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (str/blank? (:out r)) (str "a refusal printed a result\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % "cannot-run: ") (str/includes? % "hook needs a git repository"))
                      (s/err-lines r))
                (str "it does not say the hook needs a git repository\n" (s/show r)))
      (let [d (ps/diff (:before ctx) (ps/snapshot (:dir ctx)))]
        (s/check! (empty? d) (str "written: " (pr-str d))))
      nil)))

;; ---------------------------------------------------------------------------
;; Adopt never reads the existing code into the spec

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [read #(clojure.edn/read-string (slurp (str (fs/file (:dir ctx) %))))
          m (read "dhf/spec/model.edn")]
      (s/check! (every? empty? (vals (dissoc m :system))) (str "the model holds more than the system: " (pr-str m)))
      (s/check! (= {} (read "dhf/spec/needs.edn")) "the needs are not empty")
      (s/check! (= {:issued 0} (read "dhf/spec/ids.edn")) "the ledger is not at 0")
      nil)))

(defstep #"no feature file is written"
  (fn [ctx]
    (let [fs* (map str (fs/glob (:dir ctx) "**.feature" {:hidden true}))]
      (s/check! (empty? fs*) (str "feature files written: " (pr-str fs*)))
      nil)))
