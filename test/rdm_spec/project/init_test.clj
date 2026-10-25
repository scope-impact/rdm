(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(deftest a-prefix-starts-a-tag
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "my-proj2")))
  (doseq [p ["9lives" "a--b" "a-" "-a" "a_b" ""]]
    (is (re-find #"starts with a letter" (init/prefix-problem p)) p))
  (is (re-find #"taken by need tags" (init/prefix-problem "UN"))))

(deftest every-skeleton-file-is-there-and-filled
  (doseq [[_ resource] init/skeleton]
    (is (string? (init/skeleton-text resource {:name "sauna" :id-prefix "x"})) resource))
  (let [m (edn/read-string (init/skeleton-text "spec/model.edn" {:name "My Sauna" :id-prefix "x"}))]
    (is (= {:id :my-sauna :name "My Sauna"} (select-keys (:system m) [:id :name]))))
  (is (= "x" (:id-prefix (edn/read-string (init/skeleton-text "harness/config.edn" {:name "s" :id-prefix "x"}))))))

(deftest init-writes-the-skeleton-into-a-new-path
  (let [parent (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (let [out (init/init {:root parent :args ["sauna"] :opts {}})
            dir (fs/file parent "sauna")]
        (is (= init/destinations (get-in out [:result :init :wrote])))
        (doseq [d init/destinations] (is (fs/regular-file? (fs/file dir d)) d))
        (is (fs/directory? (fs/file dir ".git")))
        (is (fs/executable? (fs/file dir ".githooks/pre-commit"))))
      (testing "a path that exists cannot be initialised"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                              (init/init {:root parent :args ["sauna"] :opts {}}))))
      (testing "a bad prefix writes nothing"
        (let [e (try (init/init {:root parent :args ["other"] :opts {:id-prefix "UN"}}) nil (catch Exception e e))]
          (is (command/cannot-run? e))
          (is (not (fs/exists? (fs/file parent "other"))))))
      (finally (fs/delete-tree parent)))))
