;; Step definitions for dhf/spec/project/init.feature. They drive the rdm-spec
;; command as a separate process in temporary directories, and read what init
;; wrote as a person would: as files, never through init's own code.
(ns steps.project-init
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.project-support :as ps]
            [steps.support :as s]))

;; --- helpers ----------------------------------------------------------------

(defn- passed! [ctx]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 0 (:exit r)) (s/show r))))

(defn- init-path
  "The PATH the run gave init: its first argument that is not an option or an
  option's value."
  [ctx]
  (let [args (:args (:run ctx))]
    (s/check! (= "init" (first args)) (str "the run was not init: " (pr-str args)))
    (loop [[x & more] (rest args)]
      (cond (nil? x) (s/fail! (str "init was given no path: " (pr-str args)))
            (str/starts-with? x "--") (recur (rest more))
            :else x))))

(defn- project-dir [ctx] (str (fs/file (:dir ctx) (init-path ctx))))

(defn- edn-of [dir rel]
  (let [f (fs/file dir rel)]
    (s/check! (fs/regular-file? f) (str rel " was not written"))
    (try (edn/read-string (slurp (str f)))
         (catch Exception e (s/fail! (str rel " is not EDN: " (ex-message e)))))))

(defn- text-of [dir rel]
  (let [f (fs/file dir rel)]
    (s/check! (fs/regular-file? f) (str rel " was not written"))
    (slurp (str f))))

(defn- listed
  "The items a table cell lists: \"a, b, c and d\" -> [a b c d]."
  [cell]
  (vec (remove str/blank? (map str/trim (str/split cell #",\s*|\s+and\s+")))))

(defn- after
  "The part of `cell` after `marker`, up to the next \", and \" or the end."
  [cell marker]
  (let [i (str/index-of cell marker)]
    (s/check! i (str "the table cell does not say " (pr-str marker) ": " cell))
    (first (str/split (subs cell (+ i (count marker))) #",\s+and\s+"))))

(defn- markdown-heading! [rel text]
  (s/check! (re-find #"(?m)^# \S" text) (str rel " has no title")))

;; --- what each skeleton file holds -------------------------------------------
;; One check per path of the table; a path the table lists that has no check
;; here fails, so a new row cannot pass unread.

(def ^:private room-path-keys
  "The configuration keys naming the files and directories the tasks read."
  [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir :steps-dir :results-dir :cycles-dir])

(def ^:private holds
  {"dhf/spec/model.edn"
   (fn [dir rel _]
     (let [m (edn-of dir rel)]
       (s/check! (map? m) (str rel " is not a map"))
       (s/check! (map? (:system m)) (str rel " holds no system"))
       (s/check! (keyword? (get-in m [:system :id])) (str rel ": the system has no keyword id"))
       (s/check! (= (str (fs/file-name dir)) (get-in m [:system :name]))
                 (str rel ": the system is not named after the project: " (pr-str (:system m))))
       (s/check! (every? #(and (coll? %) (empty? %)) (vals (dissoc m :system)))
                 (str rel " holds more than the system: " (pr-str (dissoc m :system))))))

   "dhf/spec/events.edn"
   (fn [dir rel _] (s/check! (= {} (edn-of dir rel)) (str rel " is not an empty catalogue")))

   "dhf/spec/needs.edn"
   (fn [dir rel _] (s/check! (= {} (edn-of dir rel)) (str rel " declares needs")))

   "dhf/spec/ids.edn"
   (fn [dir rel _] (s/check! (= {:issued 0} (edn-of dir rel)) (str rel " is not the ledger at 0")))

   "dhf/spec/glossary.md"
   (fn [dir rel _]
     (let [t (text-of dir rel)]
       (markdown-heading! rel t)
       (s/check! (not (re-find #"(?m)^\*\*[^*]+\*\*:" t)) (str rel " defines a term"))))

   "dhf/spec/README.md"
   (fn [dir rel _]
     (let [t (text-of dir rel)
           prefix (:id-prefix (edn-of dir "dhf/harness/config.edn"))]
       (markdown-heading! rel t)
       (s/check! (re-find #"(?m)^## Conventions" t) (str rel " states no conventions"))
       (doseq [w ["@e2e" "@contract" "@wip" "@UN-" "rdm-spec stamp" "rdm-spec check" (str "@" prefix "-NNNN")]]
         (s/check! (str/includes? t w) (str rel "'s conventions do not mention " w)))))

   "dhf/harness/config.edn"
   (fn [dir rel cell]
     (let [c (edn-of dir rel)
           allow (listed (after cell "the allow-list "))]
       (s/check! (map? c) (str rel " is not a map"))
       (s/check! (and (string? (:id-prefix c)) (not (str/blank? (:id-prefix c)))) (str rel " sets no id prefix"))
       (doseq [k room-path-keys]
         (s/check! (string? (get c k)) (str rel " sets no " k)))
       (doseq [k [:ledger :model :events :needs]]
         (s/check! (fs/regular-file? (fs/file dir (get c k))) (str rel ": " k " names no file init wrote")))
       (doseq [k [:spec-dir :raw-dir :wiki-dir]]
         (s/check! (fs/directory? (fs/file dir (get c k))) (str rel ": " k " names no directory init wrote")))
       (s/check! (map? (:site c)) (str rel " sets no :site"))
       (s/check! (= allow (get-in c [:clean-room :allow]))
                 (str rel ": the allow-list is " (pr-str (get-in c [:clean-room :allow])) ", not " (pr-str allow)))
       (s/check! (empty? (:implementation c)) (str rel " names implementation paths: " (pr-str (:implementation c))))
       (s/check! (empty? (:code-layout c)) (str rel " lays out code: " (pr-str (:code-layout c))))))

   "dhf/raw/README.md"
   (fn [dir rel _]
     (let [t (text-of dir rel)]
       (markdown-heading! rel t)
       (s/check! (re-find #"(?m)^## Rules" t) (str rel " states no rules"))))

   "dhf/raw/sources.edn"
   (fn [dir rel _] (s/check! (= {} (edn-of dir rel)) (str rel " records sources")))

   "dhf/wiki/index.md"
   (fn [dir rel _]
     (let [t (text-of dir rel)
           pages (second (str/split t #"(?m)^## Pages\s*$"))]
       (markdown-heading! rel t)
       (s/check! (re-find #"(?m)^## Rules" t) (str rel " states no rules"))
       (s/check! pages (str rel " has no list of pages"))
       (s/check! (not (re-find #"(?m)^\s*[-*] |\]\(" pages)) (str rel " lists pages: " pages))))

   "dhf/decisions/README.md"
   (fn [dir rel _]
     (let [t (text-of dir rel)]
       (markdown-heading! rel t)
       (s/check! (re-find #"(?i)decision" t) (str rel " says nothing of decisions"))))

   ".githooks/pre-commit"
   (fn [dir rel _]
     (let [t (text-of dir rel)]
       (s/check! (fs/executable? (fs/file dir rel)) (str rel " is not executable"))
       (s/check! (re-find #"rdm-spec\"?\s+\"?check\b" t) (str rel " does not run rdm-spec check"))
       (s/check! (= ".githooks" (:out (ps/git dir "config" "--get" "core.hooksPath")))
                 "core.hooksPath is not .githooks")))

   ".github/workflows/cut.yml"
   (fn [dir rel _]
     (let [t (text-of dir rel)]
       (s/check! (re-find #"(?m)^\s*run:\s*rdm-spec cut\s*$" t) (str rel " does not run rdm-spec cut"))
       (s/check! (re-find #"(?m)^\s*push:" t) (str rel " does not run on push"))
       (s/check! (str/includes? t "default_branch") (str rel " is not bound to the default branch"))))

   ".gitignore"
   (fn [dir rel cell]
     (let [want (listed cell)
           have (vec (remove str/blank? (map str/trim (str/split-lines (text-of dir rel)))))]
       (s/check! (= want have) (str rel " holds " (pr-str have) ", not " (pr-str want)))))})

;; --- Givens -----------------------------------------------------------------

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir rel))) (str rel " exists"))
      (assoc ctx :dir dir))))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str rel "/notes.md") "Ours, not init's.\n")
      (assoc ctx :dir dir :held rel :before (ps/snapshot (fs/file dir rel) true)))))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx rel]
    (let [dir (s/tmp-dir)
          r (s/rdm-spec! dir "init" rel)]
      (s/check! (= 0 (:exit r)) (str "init: " (s/show r)))
      (assoc ctx :dir dir :project rel))))

;; --- When -------------------------------------------------------------------

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line rel]
    (when (= "check" (first (s/split-args line)))
      (apply s/needs-built! ps/check-stages))
    (ps/run-in ctx (fs/file (:dir ctx) rel) (s/split-args line))))

;; --- Thens ------------------------------------------------------------------

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx rel table]
    (passed! ctx)
    (let [dir (str (fs/file (:dir ctx) rel))
          rows (vec (rest table))
          files (set (keys (ps/snapshot dir false)))]
      (s/check! (= (str (fs/canonicalize dir)) (ps/repo-root dir)) (str rel " is not a git repository"))
      (s/check! (= (set (map first rows)) files)
                (str rel " holds " (pr-str (sort files)) ", not " (pr-str (sort (map first rows)))))
      (doseq [[path cell] rows]
        (let [check (or (get holds path) (s/fail! (str "no check for what " path " holds")))]
          (check dir path cell)))
      (assoc ctx :project rel :table rows))))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (passed! ctx)
    (let [res (ps/result (:run ctx))
          files (sort (keys (ps/snapshot (project-dir ctx) false)))]
      (s/check! (= files (sort (:wrote res))) (str "init lists " (pr-str (:wrote res)) ", not " (pr-str files)))
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res))
                     (every? #(and (string? %) (not (str/blank? %))) (:next-steps res)))
                (str "init names no next steps: " (pr-str (:next-steps res)))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/includes? (:err r) (init-path ctx)) (str "the error stream does not name the path\n" (s/show r))))))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx rel]
    (let [d (ps/diff (:before ctx) (ps/snapshot (fs/file (:dir ctx) rel) true))]
      (s/check! (empty? d) (str "changed in " rel ": " d)))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (passed! ctx)
    (let [c (edn-of (project-dir ctx) "dhf/harness/config.edn")]
      (s/check! (= prefix (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c)))))))

;; Shared with dhf/spec/spec/check.feature, whose step file defines it too
;; unless one loaded earlier did: the run exited 0.
(when-not (seq (runner/matching "it passes"))
  (defstep #"it passes"
    (fn [ctx]
      (let [r (:run ctx)]
        (s/check! r "no command was run")
        (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))))
