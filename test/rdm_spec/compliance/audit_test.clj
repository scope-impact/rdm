(ns rdm-spec.compliance.audit-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.audit :as audit]))

(deftest the-clauses-page
  (let [page (audit/clauses-page ["dhf/clauses/std.txt"]
                                 [{:key "STD:4.1" :description "Quality system" :declared "dhf/clauses/std.txt"
                                   :named-at [{:file "dhf/documents/q.md" :line 7}]}
                                  {:key "STD:5.1" :description "Plan | scope" :declared "dhf/clauses/std.txt"
                                   :named-at [{:file "dhf/documents/plan.md" :line 8}
                                              {:file "dhf/documents/risk/register.md" :line 9}]}])
        lines (str/split-lines page)]
    (is (= ["## dhf/clauses/std.txt"] (filter #(str/starts-with? % "## ") lines)))
    (is (some #{"| STD:4.1 | Quality system | dhf/documents/q.md:7 |"} lines))
    (is (some #{"| STD:5.1 | Plan \\| scope | dhf/documents/plan.md:8, dhf/documents/risk/register.md:9 |"} lines))
    (is (< (.indexOf page "STD:4.1") (.indexOf page "STD:5.1")))))

(deftest the-traceability-page
  (let [spec {:needs [{:id "UN-001" :text "A first need."}]
              :scenarios [{:id "spec-0002" :title "Second" :needs ["UN-001"] :component :demo/alpha :component-name "Alpha"}
                          {:id "spec-0001" :title "First" :needs ["UN-001"] :component :demo/alpha :component-name "Alpha"}
                          {:id nil :title "Unstamped" :needs ["UN-001"]}]}
        page (audit/traceability-page spec {"spec-0001" :verified "spec-0002" :failed})]
    (is (str/includes? page "## UN-001\n\nA first need."))
    (is (str/includes? page "| @spec-0001 | First | Alpha (demo/alpha) | verified |\n| @spec-0002 | Second | Alpha (demo/alpha) | failed |"))
    (is (not (str/includes? page "Unstamped")))))

(deftest the-manifest-and-a-clean-record
  (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-audit-test-"}))]
    (try
      (spit (str (fs/file d "b.txt")) "b")
      (fs/create-dirs (fs/file d "a"))
      (spit (str (fs/file d "a" "z.txt")) "z")
      (spit (str (fs/file d "manifest.edn")) "old")
      (let [m (audit/manifest "abc" true d)]
        (is (= [:commit :clean :files] (keys m)))
        (is (= ["a/z.txt" "b.txt"] (map :path (:files m))))
        (is (= "3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d" (:sha256 (second (:files m))))))
      (let [git (fn [& args] (apply p/shell {:dir d :out :string :err :string} "git" args))
            req {:root d :env (into {} (System/getenv))}]
        (git "init" "-q")
        (git "add" "b.txt")
        (git "-c" "user.name=t" "-c" "user.email=t@rdm-spec.invalid" "commit" "-q" "-m" "x")
        (is (audit/head-commit req))
        (is (true? (audit/clean? req)) "untracked files never count")
        (spit (str (fs/file d "b.txt")) "changed")
        (is (false? (audit/clean? req))))
      (finally (fs/delete-tree d)))))
