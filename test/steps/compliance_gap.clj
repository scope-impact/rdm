(ns steps.compliance-gap
  "Step definitions for dhf/spec/compliance/gap.feature: gap analysis as a
  stage of `rdm-spec check`, in a regulated project (steps.compliance-support)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private plan-rel "dhf/documents/plan.md")

(defn- write-plan! [ctx text] (cs/write! (:dir ctx) plan-rel text) ctx)

(defn- line-of
  "The 1-based number of the first line of `text` holding `part`."
  [text part]
  (some (fn [[i l]] (when (str/includes? l part) (inc i))) (map-indexed vector (str/split-lines text))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"a file dhf/documents/plan\.md that names no clause"
  (fn [ctx]
    (write-plan! ctx (cs/plan ["# Plan" "We plan the work, and STD:5.1 is only mentioned."]))))

(defstep #"a regulated project"
  (fn [ctx] (cs/regulated-project! ctx)))

(defstep #"(dhf/clauses/\S+) holds:"
  (fn [ctx rel doc] (cs/write! (:dir ctx) rel (str doc "\n")) ctx))

(defstep #"(dhf/clauses/\S+) holds \"([^\"]*)\""
  (fn [ctx rel line] (cs/write! (:dir ctx) rel (str line "\n")) ctx))

(defstep #"dhf/clauses/std\.txt holds only comments"
  (fn [ctx] (cs/write! (:dir ctx) "dhf/clauses/std.txt" "# The standard\n# Its clauses come later\n") ctx))

(defstep #"dhf/clauses/std\.txt holds the clauses STD:5\.1 and STD:5\.2"
  (fn [ctx]
    (cs/write! (:dir ctx) "dhf/clauses/std.txt" "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
    (assoc ctx :clause-line {"STD:5.2" 2} :description {"STD:5.2" "Requirements analysis"})))

(defstep #"dhf/clauses/std\.txt holds the one clause STD:5\.1"
  (fn [ctx] (cs/write! (:dir ctx) "dhf/clauses/std.txt" "STD:5.1 Development plan\n") ctx))

(defstep #"no document names any clause"
  (fn [ctx] (write-plan! ctx (cs/plan ["# Plan" "We plan the work."]))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and nothing else"
  (fn [ctx] (write-plan! ctx (cs/plan ["# Plan" "We plan the work [[STD:5.1]]."]))))

(defstep #"dhf/documents/plan\.md holds the line (.+)"
  (fn [ctx line] (write-plan! ctx (cs/plan ["# Plan" "" line]))))

(defstep #"dhf/documents/plan\.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding \"\[\[STD:5\.1\]\]\""
  (fn [ctx]
    (write-plan! ctx (cs/plan ["# Plan" "" "- The plan" "" "    It is this document [[STD:5.1]]." "" "The end."]))))

(defstep #"dhf/documents/plan\.md holds, after a blank line, a line of four spaces then \"\[\[STD:5\.1\]\]\""
  (fn [ctx] (write-plan! ctx (cs/plan ["# Plan" "" "    [[STD:5.1]]"]))))

(defstep #"dhf/documents/plan\.md holds \"An open \[\[ tag\", a blank line, and \"STD:5\.1 \]\]\""
  (fn [ctx] (write-plan! ctx (cs/plan ["# Plan" "An open [[ tag" "" "STD:5.1 ]]"]))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 and STD:9\.9 in audit tags"
  (fn [ctx]
    (let [text (cs/plan ["# Plan" "We plan the work [[STD:5.1]]." "" "We also meet [[STD:9.9]]."])]
      (assoc (write-plan! ctx text) :tag-line (line-of text "[[STD:9.9]]")))))

(defstep #"dhf/documents/plan\.md has the title \"Development plan \[\[STD:5\.1\]\]\" and a body that names no clause"
  (fn [ctx]
    (write-plan! ctx (cs/document [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan [[STD:5.1]]"]]
                                  ["# Plan" "We plan the work."]))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx]
    (let [text (cs/plan ["# Plan" "We plan the work [[STD:5.1]]." "" "TODO: write the plan"])]
      (assoc (write-plan! ctx text) :todo-line (line-of text "TODO")))))

(defstep #"dhf/documents/plan\.md has frontmatter with an id and a title but no revision"
  (fn [ctx]
    (write-plan! ctx (cs/document [["id" "PLAN-001"] ["title" "Development plan"]]
                                  ["# Plan" "We plan the work [[STD:5.1]]."]))))

(defstep #"dhf/documents/plan\.md and dhf/documents/old-plan\.md both have the id PLAN-001"
  (fn [ctx]
    (cs/write! (:dir ctx) "dhf/documents/old-plan.md"
               (cs/document [["id" "PLAN-001"] ["revision" "1"] ["title" "Old development plan"]]
                            ["# Old plan" "We planned the work [[STD:5.1]]."]))
    ctx))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (doseq [l (s/err-lines r)]
        (s/check! (not (re-find #"clause|dhf/clauses|dhf/documents|document-" l))
                  (str "a line names a clause, a clause list or a document: " l "\n" (s/show r))))
      nil)))

(defn- unreferenced-keys
  "The keys the clause-unreferenced findings of the run name, as written
  after the rule."
  [r]
  (vec (for [l (s/err-lines r)
             :let [m (re-find #": clause-unreferenced: (\S+)" l)]
             :when m]
         (second m))))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4\.1, STD:5\.1 and STD:5\.2, and none for any other key"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          ks (unreferenced-keys r)]
      (s/check! (= (sort ks) ["STD:4.1" "STD:5.1" "STD:5.2"])
                (str "the clause-unreferenced findings name " (pr-str ks) "\n" (s/show r)))
      (s/check! (not-any? #(re-find #"clause-list" %) (s/err-lines r))
                (str "a line of the clause list is reported as malformed\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and says the clause list dhf/clauses/std\.txt holds no clause"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "dhf/clauses/std.txt")]
      (s/check! (re-find #"holds no clause" (str (:err r))) (str "it does not say the list holds no clause\n" (s/show r)))
      nil)))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std\.txt, the line of STD:5\.2, the key and its description"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          l (cs/line-naming! r "dhf/clauses/std.txt:2: clause-unreferenced: " "STD:5.2" "Requirements analysis")]
      (s/check! (not-any? #(re-find #"clause-unreferenced: STD:5\.1\b" %) (s/err-lines r))
                (str "the named clause STD:5.1 is reported\n" (s/show r)))
      l nil)))

(defstep #"the clause STD:5\.1 is (named|not named)"
  (fn [ctx named]
    (let [r (kc/the-run ctx)
          gap? (some #{"STD:5.1"} (unreferenced-keys r))]
      (if (= "named" named)
        (do (kc/exits! ctx 0) (s/check! (not gap?) (str "STD:5.1 is reported unreferenced\n" (s/show r))))
        (do (kc/exits! ctx 1) (s/check! gap? (str "STD:5.1 is not reported unreferenced\n" (s/show r)))))
      nil)))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan\.md, the line of the tag and STD:9\.9"
  (fn [ctx]
    (cs/line-naming! (kc/the-run ctx) (str plan-rel ":" (:tag-line ctx) ": warning: clause-unknown: ") "STD:9.9")
    nil))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan\.md and the line holding TODO"
  (fn [ctx]
    (cs/line-naming! (kc/the-run ctx) (str plan-rel ":" (:todo-line ctx) ": warning: document-placeholder: "))
    nil))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan\.md and the missing revision"
  (fn [ctx]
    (cs/line-naming! (kc/the-run ctx) (str plan-rel ": document-unidentified: ") "revision")
    nil))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (cs/line-naming! r "PLAN-001" plan-rel "dhf/documents/old-plan.md")
      (s/check! (fs/exists? (fs/file (:dir ctx) plan-rel)) "no plan.md")
      nil)))
