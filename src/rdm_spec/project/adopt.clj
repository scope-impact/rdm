(ns rdm-spec.project.adopt
  "The Adopt component of the project context (dhf/spec/project/adopt.feature):
  `rdm-spec adopt` adds the skeleton (rdm-spec.project.init) to the git
  repository holding the current directory, at its top, and never overwrites
  anything: a destination that exists, a symbolic link included, is kept and
  named. An existing .gitignore is kept, and the lines init would have added
  to it are named. The hook is installed as init installs it, unless a hooks
  path is already set (rdm-spec.project.hooks). Adopt never reads the
  repository's code: the spec it adds is the empty one init writes.

  A directory that is not in a git repository is refused before anything is
  written: the hook needs one."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn missing-gitignore-lines
  "The lines init writes to .gitignore that `text` (an existing one) lacks."
  [text]
  (let [have (set (map str/trim (str/split-lines (or text ""))))]
    (vec (remove have (init/gitignore-lines)))))

(defn adopt
  "The adopt task's stage. Result {:adopt {:root :wrote [path] :kept [path]
  :gitignore-lines [line]}}; warnings name what was kept that matters."
  [req]
  (let [top (hooks/require-repository! (:root req))
        gi (fs/file top ".gitignore")
        gi-before (when (and (init/exists? gi) (fs/regular-file? gi)) (slurp (str gi)))
        gi-existed? (init/exists? gi)
        {:keys [wrote kept]} (init/write-skeleton! top (init/vars (str (fs/file-name top)) init/default-prefix))
        lines (if gi-existed? (missing-gitignore-lines gi-before) [])
        hook (hooks/activate! top)]
    {:result {:adopt {:root (command/rel req top)
                      :wrote wrote
                      :kept kept
                      :gitignore-lines lines}}
     :findings (vec (concat
                     (when (seq lines)
                       [(command/warning ".gitignore" nil "gitignore-kept"
                                         (str "the .gitignore already there is kept; add the lines init writes: "
                                              (str/join ", " lines)))])
                     (hooks/check-kept-hook top)
                     (:findings hook)))}))
