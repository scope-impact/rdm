(ns rdm-spec.project.adopt
  "Adds the skeleton to an existing repository, never overwriting a file, and
  never reading its code into the spec (dhf/spec/01-start-a-project/adopt.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn exists? [p] (or (fs/exists? p {:nofollow-links true}) (fs/sym-link? p)))

(defn adopt [run]
  (let [dir (:root run)]
    (when-not (zero? (:exit (project/git dir "rev-parse" "--git-dir")))
      (project/cannot-run! (str "adopt installs the pre-commit hook, and the hook needs a git repository: " dir " is not one")))
    (let [files (init/skeleton (fs/file-name dir) "spec")
          {kept true wrote false} (group-by (fn [[rel _]] (boolean (exists? (fs/path dir rel)))) files)
          gi (fs/path dir ".gitignore")
          present (when (fs/exists? gi) (set (map str/trim (str/split-lines (slurp (str gi))))))]
      (doseq [[rel text] wrote] (init/write-file! dir rel text))
      (hooks/install! dir)
      {:result {:adopt (cond-> {:wrote (mapv first wrote) :kept (mapv first kept) :next init/next-steps}
                         (some #(= ".gitignore" (first %)) kept)
                         (assoc :gitignore-lines (vec (remove #(contains? present %) init/gitignore-lines))))}
       :warnings (vec (for [[rel _] kept]
                        (project/warning rel nil "adopt-kept" (str rel " already exists and is kept unchanged"))))})))
