(ns steps.knowledge-support
  "Fixtures for the knowledge context's step definitions (knowledge_sources,
  knowledge_wiki): projects whose sources index, needs and wiki a scenario
  edits, and git checkouts of sources given with --sources.

  A project is a copy of the room (its sources, needs and wiki as they are)
  or a small one written by `rdm-spec init` with one source, loyly, pinned
  at 17d68d2. A checkout is a git repository whose pin is a tag named for
  it, so a pin like 17d68d2 resolves in a repository made a moment ago.
  Every directory lies under the run's fixture root (steps.support)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; git

(def git-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn git!
  "Runs git in `dir`; fails the step when git fails. Returns its trimmed output."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env git-env}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn git-init! [dir]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid")
    (git! dir "config" "commit.gpgsign" "false"))
  dir)

(defn commit!
  "Commits everything in `dir` (with --no-verify); returns the commit's full hash."
  [dir message]
  (git-init! dir)
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" message)
  (git! dir "rev-parse" "HEAD"))

(defn checkout!
  "A git repository at `parent`/`id` holding `files` ({path text}), committed
  and tagged `pin` (when given). Returns its full commit hash."
  [parent id files pin]
  (let [dir (str (fs/file parent id))]
    (fs/create-dirs dir)
    (doseq [[path text] files] (s/write! dir path text))
    (let [h (commit! dir (str "the checkout of " id))]
      (when pin (git! dir "tag" "-f" pin))
      h)))

;; ---------------------------------------------------------------------------
;; EDN files of a project

(defn read-edn [dir rel] (edn/read-string (slurp (str (fs/file dir rel)))))

(defn write-edn! [dir rel v] (s/write! dir rel (str (pr-str v) "\n")))

(defn update-edn! [dir rel f] (write-edn! dir rel (f (read-edn dir rel))))

(defn sources-rel [dir] (str (or (:raw-dir (kc/config-of dir)) "dhf/raw") "/sources.edn"))

(defn needs-rel [dir] (or (:needs (kc/config-of dir)) "dhf/spec/needs.edn"))

(defn wiki-rel [dir] (or (:wiki-dir (kc/config-of dir)) "dhf/wiki"))

(defn update-sources! [dir f] (update-edn! dir (sources-rel dir) f))

(defn update-needs! [dir f] (update-edn! dir (needs-rel dir) f))

;; ---------------------------------------------------------------------------
;; Projects

(def loyly
  {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []})

(defn small-project!
  "A project `rdm-spec init` wrote, with the source loyly (pinned 17d68d2,
  nothing fenced) and the need UN-001 citing `citations`."
  [ctx citations]
  (let [{:keys [dir]} (kc/project-set-up! ctx)]
    (write-edn! dir (sources-rel dir) {:loyly loyly})
    (s/write! dir "dhf/raw/loyly.md" "# loyly\n\nA source.\n")
    (write-edn! dir (needs-rel dir) {"UN-001" {:text "A need." :sources (vec citations)}})
    dir))

(defn project
  "The scenario's project: its :dir, else a new copy of the room."
  [ctx]
  (or (:dir ctx) (s/copy-of-room!)))

(defn line-of
  "The 1-based line of the first line of `text` containing `part`, at or
  after line `from` (default 1)."
  ([text part] (line-of text part 1))
  ([text part from]
   (some (fn [[i l]] (when (and (>= (inc i) from) (str/includes? l part)) (inc i)))
         (map-indexed vector (str/split-lines text)))))

(defn cite-in-need!
  "Adds `citation` to the sources of the need `need` of the project `dir`;
  returns {:file :line} where it is cited."
  [dir need citation]
  (update-needs! dir (fn [ns] (update-in ns [need :sources] #(vec (concat % [citation])))))
  (let [rel (needs-rel dir)
        text (slurp (str (fs/file dir rel)))]
    {:file rel :line (line-of text (pr-str citation))}))

(defn add-wiki-page!
  "Writes the wiki page `name` (under the wiki's pages/) holding `text`, and
  lists it in the index. Returns its path relative to the project."
  [dir name text]
  (let [wiki (wiki-rel dir)
        rel (str "pages/" name)]
    (s/write! dir (str wiki "/" rel) text)
    (spit (str (fs/file dir wiki "index.md")) (str "\n- [A page](" rel ")\n") :append true)
    (str wiki "/" rel)))

;; ---------------------------------------------------------------------------
;; Reading a run

(defn findings [ctx] (kc/finding-lines (kc/the-run ctx)))

(defn warnings [ctx] (filterv kc/warning-line? (s/err-lines (kc/the-run ctx))))

(defn line-naming
  "The first of `lines` holding every one of `parts`, or nil."
  [lines parts]
  (first (filter (fn [l] (every? #(str/includes? l (str %)) parts)) lines)))

(defn fails-naming!
  "Checks the last run exited 1 and that one finding line names every one of
  `parts`. Returns the line."
  [ctx & parts]
  (let [r (kc/exits! ctx 1)
        l (line-naming (findings ctx) parts)]
    (s/check! l (str "no finding names all of " (pr-str parts) "\n" (s/show r)))
    l))

(defn fails-rule-naming!
  "As fails-naming!, the line's rule being `rule`."
  [ctx rule & parts]
  (apply fails-naming! ctx (str ": " rule ": ") parts))
