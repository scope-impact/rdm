(ns steps.regeneration-cut
  "Steps of the component Cut (dhf/spec/06-regenerate/cut.feature).
  The scenario's origin is a bare repository it makes."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn branch [ctx] (k/git! ctx "rev-parse" "--abbrev-ref" "HEAD"))

(defn origin!
  "A project committed and pushed to its own origin, origin/HEAD set."
  [ctx & [opts]]
  (let [ctx (k/spec-project! ctx opts)
        origin (str (:base ctx) "/origin.git")]
    (k/git! ctx "init" "-q" "--bare" origin)
    (k/commit! ctx "the first spec")
    (k/git! ctx "remote" "add" "origin" origin)
    (k/git! ctx "push" "-q" "origin" (branch ctx))
    (k/git! ctx "remote" "set-head" "origin" (branch ctx))
    (assoc ctx ::origin origin)))

(defn push! [ctx] (k/git! ctx "push" "-q" "origin" (branch ctx)) ctx)

(defn remote-tags [ctx] (set (map #(last (str/split % #"/")) (remove str/blank? (str/split-lines (k/git! ctx "ls-remote" "--tags" "origin"))))))

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-6"
  (fn [ctx]
    (let [ctx (origin! ctx)]
      (k/tag! ctx "spec-6")
      (k/write! ctx "dhf/spec/glossary.md" "# Glossary\n\nA term.\n")
      (k/commit! ctx "a spec change")
      (push! ctx))))

(defstep #"HEAD is tagged spec-7"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (k/git! ctx "rev-parse" "HEAD") (k/git! ctx "rev-parse" "spec-7^{commit}")) "HEAD is not spec-7")
    ctx))

(defstep #"the tag is pushed to origin"
  (fn [ctx] (k/check! ((remote-tags ctx) "spec-7") "origin holds the tags " (pr-str (remote-tags ctx))) ctx))

(defstep #"the commit tagged spec-7"
  (fn [ctx]
    (let [ctx (origin! ctx)]
      (k/tag! ctx "spec-7")
      (k/git! ctx "push" "-q" "origin" "spec-7")
      ctx)))

(defstep #"\"rdm-spec cut\" is run again on it" (fn [ctx] (k/run-rdm ctx "cut")))

(defstep #"no tag is added and the cut says the commit is already spec-7"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= "spec-7" (k/git! ctx "tag" "--points-at" "HEAD")) "the tags at HEAD are " (k/git! ctx "tag" "--points-at" "HEAD"))
    (k/check! (str/includes? (str (:out ctx) (:err ctx)) "already spec-7") "the cut does not say so: " (k/shows ctx))
    ctx))

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [ctx]
    (let [ctx (origin! ctx {:features {"dhf/spec/demo/thing.feature"
                                       "@UN-001\nFeature: Thing\n\n  @spec-0001\n  Scenario: No level\n    Given a thing\n"}})
          check (k/run-rdm ctx "check")]
      (k/exits! check 1)
      (assoc ctx ::check-lines (remove #(re-find #"warning: " %) (k/err-lines check))))))

(defstep #"it exits 1, tags nothing, and prints each of the check's finding lines unchanged"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (str/blank? (k/git! ctx "tag")) "a tag was made")
    (doseq [l (::check-lines ctx)] (k/check! (some #{l} (k/err-lines ctx)) "the line is not printed: " l))
    ctx))

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (let [ctx (origin! ctx)]
      (k/git! ctx "checkout" "-q" "-b" "feature-x")
      (k/write! ctx "dhf/spec/glossary.md" "# Glossary\n\nUnmerged.\n")
      (k/commit! ctx "not merged"))))

(defstep #"\"rdm-spec cut\" is run on it" (fn [ctx] (k/run-rdm ctx "cut")))

(defstep #"it exits 2 and names the branch"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "feature-x") ctx))
