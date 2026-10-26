(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(deftest the-skeleton
  (is (= (map first init/skeleton) init/destinations))
  (is (= 15 (count init/destinations)))
  (is (some #{"dhf/harness/config.edn"} init/destinations)))

(deftest the-id-prefix
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "my-sauna2")))
  (is (str/includes? (init/prefix-problem "9lives") "starts with a letter"))
  (is (init/prefix-problem "a--b"))
  (is (init/prefix-problem "a-"))
  (is (str/includes? (init/prefix-problem "UN") "taken by need tags")))

(deftest the-configuration-the-skeleton-holds
  (let [c (edn/read-string (init/skeleton-text "harness/config.edn" {:name "sauna" :id-prefix "sauna"}))]
    (is (= "sauna" (:id-prefix c)))
    (is (= {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src" "dhf/allure-results"]}
           (get-in c [:clean-room :deny])))
    (is (every? (set (get-in c [:clean-room :keep]))
                ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]))
    (is (= {:watch ["/" "~"] :tmp "/tmp"} (:agency c)))
    (is (not-any? #(contains? c %) [:implementation :code-layout :test-command]))))

(deftest the-model-names-the-system
  (let [m (edn/read-string (init/skeleton-text "spec/model.edn" {:name "My Sauna" :id-prefix "spec"}))]
    (is (= {:id :my-sauna :name "My Sauna"} (select-keys (:system m) [:id :name])))))

(deftest init-writes-a-new-project-and-refuses-an-existing-path
  (let [d (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (let [out (init/init {:root d :args ["sauna"] :opts {}})]
        (is (= init/destinations (get-in out [:result :init :wrote])))
        (is (seq (get-in out [:result :init :next-steps])))
        (is (fs/directory? (fs/file d "sauna" ".git")))
        (is (fs/executable? (fs/file d "sauna" ".githooks/pre-commit"))))
      (is (command/cannot-run? (try (init/init {:root d :args ["sauna"] :opts {}}) nil (catch Exception e e))))
      (is (command/cannot-run? (try (init/init {:root d :args ["other"] :opts {:id-prefix "UN"}}) nil (catch Exception e e))))
      (is (not (fs/exists? (fs/file d "other"))))
      (finally (fs/delete-tree d)))))
