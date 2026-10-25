(ns rdm-spec.project.hooks-test
  (:require [babashka.fs :as fs]
            [babashka.process]
            [clojure.test :refer [deftest is]]
            [rdm-spec.project.hooks :as hooks]))

(deftest the-hook-is-installed-once-and-another-is-kept
  (let [dir (str (fs/create-temp-dir {:prefix "hooks-test-"}))]
    (try
      (is (nil? (hooks/toplevel dir)))
      (is (hooks/init-repository! dir))
      (is (= (str (fs/canonicalize dir)) (str (fs/canonicalize (hooks/toplevel dir)))))
      (is (= {:installed? true :hooks-path ".githooks"} (hooks/install! dir)))
      (is (= {:installed? true :hooks-path ".githooks"} (hooks/install! dir)))
      (finally (fs/delete-tree dir))))
  (let [dir (str (fs/create-temp-dir {:prefix "hooks-test-"}))]
    (try
      (hooks/init-repository! dir)
      (babashka.process/shell {:dir dir} "git" "config" "core.hooksPath" "hooks")
      (is (= {:installed? false :hooks-path "hooks"} (hooks/install! dir)))
      (finally (fs/delete-tree dir)))))
