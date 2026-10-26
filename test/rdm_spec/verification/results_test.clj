(ns rdm-spec.verification.results-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.results :as results]))

(deftest reads-one-result
  (is (= {:spec "spec-0001" :status "passed"}
         (select-keys (results/parse-result "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}") [:spec :status])))
  (is (:unreadable (results/parse-result "{\"status\": ")))
  (is (re-find #"passed, failed, broken or skipped" (:unreadable (results/parse-result "{\"status\":\"green\"}"))))
  (is (nil? (:spec (results/parse-result "{\"status\":\"passed\"}")))))
