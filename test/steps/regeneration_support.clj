(ns steps.regeneration-support
  "Helpers the regeneration steps share (dhf/spec/regeneration/cut.feature and
  fence.feature): throwaway git repositories, their bare origins, and the
  fixture projects a cut or a fence works on.

  Every repository lives in a directory of its own made with
  steps.support/tmp-dir; git runs with no hooks, no signing and a fixed
  identity, so a fixture never depends on the machine's git configuration."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; git

(def git-env
  {"GIT_AUTHOR_NAME" "Fixture" "GIT_AUTHOR_EMAIL" "fixture@example.invalid"
   "GIT_COMMITTER_NAME" "Fixture" "GIT_COMMITTER_EMAIL" "fixture@example.invalid"
   "GIT_CONFIG_NOSYSTEM" "1"})

(defn git
  "Runs git in `dir`: {:exit :out :err}, never failing."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env git-env}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                 "-c" "init.defaultBranch=main" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn git!
  "Runs git in `dir` and returns its trimmed output; fails the step on an error."
  [dir & args]
  (let [r (apply git dir args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn lines [s] (vec (remove str/blank? (str/split-lines (or s "")))))

(defn commit!
  "Commits everything in `dir` (a repository, made when it is not one yet)
  with message `msg`; returns the commit's hash."
  [dir msg]
  (when-not (fs/exists? (fs/file dir ".git")) (git! dir "init" "-q"))
  (git! dir "add" "-A" "-f" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" msg)
  (git! dir "rev-parse" "HEAD"))

(defn tags-at
  "The tags pointing at `rev` in `dir`."
  [dir rev]
  (set (lines (git! dir "tag" "--points-at" rev))))

(defn all-tags [dir] (set (lines (git! dir "tag" "--list"))))

(defn remote-tags
  "The tags the repository `bare` holds: {name commit}."
  [bare]
  (into {} (for [l (lines (git! bare "for-each-ref" "--format=%(refname:short) %(objectname)" "refs/tags"))
                 :let [[n c] (str/split l #" ")]]
             [n c])))

;; ---------------------------------------------------------------------------
;; Files

(defn files-under
  "{relative path bytes-as-string} of every file under `dir`, outside .git."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [rel (str/replace (str (fs/relativize dir f)) "\\" "/")]
              :when (and (fs/regular-file? f)
                         (not (or (= ".git" rel) (str/starts-with? rel ".git/"))))]
          [rel (slurp (str f))])))

(defn under?
  "True when the relative path `rel` is `prefix` or lies under it (a
  directory), or starts with it when `prefix` is a file-name prefix."
  [rel prefix]
  (or (= rel prefix)
      (str/starts-with? rel (if (str/ends-with? prefix "/") prefix (str prefix "/")))))

;; ---------------------------------------------------------------------------
;; Fixture projects

(def implementation-paths ["src" "test" "test.clj" "bb.edn"])

(defn copy-implementation!
  "Copies the room's own implementation (its configured :implementation
  paths) into `dir`."
  [dir]
  (doseq [a (:implementation (s/room-config))
          :let [src (fs/file s/room-root a)]
          :when (fs/exists? src)]
    (if (fs/directory? src)
      (fs/copy-tree src (fs/file dir a) {:replace-existing true})
      (do (fs/create-dirs (fs/parent (fs/file dir a)))
          (fs/copy src (fs/file dir a) {:replace-existing true})))))

(def secret-path
  "A file that an earlier commit held and the cut no longer does: a previous
  implementation that must never reach a clean room."
  "src/rdm_spec/previous_build.clj")

(def secret-text "(ns rdm-spec.previous-build) ;; the previous build: never in a clean room\n")

(def outside-files
  "Files at the cut outside the allow-list and the implementation."
  {"README.md" "# A project\n"
   "dhf/cycles/02/score.edn" "{:cycle \"02\" :measurement \"never in a clean room\"}\n"
   "dhf/storm/board.md" "# The event storm's board\n"
   "dhf/decisions/adr-0001-old.md" "# An older decision\n"})

(defn cut-project!
  "A git repository whose commit tagged `tag` (spec-3) holds a copy of the
  room's allow-list, files outside it, and (unless :implementation? false)
  the room's implementation; an earlier commit held a previous build that the
  cut deleted. `:drop` lists allowed paths the cut leaves out."
  [& [{:keys [tag implementation? drop] :or {tag "spec-3" implementation? true}}]]
  (let [dir (s/copy-of-room!)]
    (s/write! dir secret-path secret-text)
    (commit! dir "the previous build")
    (fs/delete (fs/file dir secret-path))
    (doseq [d drop] (fs/delete-tree (fs/file dir d)))
    (doseq [[f t] outside-files] (s/write! dir f t))
    (when implementation? (copy-implementation! dir))
    (let [c (commit! dir (str "the spec at " tag))]
      (git! dir "tag" tag)
      ;; work after the cut, which a clean room of the cut never holds
      (s/write! dir "dhf/spec/after-the-cut.md" "written after the cut\n")
      (commit! dir "after the cut")
      {:dir dir :cut tag :commit c})))

(defn cut-files
  "{relative path content} of every file the commit `rev` holds in `dir`."
  [dir rev]
  (let [tmp (s/tmp-dir)]
    (git! dir "worktree" "add" "-q" "--detach" (str tmp "/w") rev)
    (try (files-under (fs/file tmp "w"))
         (finally (git! dir "worktree" "remove" "--force" (str tmp "/w"))))))

(defn origin-project!
  "A git repository with a bare origin: the room's allow-list (so rdm-spec
  check passes on it) committed on main and pushed, origin/HEAD naming main.
  `cuts` are tags put on earlier commits and pushed, as earlier cuts were.
  Returns {:dir :origin :head}."
  [& [{:keys [cuts] :or {cuts []}}]]
  (let [origin (str (fs/file (s/tmp-dir) "origin.git"))
        dir (s/copy-of-room!)]
    (git! (fs/parent origin) "init" "-q" "--bare" origin)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "remote" "add" "origin" origin)
    (doseq [t cuts]
      (s/write! dir "dhf/spec/history.md" (str "before " t "\n"))
      (commit! dir (str "the spec at " t))
      (git! dir "tag" t))
    (s/write! dir "dhf/spec/history.md" "the merge to cut\n")
    (let [head (commit! dir "a merged spec change")]
      (git! dir "push" "-q" "origin" "main" "--tags")
      (git! dir "remote" "set-head" "origin" "main")
      {:dir dir :origin origin :head head})))

(defn push-head!
  "Pushes HEAD of `dir` to origin's main."
  [dir]
  (git! dir "push" "-q" "origin" "HEAD:main"))
