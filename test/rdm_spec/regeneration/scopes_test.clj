(ns rdm-spec.regeneration.scopes-test
  "One test per rule Scopes enforces (06-regenerate/scopes.feature, 11 and 13)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                                            {:id :verification :components [{:id :verification/status :spec "verification/status"}]}
                                            {:id :shop :components [{:id :shop/cart :spec "shop/cart"} {:id :shop/ledger :spec "shop/ledger"} {:id :shop/mail :spec "shop/mail"}]}]}]
            :kernel [{:id :kernel/bus :spec "kernel/bus"}]
            :relationships [{:from :spec/parser :to :verification/status :event :SpecParsed}
                            {:from :shop/cart :to :shop/mail :event :OrderPlaced}
                            {:from :shop/cart :to :shop/ledger :event :OrderPlaced}]})

(deftest a-cycles-scopes-come-from-the-spec-change
  (is (= :spec/parser (scopes/owner-of :contexts model "spec/parser.feature")))
  (is (= :spec/parser (scopes/owner-of :contexts model "spec/parser.md")))
  (is (nil? (scopes/owner-of :contexts model "glossary.md"))))

(deftest a-changed-event-reaches-its-publisher-and-every-subscriber
  (is (= #{:OrderPlaced} (scopes/changed-events {:OrderPlaced {:published-by :shop/cart :carries [:a] :shape {:a "x"}}}
                                                {:OrderPlaced {:published-by :shop/cart :carries [:a] :shape {:a "y"}}})))
  (is (empty? (scopes/changed-events {:OrderPlaced {:description "a" :published-by :shop/cart :read-by [:x]}}
                                     {:OrderPlaced {:description "b" :published-by :shop/cart}}))))

(deftest scopes-are-listed-in-the-order-a-builder-builds-them
  (is (= [:kernel/bus :spec/parser :verification/status]
         (scopes/build-order model #{:verification/status :kernel/bus :spec/parser})))
  (is (= [:shop/cart :shop/ledger :shop/mail] (scopes/build-order model #{:shop/mail :shop/ledger :shop/cart}))))

(deftest a-change-outside-the-projects-folder-is-no-scope-of-it
  (is (= ["dhf/spec/a.feature"] (scopes/in-folder "wallet/" ["wallet/dhf/spec/a.feature" "dhf/spec/b.feature"]))))

(deftest a-change-rebuilds-every-slice-it-touches-whole
  (is (= "12-several-specs" (scopes/slice-of "12-several-specs/plan.md")))
  (is (nil? (scopes/slice-of "glossary.md"))))
