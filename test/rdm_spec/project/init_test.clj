(ns rdm-spec.project.init-test
  "Unit tests of Init's pure parts and of the skeleton it writes. The one
  fixture is a temporary directory, removed."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.project.init :as init]))

(deftest a-prefix-starts-with-a-letter-and-is-not-a-need-tag
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "sauna-2")))
  (is (str/includes? (init/prefix-problem "9lives") "starts with a letter"))
  (is (str/includes? (init/prefix-problem "a--b") "single hyphens"))
  (is (str/includes? (init/prefix-problem "a-") "single hyphens"))
  (is (str/includes? (init/prefix-problem "UN") "need tags")))

(deftest the-system-is-named-after-the-path
  (is (= :sauna-booking (init/system-id "Sauna Booking")))
  (is (= :project (init/system-id "!!"))))

(deftest every-skeleton-file-is-a-resource
  (is (= 15 (count init/destinations)))
  (doseq [[_ resource] init/skeleton]
    (is (string? (init/skeleton-text resource {:name "sauna" :id-prefix "spec"})) resource)))

(deftest the-readme-states-the-default-mapping
  (let [text (init/skeleton-text "spec/README.md" {:name "sauna" :id-prefix "spec"})]
    (is (some #{runner/default-mapping}
              (for [[_ b] (re-seq #"(?s)```edn\n(.*?)```" text)] (edn/read-string b))))))

(deftest init-writes-a-repository-with-the-skeleton
  (let [dir (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try
      (let [out (get-in (init/init {:root dir :args ["sauna"] :opts {}}) [:result :init])]
        (is (= init/destinations (:wrote out)))
        (is (fs/directory? (fs/file dir "sauna/.git")))
        (is (= {:issued 0} (edn/read-string (slurp (str (fs/file dir "sauna/dhf/spec/ids.edn"))))))
        (is (= "spec" (:id-prefix (edn/read-string (slurp (str (fs/file dir "sauna/dhf/harness/config.edn"))))))))
      (testing "an existing path is refused"
        (let [e (try (init/init {:root dir :args ["sauna"] :opts {}}) nil (catch Exception e e))]
          (is (command/cannot-run? e))))
      (finally (fs/delete-tree dir)))))
