(ns steps.verification-release
  "Steps of the component Release check (dhf/spec/05-verify-by-results/release.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.verification-status :as s]))

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx] (-> (s/scenarios-project! ctx [1 2] {:wip [1]}) (s/result! "spec-0002" "passed"))))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0001") ctx))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx] (-> (s/scenarios-project! ctx [1 2]) (s/result! "spec-0001" "failed") (s/result! "spec-0002" "passed"))))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (some #(and (str/includes? % "spec-0001") (re-find #"failed|unverified" %)) (k/err-lines ctx)) "no line names spec-0001 and its status: " (k/shows ctx))
    ctx))

(defstep #"a stamped scenario with no result"
  (fn [ctx] (-> (s/scenarios-project! ctx [1 2]) (s/result! "spec-0002" "passed"))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx] (-> (s/scenarios-project! ctx [1 2]) (s/result! "spec-0001" "passed") (s/result! "spec-0002" "passed"))))

(def regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(defn doc-text [body & [fm]]
  (let [fm (merge {"id" "PLAN-001" "revision" "1" "title" "Development plan"} fm)]
    (str "---\n" (str/join (for [[k v] fm :when v] (str k ": " v "\n"))) "---\n\n" body)))

(defn regulated-release! [ctx]
  (let [ctx (-> (s/scenarios-project! ctx [1]) (s/result! "spec-0001" "passed"))]
    (k/config! ctx assoc :regulated regulated)
    (k/write! ctx "dhf/clauses/std.txt" "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
    (k/write! ctx "dhf/documents/plan.md" (doc-text "We plan [[STD:5.1]] and analyse [[STD:5.2]].\n"))
    ctx))

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx] (let [ctx (regulated-release! ctx)] (k/write! ctx "dhf/documents/plan.md" (doc-text "We plan [[STD:5.1]].\n")))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "STD:5.2" "dhf/clauses/std.txt") ctx))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [ctx] (let [ctx (regulated-release! ctx)] (k/write! ctx "dhf/documents/other.md" (doc-text "Other text.\n" {"id" nil})))))

(defstep #"it exits 1 and names the document"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/documents/other.md") ctx))
