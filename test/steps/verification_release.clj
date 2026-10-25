(ns steps.verification-release
  "Step definitions for dhf/spec/verification/release.feature: `rdm-spec
  release` in a small project with its own spec (steps.verification-fixtures)
  and Allure results written by hand. The When and `it exits N` are the
  kernel's. Under the regulated profile the project also declares a clause
  list and holds a controlled document."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- needs-release! [] (s/needs-built! 'rdm-spec.verification.release))

(defn- two-scenarios!
  "ctx with a project of spec-0001 (tagged `tags`) and spec-0002, and a
  result for each id of `results` {id status}."
  [ctx tags results]
  (needs-release!)
  (let [ctx (assoc ctx :id "spec-0001"
                   :dir (fx/one-feature (str/join "\n" [(fx/scenario-text {:id "spec-0001" :tags tags})
                                                        (fx/scenario-text {:id "spec-0002"})])))]
    (reduce (fn [c [id st]] (fx/add-result! c id st)) ctx results)))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- exits-1-naming! [ctx words]
  (let [r (run ctx)]
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l %) words)) (s/err-lines r))
              (str "no finding names " (str/join " and " words) "\n" (s/show r)))))

;; ---------------------------------------------------------------------------
;; A release needs every scenario verified

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx] (two-scenarios! ctx ["@wip"] {"spec-0002" "passed"})))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx] (exits-1-naming! ctx [(:id ctx)])))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx] (assoc (two-scenarios! ctx [] {"spec-0001" "failed" "spec-0002" "passed"}) :status "failed")))

(defstep #"a stamped scenario with no result"
  (fn [ctx] (assoc (two-scenarios! ctx [] {"spec-0002" "passed"}) :status "unverified")))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx] (exits-1-naming! ctx [(:id ctx) (:status ctx)])))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx] (two-scenarios! ctx [] {"spec-0001" "passed" "spec-0002" "passed"})))

;; ---------------------------------------------------------------------------
;; Under the regulated profile, a release needs every clause named

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx]
    (let [ctx (two-scenarios! ctx [] {"spec-0001" "passed" "spec-0002" "passed"})
          dir (:dir ctx)
          cfg-file (fs/file dir "dhf/harness/config.edn")
          cfg (edn/read-string (slurp (str cfg-file)))]
      (spit (str cfg-file) (pr-str (assoc cfg :regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                          :documents "dhf/documents"})))
      (s/write! dir "dhf/clauses/std.txt" "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
      (s/write! dir "dhf/documents/plan.md"
                "---\nid: PLAN-001\nrevision: 1\ntitle: Development plan\n---\n\nWe plan the work [[STD:5.1]].\n")
      (assoc ctx :clause "STD:5.2" :clause-list "dhf/clauses/std.txt"))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (exits-1-naming! ctx [(:clause ctx) (:clause-list ctx)])))
