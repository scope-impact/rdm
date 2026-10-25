(ns rdm-spec.regeneration.cut-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.cut :as cut]))

(deftest the-next-cut-follows-the-highest
  (is (= "spec-1" (cut/next-cut [])))
  (is (= "spec-7" (cut/next-cut ["spec-6" "spec-5" "v1.0" "spec-x"])))
  (testing "by number, not by text"
    (is (= "spec-11" (cut/next-cut ["spec-9" "spec-10"])))
    (is (= "spec-10" (cut/latest-cut ["spec-10" "spec-9" "other"])))))

(deftest a-cut-tag-is-spec-N
  (is (cut/cut-tag? "spec-3"))
  (is (not (cut/cut-tag? "spec-3a")))
  (is (not (cut/cut-tag? "spec-")))
  (is (= 12 (cut/cut-number "spec-12"))))

(defn- sh [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string
                  :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t.invalid"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t.invalid"}}
         args))

(deftest an-export-holds-the-files-and-no-history
  (let [d (str (fs/create-temp-dir {:prefix "cut-test-"}))]
    (try
      (sh d "git" "init" "-q")
      (spit (str (fs/file d "a.txt")) "one\n")
      (fs/create-dirs (fs/file d "dir"))
      (spit (str (fs/file d "dir" "b.txt")) "two\n")
      (sh d "git" "add" "-A")
      (sh d "git" "commit" "-q" "--no-verify" "-m" "c")
      (sh d "git" "tag" "spec-1")
      (spit (str (fs/file d "a.txt")) "changed\n")
      (let [req {:root d :task "cut"}
            out (str (fs/file d "out"))]
        (is (= (:out (sh d "git" "rev-parse" "HEAD")) (str (cut/commit-of req "spec-1") "\n")))
        (is (= ["spec-1"] (cut/cut-tags-at req (cut/commit-of req "HEAD"))))
        (is (nil? (cut/commit-of req "spec-2")))
        (is (= "two\n" (cut/show req "spec-1" "dir/b.txt")))
        (is (nil? (cut/config-at req "spec-1")))
        (cut/export! req "spec-1" out)
        (is (= "one\n" (slurp (str (fs/file out "a.txt")))))
        (is (= "two\n" (slurp (str (fs/file out "dir" "b.txt")))))
        (is (not (fs/exists? (fs/file out ".git")))))
      (finally (fs/delete-tree d)))))
