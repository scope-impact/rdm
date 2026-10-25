(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.verification.status :as status]))

(deftest status-of-a-scenario
  (testing "planned whatever the results"
    (is (= :planned (status/status-of true [])))
    (is (= :planned (status/status-of true ["passed"])))
    (is (= :planned (status/status-of true ["failed"]))))
  (is (= :unverified (status/status-of false [])))
  (is (= :verified (status/status-of false ["passed" "passed"])))
  (testing "any status but passed fails"
    (doseq [s ["failed" "broken" "skipped" "unknown"]]
      (is (= :failed (status/status-of false ["passed" s])) s))))

(def scs (mapv status/scenario-entry
               [{:id "spec-0001" :name "a" :component-name "Alpha" :context :demo :level "e2e" :needs ["UN-001"]}
                {:id "spec-0002" :name "b" :wip? true}
                {:id "spec-0003" :name "c" :keyword "Scenario Outline"}
                {:id "spec-0004" :name "d"}]))

(deftest an-entry-names-component-and-context
  (let [e (first scs)]
    (is (= "Alpha" (:component e)))
    (is (= "demo" (:context e)))
    (is (false? (:planned? e))))
  (is (:planned? (second scs)))
  (is (:outline? (nth scs 2))))

(deftest the-report-lists-every-stamped-scenario
  (let [read {:dir "r"
              :results [{:file "1" :ids ["spec-0001"] :status "passed"}
                        {:file "2" :ids ["spec-0002"] :status "passed"}
                        {:file "3" :ids ["spec-0003"] :status "passed"}
                        {:file "4" :ids ["spec-0003"] :status "failed"}
                        {:file "5" :ids ["spec-9999"] :status "passed"}
                        {:file "6" :ids [] :status "failed"}]
              :unreadable [{:file "7" :reason "not JSON"}]}
        rep (status/derive-report scs read)]
    (is (= {"spec-0001" :verified "spec-0002" :planned "spec-0003" :failed "spec-0004" :unverified}
           (into {} (map (juxt :id :status) (:scenarios rep)))))
    (is (= {:scenarios 4 :verified 1 :failed 1 :unverified 1 :planned 1} (:counts rep)))
    (is (= ["spec-0003"] (get-in rep [:by-status :failed])))
    (is (= 2 (count (:results (nth (:scenarios rep) 2)))))
    (is (= [{:id "spec-9999" :file "5"}] (:unknown-results rep)))
    (testing "an unreadable file is an error, an unknown result a warning"
      (let [fs (status/findings rep)]
        (is (= ["unreadable-result" "unknown-result"] (map :rule fs)))
        (is (= [:error :warning] (map :severity fs)))))))

(deftest no-results-no-status
  (let [req {:state (atom {}) :results nil}]
    ((second (first (status/subscriptions req))) {:scenarios [] :needs {}})
    (is (= {:derived? false :results nil} (:report (status/derive! req))))))

(deftest deriving-publishes-verification-derived
  (let [dir (str (babashka.fs/create-temp-dir))
        _ (spit (str dir "/a-result.json") "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
        b (bus/make-bus)
        seen (atom nil)
        req {:root dir :state (atom {}) :bus b :results dir}]
    (doseq [[e h] (status/subscriptions req)] (bus/subscribe! b e :verification/status h))
    (bus/subscribe! b :VerificationDerived :publishing/site #(reset! seen %))
    (bus/publish! b :spec/parser :SpecParsed {:revision nil :needs {} :refusals []
                                              :scenarios [{:id "spec-0001" :name "a"} {:id nil :name "b"}]})
    (let [{:keys [report]} (status/derive! req)]
      (is (= ["b"] (map :name (:unstamped report))))
      (is (= {:revision :working-tree :statuses {"spec-0001" :verified} :unknown-results [] :unreadable-files []}
             @seen))
      (testing "derived once per run"
        (reset! seen nil)
        (status/derive! req)
        (is (nil? @seen))))))
