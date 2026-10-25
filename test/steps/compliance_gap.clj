(ns steps.compliance-gap
  "Step definitions for dhf/spec/compliance/gap.feature: gap analysis as a
  stage of `rdm-spec check`, in a regulated project (steps.compliance-support)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- unreferenced-lines
  "The run's clause-unreferenced finding lines."
  [r]
  (filter #(str/includes? % "clause-unreferenced") (s/err-lines r)))

(defn- names-key? [line k]
  (boolean (re-find (re-pattern (str "(?<![A-Za-z0-9._:-])" (java.util.regex.Pattern/quote k)
                                     "(?![A-Za-z0-9_:-]|\\.[A-Za-z0-9])"))
                    line)))

;; --- Without :regulated, nothing of the profile runs ---------------------------

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx]
    (let [ctx (cs/initialised! ctx)]
      (cs/config! (:dir ctx) #(dissoc % :regulated))
      ctx)))

(defstep #"a file dhf/documents/plan\.md that names no clause"
  (fn [ctx]
    (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "We meet STD:5.1 here, and [[STD:5.1]] is only text."))))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (doseq [l (s/err-lines r)]
        (s/check! (not (re-find #"clause|dhf/documents|dhf/clauses|document-" l))
                  (str "a line names the profile: " l "\n" (s/show r))))
      nil)))

;; --- A clause list is read as clause lines, comments and includes ------------

(defstep #"a regulated project"
  (fn [ctx] (cs/regulated! ctx)))

(defstep #"dhf/clauses/std\.txt holds:"
  (fn [ctx text] (cs/write! ctx cs/std (str text "\n"))))

(defstep #"(dhf/clauses/\S+) holds \"([^\"]*)\""
  (fn [ctx rel line] (cs/write! ctx rel (str line "\n"))))

(defstep #"no document names any clause"
  (fn [ctx] (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "This plan names nothing yet."))))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4\.1, STD:5\.1 and STD:5\.2, and none for any other key"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          ls (unreferenced-lines r)]
      (doseq [k ["STD:4.1" "STD:5.1" "STD:5.2"]]
        (s/check! (= 1 (count (filter #(names-key? % k) ls)))
                  (str "not exactly one clause-unreferenced finding names " k "\n" (s/show r))))
      (s/check! (= 3 (count ls)) (str "expected three clause-unreferenced findings\n" (s/show r)))
      (s/check! (some #(and (names-key? % "STD:4.1") (str/includes? % "dhf/clauses/base.txt:1:")) ls)
                (str "STD:4.1 is not named where base.txt holds it\n" (s/show r)))
      nil)))

(defstep #"dhf/clauses/std\.txt holds only comments"
  (fn [ctx] (cs/write! ctx cs/std "# The standard\n# Nothing listed yet\n")))

(defstep #"it exits 2 and says the clause list dhf/clauses/std\.txt holds no clause"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "dhf/clauses/std.txt")]
      (s/check! (str/includes? (:err r) "no clause") (str "it does not say the list holds no clause\n" (s/show r)))
      nil)))

;; --- A clause is met only by an audit tag naming it ---------------------------

(defstep #"dhf/clauses/std\.txt holds the clauses STD:5\.1 and STD:5\.2"
  (fn [ctx]
    (-> (cs/write! ctx cs/std "# The standard\nSTD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
        (assoc :clause-line 3))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and nothing else"
  (fn [ctx] (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "We plan the work [[STD:5.1]]."))))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std\.txt, the line of STD:5\.2, the key and its description"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          l (cs/err-line! r (str "dhf/clauses/std.txt:" (:clause-line ctx) ": clause-unreferenced: ")
                          "STD:5.2" "Requirements analysis")]
      (s/check! (= 1 (count (unreferenced-lines r))) (str "STD:5.1 is reported too: " l))
      nil)))

(defstep #"dhf/clauses/std\.txt holds the one clause STD:5\.1"
  (fn [ctx] (cs/write! ctx cs/std "STD:5.1 Development plan\n")))

(defstep #"dhf/documents/plan\.md holds the line (.+)"
  (fn [ctx line] (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" line))))

(defstep #"the clause (\S+) is named"
  (fn [ctx k]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not-any? #(names-key? % k) (unreferenced-lines r))
                (str k " is reported unreferenced\n" (s/show r)))
      nil)))

(defstep #"the clause (\S+) is not named"
  (fn [ctx k]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(names-key? % k) (unreferenced-lines r))
                (str "no clause-unreferenced finding names " k "\n" (s/show r)))
      nil)))

(defstep #"dhf/documents/plan\.md holds, after a blank line, a line of four spaces then \"\[\[STD:5\.1\]\]\""
  (fn [ctx] (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "An example:" "" "    [[STD:5.1]]"))))

(defstep #"dhf/documents/plan\.md holds \"An open \[\[ tag\", a blank line, and \"STD:5\.1 \]\]\""
  (fn [ctx] (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "An open [[ tag" "" "STD:5.1 ]]"))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 and STD:9\.9 in audit tags"
  (fn [ctx]
    (-> (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "We plan the work [[STD:5.1]]." "" "We also do [[STD:9.9]]."))
        (assoc :tag-line (+ cs/plan-body-start 4)))))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan\.md, the line of the tag and STD:9\.9"
  (fn [ctx]
    (cs/err-line! (kc/the-run ctx) (str "dhf/documents/plan.md:" (:tag-line ctx) ": warning: clause-unknown: ")
                  "STD:9.9")
    nil))

;; --- Frontmatter names no clause -----------------------------------------------

(defstep #"dhf/documents/plan\.md has the title \"Development plan \[\[STD:5\.1\]\]\" and a body that names no clause"
  (fn [ctx]
    (cs/write! ctx cs/plan (cs/document [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan [[STD:5.1]]"]]
                                        "# Plan\n\nThis plan names nothing.\n"))))

;; --- A placeholder is a warning ------------------------------------------------

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx]
    (-> (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "" "We plan the work [[STD:5.1]]." "" "TODO: write the plan"))
        (assoc :todo-line (+ cs/plan-body-start 4)))))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan\.md and the line holding TODO"
  (fn [ctx]
    (cs/err-line! (kc/the-run ctx) (str "dhf/documents/plan.md:" (:todo-line ctx) ": warning: document-placeholder: "))
    nil))

;; --- A controlled document carries its identity -------------------------------

(defstep #"dhf/documents/plan\.md has frontmatter with an id and a title but no revision"
  (fn [ctx]
    (cs/write! ctx cs/plan (cs/document [["id" "PLAN-001"] ["title" "Development plan"]]
                                        "# Plan\n\nWe plan the work [[STD:5.1]].\n"))))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan\.md and the missing revision"
  (fn [ctx]
    (let [l (cs/err-line! (kc/the-run ctx) "dhf/documents/plan.md" "document-unidentified" "revision")]
      (s/check! (not (re-find #"\bid\b|title" (str/replace l #"^.*document-unidentified: " "")))
                (str "it names more than the revision as missing: " l))
      nil)))

(defstep #"dhf/documents/plan\.md and dhf/documents/old-plan\.md both have the id PLAN-001"
  (fn [ctx]
    (-> ctx
        (cs/write! cs/plan (cs/plan-doc "# Plan" "" "We plan the work [[STD:5.1]]."))
        (cs/write! "dhf/documents/old-plan.md"
                   (cs/document [["id" "PLAN-001"] ["revision" "4"] ["title" "Old plan"]] "The old plan.\n")))))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (cs/err-line! r "PLAN-001" "dhf/documents/plan.md" "dhf/documents/old-plan.md")
      nil)))
