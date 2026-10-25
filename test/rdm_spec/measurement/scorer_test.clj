(ns rdm-spec.measurement.scorer-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.scorer :as scorer]))

(def roles {:builder {:name "builder-a" :actor :agent}
            :grader {:name "grader-b" :actor :agent}
            :spec-owner {:name "Sami" :actor :human}})

(defn- rules [fs] (set (map :rule fs)))

(deftest role-findings
  (testing "three named roles, each human or agent, pass"
    (is (empty? (scorer/role-findings "c.edn" roles))))
  (testing "a missing role is named"
    (let [[f :as fs] (scorer/role-findings "c.edn" (dissoc roles :spec-owner))]
      (is (= 1 (count fs)))
      (is (= "cycle-role-missing" (:rule f)))
      (is (str/includes? (:message f) "spec owner"))))
  (testing "a role with no name or an unknown actor"
    (is (= #{"cycle-role"} (rules (scorer/role-findings "c.edn" (assoc roles :grader {:actor :agent})))))
    (is (= #{"cycle-role"} (rules (scorer/role-findings "c.edn" (assoc roles :grader {:name "g" :actor :robot}))))))
  (testing "the builder may be neither grader nor spec owner"
    (is (= #{"builder-scores-own-cycle"}
           (rules (scorer/role-findings "c.edn" (assoc roles :grader {:name "builder-a" :actor :agent})))))
    (is (= #{"builder-scores-own-cycle"}
           (rules (scorer/role-findings "c.edn" (assoc roles :spec-owner {:name "builder-a" :actor :human})))))))

(deftest ratification-findings
  (is (empty? (scorer/ratification-findings "r.edn" {:cut "spec-3" :outcome :passed} "spec-3")))
  (doseq [r [nil {:cut "spec-3" :outcome :refused} {:cut "spec-2" :outcome :passed} [:passed]]]
    (is (= #{"no-passing-ratification"} (rules (scorer/ratification-findings "r.edn" r "spec-3"))) (pr-str r))))

(defn- verdict-rules [text]
  (rules (scorer/verdict-findings "v.edn" (first (:forms (scorer/parse-verdicts text))))))

(deftest verdict-shape
  (is (empty? (verdict-rules "[{:id \"spec-0012\" :verdict :pass :evidence \"e\"}]")))
  (is (= #{"verdicts-shape"} (verdict-rules "{:id \"spec-0012\"}")))
  (is (= #{"verdicts-duplicate"}
         (verdict-rules "[{:id \"spec-0012\" :verdict :pass :evidence \"e\"}\n{:id \"spec-0012\" :verdict :fail :evidence \"f\"}]")))
  (is (= #{"verdicts-verdict"} (verdict-rules "[{:id \"spec-0012\" :verdict :maybe :evidence \"e\"}]")))
  (is (= #{"verdicts-evidence"} (verdict-rules "[{:id \"spec-0012\" :verdict :pass}]")))
  (is (= #{"verdicts-evidence"} (verdict-rules "[{:id \"spec-0012\" :verdict :pass :evidence \"  \"}]")))
  (is (= #{"verdicts-id"} (verdict-rules "[{:verdict :pass :evidence \"e\"}]")))
  (is (= #{"verdicts-shape"} (verdict-rules "[{:id \"spec-0012\" :verdict :pass :evidence \"e\" :by \"me\"}]")))
  (testing "a finding names the line of the verdict"
    (let [[f] (scorer/verdict-findings "v.edn" (first (:forms (scorer/parse-verdicts
                                                                "[{:id \"spec-0012\" :verdict :pass :evidence \"e\"}\n {:id \"spec-0014\" :verdict :x :evidence \"e\"}]"))))]
      (is (= 2 (:line f))))))

(deftest unknown-ids
  (let [vs [{:id "spec-0012"} {:id "spec-0999"}]]
    (is (= ["spec-0999 is not on the cycle's checklist; only its items are graded"]
           (map :message (scorer/unknown-findings "v.edn" vs ["spec-0012"]))))))

(deftest items-and-score
  (let [items (scorer/score-items ["spec-0012" "spec-0014" "spec-0015"]
                                  [{:id "spec-0014" :verdict :fail :evidence "b"} {:id "spec-0012" :verdict :pass :evidence "a"}])
        m (scorer/score-map "03" (assoc roles :cut "spec-3") {:room-tree "4b825dc"} items)]
    (is (= ["spec-0012" "spec-0014" "spec-0015"] (map :id items)) "checklist order")
    (is (= {:id "spec-0015" :verdict :fail :evidence scorer/ungraded-evidence :ungraded true} (last items)))
    (is (= [1 3] [(:passed m) (:scored m)]))
    (is (= :project (:scope m)) "no scope is the whole project")
    (is (= [:cycle :cut :scope :room-tree :builder :grader :spec-owner :items :passed :scored] (keys m)))
    (is (= m (edn/read-string (scorer/score-text m))))))

;; --- the task, end to end in a temporary project ---------------------------

(defn- project [{:keys [verdicts ratified cycle]}]
  (let [dir (str (fs/create-temp-dir {:prefix "scorer-test-"}))
        w (fn [rel s] (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f s)))]
    (w "dhf/harness/config.edn" (pr-str {:cycles-dir "dhf/cycles"}))
    (w "dhf/cycles/03/cycle.edn" (pr-str (or cycle (merge {:cut "spec-3" :scope :project} roles))))
    (w "dhf/cycles/03/checklist.json" (json/generate-string {:cycle "03" :cut "spec-3"
                                                             :items [{:id "spec-0012"} {:id "spec-0014"}] :planned []}))
    (when-not (= :none ratified)
      (w "dhf/cycles/03/ratified.edn" (pr-str (or ratified {:cut "spec-3" :room-tree "4b825dc" :outcome :passed}))))
    (w "verdicts.edn" (or verdicts "[{:id \"spec-0012\" :verdict :pass :evidence \"ok\"}]"))
    dir))

(defn- run [dir] (command/run-task ["score" "03" "--verdicts" "verdicts.edn"] dir nil))

(defn- score-file [dir] (fs/file dir "dhf/cycles/03/score.edn"))

(deftest score-task
  (testing "a pass writes score.edn, the ungraded item failed"
    (let [dir (project {})
          r (run dir)
          s (edn/read-string (slurp (score-file dir)))]
      (is (= 0 (:exit r)) (pr-str r))
      (is (= [1 2] [(:passed s) (:scored s)]))
      (is (= "4b825dc" (:room-tree s)))))
  (testing "no verdict: cannot run, nothing written"
    (doseq [v ["[]" "" "nil"]]
      (let [dir (project {:verdicts v})
            r (run dir)]
        (is (= 2 (:exit r)) (pr-str v r))
        (is (some #(str/includes? % "grading did not happen") (:err r)))
        (is (not (fs/exists? (score-file dir)))))))
  (testing "refusals exit 1 and write nothing"
    (doseq [[opts re] [[{:verdicts "{}"} #"map, not a vector"]
                       [{:ratified :none} #"no passing ratification"]
                       [{:ratified {:cut "spec-3" :outcome :refused}} #"no passing ratification"]
                       [{:verdicts "[{:id \"spec-0999\" :verdict :pass :evidence \"x\"}]"} #"spec-0999"]
                       [{:cycle (merge {:cut "spec-3"} (dissoc roles :spec-owner))} #"spec owner"]
                       [{:verdicts "[{:id"} #"not EDN"]]]
      (let [dir (project opts)
            r (run dir)]
        (is (= 1 (:exit r)) (pr-str opts r))
        (is (some #(re-find re %) (:err r)) (pr-str opts (:err r)))
        (is (not (fs/exists? (score-file dir))))))))

(deftest read-score
  (let [dir (project {})
        req {:root dir :config {:cycles-dir "dhf/cycles"}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not scored" (scorer/read-score req "03")))
    (run dir)
    (is (= "03" (:cycle (scorer/read-score req "03"))))))
