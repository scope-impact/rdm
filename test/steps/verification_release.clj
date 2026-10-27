(ns steps.verification-release
  "Step definitions of dhf/spec/verification/release.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.verification-results :as vr]))

(defn release! [ctx scenarios results]
  (let [ctx (vr/scenarios-project! ctx scenarios)]
    (k/write-files! (:dir ctx) {"dhf/spec/ids.edn" (str "{:issued " (count scenarios) "}\n")})
    (doseq [r results] (k/write-result! (vr/results-dir ctx) r))
    ctx))

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx] (-> (release! ctx [(k/sc 1 "Done") (update (k/sc 2 "Planned") :tags conj "wip")] [{:id "spec-0001"}])
                (k/names+ "spec-0002"))))

(defstep #"it exits 1 and names the scenario's id(?: and its status)?"
  (fn [ctx]
    (k/check-exit ctx 1)
    (apply k/err-line-with ctx (:names ctx))
    (assert (not (str/includes? (:err ctx) "spec-0001:")) (:err ctx))
    ctx))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx] (-> (release! ctx [(k/sc 1 "Done") (k/sc 2 "Failing")] [{:id "spec-0001"} {:id "spec-0002" :status "failed"}])
                (k/names+ "spec-0002" "failed"))))

(defstep #"a stamped scenario with no result"
  (fn [ctx] (-> (release! ctx [(k/sc 1 "Done") (k/sc 2 "Untested")] [{:id "spec-0001"}])
                (k/names+ "spec-0002" "unverified"))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx] (release! ctx [(k/sc 1 "Done") (k/sc 2 "Also done")] [{:id "spec-0001"} {:id "spec-0002"}])))

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx]
    (-> (k/regulated! ctx {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"})
        (k/pass-all! ["spec-0001"]))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "STD:5.2" "dhf/clauses/std.txt")))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [ctx]
    (let [ctx (-> (k/regulated! ctx) (k/pass-all! ["spec-0001"]))]
      (k/write-files! (:dir ctx) {"dhf/documents/notes.md" (k/doc-text {:revision "1" :title "Notes"} "# Notes\n")})
      ctx)))

(defstep #"it exits 1 and names the document"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "dhf/documents/notes.md")))
