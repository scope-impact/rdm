(ns rdm-spec.kernel.plugins-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.plugins :as plugins]))

(defn- why [config]
  (try (plugins/validate! "." config) nil
       (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e)))))

(deftest a-declaration-is-read-with-its-defaults
  (is (= [{:name "a" :subscribes [:SpecParsed] :command ["x"] :seconds 60 :env {}}]
         (plugins/declared {:plugins [{:name "a" :subscribes [:SpecParsed] :command ["x"]}]}))))

(deftest a-declaration-rdm-spec-cannot-trust-is-refused
  (is (nil? (why {:plugins [{:name "a" :answers :Gate :command ["x"]}]})))
  (is (re-find #"subscribes to events or answers a request" (why {:plugins [{:name "a" :command ["x"]}]})))
  (is (re-find #"not both" (why {:plugins [{:name "a" :answers :Gate :subscribes [:SpecParsed] :command ["x"]}]})))
  (is (re-find #"NoSuchEvent.*CycleVerified" (why {:plugins [{:name "a" :subscribes [:NoSuchEvent] :command ["x"]}]})))
  (is (re-find #":Gate and :Check" (why {:plugins [{:name "a" :answers :Review :command ["x"]}]})))
  (is (re-find #"declared once" (why {:plugins [{:name "a" :answers :Gate :command ["x"]} {:name "a" :answers :Check :command ["x"]}]})))
  (is (re-find #"never writes" (why {:implementation ["src"] :plugins [{:name "a" :answers :Gate :command ["bb" "src/a.clj"]}]})))
  (is (re-find #":env" (why {:plugins [{:name "a" :answers :Gate :command ["x"] :env {"A" 1}}]})))
  (is (re-find #":owner" (why {:plugins [{:name "a" :answers :Gate :command ["x"] :owner "me"}]}))))

(deftest what-a-plugin-says-becomes-a-report
  (is (= {:outcome :passed :findings [] :warnings [{:raw "f:1: warning: r: w"}]}
         (select-keys (plugins/report-of {:name "a"} {:exit 0 :out "" :err "f:1: warning: r: w\n"}) [:outcome :findings :warnings])))
  (is (= [{:raw "f:1: r: x"}] (:findings (plugins/report-of {:name "a"} {:exit 1 :out "" :err "f:1: r: x\n"}))))
  (is (= :refused (:outcome (plugins/report-of {:name "a" :command ["x"]} {:exit 2 :out "" :err "no"}))))
  (is (re-find #"timed out after 2 seconds.*started"
               (:why (plugins/report-of {:name "a" :seconds 2 :command ["x"]} {:timed-out true :out "started\n" :err ""})))))
