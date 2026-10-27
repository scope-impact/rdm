(ns rdm-spec.verification.release-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.release :as release]))

(deftest a-release-needs-every-scenario-verified
  (is (= [["release-refused" "spec-0002 is planned (tagged @wip): a release needs every scenario verified"]
          ["release-refused" "spec-0003 is failed: a release needs every scenario verified"]]
         (map (juxt :rule :message)
              (release/refusals [{:id "spec-0001" :status :verified} {:id "spec-0002" :status :planned}
                                 {:id "spec-0003" :status :failed}])))))
