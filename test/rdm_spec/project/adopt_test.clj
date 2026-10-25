(ns rdm-spec.project.adopt-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.adopt :as adopt]
            [rdm-spec.project.init :as init]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "adopt-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest the-gitignore-lines-to-add
  (is (= (init/gitignore-lines) (adopt/missing-gitignore-lines "build/\n")))
  (is (= (rest (init/gitignore-lines)) (adopt/missing-gitignore-lines (str (first (init/gitignore-lines)) "\n")))))

(deftest adopt-adds-keeps-and-refuses
  (with-tmp
    (fn [dir]
      (is (= 2 (:exit (command/execute ["adopt"] {:root dir}))))
      (is (empty? (fs/list-dir dir)))
      (p/shell {:dir dir :out :string :err :string} "git" "init" "-q")
      (spit (str (fs/file dir ".gitignore")) "build/\n")
      (let [out (command/execute ["adopt"] {:root dir})
            res (get-in out [:result :adopt])]
        (is (= 0 (:exit out)))
        (is (= [".gitignore"] (:kept res)))
        (is (= (init/gitignore-lines) (:gitignore-lines res)))
        (is (= "build/\n" (slurp (str (fs/file dir ".gitignore"))))))
      (let [res (get-in (command/execute ["adopt"] {:root dir}) [:result :adopt])]
        (is (empty? (:wrote res)))
        (is (= (set (map :path init/skeleton)) (set (:kept res))))))))
