(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` in a regulated project the scenario makes (steps.compliance-support:
  `releasable!`, `regulated!`), its OUT and OUT2 new paths.

  Context keys set here: :dir, :placeholders (OUT, OUT2), :line (the line a
  step wrote into plan.md), :written (what the outline's row wrote:
  {:kind :lines}), :clause, :out-file ({:path :text}, a file in an existing
  OUT), :changed (the file a step left uncommitted), :first-run."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(def ^:private sop-rel "dhf/documents/sop.md")

(defn- sop!
  "A second document naming STD:5.1, so plan.md may name nothing."
  [ctx]
  (cs/write! ctx sop-rel (cs/document "SOP-001" "Procedure" "We follow the procedure [[STD:5.1]].\n")))

(defn- succeeded!
  "Checks the audit exited 0; returns the run."
  [ctx]
  (kc/exits! ctx 0))

(defn- out-lines [ctx copy] (cs/lines (cs/out-file ctx (str copy "/plan.md"))))

(defn- files-under
  "{relative-path bytes-as-vector} of every file under `d`."
  [d]
  (into (sorted-map)
        (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
          [(str/replace (str (fs/relativize d f)) "\\" "/") (vec (fs/read-all-bytes f))])))

(defn- sha256 [^bytes b]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md b)))))

(defn- pdf-text [f & [first-page?]]
  (let [r (apply p/shell {:out :string :err :string :continue true}
                 (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " f ": " (:err r)))
    (:out r)))

;; ---------------------------------------------------------------------------
;; Only a releasable record has an audit copy

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx]
    (cs/releasable! ctx #(cs/result! % "spec-0002" "failed"))))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])
          want (kc/finding-lines release)
          got (kc/finding-lines r)]
      (s/check! (= 1 (:exit release)) (str "the release check passed\n" (s/show release)))
      (s/check! (seq want) "the release check named nothing")
      (s/check! (= want got) (str "the audit's findings are\n" (str/join "\n" got) "\nnot the release check's\n"
                                  (str/join "\n" want))))
    (cs/no-out! ctx)))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (-> (cs/releasable! ctx #(cs/write! % cs/std-rel "STD:5.1 Development plan\nSTD:5.2 Software testing\n"))
        (assoc :clause "STD:5.2"))))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % (:clause ctx)) (kc/finding-lines r))
                (str "no finding names " (:clause ctx) "\n" (s/show r))))
    (cs/no-out! ctx)))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (kc/refused-naming! ctx "configuration" "no :regulated")
    (cs/no-out! ctx)))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)
          out (cs/out-dir ctx "OUT")
          f (s/write! out "notes.txt" "Kept as it is.\n")]
      (assoc ctx :out-file {:path f :text "Kept as it is.\n"}))))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (kc/refused-naming! ctx (cs/out-dir ctx "OUT"))
    (let [{:keys [path text]} (:out-file ctx)]
      (s/check! (= text (slurp path)) "the file in OUT changed")
      (s/check! (= ["notes.txt"] (keys (files-under (cs/out-dir ctx "OUT"))))
                (str "OUT holds " (pr-str (keys (files-under (cs/out-dir ctx "OUT")))))))
    nil))

;; ---------------------------------------------------------------------------
;; Every document has an auditor's copy and an engineer's copy

(defn- written
  "What the outline's row writes into plan.md's body: {:kind :body :lines
  (the lines the row is about) :title}."
  [what]
  (if-let [[_ line] (re-matches #"the line \"(.*)\"" what)]
    {:kind :line :body (str line "\n") :lines [line]}
    (case what
      "a tag that opens on one line and closes on the next"
      {:kind :split :body "We plan [[STD:5.1\nSTD:5.2]] the work.\n" :lines ["We plan [[STD:5.1" "STD:5.2]] the work."]}
      "a \"[[\" with no \"]]\" before the paragraph's blank line"
      {:kind :paragraph :body "We plan [[ the work\nand test it.\n\nThen ]] we ship.\n"
       :lines ["We plan [[ the work" "and test it."]}
      "a fenced code block holding [[STD:5.1]]"
      {:kind :code :body "The tag reads:\n\n```\n[[STD:5.1]]\n```\n" :lines ["```" "[[STD:5.1]]" "```"]}
      "an indented code block holding [[STD:5.1]]"
      {:kind :code :body "The tag reads:\n\n    [[STD:5.1]]\n" :lines ["    [[STD:5.1]]"]}
      "the title \"Development plan [[STD:5.1]]\" in its frontmatter"
      {:kind :title :body "We plan the work.\n" :title "Development plan [[STD:5.1]]"}
      (s/fail! (str "no fixture for plan.md holding " what)))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (let [w (written what)]
      (-> (cs/releasable! ctx (fn [c]
                                (sop! c)
                                (cs/write! c cs/plan-rel (cs/document "PLAN-001" (or (:title w) "Development plan")
                                                                      (:body w)))))
          (assoc :written w :line (first (:lines w)))))))

(defn- index-of-run
  "The index in `ls` where the run `want` starts, or nil."
  [ls want]
  (first (for [i (range (inc (- (count ls) (count want))))
               :when (= want (subvec (vec ls) i (+ i (count want))))]
           i)))

(defstep #"OUT/(auditor|engineer)/plan\.md holds (.+)"
  (fn [ctx copy kept]
    (succeeded! ctx)
    (let [ls (out-lines ctx copy)
          w (:written ctx)
          show (str "OUT/" copy "/plan.md is\n" (str/join "\n" ls))]
      (if-let [[_ line] (re-matches #"the line \"(.*)\"" kept)]
        (s/check! (some #{line} ls) show)
        (case kept
          "both lines without the tag"
          (let [i (index-of-run (out-lines ctx "auditor") (:lines w))
                [a b] (when i (subvec (vec ls) i (+ i 2)))]
            (s/check! i "the auditor's copy does not hold the two lines")
            (s/check! (= (count (out-lines ctx "auditor")) (count ls)) (str "the line count changed\n" show))
            (s/check! (and (= "We plan" a) (= "the work." (str/trim (str b)))) show))

          ("that line unchanged" "the paragraph unchanged" "the code block unchanged")
          (s/check! (index-of-run ls (:lines w)) show)

          "the title \"Development plan\""
          (s/check! (some #{"title: Development plan"} ls) show)

          (s/fail! (str "no check for " kept)))))
    nil))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (out-lines ctx "auditor")
          e (out-lines ctx "engineer")
          i (.indexOf ^java.util.List a (:line ctx))]
      (s/check! (<= 0 i) "the auditor's copy does not hold the line")
      (s/check! (= (count a) (count e)) "the copies have different line counts")
      (s/check! (= (concat (take i a) (drop (inc i) a)) (concat (take i e) (drop (inc i) e)))
                (str "the copies differ elsewhere:\n" (str/join "\n" a) "\n---\n" (str/join "\n" e))))
    nil))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (cs/releasable! ctx #(cs/write! % "dhf/documents/risk/register.md"
                                    (cs/document "RISK-001" "Risk register" "Each risk is controlled [[STD:5.1]].\n")))))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (succeeded! ctx)
    (doseq [copy ["auditor" "engineer"] rel ["plan.md" "risk/register.md"]]
      (cs/out-file ctx (str copy "/" rel)))
    nil))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (cs/releasable! ctx #(cs/write! % cs/plan-rel (cs/document "PLAN-001" 3 "Development plan"
                                                               "We plan the work [[STD:5.1]].\n")))))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (succeeded! ctx)
    (doseq [copy ["auditor" "engineer"]]
      (s/check! (fs/regular-file? (fs/file (cs/out-dir ctx "OUT") copy "plan.pdf")) (str "no OUT/" copy "/plan.pdf")))
    nil))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [copy ["auditor" "engineer"]
            :let [t (pdf-text (fs/file (cs/out-dir ctx "OUT") copy "plan.pdf") true)]]
      (s/check! (str/includes? t "PLAN-001") (str copy "'s first page: " t))
      (s/check! (re-find #"(?i)revision\W{0,3}3\b" t) (str copy "'s first page: " t))
      (s/check! (str/includes? t "Development plan") (str copy "'s first page: " t)))
    nil))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"\[\[\""
  (fn [ctx]
    (let [t (pdf-text (fs/file (cs/out-dir ctx "OUT") "engineer" "plan.pdf"))]
      (s/check! (not (str/includes? t "[[")) (str "the engineer's PDF reads: " t)))
    nil))

;; ---------------------------------------------------------------------------
;; The audit copy carries the evidence it rests on

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"include base\.txt\" then \"STD:5\.1 Development plan\", and whose dhf/clauses/base\.txt holds \"STD:4\.1 Quality system\""
  (fn [ctx]
    (cs/releasable! ctx (fn [c]
                          (cs/write! c cs/std-rel "include base.txt\nSTD:5.1 Development plan\n")
                          (cs/write! c "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
                          (cs/write! c cs/plan-rel (cs/document "PLAN-001" "Development plan"
                                                                "We plan the work [[STD:4.1]].\n"))))))

(defstep #"STD:5\.1 is named on line 8 of dhf/documents/plan\.md and line 9 of dhf/documents/risk/register\.md"
  (fn [ctx]
    (cs/write! ctx cs/plan-rel (cs/document "PLAN-001" "Development plan"
                                            "We plan the work [[STD:5.1]].\nWe keep a quality system [[STD:4.1]].\n"))
    (cs/write! ctx "dhf/documents/risk/register.md"
               (cs/document "RISK-001" "Risk register" "Risks are listed.\nEach risk is planned for [[STD:5.1]].\n"))
    (doseq [[rel n] [[cs/plan-rel 8] ["dhf/documents/risk/register.md" 9]]]
      (s/check! (str/includes? (nth (cs/lines (slurp (str (fs/file (:dir ctx) rel)))) (dec n)) "[[STD:5.1]]")
                (str rel " does not name STD:5.1 on line " n)))
    (cs/commit! ctx)))

(defn- sections
  "[[heading [line ..]] ..] of the Markdown `text`, by its ## headings."
  [text]
  (->> (cs/lines text)
       (partition-by #(str/starts-with? % "## "))
       (drop-while #(not (str/starts-with? (first %) "## ")))
       (partition 2)
       (mapv (fn [[[h] body]] [h (vec body)]))))

(defstep #"OUT/clauses\.md has one section per declared clause list, dhf/clauses/std\.txt, and no section for dhf/clauses/base\.txt"
  (fn [ctx]
    (succeeded! ctx)
    (let [text (cs/out-file ctx "clauses.md")
          ss (sections text)]
      (s/check! (= 1 (count ss)) (str "clauses.md has " (count ss) " sections:\n" text))
      (s/check! (str/includes? (ffirst ss) cs/std-rel) (str "its section is " (ffirst ss)))
      (s/check! (not (some #(str/includes? (first %) "base.txt") ss)) text))
    nil))

(defstep #"under it STD:4\.1 comes first, where its include stands, then a row for STD:5\.1 with \"Development plan\", dhf/documents/plan\.md:8 and dhf/documents/risk/register\.md:9"
  (fn [ctx]
    (let [text (cs/out-file ctx "clauses.md")
          body (second (first (sections text)))
          at (fn [k] (first (keep-indexed (fn [i l] (when (str/includes? l k) i)) body)))
          i41 (at "STD:4.1") i51 (at "STD:5.1")
          row (when i51 (nth body i51))]
      (s/check! (and i41 i51 (< i41 i51)) (str "STD:4.1 does not come before STD:5.1:\n" text))
      (doseq [part ["Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]]
        (s/check! (str/includes? row part) (str "the row of STD:5.1 holds no " part ": " row))))
    nil))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"OUT/traceability\.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (succeeded! ctx)
    (let [text (cs/out-file ctx "traceability.md")
          ls (cs/lines text)
          i (first (keep-indexed (fn [i l] (when (str/includes? l "UN-001") i)) ls))
          after (when i (drop i ls))]
      (s/check! i (str "traceability.md names no UN-001:\n" text))
      (s/check! (some #(str/includes? % cs/need-text) (take 4 after)) (str "UN-001's text is not under it:\n" text))
      (doseq [[id title] [["spec-0001" "The work is planned"] ["spec-0002" "The plan has a format"]]
              :let [row (first (filter #(str/includes? % id) after))]]
        (s/check! row (str "no line under UN-001 names " id ":\n" text))
        (doseq [part [title "Alpha" "verified"]]
          (s/check! (str/includes? row part) (str "the line of " id " holds no " part ": " row)))))
    nil))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (cs/results-dir ctx) "4f2a-attachment.txt" "a log the run attached\n")
      (s/write! (cs/results-dir ctx) "4f2a-container.json" "{\"uuid\":\"4f2a\",\"children\":[]}")
      ctx)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (succeeded! ctx)
    (let [want (files-under (cs/results-dir ctx))
          got (files-under (fs/file (cs/out-dir ctx "OUT") "results"))]
      (s/check! (seq want) "the results directory is empty")
      (s/check! (= want got) (str "OUT/results holds " (pr-str (keys got)) ", not " (pr-str (keys want)))))
    nil))

(defstep #"a releasable regulated project"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (succeeded! ctx)
    (let [m (cs/read-edn (cs/out-file ctx "manifest.edn"))
          out (cs/out-dir ctx "OUT")
          others (dissoc (files-under out) "manifest.edn")]
      (s/check! (= #{:commit :files} (set (keys m))) (str "the manifest's keys are " (pr-str (keys m))))
      (s/check! (= (cs/git! ctx "rev-parse" "HEAD") (:commit m)) (str "the manifest's commit is " (:commit m)))
      (s/check! (every? #(= #{:path :sha256} (set (keys %))) (:files m)) "a file entry is not {:path :sha256}")
      (s/check! (= (map :path (:files m)) (sort (map :path (:files m)))) "the files are not sorted by path")
      (s/check! (= (vec (for [[path b] others] {:path path :sha256 (sha256 (byte-array b))}))
                   (vec (:files m)))
                (str "the manifest's files are " (pr-str (map :path (:files m))) ", OUT holds " (pr-str (keys others)))))
    nil))

(defstep #"a releasable regulated project, all committed"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (s/check! (str/blank? (cs/git! ctx "status" "--porcelain")) "the project is not all committed")
      ctx)))

(def ^:private draft (cs/document "DRAFT-001" "Draft" "A draft that names [[STD:5.1]].\n"))

(defstep #"(an untracked document dhf/documents/draft\.md|a document dhf/documents/draft\.md that git ignores|an uncommitted change to a document|an uncommitted change to a scenario's title|an uncommitted change to the clause list|an uncommitted change to the configuration)"
  (fn [ctx change]
    (let [dir (:dir ctx)
          edit! (fn [rel f] (let [file (fs/file dir rel)] (spit (str file) (f (slurp (str file))))) rel)
          changed
          (case change
            "an untracked document dhf/documents/draft.md"
            (do (cs/write! ctx "dhf/documents/draft.md" draft) "dhf/documents/draft.md")
            "a document dhf/documents/draft.md that git ignores"
            (do (edit! ".gitignore" #(str % "dhf/documents/draft.md\n"))
                (cs/commit! ctx)
                (cs/write! ctx "dhf/documents/draft.md" draft)
                (s/check! (str/blank? (cs/git! ctx "status" "--porcelain")) "git does not ignore the draft")
                "dhf/documents/draft.md")
            "an uncommitted change to a document"
            (edit! cs/plan-rel #(str % "We review the plan.\n"))
            "an uncommitted change to a scenario's title"
            (edit! "dhf/spec/demo/alpha.feature" #(str/replace % "Scenario: The work is planned" "Scenario: The work is well planned"))
            "an uncommitted change to the clause list"
            (edit! cs/std-rel #(str % "# reviewed\n"))
            "an uncommitted change to the configuration"
            (edit! "dhf/harness/config.edn" #(str/replace-first % "{" "{:note \"reviewed\" ")))]
      (assoc ctx :changed changed))))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx what]
    (let [r (kc/exits! ctx 1)
          file (if (str/starts-with? what "that ") (:changed ctx) what)]
      (s/check! (= file (:changed ctx)) (str "the step changed " (:changed ctx) ", not " file))
      (s/check! (some #(and (str/includes? % file) (str/includes? % "not committed")) (kc/finding-lines r))
                (str "no finding names " file " as not committed\n" (s/show r))))
    (cs/no-out! ctx)))

(defstep #"\"rdm-spec ([^\"]*)\" is run, then \"rdm-spec ([^\"]*)\""
  (fn [ctx first-line second-line]
    (let [ctx (kc/run-line ctx first-line)
          first-run (:run ctx)]
      (assoc (kc/run-line ctx second-line) :first-run first-run))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (s/check! (zero? (:exit (:first-run ctx))) (str "the first audit failed\n" (s/show (:first-run ctx))))
    (succeeded! ctx)
    (let [a (files-under (cs/out-dir ctx "OUT"))
          b (files-under (cs/out-dir ctx "OUT2"))]
      (s/check! (some #(str/ends-with? % ".pdf") (keys a)) "OUT holds no PDF")
      (s/check! (contains? a "manifest.edn") "OUT holds no manifest")
      (s/check! (= (keys a) (keys b)) (str "OUT holds " (pr-str (keys a)) ", OUT2 " (pr-str (keys b))))
      (doseq [k (keys a)]
        (s/check! (= (get a k) (get b k)) (str k " differs between OUT and OUT2"))))
    nil))
