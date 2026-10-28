(ns steps.compliance-gap
  "Steps of the component Gap analysis (dhf/spec/10-regulated-profile/gap.feature),
  and the regulated project the compliance context's steps share."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep doc-string]]
            [steps.kernel-shared :as k]))

(def regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(defn doc-text
  "A controlled document: frontmatter FM (id, revision and title by default) and BODY."
  [body & [fm]]
  (let [fm (merge {"id" "PLAN-001" "revision" "1" "title" "Development plan"} fm)]
    (str "---\n" (str/join (for [[k v] fm :when v] (str k ": " v "\n"))) "---\n\n" body)))

(defn regulated-project!
  "A project set up by rdm-spec whose configuration holds :regulated with the
  one clause list dhf/clauses/std.txt and the documents directory dhf/documents."
  [ctx & [opts]]
  (let [ctx (k/spec-project! ctx opts)]
    (k/config! ctx assoc :regulated regulated)
    (k/write! ctx "dhf/clauses/std.txt" "# The standard\nSTD:5.1 Development plan\n")
    (k/write! ctx "dhf/documents/plan.md" (doc-text "# Plan\n\nWe plan the work [[STD:5.1]].\n"))
    ctx))

(defn clauses! [ctx text] (k/write! ctx "dhf/clauses/std.txt" text))
(defn plan! [ctx body & [fm]] (k/write! ctx "dhf/documents/plan.md" (doc-text body fm)))

(defn findings [ctx] (remove #(re-find #"(^|: )warning: " %) (k/err-lines ctx)))

(defn unreferenced [ctx] (filter #(str/includes? % "clause-unreferenced") (k/err-lines ctx)))

(defn named! [ctx named?]
  (if named?
    (do (k/exits! ctx 0) (k/check! (empty? (unreferenced ctx)) "STD:5.1 is not named: " (k/shows ctx)))
    (do (k/exits! ctx 1) (k/check! (some #(str/includes? % "STD:5.1") (unreferenced ctx)) "STD:5.1 is named: " (k/shows ctx))))
  ctx)

(defstep #"a regulated project" (fn [ctx] (regulated-project! ctx)))

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx] (k/spec-project! ctx)))

(defstep #"a file dhf/documents/plan.md that names no clause"
  (fn [ctx] (k/write! ctx "dhf/documents/plan.md" "# Plan\n\nWe plan STD:5.1, without a tag.\n")))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(re-find #"clause|dhf/documents|document-" %) (k/err-lines ctx)) "a line names the profile: " (k/shows ctx))
    ctx))

(defstep #"dhf/clauses/std.txt holds:" (fn [ctx] (clauses! ctx (doc-string))))

(defstep #"dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [ctx] (k/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")))

(defstep #"no document names any clause"
  (fn [ctx] (plan! ctx "# Plan\n\nNothing named here.\n")))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4.1, STD:5.1 and STD:5.2, and none for any other key"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [us (unreferenced ctx)]
      (k/check! (= 3 (count us)) "the clause-unreferenced findings are " (pr-str us))
      (doseq [key ["STD:4.1" "STD:5.1" "STD:5.2"]] (k/check! (some #(str/includes? % key) us) "no finding names " key)))
    ctx))

(defstep #"dhf/clauses/std.txt holds only comments"
  (fn [ctx] (clauses! ctx "# The standard\n# nothing yet\n")))

(defstep #"it exits 2 and says the clause list dhf/clauses/std.txt holds no clause"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "the clause list dhf/clauses/std.txt holds no clause") ctx))

(defstep #"dhf/clauses/std.txt holds \"include missing.txt\""
  (fn [ctx] (clauses! ctx "include missing.txt\n")))

(defstep #"it exits 2 and names dhf/clauses/missing.txt"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/clauses/missing.txt") ctx))

(defstep #"dhf/clauses/std.txt holds \"STD:5.1 Development plan\" and the line \"Quality system\""
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\nQuality system\n")))

(defstep #"it exits 1 with a finding naming dhf/clauses/std.txt and the line of \"Quality system\", and STD:5.1 is the only clause"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (= 1 (count (findings ctx))) "the findings are " (pr-str (findings ctx)))
    (k/err-line-has! ctx "dhf/clauses/std.txt:2:")
    ctx))

(defstep #"dhf/clauses/std.txt holds the clauses STD:5.1 and STD:5.2"
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and nothing else"
  (fn [ctx] (plan! ctx "# Plan\n\nWe plan [[STD:5.1]].\n")))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std.txt, the line of STD:5.2, the key and its description"
  (fn [ctx] (k/err-line-has! ctx "dhf/clauses/std.txt:2:" "clause-unreferenced" "STD:5.2" "Requirements analysis") ctx))

(defstep #"dhf/clauses/std.txt holds the one clause STD:5.1"
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\n")))

(defstep #"dhf/documents/plan.md holds the line (.+)"
  (fn [ctx line] (plan! ctx (str "# Plan\n\n" line "\n"))))

(defstep #"the clause STD:5.1 is (named|not named)"
  (fn [ctx named] (named! ctx (= named "named"))))

(defstep #"dhf/documents/plan.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding \"\[\[STD:5.1\]\]\""
  (fn [ctx] (plan! ctx "# Plan\n\n- The plan\n\n    This item meets [[STD:5.1]].\n")))

(defstep #"dhf/documents/plan.md holds, after a blank line, a line of four spaces then \"\[\[STD:5.1\]\]\""
  (fn [ctx] (plan! ctx "# Plan\n\nSome text.\n\n    [[STD:5.1]]\n")))

(defstep #"dhf/documents/plan.md holds \"An open \[\[ tag\", a blank line, and \"STD:5.1 \]\]\""
  (fn [ctx] (plan! ctx "# Plan\n\nAn open [[ tag\n\nSTD:5.1 ]]\n")))

(defstep #"dhf/documents/plan.md names STD:5.1 and STD:9.9 in audit tags"
  (fn [ctx] (plan! ctx "# Plan\n\nWe plan [[STD:5.1]].\nWe also do [[STD:9.9]].\n")))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan.md, the line of the tag and STD:9.9"
  (fn [ctx] (k/err-line-has! ctx "dhf/documents/plan.md:10:" "warning: clause-unknown" "STD:9.9") ctx))

(defstep #"dhf/documents/plan.md has the title \"Development plan \[\[STD:5.1\]\]\" and a body that names no clause"
  (fn [ctx] (plan! ctx "# Plan\n\nNothing here.\n" {"title" "Development plan [[STD:5.1]]"})))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx] (plan! ctx "# Plan\n\nWe plan [[STD:5.1]].\n\nTODO: write the plan\n")))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan.md and the line holding TODO"
  (fn [ctx] (k/err-line-has! ctx "dhf/documents/plan.md:11:" "warning: document-placeholder") ctx))

(defstep #"dhf/documents/plan.md has frontmatter with an id and a title but no revision"
  (fn [ctx] (plan! ctx "# Plan\n\nWe plan [[STD:5.1]].\n" {"revision" nil})))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan.md and the missing revision"
  (fn [ctx] (k/err-line-has! ctx "dhf/documents/plan.md" "document-unidentified" "revision") ctx))

(defstep #"dhf/documents/plan.md and dhf/documents/old-plan.md both have the id PLAN-001"
  (fn [ctx] (k/write! ctx "dhf/documents/old-plan.md" (doc-text "# Old\n\nWe planned [[STD:5.1]].\n" {"title" "Old plan"}))))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "PLAN-001" "dhf/documents/plan.md" "dhf/documents/old-plan.md") ctx))
