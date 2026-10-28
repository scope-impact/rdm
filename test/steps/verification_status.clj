(ns steps.verification-status
  "Steps of the components Status and Results reader (05-verify-by-results,
  12-several-specs/status.feature, 13-slice-development/status.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn results-dir [ctx] (k/path (:dir ctx) "dhf/allure-results"))

(defn project-of
  "A project whose cart holds the stamped scenarios ids (spec-NNNN), no others."
  [ids & {:keys [wip]}]
  (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios (vec (for [id ids] {:id id :title (str "Scenario " id) :wip (contains? (set wip) id)}))})}
                   :no-till true))

(defn statuses [ctx] (into {} (for [s (get-in ctx [:edn :status :scenarios])] [(:id s) (:status s)])))

(defn ensure-run
  "Runs status in the project when no command has run yet."
  [ctx] (if (:runs ctx) ctx (k/rdm! ctx "status")))

;; ------------------------------------------------------------------ results

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [_] (let [ctx (project-of ["spec-0001"])]
            (k/result! (results-dir ctx) "spec-0001" "passed")
            (k/result! (results-dir ctx) "spec-9999" "passed")
            ctx)))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx] (k/expect! (not (str/includes? (k/slurp-in (:dir ctx) "dhf/spec/shop/cart.feature") "spec-9999")) ctx "it is") ctx))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= ["spec-9999"] (map :spec (get-in ctx [:edn :status :unknown-results]))) ctx "spec-9999 is not an unknown result")
    ctx))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= {"spec-0001" :verified} (statuses ctx)) ctx (str "the statuses are " (statuses ctx)))
    (k/expect! (= {:verified 1 :failed 0 :unverified 0 :planned 0} (get-in ctx [:edn :status :counts])) ctx "the counts moved")
    ctx))

(defstep #"the results directory exists and holds no Allure result"
  (fn [_] (let [ctx (project-of ["spec-0001"])] (fs/create-dirs (results-dir ctx)) (k/write! (results-dir ctx) "notes.txt" "x") ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no test run wrote results there") ctx))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [_] (let [ctx (project-of ["spec-0001"])]
            (k/result! (results-dir ctx) "spec-0001" "passed")
            (k/write! (results-dir ctx) "bad-result.json" "{\"status\": \"passed\",\n \"labels\": [")
            ctx)))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [ls (filter #(str/includes? % "bad-result.json") (k/err-lines ctx))]
      (k/expect! (= 1 (count ls)) ctx "not one line")
      (k/expect! (str/includes? (first ls) "unreadable") ctx "not named unreadable"))
    ctx))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [_] (let [ctx (project-of ["spec-0001" "spec-0002"])]
            (doseq [id ["spec-0001" "spec-0002"]]
              (k/result! (results-dir ctx) id "passed"
                         {"labels" [{"name" "framework" "value" "cucumberjs"} {"name" "language" "value" "javascript"} {"name" "spec" "value" id}]
                          "fullName" "features/cart.feature:Scenario"}))
            ctx)))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= {"spec-0001" :verified "spec-0002" :verified} (statuses ctx)) ctx "not verified") ctx))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [_] (let [ctx (project-of ["spec-0001"])]
            (k/result! (results-dir ctx) "spec-0001" "passed")
            (k/result! (results-dir ctx) nil "failed" {"name" "a unit test"})
            ctx)))

(defstep #"it is not listed as an unknown result"
  (fn [ctx] (k/expect! (empty? (get-in ctx [:edn :status :unknown-results])) ctx "listed as unknown") ctx))

(defstep #"two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none"
  (fn [_] (let [ctx (project-of ["spec-0012"])]
            (k/result! (results-dir ctx) "spec-0012" "passed"
                       {"attachments" [{"name" "trace" "source" "x.txt" "type" "text/plain"}]
                        "links" [{"type" "issue" "name" "UN-001" "url" "u"}]
                        "labels" [{"name" "spec" "value" "spec-0012"} {"name" "owner" "value" "sami"} {"name" "severity" "value" "critical"}]})
            (k/result! (results-dir ctx) "spec-0012" "passed" {"labels" [{"name" "spec" "value" "spec-0012"}]})
            ctx)))

(defstep #"spec-0012 is verified, and the other elements change nothing"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= {"spec-0012" :verified} (statuses ctx)) ctx "not verified") ctx))

(defstep #"an Allure result labelled spec=spec-0012 whose status is \"green\""
  (fn [_] (let [ctx (project-of ["spec-0012"])]
            (k/result! (results-dir ctx) "spec-0012" "green")
            ctx)))

(defstep #"the report names its file as unreadable, saying the status must be passed, failed, broken or skipped"
  (fn [ctx] (k/exits! ctx 0) (k/line-has! ctx "-result.json" "unreadable" "passed, failed, broken or skipped") ctx))

;; ------------------------------------------------------------------ status

(defstep #"a project with the stamped scenarios @spec-0001 \"One\", @spec-0002 \"Two\" and @spec-0003 \"Three\", in dhf/spec/a/b.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip"
  (fn [_]
    (let [ctx (k/new-project)]
      (k/write! (:dir ctx) "dhf/spec/a/b.feature"
                (str "@UN-001\nFeature: B\n  @spec-0001 @e2e\n  Scenario: One\n    Given one\n\n"
                     "  @spec-0002 @e2e\n  Scenario: Two\n    Given two\n\n"
                     "  @spec-0003 @e2e @wip\n  Scenario: Three\n    Given three\n"))
      ctx)))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [ctx (k/var-dir! ctx "DIR") d (get-in ctx [:vars "DIR"])]
      (k/result! d "spec-0001" "passed")
      (assoc ctx :unknown-file (str d "/" (fs/file-name (k/result! d "spec-0099" "passed")))))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status .+"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (get-in ctx [:vars "DIR"])]
      (k/expect! (= {:status {:results d
                              :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified}
                                          {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified}
                                          {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}]
                              :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                              :unknown-results [{:file (:unknown-file ctx) :spec "spec-0099"}]}}
                    (:edn ctx))
                 ctx "not exactly the shape"))
    ctx))

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [_] (let [ctx (project-of ["spec-0001" "spec-0002"] :wip ["spec-0001"])]
            (k/result! (results-dir ctx) "spec-0002" "passed")
            (assoc ctx :subject "spec-0001"))))

(defstep #"it lists the scenario as planned(?:, not as verified, failed or unverified)?"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= :planned ((statuses ctx) (:subject ctx))) ctx "not planned") ctx))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [_] (assoc (project-of ["spec-0001"] :wip ["spec-0001"]) :subject "spec-0001")))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (k/result! (results-dir ctx) "spec-0001" "skipped") (k/result! (results-dir ctx) "spec-0001" "passed") ctx))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [_] (let [ctx (project-of ["spec-0001" "spec-0002" "spec-0003"])]
            (k/result! (results-dir ctx) "spec-0001" "passed")
            (k/result! (results-dir ctx) "spec-0002" "failed")
            (k/keep-spec ctx))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified} (statuses ctx)) ctx "not one of each") ctx))

(defn- row-result! [ctx row status]
  (k/result! (results-dir ctx) "spec-0013" status {"parameters" [{"name" "x" "value" row}]}))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx] (row-result! ctx "a" "passed") (row-result! ctx "b" "passed") (row-result! ctx "c" "failed") ctx))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx] (dotimes [_ 3] (k/result! (results-dir ctx) "spec-0013" "passed")) ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx] (row-result! ctx "a" "passed") (row-result! ctx "b" "passed") ctx))

(defstep #"a stamped scenario @spec-0007"
  (fn [_] (assoc (project-of ["spec-0007"]) :subject "spec-0007")))

(defstep #"an Allure result labelled spec=spec-0007 with status (\w+)"
  (fn [ctx st] (k/result! (results-dir ctx) "spec-0007" st) ctx))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx] (k/result! (results-dir ctx) "spec-0007" "passed") (k/result! (results-dir ctx) "spec-0007" "failed") ctx))

(defstep #"the scenario is (verified|failed|unverified)"
  (fn [ctx st]
    (let [ctx (ensure-run ctx)]
      (k/exits! ctx 0)
      (k/expect! (= (keyword st) ((statuses ctx) (:subject ctx))) ctx (str "it is " ((statuses ctx) (:subject ctx))))
      ctx)))

(defstep #"ten stamped scenarios"
  (fn [_] (project-of (for [i (range 1 11)] (format "spec-%04d" i)))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (doseq [i (range 1 10)] (k/result! (results-dir ctx) (format "spec-%04d" i) "passed")) ctx))

(defstep #"the report counts ten scenarios"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= 10 (count (statuses ctx)) (reduce + (vals (get-in ctx [:edn :status :counts])))) ctx "not ten") ctx))

(defstep #"the tenth is unverified"
  (fn [ctx] (k/expect! (= :unverified ((statuses ctx) "spec-0010")) ctx "the tenth is not unverified") ctx))
