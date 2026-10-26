(ns rdm-spec.architecture.domain-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.domain :as domain]))

(def c "loyly:README.md@17d68d2")
(def good
  {:contexts {:booking {:aggregates {:standing-slot {:commands {:RequestStandingSlot {:by :resident :emits [:SlotConfirmed] :refuses [:held] :sources [c]}}
                                                     :invariants {:one-holder {:text "one" :verified-by ["spec-0003"] :sources [c]}}
                                                     :reads [:open-days]
                                                     :sources [c]}}
                        :read-models {:open-days {:fed-by [:SlotConfirmed] :sources [c]}}
                        :policies {:charge {:when :SlotConfirmed :then "invoice" :sources [c]}}
                        :repositories {:booking-record {:stores [:standing-slot]}}}}
   :events {:SlotConfirmed {:sources [c]}}})

(def env {:contexts #{:booking :schedule} :catalogue #{:SlotConfirmed :DaysConfigured}
          :scenario-ids #{"spec-0003"} :sources {"loyly" #{"17d68d2"}}})

(defn rules [d] (map :rule (domain/findings d env)))

(deftest good-passes (is (empty? (rules good))))
(deftest undeclared-key (is (= ["domain-key-undeclared"] (rules (assoc-in good [:contexts :booking :owner] 1)))))
(deftest unsourced (is (= ["domain-unsourced"] (rules (update-in good [:contexts :booking :aggregates :standing-slot] dissoc :sources)))))
(deftest unknown-source (is (= ["domain-source-unknown"] (rules (assoc-in good [:contexts :booking :aggregates :standing-slot :commands :RequestStandingSlot :sources] ["loyly:raw/x.md@0000000"])))))
(deftest silent (is (= ["domain-command-silent" "domain-event-orphan"] (rules (update-in good [:contexts :booking :aggregates :standing-slot :commands :RequestStandingSlot] dissoc :emits :refuses)))))
(deftest orphan (is (= ["domain-event-orphan"] (rules (assoc-in good [:events :DaysConfigured] {:sources [c]})))))
(deftest fed-by-command (is (= ["domain-read-model-fed"] (rules (assoc-in good [:contexts :booking :read-models :open-days :fed-by] [:RequestStandingSlot])))))
(deftest unverified
  (is (= ["domain-invariant-unverified"] (rules (assoc-in good [:contexts :booking :aggregates :standing-slot :invariants :one-holder :verified-by] []))))
  (is (= ["domain-invariant-unverified"] (rules (assoc-in good [:contexts :booking :aggregates :standing-slot :invariants :one-holder :verified-by] ["spec-9999"])))))
(deftest uncatalogued (is (= ["domain-event-uncatalogued"] (rules (-> good (assoc-in [:contexts :booking :aggregates :standing-slot :commands :RequestStandingSlot :emits] [:SlotConfirmed :Other]))))))
(deftest coupled
  (is (= ["domain-contexts-coupled"]
         (rules (-> good (assoc-in [:contexts :schedule] {:aggregates {:calendar {:commands {:Open {:emits [:SlotConfirmed] :sources [c]}} :sources [c] :reads [:standing-slot]}}}))))))
(deftest two-handlers
  (is (= ["domain-command-handlers"]
         (rules (assoc-in good [:contexts :schedule] {:aggregates {:calendar {:commands {:RequestStandingSlot {:emits [:SlotConfirmed] :sources [c]}} :sources [c]}}})))))
