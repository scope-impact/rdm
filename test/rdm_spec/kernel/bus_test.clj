(ns rdm-spec.kernel.bus-test
  "Unit tests of the event bus. They make no fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as command]))

(def catalogue
  {:SpecParsed {:published-by :spec/parser :carries [:revision :scenarios]}
   :SpecGated {:published-by :spec/check :carries [:passed?]}})

(defn- refused [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (when (command/cannot-run? e) (ex-message e)))))

(deftest events-are-delivered-in-subscription-order-and-kept
  (let [b (bus/make-bus {:catalogue catalogue :source "dhf/spec/events.edn"})
        seen (atom [])]
    (bus/subscribe! b :SpecParsed :a/one #(swap! seen conj [:one (:revision %)]))
    (bus/subscribe! b :SpecParsed :a/two #(swap! seen conj [:two (:revision %)]))
    (bus/subscribe! b :SpecGated :a/three #(swap! seen conj [:three (:passed? %)]))
    (bus/publish! b :spec/parser :SpecParsed {:revision "r" :scenarios []})
    (bus/publish! b :spec/check :SpecGated {:passed? true})
    (is (= [[:one "r"] [:two "r"] [:three true]] @seen))
    (is (= [[:SpecParsed :spec/parser] [:SpecGated :spec/check]]
           (map (juxt :event :publisher) (bus/events b))))))

(deftest the-catalogue-decides-what-the-bus-carries
  (let [b (bus/make-bus {:catalogue catalogue :source "dhf/spec/events.edn"})]
    (testing "an event the catalogue does not declare"
      (let [m (refused #(bus/publish! b :knowledge/sources :KnowledgeIndexed {}))]
        (is (str/includes? m "KnowledgeIndexed"))
        (is (str/includes? m "dhf/spec/events.edn"))))
    (testing "an event from another publisher"
      (is (str/includes? (refused #(bus/publish! b :spec/check :SpecParsed {:revision 1 :scenarios []}))
                         "spec/parser")))
    (testing "an event missing a key it carries"
      (is (str/includes? (refused #(bus/publish! b :spec/parser :SpecParsed {:revision 1})) ":scenarios")))
    (is (empty? (bus/events b)) "a refused event is never kept")))

(deftest an-empty-catalogue-checks-nothing
  (let [b (bus/make-bus)]
    (bus/publish! b :x/y :Anything {:a 1})
    (is (= [{:event :Anything :publisher :x/y :payload {:a 1}}] (bus/events b)))))

(deftest no-bus-is-nothing-to-publish-on
  (is (nil? (bus/publish! nil :x/y :E {})))
  (is (= [] (bus/events nil))))
