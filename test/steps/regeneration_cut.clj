(ns steps.regeneration-cut
  "Step definitions for dhf/spec/regeneration/cut.feature. Each scenario builds
  a throwaway repository whose origin is a local bare repository, then runs
  the rdm-spec command in it (the shared `\"rdm-spec ...\" is run` step reads
  the repository from :dir)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.regeneration-support :as g]
            [steps.support :as s]))

(defn- needs-cut! [] (s/needs-built! 'rdm-spec.regeneration.cut))

(defn- run-cut [ctx]
  (assoc ctx :run (s/rdm-spec! (:dir ctx) "cut")))

;; --- @spec-0036 --------------------------------------------------------------

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-6"
  (fn [ctx]
    (needs-cut!)
    (let [{:keys [work origin]} (g/cut-repo!)]
      ;; earlier cuts, pushed, on earlier merges; a tag that is no cut
      (g/git! work "tag" "spec-5")
      (g/merge-commit! work 1)
      (g/git! work "tag" "spec-6")
      (g/git! work "tag" "v6.0")
      (g/merge-commit! work 2)
      (g/git! work "push" "-q" "origin" "main" "--tags")
      (s/check! (= (g/git! work "rev-parse" "HEAD") (g/git! work "rev-parse" "origin/HEAD"))
                "the fixture's HEAD is not origin/HEAD")
      (assoc ctx :dir work :origin origin :head (g/git! work "rev-parse" "HEAD")))))

(defstep #"HEAD is tagged spec-7"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (zero? (:exit r)) (s/show r))
      (s/check! (= ["spec-7"] (g/tags-at (:dir ctx) "HEAD"))
                (str "HEAD carries " (pr-str (g/tags-at (:dir ctx) "HEAD")) "\n" (s/show r)))
      (s/check! (str/includes? (:out r) "spec-7") (str "the result does not name spec-7\n" (s/show r))))))

(defstep #"the tag is pushed to origin"
  (fn [ctx]
    (let [at-origin (g/git (:origin ctx) "rev-parse" "--verify" "--quiet" "refs/tags/spec-7^{commit}")]
      (s/check! (zero? (:exit at-origin)) (str "origin holds no tag spec-7\n" (s/show (:run ctx))))
      (s/check! (= (:head ctx) (:out at-origin)) "origin's spec-7 names another commit"))))

;; --- @spec-0162 --------------------------------------------------------------

(defstep #"the commit tagged spec-7"
  (fn [ctx]
    (needs-cut!)
    (let [{:keys [work origin]} (g/cut-repo!)]
      (g/git! work "tag" "spec-6")
      (g/merge-commit! work 1)
      (g/git! work "tag" "spec-7")
      (g/git! work "push" "-q" "origin" "main" "--tags")
      (assoc ctx :dir work :origin origin
             :tags-before (g/all-tags work) :origin-tags-before (g/all-tags origin)))))

(defstep #"\"rdm-spec cut\" is run again on it"
  (fn [ctx] (run-cut ctx)))

(defstep #"no tag is added and the cut says the commit is already spec-7"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (zero? (:exit r)) (s/show r))
      (s/check! (= (:tags-before ctx) (g/all-tags (:dir ctx)))
                (str "the tags changed: " (pr-str (g/all-tags (:dir ctx)))))
      (s/check! (= (:origin-tags-before ctx) (g/all-tags (:origin ctx)))
                (str "origin's tags changed: " (pr-str (g/all-tags (:origin ctx)))))
      (s/check! (re-find #"already spec-7\b" (str (:out r) (:err r)))
                (str "the cut does not say the commit is already spec-7\n" (s/show r))))))

;; --- @spec-0163 --------------------------------------------------------------

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (needs-cut!)
    (let [{:keys [work origin]} (g/cut-repo!)]
      (g/git! work "tag" "spec-1")
      (g/git! work "push" "-q" "origin" "main" "--tags")
      (g/git! work "checkout" "-q" "-b" "feature-x")
      (g/merge-commit! work 1)
      (assoc ctx :dir work :origin origin
             :tags-before (g/all-tags work) :origin-tags-before (g/all-tags origin)))))

(defstep #"\"rdm-spec cut\" is run on it"
  (fn [ctx] (run-cut ctx)))

(defstep #"it exits 2 and names the branch"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      ;; the default branch, the one cut, and the branch HEAD is on
      (s/check! (re-find #"\bmain\b" (:err r)) (str "the default branch main is not named\n" (s/show r)))
      (s/check! (str/includes? (:err r) "feature-x") (str "HEAD's branch is not named\n" (s/show r)))
      (s/check! (= (:tags-before ctx) (g/all-tags (:dir ctx))) "a tag was added")
      (s/check! (= (:origin-tags-before ctx) (g/all-tags (:origin ctx))) "a tag was pushed"))))
