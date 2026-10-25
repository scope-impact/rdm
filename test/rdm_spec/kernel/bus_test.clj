(ns rdm-spec.kernel.bus-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]))

(def catalogue
  {:A {:published-by :ctx/a :carries [:x :y]}
   :B {:published-by :ctx/b :carries []}})

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest delivers-in-subscription-order
  (let [b (bus/make-bus {:catalogue catalogue})
        seen (atom [])]
    (bus/subscribe! b :A :s/one #(swap! seen conj [:one %]))
    (bus/subscribe! b :A :s/two #(swap! seen conj [:two %]))
    (bus/subscribe! b :B :s/three #(swap! seen conj [:three %]))
    (bus/publish! b :ctx/a :A {:x 1 :y nil})
    (is (= [[:one {:x 1 :y nil}] [:two {:x 1 :y nil}]] @seen))))

(deftest records-events-in-publication-order
  (let [b (bus/make-bus {:catalogue catalogue})]
    (testing "an event published from a handler comes after the one that caused it"
      (bus/subscribe! b :A :ctx/b (fn [_] (bus/publish! b :ctx/b :B {})))
      (bus/publish! b :ctx/a :A {:x 1 :y 2})
      (is (= [:A :B] (map :event (bus/events b))))
      (is (= [:ctx/a :ctx/b] (map :publisher (bus/events b))))
      (is (= {:x 1 :y 2} (:payload (first (bus/events b))))))
    (is (= ["event: A published-by ctx/a" "event: B published-by ctx/b"]
           (map bus/format-event (bus/events b))))))

(deftest no-catalogue-checks-nothing
  (doseq [b [(bus/make-bus) (bus/make-bus {:catalogue nil}) (bus/make-bus {:catalogue {}})]]
    (bus/publish! b :anyone :Anything {})
    (is (= [:Anything] (map :event (bus/events b))))))

(deftest refuses-what-the-catalogue-does-not-allow
  (let [b (bus/make-bus {:catalogue catalogue :catalogue-file "dhf/spec/events.edn"})
        seen (atom 0)]
    (bus/subscribe! b :C :s/s (fn [_] (swap! seen inc)))
    (testing "an undeclared event"
      (let [e (refusal #(bus/publish! b :ctx/a :C {}))]
        (is (:rdm-spec.kernel.command/cannot-run (ex-data e)))
        (is (re-find #"\bC\b" (ex-message e)))
        (is (re-find #"dhf/spec/events\.edn" (ex-message e)))))
    (testing "the wrong publisher"
      (let [e (refusal #(bus/publish! b :ctx/b :A {:x 1 :y 2}))]
        (is (:rdm-spec.kernel.command/cannot-run (ex-data e)))
        (is (re-find #"ctx/a" (ex-message e)))
        (is (re-find #"ctx/b" (ex-message e)))))
    (testing "a missing key"
      (let [e (refusal #(bus/publish! b :ctx/a :A {:x 1}))]
        (is (:rdm-spec.kernel.command/cannot-run (ex-data e)))
        (is (re-find #":y" (ex-message e)))))
    (testing "extra keys are carried"
      (is (nil? (refusal #(bus/publish! b :ctx/a :A {:x 1 :y 2 :z 3})))))
    (is (zero? @seen))
    (is (= [:A] (map :event (bus/events b))) "a refused event is neither delivered nor recorded")))

(deftest the-same-inputs-the-same-events
  (let [run (fn [] (let [b (bus/make-bus {:catalogue catalogue})]
                     (bus/subscribe! b :A :ctx/b (fn [_] (bus/publish! b :ctx/b :B {})))
                     (bus/publish! b :ctx/a :A {:x 1 :y 2})
                     (mapv bus/format-event (bus/events b))))]
    (is (= (run) (run)))))
