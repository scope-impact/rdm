(ns rdm-spec.kernel.bus-test
  "Unit tests of the event bus. None makes a fixture on disk; the one file
  read is rdm-spec's own spec, dhf/spec/events.edn, which the built-in
  catalogue must match."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]))

(defn- refused? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (bus/refused? e))))

(def ^:private room
  (let [here (fs/absolutize *file*)]
    (-> here fs/parent fs/parent fs/parent fs/parent)))

(deftest the-built-in-catalogue-is-rdm-specs-own-spec
  (let [f (fs/file room "dhf/spec/events.edn")]
    (when (fs/exists? f)
      (let [spec (edn/read-string (slurp (str f)))]
        (is (= (into {} (for [[e d] spec] [e (select-keys d [:published-by :carries])]))
               bus/catalogue))))))

(deftest delivery-is-synchronous-and-in-subscription-order
  (let [b (bus/bus)
        seen (atom [])]
    (bus/subscribe! b :SpecParsed (fn [p] (swap! seen conj [:first (:revision p)])))
    (bus/subscribe! b :SpecParsed (fn [p] (swap! seen conj [:second (:revision p)])))
    (bus/subscribe! b :SpecGated (fn [_] (swap! seen conj [:gated])))
    (bus/publish! b :spec/parser :SpecParsed {:revision :working-tree :scenarios [] :needs [] :refusals []})
    (is (= [[:first :working-tree] [:second :working-tree]] @seen))
    (bus/publish! b :spec/check :SpecGated {:revision :working-tree :passed? true :findings []})
    (is (= [[:first :working-tree] [:second :working-tree] [:gated]] @seen))
    (testing "the bus keeps what was published, in order"
      (is (= [{:event :SpecParsed :publisher :spec/parser} {:event :SpecGated :publisher :spec/check}]
             (bus/published b)))
      (is (= ["event: SpecParsed published-by spec/parser" "event: SpecGated published-by spec/check"]
             (map bus/event-line (bus/published b)))))))

(deftest the-bus-refuses-what-rdm-spec-does-not-declare
  (let [b (bus/bus)]
    (testing "an event rdm-spec does not declare, whatever a project declares"
      (is (refused? #(bus/publish! b :shop/orders :OrderPlaced {:order {}})))
      (is (refused? #(bus/subscribe! b :OrderPlaced identity))))
    (testing "an event from the wrong publisher"
      (is (refused? #(bus/publish! b :shop/orders :SpecParsed {:revision 1 :scenarios [] :needs [] :refusals []}))))
    (testing "an event missing a key it carries"
      (is (refused? #(bus/publish! b :spec/parser :SpecParsed {:revision 1}))))
    (is (empty? (bus/published b)))))

(deftest a-bus-carries-only-the-catalogue-it-was-built-with
  (let [b (bus/bus {:Pinged {:published-by :a/b :carries [:at]}})]
    (bus/publish! b :a/b :Pinged {:at 1})
    (is (refused? #(bus/publish! b :spec/check :SpecGated {:revision 1 :passed? true :findings []})))
    (is (= [{:event :Pinged :publisher :a/b}] (bus/published b)))))
