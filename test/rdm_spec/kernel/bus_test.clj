(ns rdm-spec.kernel.bus-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]))

(def catalogue
  {:A {:published-by :ctx/one :carries [:x :y]}
   :B {:published-by :ctx/two :carries []}})

(defn- refusal [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         {:message (ex-message e) :cannot-run? (:rdm-spec.kernel.command/cannot-run (ex-data e))})))

(deftest delivers-synchronously-in-subscription-order
  (let [b (bus/make-bus {:catalogue catalogue})
        seen (atom [])]
    (bus/subscribe! b :A :s/first #(swap! seen conj [:first %]))
    (bus/subscribe! b :A :s/second #(swap! seen conj [:second %]))
    (bus/subscribe! b :B :s/third #(swap! seen conj [:third %]))
    (bus/publish! b :ctx/one :A {:x 1 :y nil})
    (is (= [[:first {:x 1 :y nil}] [:second {:x 1 :y nil}]] @seen))))

(deftest records-every-event-in-publication-order
  (let [b (bus/make-bus {:catalogue catalogue})]
    ;; a handler that publishes: its event comes after the one it handles
    (bus/subscribe! b :A :ctx/two (fn [_] (bus/publish! b :ctx/two :B {})))
    (bus/publish! b :ctx/one :A {:x 1 :y 2})
    (is (= [:A :B] (map :event (bus/events b))))
    (is (= [:ctx/one :ctx/two] (map :publisher (bus/events b))))
    (is (= {:x 1 :y 2} (:payload (first (bus/events b)))))
    (is (= ["event: A published-by ctx/one" "event: B published-by ctx/two"]
           (map bus/format-event (bus/events b))))))

(deftest no-catalogue-checks-nothing
  (doseq [b [(bus/make-bus) (bus/make-bus {:catalogue nil}) (bus/make-bus {:catalogue {}})]]
    (bus/publish! b :anyone :Undeclared {:whatever 1})
    (is (= [:Undeclared] (map :event (bus/events b))))))

(deftest refuses-what-the-catalogue-does-not-allow
  (let [b (bus/make-bus {:catalogue catalogue :catalogue-file "dhf/spec/events.edn"})
        seen (atom 0)]
    (bus/subscribe! b :A :s/s (fn [_] (swap! seen inc)))
    (testing "an undeclared event"
      (let [r (refusal #(bus/publish! b :ctx/one :C {}))]
        (is (:cannot-run? r))
        (is (str/includes? (:message r) "C"))
        (is (str/includes? (:message r) "dhf/spec/events.edn"))))
    (testing "a carried key missing"
      (let [r (refusal #(bus/publish! b :ctx/one :A {:x 1}))]
        (is (:cannot-run? r))
        (is (str/includes? (:message r) ":y"))))
    (testing "the wrong publisher"
      (let [r (refusal #(bus/publish! b :ctx/two :A {:x 1 :y 2}))]
        (is (:cannot-run? r))
        (is (str/includes? (:message r) "ctx/one"))))
    (testing "a refused event is neither delivered nor recorded"
      (is (= 0 @seen))
      (is (empty? (bus/events b))))))

(deftest a-catalogue-file-alone-names-the-catalogue
  (let [r (refusal #(bus/publish! (bus/make-bus {:catalogue catalogue}) :x/y :Z {}))]
    (is (str/includes? (:message r) "catalogue"))))
