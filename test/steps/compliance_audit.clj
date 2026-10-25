(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` in a releasable regulated project (steps.compliance-support)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private plan-rel "dhf/documents/plan.md")

(defn- out-text [ctx rel]
  (let [f (cs/out-file ctx "OUT" rel)]
    (s/check! (fs/regular-file? f) (str "OUT/" rel " does not exist\n" (s/show (kc/the-run ctx))))
    (slurp (str f))))

(defn- lines [text] (str/split-lines text))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defn- files-under [dir]
  (vec (sort (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
               (str/replace (str (fs/relativize dir f)) "\\" "/")))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx]
    (cs/releasable! ctx {:results [["spec-0001" "failed"] ["spec-0002" "passed"]]})))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (assoc (cs/releasable! ctx {:clauses {"dhf/clauses/std.txt" "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"}})
           :clause "STD:5.2")))

(defstep #"a releasable regulated project"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"a releasable regulated project, all committed"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)
          f (cs/out-file ctx "OUT" "kept.txt")]
      (fs/create-dirs (fs/parent f))
      (spit (str f) "kept\n")
      (assoc ctx :kept-file (str f)))))

;; What plan.md holds, and what its engineer's copy should hold.
(def ^:private written
  {"the line \"We plan the work [[STD:5.1]].\""
   {:body ["# Plan" "We plan the work [[STD:5.1]]."]
    :engineer ["# Plan" "We plan the work."]}
   "the line \"We plan [[STD:5.1]].\""
   {:body ["# Plan" "We plan [[STD:5.1]]."]
    :engineer ["# Plan" "We plan."]}
   "the line \"We plan [[STD:5.1]] and test [[STD:5.2]].\""
   {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"
    :body ["# Plan" "We plan [[STD:5.1]] and test [[STD:5.2]]."]
    :engineer ["# Plan" "We plan and test."]}
   "a tag that opens on one line and closes on the next"
   {:body ["# Plan" "We plan the work [[STD:5.1" "is met here]] every day."]
    :engineer ["# Plan" "We plan the work" " every day."]}
   "the line \"We plan `[[STD:5.1]]`.\""
   {:body ["# Plan" "We plan the work [[STD:5.1]]." "" "We plan `[[STD:5.1]]`."]
    :engineer ["# Plan" "We plan the work." "" "We plan `[[STD:5.1]]`."]}
   "a \"[[\" with no \"]]\" before the paragraph's blank line"
   {:body ["# Plan" "We plan the work [[STD:5.1]]." "" "An open [[ tag" "that never closes." "" "Then ]] a close."]
    :engineer ["# Plan" "We plan the work." "" "An open [[ tag" "that never closes." "" "Then ]] a close."]}
   "a fenced code block holding [[STD:5.1]]"
   {:body ["# Plan" "We plan the work [[STD:5.1]]." "" "```" "x = \"[[STD:5.1]]\"" "```"]
    :engineer ["# Plan" "We plan the work." "" "```" "x = \"[[STD:5.1]]\"" "```"]}
   "an indented code block holding [[STD:5.1]]"
   {:body ["# Plan" "We plan the work [[STD:5.1]]." "" "    x = \"[[STD:5.1]]\"" "    y = 2"]
    :engineer ["# Plan" "We plan the work." "" "    x = \"[[STD:5.1]]\"" "    y = 2"]}
   "the title \"Development plan [[STD:5.1]]\" in its frontmatter"
   {:title "Development plan [[STD:5.1]]"
    :body ["# Plan" "We plan the work [[STD:5.1]]."]
    :engineer-title "Development plan"
    :engineer ["# Plan" "We plan the work."]}})

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (let [{:keys [clauses body title engineer engineer-title] :as w}
          (or (get written what) (s/fail! (str "no fixture for " what)))
          front (fn [t] [["id" "PLAN-001"] ["revision" "1"] ["title" t]])
          text (cs/document (front (or title "Development plan")) body)]
      (-> (cs/releasable! ctx (cond-> {:documents {plan-rel text}}
                                clauses (assoc :clauses {"dhf/clauses/std.txt" clauses})))
          (assoc :written w
                 :auditor-text text
                 :engineer-text (cs/document (front (or engineer-title title "Development plan")) engineer))))))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (cs/releasable! ctx {:documents {"dhf/documents/risk/register.md"
                                     (cs/document [["id" "RISK-001"] ["revision" "2"] ["title" "Risk register"]]
                                                  ["# Risks" "Each risk is controlled [[STD:5.1]]."])}})))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (cs/releasable! ctx {:documents {plan-rel (cs/document [["id" "PLAN-001"] ["revision" "3"] ["title" "Development plan"]]
                                                           ["# Plan" "We plan the work [[STD:5.1]]."])}})))

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"include base\.txt\" then \"STD:5\.1 Development plan\", and whose dhf/clauses/base\.txt holds \"STD:4\.1 Quality system\""
  (fn [ctx]
    (assoc ctx :pending-clauses {"dhf/clauses/std.txt" "include base.txt\nSTD:5.1 Development plan\n"
                                 "dhf/clauses/base.txt" "STD:4.1 Quality system\n"})))

(defstep #"STD:5\.1 is named on line 8 of dhf/documents/plan\.md and line 9 of dhf/documents/risk/register\.md"
  (fn [ctx]
    (s/check! (:pending-clauses ctx) "no clause list was given")
    (cs/releasable! (dissoc ctx :pending-clauses)
                    {:clauses (:pending-clauses ctx)
                     :documents {plan-rel (cs/plan ["# Plan" "We plan the work [[STD:5.1]]." ""
                                                    "The quality system holds [[STD:4.1]]."])
                                 "dhf/documents/risk/register.md"
                                 (cs/document [["id" "RISK-001"] ["revision" "2"] ["title" "Risk register"]]
                                              ["# Risks" "Each risk is listed." "Each risk is controlled [[STD:5.1]]."])}})))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (cs/releasable! ctx)))

;; The changes made after the commit

(defn- append! [dir rel text]
  (spit (str (fs/file dir rel)) text :append true))

(defstep #"an untracked document dhf/documents/draft\.md"
  (fn [ctx]
    (cs/write! (:dir ctx) "dhf/documents/draft.md"
               (cs/document [["id" "DRAFT-001"] ["revision" "1"] ["title" "Draft"]] ["# Draft" "A draft [[STD:5.1]]."]))
    (assoc ctx :changed "dhf/documents/draft.md")))

(defstep #"an uncommitted change to a document"
  (fn [ctx]
    (append! (:dir ctx) plan-rel "\nWe also review the plan [[STD:5.1]].\n")
    (assoc ctx :changed plan-rel)))

(defstep #"an uncommitted change to a scenario's title"
  (fn [ctx]
    (let [rel "dhf/spec/demo/plan.feature"
          f (fs/file (:dir ctx) rel)]
      (spit (str f) (str/replace (slurp (str f)) "Scenario: A plan is reviewed" "Scenario: A plan is reviewed twice"))
      (assoc ctx :changed rel))))

(defstep #"an uncommitted change to the clause list"
  (fn [ctx]
    (append! (:dir ctx) "dhf/clauses/std.txt" "# a comment added\n")
    (assoc ctx :changed "dhf/clauses/std.txt")))

;; ---------------------------------------------------------------------------
;; Whens

(defstep #"\"rdm-spec audit OUT\" is run, then \"rdm-spec audit OUT2\""
  (fn [ctx]
    (let [ctx (kc/run-line ctx "audit OUT")
          first-run (:run ctx)
          ctx (kc/run-line ctx "audit OUT2")]
      (assoc ctx :first-run first-run))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])
          want (s/err-lines release)]
      (s/check! (= 1 (:exit release)) (str "the release check passed\n" (s/show release)))
      (s/check! (seq want) "the release check printed no finding")
      (doseq [l want]
        (s/check! (some #{l} (s/err-lines r)) (str "the release check's line is not printed unchanged: " l "\n" (s/show r))))
      (cs/creates-no-out! ctx)
      nil)))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (cs/line-naming! r (:clause ctx))
      (cs/creates-no-out! ctx)
      nil)))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (kc/refused-naming! ctx ":regulated")
    (cs/creates-no-out! ctx)
    nil))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx (get-in ctx [:placeholders "OUT"]))
    (s/check! (= "kept\n" (slurp (:kept-file ctx))) "the file in OUT changed")
    (s/check! (= ["kept.txt"] (files-under (get-in ctx [:placeholders "OUT"]))) "OUT was written into")
    nil))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx _file]
    (let [r (kc/exits! ctx 1)]
      (cs/line-naming! r (:changed ctx) "not committed")
      (cs/creates-no-out! ctx)
      nil)))

(defstep #"OUT/(auditor|engineer)/plan\.md holds (.+)"
  (fn [ctx copy what]
    (let [got (out-text ctx (str copy "/plan.md"))
          {:keys [engineer]} (:written ctx)]
      (cond
        (= "auditor" copy)
        (let [[_ l] (re-matches #"the line \"(.*)\"" what)]
          (s/check! (some #{l} (lines got)) (str "OUT/auditor/plan.md holds no line " (pr-str l) ":\n" got)))

        (str/starts-with? what "the line \"")
        (let [[_ l] (re-matches #"the line \"(.*)\"" what)]
          (s/check! (some #{l} (lines got)) (str "OUT/engineer/plan.md holds no line " (pr-str l) ":\n" got))))
      (when (= "engineer" copy)
        (s/check! engineer "no engineer's copy is expected")
        (s/check! (= (:engineer-text ctx) got)
                  (str "OUT/engineer/plan.md holds\n" got "\nexpected\n" (:engineer-text ctx)))
        (s/check! (= (count (lines (:auditor-text ctx))) (count (lines got)))
                  "the engineer's copy does not keep every line's number"))
      nil)))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (lines (out-text ctx "auditor/plan.md"))
          e (lines (out-text ctx "engineer/plan.md"))]
      (s/check! (= (count a) (count e)) "the two copies have different numbers of lines")
      (let [diff (remove (fn [[x y]] (= x y)) (map vector a e))]
        (s/check! (= [["We plan the work [[STD:5.1]]." "We plan the work."]] diff)
                  (str "the copies differ in " (pr-str diff))))
      (s/check! (= (:auditor-text ctx) (str/join "\n" (concat a [""])))
                "the auditor's copy is not the document"))
    nil))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [c ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (s/check! (fs/regular-file? (cs/out-file ctx "OUT" (str c "/" f))) (str "no OUT/" c "/" f)))
    nil))

(defn- pdf-text [f & [first-page?]]
  (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                 (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " f ": " (:err r)))
    (:out r)))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [c ["auditor" "engineer"]]
      (s/check! (fs/regular-file? (cs/out-file ctx "OUT" (str c "/plan.pdf"))) (str "no OUT/" c "/plan.pdf")))
    nil))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [c ["auditor" "engineer"]
            :let [t (pdf-text (cs/out-file ctx "OUT" (str c "/plan.pdf")) true)]]
      (doseq [want ["PLAN-001" "Development plan"]]
        (s/check! (str/includes? t want) (str "the first page of OUT/" c "/plan.pdf does not hold " want ":\n" t)))
      (s/check! (re-find #"(?i)revision\W+3\b" t) (str "the first page of OUT/" c "/plan.pdf does not hold the revision 3:\n" t)))
    nil))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"\[\[\""
  (fn [ctx]
    (let [t (pdf-text (cs/out-file ctx "OUT" "engineer/plan.pdf"))]
      (s/check! (not (str/includes? t "[[")) (str "OUT/engineer/plan.pdf holds [[:\n" t)))
    nil))

(defn- sections
  "{heading [lines]} of the level-2 sections of `text`, and their order."
  [text]
  (reduce (fn [acc l]
            (if-let [[_ h] (re-matches #"##\s+(.*?)\s*" l)]
              (-> acc (update :order conj h) (assoc :current h))
              (if-let [h (:current acc)] (update-in acc [:by h] (fnil conj []) l) acc)))
          {:order [] :by {}}
          (lines text)))

(defstep #"OUT/clauses\.md has one section per declared clause list, dhf/clauses/std\.txt, and no section for dhf/clauses/base\.txt"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [{:keys [order]} (sections (out-text ctx "clauses.md"))]
      (s/check! (= 1 (count order)) (str "clauses.md has the sections " (pr-str order)))
      (s/check! (str/includes? (first order) "dhf/clauses/std.txt") (str "the section is " (pr-str (first order))))
      (s/check! (not-any? #(str/includes? % "base.txt") order) "a section names base.txt"))
    nil))

(defstep #"under it STD:4\.1 comes first, where its include stands, then a row for STD:5\.1 with \"Development plan\", dhf/documents/plan\.md:8 and dhf/documents/risk/register\.md:9"
  (fn [ctx]
    (let [{:keys [order by]} (sections (out-text ctx "clauses.md"))
          rows (filter #(re-find #"STD:\d" %) (get by (first order)))]
      (s/check! (= 2 (count rows)) (str "the section's clause rows are " (pr-str rows)))
      (s/check! (str/includes? (first rows) "STD:4.1") (str "the first row is " (first rows)))
      (doseq [want ["STD:5.1" "Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]]
        (s/check! (str/includes? (second rows) want) (str "the row of STD:5.1 does not hold " want ": " (second rows)))))
    nil))

(defstep #"OUT/traceability\.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [text (out-text ctx "traceability.md")
          {:keys [order by]} (sections text)
          h (first (filter #(str/includes? % "UN-001") order))
          body (str/join "\n" (get by h))]
      (s/check! h (str "traceability.md has no section for UN-001:\n" text))
      (s/check! (str/includes? (str h "\n" body) cs/need-text) (str "the section of UN-001 does not hold its text:\n" text))
      (doseq [{:keys [id title]} cs/scenarios
              :let [row (first (filter #(str/includes? % (str "@" id)) (get by h)))]]
        (s/check! row (str "no row for @" id ":\n" text))
        (doseq [want [title "Planning" "verified"]]
          (s/check! (str/includes? row want) (str "the row of @" id " does not hold " want ": " row)))))
    nil))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rd (:results-dir ctx)
          out (cs/out-file ctx "OUT" "results")]
      (s/check! (seq (files-under rd)) "the results directory is empty")
      (s/check! (= (files-under rd) (files-under out)) (str "OUT/results holds " (pr-str (files-under out))))
      (doseq [f (files-under rd)]
        (s/check! (= (sha256 (fs/file rd f)) (sha256 (fs/file out f))) (str "OUT/results/" f " is no copy"))))
    nil))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [m (edn/read-string (out-text ctx "manifest.edn"))
          o (get-in ctx [:placeholders "OUT"])
          head (cs/git! (:dir ctx) "rev-parse" "HEAD")
          others (remove #{"manifest.edn"} (files-under o))]
      (s/check! (= #{:commit :files} (set (keys m))) (str "the manifest's keys are " (pr-str (keys m))))
      (s/check! (= head (:commit m)) (str "the manifest's commit is " (:commit m) ", HEAD is " head))
      (s/check! (= (vec others) (mapv :path (:files m))) (str "the manifest lists " (pr-str (map :path (:files m)))))
      (doseq [e (:files m)]
        (s/check! (= #{:path :sha256} (set (keys e))) (str "a manifest entry is " (pr-str e)))
        (s/check! (= (:sha256 e) (sha256 (fs/file o (:path e)))) (str "the sha256 of " (:path e) " is not the file's"))))
    nil))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (s/check! (zero? (:exit (:first-run ctx))) (str "the first audit failed\n" (s/show (:first-run ctx))))
    (kc/exits! ctx 0)
    (let [a (get-in ctx [:placeholders "OUT"])
          b (get-in ctx [:placeholders "OUT2"])
          fa (files-under a)]
      (s/check! (= fa (files-under b)) "OUT and OUT2 hold different files")
      (s/check! (some #(str/ends-with? % ".pdf") fa) "OUT holds no PDF")
      (s/check! (some #{"manifest.edn"} fa) "OUT holds no manifest")
      (doseq [f fa]
        (s/check! (java.util.Arrays/equals (fs/read-all-bytes (fs/file a f)) (fs/read-all-bytes (fs/file b f)))
                  (str f " differs between OUT and OUT2"))))
    nil))
