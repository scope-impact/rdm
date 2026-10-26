(ns rdm-spec.kernel.bus-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.kernel.bus :as bus]))

(def parsed {:revision :working-tree :scenarios [] :needs [] :refusals []})

(deftest delivers-in-subscription-order-and-logs
  (bus/start!)
  (let [seen (atom [])]
    (bus/subscribe! :SpecParsed :architecture/lint #(swap! seen conj [:lint %]))
    (bus/subscribe! :SpecParsed :verification/status #(swap! seen conj [:status %]))
    (bus/publish! :spec/parser :SpecParsed parsed)
    (is (= [[:lint parsed] [:status parsed]] @seen))
    (is (= [[:SpecParsed :spec/parser]] (bus/published)))
    (is (= parsed (bus/latest :SpecParsed)))))

(deftest refuses-what-the-catalogue-does-not-declare
  (bus/start!)
  (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :spec/parser :OrderPlaced {})))
  (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :spec/check :SpecParsed parsed)))
  (is (thrown? clojure.lang.ExceptionInfo (bus/publish! :spec/parser :SpecParsed (dissoc parsed :needs))))
  (is (:cannot-run (ex-data (try (bus/publish! :spec/parser :Nope {}) (catch Exception e e))))))
