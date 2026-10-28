(ns rdm-spec.regeneration.cut-test
  "One test per rule Cut enforces (06-regenerate/cut.feature)."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]))

(defn- with-repo [f]
  (let [top (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})
        d (str (fs/path top "r"))]
    (try
      (fs/create-dirs d)
      (project/git! d "init" "-q")
      (spit (str (fs/path d "a.txt")) "a")
      (project/git! d "add" "-A")
      (project/git! d "commit" "-q" "-m" "one")
      (f d)
      (finally (fs/delete-tree top)))))

(deftest a-cut-is-a-pushed-tag-and-regeneration-reads-only-a-cut
  (is (= "spec-7" (cut/next-tag ["spec-6" "spec-2" "other"])))
  (is (= "spec-1" (cut/next-tag [])))
  (with-repo (fn [d]
               (project/git! d "tag" "spec-3")
               (is (cut/tag-commit d "spec-3"))
               (is (nil? (cut/tag-commit d "spec-4")))
               (is (= ["a.txt"] (map :path (cut/ls-tree d "spec-3")))))))

(deftest every-merge-is-cut-once
  (with-repo (fn [d]
               (project/git! d "tag" "spec-7")
               (is (= ["spec-7"] (cut/cuts-at d "HEAD"))))))
