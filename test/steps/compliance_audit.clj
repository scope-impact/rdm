(ns steps.compliance-audit
  "Steps of the component Audit copy (dhf/spec/10-regulated-profile/audit.feature).
  A releasable regulated project: every scenario verified by a result in the
  configured results directory, every clause named, everything committed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.compliance-gap :as g]))

(defn releasable!
  "A regulated project whose scenarios all pass, every clause named, committed.
  PLAN is plan.md's body; DOCS more documents."
  [ctx & [{:keys [plan docs clauses fm results]}]]
  (let [ctx (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                             (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "One"} {:id "spec-0002" :title "Two"}])}})]
    (k/config! ctx assoc :regulated g/regulated)
    (k/write! ctx "dhf/clauses/std.txt" (or clauses "STD:5.1 Development plan\n"))
    (k/write! ctx "dhf/documents/plan.md" (g/doc-text (or plan "# Plan\n\nWe plan the work [[STD:5.1]].\n") fm))
    (doseq [[f t] docs] (k/write! ctx f t))
    (k/commit! ctx "the record")
    (doseq [[id st] (or results {"spec-0001" "passed" "spec-0002" "passed"})]
      (k/result-json! ctx "dhf/allure-results" {:spec id :status st}))
    ctx))

(defn out [ctx & [rel]] (str (get-in ctx [:ph "OUT"] (str (:base ctx) "/out")) (when rel (str "/" rel))))

(defn no-out! [ctx] (k/check! (not (fs/exists? (out ctx))) "OUT was created") ctx)

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx] (releasable! ctx {:results {"spec-0001" "passed" "spec-0002" "failed"}})))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [rel (k/run-rdm ctx "release")]
      (doseq [l (remove #(re-find #"warning: " %) (k/err-lines rel))]
        (k/check! (some #{l} (k/err-lines ctx)) "the release's line is not printed: " l)))
    (no-out! ctx)))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx] (releasable! ctx {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"})))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "STD:5.2") (no-out! ctx)))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "the configuration holds no :regulated") ctx))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx] (let [ctx (releasable! ctx)] (fs/create-dirs (out ctx)) (spit (out ctx "mine.txt") "mine\n") ctx)))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-has! ctx (out ctx))
    (k/check! (= "mine\n" (slurp (out ctx "mine.txt"))) "the file changed")
    (k/check! (= ["mine.txt"] (map #(str (fs/file-name %)) (fs/list-dir (out ctx)))) "OUT was written into")
    ctx))

(defn lines-of [f] (str/split-lines (slurp f)))

(defstep #"a releasable regulated project whose dhf/documents/plan.md holds the line \"We plan the work \[\[STD:5.1\]\].\""
  (fn [ctx] (releasable! ctx)))

(defstep #"OUT/auditor/plan.md holds the line \"We plan the work \[\[STD:5.1\]\].\""
  (fn [ctx] (k/check! (some #{"We plan the work [[STD:5.1]]."} (lines-of (out ctx "auditor/plan.md"))) "the auditor's copy lacks the line") ctx))

(defstep #"OUT/engineer/plan.md holds the line \"We plan the work.\""
  (fn [ctx] (k/check! (some #{"We plan the work."} (lines-of (out ctx "engineer/plan.md"))) "the engineer's copy lacks the line") ctx))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (lines-of (out ctx "auditor/plan.md")) e (lines-of (out ctx "engineer/plan.md"))]
      (k/check! (= (count a) (count e)) "the copies have different line counts")
      (k/check! (= 1 (count (remove true? (map = a e)))) "more than that line differs")
      ctx)))

(def written
  {"the line \"We plan [[STD:5.1]].\"" "We plan [[STD:5.1]]."
   "the line \"We plan [[STD:5.1]] and test [[STD:5.2]].\"" "We plan [[STD:5.1]] and test [[STD:5.2]]."
   "a tag that opens on one line and closes on the next" "We plan [[STD:5.1\nfor the plan]] this way."
   "the line \"We plan `[[STD:5.1]]`.\"" "We plan `[[STD:5.1]]`.\n\nIt is met [[STD:5.1]] here."
   "a \"[[\" with no \"]]\" before the paragraph's blank line" "We plan [[ an open note\nthat never closes.\n\nIt is met [[STD:5.1]] here."
   "a fenced code block holding [[STD:5.1]]" "```\ncode [[STD:5.1]]\n```\n\nIt is met [[STD:5.1]] here."
   "an indented code block holding [[STD:5.1]]" "Some text.\n\n    code [[STD:5.1]]\n\nIt is met [[STD:5.1]] here."
   "the title \"Development plan [[STD:5.1]]\" in its frontmatter" "It is met [[STD:5.1]] here."})

(defstep #"a releasable regulated project whose dhf/documents/plan.md holds (the line \"We plan \[\[STD:5.1\]\]\.\"|the line \"We plan \[\[STD:5.1\]\] and test \[\[STD:5.2\]\]\.\"|a tag that opens on one line and closes on the next|the line \"We plan `\[\[STD:5.1\]\]`\.\"|a \"\[\[\" with no \"\]\]\" before the paragraph's blank line|a fenced code block holding \[\[STD:5.1\]\]|an indented code block holding \[\[STD:5.1\]\]|the title \"Development plan \[\[STD:5.1\]\]\" in its frontmatter)"
  (fn [ctx w]
    (-> (releasable! ctx {:plan (str "# Plan\n\n" (written w) "\n")
                          :fm (when (str/starts-with? w "the title") {"title" "Development plan [[STD:5.1]]"})})
        (assoc ::written w))))

(defstep #"OUT/engineer/plan.md holds (the line \"We plan\.\"|the line \"We plan and test\.\"|both lines without the tag|that line unchanged|the paragraph unchanged|the code block unchanged|the title \"Development plan\")"
  (fn [ctx kept]
    (k/exits! ctx 0)
    (let [e (slurp (out ctx "engineer/plan.md"))
          w (::written ctx)]
      (k/check! (case kept
                  "the line \"We plan.\"" (some #{"We plan."} (str/split-lines e))
                  "the line \"We plan and test.\"" (some #{"We plan and test."} (str/split-lines e))
                  "both lines without the tag" (str/includes? e "We plan\n this way.")
                  "that line unchanged" (str/includes? e "We plan `[[STD:5.1]]`.")
                  "the paragraph unchanged" (str/includes? e "We plan [[ an open note\nthat never closes.")
                  "the code block unchanged" (str/includes? e (if (str/includes? w "fenced") "```\ncode [[STD:5.1]]\n```" "    code [[STD:5.1]]"))
                  "the title \"Development plan\"" (str/includes? e "title: Development plan\n"))
                "the engineer's copy does not hold " kept ":\n" e)
      ctx)))

(defstep #"a releasable regulated project with dhf/documents/plan.md and dhf/documents/risk/register.md"
  (fn [ctx] (releasable! ctx {:docs {"dhf/documents/risk/register.md" (g/doc-text "# Risks\n\nNone yet [[STD:5.1]].\n" {"id" "RISK-001" "title" "Risk register"})}})))

(defstep #"OUT/auditor and OUT/engineer each hold plan.md and risk/register.md"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [c ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (k/check! (fs/exists? (out ctx (str c "/" f))) "OUT/" c "/" f " is missing"))
    ctx))

(defstep #"a releasable regulated project whose dhf/documents/plan.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx] (releasable! ctx {:fm {"revision" "3"}})))

(defn pdf-text [ctx f & [first-page?]]
  (:out @(p/process (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [f "-"]) {:out :string :err :string :env (k/base-env ctx)})))

(defstep #"OUT/auditor/plan.pdf and OUT/engineer/plan.pdf exist"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [c ["auditor" "engineer"]] (k/check! (fs/exists? (out ctx (str c "/plan.pdf"))) c "/plan.pdf is missing"))
    ctx))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [c ["auditor" "engineer"]]
      (let [t (pdf-text ctx (out ctx (str c "/plan.pdf")) true)]
        (doseq [s ["PLAN-001" "3" "Development plan"]] (k/check! (str/includes? t s) c "'s first page does not hold " s ":\n" t))
        (k/check! (re-find #"(?i)revision\s*3" t) c "'s first page does not hold the revision 3")))
    ctx))

(defstep #"no text of OUT/engineer/plan.pdf holds \"\[\[\""
  (fn [ctx] (k/check! (not (str/includes? (pdf-text ctx (out ctx "engineer/plan.pdf")) "[[")) "the engineer's PDF holds [[") ctx))

(defstep #"a releasable regulated project whose dhf/clauses/std.txt holds \"include base.txt\" then \"STD:5.1 Development plan\", and whose dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [ctx]
    (let [ctx (releasable! ctx {:clauses "include base.txt\nSTD:5.1 Development plan\n"
                                :plan "# Plan\nLine 8 [[STD:5.1]].\n\nQuality [[STD:4.1]].\n"
                                :docs {"dhf/documents/risk/register.md" (g/doc-text "# Risks\n\nLine 9 [[STD:5.1]].\n" {"id" "RISK-001"})}})]
      (k/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
      (k/commit! ctx "the base list"))))

(defstep #"STD:5.1 is named on line 8 of dhf/documents/plan.md and line 9 of dhf/documents/risk/register.md"
  (fn [ctx]
    (k/check! (= "Line 8 [[STD:5.1]]." (nth (str/split-lines (k/slurp-rel ctx "dhf/documents/plan.md")) 7)) "line 8 of plan.md does not name STD:5.1")
    (k/check! (= "Line 9 [[STD:5.1]]." (nth (str/split-lines (k/slurp-rel ctx "dhf/documents/risk/register.md")) 8)) "line 9 of register.md does not name STD:5.1")
    ctx))

(defstep #"OUT/clauses.md has one section per declared clause list, dhf/clauses/std.txt, and no section for dhf/clauses/base.txt"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (slurp (out ctx "clauses.md"))
          heads (re-seq #"(?m)^## .*$" t)]
      (k/check! (= ["## dhf/clauses/std.txt"] heads) "the sections are " (pr-str heads) ":\n" t)
      ctx)))

(defstep #"under it STD:4.1 comes first, where its include stands, then a row for STD:5.1 with \"Development plan\", dhf/documents/plan.md:8 and dhf/documents/risk/register.md:9"
  (fn [ctx]
    (let [t (slurp (out ctx "clauses.md"))
          row (first (filter #(str/includes? % "STD:5.1") (str/split-lines t)))]
      (k/check! (< (str/index-of t "STD:4.1") (str/index-of t "STD:5.1")) "STD:4.1 does not come first")
      (k/check! (every? #(str/includes? (str row) %) ["Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]) "the row is " row)
      ctx)))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx] (releasable! ctx)))

(defstep #"OUT/traceability.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (slurp (out ctx "traceability.md"))
          sec (second (re-find #"(?s)UN-001(.*?)(?:\n## |\z)" t))]
      (k/check! (str/includes? (str sec) "A team keeps its requirements.") "the need's text is missing:\n" t)
      (doseq [[id title] [["spec-0001" "One"] ["spec-0002" "Two"]]]
        (let [row (first (filter #(str/includes? % id) (str/split-lines (str sec))))]
          (k/check! (every? #(str/includes? (str row) %) [title "Thing" "verified"]) "the row of " id " is " row)))
      ctx)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (releasable! ctx)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [f (fs/list-dir (k/path ctx "dhf/allure-results"))]
      (k/check! (= (slurp (str f)) (slurp (out ctx (str "results/" (fs/file-name f))))) (fs/file-name f) " is not copied"))
    ctx))

(defstep #"a releasable regulated project" (fn [ctx] (releasable! ctx)))

(defn sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (fs/read-all-bytes f))))))

(defstep #"OUT/manifest.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [m (edn/read-string (slurp (out ctx "manifest.edn")))
          files (sort (for [f (fs/glob (out ctx) "**") :when (fs/regular-file? f) :let [r (str (fs/relativize (out ctx) f))] :when (not= r "manifest.edn")] r))]
      (k/check! (= #{:commit :files} (set (keys m))) "the manifest holds " (pr-str (keys m)))
      (k/check! (= (k/git! ctx "rev-parse" "HEAD") (:commit m)) "the commit is " (:commit m))
      (k/check! (= (for [f files] {:path f :sha256 (sha256 (out ctx f))}) (:files m)) "the files are " (pr-str (:files m)))
      ctx)))

(defstep #"a releasable regulated project, all committed" (fn [ctx] (releasable! ctx)))

(def changes
  {"an untracked document dhf/documents/draft.md" ["dhf/documents/draft.md" (fn [ctx] (k/write! ctx "dhf/documents/draft.md" (g/doc-text "Draft [[STD:5.1]].\n" {"id" "DRAFT-1"})))]
   "a document dhf/documents/draft.md that git ignores" ["dhf/documents/draft.md"
                                                         (fn [ctx] (k/write! ctx ".gitignore" (str (k/slurp-rel ctx ".gitignore") "dhf/documents/draft.md\n"))
                                                           (k/commit! ctx "ignore the draft")
                                                           (k/write! ctx "dhf/documents/draft.md" (g/doc-text "Draft [[STD:5.1]].\n" {"id" "DRAFT-1"})))]
   "an uncommitted change to a document" ["dhf/documents/plan.md" (fn [ctx] (k/write! ctx "dhf/documents/plan.md" (str (k/slurp-rel ctx "dhf/documents/plan.md") "\nMore.\n")))]
   "an uncommitted change to a scenario's title" ["dhf/spec/demo/thing.feature" (fn [ctx] (k/write! ctx "dhf/spec/demo/thing.feature" (str/replace (k/slurp-rel ctx "dhf/spec/demo/thing.feature") "Scenario: One" "Scenario: One, reworded")))]
   "an uncommitted change to the clause list" ["dhf/clauses/std.txt" (fn [ctx] (k/write! ctx "dhf/clauses/std.txt" "# changed\nSTD:5.1 Development plan\n"))]
   "an uncommitted change to the configuration" ["dhf/harness/config.edn" (fn [ctx] (k/write! ctx "dhf/harness/config.edn" (str (k/slurp-rel ctx "dhf/harness/config.edn") "\n;; changed\n")))]})

(defstep #"(an untracked document dhf/documents/draft.md|a document dhf/documents/draft.md that git ignores|an uncommitted change to a document|an uncommitted change to a scenario's title|an uncommitted change to the clause list|an uncommitted change to the configuration)"
  (fn [ctx change]
    (let [[file f] (changes change)]
      (f ctx)
      (assoc ctx ::file file))))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx _]
    (k/exits! ctx 1)
    (k/err-line-has! ctx (::file ctx) "not committed")
    (no-out! ctx)))

(defstep #"\"rdm-spec audit OUT\" is run, then \"rdm-spec audit OUT2\""
  (fn [ctx] (-> ctx (k/run-rdm "audit OUT") (k/run-rdm "audit OUT2"))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [a (out ctx) b (str (:base ctx) "/out2")
          files (fn [d] (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (str (fs/relativize d f)))))]
      (k/check! (= (files a) (files b)) "the copies hold different files")
      (k/check! (some #(str/ends-with? % ".pdf") (files a)) "no PDF was written")
      (doseq [f (files a)] (k/check! (= (sha256 (str a "/" f)) (sha256 (str b "/" f))) f " differs"))
      ctx)))
