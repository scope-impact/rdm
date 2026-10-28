(ns steps.regeneration-cut
  "Steps of the component Cut (06-regenerate/cut.feature), and the regeneration
  context's repository fixtures."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn with-origin!
  "Makes the project at :dir a git repository with a bare origin, its default branch main pushed."
  [ctx]
  (let [d (:dir ctx) origin (k/path (:top ctx) "origin.git")]
    (k/git! d "init" "-q")
    (k/commit-all! d "the first change")
    (k/git! d "checkout" "-q" "-B" "main")
    (fs/create-dirs origin)
    (k/git! origin "init" "-q" "--bare" "-b" "main")
    (k/git! d "remote" "add" "origin" origin)
    (k/git! d "push" "-q" "origin" "main")
    (k/git! d "fetch" "-q" "origin")
    (k/git! d "remote" "set-head" "origin" "main")
    (assoc ctx :origin origin)))

(defn spec! [] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}))

(defn push-new-commit! [ctx]
  (k/write! (:dir ctx) "dhf/spec/glossary.md" (str "# Glossary\n\n" (random-uuid) "\n"))
  (k/commit-all! (:dir ctx) "a merge")
  (k/git! (:dir ctx) "push" "-q" "origin" "main")
  (k/git! (:dir ctx) "fetch" "-q" "origin")
  ctx)

(defn tags-at [ctx rev] (set (remove str/blank? (str/split-lines (k/git! (:dir ctx) "tag" "--points-at" rev)))))
(defn origin-tags [ctx] (set (remove str/blank? (str/split-lines (k/git! (:origin ctx) "tag" "--list")))))

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-6"
  (fn [_]
    (let [ctx (with-origin! (spec!))]
      (k/git! (:dir ctx) "tag" "spec-6")
      (k/git! (:dir ctx) "push" "-q" "origin" "spec-6")
      (push-new-commit! ctx))))

(defstep #"HEAD is tagged spec-7"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (contains? (tags-at ctx "HEAD") "spec-7") ctx "HEAD is not spec-7") ctx))

(defstep #"the tag is pushed to origin"
  (fn [ctx] (k/expect! (contains? (origin-tags ctx) "spec-7") ctx "origin has no spec-7") ctx))

(defstep #"the commit tagged spec-7"
  (fn [_]
    (let [ctx (with-origin! (spec!))]
      (k/git! (:dir ctx) "tag" "spec-7")
      (k/git! (:dir ctx) "push" "-q" "origin" "spec-7")
      ctx)))

(defstep #"\"rdm-spec cut\" is run (?:again )?on it"
  (fn [ctx] (k/rdm! ctx "cut")))

(defstep #"no tag is added and the cut says the commit is already spec-7"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= #{"spec-7"} (tags-at ctx "HEAD")) ctx "a tag was added")
    (k/out-has! ctx "already spec-7")
    ctx))

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [_]
    (let [ctx (with-origin! (spec!))]
      (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :level nil}]}))
      (push-new-commit! ctx)
      (let [c (k/rdm! ctx "check")]
        (k/exits! c 1)
        (assoc ctx :check-lines (k/finding-lines c))))))

(defstep #"it exits 1, tags nothing, and prints each of the check's finding lines unchanged"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/expect! (empty? (tags-at ctx "HEAD")) ctx "a tag was added")
    (k/expect! (seq (:check-lines ctx)) ctx "the check had no finding")
    (doseq [l (:check-lines ctx)] (k/expect! (some #{l} (k/err-lines ctx)) ctx (str "not relayed: " l)))
    ctx))

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [_]
    (let [ctx (with-origin! (spec!))]
      (k/git! (:dir ctx) "checkout" "-q" "-b" "feature-x")
      (k/write! (:dir ctx) "dhf/spec/glossary.md" "# Glossary\n\nmore\n")
      (k/commit-all! (:dir ctx) "unmerged")
      ctx)))

(defstep #"it exits 2 and names the branch"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "feature-x") ctx))
