(ns rdm-spec.project.adopt
  "Adopt (dhf/spec/project/adopt.feature): `rdm-spec adopt` adds the
  skeleton, as init writes it, to the git repository at the working
  directory, never overwriting a file, and installs the pre-commit hook.

  A destination that exists (a file, a directory, or a symbolic link, even a
  broken one) is kept and named. An existing .gitignore is kept, and the
  lines init would have added to it, those it does not hold, are named, for
  a person to add. core.hooksPath is set to .githooks unless another is set,
  which is kept and named.

  Adopt never reads the existing code into the spec: the adopted model holds
  the system and nothing else, there are no needs, the ledger is at 0 and no
  feature file is written.

  Cannot run (exit 2), writing nothing: the working directory is not a git
  repository (the hook needs one), or is not its top.

  Result: {:adopt {:wrote [path] :kept [path] :gitignore {:kept bool
  :lines-to-add [line]} :hooks-path P :next-steps [..]}}; a kept .gitignore
  with lines to add and a hooks path kept are also warnings."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn- lines-of [text]
  (vec (remove #(or (str/blank? %) (str/starts-with? % "#")) (map str/trim (str/split-lines (str text))))))

(defn missing-lines
  "Pure: the lines of the skeleton's .gitignore `wanted` that `existing` does not hold."
  [wanted existing]
  (let [have (set (lines-of existing))]
    (vec (remove have (lines-of wanted)))))

(defn adopt
  "The adopt task's stage."
  [req]
  (let [root (:root req)
        top (hooks/toplevel root)
        _ (when-not top
            (command/cannot-run! (str root " is not a git repository: the hook needs a git repository"
                                      " (git init it first, or start a new project with rdm-spec init)")))
        _ (when-not (= (str (fs/canonicalize top)) (str (fs/canonicalize root)))
            (command/cannot-run! (str "adopt runs at the top of the git repository, " top
                                      ": the hook is installed for the whole repository")))
        opts {:name (fs/file-name (fs/canonicalize root)) :id-prefix init/default-prefix}
        results (vec (for [[dest resource] init/skeleton]
                       (if (init/exists? (fs/file root dest))
                         {:kept dest}
                         (do (init/write-file! root dest resource opts) {:wrote dest}))))
        kept (vec (keep :kept results))
        gitignore-kept? (some #{".gitignore"} kept)
        to-add (if gitignore-kept?
                 (missing-lines (init/skeleton-text "gitignore" opts)
                                (let [f (fs/file root ".gitignore")] (when (fs/regular-file? f) (slurp (str f)))))
                 [])
        {:keys [installed? hooks-path]} (hooks/install! root)]
    {:result {:adopt {:wrote (vec (keep :wrote results))
                      :kept kept
                      :gitignore {:kept (boolean gitignore-kept?) :lines-to-add to-add}
                      :hooks-path hooks-path
                      :next-steps init/next-steps}}
     :findings (vec (concat
                     (when (seq to-add)
                       [(command/warning ".gitignore" "gitignore-kept"
                                         (str "kept as it was; the lines init writes that it does not hold: "
                                              (str/join " " to-add)))])
                     (when-not installed?
                       [(command/warning "hook-not-installed"
                                         (str "core.hooksPath is " (pr-str hooks-path) ", kept: the hook in "
                                              hooks/hook-file " runs only once it points at " hooks/hooks-path))])))}))
