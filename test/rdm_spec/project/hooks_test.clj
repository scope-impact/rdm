(ns rdm-spec.project.hooks-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [rdm-spec.project.hooks :as hooks]))

(deftest the-hook-runs-the-check
  (is (hooks/runs-check? (hooks/hook-content)))
  (is (hooks/runs-check? "exec rdm-spec check"))
  (is (not (hooks/runs-check? "npm test"))))

(deftest a-directory-outside-git-is-refused
  (let [dir (str (fs/create-temp-dir))]
    (is (nil? (hooks/repository-root dir)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hook needs a git repository"
                          (hooks/require-repository! dir)))))

(deftest activate-sets-the-hooks-path-once-and-keeps-another
  (let [dir (str (fs/create-temp-dir))]
    (hooks/git dir "init" "--quiet")
    (is (= (str (fs/canonicalize dir)) (hooks/repository-root dir)))
    (is (= {:hooks-path ".githooks" :set? true :findings []} (hooks/activate! dir)))
    (is (= {:hooks-path ".githooks" :set? false :findings []} (hooks/activate! dir)))
    (hooks/git dir "config" "core.hooksPath" ".husky")
    (let [r (hooks/activate! dir)]
      (is (= ".husky" (:hooks-path (hooks/activate! dir))))
      (is (= "hooks-path-kept" (:rule (first (:findings r))))))))
