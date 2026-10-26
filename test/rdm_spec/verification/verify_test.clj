(ns rdm-spec.verification.verify-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.verify :as verify]))

(def impl ["src" "test" "test.clj" "bb.edn"])

(defn- why [checks]
  (try (verify/checks-of {:verify checks :implementation impl}) nil
       (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest a-configuration-verify-cannot-trust-is-refused
  (is (nil? (why [{:kind :tests :command ["bb" "test.clj"]}])))
  (is (str/includes? (why nil) "declares no :verify"))
  (is (str/includes? (why [{:kind :runs :name "a" :command ["x"] :expect {:exit 0} :budget {:seconds 1}}])
                     "a project is verified by its tests first"))
  (is (str/includes? (why [{:kind :tests :command ["x"]} {:kind :renders}]) ":renders is not a kind of check"))
  (is (str/includes? (why [{:kind :tests}]) "the tests check names no command"))
  (is (str/includes? (why [{:kind :tests :command ["x"]}
                           {:kind :serves :start ["x"] :port 1 :budget {:seconds 1 :polls 1} :probe ["bb" "src/probe.clj"]}])
                     "the probe is in the implementation the builder writes"))
  (testing "a check's name is its kind's when it names none"
    (is (= ["tests" "answers"] (mapv :name (verify/checks-of {:implementation impl
                                                               :verify [{:kind :tests :command ["x"]}
                                                                        {:kind :runs :name "answers" :command ["y"]
                                                                         :expect {:exit 0} :budget {:seconds 1}}]}))))))

(deftest an-answer-is-an-http-response
  (is (verify/http-response? "HTTP/1.1 404 Not Found"))
  (is (verify/http-response? "HTTP/1.0 200 OK"))
  (is (not (verify/http-response? nil)))
  (is (not (verify/http-response? "hello"))))

(deftest seconds-are-held-to-the-hundredth
  (is (= 1.23 (verify/seconds 1234)))
  (is (= 0.0 (verify/seconds 0))))

(deftest a-command-is-run-within-its-budget
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [r (verify/run-command ["bb" "-e" "(println \"hi\") (System/exit 3)"] {:dir dir :budget-ms 30000})]
        (is (= 3 (:exit r)))
        (is (str/starts-with? (:out r) "hi")))
      (let [r (verify/run-command ["bb" "-e" "(Thread/sleep 20000)"] {:dir dir :budget-ms 500})]
        (is (:timed-out? r)))
      (is (command/cannot-run? (try (verify/run-command ["rdm-spec-no-such-command-x"] {:dir dir :budget-ms 500}) nil
                                    (catch Exception e e))))
      (finally (fs/delete-tree dir)))))

(deftest the-room-tree-is-the-git-tree-of-the-implementation
  (let [room (str (fs/create-temp-dir))]
    (try
      (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" (verify/room-tree room impl)) "an empty room is the empty tree")
      (spit (str (fs/file room "test.clj")) "x\n")
      (spit (str (fs/file room "notes.md")) "not implementation\n")
      (let [h (verify/room-tree room impl)]
        (is (= 40 (count h)))
        (spit (str (fs/file room "notes.md")) "changed\n")
        (is (= h (verify/room-tree room impl)) "only the implementation counts"))
      (finally (fs/delete-tree room)))))
