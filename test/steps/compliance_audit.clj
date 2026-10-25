(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` in a releasable regulated project (steps.compliance-support). OUT and
  OUT2 are new paths in directories the run removes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defn- files-under
  "Every regular file under `dir`, as paths relative to it, sorted."
  [dir]
  (vec (sort (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
               (str/replace (str (fs/relativize dir f)) "\\" "/")))))

(defn- exits-0! [ctx] (kc/exits! ctx 0))

(defn- no-out! [ctx r]
  (let [out (get-in ctx [:placeholders "OUT"])]
    (s/check! out "no OUT was given")
    (s/check! (not (fs/exists? out)) (str "OUT was created: " out "\n" (s/show r)))))

;; --- Only a releasable record has an audit copy -------------------------------

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx]
    (assoc (cs/releasable! ctx {"spec-0002" "failed"}) :failed "spec-0002")))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])]
      (s/check! (= 1 (:exit release)) (str "the release check passes\n" (s/show release)))
      (s/check! (= (s/err-lines release) (s/err-lines r))
                (str "the audit's findings are not the release check's\nrelease:\n" (s/show release)
                     "\naudit:\n" (s/show r)))
      (cs/err-line! r (:failed ctx))
      (no-out! ctx r)
      nil)))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (let [ctx (-> (cs/regulated! ctx) cs/with-spec!)]
      (cs/write! ctx cs/std "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
      (doseq [id ["spec-0001" "spec-0002"]] (cs/result! ctx id "passed"))
      (assoc (cs/commit! ctx) :clause "STD:5.2"))))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (cs/err-line! r (:clause ctx) "clause-unreferenced")
      (no-out! ctx r)
      nil)))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (kc/refused-naming! ctx ":regulated")
    nil))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)
          out (str (fs/file (kc/tmp-dir) "out"))
          f (s/write! out "kept.txt" "kept\n")]
      (-> ctx
          (assoc-in [:placeholders "OUT"] out)
          (assoc :kept f)))))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (let [out (get-in ctx [:placeholders "OUT"])]
      (kc/refused-naming! ctx out)
      (s/check! (= ["kept.txt"] (files-under out)) (str "OUT was written into: " (files-under out)))
      (s/check! (= "kept\n" (slurp (:kept ctx))) "the file in OUT changed")
      nil)))

;; --- Every document has an auditor's copy and an engineer's copy -------------

(def ^:private support-doc
  "A second document naming STD:5.1, so the record stays releasable whatever
  plan.md holds."
  ["dhf/documents/sop.md"
   (cs/document [["id" "SOP-001"] ["revision" "1"] ["title" "Procedure"]] "We follow the plan [[STD:5.1]].\n")])

(defn- releasable-with-plan!
  "A releasable regulated project whose plan.md is `text`."
  [ctx text]
  (let [ctx (cs/releasable! ctx)]
    (apply cs/write! ctx support-doc)
    (cs/write! ctx cs/plan text)
    (cs/commit! ctx)))

(def ^:private written
  "What plan.md holds, by the outline's words: [body lines, or a whole
  document under :doc] and what the engineer's copy keeps."
  {"a tag that opens on one line and closes on the next"
   {:lines ["We plan the work [[STD:5.1" "and more]] every week."]
    :kept ["We plan the work" " every week."]}
   "a \"[[\" with no \"]]\" before the paragraph's blank line"
   {:lines ["We plan [[STD:5.1 and" "never close it." "" "Next paragraph ]]."]
    :kept ["We plan [[STD:5.1 and" "never close it." "" "Next paragraph ]]."]}
   "a fenced code block holding [[STD:5.1]]"
   {:lines ["```" "We plan [[STD:5.1]]." "```"] :kept ["```" "We plan [[STD:5.1]]." "```"]}
   "an indented code block holding [[STD:5.1]]"
   {:lines ["    We plan [[STD:5.1]]." "    And more."] :kept ["    We plan [[STD:5.1]]." "    And more."]}
   "the title \"Development plan [[STD:5.1]]\" in its frontmatter"
   {:doc (cs/document [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan [[STD:5.1]]"]]
                      "# Plan\n\nWe plan the work.\n")}})

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds the line \"(.*)\""
  (fn [ctx line]
    (-> (releasable-with-plan! ctx (cs/plan-doc "# Plan" "" "Before." "" line "" "After."))
        (assoc :line-written line))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (?!the line \")(.+)"
  (fn [ctx what]
    (let [w (get written what)]
      (s/check! w (str "no fixture for: " what))
      (-> (releasable-with-plan! ctx (or (:doc w) (apply cs/plan-doc "# Plan" "" (concat (:lines w) ["" "After."]))))
          (assoc :written w)))))

(defstep #"(OUT\d*/\S+) holds the line \"(.*)\""
  (fn [ctx rel line]
    (let [f (cs/out-file ctx rel)]
      (s/check! (fs/regular-file? f) (str rel " does not exist\n" (s/show (kc/the-run ctx))))
      (s/check! (some #{line} (cs/lines-of f)) (str rel " holds no line " (pr-str line) ":\n" (slurp (str f))))
      nil)))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (cs/lines-of (cs/out-file ctx "OUT/auditor/plan.md"))
          e (cs/lines-of (cs/out-file ctx "OUT/engineer/plan.md"))]
      (s/check! (= (count a) (count e)) "the two copies differ in their number of lines")
      (let [diff (keep-indexed (fn [i [x y]] (when (not= x y) i)) (map vector a e))]
        (s/check! (= [(:line-written ctx)] (map #(get a %) diff))
                  (str "the copies differ on other lines: " (pr-str (map #(get e %) diff)))))
      nil)))

(defn- engineer-body
  "The engineer's copy of plan.md and the auditor's, as lines."
  [ctx]
  (kc/exits! ctx 0)
  [(cs/lines-of (cs/out-file ctx "OUT/engineer/plan.md"))
   (cs/lines-of (cs/out-file ctx "OUT/auditor/plan.md"))])

(defn- holds-run! [lines want]
  (s/check! (some #(= want %) (partition (count want) 1 lines))
            (str "the engineer's copy does not hold " (pr-str want) ":\n" (str/join "\n" lines))))

(defstep #"OUT/engineer/plan\.md holds both lines without the tag"
  (fn [ctx]
    (let [[e _] (engineer-body ctx)]
      (holds-run! e (get-in ctx [:written :kept]))
      (s/check! (not-any? #(str/includes? % "[[") e) "a tag is left") nil)))

(defstep #"OUT/engineer/plan\.md holds (?:that line|the paragraph|the code block) unchanged"
  (fn [ctx]
    (let [[e a] (engineer-body ctx)]
      (holds-run! e (or (get-in ctx [:written :kept]) [(:line-written ctx)]))
      (s/check! (= a e) "the engineer's copy differs from the auditor's") nil)))

(defstep #"OUT/engineer/plan\.md holds the title \"([^\"]*)\""
  (fn [ctx title]
    (let [[e a] (engineer-body ctx)]
      (s/check! (some #{(str "title: " title)} e) (str "no title " title ":\n" (str/join "\n" e)))
      (s/check! (some #(str/includes? % "[[STD:5.1]]") a) "the auditor's copy lost its title's tag") nil)))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (cs/write! ctx "dhf/documents/risk/register.md"
                 (cs/document [["id" "RISK-001"] ["revision" "2"] ["title" "Risk register"]] "No risk yet.\n"))
      (cs/commit! ctx))))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (exits-0! ctx)
    (doseq [copy ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (s/check! (fs/regular-file? (cs/out-file ctx (str "OUT/" copy "/" f))) (str "OUT/" copy "/" f " does not exist")))
    nil))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (releasable-with-plan! ctx (cs/document [["id" "PLAN-001"] ["revision" "3"] ["title" "Development plan"]]
                                            "# Plan\n\nWe plan the work [[STD:5.1]].\n"))))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (exits-0! ctx)
    (doseq [f ["OUT/auditor/plan.pdf" "OUT/engineer/plan.pdf"]]
      (let [file (cs/out-file ctx f)]
        (s/check! (fs/regular-file? file) (str f " does not exist"))
        (s/check! (str/starts-with? (slurp (str file)) "%PDF") (str f " is not a PDF"))))
    nil))

(defn- pdf-text [f & pages]
  (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                 "pdftotext" (concat pages ["-layout" (str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " f ": " (:err r)))
    (:out r)))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [f ["OUT/auditor/plan.pdf" "OUT/engineer/plan.pdf"]
            :let [t (pdf-text (cs/out-file ctx f) "-f" "1" "-l" "1")]]
      (s/check! (str/includes? t "PLAN-001") (str f "'s first page names no PLAN-001:\n" t))
      (s/check! (re-find #"(?i)revision\W*3\b" t) (str f "'s first page names no revision 3:\n" t))
      (s/check! (str/includes? t "Development plan") (str f "'s first page has no title:\n" t)))
    nil))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"\[\[\""
  (fn [ctx]
    (let [t (pdf-text (cs/out-file ctx "OUT/engineer/plan.pdf"))]
      (s/check! (not (str/includes? t "[[")) (str "the engineer's PDF holds [[:\n" t))
      (s/check! (str/includes? (pdf-text (cs/out-file ctx "OUT/auditor/plan.pdf")) "[[STD:5.1]]")
                "the auditor's PDF lost its tag")
      nil)))

;; --- The audit copy carries the evidence it rests on --------------------------

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"include base\.txt\" then \"STD:5\.1 Development plan\", and whose dhf/clauses/base\.txt holds \"STD:4\.1 Quality system\""
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (cs/write! ctx cs/std "include base.txt\nSTD:5.1 Development plan\n")
      (cs/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
      (cs/write! ctx "dhf/documents/quality.md"
                 (cs/document [["id" "QMS-001"] ["revision" "1"] ["title" "Quality"]] "Our system [[STD:4.1]].\n"))
      (cs/commit! ctx))))

(defstep #"STD:5\.1 is named on line 8 of dhf/documents/plan\.md and line 9 of dhf/documents/risk/register\.md"
  (fn [ctx]
    ;; plan-doc's body starts on line 7.
    (cs/write! ctx cs/plan (cs/plan-doc "# Plan" "We plan the work [[STD:5.1]]."))
    (cs/write! ctx "dhf/documents/risk/register.md"
               (cs/document [["id" "RISK-001"] ["revision" "2"] ["title" "Risk register"]]
                            "# Register\n\nRisks are planned [[STD:5.1]].\n"))
    (cs/commit! ctx)))

(defn- clauses-md [ctx]
  (exits-0! ctx)
  (let [f (cs/out-file ctx "OUT/clauses.md")]
    (s/check! (fs/regular-file? f) "OUT/clauses.md does not exist")
    (cs/lines-of f)))

(defstep #"OUT/clauses\.md has one section per declared clause list, dhf/clauses/std\.txt, and no section for dhf/clauses/base\.txt"
  (fn [ctx]
    (let [ls (clauses-md ctx)
          sections (filter #(re-find #"^#{2,}\s" %) ls)]
      (s/check! (= 1 (count sections)) (str "sections: " (pr-str sections)))
      (s/check! (str/includes? (first sections) "dhf/clauses/std.txt") (str "sections: " (pr-str sections)))
      nil)))

(defstep #"under it STD:4\.1 comes first, where its include stands, then a row for STD:5\.1 with \"Development plan\", dhf/documents/plan\.md:8 and dhf/documents/risk/register\.md:9"
  (fn [ctx]
    (let [rows (filter #(re-find #"\|\s*STD:" %) (clauses-md ctx))]
      (s/check! (= 2 (count rows)) (str "rows: " (pr-str rows)))
      (s/check! (str/includes? (first rows) "STD:4.1") (str "the first row is not STD:4.1: " (first rows)))
      (doseq [w ["STD:5.1" "Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]]
        (s/check! (str/includes? (second rows) w) (str "the STD:5.1 row lacks " w ": " (second rows))))
      nil)))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"OUT/traceability\.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (exits-0! ctx)
    (let [text (slurp (str (cs/out-file ctx "OUT/traceability.md")))
          ls (str/split-lines text)
          need-at (first (keep-indexed (fn [i l] (when (and (re-find #"^#+\s" l) (str/includes? l "UN-001")) i)) ls))]
      (s/check! need-at (str "no heading names UN-001:\n" text))
      (s/check! (str/includes? text cs/need-text) (str "the need's text is missing:\n" text))
      (doseq [[id title] cs/scenario-titles
              :let [row (some #(when (str/includes? % (str "@" id)) %) (drop need-at ls))]]
        (s/check! row (str "no row for @" id " under UN-001:\n" text))
        (doseq [w [title "Alpha" "verified"]]
          (s/check! (str/includes? row w) (str "the row for @" id " lacks " w ": " row))))
      nil)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (exits-0! ctx)
    (let [src (fs/file (:dir ctx) cs/results-rel)
          dst (cs/out-file ctx "OUT/results")
          want (files-under src)]
      (s/check! (seq want) "the results directory is empty")
      (s/check! (= want (files-under dst)) (str "OUT/results holds " (files-under dst) ", not " want))
      (doseq [f want]
        (s/check! (= (sha256 (fs/file src f)) (sha256 (fs/file dst f))) (str f " differs")))
      nil)))

(defstep #"a releasable regulated project"
  (fn [ctx] (cs/releasable! ctx)))

(defn- manifest [ctx]
  (let [f (cs/out-file ctx "OUT/manifest.edn")]
    (s/check! (fs/regular-file? f) "OUT/manifest.edn does not exist")
    (edn/read-string (slurp (str f)))))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :clean :files\}: the commit HEAD resolves to, :clean true when no file under the clause lists' and documents' directories differs from it, untracked files included, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (exits-0! ctx)
    (let [m (manifest ctx)
          out (cs/out-file ctx "OUT")
          want (vec (for [f (files-under out) :when (not= "manifest.edn" f)]
                      {:path f :sha256 (sha256 (fs/file out f))}))]
      (s/check! (= #{:commit :clean :files} (set (keys m))) (str "the manifest's keys: " (keys m)))
      (s/check! (= (cs/git! (:dir ctx) "rev-parse" "HEAD") (:commit m)) (str "the commit: " (:commit m)))
      (s/check! (true? (:clean m)) "a committed record is not :clean")
      (s/check! (= want (:files m)) (str "the files: " (pr-str (:files m)) "\nnot " (pr-str want)))
      ;; Dirty: a changed document under the documents directory.
      (cs/write! ctx cs/plan (str (slurp (str (fs/file (:dir ctx) cs/plan))) "\nA change.\n"))
      (let [out2 (str (fs/file (kc/tmp-dir) "out"))
            r (kc/run! ctx (:dir ctx) ["audit" out2])]
        (s/check! (zero? (:exit r)) (s/show r))
        (s/check! (false? (:clean (edn/read-string (slurp (str (fs/file out2 "manifest.edn"))))))
                  "a changed document leaves the manifest :clean"))
      nil)))

(defstep #"a releasable regulated project whose every clause is named in committed documents"
  (fn [ctx] (cs/releasable! ctx)))

(defstep #"an untracked document dhf/documents/draft\.md"
  (fn [ctx]
    (cs/write! ctx "dhf/documents/draft.md"
               (cs/document [["id" "DRAFT-001"] ["revision" "1"] ["title" "Draft"]] "A draft [[STD:5.1]].\n"))))

(defstep #"OUT holds no copy of draft\.md, and OUT/manifest\.edn says :clean false"
  (fn [ctx]
    (exits-0! ctx)
    (let [fs* (files-under (cs/out-file ctx "OUT"))]
      (s/check! (not-any? #(str/includes? % "draft") fs*) (str "OUT holds a draft: " fs*))
      (s/check! (some #{"auditor/plan.md"} fs*) (str "OUT holds no plan.md: " fs*))
      (s/check! (false? (:clean (manifest ctx))) "the manifest says :clean true")
      nil)))

(defstep #"\"rdm-spec ([^\"]*)\" is run, then \"rdm-spec ([^\"]*)\""
  (fn [ctx a b]
    (let [ctx (kc/run-line ctx a)
          first-run (:run ctx)
          ctx (kc/run-line ctx b)]
      (assoc ctx :runs [first-run (:run ctx)]))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (doseq [r (:runs ctx)] (s/check! (zero? (:exit r)) (s/show r)))
    (let [a (cs/out-file ctx "OUT") b (cs/out-file ctx "OUT2")
          fa (files-under a)]
      (s/check! (some #(str/ends-with? % ".pdf") fa) "OUT holds no PDF")
      (s/check! (some #{"manifest.edn"} fa) "OUT holds no manifest")
      (s/check! (= fa (files-under b)) "OUT and OUT2 hold other files")
      (doseq [f fa]
        (s/check! (= (sha256 (fs/file a f)) (sha256 (fs/file b f))) (str f " differs between OUT and OUT2")))
      nil)))
