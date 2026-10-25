(ns rdm-spec.measurement.checklist-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def scenarios
  [{:id "spec-0012" :title "B" :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint" :level :e2e :line 3 :file "a.feature"}
   {:id "spec-0002" :title "A" :context :verification :component :verification/status
    :context-name "verification" :component-name "Status" :level :contract}
   {:id "spec-0013" :title "W" :context :architecture :component :architecture/lint :wip? true}
   {:id "spec-10000" :title "Big" :context :kernel :component :kernel/bus
    :context-name "kernel" :component-name "Event bus" :level :e2e}])

(deftest ids-sort-by-length-then-text
  (is (= ["spec-0999" "spec-1000" "spec-10000"] (checklist/id-order ["spec-10000" "spec-1000" "spec-0999"]))))

(deftest scopes
  (let [lint (first scenarios) status (second scenarios)]
    (is (checklist/in-scope? :project lint))
    (is (checklist/in-scope? {:context :architecture} lint))
    (is (not (checklist/in-scope? {:context :architecture} status)))
    (is (checklist/in-scope? {:component :verification/status} status))
    (is (checklist/in-scope? [{:context :kernel} {:component :verification/status}] status))
    (testing "a malformed scope cannot run"
      (doseq [s [:everything {:context "x"} {:component :lint} [] [{:context :a :component :b/c}]]]
        (is (thrown? clojure.lang.ExceptionInfo (checklist/scope-of {:scope s ::checklist/file "c.edn"})) (pr-str s))))
    (is (= :project (checklist/scope-of {})))))

(deftest the-checklist-has-one-shape
  (let [cl (checklist/derive-checklist "03" "spec-3" :project scenarios)
        text (checklist/checklist-text cl)]
    (is (= ["spec-0002" "spec-0012" "spec-10000"] (map #(get % "id") (get cl "items"))))
    (is (= [{"id" "spec-0013" "title" "W"}] (get cl "planned")))
    (is (= {"id" "spec-0012" "title" "B" "context" "architecture" "component" "Lint" "level" "e2e"}
           (second (get cl "items"))))
    (is (< (str/index-of text "\"cycle\"") (str/index-of text "\"cut\"") (str/index-of text "\"items\"")
           (str/index-of text "\"planned\"")))
    (is (= cl (json/parse-string text)))
    (testing "a scope keeps its own"
      (is (= ["spec-0012"] (map #(get % "id") (get (checklist/derive-checklist "03" "spec-3" {:context :architecture} scenarios) "items")))))))

(deftest an-unstamped-scenario-is-named
  (let [fs* (checklist/unstamped-findings {:scenarios [{:id nil :ids [] :title "X" :file "f.feature" :line 9}
                                                       {:id nil :ids ["spec-1" "spec-2"] :title "Y" :file "f.feature" :line 12}]
                                           :refusals [{:file "g.feature" :line 2 :rule "gherkin-keyword" :message "no"}]})]
    (is (= [["g.feature" 2 "gherkin-keyword"] ["f.feature" 9 "unstamped-scenario"] ["f.feature" 12 "unstamped-scenario"]]
           (map (juxt :file :line :rule) fs*)))
    (is (re-find #"carries 2 ids" (:message (last fs*))))))

(defn- req [root & [args]]
  {:root root :task "checklist" :args (or args ["03"]) :opts {} :config {:cycles-dir "dhf/cycles"} :state (atom {})})

(deftest the-stage
  (let [root (str (fs/create-temp-dir))]
    (try
      (testing "no cycle.edn cannot run"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no dhf/cycles/03/cycle.edn" (checklist/checklist (req root)))))
      (spit (str (doto (fs/file root "dhf/cycles/03") fs/create-dirs) "/cycle.edn") (pr-str {:cut "spec-3"}))
      (testing "a cut the repository does not hold cannot run"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"spec-3.*not a tag" (checklist/checklist (req root)))))
      (testing "a bad cycle name cannot run"
        (is (thrown? clojure.lang.ExceptionInfo (checklist/checklist (req root ["../x"])))))
      (testing "with the spec parsed, it writes the checklist"
        (let [r (req root)
              [[_ handler]] (checklist/subscriptions r)]
          (handler {:scenarios scenarios :refusals []})
          (let [{:keys [result findings]} (checklist/checklist r)]
            (is (empty? findings))
            (is (= 3 (get-in result [:checklist :items])))
            (is (= "spec-3" (get (json/parse-string (slurp (str (fs/file root "dhf/cycles/03/checklist.json")))) "cut"))))))
      (testing "an unstamped scenario writes nothing"
        (fs/delete (fs/file root "dhf/cycles/03/checklist.json"))
        (let [r (req root)
              [[_ handler]] (checklist/subscriptions r)]
          (handler {:scenarios (conj scenarios {:id nil :ids [] :title "X" :file "f" :line 1}) :refusals []})
          (is (seq (:findings (checklist/checklist r))))
          (is (not (fs/exists? (fs/file root "dhf/cycles/03/checklist.json"))))))
      (testing "a scope that owns nothing is a finding"
        (spit (str (fs/file root "dhf/cycles/03/cycle.edn")) (pr-str {:cut "spec-3" :scope {:context :nowhere}}))
        (let [r (req root)
              [[_ handler]] (checklist/subscriptions r)]
          (handler {:scenarios scenarios :refusals []})
          (is (= ["scope-owns-nothing"] (map :rule (:findings (checklist/checklist r)))))))
      (finally (fs/delete-tree root)))))
