(ns rdm-spec.spec.stamper-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(deftest ids-are-the-next-ones-never-one-taken
  (is (= [42 43] (stamper/next-ids 41 [] 2)))
  (is (= [42 44] (stamper/next-ids 41 [43] 2)))
  (is (= "spec-0042" (stamper/format-id "spec" 42)))
  (is (= "spec-12345" (stamper/format-id "spec" 12345))))

(deftest the-id-goes-first-on-the-tag-line-or-on-a-new-one
  (is (= "Feature: F\n  @spec-0001 @e2e\n  Scenario: S\n"
         (stamper/stamp-text "Feature: F\n  @e2e\n  Scenario: S\n" {3 "spec-0001"})))
  (is (= "Feature: F\n  # c\n  @spec-0001 @e2e\n  # d\n  Scenario: S\n"
         (stamper/stamp-text "Feature: F\n  # c\n  @e2e\n  # d\n  Scenario: S\n" {5 "spec-0001"})))
  (is (= "Feature: F\n\n  @spec-0001\n  Scenario: A\n\n  @spec-0002\n  Scenario: B\n"
         (stamper/stamp-text "Feature: F\n\n  Scenario: A\n\n  Scenario: B\n" {3 "spec-0001" 5 "spec-0002"}))))

(deftest stamping-a-project
  (let [dir (str (fs/create-temp-dir {:prefix "stamper-test-"}))
        f (fs/file dir "dhf/spec/a/b.feature")
        ledger (fs/file dir "dhf/spec/ids.edn")]
    (try
      (fs/create-dirs (fs/parent f))
      (spit (str f) "@UN-001\nFeature: B\n\n  @spec-0001 @e2e\n  Scenario: A\n    Given x\n\n  @e2e\n  Scenario: C\n    Given y\n")
      (spit (str ledger) ";; the ledger\n{:issued 5}\n")
      (let [out (stamper/stamp {:root dir :config {}})]
        (is (= [{:id "spec-0006" :file "dhf/spec/a/b.feature" :line 9 :title "C"}] (get-in out [:result :stamp :stamped])))
        (is (= ";; the ledger\n{:issued 6}\n" (slurp (str ledger))))
        (is (re-find #"@spec-0006 @e2e\n  Scenario: C" (slurp (str f)))))
      (let [before (slurp (str f))
            out (stamper/stamp {:root dir :config {}})]
        (is (empty? (get-in out [:result :stamp :stamped])))
        (is (= before (slurp (str f))))
        (is (= ";; the ledger\n{:issued 6}\n" (slurp (str ledger)))))
      (finally (fs/delete-tree dir)))))
