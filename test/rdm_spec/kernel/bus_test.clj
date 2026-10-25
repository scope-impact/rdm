(ns rdm-spec.kernel.bus-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]))

(def catalogue
  {:SpecParsed {:published-by :spec/parser :carries [:revision :scenarios]}})

(deftest delivers-in-subscription-order-and-records-events
  (let [b (bus/make-bus)
        seen (atom [])]
    (bus/subscribe! b :SpecParsed :a/one #(swap! seen conj [:one %]))
    (bus/subscribe! b :Other :a/two #(swap! seen conj [:other %]))
    (bus/subscribe! b :SpecParsed :a/three #(swap! seen conj [:three %]))
    (bus/publish! b :spec/parser :SpecParsed {:x 1})
    (is (= [[:one {:x 1}] [:three {:x 1}]] @seen))
    (is (= [{:event :SpecParsed :publisher :spec/parser}] (bus/events b)))
    (is (= "event: SpecParsed published-by spec/parser" (bus/format-event (first (bus/events b)))))))

(deftest checks-against-the-catalogue
  (let [b (bus/make-bus {:catalogue catalogue :catalogue-file "dhf/spec/events.edn"})]
    (testing "a declared event from its publisher with every key passes"
      (bus/publish! b :spec/parser :SpecParsed {:revision nil :scenarios []})
      (is (= 1 (count (bus/events b)))))
    (testing "an undeclared event cannot run, naming it and the catalogue"
      (let [e (try (bus/publish! b :spec/check :SpecGated {}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (:rdm-spec.kernel.command/cannot-run (ex-data e)))
        (is (re-find #"SpecGated.*dhf/spec/events\.edn" (ex-message e)))))
    (testing "the wrong publisher and a missing key are refused"
      (is (thrown? clojure.lang.ExceptionInfo (bus/publish! b :spec/check :SpecParsed {:revision nil :scenarios []})))
      (is (thrown? clojure.lang.ExceptionInfo (bus/publish! b :spec/parser :SpecParsed {:revision nil}))))
    (is (= 1 (count (bus/events b))) "a refused event is not recorded")))

(deftest an-empty-or-absent-catalogue-checks-nothing
  (doseq [c [nil {}]]
    (let [b (bus/make-bus {:catalogue c})]
      (bus/publish! b :x/y :Anything {})
      (is (= [{:event :Anything :publisher :x/y}] (bus/events b))))))
