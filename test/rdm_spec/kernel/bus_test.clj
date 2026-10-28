(ns rdm-spec.kernel.bus-test
  "One test per rule the event bus enforces (08-plugins/bus.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.kernel.bus :as bus]))

(deftest a-run-shows-the-events-it-published-in-order
  (bus/with-bus
    (let [seen (atom [])]
      (bus/subscribe! :SpecParsed #(swap! seen conj %))
      (bus/publish! :KnowledgeIndexed :knowledge/sources {:revision :working-tree :sources [] :pages [] :citations [] :findings []})
      (bus/publish! :SpecParsed :spec/parser {:revision :working-tree :scenarios [] :needs [] :refusals []})
      (is (= ["event: KnowledgeIndexed published-by knowledge/sources" "event: SpecParsed published-by spec/parser"]
             (map bus/event-line (bus/published))))
      (is (= 1 (count @seen))))))

(deftest the-bus-carries-rdm-specs-own-events-only
  (bus/with-bus
    (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :OrderPlaced :shop/cart {})))
    (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :SpecParsed :shop/cart {:revision 1 :scenarios [] :needs [] :refusals []})))
    (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :SpecParsed :spec/parser {:revision 1})))))
