(ns steps.compliance-gap
  "Step definitions of dhf/spec/compliance/gap.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def std "dhf/clauses/std.txt")
(def plan "dhf/documents/plan.md")

(defn write! [ctx files] (k/write-files! (:dir ctx) files) ctx)

(defn plan-doc [body] (k/doc-text {:id "PLAN-001" :revision "1" :title "Development plan"} body))

(defn line-of [ctx path s]
  (let [ls (str/split-lines (slurp (fs/file (k/guard! (:dir ctx)) path)))]
    (inc (or (first (keep-indexed (fn [i l] (when (str/includes? l s) i)) ls)) (fail! (str s " is not in " path))))))

(defn unreferenced [ctx] (filter #(str/includes? % "clause-unreferenced") (k/err-lines ctx)))

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx] (k/init-project! ctx)))

(defstep #"a file dhf/documents/plan.md that names no clause"
  (fn [ctx] (write! ctx {plan "# A plan\n\nWe plan STD:5.1 in passing.\n"})))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (re-find #"(?i)clause|document|STD:" (:err ctx))) (:err ctx))
    ctx))

(defstep #"a regulated project"
  (fn [ctx] (k/regulated! ctx)))

(defstep #"dhf/clauses/std.txt holds:"
  (fn [ctx doc] (write! ctx {std doc})))

(defstep #"dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [ctx] (write! ctx {"dhf/clauses/base.txt" "STD:4.1 Quality system\n"})))

(defstep #"no document names any clause"
  (fn [ctx] (write! ctx {plan (plan-doc "# Development plan\n\nNothing named here.\n")})))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4.1, STD:5.1 and STD:5.2, and none for any other key"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [ls (unreferenced ctx)]
      (assert (= 3 (count ls)) (:err ctx))
      (doseq [key ["STD:4.1" "STD:5.1" "STD:5.2"]] (assert (some #(str/includes? % key) ls) key)))
    ctx))

(defstep #"dhf/clauses/std.txt holds only comments"
  (fn [ctx] (write! ctx {std "# A list\n# with no clause\n"})))

(defstep #"it exits 2 and says the clause list dhf/clauses/std.txt holds no clause"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx std "holds no clause")))

(defstep #"dhf/clauses/std.txt holds \"include missing.txt\""
  (fn [ctx] (write! ctx {std "include missing.txt\n"})))

(defstep #"it exits 2 and names dhf/clauses/missing.txt"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "dhf/clauses/missing.txt")))

(defstep #"dhf/clauses/std.txt holds \"STD:5.1 Development plan\" and the line \"Quality system\""
  (fn [ctx] (write! ctx {std "STD:5.1 Development plan\nQuality system\n"})))

(defstep #"it exits 1 with a finding naming dhf/clauses/std.txt and the line of \"Quality system\", and STD:5.1 is the only clause"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx (str std ":2"))
    (assert (empty? (unreferenced ctx)) (:err ctx))
    (assert (not (str/includes? (:err ctx) "Quality:")))
    ctx))

(defstep #"dhf/clauses/std.txt holds the clauses STD:5.1 and STD:5.2"
  (fn [ctx] (write! ctx {std "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"})))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and nothing else"
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\nWe plan [[STD:5.1]].\n")})))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std.txt, the line of STD:5.2, the key and its description"
  (fn [ctx] (k/err-line-with ctx (str std ":2: clause-unreferenced") "STD:5.2" "Requirements analysis")))

(defstep #"dhf/clauses/std.txt holds the one clause STD:5.1"
  (fn [ctx] (write! ctx {std "STD:5.1 Development plan\n"})))

(defstep #"dhf/documents/plan.md holds the line (.+)"
  (fn [ctx line] (write! ctx {plan (plan-doc (str "# Plan\n\n" line "\n"))})))

(defstep #"the clause STD:5.1 is (named|not named)"
  (fn [ctx named]
    (assert (#{0 1} (:exit ctx)) (:err ctx))
    (let [u (some #(str/includes? % "STD:5.1") (unreferenced ctx))]
      (if (= named "named")
        (do (assert (not u) (:err ctx)) (k/check-exit ctx 0))
        (do (assert u (:err ctx)) (k/check-exit ctx 1))))
    ctx))

(defstep #"dhf/documents/plan.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding \"\[\[STD:5.1\]\]\""
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\n- The plan\n\n    Its continuation [[STD:5.1]].\n")})))

(defstep #"dhf/documents/plan.md holds, after a blank line, a line of four spaces then \"\[\[STD:5.1\]\]\""
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\nSome text.\n\n    [[STD:5.1]]\n")})))

(defstep #"dhf/documents/plan.md holds \"An open \[\[ tag\", a blank line, and \"STD:5.1 \]\]\""
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\nAn open [[ tag\n\nSTD:5.1 ]]\n")})))

(defstep #"dhf/documents/plan.md names STD:5.1 and STD:9.9 in audit tags"
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\nWe plan [[STD:5.1]].\nAnd more [[STD:9.9]].\n")})))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan.md, the line of the tag and STD:9.9"
  (fn [ctx] (k/err-line-with ctx (str plan ":" (line-of ctx plan "STD:9.9")) "warning: clause-unknown" "STD:9.9")))

(defstep #"dhf/documents/plan.md has the title \"Development plan \[\[STD:5.1\]\]\" and a body that names no clause"
  (fn [ctx] (write! ctx {plan (k/doc-text {:id "PLAN-001" :revision "1" :title "Development plan [[STD:5.1]]"} "# Plan\n\nNothing.\n")})))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx] (write! ctx {plan (plan-doc "# Plan\n\nWe plan [[STD:5.1]].\n\nTODO: write the plan\n")})))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan.md and the line holding TODO"
  (fn [ctx] (k/err-line-with ctx (str plan ":" (line-of ctx plan "TODO")) "warning: document-placeholder")))

(defstep #"dhf/documents/plan.md has frontmatter with an id and a title but no revision"
  (fn [ctx] (write! ctx {plan (k/doc-text {:id "PLAN-001" :title "Plan"} "# Plan\n\nWe plan [[STD:5.1]].\n")})))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan.md and the missing revision"
  (fn [ctx] (k/err-line-with ctx plan "document-unidentified" "revision")))

(defstep #"dhf/documents/plan.md and dhf/documents/old-plan.md both have the id PLAN-001"
  (fn [ctx] (write! ctx {"dhf/documents/old-plan.md" (plan-doc "# Old\n\nOld [[STD:5.1]].\n")})))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "PLAN-001" "dhf/documents/old-plan.md" "dhf/documents/plan.md")))
