(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.project.init :as init]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "init-test-"})))

(deftest a-prefix-must-start-a-tag
  (doseq [p ["spec" "sauna" "a" "Sauna2" "my-project" "a1-b2-c3"]]
    (is (init/valid-prefix? p) p))
  (doseq [p ["9lives" "" "-spec" "spec-" "my--project" "my_project" "sau na" "spec@" nil]]
    (is (not (init/valid-prefix? p)) (pr-str p)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a prefix starts with a letter"
                        (init/require-prefix! "9lives"))))

(deftest the-system-id-is-a-keyword-starting-with-a-letter
  (is (= :sauna (init/system-id "sauna")))
  (is (= :my-sauna (init/system-id "My Sauna")))
  (is (= :system-9lives (init/system-id "9lives")))
  (is (= :system (init/system-id "___"))))

(deftest templates-are-filled-and-unknown-keys-kept
  (is (= "a sauna b {{other}}" (init/fill "a {{name}} b {{other}}" (init/vars-for "sauna" "spec")))))

(deftest the-gitignore-lines-are-those-the-spec-lists
  (is (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]
         (init/gitignore-lines))))

(deftest the-skeleton-is-written-filled-and-readable
  (let [dir (tmp)
        out (init/write-skeleton! dir (init/vars-for "sauna" "sauna"))]
    (is (= init/skeleton-paths (:wrote out)))
    (is (= [] (:kept out) (:skipped out) (:findings out)))
    (doseq [p init/skeleton-paths]
      (is (not (re-find #"\{\{" (slurp (str (fs/file dir p))))) (str p " holds an unfilled key")))
    (doseq [p init/skeleton-paths :when (re-find #"\.edn$" p)]
      (is (map? (edn/read-string (slurp (str (fs/file dir p))))) (str p " is not an EDN map")))
    (is (= "sauna" (:id-prefix (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))))
    (is (= {:id :sauna :name "sauna" :description "Describe what the system does"}
           (:system (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn")))))))
    (is (fs/executable? (fs/file dir ".githooks/pre-commit")))
    (testing "writing again keeps every file"
      (let [again (init/write-skeleton! dir (init/vars-for "other" "other"))]
        (is (= [] (:wrote again)))
        (is (= init/skeleton-paths (:kept again)))
        (is (= "sauna" (:id-prefix (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))))))))

(deftest a-link-is-kept-and-a-blocked-path-skipped
  (let [dir (tmp)]
    (fs/create-dirs (fs/file dir "dhf/harness"))
    (fs/create-sym-link (fs/file dir "dhf/harness/config.edn") "nowhere.edn")
    (spit (str (fs/file dir ".github")) "a file, not a directory\n")
    (let [out (init/write-skeleton! dir (init/vars-for "sauna" "spec"))]
      (is (some #{"dhf/harness/config.edn"} (:kept out)))
      (is (= [".github/workflows/cut.yml"] (:skipped out)))
      (is (= ["skeleton-skipped"] (map :rule (:findings out))))
      (is (= "nowhere.edn" (str (fs/read-link (fs/file dir "dhf/harness/config.edn"))))))))

(deftest init-writes-a-repository-and-refuses-what-exists
  (let [base (tmp)
        req (fn [args opts] {:task "init" :args args :opts opts :root base})
        out (init/init (req ["sauna"] {}))]
    (is (= init/skeleton-paths (get-in out [:result :wrote])))
    (is (= "spec" (get-in out [:result :id-prefix])))
    (is (= ".githooks" (get-in out [:result :hooks-path])))
    (is (= "cd sauna" (first (get-in out [:result :next-steps]))))
    (is (fs/directory? (fs/file base "sauna/.git")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sauna already exists"
                          (init/init (req ["sauna"] {}))))
    (testing "a refused prefix writes nothing"
      (is (thrown? clojure.lang.ExceptionInfo (init/init (req ["other"] {:id-prefix "9lives"}))))
      (is (not (fs/exists? (fs/file base "other")))))
    (testing "a path under a file is refused"
      (spit (str (fs/file base "plain")) "x")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a directory"
                            (init/init (req ["plain/sub"] {})))))))
