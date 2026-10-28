(ns rdm-spec.project.adopt-test
  "One test per rule Adopt enforces (01-start-a-project/adopt.feature)."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [rdm-spec.project.adopt :as adopt]))

(deftest adopt-adds-and-never-overwrites
  (let [d (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})]
    (try
      (fs/create-sym-link (fs/path d "link") "nowhere")
      (is (adopt/exists? (fs/path d "link")) "a dangling link counts as existing")
      (is (not (adopt/exists? (fs/path d "absent"))))
      (finally (fs/delete-tree d)))))

(deftest adopt-never-reads-the-existing-code-into-the-spec
  (is (= #{:system} (set (keys (clojure.edn/read-string (second (first (rdm-spec.project.init/skeleton "x" "spec")))))))))
