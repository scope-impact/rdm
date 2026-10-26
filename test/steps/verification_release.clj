(ns steps.verification-release
  "Step definitions of dhf/spec/verification/release.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.verification-results :as vr]))

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx]
    (-> (vr/scenarios-project! ctx [{:tags ["spec-0001" "e2e" "wip"] :title "Planned" :steps ["Given a step"]} (k/sc 2 "Done")])
        (vr/result! {:id "spec-0002"})
        (k/names+ "spec-0001"))))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx]
    (-> (vr/scenarios-project! ctx [(k/sc 1 "Failing") (k/sc 2 "Passing")])
        (vr/result! {:id "spec-0001" :status "failed"}) (vr/result! {:id "spec-0002"})
        (k/names+ "spec-0001" "failed"))))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))

(defstep #"a stamped scenario with no result"
  (fn [ctx]
    (-> (vr/scenarios-project! ctx [(k/sc 1 "Unrun") (k/sc 2 "Passing")])
        (vr/result! {:id "spec-0002"})
        (k/names+ "spec-0001" "unverified"))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx] (-> (vr/scenarios-project! ctx [(k/sc 1 "One") (k/sc 2 "Two")])
                (vr/result! {:id "spec-0001"}) (vr/result! {:id "spec-0002"}))))

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx]
    (-> (k/regulated! ctx {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"})
        (k/pass-all! ["spec-0001"])
        (k/names+ "STD:5.2" "dhf/clauses/std.txt"))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [ctx]
    (-> (k/regulated! ctx {:docs {"dhf/documents/plan.md" (k/doc-text {:id "PLAN-001" :revision "1" :title "Plan"} "We plan [[STD:5.1]].\n")
                                  "dhf/documents/notes.md" (k/doc-text {:revision "1" :title "Notes"} "Notes.\n")}})
        (k/pass-all! ["spec-0001"])
        (k/names+ "dhf/documents/notes.md"))))

(defstep #"it exits 1 and names the document"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))
