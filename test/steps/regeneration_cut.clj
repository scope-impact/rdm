(ns steps.regeneration-cut
  "Step definitions for dhf/spec/regeneration/cut.feature.

  A cut fixture is a repository with a bare origin (steps.regeneration-support/
  origin-project!) holding a copy of the room's allow-list, on which rdm-spec
  check passes. The context keeps :dir (the repository), :origin (the bare
  origin) and :head (the commit the scenario cuts)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-cut! [] (s/needs-built! 'rdm-spec.regeneration.cut))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- spec-tags [dir]
  (set (filter #(re-matches #"spec-\d+" %) (rs/all-tags dir))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-(\d+)"
  (fn [ctx n]
    (needs-cut!)
    (let [n (parse-long n)
          p (rs/origin-project! {:cuts [(str "spec-" (dec n)) (str "spec-" n)]})]
      (s/check! (= (:head p) (rs/git! (:dir p) "rev-parse" "refs/remotes/origin/HEAD"))
                "origin/HEAD does not name HEAD")
      (merge ctx p {:tags-before (spec-tags (:dir p))}))))

(defstep #"the commit tagged spec-(\d+)"
  (fn [ctx n]
    (needs-cut!)
    (let [tag (str "spec-" n)
          p (rs/origin-project! {:cuts [(str "spec-" (dec (parse-long n)))]})]
      ;; HEAD is the merge, already cut as spec-n and pushed
      (rs/git! (:dir p) "tag" tag)
      (rs/git! (:dir p) "push" "-q" "origin" tag)
      (merge ctx p {:tag tag :tags-before (spec-tags (:dir p))
                    :remote-before (rs/remote-tags (:origin p))}))))

(def ^:private broken-scenario
  "The feature and tag the failing Given breaks: one scenario loses its level."
  ["dhf/spec/kernel/command.feature" "@spec-0177 @contract" "@spec-0177"])

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [ctx]
    (needs-cut!)
    (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
    (let [p (rs/origin-project! {:cuts ["spec-1" "spec-2"]})
          dir (:dir p)
          [rel from to] broken-scenario
          f (fs/file dir rel)
          text (slurp f)]
      (s/check! (str/includes? text from) (str rel " holds no " from))
      (spit f (str/replace text from to))
      (let [head (rs/commit! dir "a merged spec change that breaks the check")]
        (rs/push-head! dir)
        (rs/git! dir "remote" "set-head" "origin" "main")
        (let [check (s/rdm-spec! dir "check")]
          (s/check! (= 1 (:exit check)) (str "rdm-spec check does not exit 1 on HEAD\n" (s/show check)))
          (merge ctx p {:head head :check check :tags-before (spec-tags dir)
                        :remote-before (rs/remote-tags (:origin p))}))))))

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (needs-cut!)
    (let [p (rs/origin-project! {:cuts ["spec-1"]})
          dir (:dir p)]
      (rs/git! dir "checkout" "-q" "-b" "feature-x")
      (s/write! dir "dhf/spec/unmerged.md" "not merged yet\n")
      (let [head (rs/commit! dir "work not merged to the default branch")]
        (merge ctx p {:head head :branch "feature-x" :tags-before (spec-tags dir)
                      :remote-before (rs/remote-tags (:origin p))})))))

;; ---------------------------------------------------------------------------
;; Whens (the plain one is steps.kernel-command's)

(defstep #"\"rdm-spec ([^\"]*)\" is run (?:again )?on it"
  (fn [ctx line] (kc/run-line ctx line)))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"HEAD is tagged spec-(\d+)"
  (fn [ctx n]
    (let [r (run ctx)
          tag (str "spec-" n)
          dir (:dir ctx)]
      (s/check! (zero? (:exit r)) (str "the cut did not pass\n" (s/show r)))
      (s/check! (contains? (rs/tags-at dir "HEAD") tag) (str "HEAD is not tagged " tag "; its tags: "
                                                             (rs/tags-at dir "HEAD") "\n" (s/show r)))
      (s/check! (= #{tag} (into #{} (remove (:tags-before ctx)) (spec-tags dir)))
                (str "the cut added other tags than " tag ": " (spec-tags dir)))
      (s/check! (= tag (:cut (kc/stdout-edn r))) (str "the result does not name the cut " tag "\n" (s/show r)))
      (assoc ctx :tag tag))))

(defstep #"the tag is pushed to origin"
  (fn [ctx]
    (let [remote (rs/remote-tags (:origin ctx))
          tag (:tag ctx)]
      (s/check! (= (:head ctx) (get remote tag))
                (str "origin holds " tag " at " (pr-str (get remote tag)) ", not HEAD " (:head ctx))))))

(defstep #"no tag is added and the cut says the commit is already spec-(\d+)"
  (fn [ctx n]
    (let [r (run ctx)
          tag (str "spec-" n)
          out (kc/stdout-edn r)]
      (s/check! (zero? (:exit r)) (str "cutting a commit already cut did not pass\n" (s/show r)))
      (s/check! (= (:tags-before ctx) (spec-tags (:dir ctx)))
                (str "the tags changed: " (:tags-before ctx) " -> " (spec-tags (:dir ctx))))
      (s/check! (= (:remote-before ctx) (rs/remote-tags (:origin ctx))) "origin's tags changed")
      (s/check! (= tag (:cut out)) (str "the result names no cut " tag "\n" (s/show r)))
      (s/check! (true? (:already-cut out)) (str "the result does not say the commit was already cut\n" (s/show r)))
      (s/check! (re-find (re-pattern (str "already " tag)) (pr-str out))
                (str "the cut does not say the commit is already " tag "\n" (s/show r))))))

(defn- nothing-tagged! [ctx]
  (s/check! (= (:tags-before ctx) (spec-tags (:dir ctx)))
            (str "the tags changed: " (:tags-before ctx) " -> " (spec-tags (:dir ctx))))
  (s/check! (= (:remote-before ctx) (rs/remote-tags (:origin ctx))) "origin's tags changed"))

(defstep #"it exits 1, tags nothing, and names the check's findings"
  (fn [ctx]
    (let [r (run ctx)
          check-errors (remove #(str/includes? % "warning: ") (s/err-lines (:check ctx)))
          cut-err (s/err-lines r)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (nil? (:cut (kc/stdout-edn r))) (str "a refused cut names a cut\n" (s/show r)))
      (nothing-tagged! ctx)
      (s/check! (seq check-errors) "the check named no finding")
      (doseq [l check-errors]
        (s/check! (some #(str/includes? % l) cut-err)
                  (str "the cut does not name the check's finding " (pr-str l) "\n" (s/show r)))))))

(defstep #"it exits 2 and names the branch"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/includes? (:err r) (:branch ctx)) (str "the error stream does not name " (:branch ctx) "\n" (s/show r)))
      (nothing-tagged! ctx))))
