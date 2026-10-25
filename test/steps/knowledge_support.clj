(ns steps.knowledge-support
  "Fixtures for the knowledge context's step definitions (knowledge_sources,
  knowledge_wiki): copies of the room's spec, sources index, needs and wiki,
  changed by the scenario, and local git checkouts of a source.

  A checkout fixture is a fresh repository holding the files the room's
  needs and wiki cite of that source, plus its fenced paths, committed once;
  the pin (a short commit no local commit can be made to have) is a tag on
  that commit, so `<pin>:<path>` reads the file at it as at the real commit."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner]
            [steps.support :as s]))

(def knowledge-ns ['rdm-spec.knowledge.sources 'rdm-spec.knowledge.wiki])

(def check-ns
  "Every namespace `rdm-spec check` runs."
  ['rdm-spec.knowledge.sources 'rdm-spec.knowledge.wiki 'rdm-spec.spec.check 'rdm-spec.spec.parser
   'rdm-spec.architecture.lint 'rdm-spec.architecture.views])

(defn needs-knowledge! [] (apply s/needs-built! knowledge-ns))

(def index-rel "dhf/raw/sources.edn")
(def needs-rel "dhf/spec/needs.edn")
(def wiki-rel "dhf/wiki")

(defn generate-views!
  "Generates the views in `dir` (when they are built), so a whole check of
  the copy can pass."
  [dir]
  (when (s/built? 'rdm-spec.architecture.views)
    (s/rdm-spec! dir "views"))
  dir)

(defn ensure-room!
  "ctx with :dir, a copy of the room's allow-listed paths when it has none."
  [ctx]
  (needs-knowledge!)
  (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!))))

;; ---------------------------------------------------------------------------
;; The sources index and the needs

(defn read-edn [dir rel] (edn/read-string (slurp (str (fs/file dir rel)))))

(defn index [dir] (read-edn dir index-rel))

(defn update-index!
  "Rewrites the project's sources index as (f index)."
  [dir f]
  (spit (str (fs/file dir index-rel)) (pr-str (f (index dir)))))

(defn source-id
  "The id of the source whose repository ends with `repo` (owner/name), or
  `repo` itself when it is already an id."
  [idx repo]
  (or (some (fn [[k v]] (when (or (= repo (name k)) (str/ends-with? (str (:repository v)) (str "/" repo))) (name k)))
            idx)
      repo))

(defn write-needs!
  "Writes the needs one per line, so each need's citations are on its line."
  [dir needs]
  (spit (str (fs/file dir needs-rel))
        (str ";; needs, rewritten by a scenario\n{"
             (str/join "\n " (for [[k v] (sort-by key needs)] (str (pr-str k) " " (pr-str v))))
             "}\n")))

(defn cite!
  "Adds `citation` to the sources of the need `need` in the project `dir`;
  returns the line of needs.edn it is on."
  ([dir citation] (cite! dir "UN-011" citation))
  ([dir need citation]
   (let [needs (update-in (read-edn dir needs-rel) [need :sources] #(vec (concat % [citation])))]
     (write-needs! dir needs)
     (some (fn [[i l]] (when (str/includes? l (str "\"" citation "\"")) (inc i)))
           (map-indexed vector (str/split-lines (slurp (str (fs/file dir needs-rel)))))))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn git!
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
                                      "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main"
                 args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (str/trim (:out r))))

(def citation-re #"([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})")

(defn cited-paths
  "Every path the room's needs and wiki cite of the source `id`, and its
  fenced paths: what a faithful checkout of it holds."
  [id]
  (let [root s/room-root
        texts (concat [(slurp (str (fs/file root needs-rel)))]
                      (for [f (fs/glob (fs/file root wiki-rel) "**.md")] (slurp (str f))))
        cited (for [t texts [_ src path] (re-seq citation-re t) :when (= src id)] path)
        fenced (get-in (read-edn root index-rel) [(keyword id) :fenced])]
    (vec (distinct (concat ["README.md"] cited fenced)))))

(defn checkout!
  "Makes a git checkout of the source `id` under `sources-dir`, holding
  `paths` (a fenced directory gets a file inside it), its one commit tagged
  `pin`. Returns its directory."
  [sources-dir id pin paths]
  (let [co (str (fs/file sources-dir id))]
    (fs/create-dirs co)
    (doseq [path paths
            :let [file? (re-find #"\.[A-Za-z0-9]+$" path)]]
      (s/write! co (if file? path (str path "/kept.txt")) (str "fixture content of " path "\n")))
    (git! co "init" "-q")
    (git! co "add" "-A")
    (git! co "commit" "-q" "-m" (str "a fixture checkout of " id))
    (git! co "tag" pin)
    co))

(defn sources-dir! [ctx] (or (:sources-dir ctx) (s/tmp-dir)))

;; ---------------------------------------------------------------------------
;; The run

(defn run-check
  "Runs the command line `line` (without rdm-spec) in the scenario's
  project, DIR standing for its --sources directory: ctx with :run."
  [ctx line]
  (let [ctx (ensure-room! ctx)
        args (mapv #(if (and (= "DIR" %) (:sources-dir ctx)) (:sources-dir ctx) %) (s/split-args line))]
    (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args))))

(defn finding-lines
  "The run's finding lines (no warnings, no events)."
  [run]
  (vec (remove #(or (str/starts-with? % "event: ") (str/includes? % "warning: ")) (s/err-lines run))))

(defn warning-lines [run] (vec (filter #(str/includes? % "warning: ") (s/err-lines run))))

(defn result
  "The run's knowledge result (stdout's :knowledge), or nil."
  [run]
  (:knowledge (try (edn/read-string (:out run)) (catch Exception _ nil))))

(defn citations-of
  "The citations the run reported for `citation`."
  [run citation]
  (filter #(= citation (:citation %)) (:citations (result run))))

(defn fails-with!
  "Checks the run exited 1 with a finding line satisfying `pred`; returns it."
  [ctx pred what]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (or (first (filter pred (finding-lines r)))
        (s/fail! (str "no finding " what "\n" (s/show r))))))

(defn without [line citation] (str/replace line citation ""))

(defn ensure-step!
  "Registers `pattern` -> `f` at run time unless a definition already
  matches `text`: a step whose wording another context's feature shares, so
  it is never defined twice."
  [text pattern f]
  (when-not (some #(re-matches (:pattern %) text) @runner/*registry*)
    (runner/register! pattern f "test/steps/knowledge_support.clj" (hash text))))
