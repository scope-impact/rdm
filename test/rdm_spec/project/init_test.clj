(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(deftest an-id-prefix-starts-a-tag
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "sauna-x2")))
  (is (re-find #"starts with a letter" (init/prefix-problem "9lives")))
  (is (re-find #"single hyphens" (init/prefix-problem "a--b")))
  (is (re-find #"need tags" (init/prefix-problem "UN"))))

(deftest the-skeleton-is-every-destination-once
  (is (= (count init/destinations) (count (distinct init/destinations))))
  (is (some #{".githooks/pre-commit"} init/destinations))
  (is (every? #(some? (init/skeleton-text (second %) {:name "x" :id-prefix "spec"})) init/skeleton)))

(deftest the-configuration-has-a-tests-check-without-a-command
  (let [c (edn/read-string (init/skeleton-text "harness/config.edn" {:name "sauna" :id-prefix "sauna"}))]
    (is (= "sauna" (:id-prefix c)))
    (is (= [{:kind :tests}] (:verify c)))))

(deftest the-spec-readme-carries-the-results-contract
  (let [t (init/skeleton-text "spec/README.md" {:name "x" :id-prefix "spec"})]
    (doseq [e ["historyId" "statusDetails" "parameters" "subSuite" "links" "attachments" "planned"]]
      (is (str/includes? t (str "`" e "`")) e))))

(deftest init-refuses-a-path-that-exists
  (let [d (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (is (command/cannot-run? (try (init/init {:root d :args ["."] :opts {}}) nil (catch Exception e e))))
      (let [out (init/init {:root d :args ["p"] :opts {}})]
        (is (= (set init/destinations) (set (get-in out [:result :init :wrote]))))
        (is (fs/executable? (fs/file d "p/.githooks/pre-commit")))
        (is (= :p (get-in (edn/read-string (slurp (str (fs/file d "p/dhf/spec/model.edn")))) [:system :id]))))
      (finally (fs/delete-tree d)))))
