(ns steps.regeneration-cut
  "Step definitions for dhf/spec/regeneration/cut.feature.

  Context keys set here: :dir (the project, a git repository on main with
  an origin), :origin (the bare repository), :head (HEAD's commit),
  :tags-before and :remote-before (the tags before the When), :check-run
  (rdm-spec check run on HEAD), :branch and :default-branch."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- project-with-origin!
  "A copy of the room, committed on main with the cuts spec-5 and spec-6 on
  older commits, `edit!` applied before its last commit, pushed to a new
  origin."
  [ctx edit!]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (rs/commit! dir "the first merge")
    (rs/tag! dir "spec-5")
    (s/write! dir "README.md" "a second merge\n")
    (rs/commit! dir "the second merge")
    (rs/tag! dir "spec-6")
    (s/write! dir "README.md" "a third merge\n")
    (edit! dir)
    (let [head (rs/commit! dir "the third merge")
          bare (rs/origin! dir)]
      (assoc ctx :dir dir :origin bare :head head
             :tags-before (rs/tags dir) :remote-before (rs/remote-tags bare)))))

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-6"
  (fn [ctx] (project-with-origin! ctx (fn [_]))))

(defstep #"HEAD is tagged spec-7"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          at-head (rs/git! (:dir ctx) "tag" "--points-at" "HEAD")]
      (s/check! (some #{"spec-7"} (str/split-lines at-head)) (str "HEAD is not tagged spec-7: " (pr-str at-head) "\n" (s/show r)))
      (s/check! (= (sort (conj (:tags-before ctx) "spec-7")) (rs/tags (:dir ctx)))
                (str "the tags are " (pr-str (rs/tags (:dir ctx))) ", not those before and spec-7"))
      nil)))

(defstep #"the tag is pushed to origin"
  (fn [ctx]
    (let [remote (rs/remote-tags (:origin ctx))]
      (s/check! (= (:head ctx) (get remote "spec-7")) (str "origin's spec-7 is " (pr-str (get remote "spec-7"))
                                                         ", not HEAD " (:head ctx)))
      nil)))

;; --- cutting twice -------------------------------------------------------------

(defstep #"the commit tagged spec-7"
  (fn [ctx]
    (let [ctx (project-with-origin! ctx (fn [_]))
          dir (:dir ctx)]
      (rs/tag! dir "spec-7")
      (rs/git! dir "push" "-q" "origin" "refs/tags/spec-7")
      (assoc ctx :tags-before (rs/tags dir) :remote-before (rs/remote-tags (:origin ctx))))))

(defstep #"\"rdm-spec cut\" is run again on it"
  (fn [ctx] (kc/run-line ctx "cut")))

(defstep #"no tag is added and the cut says the commit is already spec-7"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          result (kc/stdout-edn r)]
      (s/check! (= (:tags-before ctx) (rs/tags (:dir ctx))) (str "the tags changed: " (pr-str (rs/tags (:dir ctx)))))
      (s/check! (= (:remote-before ctx) (rs/remote-tags (:origin ctx))) "origin's tags changed")
      (s/check! (re-find #"already spec-7" (pr-str result))
                (str "the cut does not say the commit is already spec-7\n" (s/show r)))
      nil)))

;; --- a HEAD whose spec fails the check --------------------------------------------

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [ctx]
    (let [ctx (project-with-origin! ctx (fn [dir] (rs/drop-level! dir)))
          check (kc/run! ctx (:dir ctx) ["check"])]
      (s/check! (= 1 (:exit check)) (str "rdm-spec check does not exit 1 on HEAD\n" (s/show check)))
      (assoc ctx :check-run check))))

(defstep #"it exits 1, tags nothing, and prints each of the check's finding lines unchanged"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          want (kc/finding-lines (:check-run ctx))
          got (set (kc/finding-lines r))]
      (s/check! (seq want) "the check named no finding")
      (doseq [l want]
        (s/check! (contains? got l) (str "the cut does not print the check's line " (pr-str l) "\n" (s/show r))))
      (s/check! (= (:tags-before ctx) (rs/tags (:dir ctx))) (str "the tags changed: " (pr-str (rs/tags (:dir ctx)))))
      (s/check! (= (:remote-before ctx) (rs/remote-tags (:origin ctx))) "origin's tags changed")
      nil)))

;; --- a HEAD that is not origin/HEAD ------------------------------------------------

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (let [ctx (project-with-origin! ctx (fn [_]))
          dir (:dir ctx)]
      (rs/git! dir "checkout" "-q" "-b" "feature")
      (s/write! dir "README.md" "work not merged\n")
      (rs/commit! dir "work not merged")
      (assoc ctx :branch "feature" :default-branch "main"
             :tags-before (rs/tags dir) :remote-before (rs/remote-tags (:origin ctx))))))

(defstep #"\"rdm-spec cut\" is run on it"
  (fn [ctx] (kc/run-line ctx "cut")))

(defstep #"it exits 2 and names the branch"
  (fn [ctx]
    (kc/refused-naming! ctx (:branch ctx) (:default-branch ctx))
    (s/check! (= (:tags-before ctx) (rs/tags (:dir ctx))) "a tag was added")
    nil))
