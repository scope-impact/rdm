(ns steps.compliance-gap
  "Step definitions for dhf/spec/compliance/gap.feature: `rdm-spec check` on
  a regulated project the scenario makes (steps.compliance-support). Also
  the compliance context's steps audit.feature shares: \"a project set up by
  rdm-spec whose configuration holds no :regulated\".

  Context keys: :dir, :placeholders OUT/OUT2, :line (a line of a file the
  step wrote)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as c]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- line-of
  "The number of the first line of `text` holding `part`."
  [text part]
  (some (fn [[i l]] (when (str/includes? l part) (inc i))) (map-indexed vector (str/split-lines text))))

(defn- finding-with
  "The run's finding lines holding every one of `parts`."
  [ctx & parts]
  (filter (fn [l] (every? #(str/includes? l %) parts)) (kc/finding-lines (kc/the-run ctx))))

(defn- warning-lines [ctx] (filter kc/warning-line? (s/err-lines (kc/the-run ctx))))

(defn- unreferenced [ctx]
  (vec (keep #(second (re-find #": clause-unreferenced: .*?([A-Z]+:[0-9][0-9A-Za-z._:-]*)" %))
             (kc/finding-lines (kc/the-run ctx)))))

;; ---------------------------------------------------------------------------
;; Without :regulated

;; Shared within compliance (gap.feature, audit.feature). Sets :dir, :placeholders OUT/OUT2.
(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx] (c/unregulated! ctx)))

(defstep #"a file dhf/documents/plan\.md that names no clause"
  (fn [ctx] (c/write! ctx c/plan-rel "# Plan\n\nWe plan the work. STD:5.1 is mentioned in passing.\n")))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not-any? #(re-find #"(?i)clause|dhf/documents|STD:" %) (s/err-lines r))
                (str "a line names a clause, a clause list or a document\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Clause lists

(defstep #"a regulated project"
  (fn [ctx] (c/regulated! ctx)))

(defstep #"dhf/clauses/std\.txt holds:"
  (fn [ctx text] (c/write! ctx c/std-rel (str text "\n"))))

(defstep #"dhf/clauses/base\.txt holds \"([^\"]*)\""
  (fn [ctx text] (c/write! ctx "dhf/clauses/base.txt" (str text "\n"))))

(defstep #"no document names any clause"
  (fn [ctx] (c/write! ctx c/plan-rel (c/document "PLAN-001" "Development plan" "We plan the work.\n"))))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4\.1, STD:5\.1 and STD:5\.2, and none for any other key"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          keys (unreferenced ctx)]
      (s/check! (= ["STD:4.1" "STD:5.1" "STD:5.2"] (sort keys)) (str "the clause-unreferenced findings name " (pr-str keys) "\n" (s/show r)))
      nil)))

(defstep #"dhf/clauses/std\.txt holds only comments"
  (fn [ctx] (c/write! ctx c/std-rel "# The standard's clauses\n# none yet\n")))

(defstep #"it exits 2 and says the clause list dhf/clauses/std\.txt holds no clause"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx c/std-rel)]
      (s/check! (some #(str/includes? % (str "the clause list " c/std-rel " holds no clause")) (kc/cannot-run-lines r))
                (str "it does not say the clause list holds no clause\n" (s/show r)))
      nil)))

(defstep #"dhf/clauses/std\.txt holds \"([^\"]*)\""
  (fn [ctx text] (c/write! ctx c/std-rel (str text "\n"))))

(defstep #"dhf/clauses/std\.txt holds \"STD:5\.1 Development plan\" and the line \"Quality system\""
  (fn [ctx]
    (let [text "STD:5.1 Development plan\nQuality system\n"]
      (assoc (c/write! ctx c/std-rel text) :line (line-of text "Quality system")))))

(defstep #"it exits 1 with a finding naming dhf/clauses/std\.txt and the line of \"Quality system\", and STD:5\.1 is the only clause"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          at (str c/std-rel ":" (:line ctx) ": ")
          out (kc/stdout-edn r)]
      (s/check! (some #(str/starts-with? % at) (kc/finding-lines r)) (str "no finding names " at "\n" (s/show r)))
      (s/check! (= ["STD:5.1"] (get-in out [:gap :clauses])) (str "the clauses are " (pr-str (get-in out [:gap :clauses]))))
      (s/check! (empty? (unreferenced ctx)) (str "a clause is unreferenced: " (pr-str (unreferenced ctx))))
      nil)))

;; ---------------------------------------------------------------------------
;; What names a clause

(defstep #"dhf/clauses/std\.txt holds the clauses STD:5\.1 and STD:5\.2"
  (fn [ctx]
    (let [text "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"]
      (assoc (c/write! ctx c/std-rel text) :line (line-of text "STD:5.2")))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and nothing else"
  (fn [ctx] (c/write! ctx c/plan-rel (c/document "PLAN-001" "Development plan" "We plan the work [[STD:5.1]].\n"))))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std\.txt, the line of STD:5\.2, the key and its description"
  (fn [ctx]
    (s/check! (seq (filter #(str/starts-with? % (str c/std-rel ":" (:line ctx) ": clause-unreferenced: "))
                           (finding-with ctx "STD:5.2" "Requirements analysis")))
              (str "no such finding\n" (s/show (kc/the-run ctx))))
    (s/check! (= ["STD:5.2"] (unreferenced ctx)) (str "the unreferenced clauses are " (pr-str (unreferenced ctx))))
    nil))

(defstep #"dhf/clauses/std\.txt holds the one clause STD:5\.1"
  (fn [ctx] (c/write! ctx c/std-rel "STD:5.1 Development plan\n")))

(defn- plan-body! [ctx body]
  (c/write! ctx c/plan-rel (c/document "PLAN-001" "Development plan" body)))

(defstep #"dhf/documents/plan\.md holds the line (.+)"
  (fn [ctx line] (plan-body! ctx (str "Some text before.\n\n" line "\n"))))

(defstep #"the clause STD:5\.1 is (named|not named)"
  (fn [ctx named]
    (let [r (kc/the-run ctx)
          un (unreferenced ctx)]
      (if (= "named" named)
        (do (s/check! (not (some #{"STD:5.1"} un)) (str "STD:5.1 is not named\n" (s/show r)))
            (kc/exits! ctx 0))
        (do (kc/exits! ctx 1)
            (s/check! (some #{"STD:5.1"} un) (str "STD:5.1 is named\n" (s/show r)))))
      nil)))

(defstep #"dhf/documents/plan\.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding \"\[\[STD:5\.1\]\]\""
  (fn [ctx] (plan-body! ctx "Before the list.\n\n- The plan\n\n    The item goes on [[STD:5.1]].\n")))

(defstep #"dhf/documents/plan\.md holds, after a blank line, a line of four spaces then \"\[\[STD:5\.1\]\]\""
  (fn [ctx] (plan-body! ctx "A paragraph.\n\n    [[STD:5.1]]\n")))

(defstep #"dhf/documents/plan\.md holds \"An open \[\[ tag\", a blank line, and \"STD:5\.1 \]\]\""
  (fn [ctx] (plan-body! ctx "An open [[ tag\n\nSTD:5.1 ]]\n")))

(defstep #"dhf/documents/plan\.md names STD:5\.1 and STD:9\.9 in audit tags"
  (fn [ctx]
    (let [body "We plan the work [[STD:5.1]].\n\nWe also meet [[STD:9.9]].\n"]
      (assoc (plan-body! ctx body) :line (+ 7 (line-of body "STD:9.9"))))))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan\.md, the line of the tag and STD:9\.9"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          at (str c/plan-rel ":" (:line ctx) ": warning: clause-unknown: ")]
      (s/check! (some #(and (str/starts-with? % at) (str/includes? % "STD:9.9")) (warning-lines ctx))
                (str "no warning starts " at "\n" (s/show r)))
      nil)))

(defstep #"dhf/documents/plan\.md has the title \"Development plan \[\[STD:5\.1\]\]\" and a body that names no clause"
  (fn [ctx] (c/write! ctx c/plan-rel "---\nid: PLAN-001\nrevision: 1\ntitle: Development plan [[STD:5.1]]\n---\n\n# Development plan\n\nWe plan the work.\n")))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx]
    (let [body "We plan the work [[STD:5.1]].\n\nTODO: write the plan\n"]
      (assoc (plan-body! ctx body) :line (+ 7 (line-of body "TODO"))))))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan\.md and the line holding TODO"
  (fn [ctx]
    (let [at (str c/plan-rel ":" (:line ctx) ": warning: document-placeholder: ")]
      (s/check! (some #(str/starts-with? % at) (warning-lines ctx))
                (str "no warning starts " at "\n" (s/show (kc/the-run ctx))))
      nil)))

;; ---------------------------------------------------------------------------
;; Controlled documents

(defstep #"dhf/documents/plan\.md has frontmatter with an id and a title but no revision"
  (fn [ctx] (c/write! ctx c/plan-rel "---\nid: PLAN-001\ntitle: Development plan\n---\n\nWe plan the work [[STD:5.1]].\n")))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan\.md and the missing revision"
  (fn [ctx]
    (s/check! (seq (filter #(and (str/starts-with? % c/plan-rel) (str/includes? % ": document-unidentified: "))
                           (finding-with ctx "revision")))
              (str "no such finding\n" (s/show (kc/the-run ctx))))
    nil))

(defstep #"dhf/documents/plan\.md and dhf/documents/old-plan\.md both have the id PLAN-001"
  (fn [ctx] (c/write! ctx "dhf/documents/old-plan.md" (c/document "PLAN-001" "Old plan" "We planned [[STD:5.1]].\n"))))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (seq (finding-with ctx "PLAN-001" "dhf/documents/plan.md" "dhf/documents/old-plan.md"))
              (str "no finding names PLAN-001 and both files\n" (s/show (kc/the-run ctx))))
    nil))
