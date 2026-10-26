(ns rdm-spec.architecture.domain-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.domain :as domain]))

(def cite "loyly:README.md@17d68d2")

(def d
  {:contexts
   {:booking {:aggregates {:slot {:commands {:Request {:by :resident :emits [:Confirmed] :refuses [:held] :sources [cite]}}
                                  :invariants {:one {:text "one" :verified-by ["spec-0003"] :sources [cite]}}
                                  :reads [:days]
                                  :sources [cite]}}
              :read-models {:days {:fed-by [:Configured] :sources [cite]}}
              :policies {:charge {:when :Confirmed :then "invoice" :sources [cite]}}
              :repositories {:record {:stores [:slot]}}}}
   :events {:Confirmed {:sources [cite]}}})

(defn- rules [x]
  (mapv :rule (domain/findings {:domain x :text (pr-str x) :file "dhf/spec/domain.edn"
                                :contexts #{:booking :billing}
                                :catalogue {:Confirmed {} :Configured {}}
                                :scenario-ids #{"spec-0003"}
                                :sources {"loyly" #{"17d68d2"}}})))

(deftest a-sound-domain-model-passes
  (is (= [] (rules d))))

(deftest shape
  (is (= ["domain-key-undeclared"] (rules (assoc-in d [:contexts :booking :owner] 1))))
  (is (= ["domain-key-undeclared"] (rules (assoc d :storms []))))
  (is (= ["domain-context-unknown"] (rules (assoc-in d [:contexts :lodging] {}))))
  (is (some #{"domain-shape"} (rules (assoc-in d [:contexts :booking :aggregates] []))))
  (is (= ["domain-unreadable"] (mapv :rule (domain/findings {:domain ::unreadable :error "bad" :file "f"})))))

(deftest sources
  (is (= ["domain-unsourced"] (rules (update-in d [:contexts :booking :aggregates :slot] dissoc :sources))))
  (is (= ["domain-unsourced"] (rules (assoc-in d [:events :Confirmed :sources] []))))
  (is (= ["domain-source-unknown"] (rules (assoc-in d [:contexts :booking :aggregates :slot :sources] ["loyly:x@0000000"]))))
  (is (= ["domain-source-unknown"] (rules (assoc-in d [:contexts :booking :aggregates :slot :sources] ["nonsense"])))))

(deftest ddd-rules
  (let [cmd [:contexts :booking :aggregates :slot :commands :Request]]
    (is (= ["domain-command-handlers"]
           (rules (assoc-in d [:contexts :booking :aggregates :pool] {:commands {:Request (get-in d cmd)} :sources [cite]}))))
    (is (= ["domain-command-silent" "domain-event-orphan"] (rules (update-in d cmd assoc :emits [] :refuses []))))
    (is (= ["domain-event-orphan"] (rules (assoc-in d [:events :Configured] {:sources [cite]}))))
    (is (= ["domain-read-model-fed"] (rules (assoc-in d [:contexts :booking :read-models :days :fed-by] [:Request]))))
    (is (= ["domain-invariant-unverified"] (rules (assoc-in d [:contexts :booking :aggregates :slot :invariants :one :verified-by] []))))
    (is (= ["domain-invariant-unverified"] (rules (assoc-in d [:contexts :booking :aggregates :slot :invariants :one :verified-by] ["spec-9999"]))))
    (is (= ["domain-contexts-coupled"]
           (rules (-> d (assoc-in [:contexts :billing] {:aggregates {:invoice {:sources [cite]}}})
                      (assoc-in [:contexts :booking :aggregates :slot :reads] [:invoice])))))
    (testing "an event a command emits that the catalogue lacks"
      (is (= ["domain-event-uncatalogued"]
             (rules (-> d (assoc-in (conj cmd :emits) [:Confirmed :Held]) (assoc-in [:events :Held] {:sources [cite]})))))))
  (testing "a finding names its element"
    (let [[f] (domain/findings {:domain (assoc-in d [:events :Configured] {:sources [cite]}) :text "" :file "x"
                                :contexts #{:booking} :catalogue {:Confirmed {} :Configured {}} :scenario-ids #{"spec-0003"}
                                :sources {"loyly" #{"17d68d2"}}})]
      (is (str/includes? (:message f) ":Configured")))))
