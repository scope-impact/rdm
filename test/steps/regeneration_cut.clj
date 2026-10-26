(ns steps.regeneration-cut
  "Step definitions of dhf/spec/regeneration/cut.feature: a project with a
  bare origin the scenario makes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn with-origin!
  "The project committed, an earlier commit tagged `last-cut`, pushed to a
  bare origin whose HEAD is main."
  [ctx last-cut]
  (let [ctx (k/project! ctx)
        origin (k/path-in ctx "origin.git")
        d (:dir ctx)]
    (k/git (:base ctx) "init" "-q" "--bare" origin)
    (k/commit! d "first")
    (k/git d "branch" "-M" "main")
    (when last-cut (k/git d "tag" last-cut))
    (k/write-files! d {"dhf/spec/glossary.md" "# Glossary\n"})
    (k/commit! d "second")
    (k/git d "remote" "add" "origin" origin)
    (k/git d "push" "-q" "origin" "main" "--tags")
    (k/git d "remote" "set-head" "origin" "main")
    (assoc ctx ::origin origin)))

(defn tags-at [dir rev] (set (remove str/blank? (str/split-lines (k/git dir "tag" "--points-at" rev)))))

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-6"
  (fn [ctx] (with-origin! ctx "spec-6")))

(defstep #"HEAD is tagged spec-7"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (contains? (tags-at (:dir ctx) "HEAD") "spec-7") (pr-str (tags-at (:dir ctx) "HEAD")))
    ctx))

(defstep #"the tag is pushed to origin"
  (fn [ctx]
    (assert (str/includes? (k/git (:dir ctx) "ls-remote" "--tags" "origin") "refs/tags/spec-7"))
    ctx))

(defstep #"the commit tagged spec-7"
  (fn [ctx]
    (let [ctx (with-origin! ctx "spec-6")]
      (k/git (:dir ctx) "tag" "spec-7")
      (k/git (:dir ctx) "push" "-q" "origin" "spec-7")
      (assoc ctx ::tags (k/git (:dir ctx) "tag")))))

(defstep #"\"rdm-spec cut\" is run (?:again )?on it"
  (fn [ctx] (k/rdm ctx ["cut"])))

(defstep #"no tag is added and the cut says the commit is already spec-7"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (::tags ctx) (k/git (:dir ctx) "tag")))
    (assert (str/includes? (str (:out ctx) (:err ctx)) "already spec-7") (:out ctx))
    ctx))

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {"dhf/spec/kernel/core.feature"
                                          (k/feature-text {:name "Core" :tags ["UN-001"]
                                                           :scenarios [{:tags ["spec-0001"] :title "No level" :steps ["Given a step"]}]})}})]
      (with-origin! ctx "spec-6"))))

(defstep #"it exits 1, tags nothing, and prints each of the check's finding lines unchanged"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (empty? (tags-at (:dir ctx) "HEAD")))
    (let [check (k/rdm ctx ["check"])
          findings (remove #(str/includes? % "warning:") (k/err-lines check))]
      (assert (seq findings))
      (doseq [l findings] (assert (some #{l} (k/err-lines ctx)) (str "not printed: " l))))
    ctx))

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (let [ctx (with-origin! ctx "spec-6")]
      (k/write-files! (:dir ctx) {"dhf/spec/glossary.md" "# Glossary\n\nMore.\n"})
      (k/commit! (:dir ctx) "local only")
      ctx)))

(defstep #"it exits 2 and names the branch"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "main")))
