(ns rdm-spec.measurement.oracle-test
  "Unit tests of the Oracle component: the command it runs, and the gate.
  Its one fixture is a temporary directory, removed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest no-oracle-is-no-oracle
  (is (= {:result {} :findings []} (oracle/gate {:config {}})))
  (let [out (oracle/oracle {:config {} :args []})]
    (is (str/includes? (get-in out [:relay :out]) "no oracle to run"))
    (is (= 0 (get-in out [:relay :exit])))
    (is (= "" (get-in out [:relay :err])))))

(deftest a-configured-oracle-is-a-command
  (is (= ["bb" "o.clj" "record" "--reason" "r"] (oracle/command-line {:oracle {:command ["bb" "o.clj"]}} ["record" "--reason" "r"])))
  (is (command/cannot-run? (try (oracle/command-line {:oracle {:command []}} []) nil (catch Exception e e)))))

(deftest the-gate-stops-on-a-failing-oracle
  (let [d (str (fs/create-temp-dir {:prefix "oracle-test-"}))]
    (try
      (spit (str (fs/file d "o.clj")) "(binding [*out* *err*] (println \"oracle-differs: x\")) (System/exit 1)")
      (let [req {:root d :config {:oracle {:command ["bb" "o.clj"]}} :env {}}
            out (oracle/gate req)]
        (is (= ["oracle-differs: x"] (map command/format-finding (:findings out))))
        (is (not (command/warning? (first (:findings out))))))
      (spit (str (fs/file d "o.clj")) "(println {:oracle {:differences 0}}) (System/exit 0)")
      (is (= [] (:findings (oracle/gate {:root d :config {:oracle {:command ["bb" "o.clj"]}} :env {}}))))
      (testing "a command that cannot be started"
        (is (command/cannot-run? (try (oracle/gate {:root d :config {:oracle {:command ["no-such-oracle"]}} :env {}})
                                      nil (catch Exception e e)))))
      (finally (fs/delete-tree d)))))
