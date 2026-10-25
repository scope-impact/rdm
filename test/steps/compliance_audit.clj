(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` on a releasable regulated project the scenario makes
  (steps.compliance-support). OUT and OUT2 are :placeholders, new paths
  that do not exist, which the kernel's When substitutes.

  Context keys: :dir, :placeholders, :changed, :first-run, :written (what
  the outline wrote), :results-copy."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as c]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Only a releasable record has an audit copy

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx]
    (c/releasable! ctx (fn [ctx]
                         (fs/delete-tree (c/results-dir ctx))
                         (c/result! ctx "spec-0001" "failed")
                         (c/result! ctx "spec-0002" "passed")))))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])
          want (kc/finding-lines release)
          got (kc/finding-lines r)]
      (s/check! (= 1 (:exit release)) (str "the release check passes\n" (s/show release)))
      (s/check! (seq want) "the release check names nothing")
      (s/check! (= want got) (str "the audit's findings are\n" (str/join "\n" got) "\nthe release check's\n" (str/join "\n" want)))
      (c/no-out! ctx))))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (c/releasable! ctx #(c/write! % c/std-rel "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"))))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % "STD:5.2") (kc/finding-lines r)) (str "no finding names STD:5.2\n" (s/show r)))
      (c/no-out! ctx))))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "holds no :regulated")]
      (c/no-out! ctx)
      r
      nil)))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (c/releasable! ctx)
          out (c/out-dir ctx "OUT")]
      (s/write! out "keep.txt" "an auditor's note\n")
      ctx)))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (let [out (c/out-dir ctx "OUT")]
      (kc/refused-naming! ctx out)
      (s/check! (= ["keep.txt"] (map #(str (fs/file-name %)) (fs/list-dir out))) "OUT holds other files now")
      (s/check! (= "an auditor's note\n" (slurp (str (fs/file out "keep.txt")))) "the file in OUT changed")
      nil)))

;; ---------------------------------------------------------------------------
;; Two copies

(def ^:private written
  "What the outline writes into plan.md's body, by its <written>; every
  plan also names STD:5.1 in a paragraph of its own, so it is releasable."
  {"a tag that opens on one line and closes on the next"
   "We plan the work [[STD:5.1\nDevelopment plan]] carefully."
   "a \"[[\" with no \"]]\" before the paragraph's blank line"
   "We plan [[ the work\nand test it."
   "a fenced code block holding [[STD:5.1]]"
   "```\ncode [[STD:5.1]] here\n```"
   "an indented code block holding [[STD:5.1]]"
   "    code [[STD:5.1]] here"})

(defn- plan-with
  "plan.md holding `body`; a paragraph naming STD:5.1 comes first unless
  the body names it outside code."
  [body title]
  (c/document "PLAN-001" title (str (when-not (re-find #"^We[^`]*\[\[STD:5\.1\]\]" body) "This plan meets [[STD:5.1]].\n\n")
                                    body "\n\nThe end.\n")))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (let [[_ line] (re-matches #"the line \"(.*)\"" what)
          [_ title] (re-matches #"the title \"(.*)\" in its frontmatter" what)
          body (cond line line
                     title "We plan the work."
                     :else (or (get written what) (s/fail! (str "nothing to write for " what))))
          text (plan-with body (or title "Development plan"))]
      (assoc (c/releasable! ctx #(c/write! % c/plan-rel text)) :written body))))

(defn- copy-lines [ctx who] (c/lines (c/out-file ctx (str who "/plan.md"))))

(defstep #"OUT/auditor/plan\.md holds the line \"(.*)\""
  (fn [ctx line]
    (kc/exits! ctx 0)
    (s/check! (some #{line} (copy-lines ctx "auditor")) (str "the auditor's copy holds no line " (pr-str line)))
    nil))

(defstep #"OUT/engineer/plan\.md holds (.+)"
  (fn [ctx kept]
    (kc/exits! ctx 0)
    (let [eng (copy-lines ctx "engineer")
          aud (copy-lines ctx "auditor")
          has (fn [l] (s/check! (some #{l} eng) (str "the engineer's copy holds no line " (pr-str l) ":\n" (str/join "\n" eng))))
          unchanged (fn [] (doseq [l (c/lines (:written ctx))] (has l)))
          [_ line] (re-matches #"the line \"(.*)\"" kept)
          [_ title] (re-matches #"the title \"(.*)\"" kept)]
      (s/check! (= (count aud) (count eng)) "the two copies do not have the same number of lines")
      (cond
        line (has line)
        title (do (has (str "title: " title))
                  (s/check! (not-any? #(str/includes? % "[[") (take 5 eng)) "the frontmatter keeps a tag"))
        (= kept "both lines without the tag")
        (let [i (.indexOf ^java.util.List eng "We plan the work")]
          (s/check! (>= i 0) (str "no line \"We plan the work\":\n" (str/join "\n" eng)))
          (s/check! (= "carefully." (str/trim (nth eng (inc i)))) (str "the next line is " (pr-str (nth eng (inc i))))))
        (#{"that line unchanged" "the paragraph unchanged" "the code block unchanged"} kept) (unchanged)
        :else (s/fail! (str "no check for " kept)))
      nil)))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [eng (copy-lines ctx "engineer")
          aud (copy-lines ctx "auditor")
          diff (remove (fn [[a e]] (= a e)) (map vector aud eng))]
      (s/check! (= (count aud) (count eng)) "the two copies do not have the same number of lines")
      (s/check! (= [["We plan the work [[STD:5.1]]." "We plan the work."]] (map vec diff))
                (str "the copies differ in " (pr-str diff)))
      nil)))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (c/releasable! ctx #(c/write! % "dhf/documents/risk/register.md"
                                  (c/document "RISK-001" "Risk register" "Risks are controlled [[STD:5.1]].\n")))))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [who ["auditor" "engineer"] rel ["plan.md" "risk/register.md"]]
      (c/out-file ctx (str who "/" rel)))
    nil))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (c/releasable! ctx #(c/write! % c/plan-rel (c/document "PLAN-001" 3 "Development plan" "We plan the work [[STD:5.1]].\n")))))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [who ["auditor" "engineer"]]
      (s/check! (fs/regular-file? (fs/file (c/out-dir ctx "OUT") who "plan.pdf")) (str "no " who "/plan.pdf")))
    nil))

(defn- pdf-text [f & pages]
  (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                 (concat ["pdftotext"] pages [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " f ": " (:err r)))
    (:out r)))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [who ["auditor" "engineer"]
            :let [t (pdf-text (fs/file (c/out-dir ctx "OUT") who "plan.pdf") "-f" "1" "-l" "1")]]
      (s/check! (and (str/includes? t "PLAN-001") (re-find #"(?i)revision\W*3\b" t) (str/includes? t "Development plan"))
                (str who "/plan.pdf's first page reads:\n" t)))
    nil))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"\[\[\""
  (fn [ctx]
    (let [t (pdf-text (fs/file (c/out-dir ctx "OUT") "engineer" "plan.pdf"))]
      (s/check! (not (str/includes? t "[[")) (str "the engineer's PDF holds [[:\n" t))
      (s/check! (str/includes? (pdf-text (fs/file (c/out-dir ctx "OUT") "auditor" "plan.pdf")) "[[")
                "the auditor's PDF holds no [[ either")
      nil)))

;; ---------------------------------------------------------------------------
;; The evidence

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"include base\.txt\" then \"STD:5\.1 Development plan\", and whose dhf/clauses/base\.txt holds \"STD:4\.1 Quality system\""
  (fn [ctx]
    (c/releasable! ctx (fn [ctx]
                         (c/write! ctx c/std-rel "include base.txt\nSTD:5.1 Development plan\n")
                         (c/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")))))

(defstep #"STD:5\.1 is named on line 8 of dhf/documents/plan\.md and line 9 of dhf/documents/risk/register\.md"
  (fn [ctx]
    (let [plan (c/document "PLAN-001" "Development plan" "We plan the work [[STD:5.1]].\n\nThe quality system [[STD:4.1]].\n")
          register (c/document "RISK-001" "Risk register" "The register.\nRisks are controlled [[STD:5.1]].\n")]
      (s/check! (str/includes? (nth (c/lines plan) 7) "[[STD:5.1]]") "plan.md's line 8 names no STD:5.1")
      (s/check! (str/includes? (nth (c/lines register) 8) "[[STD:5.1]]") "register.md's line 9 names no STD:5.1")
      (c/write! ctx c/plan-rel plan)
      (c/write! ctx "dhf/documents/risk/register.md" register)
      (c/commit! ctx))))

(defn- headings [text] (filter #(re-find #"^#+ " %) (c/lines text)))

(defstep #"OUT/clauses\.md has one section per declared clause list, dhf/clauses/std\.txt, and no section for dhf/clauses/base\.txt"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [hs (headings (c/out-file ctx "clauses.md"))]
      (s/check! (= 1 (count (filter #(str/includes? % "dhf/clauses/std.txt") hs))) (str "the headings are " (pr-str hs)))
      (s/check! (not-any? #(str/includes? % "base.txt") hs) (str "the headings are " (pr-str hs)))
      nil)))

(defstep #"under it STD:4\.1 comes first, where its include stands, then a row for STD:5\.1 with \"Development plan\", dhf/documents/plan\.md:8 and dhf/documents/risk/register\.md:9"
  (fn [ctx]
    (let [ls (c/lines (c/out-file ctx "clauses.md"))
          section (take-while #(not (re-find #"^#+ " %)) (rest (drop-while #(not (and (re-find #"^#+ " %) (str/includes? % "std.txt"))) ls)))
          rows (filter #(re-find #"STD:\d" %) section)]
      (s/check! (= 2 (count rows)) (str "the section's rows are " (pr-str rows)))
      (s/check! (str/includes? (first rows) "STD:4.1") (str "the first row is " (first rows)))
      (s/check! (every? #(str/includes? (second rows) %) ["STD:5.1" "Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"])
                (str "the second row is " (second rows)))
      nil)))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (c/releasable! ctx)))

(defstep #"OUT/traceability\.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [ls (c/lines (c/out-file ctx "traceability.md"))
          section (cons (first (drop-while #(not (and (re-find #"^#+ " %) (str/includes? % "UN-001"))) ls))
                        (take-while #(not (re-find #"^#+ " %)) (rest (drop-while #(not (and (re-find #"^#+ " %) (str/includes? % "UN-001"))) ls))))
          text (str/join "\n" section)]
      (s/check! (some? (first section)) "no heading names UN-001")
      (s/check! (str/includes? text c/need-text) (str "the section holds no need text:\n" text))
      (doseq [[id title] [["spec-0001" "The work is planned"] ["spec-0002" "The plan has a format"]]]
        (s/check! (some #(every? (fn [p] (str/includes? % p)) [id title "Alpha" "verified"]) section)
                  (str "no line holds " id ", its title, Alpha and verified:\n" text)))
      nil)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (c/releasable! ctx)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rd (c/results-dir ctx)
          fs* (for [f (fs/glob rd "**") :when (fs/regular-file? f)] (str (fs/relativize rd f)))]
      (s/check! (seq fs*) "the results directory is empty")
      (doseq [f fs*]
        (s/check! (= (slurp (str (fs/file rd f))) (c/out-file ctx (str "results/" f))) (str "results/" f " is no copy"))))
    nil))

(defstep #"a releasable regulated project"
  (fn [ctx] (c/releasable! ctx)))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [m (c/read-edn (c/out-file ctx "manifest.edn"))
          out (c/out-dir ctx "OUT")
          files (sort (for [f (fs/glob out "**") :when (fs/regular-file? f)
                            :let [rel (str (fs/relativize out f))] :when (not= "manifest.edn" rel)]
                        rel))]
      (s/check! (= #{:commit :files} (set (keys m))) (str "the manifest holds " (pr-str (keys m))))
      (s/check! (= (c/git! ctx "rev-parse" "HEAD") (:commit m)) (str "the manifest's commit is " (:commit m)))
      (s/check! (= (vec (for [f files] {:path f :sha256 (sha256 (fs/file out f))})) (vec (:files m)))
                (str "the manifest's files are " (pr-str (:files m)) "\nOUT holds " (pr-str files)))
      nil)))

(defstep #"a releasable regulated project, all committed"
  (fn [ctx]
    (let [ctx (c/releasable! ctx)]
      (s/check! (= "" (c/git! ctx "status" "--porcelain")) "the project is not all committed")
      ctx)))

(defstep #"(an untracked document dhf/documents/draft\.md|an uncommitted change to a document|an uncommitted change to a scenario's title|an uncommitted change to the clause list|an uncommitted change to the configuration)"
  (fn [ctx change]
    (let [edit (fn [rel f] (c/write! ctx rel (f (slurp (str (fs/file (:dir ctx) rel))))) (assoc ctx :changed rel))]
      (case change
        "an untracked document dhf/documents/draft.md"
        (assoc (c/write! ctx "dhf/documents/draft.md" (c/document "DRAFT-001" "Draft" "A draft [[STD:5.1]].\n"))
               :changed "dhf/documents/draft.md")
        "an uncommitted change to a document" (edit c/plan-rel #(str % "\nOne more line.\n"))
        "an uncommitted change to a scenario's title"
        (edit "dhf/spec/demo/alpha.feature" #(str/replace % "Scenario: The work is planned" "Scenario: The work is planned well"))
        "an uncommitted change to the clause list" (edit c/std-rel #(str "# The standard\n" %))
        "an uncommitted change to the configuration" (edit "dhf/harness/config.edn" #(str ";; changed\n" %))))))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx file]
    (let [r (kc/exits! ctx 1)
          f (if (#{"that document" "that feature file"} file) (:changed ctx) file)]
      (s/check! (= f (:changed ctx)) (str "the scenario changed " (:changed ctx) ", not " f))
      (s/check! (some #(and (str/includes? % f) (str/includes? % "not committed")) (kc/finding-lines r))
                (str "no finding names " f " as not committed\n" (s/show r)))
      (c/no-out! ctx))))

(defstep #"\"rdm-spec ([^\"]*)\" is run, then \"rdm-spec ([^\"]*)\""
  (fn [ctx first-line second-line]
    (let [ctx (kc/run-line ctx first-line)]
      (kc/run-line (assoc ctx :first-run (:run ctx)) second-line))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (s/check! (zero? (:exit (:first-run ctx))) (str "the first run failed\n" (s/show (:first-run ctx))))
    (kc/exits! ctx 0)
    (let [[a b] (map #(c/out-dir ctx %) ["OUT" "OUT2"])
          files (fn [d] (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (str (fs/relativize d f)))))]
      (s/check! (= (files a) (files b)) (str "OUT holds " (pr-str (files a)) ", OUT2 " (pr-str (files b))))
      (s/check! (some #(str/ends-with? % ".pdf") (files a)) "OUT holds no PDF")
      (doseq [f (files a)]
        (s/check! (java.util.Arrays/equals (fs/read-all-bytes (fs/file a f)) (fs/read-all-bytes (fs/file b f)))
                  (str f " differs between OUT and OUT2"))))
    nil))
