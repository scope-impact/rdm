(ns rdm-spec.regeneration.cut-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

(deftest cut-numbers
  (is (= 7 (cut/cut-number "spec-7")))
  (is (= 12 (cut/cut-number "spec-12")))
  (is (nil? (cut/cut-number "spec-")))
  (is (nil? (cut/cut-number "spec-7-rc")))
  (is (nil? (cut/cut-number "v7")))
  (is (nil? (cut/cut-number nil)))
  (testing "the last cut is the highest number, not the last name"
    (is (= ["spec-2" "spec-9" "spec-10"] (cut/sort-cuts ["spec-10" "x" "spec-2" "spec-9"])))
    (is (= "spec-11" (cut/next-cut ["spec-10" "spec-9" "v100"])))
    (is (= "spec-1" (cut/next-cut [])))))

(deftest short-hashes-and-lines
  (is (= "0123456" (cut/short-hash "0123456789abcdef")))
  (is (= "abc" (cut/short-hash "abc")))
  (is (= ["a" "b"] (cut/lines "a\n\n b \n"))))

;; ---------------------------------------------------------------------------
;; On throwaway repositories

(def ^:private ident
  {:extra-env {"GIT_AUTHOR_NAME" "T" "GIT_AUTHOR_EMAIL" "t@x.invalid"
               "GIT_COMMITTER_NAME" "T" "GIT_COMMITTER_EMAIL" "t@x.invalid"}})

(defn- sh [d & args]
  (apply cut/git! d ident "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null"
         "-c" "init.defaultBranch=main" args))

(defn- write! [d rel text]
  (fs/create-dirs (fs/parent (fs/file d rel)))
  (spit (str (fs/file d rel)) text))

(defn- commit! [d msg]
  (sh d "add" "-A") (sh d "commit" "-q" "--allow-empty" "-m" msg) (sh d "rev-parse" "HEAD"))

(defn- repo-with-origin!
  "A repository with a bare origin, origin/HEAD naming main, and no config,
  so the check cannot run in it; returns {:dir :origin :head}."
  []
  (let [t (str (fs/create-temp-dir {:prefix "cut-test-"}))
        origin (str (fs/file t "origin.git"))
        d (str (fs/file t "work"))]
    (fs/create-dirs d)
    (sh t "init" "-q" "--bare" origin)
    (sh d "init" "-q")
    (sh d "remote" "add" "origin" origin)
    (write! d "a.txt" "a")
    (commit! d "one")
    (sh d "tag" "spec-1")
    (write! d "b.txt" "b")
    (let [head (commit! d "two")]
      (sh d "push" "-q" "origin" "main" "--tags")
      (sh d "remote" "set-head" "origin" "main")
      {:dir d :origin origin :head head})))

(deftest git-helpers
  (let [{:keys [dir head]} (repo-with-origin!)]
    (is (nil? (cut/repository! dir)))
    (is (= head (cut/rev dir "HEAD")))
    (is (= head (cut/rev dir "main")))
    (is (nil? (cut/rev dir "no-such-name")))
    (is (= ["spec-1"] (cut/cuts-at dir (cut/rev dir "spec-1"))))
    (is (= [] (cut/cuts-at dir head)))
    (is (= {:branch "main" :commit head} (cut/default-branch dir)))
    (is (= "main" (cut/current-branch dir)))
    (is (= ["spec-1"] (cut/remote-cuts dir)))
    (testing "git returns the outcome; git! cannot run on a failure"
      (is (pos? (:exit (cut/git dir "rev-parse" "--verify" "nothing"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git" (cut/git! dir "rev-parse" "--verify" "nothing"))))
    (testing "checkout! writes the commit's paths, never the working tree's"
      (write! dir "a.txt" "changed in the working tree")
      (let [out (str (fs/file (fs/create-temp-dir) "out"))]
        (cut/checkout! dir head out ["a.txt"])
        (is (= "a" (slurp (str (fs/file out "a.txt")))))
        (is (not (fs/exists? (fs/file out "b.txt"))))
        (is (not (fs/exists? (fs/file out ".git"))))))))

(deftest not-a-repository
  (let [d (str (fs/create-temp-dir))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a git repository" (cut/repository! d)))
    (is (= 2 (:exit (command/run-task ["cut"] d {"PATH" (System/getenv "PATH")}))))))

(deftest the-task
  (testing "HEAD not on origin/HEAD is refused, naming both branches"
    (let [{:keys [dir]} (repo-with-origin!)]
      (sh dir "checkout" "-q" "-b" "topic")
      (commit! dir "three")
      (let [r (command/run-task ["cut"] dir nil)]
        (is (= 2 (:exit r)))
        (is (str/blank? (:out r)))
        (is (some #(and (str/includes? % "topic") (str/includes? % "main")) (:err r)) (pr-str r)))))
  (testing "no origin/HEAD is refused"
    (let [{:keys [dir]} (repo-with-origin!)]
      (sh dir "remote" "set-head" "origin" "-d")
      (let [r (command/run-task ["cut"] dir nil)]
        (is (= 2 (:exit r)))
        (is (some #(str/includes? % "origin/HEAD") (:err r))))))
  (testing "a commit already cut changes nothing, whatever the branch"
    (let [{:keys [dir]} (repo-with-origin!)]
      (sh dir "checkout" "-q" "spec-1")
      (let [r (command/run-task ["cut"] dir nil)]
        (is (= 0 (:exit r)) (pr-str r))
        (is (str/includes? (:out r) ":already-cut \"spec-1\"")))))
  (testing "a check that cannot run stops the cut"
    (let [{:keys [dir head]} (repo-with-origin!)
          r (command/run-task ["cut"] dir nil)]
      (is (= 2 (:exit r)))
      (is (some #(str/includes? % "check") (:err r)))
      (is (= [] (cut/cuts-at dir head))))))
