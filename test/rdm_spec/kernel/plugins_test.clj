(ns rdm-spec.kernel.plugins-test
  "One test per rule Plugins enforces (08-plugins/plugins.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.kernel.plugins :as plugins]))

(def cfg {:implementation ["src"]})

(deftest a-plugin-is-declared-in-the-configuration-in-the-order-it-runs
  (is (nil? (plugins/problem cfg [{:name "a" :subscribes [:SpecParsed] :command ["bb" "x.clj"] :seconds 20}])))
  (is (str/includes? (plugins/problem cfg [{:name "a" :command ["x"]}]) "subscribes to events or answers a request"))
  (is (str/includes? (plugins/problem cfg [{:name "a" :subscribes [:NoSuchEvent] :command ["x"]}]) "SpecParsed"))
  (is (str/includes? (plugins/problem cfg [{:name "a" :answers :Review :command ["x"]}]) ":Gate and :Check"))
  (is (str/includes? (plugins/problem cfg [{:name "a" :answers :Gate :command ["x"]} {:name "a" :answers :Gate :command ["x"]}]) "declared once"))
  (is (str/includes? (plugins/problem cfg [{:name "a" :answers :Gate :command ["bb" "src/x.clj"]}]) "never writes"))
  (is (= 60 (plugins/seconds {}))))

(deftest a-subscriber-receives-each-event-it-subscribes-to
  (is (= [[{:name "a" :subscribes [:SpecParsed]} {:event :SpecParsed :revision 1}]]
         (plugins/deliveries [{:name "a" :subscribes [:SpecParsed]} {:name "b" :subscribes [:VerificationDerived]}]
                             [{:event :SpecParsed :payload {:revision 1}}]))))

(deftest a-plugin-answers-through-one-interface
  (is (= {:findings ["x:1: r: w"] :warnings ["x:2: warning: r: w"]}
         (plugins/split-lines* "x:1: r: w\nx:2: warning: r: w\n"))))

(deftest an-answerer-is-asked-at-its-requests-place
  (is (= ["first" "second"] (map :name (plugins/answerers [{:name "first" :answers :Gate} {:name "c" :answers :Check} {:name "second" :answers :Gate}] :Gate)))))

(deftest a-plugin-is-added-from-the-templates-rdm-spec-carries
  (is (= #{"ddd-domain" "affordances"} (set (map :name (plugins/templates))))))

(deftest what-a-plugin-needs-to-know-is-in-the-configuration
  (is (str/includes? (plugins/problem cfg [{:name "a" :answers :Gate :command ["x"] :env {"A" 1}}]) ":env")))

(deftest a-plugin-can-follow-a-verified-cycle
  (is (nil? (plugins/problem cfg [{:name "a" :subscribes [:CycleVerified] :command ["x"]}]))))
