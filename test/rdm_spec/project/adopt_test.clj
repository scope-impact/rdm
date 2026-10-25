(ns rdm-spec.project.adopt-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.adopt :as adopt]
            [rdm-spec.project.init :as init]))

(deftest the-lines-to-add-are-those-missing
  (is (= ["b/" "c/"] (adopt/missing-lines "# x\na/\nb/\nc/\n" "a/\n# b/\n")))
  (is (= [] (adopt/missing-lines "a/\n" " a/ \n"))))

(deftest adopt-adds-and-never-overwrites
  (let [dir (str (fs/create-temp-dir {:prefix "adopt-test-"}))]
    (try
      (testing "not a repository: nothing is written"
        (let [e (try (adopt/adopt {:root dir}) nil (catch Exception e e))]
          (is (command/cannot-run? e))
          (is (re-find #"the hook needs a git repository" (ex-message e)))
          (is (empty? (fs/list-dir dir)))))
      (p/shell {:dir dir :out :string :err :string} "git" "init" "-q")
      (spit (str (fs/file dir ".gitignore")) "site/\n")
      (let [out (get-in (adopt/adopt {:root dir}) [:result :adopt])]
        (is (= [".gitignore"] (:kept out)))
        (is (= (remove #{".gitignore"} init/destinations) (:wrote out)))
        (is (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/"]
               (get-in out [:gitignore :lines-to-add])))
        (is (= "site/\n" (slurp (str (fs/file dir ".gitignore"))))))
      (let [out (get-in (adopt/adopt {:root dir}) [:result :adopt])]
        (is (empty? (:wrote out)))
        (is (= init/destinations (:kept out))))
      (finally (fs/delete-tree dir)))))
