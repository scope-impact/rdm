;; Step definitions for dhf/spec/project/init.feature. They drive the rdm-spec
;; command as a separate process, in temporary directories, and read what it
;; wrote. The When "\"rdm-spec ...\" is run" is the shared one
;; (steps.kernel-command): it runs in the scenario's :dir, which the Givens
;; here set.
(ns steps.project-init
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.project-support :as ps]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- passed! [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))

(defn- project-dir
  "The directory init was asked to write: `rel` under the scenario's :dir."
  [ctx rel]
  (str (fs/file (or (:dir ctx) (s/fail! "no directory was set")) rel)))

(defn- read-edn-file [f]
  (try (edn/read-string (slurp (str f)))
       (catch Exception e (s/fail! (str f " is not readable EDN: " (ex-message e))))))

(defn- config-of [dir] (read-edn-file (fs/file dir "dhf/harness/config.edn")))

(defn- text [dir rel] (slurp (str (fs/file dir rel))))

(defn- lines [t] (vec (remove str/blank? (map str/trim (str/split-lines t)))))

;; What each row of the skeleton table holds, checked on the file written.
(def ^:private holds-checks
  {"dhf/spec/model.edn"
   (fn [dir rel]
     (let [m (read-edn-file (fs/file dir rel))]
       (s/check! (map? m) (str rel " is not a map"))
       (s/check! (and (map? (:system m)) (keyword? (get-in m [:system :id]))
                      (string? (get-in m [:system :name])))
                 (str rel " holds no system: " (pr-str (:system m))))
       (s/check! (every? empty? (vals (dissoc m :system)))
                 (str rel " holds more than the system: " (pr-str (dissoc m :system))))))

   "dhf/spec/events.edn"
   (fn [dir rel] (s/check! (= {} (read-edn-file (fs/file dir rel))) (str rel " is not an empty catalogue")))

   "dhf/spec/needs.edn"
   (fn [dir rel] (s/check! (= {} (read-edn-file (fs/file dir rel))) (str rel " declares needs")))

   "dhf/spec/ids.edn"
   (fn [dir rel] (s/check! (= {:issued 0} (read-edn-file (fs/file dir rel))) (str rel " is not the ledger at 0")))

   "dhf/spec/glossary.md"
   (fn [dir rel]
     (let [t (text dir rel)]
       (s/check! (re-find #"(?m)^# \S" t) (str rel " has no title"))
       (s/check! (not (re-find #"(?m)^\*\*[^*]+\*\*:" t)) (str rel " defines a term"))))

   "dhf/spec/README.md"
   (fn [dir rel]
     (let [t (text dir rel)]
       (s/check! (re-find #"(?m)^## Conventions" t) (str rel " states no conventions"))
       (doseq [w ["one id" "level" "@wip" "stamp" "need"]]
         (s/check! (str/includes? (str/lower-case t) (str/lower-case w)) (str rel " says nothing of " w)))))

   "dhf/harness/config.edn"
   (fn [dir rel]
     (let [c (read-edn-file (fs/file dir rel))]
       (s/check! (map? c) (str rel " is not a map"))
       (s/check! (string? (:id-prefix c)) (str rel " sets no id prefix"))
       (doseq [k [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir
                  :steps-dir :results-dir :cycles-dir]]
         (s/check! (string? (get c k)) (str rel " sets no " k)))
       (s/check! (= ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]
                    (get-in c [:clean-room :allow]))
                 (str rel " allows " (pr-str (get-in c [:clean-room :allow]))))
       (s/check! (empty? (:implementation c)) (str rel " names implementation paths: " (pr-str (:implementation c))))
       (s/check! (empty? (:code-layout c)) (str rel " names a code layout: " (pr-str (:code-layout c))))))

   "dhf/raw/README.md"
   (fn [dir rel]
     (let [t (text dir rel)]
       (s/check! (re-find #"(?m)^## Rules" t) (str rel " states no rules"))
       (s/check! (str/includes? t "Never edited") (str rel " does not say a source is never edited"))))

   "dhf/raw/sources.edn"
   (fn [dir rel] (s/check! (= {} (read-edn-file (fs/file dir rel))) (str rel " records sources")))

   "dhf/wiki/index.md"
   (fn [dir rel]
     (let [t (text dir rel)
           [_ pages] (str/split t #"(?m)^## Pages\s*$" 2)]
       (s/check! (re-find #"(?m)^## Rules" t) (str rel " states no rules"))
       (s/check! pages (str rel " has no list of pages"))
       (s/check! (not (re-find #"\]\(" pages)) (str rel " lists a page"))))

   "dhf/decisions/README.md"
   (fn [dir rel]
     (let [t (text dir rel)]
       (s/check! (re-find #"(?m)^# \S" t) (str rel " has no title"))
       (s/check! (re-find #"(?i)decision" t) (str rel " says nothing of decisions"))))

   ".githooks/pre-commit"
   (fn [dir rel]
     (let [f (fs/file dir rel)
           t (text dir rel)]
       (s/check! (fs/executable? f) (str rel " is not executable"))
       (s/check! (re-find #"rdm-spec\"?\s+\"?check\b" t) (str rel " does not run rdm-spec check"))
       (s/check! (= ".githooks" (:out (ps/git dir "config" "--local" "--get" "core.hooksPath")))
                 "core.hooksPath is not .githooks")))

   ".github/workflows/cut.yml"
   (fn [dir rel]
     (let [t (text dir rel)]
       (s/check! (re-find #"(?m)^\s*run:\s*rdm-spec cut\s*$" t) (str rel " does not run rdm-spec cut"))
       (s/check! (re-find #"(?m)^\s*push:" t) (str rel " is not run on push"))
       (s/check! (str/includes? t "default_branch") (str rel " does not keep to the default branch"))))

   ".gitignore"
   (fn [dir rel]
     (s/check! (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]
                  (lines (text dir rel)))
               (str rel " holds " (pr-str (lines (text dir rel))))))})

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir rel))) (str rel " exists"))
      (assoc ctx :dir dir :path rel))))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)
          target (fs/file dir rel)]
      (fs/create-dirs target)
      (s/write! target "notes.md" "Someone's notes.\n")
      (assoc ctx :dir dir :path rel :before (ps/snapshot (str target) true)))))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)
          r (s/rdm-spec! dir "init" rel)]
      (s/check! (= 0 (:exit r)) (str "init: " (s/show r)))
      (assoc ctx :dir dir :path rel))))

;; ---------------------------------------------------------------------------
;; When

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line rel]
    (let [dir (project-dir ctx rel)]
      (assoc ctx :run (apply s/rdm-spec! dir (s/split-args line))))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx rel table]
    (passed! ctx)
    (let [dir (project-dir ctx rel)
          rows (vec (rest table))
          expected (set (map first rows))
          held (set (keys (ps/snapshot dir false)))]
      (s/check! (= (str (fs/canonicalize dir)) (ps/repo-root dir)) (str rel " is not a git repository's top level"))
      (s/check! (= expected held)
                (str rel " holds " (pr-str (sort held)) "\n missing: " (pr-str (sort (remove held expected)))
                     "\n extra: " (pr-str (sort (remove expected held)))))
      (doseq [[path _] rows]
        (if-let [check (get holds-checks path)]
          (check dir path)
          (s/fail! (str "no check for what " path " holds")))))))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (passed! ctx)
    (let [res (ps/result (the-run ctx))
          dir (project-dir ctx (:path ctx))
          on-disk (set (keys (ps/snapshot dir false)))]
      (s/check! (= on-disk (set (:wrote res))) (str "init lists " (pr-str (:wrote res))))
      (s/check! (= (count (:wrote res)) (count (set (:wrote res)))) "init lists a file twice")
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res))
                     (every? #(and (string? %) (not (str/blank? %))) (:next-steps res)))
                (str "init names no next steps: " (pr-str (:next-steps res)))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/blank? (:out r)) (str "stdout is not empty\n" (s/show r)))
      (s/check! (str/includes? (:err r) (:path ctx)) (str "the error stream does not name " (:path ctx) "\n" (s/show r))))))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx rel]
    (let [d (ps/diff (:before ctx) (ps/snapshot (project-dir ctx rel) true))]
      (s/check! (empty? d) (str "changed: " d)))))

(defn- init-path
  "The path the run's init was given: its first argument after the task."
  [ctx]
  (let [[task path] (:args (the-run ctx))]
    (s/check! (= "init" task) (str "the run was not init: " (pr-str (:args (the-run ctx)))))
    path))

(defn- prefix-set! [ctx prefix]
  (passed! ctx)
  (let [c (config-of (project-dir ctx (init-path ctx)))]
    (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix] (prefix-set! ctx prefix)))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix] (prefix-set! ctx prefix)))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) "a prefix starts with a letter and holds letters, digits and single hyphens")
                (s/show r)))))

;; "it passes" is shared in wording with dhf/spec/spec/check.feature: defined
;; here, when loading, unless a file loaded earlier defines it, so it is there
;; for both and never twice.
(when-not (some #(re-matches (:pattern %) "it passes") @rdm-spec.kernel.runner/*registry*)
  (defstep #"it passes"
    (fn [ctx]
      (let [r (:run ctx)]
        (s/check! r "no command was run")
        (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))))
