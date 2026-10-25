(ns rdm-spec.spec.stamper-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(deftest stamp-lines-puts-the-id-first-on-the-tag-line-or-above
  (is (= ["  @spec-0005 @e2e" "  Scenario: A" "" "  @spec-0006" "  Scenario: B"]
         (stamper/stamp-lines ["  @e2e" "  Scenario: A" "" "  Scenario: B"]
                              {2 "@spec-0005" 4 "@spec-0006"}))))

(deftest ids-are-four-digits-at-least
  (is (= "spec-0042" (stamper/format-id "spec" 42)))
  (is (= "loyly-12345" (stamper/format-id "loyly" 12345))))

(defn- req [dir]
  {:root dir :config {:spec-dir "dhf/spec" :id-prefix "spec" :ledger "dhf/spec/ids.edn"} :state (atom {})})

(deftest stamps-from-the-ledger-and-records-it
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file dir "dhf/spec/a"))
    (spit (str (fs/file dir "dhf/spec/ids.edn")) ";; c\n{:issued 41}\n")
    (spit (str (fs/file dir "dhf/spec/a/b.feature"))
          "@UN-001\nFeature: B\n\n  @e2e\n  Scenario: X\n    Given a\n\n  Scenario: Y\n    Given b\n")
    (let [r (stamper/stamp (req dir))]
      (is (= ["spec-0042" "spec-0043"] (map :id (:stamped (:result r)))))
      (is (= 43 (:issued (edn/read-string (slurp (str (fs/file dir "dhf/spec/ids.edn")))))))
      (is (= ";; c\n{:issued 43}\n" (slurp (str (fs/file dir "dhf/spec/ids.edn")))))
      (is (= "@UN-001\nFeature: B\n\n  @spec-0042 @e2e\n  Scenario: X\n    Given a\n\n  @spec-0043\n  Scenario: Y\n    Given b\n"
             (slurp (str (fs/file dir "dhf/spec/a/b.feature"))))))
    (let [r (stamper/stamp (req dir))]
      (is (= [] (:stamped (:result r)))))))

(deftest a-higher-id-in-the-spec-is-never-issued-again
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file dir "dhf/spec/a"))
    (spit (str (fs/file dir "dhf/spec/ids.edn")) "{:issued 3}")
    (spit (str (fs/file dir "dhf/spec/a/b.feature"))
          "@UN-001\nFeature: B\n\n  @spec-0009 @e2e\n  Scenario: X\n    Given a\n\n  Scenario: Y\n    Given b\n")
    (is (= ["spec-0010"] (map :id (:stamped (:result (stamper/stamp (req dir)))))))))

(deftest a-refused-spec-is-not-stamped
  (let [dir (str (fs/create-temp-dir))
        text "@UN-001\nFeature: B\n\n  Scenario Template: X\n    Given a\n\n  Scenario: Y\n    Given b\n"]
    (fs/create-dirs (fs/file dir "dhf/spec/a"))
    (spit (str (fs/file dir "dhf/spec/a/b.feature")) text)
    (let [r (stamper/stamp (req dir))]
      (is (seq (:findings r)))
      (is (= text (slurp (str (fs/file dir "dhf/spec/a/b.feature")))))
      (is (not (fs/exists? (fs/file dir "dhf/spec/ids.edn")))))))
