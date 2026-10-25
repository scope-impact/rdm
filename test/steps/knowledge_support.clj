(ns steps.knowledge-support
  "Fixtures for the knowledge context's step definitions (knowledge_sources,
  knowledge_wiki): a copy of the room's spec, sources and wiki; wiki pages;
  and source checkouts built locally, never fetched.

  A checkout is a git repository named by its source's id under a sources
  directory (the --sources DIR). Its one commit is tagged with the pin it
  stands for (a tag named 17d68d2), so git reads the pin as that commit: a
  real source's commit cannot be made locally, and no network is used."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.support :as s]))

(defn ensure-room!
  "`ctx`'s project: a copy of the room's allow-listed paths unless it has one."
  [ctx]
  (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.knowledge.wiki)
  (if (and (:dir ctx) (fs/exists? (fs/file (:dir ctx) "dhf/raw/sources.edn")))
    ctx
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (when-not (fs/exists? (fs/file dir "dhf/raw/sources.edn"))
        (let [room (s/copy-of-room!)]
          (doseq [a ["dhf/spec" "dhf/raw" "dhf/wiki" "dhf/views"]
                  :when (fs/exists? (fs/file room a))]
            (fs/copy-tree (fs/file room a) (fs/file dir a) {:replace-existing true}))))
      (assoc ctx :dir (str dir)))))

(defn read-edn [dir rel] (edn/read-string (slurp (str (fs/file dir rel)))))

(defn update-edn! [dir rel f]
  (spit (str (fs/file dir rel)) (pr-str (f (read-edn dir rel)))))

(defn sources! [dir f] (update-edn! dir "dhf/raw/sources.edn" f))

(defn sources [dir] (read-edn dir "dhf/raw/sources.edn"))

(defn git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                 "git" "-c" "user.name=fixture" "-c" "user.email=fixture@example.org"
                 "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (:out r)))

(defn commit-all!
  "Makes `dir` a git repository (unless it is one) and commits everything."
  [dir message]
  (when-not (fs/exists? (fs/file dir ".git")) (git! dir "init" "-q"))
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "--allow-empty" "-m" message)
  dir)

(defn checkout!
  "A checkout of the source `id` under the sources directory `base`: a git
  repository holding `paths` (each a file; a path ending in / a directory
  holding one file), committed once and tagged `pin`. Returns its directory."
  [base id pin paths]
  (let [dir (fs/file base id)]
    (fs/create-dirs dir)
    (s/write! dir ".keep" "")
    (doseq [p paths]
      (if (str/ends-with? p "/")
        (s/write! dir (str p "file.txt") "fixture\n")
        (s/write! dir p (str "fixture: " p "\n"))))
    (commit-all! dir (str "fixture standing for " id " at " pin))
    (git! dir "tag" pin)
    (str dir)))

(defn sources-dir! [ctx]
  (or (:sources-dir ctx) (s/tmp-dir)))

(defn cited-paths
  "Every path the project's needs cite in `source`."
  [dir source]
  (let [needs (read-edn dir "dhf/spec/needs.edn")]
    (vec (distinct (for [[_ n] needs c (:sources n)
                         :let [[_ src path] (re-matches #"([^:]+):(.+)@.+" (str c))]
                         :when (= src source)]
                     path)))))

(defn fenced-paths [dir source]
  (vec (get-in (sources dir) [(keyword source) :fenced])))

;; --- the wiki --------------------------------------------------------------

(defn index-file [dir] (str (fs/file dir "dhf/wiki/index.md")))

(defn list-page!
  "Links `rel` (relative to the wiki directory) from the wiki's index."
  [dir title rel]
  (let [f (index-file dir)]
    (spit f (str (str/trimr (slurp f)) "\n\n- [" title "](" rel ")\n"))))

(defn page!
  "Writes the wiki page dhf/wiki/pages/<name>.md holding `text`, listed in
  the index unless `listed?` is false. Returns its path relative to the
  project."
  ([dir name text] (page! dir name text true))
  ([dir name text listed?]
   (let [rel (str "dhf/wiki/pages/" name ".md")]
     (s/write! dir rel text)
     (when listed? (list-page! dir name (str "pages/" name ".md")))
     rel)))

(defn footnoted-page
  "A page's text: a heading, one claim per citation, each footnoted to it."
  [title citations]
  (str "# " title "\n\n"
       (str/join "\n\n" (map-indexed (fn [i _] (str "A claim distilled from a source.[^" (inc i) "]")) citations))
       "\n\n"
       (str/join "\n" (map-indexed (fn [i c] (str "[^" (inc i) "]: `" c "`, \"A section\".")) citations))
       "\n"))

(defn line-of [dir rel s]
  (some (fn [[i l]] (when (str/includes? l s) (inc i)))
        (map-indexed vector (str/split-lines (slurp (str (fs/file dir rel)))))))

;; --- reading a run -----------------------------------------------------------

(defn finding-lines [run]
  (vec (remove #(str/starts-with? % "event: ") (s/err-lines run))))

(defn error-lines [run]
  (vec (remove #(re-find #"^(\S+: )?warning: |: warning: " %) (finding-lines run))))

(defn line-with
  "The first finding line (warnings included) holding every one of `parts`."
  [run & parts]
  (first (filter (fn [l] (every? #(str/includes? l %) parts)) (finding-lines run))))

(defn error-with [run & parts]
  (first (filter (fn [l] (every? #(str/includes? l %) parts)) (error-lines run))))

(defn result [run]
  (try (edn/read-string (:out run)) (catch Exception _ nil)))

(defn citations-of
  "The citations the run's result reports."
  [run]
  (get-in (result run) [:knowledge :citations]))

(defn fails-with!
  "The run exited 1 and one error line holds every one of `parts`."
  [ctx & parts]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (apply error-with r parts) (str "no finding names all of " (pr-str parts) "\n" (s/show r)))))
