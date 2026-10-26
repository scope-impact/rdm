(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(defn- refused [f] (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest the-id-prefix-must-start-a-tag
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "sauna-2")))
  (doseq [bad ["9lives" "a--b" "a-" "-a" "a b" "a_b" ""]]
    (is (str/includes? (init/prefix-problem bad) "starts with a letter") bad))
  (is (str/includes? (init/prefix-problem "UN") "need tags")))

(deftest the-skeleton-is-rendered
  (let [cfg (edn/read-string (init/skeleton-text "harness/config.edn" {:name "My Sauna" :id-prefix "sauna"}))
        m (edn/read-string (init/skeleton-text "spec/model.edn" {:name "My Sauna" :id-prefix "sauna"}))]
    (is (= "sauna" (:id-prefix cfg)))
    (is (= "My Sauna" (get-in m [:system :name])))
    (is (keyword? (get-in m [:system :id]))))
  (is (= (count init/destinations) (count (distinct init/destinations))))
  (is (some #{".githooks/pre-commit"} init/destinations)))

(deftest init-writes-into-a-new-path-only
  (let [d (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (testing "a path that exists is refused"
        (fs/create-dirs (fs/file d "taken"))
        (is (str/includes? (refused #(init/init {:root d :args ["taken"] :opts {}})) "taken")))
      (testing "a bad prefix writes nothing"
        (is (refused #(init/init {:root d :args ["x"] :opts {:id-prefix "9lives"}})))
        (is (not (fs/exists? (fs/file d "x")))))
      (testing "the skeleton, a repository, the hook installed"
        (let [out (get-in (init/init {:root d :args ["new"] :opts {}}) [:result :init])]
          (is (= (set init/destinations) (set (:wrote out))))
          (is (seq (:next-steps out)))
          (is (fs/directory? (fs/file d "new" ".git")))
          (is (fs/executable? (fs/file d "new" ".githooks/pre-commit")))
          (is (= "spec" (:id-prefix (edn/read-string (slurp (str (fs/file d "new" "dhf/harness/config.edn")))))))))
      (finally (fs/delete-tree d)))))
