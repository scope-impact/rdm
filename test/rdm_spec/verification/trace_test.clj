(ns rdm-spec.verification.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.trace :as trace]))

(deftest an-id-names-a-scenario-or-a-need
  (is (= [:scenario "spec-0012"] (trace/id-kind "spec" "spec-0012")))
  (is (= [:scenario "spec-0012"] (trace/id-kind "spec" "@spec-0012")))
  (is (= [:scenario "req-0001"] (trace/id-kind "req" "req-0001")))
  (is (= [:need "UN-003"] (trace/id-kind "spec" "UN-003")))
  (is (= [:need "UN-003"] (trace/id-kind nil "@UN-003")))
  (testing "anything else names neither"
    (is (nil? (trace/id-kind "spec" "req-0001")))
    (is (nil? (trace/id-kind "spec" "frobnicate")))
    (is (nil? (trace/id-kind "spec" "spec-0012x")))))

(def parsed
  [{:id "spec-0001" :ids ["spec-0001"] :title "One" :name "One" :file "f" :line 3
    :component-name "Alpha" :context-name "demo" :level :e2e :needs ["UN-001" "UN-003"]}
   {:id "spec-0002" :ids ["spec-0002"] :title "Two" :name "Two" :file "f" :line 7
    :component :demo/beta :context :demo :level :contract :needs ["UN-001"] :wip? true}
   {:id nil :ids ["spec-0003" "spec-0004"] :title "Two ids" :file "g" :line 2 :needs ["UN-003"]}])

(deftest an-entry-carries-id-title-component-context-and-level
  (is (= {:id "spec-0001" :title "One" :file "f" :line 3 :component "Alpha" :context "demo"
          :level :e2e :needs ["UN-001" "UN-003"] :planned? false}
         (trace/entry (first parsed))))
  (let [e (trace/entry (second parsed))]
    (is (= "beta" (:component e)))
    (is (true? (:planned? e)))))

(deftest what-traces-to-a-need-and-what-carries-an-id
  (is (= ["spec-0001" nil] (mapv :id (filter #(trace/traces-to? "UN-003" %) parsed))))
  (is (empty? (filter #(trace/traces-to? "UN-007" %) parsed)))
  (is (trace/carries? "spec-0002" (second parsed)))
  (testing "a scenario with two ids carries both"
    (is (trace/carries? "spec-0004" (nth parsed 2))))
  (is (not (trace/carries? "spec-0999" (first parsed)))))

(deftest a-need-as-declared
  (is (= {:id "UN-001" :text "t" :sources ["s"] :declared? true}
         (trace/need-of {"UN-001" {:text "t" :sources ["s"]}} "UN-001")))
  (is (= {:id "UN-001" :text "t" :sources ["s"] :declared? true}
         (trace/need-of {"UN-001" {:id "UN-001" :text "t" :sources ["s"] :line 4}} "UN-001")))
  (is (= {:id "UN-009" :declared? false} (trace/need-of {} "UN-009"))))

(deftest statuses-only-from-a-derived-report
  (let [entries [{:id "spec-0001"} {:id nil}]]
    (is (= entries (trace/with-statuses entries {:derived? false :results nil})))
    (is (= [{:id "spec-0001" :status :failed :results [{:file "a" :status "failed"}]} {:id nil}]
           (trace/with-statuses entries {:derived? true
                                         :scenarios [{:id "spec-0001" :status :failed
                                                      :results [{:file "a" :status "failed"}]}]})))))

(defn- req-with [scenarios needs args]
  (let [req {:task "trace" :args args :opts {} :root "." :config {:id-prefix "spec"}
             :results nil :state (atom {}) :bus nil}]
    (doseq [[_ h] (trace/subscriptions req)]
      (h {:revision :working-tree :scenarios scenarios :needs needs :refusals []}))
    req))

(def needs [{:id "UN-001" :text "first" :sources ["s"]} {:id "UN-007" :text "seventh" :sources ["s"]}])

(deftest tracing-without-results
  (let [{:keys [result findings]} (trace/trace (req-with parsed needs ["UN-001"]))]
    (is (empty? findings))
    (is (= :need (:kind result)))
    (is (= ["spec-0001" "spec-0002"] (mapv :id (:scenarios result))))
    (is (false? (:derived? result)))
    (is (= trace/no-results-note (:note result))))
  (testing "a declared need nothing traces to says so"
    (let [{:keys [result]} (trace/trace (req-with parsed needs ["UN-007"]))]
      (is (empty? (:scenarios result)))
      (is (re-find #"no scenario traces to UN-007" (:note result)))))
  (testing "a need tagged but not declared is traced"
    (is (= [nil "spec-0001"] (sort-by str (map :id (:scenarios (:result (trace/trace (req-with parsed needs ["UN-003"])))))))))
  (testing "a scenario names its needs"
    (let [{:keys [result]} (trace/trace (req-with parsed needs ["spec-0001"]))]
      (is (= :scenario (:kind result)))
      (is (= [{:id "UN-001" :text "first" :sources ["s"] :declared? true} {:id "UN-003" :declared? false}]
             (:needs result))))))

(deftest what-names-nothing-cannot-run
  (doseq [id ["spec-0999" "UN-999" "frobnicate"]]
    (let [e (try (trace/trace (req-with parsed needs [id])) nil (catch Exception e e))]
      (is (and e (command/cannot-run? e)) id)
      (is (re-find (re-pattern id) (str (ex-message e))) id)))
  (testing "no SpecParsed, no trace"
    (let [e (try (trace/trace {:args ["spec-0001"] :config {} :state (atom {})}) nil (catch Exception e e))]
      (is (command/cannot-run? e)))))

(deftest it-subscribes-for-status-too
  (is (= #{:SpecParsed} (set (map first (trace/subscriptions {:state (atom {})})))))
  (is (= 2 (count (trace/subscriptions {:state (atom {})})))))
