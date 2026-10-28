(ns rdm-spec.regeneration.cut
  "Tags HEAD spec-N when it is origin/HEAD and pushes the tag; a regeneration
  reads nothing else (dhf/spec/06-regenerate/cut.feature). Also the git
  helpers every task that reads a cut uses: a cut of a project in a folder is
  its folder at the tagged commit (11-project-in-a-folder)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")

(defn repo? [root] (zero? (:exit (project/git root "rev-parse" "--git-dir"))))

(defn prefix
  "The project's folder within its repository, \"\" at the root, else ending in /."
  [root]
  (let [r (project/git root "rev-parse" "--show-prefix")]
    (if (zero? (:exit r)) (str/trim (:out r)) "")))

(defn tag-commit
  "The commit a tag names, or nil."
  [root tag]
  (let [r (project/git root "rev-parse" "-q" "--verify" (str "refs/tags/" tag "^{commit}"))]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn ls-tree
  "Every file committed at rev in the project's folder: [{:mode :type :sha :path}], path relative to the folder."
  [root rev]
  (let [pre (prefix root)
        r (project/git root "ls-tree" "-r" "--full-tree" rev "--" (if (str/blank? pre) "." pre))]
    (when-not (zero? (:exit r)) (project/cannot-run! (str "git cannot read " rev ": " (project/one-line (:err r)))))
    (vec (for [l (str/split-lines (:out r))
               :when (not (str/blank? l))
               :let [[meta path] (str/split l #"\t" 2)
                     [mode type sha] (str/split meta #" ")]]
           {:mode mode :type type :sha sha :path (subs path (count pre))}))))

(defn show-at
  "A file's text at rev (path relative to the project's folder), or nil."
  [root rev path]
  (let [r (project/git root "show" (str rev ":" (prefix root) path))]
    (when (zero? (:exit r)) (:out r))))

(defn read-edn-at [root rev path]
  (when-let [t (show-at root rev path)] (try (edn/read-string t) (catch Exception _ nil))))

(defn export!
  "Writes the project's folder as committed at rev into dir (made if needed), files only."
  [root rev dir]
  (let [tar (str (fs/path (project/temp-dir "export-") "cut.tar"))]
    (try
      (fs/create-dirs dir)
      (let [top (str/trim (project/git! root "rev-parse" "--show-toplevel"))
            r (project/git top "archive" "--format=tar" "-o" tar (str rev ":" (prefix root)))]
        (when-not (zero? (:exit r)) (project/cannot-run! (str "git cannot export " rev ": " (project/one-line (:err r))))))
      (let [r (project/sh dir "tar" "-xf" tar)]
        (when-not (zero? (:exit r)) (project/cannot-run! (str "tar cannot unpack " rev ": " (project/one-line (:err r))))))
      dir
      (finally (fs/delete-tree (fs/parent tar))))))

(defmacro with-cut
  "Exports rev into a temporary directory, binds it to sym for body, then removes it."
  [[sym root rev] & body]
  `(let [d# (project/temp-dir "cut-")]
     (try (export! ~root ~rev d#)
          (let [~sym d#] ~@body)
          (finally (fs/delete-tree d#)))))

(defn build-room!
  "Exports the cut into room, removes the denied paths, and commits it as one root commit. Returns the commit."
  [root rev room denied]
  (export! root rev room)
  (doseq [p denied :let [f (fs/path room p)]]
    (when (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)) (fs/delete f)))
  ;; remove directories the denied files left empty
  (doseq [d (sort-by #(- (count (str %))) (filter fs/directory? (fs/glob room "**" {:hidden true})))]
    (when (and (fs/exists? d) (empty? (fs/list-dir d))) (fs/delete d)))
  (project/git! room "init" "-q")
  (project/git! room "add" "-A" "-f" ".")
  (project/git! room "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "The clean room of " rev))
  (str/trim (project/git! room "rev-parse" "HEAD")))

(defn cuts-at [root rev]
  (let [r (project/git root "tag" "--points-at" rev)]
    (vec (sort (filter #(re-matches #"spec-\d+" %) (str/split-lines (:out r)))))))

(defn next-tag [tags]
  (str "spec-" (inc (reduce max 0 (keep #(some-> (re-matches #"spec-(\d+)" %) second parse-long) tags)))))

(defn cut
  "The cut task, once the check has passed: tags HEAD spec-N and pushes it."
  [run]
  (let [root (:root run)
        head (str/trim (project/git! root "rev-parse" "HEAD"))
        origin (project/git root "rev-parse" "-q" "--verify" "refs/remotes/origin/HEAD^{commit}")
        branch (str/trim (:out (project/git root "rev-parse" "--abbrev-ref" "HEAD")))]
    (when-not (and (zero? (:exit origin)) (= head (str/trim (:out origin))))
      (project/cannot-run! (str "HEAD (the branch " branch ") is not the commit origin/HEAD names: only the default branch is cut")))
    (if-let [existing (first (cuts-at root "HEAD"))]
      {:result {:cut {:tag existing :commit head :tagged false :says (str "the commit is already " existing ": nothing is added")}}}
      (let [tags (str/split-lines (:out (project/git root "tag" "--list" "spec-*")))
            t (next-tag tags)]
        (project/git! root "tag" t "HEAD")
        (let [p (project/git root "push" "origin" (str "refs/tags/" t))]
          (when-not (zero? (:exit p))
            (project/git root "tag" "-d" t)
            (project/cannot-run! (str "the tag " t " could not be pushed to origin: " (project/one-line (:err p))))))
        {:result {:cut {:tag t :commit head :tagged true :pushed "origin"}}}))))
