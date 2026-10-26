(ns rdm-spec.verification.verify-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.verify :as verify]))

(def impl ["src" "test" "test.clj"])

(deftest a-configuration-verify-cannot-trust-is-refused
  (let [why #(verify/config-problem {:verify % :implementation impl})]
    (is (nil? (why [{:kind :tests :command ["bb" "test.clj"]}])))
    (is (re-find #"the configuration declares no :verify" (verify/config-problem {:implementation impl})))
    (is (re-find #"a project is verified by its tests first" (why [{:kind :runs :name "a" :command ["x"] :budget {:seconds 1}}])))
    (is (re-find #"a project is verified by its tests first"
                 (why [{:kind :runs :name "a" :command ["x"] :budget {:seconds 1}} {:kind :tests :command ["t"]}])))
    (is (re-find #":renders is not a kind of check" (why [{:kind :tests :command ["t"]} {:kind :renders}])))
    (is (re-find #"the tests check names no command" (why [{:kind :tests}])))
    (is (re-find #"the tests check names no command" (why [{:kind :tests :command []}])))
    (is (re-find #"the probe is in the implementation the builder writes"
                 (why [{:kind :tests :command ["t"]}
                       {:kind :serves :name "app" :start ["x" "{port}"] :port 1 :budget {:seconds 1 :polls 2}
                        :probe ["bb" "src/probe.clj" "{url}"]}])))
    (is (nil? (why [{:kind :tests :command ["t"]}
                    {:kind :serves :name "app" :start ["x" "{port}"] :port 1 :budget {:seconds 1 :polls 2}
                     :probe ["bb" "dhf/harness/probe.clj" "{url}"]}])))
    (is (re-find #"budget" (why [{:kind :tests :command ["t"]} {:kind :runs :name "a" :command ["x"]}])))
    (is (re-find #"twice" (why [{:kind :tests :command ["t"]} {:kind :tests :command ["t"]}])))))

(deftest a-check-is-named-by-its-kind-when-it-names-none
  (is (= ["tests" "answers" "runs"]
         (map verify/check-name [{:kind :tests} {:kind :runs :name "answers"} {:kind :runs}]))))

(deftest the-tests-check-reads-every-scenario
  (let [scs [{:id "spec-0001"} {:id "spec-0002"} {:id "spec-0003" :wip? true}]]
    (is (= {:named 2 :missing [] :not-passed []}
           (verify/coverage scs [{:spec "spec-0001" :status "passed"} {:spec "spec-0002" :status "passed"}])))
    (is (= {:named 1 :missing ["spec-0002"] :not-passed [["spec-0001" ["failed"]]]}
           (verify/coverage scs [{:spec "spec-0001" :status "passed"} {:spec "spec-0001" :status "failed"}])))))

(deftest seconds-and-margins-are-rounded-to-the-hundredth
  (is (= 1.23 (verify/hundredths 1.2345)))
  (is (= 28.77 (verify/hundredths (- 30 1.2345)))))

(deftest a-room-tree-is-the-tree-of-its-implementation
  (let [d (str (fs/create-temp-dir {:prefix "verify-test-"}))]
    (try
      (spit (str (fs/file d "test.clj")) "x\n")
      (fs/create-dirs (fs/file d "other"))
      (spit (str (fs/file d "other/a.txt")) "not implementation\n")
      (let [h (verify/room-tree d impl)]
        (is (re-matches #"[0-9a-f]{40}" h))
        (spit (str (fs/file d "other/a.txt")) "changed\n")
        (is (= h (verify/room-tree d impl)))
        (spit (str (fs/file d "test.clj")) "y\n")
        (is (not= h (verify/room-tree d impl))))
      (finally (fs/delete-tree d)))))
