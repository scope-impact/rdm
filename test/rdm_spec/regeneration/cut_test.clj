(ns rdm-spec.regeneration.cut-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.cut :as cut]))

(deftest cut-numbers
  (is (= 7 (cut/cut-number "spec-7")))
  (is (= 12 (cut/cut-number "spec-12")))
  (testing "only spec-N, N from 1, is a cut"
    (is (nil? (cut/cut-number "spec-0")))
    (is (nil? (cut/cut-number "spec-07")))
    (is (nil? (cut/cut-number "spec-7a")))
    (is (nil? (cut/cut-number "v7")))
    (is (nil? (cut/cut-number "pre-spec")))))

(deftest the-next-cut
  (is (= "spec-1" (cut/next-cut [])))
  (is (= "spec-1" (cut/next-cut ["v1.0" "pre-spec"])))
  (is (= "spec-7" (cut/next-cut ["spec-5" "spec-6" "v6.0"])))
  (testing "numbers compare as numbers"
    (is (= "spec-11" (cut/next-cut ["spec-9" "spec-10" "spec-2"])))))

(deftest ordering
  (is (= ["spec-2" "spec-10"] (cut/by-number ["spec-10" "x" "spec-2"]))))

(deftest outside-a-repository
  (let [d (str (fs/create-temp-dir))]
    (is (not (cut/repository? d)))
    (is (nil? (cut/commit-of d "HEAD")))))
