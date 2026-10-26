(ns rdm-spec.project.adopt
  "Adopt (dhf/spec/project/adopt.feature): adds the skeleton to the git
  repository it runs in, never overwriting a file, and never reading the
  existing code into the spec."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn missing-lines
  "The lines of `wanted` the ignore file's text does not hold."
  [text wanted]
  (let [have (set (map str/trim (str/split-lines (str text))))]
    (vec (remove have wanted))))

(defn stage [{:keys [root]}]
  (let [{:keys [exit out]} (cmd/git root "rev-parse" "--show-toplevel")]
    (when-not (= 0 exit)
      (cmd/cannot-run! (str root " is not a git repository: the hook needs a git repository, so adopt runs in one")))
    (when-not (= (str (fs/canonicalize root)) (str (fs/canonicalize (str/trim out))))
      (cmd/cannot-run! (str "adopt runs in the root of a git repository, " (str/trim out) ", not in " root))))
  (let [{:keys [wrote kept]} (init/write-skeleton! root (init/skeleton (str (fs/file-name (fs/absolutize root))) "spec"))
        ignore (fs/path root ".gitignore")
        lines (when (some #{".gitignore"} kept)
                (missing-lines (when (fs/regular-file? ignore) (slurp (fs/file ignore))) init/gitignore-lines))]
    (hooks/install! root)
    {:result {:adopt (cond-> {:wrote wrote :kept kept :next (rest init/next-steps)}
                       (some? lines) (assoc :gitignore-lines lines))}}))
