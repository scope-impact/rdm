(ns rdm-spec.regeneration.cut-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.cut :as cut]))

(deftest cut-numbers
  (is (= 7 (cut/cut-number "spec-7")))
  (is (nil? (cut/cut-number "spec-7a")))
  (is (nil? (cut/cut-number "v1.0")))
  (testing "cuts are ordered by number, not by name"
    (is (= ["spec-2" "spec-9" "spec-10"] (cut/cut-tags ["spec-10" "v1" "spec-2" "spec-9" "spec-2"])))))

(deftest next-cut
  (is (= "spec-1" (cut/next-cut [])))
  (is (= "spec-1" (cut/next-cut ["v1.0" "release"])))
  (is (= "spec-7" (cut/next-cut ["spec-6" "spec-5"])))
  (is (= "spec-11" (cut/next-cut ["spec-9" "spec-10" "spec-2"]))))

(deftest parse-ls-remote
  (is (= {"spec-1" "aaa" "spec-2" "ccc"}
         (cut/parse-ls-remote (str "aaa\trefs/tags/spec-1\n"
                                   "bbb\trefs/tags/spec-2\n"
                                   "ccc\trefs/tags/spec-2^{}\n"))))
  (is (= {} (cut/parse-ls-remote ""))))

(deftest check-findings
  (let [fs (cut/check-findings ["dhf/spec/a.feature:3: missing-level: spec-0001 carries no level"
                                "dhf/spec/needs.edn:13: warning: citation-unchecked: not checked"
                                "warning: something"
                                "event: SpecParsed published-by spec/parser"])]
    (is (= ["dhf/spec/a.feature:3: missing-level: spec-0001 carries no level"] (map :message fs)))
    (is (every? #(= :error (:severity %)) fs))))

(deftest checkout-writes-a-commit-outside-the-repository
  (let [d (str (fs/create-temp-dir))
        sh #(apply cut/git! d "-c" "commit.gpgsign=false" %&)
        target (str (fs/create-temp-dir))]
    (sh "init" "-q")
    (spit (str d "/a.txt") "a")
    (fs/create-dirs (str d "/dir"))
    (spit (str d "/dir/b.txt") "b")
    (sh "add" "-A")
    (sh "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "one")
    (spit (str d "/a.txt") "changed in the working tree")
    (cut/checkout! d "HEAD" target ["dir/b.txt"])
    (is (= ["dir/b.txt"] (map #(str (fs/relativize target %)) (filter fs/regular-file? (fs/glob target "**")))))
    (is (= "b" (slurp (str target "/dir/b.txt"))))
    (testing "the repository's own working tree and index are untouched"
      (is (= "changed in the working tree" (slurp (str d "/a.txt"))))
      (is (= "M a.txt" (sh "status" "--porcelain"))))))
