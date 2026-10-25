(ns steps.verification-release
  "Step definitions for dhf/spec/verification/release.feature: `rdm-spec
  release` on a small project the scenario makes
  (steps.verification-support); the regulated ones carry :regulated, the
  clause list dhf/clauses/std.txt and the documents directory dhf/documents.

  Context keys: :dir, :id, :status (the scenario's expected status word),
  :clause, :clause-list, :document."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- names! [ctx & parts]
  (let [r (kc/exits! ctx 1)
        ls (kc/finding-lines r)]
    (s/check! (some (fn [l] (every? #(str/includes? l %) parts)) ls)
              (str "no finding names " (str/join ", " (map pr-str parts)) "\n" (s/show r)))
    nil))

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Planned" :wip? true} {:id "spec-0002" :title "Done"}])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx]
    (names! ctx (:id ctx))
    (s/check! (not-any? #(str/includes? % "spec-0002") (kc/finding-lines (kc/the-run ctx)))
              "a finding names the verified scenario")
    nil))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Failing"} {:id "spec-0002" :title "Done"}])
        (v/project-results! [["spec-0001" "failed"] ["spec-0002" "passed"]])
        (assoc :id "spec-0001" :status "failed"))))

(defstep #"a stamped scenario with no result"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Without a result"} {:id "spec-0002" :title "Done"}])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001" :status "unverified"))))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx] (names! ctx (:id ctx) (:status ctx))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "One"} {:id "spec-0002" :title "Two"}])
        (v/project-results! [["spec-0001" "passed"] ["spec-0002" "passed"]]))))

;; --- under the regulated profile ------------------------------------------------

(defn- document [id body]
  (str "---\nid: " id "\nrevision: 1\ntitle: A document\n---\n\n# A document\n\n" body "\n"))

(defn- regulated!
  "A project whose every stamped scenario passed, :regulated, with the clause
  list `clauses` and the documents `docs` {rel text}."
  [ctx clauses docs]
  (let [ctx (-> ctx
                (v/one-feature! [{:id "spec-0001" :title "One"}])
                (v/project-results! [["spec-0001" "passed"]]))
        dir (:dir ctx)
        cfg (kc/config-of dir)]
    (s/write! dir "dhf/harness/config.edn"
              (pr-str (assoc cfg :regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})))
    (s/write! dir "dhf/clauses/std.txt" clauses)
    (doseq [[f t] docs] (s/write! dir f t))
    ctx))

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx]
    (-> ctx
        (regulated! "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"
                    {"dhf/documents/plan.md" (document "PLAN-001" "We plan [[STD:5.1]].")})
        (assoc :clause "STD:5.2" :clause-list "dhf/clauses/std.txt"))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (names! ctx (:clause ctx) (:clause-list ctx))))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [ctx]
    (-> ctx
        (regulated! "STD:5.1 Development plan\n"
                    {"dhf/documents/plan.md" (document "PLAN-001" "We plan [[STD:5.1]].")
                     "dhf/documents/notes.md" "---\nrevision: 1\ntitle: Notes\n---\n\n# Notes\n\nNothing yet.\n"})
        (assoc :document "dhf/documents/notes.md"))))

(defstep #"it exits 1 and names the document"
  (fn [ctx] (names! ctx (:document ctx))))
