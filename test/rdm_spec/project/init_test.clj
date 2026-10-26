(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(defn- cannot-run [f]
  (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest a-prefix-starts-a-tag
  (doseq [p ["spec" "sauna" "a1" "my-app" "x-1-y"]] (is (nil? (init/prefix-problem p)) p))
  (doseq [p ["9lives" "-a" "a--b" "a-" "a_b" ""]] (is (re-find #"starts with a letter" (init/prefix-problem p)) p))
  (is (re-find #"taken by need tags" (init/prefix-problem "UN"))))

(deftest the-skeleton-is-the-specs-table
  (is (= [".githooks/pre-commit" ".github/workflows/cut.yml" ".gitignore"]
         (filter #(str/starts-with? % ".") init/destinations)))
  (is (= 15 (count init/destinations)))
  (let [t (init/skeleton-text "spec/model.edn" {:name "My sauna" :id-prefix "spec"})
        m (edn/read-string t)]
    (is (= #{:system} (set (keys m))))
    (is (= "My sauna" (get-in m [:system :name])))
    (is (keyword? (get-in m [:system :id]))))
  (is (= "sauna" (:id-prefix (edn/read-string (init/skeleton-text "harness/config.edn" {:name "x" :id-prefix "sauna"}))))))

(deftest init-writes-a-repository-with-no-commit
  (let [d (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (let [out (get-in (init/init {:root d :args ["sauna"] :opts {}}) [:result :init])
            s (fs/file d "sauna")]
        (is (= init/destinations (:wrote out)))
        (is (seq (:next-steps out)))
        (is (fs/executable? (fs/file s ".githooks/pre-commit")))
        (is (= ".githooks" (str/trim (:out (p/shell {:dir (str s) :out :string} "git" "config" "--get" "core.hooksPath")))))
        (is (not (zero? (:exit (p/shell {:dir (str s) :out :string :err :string :continue true}
                                        "git" "rev-parse" "--verify" "--quiet" "HEAD"))))))
      (testing "a path that exists cannot run"
        (is (re-find #"sauna" (cannot-run #(init/init {:root d :args ["sauna"] :opts {}})))))
      (testing "a bad prefix writes nothing"
        (is (cannot-run #(init/init {:root d :args ["other"] :opts {:id-prefix "UN"}})))
        (is (not (fs/exists? (fs/file d "other")))))
      (finally (fs/delete-tree d)))))
