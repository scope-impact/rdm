(ns rdm-spec.project.adopt
  "Adopt (dhf/spec/project/adopt.feature): `rdm-spec adopt` adds the skeleton,
  as Init writes it (rdm-spec.project.init), to the git repository the command
  runs in, at its top level, never overwriting a file.

  A destination that exists in any form, a symbolic link included, is kept and
  named as kept. An existing .gitignore is kept too, and the lines Init writes
  that it lacks are named for the person to add. Nothing of the existing code is
  read into the spec: the model, the needs and the ledger start empty, and no
  feature file is written."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn missing-gitignore-lines
  "The lines Init writes to .gitignore that the text `existing` lacks."
  [existing]
  (let [have (set (map str/trim (str/split-lines (or existing ""))))]
    (vec (remove have (init/gitignore-lines)))))

(defn- gitignore-lines-to-add
  "When .gitignore was kept: the lines to add to it, as {:lines [...]
  :findings [...]}."
  [root kept]
  (if (some #{".gitignore"} kept)
    (let [f (fs/file root ".gitignore")
          text (try (slurp f) (catch Exception _ nil))
          lines (if text (missing-gitignore-lines text) (init/gitignore-lines))]
      {:lines lines
       :findings (if (seq lines)
                   [(command/warning ".gitignore" nil "gitignore-kept"
                                     (str "the .gitignore already there is kept; add these lines to it: "
                                          (str/join " " lines)))]
                   [])})
    {:lines [] :findings []}))

(defn adopt
  "The adopt task: `rdm-spec adopt`, in the repository to adopt."
  [req]
  (let [root (hooks/require-repository! (:root req))
        name (fs/file-name root)
        vars (init/vars-for name init/default-id-prefix)
        {:keys [wrote kept skipped findings]} (init/write-skeleton! root vars)
        ignore (gitignore-lines-to-add root kept)
        activated (hooks/activate! root)
        hook-findings (when (some #{hooks/hook-path} kept) (hooks/check-kept-hook root))]
    {:result (cond-> {:root root
                      :wrote wrote
                      :kept kept
                      :gitignore-lines-to-add (:lines ignore)
                      :hooks-path (:hooks-path activated)
                      :next-steps (init/next-steps nil)}
               (seq skipped) (assoc :skipped skipped))
     :findings (vec (concat findings (:findings ignore) (:findings activated) hook-findings))}))
