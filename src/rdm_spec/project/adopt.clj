(ns rdm-spec.project.adopt
  "Component Adopt (project context): adds the skeleton to an existing git
  repository, never overwriting anything already there, and never reading
  its code into the spec."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.project.hooks :as hooks]
            [rdm-spec.project.init :as init]))

(defn adopt-task [_]
  (let [root (project/root)]
    (when-not (project/git-repo? root)
      (project/cannot-run! (str root " is not a git repository: the hook needs a git repository, so adopt wrote nothing")))
    (let [files (init/skeleton (fs/file-name root) "spec")
          exists? #(let [f (project/abs root %)] (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))
          kept (vec (sort (filter exists? (keys files))))
          wrote (vec (sort (remove exists? (keys files))))]
      (doseq [rel wrote]
        (let [f (project/abs root rel)] (fs/create-dirs (fs/parent f)) (spit f (get files rel))))
      (hooks/install! root)
      (let [gi (when (some #{".gitignore"} kept)
                 (let [have (set (map str/trim (str/split-lines (or (project/read-text root ".gitignore") ""))))]
                   (vec (remove have init/gitignore-lines))))]
        (project/report {:adopt (cond-> {:wrote wrote :kept kept}
                                  gi (assoc :gitignore-lines gi))}
                        []
                        (when (seq gi)
                          [(project/warning ".gitignore" "gitignore-kept"
                                            (str "kept as it is; add the lines " (str/join ", " gi)))]))))))
