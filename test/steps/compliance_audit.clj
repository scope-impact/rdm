(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` in a releasable regulated project (steps.compliance-support): one
  component, demo/Alpha, under UN-001 with spec-0001 and spec-0002, a passing
  result for each, the clause list dhf/clauses/std.txt and dhf/documents/,
  everything committed. OUT and OUT2 are new paths (:placeholders).

  A plan a step writes always names STD:5.1 in a last paragraph of its own,
  so the record stays releasable whatever the line under test holds.

  Context keys set here: :dir, :placeholders, :written (the lines under
  test), :without-tag (the lines the engineer's copy holds instead),
  :changed (a committed file a step changed), :results-files ({name text}),
  :first-run, :before ({path text} of the existing OUT)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as c]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private register-rel "dhf/documents/risk/register.md")

(defn- out [ctx k] (c/out-dir ctx k))

(defn- files-of [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defn- plan-with
  "A plan whose body is `lines`, then a paragraph naming STD:5.1."
  ([lines] (plan-with "Development plan" lines))
  ([title lines]
   (c/document "PLAN-001" title (str (str/join "\n" lines) "\n\nThe plan is followed [[STD:5.1]].\n"))))

;; ---------------------------------------------------------------------------
;; Only a releasable record has an audit copy

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx] (c/releasable! ctx (fn [ctx] (c/result! ctx "spec-0002" "failed")))))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])]
      (s/check! (= 1 (:exit release)) (str "the release check does not refuse\n" (s/show release)))
      (s/check! (= (kc/finding-lines release) (kc/finding-lines r))
                (str "the findings are not the release check's\n" (s/show r) "\n" (s/show release)))
      (c/no-out! ctx))))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (c/releasable! ctx (fn [ctx] (c/write! ctx c/std-rel "STD:5.1 Development plan\nSTD:5.2 Design\n")))))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % "STD:5.2") (kc/finding-lines r)) (str "no finding names STD:5.2\n" (s/show r)))
      (c/no-out! ctx))))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (kc/refused-naming! ctx "holds no :regulated")
    (c/no-out! ctx)))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (c/releasable! ctx)]
      (s/write! (out ctx "OUT") "kept.txt" "a file that was here first\n")
      (assoc ctx :before (files-of (out ctx "OUT"))))))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx (out ctx "OUT"))
    (s/check! (= (:before ctx) (files-of (out ctx "OUT"))) "OUT changed")
    nil))

;; ---------------------------------------------------------------------------
;; The auditor's copy and the engineer's copy

(def ^:private written
  "What a plan holds under test: [lines written, lines the engineer's copy
  holds, or :unchanged]."
  {"the line \"We plan [[STD:5.1]].\"" [["We plan [[STD:5.1]]."] ["We plan."]]
   "the line \"We plan the work [[STD:5.1]].\"" [["We plan the work [[STD:5.1]]."] ["We plan the work."]]
   "the line \"We plan [[STD:5.1]] and test [[STD:5.2]].\"" [["We plan [[STD:5.1]] and test [[STD:5.2]]."] ["We plan and test."]]
   "a tag that opens on one line and closes on the next" [["We plan [[STD:5.1" "for the work]] together."] ["We plan" " together."]]
   "the line \"We plan `[[STD:5.1]]`.\"" [["We plan `[[STD:5.1]]`."] :unchanged]
   "a \"[[\" with no \"]]\" before the paragraph's blank line" [["We plan [[ the work" "and nothing closes it."] :unchanged]
   "a fenced code block holding [[STD:5.1]]" [["```" "a code block [[STD:5.1]]" "```"] :unchanged]
   "an indented code block holding [[STD:5.1]]" [["A paragraph." "" "    a code block [[STD:5.1]]"] :unchanged]})

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (if (= what "the title \"Development plan [[STD:5.1]]\" in its frontmatter")
      (c/releasable! ctx (fn [ctx] (c/write! ctx c/plan-rel (plan-with "Development plan [[STD:5.1]]" ["We plan."]))))
      (let [[lines kept] (or (get written what) (s/fail! (str "no fixture writes " what)))
            ;; the one line the copies may differ in names STD:5.1 itself
            text (if (= lines ["We plan the work [[STD:5.1]]."]) c/default-plan (plan-with lines))]
        (-> (c/releasable! ctx (fn [ctx] (c/write! ctx c/plan-rel text)))
            (assoc :written lines :without-tag (if (= :unchanged kept) lines kept)))))))

(defn- copy-lines [ctx who]
  (kc/exits! ctx 0)
  (str/split-lines (c/out-file ctx (str who "/plan.md"))))

(defn- holds-lines! [ctx who ls]
  (let [got (copy-lines ctx who)
        text (str/join "\n" got)]
    (s/check! (str/includes? text (str/join "\n" ls))
              (str who "/plan.md does not hold " (pr-str ls) ":\n" text))
    nil))

(defstep #"OUT/(auditor|engineer)/plan\.md holds (.+)"
  (fn [ctx who what]
    (cond
      (re-matches #"the line \"(.*)\"" what)
      (let [line (second (re-matches #"the line \"(.*)\"" what))]
        (s/check! (some #{line} (copy-lines ctx who)) (str who "/plan.md holds no line " (pr-str line)))
        nil)

      (re-matches #"the title \"(.*)\"" what)
      (let [t (second (re-matches #"the title \"(.*)\"" what))]
        (s/check! (some #{(str "title: " t)} (copy-lines ctx who)) (str who "/plan.md has not the title " (pr-str t)))
        nil)

      :else (holds-lines! ctx who (:without-tag ctx)))))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (copy-lines ctx "auditor")
          e (copy-lines ctx "engineer")
          diff (keep-indexed (fn [i [x y]] (when (not= x y) i)) (map vector a e))]
      (s/check! (= (count a) (count e)) "the copies have different numbers of lines")
      (s/check! (= 1 (count diff)) (str "the copies differ at lines " (pr-str (map inc diff))))
      nil)))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (c/releasable! ctx (fn [ctx] (c/write! ctx register-rel (c/document "RISK-001" "Risk register" "We weigh risks [[STD:5.1]].\n"))))))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [who ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (c/out-file ctx (str who "/" f)))
    nil))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (c/releasable! ctx (fn [ctx] (c/write! ctx c/plan-rel (c/document "PLAN-001" 3 "Development plan" "We plan the work [[STD:5.1]].\n"))))))

(defn- pdf-text [ctx rel & [first-page?]]
  (let [f (fs/file (out ctx "OUT") rel)
        _ (s/check! (fs/regular-file? f) (str "OUT holds no " rel "\n" (s/show (kc/the-run ctx))))
        r (apply p/shell {:out :string :err :string :continue true :in ""}
                 (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " rel ": " (:err r)))
    (:out r)))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [rel ["auditor/plan.pdf" "engineer/plan.pdf"]]
      (s/check! (fs/regular-file? (fs/file (out ctx "OUT") rel)) (str "OUT holds no " rel)))
    nil))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [rel ["auditor/plan.pdf" "engineer/plan.pdf"]
            :let [t (pdf-text ctx rel true)]]
      (s/check! (and (str/includes? t "PLAN-001") (re-find #"(?i)revision\W*3" t) (str/includes? t "Development plan"))
                (str rel "'s first page does not name the document:\n" t)))
    nil))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"\[\[\""
  (fn [ctx]
    (let [t (pdf-text ctx "engineer/plan.pdf")]
      (s/check! (not (str/includes? t "[[")) (str "the engineer's PDF holds [[:\n" t))
      nil)))

;; ---------------------------------------------------------------------------
;; The evidence

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"include base\.txt\" then \"STD:5\.1 Development plan\", and whose dhf/clauses/base\.txt holds \"STD:4\.1 Quality system\""
  (fn [ctx]
    (c/releasable! ctx (fn [ctx]
                         (c/write! ctx c/std-rel "include base.txt\nSTD:5.1 Development plan\n")
                         (c/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
                         (c/write! ctx c/plan-rel (c/document "PLAN-001" "Development plan"
                                                              "We plan the work [[STD:5.1]].\nWe keep a quality system [[STD:4.1]].\n"))))))

(defstep #"STD:5\.1 is named on line 8 of dhf/documents/plan\.md and line 9 of dhf/documents/risk/register\.md"
  (fn [ctx]
    (c/write! ctx register-rel (c/document "RISK-001" "Risk register" "We weigh risks.\nEach risk follows the plan [[STD:5.1]].\n"))
    (doseq [[rel n] [[c/plan-rel 8] [register-rel 9]]]
      (s/check! (str/includes? (nth (str/split-lines (slurp (str (fs/file (:dir ctx) rel)))) (dec n)) "[[STD:5.1]]")
                (str rel " does not name STD:5.1 on line " n)))
    (c/commit! ctx)))

(defn- lines-of [ctx rel] (kc/exits! ctx 0) (str/split-lines (c/out-file ctx rel)))

(defn- index-of [ls part] (first (keep-indexed (fn [i l] (when (str/includes? l part) i)) ls)))

(defstep #"OUT/clauses\.md has one section per declared clause list, dhf/clauses/std\.txt, and no section for dhf/clauses/base\.txt"
  (fn [ctx]
    (let [ls (lines-of ctx "clauses.md")
          heads (filter #(str/starts-with? % "#") ls)]
      (s/check! (some #(str/includes? % "dhf/clauses/std.txt") heads) (str "no section for std.txt: " (pr-str heads)))
      (s/check! (not-any? #(str/includes? % "base.txt") heads) (str "a section for base.txt: " (pr-str heads)))
      nil)))

(defstep #"under it STD:4\.1 comes first, where its include stands, then a row for STD:5\.1 with \"Development plan\", dhf/documents/plan\.md:8 and dhf/documents/risk/register\.md:9"
  (fn [ctx]
    (let [ls (lines-of ctx "clauses.md")
          sec (index-of ls "dhf/clauses/std.txt")
          a (index-of ls "STD:4.1")
          b (index-of ls "STD:5.1")]
      (s/check! (and sec a b (< sec a b)) (str "the rows are not in order:\n" (str/join "\n" ls)))
      (s/check! (every? #(str/includes? (nth ls b) %) ["Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"])
                (str "the row of STD:5.1 is " (pr-str (nth ls b))))
      nil)))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (c/releasable! ctx)))

(defstep #"OUT/traceability\.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (let [ls (lines-of ctx "traceability.md")
          need (index-of ls "UN-001")
          text (index-of ls c/need-text)]
      (s/check! (and need text (< need text)) (str "UN-001 and its text are not there:\n" (str/join "\n" ls)))
      (doseq [[id title] [["@spec-0001" "The work is planned"] ["@spec-0002" "The plan has a format"]]
              :let [i (index-of ls id)]]
        (s/check! (and i (> i text)) (str id " is not listed under UN-001"))
        (s/check! (every? #(str/includes? (nth ls i) %) [title "Alpha" "verified"]) (str "the row is " (pr-str (nth ls i)))))
      nil)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx]
    (let [ctx (c/releasable! ctx (fn [ctx] (s/write! (c/results-dir ctx) "environment.properties" "os=any\n") ctx))]
      (assoc ctx :results-files (files-of (c/results-dir ctx))))))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (files-of (fs/file (out ctx "OUT") "results"))]
      (s/check! (= (:results-files ctx) got) (str "OUT/results holds " (pr-str (keys got))))
      nil)))

(defstep #"a releasable regulated project"
  (fn [ctx] (c/releasable! ctx)))

(defn- sha256 [bytes]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (byte-array bytes))]
    (apply str (map #(format "%02x" %) d))))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [m (edn/read-string (c/out-file ctx "manifest.edn"))
          files (dissoc (files-of (out ctx "OUT")) "manifest.edn")]
      (s/check! (= #{:commit :files} (set (keys m))) (str "the manifest's keys are " (pr-str (keys m))))
      (s/check! (= (c/git! ctx "rev-parse" "HEAD") (:commit m)) (str "the manifest's commit is " (:commit m)))
      (s/check! (= (vec (for [[path bytes] (sort-by key files)] {:path path :sha256 (sha256 bytes)})) (:files m))
                (str "the manifest's files are " (pr-str (:files m))))
      nil)))

(defstep #"a releasable regulated project, all committed"
  (fn [ctx]
    (let [ctx (c/releasable! ctx)]
      (s/check! (str/blank? (c/git! ctx "status" "--porcelain")) "the project is not all committed")
      ctx)))

(defstep #"an untracked document dhf/documents/draft\.md"
  (fn [ctx] (c/write! ctx "dhf/documents/draft.md" (c/document "DRAFT-001" "A draft" "We draft [[STD:5.1]].\n"))))

(defstep #"a document dhf/documents/draft\.md that git ignores"
  (fn [ctx]
    (spit (str (fs/file (:dir ctx) ".gitignore")) "dhf/documents/draft.md\n" :append true)
    (c/commit! ctx)
    (c/write! ctx "dhf/documents/draft.md" (c/document "DRAFT-001" "A draft" "We draft [[STD:5.1]].\n"))))

(defstep #"an uncommitted change to a document"
  (fn [ctx]
    (spit (str (fs/file (:dir ctx) c/plan-rel)) "A line nobody committed.\n" :append true)
    (assoc ctx :changed c/plan-rel)))

(defstep #"an uncommitted change to a scenario's title"
  (fn [ctx]
    (let [rel "dhf/spec/demo/alpha.feature"
          f (fs/file (:dir ctx) rel)]
      (spit (str f) (str/replace (slurp (str f)) "Scenario: The work is planned" "Scenario: The work is planned well"))
      (assoc ctx :changed rel))))

(defstep #"an uncommitted change to the clause list"
  (fn [ctx] (spit (str (fs/file (:dir ctx) c/std-rel)) "# a comment nobody committed\n" :append true) ctx))

(defstep #"an uncommitted change to the configuration"
  (fn [ctx] (spit (str (fs/file (:dir ctx) "dhf/harness/config.edn")) ";; a comment nobody committed\n" :append true) ctx))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx what]
    (let [file (if (#{"that document" "that feature file"} what) (:changed ctx) what)
          r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/starts-with? % (str file ":")) (str/includes? % "not committed")) (kc/finding-lines r))
                (str "no finding names " file " as not committed\n" (s/show r)))
      (c/no-out! ctx))))

(defstep #"\"rdm-spec audit OUT\" is run, then \"rdm-spec audit OUT2\""
  (fn [ctx]
    (let [a (kc/run-line ctx "audit OUT")
          b (kc/run-line a "audit OUT2")]
      (assoc b :first-run (:run a)))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (s/check! (zero? (:exit (:first-run ctx))) (str "the first audit failed\n" (s/show (:first-run ctx))))
    (kc/exits! ctx 0)
    (let [a (files-of (out ctx "OUT"))
          b (files-of (out ctx "OUT2"))]
      (s/check! (some #(str/ends-with? % ".pdf") (keys a)) "OUT holds no PDF")
      (s/check! (contains? a "manifest.edn") "OUT holds no manifest")
      (s/check! (= (keys a) (keys b)) (str "the files differ: " (pr-str (keys a)) " " (pr-str (keys b))))
      (doseq [k (keys a)] (s/check! (= (get a k) (get b k)) (str k " differs")))
      nil)))
