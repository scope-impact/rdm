(ns steps.compliance-gap
  "Steps of the components Gap analysis and Audit copy (10-regulated-profile/gap.feature, audit.feature)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn clauses! [ctx text] (k/write! (:dir ctx) "dhf/clauses/std.txt" text) ctx)
(defn plan! [ctx body & [fm]] (k/write! (:dir ctx) "dhf/documents/plan.md" (k/doc-text (merge {:body body} fm))) ctx)

(defn recommit! [ctx] (when (fs/exists? (k/path (:dir ctx) ".git/HEAD")) (when (:committed ctx) (k/commit-all! (:dir ctx) "and more"))) ctx)

(defn finding-for [ctx key] (some #(when (and (str/includes? % "clause-unreferenced") (re-find (re-pattern (str "(?<![A-Za-z0-9.:_-])" (java.util.regex.Pattern/quote key) "(?![A-Za-z0-9_])")) %)) %) (k/err-lines ctx)))

;; ------------------------------------------------------------------ gap

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})})))

(defstep #"a file dhf/documents/plan.md that names no clause"
  (fn [ctx] (k/write! (:dir ctx) "dhf/documents/plan.md" "# A plan\n\nNo clause here, but STD:5.1 and [[STD:5.2]] in passing.\n") ctx))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (not (re-find #"clause|dhf/documents|dhf/clauses" (str (:err ctx)))) ctx "a line names the profile")
    ctx))

(defstep #"a regulated project" (fn [_] (k/regulated-project!)))

(defstep #"dhf/clauses/std.txt holds:"
  (fn [ctx doc] (clauses! ctx doc)))

(defstep #"dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [ctx] (k/write! (:dir ctx) "dhf/clauses/base.txt" "STD:4.1 Quality system\n") ctx))

(defstep #"no document names any clause"
  (fn [ctx] (plan! ctx "We plan nothing in particular.\n")))

(defstep #"it exits 1 with one clause-unreferenced finding for each of STD:4.1, STD:5.1 and STD:5.2, and none for any other key"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [ls (filter #(str/includes? % "clause-unreferenced") (k/err-lines ctx))]
      (k/expect! (= 3 (count ls)) ctx (str (count ls) " findings"))
      (doseq [key ["STD:4.1" "STD:5.1" "STD:5.2"]] (k/expect! (finding-for ctx key) ctx (str "no finding for " key))))
    ctx))

(defstep #"dhf/clauses/std.txt holds only comments"
  (fn [ctx] (clauses! ctx "# a list\n# with nothing in it\n")))

(defstep #"it exits 2 and says the clause list dhf/clauses/std.txt holds no clause"
  (fn [ctx] (k/exits! ctx 2) (k/line-has! ctx "dhf/clauses/std.txt" "holds no clause") ctx))

(defstep #"dhf/clauses/std.txt holds \"include missing.txt\""
  (fn [ctx] (clauses! ctx "include missing.txt\n")))

(defstep #"it exits 2 and names dhf/clauses/missing.txt"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/clauses/missing.txt") ctx))

(defstep #"dhf/clauses/std.txt holds \"STD:5.1 Development plan\" and the line \"Quality system\""
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\nQuality system\n")))

(defstep #"it exits 1 with a finding naming dhf/clauses/std.txt and the line of \"Quality system\", and STD:5.1 is the only clause"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/line-has! ctx "dhf/clauses/std.txt:2:")
    (k/expect! (not (some #(str/includes? % "clause-unreferenced") (k/err-lines ctx))) ctx "a clause is unreferenced: Quality became a clause")
    ctx))

(defstep #"dhf/clauses/std.txt holds the clauses STD:5.1 and STD:5.2"
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and nothing else"
  (fn [ctx] (plan! ctx "We plan [[STD:5.1]].\n")))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std.txt, the line of STD:5.2, the key and its description"
  (fn [ctx] (k/line-has! ctx "dhf/clauses/std.txt:2:" "clause-unreferenced" "STD:5.2" "Requirements analysis") ctx))

(defstep #"dhf/clauses/std.txt holds the one clause STD:5.1"
  (fn [ctx] (clauses! ctx "STD:5.1 Development plan\n")))

(defstep #"dhf/documents/plan.md holds the line (.+)"
  (fn [ctx line] (plan! ctx (str "Some words.\n\n" line "\n"))))

(defstep #"the clause STD:5.1 is (named|not named)"
  (fn [ctx named]
    (k/expect! (#{0 1} (:exit ctx)) ctx "the check did not run")
    (if (= named "named")
      (k/expect! (not (finding-for ctx "STD:5.1")) ctx "STD:5.1 is not named")
      (k/expect! (finding-for ctx "STD:5.1") ctx "STD:5.1 is named"))
    ctx))

(defstep #"dhf/documents/plan.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding \"\[\[STD:5.1\]\]\""
  (fn [ctx] (plan! ctx "- The plan\n\n    This item meets [[STD:5.1]].\n")))

(defstep #"dhf/documents/plan.md holds, after a blank line, a line of four spaces then \"\[\[STD:5.1\]\]\""
  (fn [ctx] (plan! ctx "Some words.\n\n    [[STD:5.1]]\n")))

(defstep #"dhf/documents/plan.md holds \"An open \[\[ tag\", a blank line, and \"STD:5.1 \]\]\""
  (fn [ctx] (plan! ctx "An open [[ tag\n\nSTD:5.1 ]]\n")))

(defstep #"dhf/documents/plan.md names STD:5.1 and STD:9.9 in audit tags"
  (fn [ctx] (plan! ctx "We plan [[STD:5.1]].\n\nWe also [[STD:9.9]].\n")))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan.md, the line of the tag and STD:9.9"
  (fn [ctx] (k/line-has! ctx "dhf/documents/plan.md:9:" "warning: clause-unknown" "STD:9.9") ctx))

(defstep #"dhf/documents/plan.md has the title \"Development plan \[\[STD:5.1\]\]\" and a body that names no clause"
  (fn [ctx] (plan! ctx "We plan nothing.\n" {:title "Development plan [[STD:5.1]]"})))

(defstep #"dhf/documents/plan.md names STD:5.1 in an audit tag and holds a line \"TODO: write the plan\""
  (fn [ctx] (plan! ctx "We plan [[STD:5.1]].\n\nTODO: write the plan\n")))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan.md and the line holding TODO"
  (fn [ctx] (k/line-has! ctx "dhf/documents/plan.md:9:" "warning: document-placeholder") ctx))

(defstep #"dhf/documents/plan.md has frontmatter with an id and a title but no revision"
  (fn [ctx] (plan! ctx "We plan [[STD:5.1]].\n" {:revision nil})))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan.md and the missing revision"
  (fn [ctx] (k/line-has! ctx "dhf/documents/plan.md" "document-unidentified" "revision") ctx))

(defstep #"dhf/documents/plan.md and dhf/documents/old-plan.md both have the id PLAN-001"
  (fn [ctx] (k/write! (:dir ctx) "dhf/documents/old-plan.md" (k/doc-text {:title "Old plan"})) ctx))

(defstep #"it exits 1 and names the id PLAN-001 and both files"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "PLAN-001" "dhf/documents/plan.md" "dhf/documents/old-plan.md") ctx))

;; ------------------------------------------------------------------ the audit copy

(defn out [ctx & parts] (apply k/path (get-in ctx [:vars "OUT"]) parts))
(defn out-text [ctx rel] (let [f (out ctx rel)] (when (fs/exists? f) (slurp f))))

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [_] (let [ctx (k/regulated-project!)]
            (k/result! (k/path (:dir ctx) "dhf/allure-results") "spec-0001" "failed")
            (k/pass-all! ctx ["spec-0098"])
            (k/git! (:dir ctx) "init" "-q") (k/commit-all! (:dir ctx) "all")
            (assoc (k/var-dir! ctx "OUT") :release (k/rdm! ctx "release")))))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [rel (k/finding-lines (:release ctx))]
      (k/expect! (seq rel) ctx "the release check found nothing")
      (doseq [l rel] (k/expect! (some #{l} (k/err-lines ctx)) ctx (str "not printed unchanged: " l))))
    (k/expect! (not (fs/exists? (out ctx))) ctx "OUT was created")
    ctx))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [_] (let [ctx (k/releasable! :clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")] ctx)))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "STD:5.2") (k/expect! (not (fs/exists? (out ctx))) ctx "OUT was created") ctx))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no :regulated") ctx))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [_] (let [ctx (k/var-dir! (k/releasable!) "OUT")]
            (k/write! (out ctx) "mine.txt" "mine\n")
            ctx)))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx (out ctx))
    (k/expect! (= ["mine.txt"] (keys (k/tree-snapshot (out ctx)))) ctx "OUT changed")
    (k/expect! (= "mine\n" (out-text ctx "mine.txt")) ctx "the file changed")
    ctx))

(defstep #"a releasable regulated project whose dhf/documents/plan.md holds the line \"We plan the work \[\[STD:5.1\]\].\""
  (fn [_] (k/releasable! :docs {"plan.md" (k/doc-text {:body "Intro.\n\nWe plan the work [[STD:5.1]].\n\nOutro.\n"})})))

(defstep #"OUT/(auditor|engineer)/plan.md holds the line \"(We plan the work(?: \[\[STD:5.1\]\])?\.)\""
  (fn [ctx who line]
    (k/expect! (some #{line} (str/split-lines (str (out-text ctx (str who "/plan.md"))))) ctx (str who "'s copy lacks the line " line))
    ctx))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (str/split-lines (out-text ctx "auditor/plan.md")) e (str/split-lines (out-text ctx "engineer/plan.md"))]
      (k/expect! (= (count a) (count e)) ctx "the copies have different lines")
      (k/expect! (= 1 (count (remove true? (map = a e)))) ctx "more than that line differs"))
    ctx))

(def written
  {"the line \"We plan [[STD:5.1]].\"" "We plan [[STD:5.1]].\n"
   "the line \"We plan [[STD:5.1]] and test [[STD:5.2]].\"" "We plan [[STD:5.1]] and test [[STD:5.2]].\n"
   "a tag that opens on one line and closes on the next" "We plan [[STD:5.1\nis met]] the work.\n"
   "the line \"We plan `[[STD:5.1]]`.\"" "We plan `[[STD:5.1]]`.\n\nWe meet [[STD:5.1]].\n"
   "a \"[[\" with no \"]]\" before the paragraph's blank line" "We plan [[ an open tag\nand more.\n\nWe meet [[STD:5.1]].\n"
   "a fenced code block holding [[STD:5.1]]" "We meet [[STD:5.1]].\n\n```\ncode [[STD:5.1]]\n```\n"
   "an indented code block holding [[STD:5.1]]" "We meet [[STD:5.1]].\n\n    code [[STD:5.1]]\n"
   "the title \"Development plan [[STD:5.1]]\" in its frontmatter" "We meet [[STD:5.1]].\n"})

(defstep (re-pattern (str "a releasable regulated project whose dhf/documents/plan.md holds (" (str/join "|" (map #(java.util.regex.Pattern/quote %) (keys written))) ")"))
  (fn [_ w]
    (let [body (or (written w) (fail! (str "no fixture for " w)))
          title (if (str/starts-with? w "the title") "Development plan [[STD:5.1]]" "Development plan")]
      (assoc (k/releasable! :clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"
                            :docs {"plan.md" (k/doc-text {:title title :body (str body "\nAnd [[STD:5.2]].\n")})})
             :written w))))

(defstep #"OUT/engineer/plan.md holds (the line \"We plan.\"|the line \"We plan and test.\"|both lines without the tag|that line unchanged|the paragraph unchanged|the code block unchanged|the title \"Development plan\")"
  (fn [ctx kept]
    (k/exits! ctx 0)
    (let [t (out-text ctx "engineer/plan.md") ls (str/split-lines t)]
      (case kept
        "the line \"We plan.\"" (k/expect! (some #{"We plan."} ls) ctx t)
        "the line \"We plan and test.\"" (k/expect! (some #{"We plan and test."} ls) ctx t)
        "both lines without the tag" (k/expect! (and (some #{"We plan"} ls) (some #{" the work."} ls)) ctx t)
        "that line unchanged" (k/expect! (some #{"We plan `[[STD:5.1]]`."} ls) ctx t)
        "the paragraph unchanged" (k/expect! (and (some #{"We plan [[ an open tag"} ls) (some #{"and more."} ls)) ctx t)
        "the code block unchanged" (k/expect! (or (some #{"code [[STD:5.1]]"} ls) (some #{"    code [[STD:5.1]]"} ls)) ctx t)
        "the title \"Development plan\"" (k/expect! (some #{"title: Development plan"} ls) ctx t)))
    ctx))

(defstep #"a releasable regulated project with dhf/documents/plan.md and dhf/documents/risk/register.md"
  (fn [_] (k/releasable! :docs {"plan.md" (k/doc-text {}) "risk/register.md" (k/doc-text {:id "RISK-001" :title "Risk register" :body "Risks.\n"})})))

(defstep #"OUT/auditor and OUT/engineer each hold plan.md and risk/register.md"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [w ["auditor" "engineer"] f ["plan.md" "risk/register.md"]] (k/expect! (fs/exists? (out ctx w f)) ctx (str w "/" f)))
    ctx))

(defstep #"a releasable regulated project whose dhf/documents/plan.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [_] (k/releasable! :docs {"plan.md" (k/doc-text {:revision "3" :body "We plan the work [[STD:5.1]].\n"})})))

(defstep #"OUT/auditor/plan.pdf and OUT/engineer/plan.pdf exist"
  (fn [ctx] (k/exits! ctx 0)
    (doseq [w ["auditor" "engineer"]] (k/expect! (fs/exists? (out ctx w "plan.pdf")) ctx (str "no " w "/plan.pdf"))) ctx))

(defn pdf-text [f & [first-page]]
  (:out (apply p/shell {:out :string :err :string} (concat ["pdftotext"] (when first-page ["-f" "1" "-l" "1"]) [(str f) "-"]))))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [w ["auditor" "engineer"] :let [t (pdf-text (out ctx w "plan.pdf") true)]]
      (k/expect! (and (str/includes? t "PLAN-001") (re-find #"(?i)revision\s*3" t) (str/includes? t "Development plan")) ctx (str w "'s first page: " t)))
    ctx))

(defstep #"no text of OUT/engineer/plan.pdf holds \"\[\[\""
  (fn [ctx] (k/expect! (not (str/includes? (pdf-text (out ctx "engineer" "plan.pdf")) "[[")) ctx "the engineer's PDF holds [[") ctx))

(defstep #"a releasable regulated project whose dhf/clauses/std.txt holds \"include base.txt\" then \"STD:5.1 Development plan\", and whose dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [_] (let [ctx (k/regulated-project! :clauses "include base.txt\nSTD:5.1 Development plan\n")]
            (k/write! (:dir ctx) "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
            ctx)))

(defstep #"STD:5.1 is named on line 8 of dhf/documents/plan.md and line 9 of dhf/documents/risk/register.md"
  (fn [ctx]
    ;; frontmatter takes lines 1 to 5, then a blank line
    (k/write! (:dir ctx) "dhf/documents/plan.md" (k/doc-text {:body "Line seven [[STD:4.1]].\nLine eight [[STD:5.1]].\n"}))
    (k/write! (:dir ctx) "dhf/documents/risk/register.md" (k/doc-text {:id "RISK-001" :title "Risks" :body "Seven.\nEight.\nNine [[STD:5.1: risks]].\n"}))
    (k/pass-all! ctx ["spec-0001" "spec-0098"])
    (k/git! (:dir ctx) "init" "-q")
    (k/commit-all! (:dir ctx) "releasable")
    ctx))

(defstep #"OUT/clauses.md has one section per declared clause list, dhf/clauses/std.txt, and no section for dhf/clauses/base.txt"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (out-text ctx "clauses.md") hs (filter #(str/starts-with? % "## ") (str/split-lines t))]
      (k/expect! (= 1 (count hs)) ctx (str "the sections are " hs))
      (k/expect! (str/includes? (first hs) "dhf/clauses/std.txt") ctx "not std.txt's"))
    ctx))

(defstep #"under it STD:4.1 comes first, where its include stands, then a row for STD:5.1 with \"Development plan\", dhf/documents/plan.md:8 and dhf/documents/risk/register.md:9"
  (fn [ctx]
    (let [t (out-text ctx "clauses.md")
          row (some #(when (str/includes? % "STD:5.1") %) (str/split-lines t))]
      (k/expect! (< (str/index-of t "STD:4.1") (str/index-of t "STD:5.1")) ctx "STD:4.1 is not first")
      (doseq [x ["Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]]
        (k/expect! (str/includes? (str row) x) ctx (str "the row lacks " x))))
    ctx))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [_] (let [ctx (k/regulated-project! :features {"shop/cart.feature" (k/feature-text {:name "Cart" :tags [] :scenarios [{:id "spec-0001" :tags ["UN-001"] :title "First"} {:id "spec-0002" :tags ["UN-001"] :title "Second"}]})
                                                     "shop/till.feature" (k/feature-text {:name "Till" :tags ["UN-002"] :scenarios [{:id "spec-0098"}]})})]
            (k/pass-all! ctx ["spec-0001" "spec-0002" "spec-0098"])
            (k/git! (:dir ctx) "init" "-q") (k/commit-all! (:dir ctx) "releasable")
            ctx)))

(defstep #"OUT/traceability.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (out-text ctx "traceability.md")
          sect (second (str/split t #"(?m)^## UN-001"))
          sect (first (str/split (str sect) #"(?m)^## "))]
      (k/expect! (str/includes? (str sect) "A buyer buys.") ctx "not under UN-001 and its text")
      (doseq [[id title] [["spec-0001" "First"] ["spec-0002" "Second"]]]
        (k/expect! (some #(and (str/includes? % id) (str/includes? % title) (str/includes? % "Cart") (str/includes? % "verified")) (str/split-lines (str sect))) ctx (str id "'s line"))))
    ctx))

(defstep #"a releasable regulated project and its results directory"
  (fn [_] (k/releasable!)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= (k/tree-snapshot (k/path (:dir ctx) "dhf/allure-results")) (k/tree-snapshot (out ctx "results"))) ctx "not a copy")
    ctx))

(defstep #"a releasable regulated project(?:, all committed)?" (fn [_] (k/releasable!)))

(defstep #"OUT/manifest.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [m (edn/read-string (out-text ctx "manifest.edn"))
          files (sort (remove #{"manifest.edn"} (keys (k/tree-snapshot (out ctx)))))]
      (k/expect! (= #{:commit :files} (set (keys m))) ctx "not exactly {:commit :files}")
      (k/expect! (= (k/git! (:dir ctx) "rev-parse" "HEAD") (:commit m)) ctx "not HEAD's commit")
      (k/expect! (= files (map :path (:files m))) ctx "not every other file, sorted")
      (let [f (first (:files m))
            sha (str/trim (first (str/split (:out (p/shell {:out :string} "sha256sum" (out ctx (:path f)))) #" ")))]
        (k/expect! (= sha (:sha256 f)) ctx "the sha256 is wrong")))
    ctx))

(defstep #"an untracked document dhf/documents/draft.md"
  (fn [ctx] (k/write! (:dir ctx) "dhf/documents/draft.md" (k/doc-text {:id "DRAFT-1" :title "Draft"})) (assoc ctx :uncommitted "dhf/documents/draft.md")))

(defstep #"a document dhf/documents/draft.md that git ignores"
  (fn [ctx]
    (k/write! (:dir ctx) ".gitignore" (str (k/slurp-in (:dir ctx) ".gitignore") "dhf/documents/draft.md\n"))
    (k/commit-all! (:dir ctx) "ignore the draft")
    (k/write! (:dir ctx) "dhf/documents/draft.md" (k/doc-text {:id "DRAFT-1" :title "Draft"}))
    (assoc ctx :uncommitted "dhf/documents/draft.md")))

(defstep #"an uncommitted change to a document"
  (fn [ctx] (k/write! (:dir ctx) "dhf/documents/plan.md" (k/doc-text {:body "We plan [[STD:5.1]], changed.\n"})) (assoc ctx :uncommitted "dhf/documents/plan.md")))

(defstep #"an uncommitted change to a scenario's title"
  (fn [ctx] (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :title "Retitled"}]}))
    (assoc ctx :uncommitted "dhf/spec/shop/cart.feature")))

(defstep #"an uncommitted change to the clause list"
  (fn [ctx] (k/write! (:dir ctx) "dhf/clauses/std.txt" "STD:5.1 Development plan, retitled\n") (assoc ctx :uncommitted "dhf/clauses/std.txt")))

(defstep #"an uncommitted change to the configuration"
  (fn [ctx] (k/edit-config! (:dir ctx) #(assoc % :stack-words ["Cobalt"])) (assoc ctx :uncommitted "dhf/harness/config.edn")))

(defstep #"it exits 1, names (dhf/documents/draft.md|that document|that feature file|dhf/clauses/std.txt|dhf/harness/config.edn) as not committed, and creates no OUT"
  (fn [ctx _]
    (k/exits! ctx 1)
    (k/line-has! ctx (:uncommitted ctx) "not committed")
    (k/expect! (not (fs/exists? (out ctx))) ctx "OUT was created")
    ctx))

(defstep #"\"rdm-spec audit OUT\" is run, then \"rdm-spec audit OUT2\""
  (fn [ctx]
    (let [ctx (-> ctx (k/var-dir! "OUT") (k/var-dir! "OUT2"))
          a (k/rdm! ctx "audit OUT")]
      (k/exits! a 0)
      (k/rdm! a "audit OUT2"))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [snap (fn [d] (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                                             [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))
          a (snap (out ctx)) b (snap (k/path (get-in ctx [:vars "OUT2"])))]
      (k/expect! (some #(str/ends-with? % ".pdf") (keys a)) ctx "no PDF")
      (k/expect! (= (keys a) (keys b)) ctx "the files differ")
      (doseq [[f v] a] (k/expect! (= v (get b f)) ctx (str f " differs"))))
    ctx))
