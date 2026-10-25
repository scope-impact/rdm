;; Step definitions for dhf/spec/project/adopt.feature. They drive the rdm-spec
;; command as a separate process in temporary git repositories.
(ns steps.project-adopt
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.project-support :as ps]
            [steps.support :as s]))

(def code-and-readme
  {"README.md" "# Thermometer\n\nReads the sauna's temperature.\n"
   "src/thermometer/core.c" "int read_temperature(void) { return 80; }\n"})

(def code-and-tests
  (merge code-and-readme
         {"test/thermometer/core_test.c" "/* reads 80 */\nint main(void) { return read_temperature() == 80 ? 0 : 1; }\n"
          "docs/requirements.txt" "The sauna shall read its temperature.\n"}))

(defn- adopted [ctx] (ps/result (:run ctx)))

(defn- passed! [ctx]
  (let [r (:run ctx)]
    (s/check! (= 0 (:exit r)) (s/show r))))

;; --- Givens -----------------------------------------------------------------

(defstep #"a git repository holding source code and a README"
  (fn [_]
    (let [dir (ps/git-repo! code-and-readme)]
      {:dir dir :before (ps/snapshot dir false) :theirs (keys code-and-readme)})))

(defstep #"a git repository holding source code and tests"
  (fn [_]
    (let [dir (ps/git-repo! code-and-tests)]
      {:dir dir :before (ps/snapshot dir false) :theirs (keys code-and-tests)})))

(defstep #"a git repository that already holds (dhf/\S+)"
  (fn [_ rel]
    (let [dir (ps/git-repo! {rel "# Our own words\n\n**Löyly**: the steam.\n"})]
      {:dir dir :held rel :before (ps/snapshot dir false)})))

(defstep #"a git repository where (dhf/\S+) is a symbolic link"
  (fn [_ rel]
    (let [dir (ps/git-repo! {"shared/config.edn" "{:id-prefix \"shared\"}\n"})
          link (fs/file dir rel)]
      (fs/create-dirs (fs/parent link))
      (fs/create-sym-link link (fs/relativize (fs/parent link) (fs/file dir "shared/config.edn")))
      {:dir dir :held rel :before (ps/snapshot dir false)})))

(defstep #"a git repository with its own \.gitignore"
  (fn [_]
    (let [dir (ps/git-repo! {".gitignore" "node_modules/\n*.log\n"})]
      {:dir dir :held ".gitignore" :before (ps/snapshot dir false)})))

(defstep #"a repository that has been adopted"
  (fn [_]
    (let [dir (ps/git-repo! code-and-readme)
          r (s/rdm-spec! dir "adopt")]
      (s/check! (= 0 (:exit r)) (s/show r))
      {:dir dir :before (ps/snapshot dir true)})))

(defstep #"a directory that is not a git repository"
  (fn [_]
    (let [dir (s/tmp-dir)]
      (s/write! dir "notes.txt" "not under version control\n")
      (s/check! (nil? (ps/repo-root dir)) (str dir " is inside a git repository"))
      {:dir dir :before (ps/snapshot dir true)})))

;; --- When -------------------------------------------------------------------

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx command-line]
    (ps/run-in ctx (:dir ctx) (s/split-args command-line))))

;; --- Thens ------------------------------------------------------------------

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (passed! ctx)
    ;; init writes a project named after its directory, so init one by the
    ;; same name and compare every file of the skeleton
    (let [dir (:dir ctx)
          name (fs/file-name dir)
          other (s/tmp-dir)
          r (s/rdm-spec! other "init" name)
          fresh (str (fs/file other name))]
      (s/check! (= 0 (:exit r)) (str "init: " (s/show r)))
      (let [a (ps/snapshot dir false)
            b (ps/snapshot fresh false)]
        (doseq [p (ps/skeleton-paths)]
          (s/check! (= (get b p) (get a p)) (str p " differs from what init writes"))))
      (s/check! (= ".githooks" (:out (ps/git dir "config" "--get" "core.hooksPath"))) "core.hooksPath is not .githooks"))))

(defstep #"its source code and README are unchanged"
  (fn [ctx]
    (let [now (ps/snapshot (:dir ctx) false)]
      (doseq [p (:theirs ctx)]
        (s/check! (= (get (:before ctx) p) (get now p)) (str p " changed"))))))

(defstep #"adopt lists every file it wrote"
  (fn [ctx]
    (passed! ctx)
    (let [res (adopted ctx)
          written (set (remove (set (keys (:before ctx))) (keys (ps/snapshot (:dir ctx) false))))]
      (s/check! (= (set (ps/skeleton-paths)) written) (str "adopt wrote " (sort written)))
      (s/check! (= (sort written) (sort (:wrote res))) (str "adopt lists " (pr-str (:wrote res)))))))

(defstep #"(dhf/spec/glossary\.md) is unchanged"
  (fn [ctx rel]
    (let [now (ps/snapshot (:dir ctx) false)]
      (s/check! (= (get (:before ctx) rel) (get now rel)) (str rel " changed")))))

(defstep #"adopt names (?:it|the link) as kept"
  (fn [ctx]
    (passed! ctx)
    (let [res (adopted ctx)]
      (s/check! (some #{(:held ctx)} (:kept res)) (str "kept: " (pr-str (:kept res))))
      (s/check! (not (some #{(:held ctx)} (:wrote res))) (str "wrote: " (pr-str (:wrote res)))))))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (let [now (ps/snapshot (:dir ctx) false)
          rel (:held ctx)]
      (s/check! (fs/sym-link? (fs/file (:dir ctx) rel)) (str rel " is no longer a link"))
      (s/check! (= (get (:before ctx) rel) (get now rel)) "the link changed")
      (s/check! (= (get (:before ctx) "shared/config.edn") (get now "shared/config.edn")) "the link's target changed"))))

(defstep #"the \.gitignore is unchanged"
  (fn [ctx]
    (let [now (ps/snapshot (:dir ctx) false)]
      (s/check! (= (get (:before ctx) ".gitignore") (get now ".gitignore")) ".gitignore changed"))))

(defstep #"adopt names the lines it would have added, those init writes to \.gitignore"
  (fn [ctx]
    (passed! ctx)
    (let [res (adopted ctx)
          other (s/tmp-dir)
          r (s/rdm-spec! other "init" "fresh")
          lines (vec (remove str/blank? (str/split-lines (slurp (str (fs/file other "fresh/.gitignore"))))))]
      (s/check! (= 0 (:exit r)) (str "init: " (s/show r)))
      (s/check! (some #{".gitignore"} (:kept res)) (str "kept: " (pr-str (:kept res))))
      (s/check! (= lines (:gitignore-lines-to-add res)) (str "adopt names " (pr-str (:gitignore-lines-to-add res))))
      (doseq [l lines]
        (s/check! (str/includes? (:err (:run ctx)) l) (str "the error stream does not name " l))))))

(defstep #"no file changes"
  (fn [ctx]
    (passed! ctx)
    (let [d (ps/diff (:before ctx) (ps/snapshot (:dir ctx) true))]
      (s/check! (empty? d) (str "changed: " d)))))

(defstep #"adopt names every destination as kept"
  (fn [ctx]
    (let [res (adopted ctx)]
      (s/check! (= (sort (ps/skeleton-paths)) (sort (:kept res))) (str "kept: " (pr-str (:kept res))))
      (s/check! (empty? (:wrote res)) (str "wrote: " (pr-str (:wrote res)))))))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (re-find #"hook needs a git repository" (:err r)) (s/show r))
      (let [d (ps/diff (:before ctx) (ps/snapshot (:dir ctx) true))]
        (s/check! (empty? d) (str "written: " d))))))

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          m (ps/read-edn (fs/file dir "dhf/spec/model.edn"))]
      (s/check! (every? empty? (vals (dissoc m :system))) (str "the model holds " (pr-str (dissoc m :system))))
      (s/check! (= {} (ps/read-edn (fs/file dir "dhf/spec/needs.edn"))) "needs are declared")
      (s/check! (= {:issued 0} (ps/read-edn (fs/file dir "dhf/spec/ids.edn"))) "the ledger is not at 0"))))

(defstep #"no feature file is written"
  (fn [ctx]
    (let [features (fs/glob (:dir ctx) "**.feature" {:hidden true})]
      (s/check! (empty? features) (str "feature files: " (vec (map str features)))))))
