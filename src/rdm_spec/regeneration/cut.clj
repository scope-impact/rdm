(ns rdm-spec.regeneration.cut
  "Component Cut of the regeneration context (dhf/spec/regeneration/cut.feature):
  `rdm-spec cut` tags HEAD spec-N when it is the commit origin/HEAD names
  and its spec passes the check, and pushes the tag. A commit already cut
  is not cut again.

  Also the context's git helpers, which Fence resolves cuts with (the
  model: Fence -> Cut, \"Resolves cuts with the git helpers of\")."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; git

(def cut-re
  "A cut's tag: spec-N."
  #"spec-(\d+)")

(defn cut-tag?
  "Whether `s` is a cut's tag."
  [s]
  (boolean (re-matches cut-re (str s))))

(defn cut-number
  "N of the cut tag spec-N."
  [tag]
  (parse-long (second (re-matches cut-re (str tag)))))

(defn next-cut
  "The tag of the next cut after the cut tags `tags` (other tags ignored)."
  [tags]
  (str "spec-" (inc (reduce max 0 (keep #(when (cut-tag? %) (cut-number %)) tags)))))

(defn latest-cut
  "The highest cut tag of `tags`, or nil."
  [tags]
  (last (sort-by cut-number (filter cut-tag? tags))))

(defn- env-of [req extra]
  (merge (or (:env req) (into {} (System/getenv))) extra))

(defn git
  "Runs git `args` in the project of `req`: {:exit :out :err}. `opts` may
  give :dir, :in and :env (extra variables)."
  [req opts & args]
  (let [r (try (apply p/shell {:dir (str (or (:dir opts) (:root req))) :out :string :err :string
                               :continue true :in (or (:in opts) "")
                               :env (env-of req (:env opts))}
                      "git" args)
               (catch java.io.IOException e
                 (command/cannot-run! (str "git cannot be run (" (ex-message e) "): rdm-spec "
                                           (:task req) " needs git on PATH"))))]
    {:exit (:exit r) :out (:out r) :err (str/trim (str (:err r)))}))

(defn git!
  "Runs git `args` (see git); the trimmed output, or the task cannot run,
  saying `why` and git's own message."
  [req opts why & args]
  (let [r (apply git req opts args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str why (when-not (str/blank? (:err r)) (str ": " (:err r))))))
    (str/trim (:out r))))

(defn repository!
  "Nothing when the project of `req` is a git repository; otherwise the
  task cannot run."
  [req]
  (let [r (git req {} "rev-parse" "--git-dir")]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str (:root req) " is not a git repository: rdm-spec " (:task req)
                                " reads cuts, which are git tags")))))

(defn commit-of
  "The commit `rev` names in the project of `req`, or nil."
  [req rev]
  (let [r (git req {} "rev-parse" "--verify" "-q" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn cut-tags-at
  "The cut tags naming the commit `commit`, sorted by N."
  [req commit]
  (let [r (git req {} "tag" "--points-at" commit)]
    (vec (sort-by cut-number (filter cut-tag? (map str/trim (str/split-lines (:out r))))))))

(defn show
  "The text of `path` at the commit `rev`, or nil when it holds none."
  [req rev path]
  (let [r (git req {} "show" (str rev ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn edn-at
  "The EDN value of `path` at `rev`, or nil when there is none; the task
  cannot run when it is not readable EDN."
  [req rev path]
  (when-let [text (show req rev path)]
    (try (edn/read-string text)
         (catch Exception e
           (command/cannot-run! (str path " at " rev " is not readable EDN: " (ex-message e)))))))

(defn config-at
  "The configuration of the commit `rev`, or nil when it holds none."
  [req rev]
  (let [c (edn-at req rev command/config-file)]
    (when (and (some? c) (not (map? c)))
      (command/cannot-run! (str command/config-file " at " rev " is not an EDN map")))
    c))

(defn export!
  "Writes the files of the commit `rev` into the directory `dir` (made
  when missing): its files and no history."
  [req rev dir]
  (let [idx (str (fs/create-temp-file {:prefix "rdm-spec-index-"}))]
    (try
      (fs/delete-if-exists idx)
      (fs/create-dirs dir)
      (git! req {:env {"GIT_INDEX_FILE" idx}} (str "the commit " rev " cannot be read") "read-tree" rev)
      (git! req {:env {"GIT_INDEX_FILE" idx}} (str "the commit " rev " cannot be written to " dir)
            (str "--work-tree=" (fs/absolutize dir)) "checkout-index" "-a" "-f")
      (finally (fs/delete-if-exists idx)))
    dir))

;; ---------------------------------------------------------------------------
;; The cut

(defn- origin-head
  "{:name :commit :branch} of what origin/HEAD names, or the task cannot run."
  [req]
  (if-let [c (commit-of req "refs/remotes/origin/HEAD")]
    (let [nm (str/trim (:out (git req {} "symbolic-ref" "-q" "--short" "refs/remotes/origin/HEAD")))
          nm (if (str/blank? nm) "origin/HEAD" nm)]
      {:name nm :commit c :branch (str/replace nm #"^origin/" "")})
    (let [r (git req {} "ls-remote" "--symref" "origin" "HEAD")
          ls (str/split-lines (:out r))
          branch (some #(second (re-find #"^ref: refs/heads/(\S+)\s+HEAD" %)) ls)
          c (some #(second (re-find #"^([0-9a-f]{40,})\s+HEAD$" %)) ls)]
      (when-not (and (zero? (:exit r)) c)
        (command/cannot-run! (str "origin/HEAD names no commit (no remote origin, or its HEAD is unknown)"
                                  (when-not (str/blank? (:err r)) (str ": " (:err r)))
                                  ": rdm-spec cut tags only the commit the default branch names")))
      {:name (str "origin/" (or branch "HEAD")) :commit c :branch (or branch "HEAD")})))

(defn- check-head!
  "The findings of `rdm-spec check` on an export of HEAD; the task cannot
  run when the check cannot."
  [req head]
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-cut-"}))]
    (try
      (export! req head dir)
      (let [{:keys [cannot-run findings]} (command/execute ["check"] {:root dir :env (:env req)})]
        (when cannot-run
          (command/cannot-run! (str "the check cannot run on HEAD " (subs head 0 (min 12 (count head))) ": " cannot-run)))
        (vec findings))
      (finally (fs/delete-tree dir)))))

(defn cut
  "The cut stage: `rdm-spec cut`."
  [req]
  (repository! req)
  (let [head (or (commit-of req "HEAD") (command/cannot-run! "HEAD names no commit: there is nothing to cut"))
        origin (origin-head req)
        branch (let [b (str/trim (:out (git req {} "symbolic-ref" "-q" "--short" "HEAD")))]
                 (if (str/blank? b) "a detached HEAD" (str "the branch " b)))
        short #(subs % 0 (min 12 (count %)))]
    (when-not (= head (:commit origin))
      (command/cannot-run! (str "HEAD (" branch ", " (short head) ") is not the commit " (:name origin)
                                " names (" (short (:commit origin)) "): only the default branch "
                                (:branch origin) " is cut")))
    (git! req {} "the cut tags cannot be fetched from origin" "fetch" "-q" "--tags" "origin")
    (if-let [tag (last (cut-tags-at req head))]
      (do (git! req {} (str "the tag " tag " cannot be pushed to origin") "push" "-q" "origin" (str "refs/tags/" tag))
          {:result {:cut {:tag tag :commit head :tagged? false
                          :note (str "the commit is already " tag ": no tag was added")}}
           :findings []})
      (let [findings (check-head! req head)
            errors (vec (remove command/warning? findings))]
        (if (seq errors)
          {:result {:cut {:commit head :tagged? false
                          :note "the spec at HEAD fails the check: nothing was tagged"}}
           :findings errors}
          (let [tags (str/split-lines (:out (git req {} "tag" "--list" "spec-*")))
                tag (next-cut (map str/trim tags))]
            (git! req {} (str "HEAD cannot be tagged " tag) "tag" tag head)
            (let [r (git req {} "push" "-q" "origin" (str "refs/tags/" tag))]
              (when-not (zero? (:exit r))
                (git req {} "tag" "-d" tag)
                (command/cannot-run! (str "the tag " tag " cannot be pushed to origin, so it was removed again"
                                          (when-not (str/blank? (:err r)) (str ": " (:err r)))))))
            {:result {:cut {:tag tag :commit head :tagged? true :pushed-to "origin"}}
             :findings []}))))))
