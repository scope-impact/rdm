(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  A fence fixture is a throwaway repository (`fence-project!`, after
  steps.regeneration-support/cut-project!): a copy of the room's allow-listed
  paths, files outside the allow-list, the room's implementation, and an
  oracle directory, committed and tagged spec-3; an earlier commit held a
  previous build the cut deleted, and a later commit holds work after the
  cut. Neither may ever reach a clean room.

  Context keys: :dir (the repository, where the shared When runs the
  command), :cut and :commit (the cut's tag and commit), :allow (the
  configured allow-list at the cut), :room (ROOM), :run (the last run)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence 'rdm-spec.regeneration.cut))

;; ---------------------------------------------------------------------------
;; The fixture

(def oracle-files
  "An oracle directory the cut holds: the instrument's goldens, which a clean
  room must never hold. It names no fixture cycle, so the oracle gate judges
  nothing and passes."
  {"dhf/harness/oracle/RECORDED.md" "# Why each golden was re-recorded\n"
   "dhf/harness/oracle/goldens/01/checklist.json" "{\"cycle\": \"01\", \"measurement\": \"never in a clean room\"}\n"})

(defn- set-allow!
  "Sets the configuration's :clean-room :allow in `dir` to `allow`."
  [dir allow]
  (let [f (fs/file dir "dhf/harness/config.edn")
        cfg (edn/read-string (slurp (str f)))]
    (spit (str f) (pr-str (assoc-in cfg [:clean-room :allow] (vec allow))))))

(defn fence-project!
  "A repository whose commit tagged spec-3 holds the room's allow-listed
  paths (less `drop`), the files outside the allow-list, `extra` files, the
  room's implementation, and a configuration allowing `allow` (default: the
  room's own). An earlier commit held a previous build; a later commit holds
  work after the cut. Returns ctx keys {:dir :cut :commit :allow}."
  [& [{:keys [allow drop extra] :or {drop [] extra {}}}]]
  (let [dir (s/copy-of-room!)
        tag "spec-3"]
    (s/write! dir rs/secret-path rs/secret-text)
    (rs/commit! dir "the previous build")
    (fs/delete (fs/file dir rs/secret-path))
    (doseq [d drop] (fs/delete-tree (fs/file dir d)))
    (doseq [[f t] (merge rs/outside-files extra)] (s/write! dir f t))
    (rs/copy-implementation! dir)
    (when allow (set-allow! dir allow))
    (let [c (rs/commit! dir (str "the spec at " tag))]
      (rs/git! dir "tag" tag)
      (s/write! dir "dhf/spec/after-the-cut.md" "written after the cut\n")
      (rs/commit! dir "after the cut")
      {:dir dir :cut tag :commit c
       :allow (or allow (get-in (s/room-config) [:clean-room :allow]))})))

(defn- fixture! [ctx & [opts]]
  (needs-fence!)
  (merge ctx (fence-project! opts)))

;; ---------------------------------------------------------------------------
;; Reading the cut and the room

(defn- cut-files [ctx] (rs/cut-files (:dir ctx) (:commit ctx)))

(defn- room-files [ctx] (rs/files-under (:room ctx)))

(defn- config [] (s/room-config))

(defn- implementation? [rel]
  (some #(rs/under? rel %) (:implementation (config))))

(defn- allowed? [allow rel] (some #(rs/under? rel %) allow))

(defn- layout-prefixes
  "The configured code layout's prefixes for a context or a component
  (\"spec/parser\"), {component} written with - as _."
  [kind scope]
  (let [[c n] (str/split scope #"/" 2)]
    (for [p (get-in (config) [:code-layout kind])]
      (-> p (str/replace "{context}" c) (str/replace "{component}" (str/replace (str n) "-" "_"))))))

(defn- scope-prefixes
  "Every layout prefix the run's --context and --component name."
  [args]
  (let [opt (fn [o] (->> (partition 2 1 args)
                         (keep (fn [[a b]] (when (= o a) b)))
                         (mapcat #(str/split % #","))))]
    (concat (mapcat #(layout-prefixes :context %) (opt "--context"))
            (mapcat #(layout-prefixes :component %) (opt "--component")))))

(defn- removed? [prefixes rel] (some #(str/starts-with? rel %) prefixes))

(defn- built! [ctx]
  (let [r (kc/exits! ctx 0)]
    (s/check! (fs/directory? (fs/file (:room ctx) ".git")) (str "ROOM is not a repository\n" (s/show r)))
    r))

(defn- refused! [ctx]
  (let [r (kc/exits! ctx 2)
        line (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
    (s/check! (str/blank? (:out r)) (str "a fence that cannot run printed a result\n" (s/show r)))
    (s/check! line (str "no cannot-run line\n" (s/show r)))
    (s/check! (or (not (fs/exists? (:room ctx))) (empty? (fs/list-dir (:room ctx))))
              (str "a refused fence built a room\n" (s/show r)))
    line))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the commit (\w+) carries no spec-N tag"
  (fn [ctx name]
    (let [ctx (fixture! ctx)
          dir (:dir ctx)
          head (rs/git! dir "rev-parse" "HEAD")]
      ;; the name is made to name a commit after the cut, which carries no tag
      (rs/git! dir "branch" name head)
      (s/check! (= head (rs/git! dir "rev-parse" name)) (str name " does not name the commit"))
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (rs/tags-at dir head)))
                (str name " carries a spec-N tag"))
      ctx)))

(defstep #"the configuration allows (.+), and the cut also holds (\S+)/"
  (fn [ctx allowed also]
    (let [allow (vec (map str/trim (str/split allowed #",\s*|\s+and\s+")))
          ctx (fixture! ctx {:allow allow :extra oracle-files})
          files (keys (cut-files ctx))]
      (s/check! (some #(rs/under? % also) files) (str "the cut holds nothing under " also))
      (s/check! (not (allowed? allow also)) (str also " is allowed"))
      (doseq [a allow]
        (s/check! (some #(rs/under? % a) files) (str "the cut holds no " a)))
      ctx)))

(defstep #"the configuration allows (\S+) and the cut holds no (\S+)"
  (fn [ctx allowed missing]
    (let [ctx (fixture! ctx {:drop [missing]})]
      (s/check! (some #{allowed} (:allow ctx)) (str "the configuration does not allow " allowed))
      (s/check! (not-any? #(rs/under? % missing) (keys (cut-files ctx))) (str "the cut holds " missing))
      ctx)))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx]
    (let [ctx (fixture! ctx)]
      (s/check! (some implementation? (keys (cut-files ctx))) "the cut holds no implementation")
      ctx)))

(defstep #"the code layout places (context|component) code at (\S+) and its unit tests at (\S+)"
  (fn [ctx kind code tests]
    (let [ctx (fixture! ctx)
          as-template (fn [p] (-> p (str/replace "<context>" "{context}") (str/replace "<component>" "{component}")))
          layout (set (get-in (config) [:code-layout (keyword kind)]))]
      (doseq [p [code tests]]
        (s/check! (contains? layout (as-template p)) (str "the code layout does not place " p)))
      ctx)))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (let [line (refused! ctx)]
      (s/check! (re-find #"(?i)regeneration reads only (a )?cuts?\b" line)
                (str "the refusal does not say regeneration reads only a cut: " line)))))

(defstep #"it exits 2 and names (dhf/\S+)"
  (fn [ctx path]
    (let [line (refused! ctx)]
      (s/check! (str/includes? line path) (str "the refusal does not name " path ": " line)))))

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx scope]
    (let [line (refused! ctx)
          model (edn/read-string (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
          contexts (for [c (:containers model) x (:contexts c)] (name (:id x)))]
      (s/check! (seq contexts) "the model has no bounded context")
      (doseq [n (concat [scope "kernel"] contexts)]
        (s/check! (re-find (re-pattern (str "\\b" n "\\b")) line) (str "the refusal does not name " n ": " line))))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (built! ctx)
    (let [room (:room ctx)
          commits (rs/lines (rs/git! room "rev-list" "--all"))
          parents (str/split (rs/git! room "rev-list" "--parents" "-n" "1" "HEAD") #"\s+")]
      (s/check! (= 1 (count commits)) (str "ROOM holds " (count commits) " commits"))
      (s/check! (= 1 (count parents)) "ROOM's commit has a parent")
      (s/check! (str/blank? (rs/git! room "status" "--porcelain" "--untracked-files=all"))
                "ROOM's working tree differs from its commit"))))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (built! ctx)
    (let [expected (into (sorted-map) (filter #(allowed? (:allow ctx) (key %)) (cut-files ctx)))
          actual (room-files ctx)]
      (s/check! (= (set (keys expected)) (set (keys actual)))
                (str "ROOM holds other files than the allowed ones: extra "
                     (vec (remove (set (keys expected)) (keys actual))) ", missing "
                     (vec (remove (set (keys actual)) (keys expected)))))
      (doseq [[f t] expected]
        (s/check! (= t (get actual f)) (str f " differs from the cut"))))))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (built! ctx)
    (let [room (:room ctx)
          allow (:allow ctx)
          entries (for [l (rs/lines (rs/git! room "rev-list" "--objects" "--all"))
                        :let [[oid p] (str/split l #" " 2)]]
                    [oid (or p "")])
          ;; a directory on the way to an allowed path (dhf, dhf/harness) is a tree
          leading? (fn [p] (some #(str/starts-with? % (str p "/")) allow))]
      (doseq [[oid p] entries]
        (s/check! (or (= "" p) (allowed? allow p) (leading? p))
                  (str "ROOM holds the object " oid " of " p)))
      (let [all (rs/lines (rs/git! room "cat-file" "--batch-all-objects" "--batch-check=%(objectname)"))]
        (s/check! (= (set all) (set (map first entries)))
                  (str "ROOM holds objects no commit reaches: " (vec (remove (set (map first entries)) all)))))
      ;; the previous build and the oracle, by content: in no object at all
      (doseq [t (cons rs/secret-text (vals oracle-files))]
        (let [blob (str/trim (:out (p/shell {:out :string :in t :dir (str room)} "git" "hash-object" "--stdin")))]
          (s/check! (not (zero? (:exit (rs/git room "cat-file" "-e" blob))))
                    (str "ROOM holds a blob of a file outside the allow-list: " (pr-str t))))))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (built! ctx)
    (doseq [p (:implementation (config))]
      (s/check! (not (fs/exists? (fs/file (:room ctx) p))) (str "ROOM holds " p)))
    (s/check! (some #(allowed? (:allow ctx) %) (keys (room-files ctx))) "ROOM holds none of the allowed paths")))

(defn- no-file-under! [ctx prefixes]
  (built! ctx)
  (let [cut (keys (cut-files ctx))]
    (doseq [p prefixes]
      (s/check! (some #(str/starts-with? % p) cut) (str "the cut holds nothing under " p ": nothing to remove"))
      (doseq [f (keys (room-files ctx))]
        (s/check! (not (str/starts-with? f p)) (str "ROOM holds " f))))))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b]
    (let [scope (second (re-find #"src/rdm_spec/([^/]+)/" a))]
      (no-file-under! ctx (distinct (concat [a b] (layout-prefixes :context scope)))))))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (no-file-under! ctx [a b])))

(defstep #"ROOM holds no file of the bounded contexts (\S+) and (\S+) or of the component (\S+)"
  (fn [ctx c1 c2 comp]
    (no-file-under! ctx (concat (layout-prefixes :context c1) (layout-prefixes :context c2)
                                (layout-prefixes :component comp)))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (built! ctx)
    (let [prefixes (scope-prefixes (:args (kc/the-run ctx)))
          expected (into (sorted-map) (for [[f t] (cut-files ctx)
                                            :when (implementation? f)
                                            :when (not (removed? prefixes f))]
                                        [f t]))
          actual (into (sorted-map) (filter #(implementation? (key %)) (room-files ctx)))]
      (s/check! (seq expected) "the scope keeps no implementation file")
      (s/check! (= (set (keys expected)) (set (keys actual)))
                (str "ROOM's implementation differs from the cut's, less the scope: extra "
                     (vec (remove (set (keys expected)) (keys actual))) ", missing "
                     (vec (remove (set (keys actual)) (keys expected)))))
      (doseq [[f t] expected]
        (s/check! (= t (get actual f)) (str f " differs from the cut"))))))
