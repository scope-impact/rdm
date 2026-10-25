(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` (OUT and OUT2 are directories the scenario names: new paths, unless a
  Given makes one).

  A releasable regulated project (`project!`) is a small git repository of
  its own: the room's configuration with :regulated (the clause list
  dhf/clauses/std.txt, the documents dhf/documents), a spec of one component
  (demo/alpha: @spec-0001 and @spec-0002, both tracing to UN-001), a passing
  result for each in dhf/allure-results, the clause STD:5.1 \"Development
  plan\" and dhf/documents/quality.md naming it; Givens change the documents,
  the clauses or the results before it is committed. The When and `it exits
  N` are the kernel's; the Given of a project with no :regulated is the gap
  steps'."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-gap :as gap]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- needs-audit! [] (s/needs-built! 'rdm-spec.compliance.audit 'rdm-spec.verification.release))

;; ---------------------------------------------------------------------------
;; The fixture

(def model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                 :contexts [{:id :demo :name "demo" :description "d"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "d"}]}]}]})

(def needs {"UN-001" {:text "A first need of the audit fixture." :sources ["demo:README.md@0000000"]}})

(def scenarios
  [{:id "spec-0001" :title "The first scenario of the audit fixture"}
   {:id "spec-0002" :title "The second scenario of the audit fixture"}])

(defn- feature-text []
  (str "@UN-001\nFeature: Alpha\n  The audit fixture's component.\n\n  Rule: Demo\n"
       (str/join (for [{:keys [id title]} scenarios]
                   (str "\n    @" id " @e2e\n    Scenario: " title "\n      Given a step of " id "\n")))))

(defn- result! [dir id status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    (s/write! dir (str "dhf/allure-results/" uuid "-result.json")
              (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                                     :labels [{:name "spec" :value id}] :steps []}))))

(def quality (gap/doc-text [["id" "QUAL-001"] ["revision" "1"] ["title" "Quality manual"]]
                           "\nThe plan is kept [[STD:5.1]].\n"))

(defn project!
  "A releasable regulated project: ctx :dir. `docs` {rel-under-documents
  text} replaces the default documents; `clauses` the clause list's text;
  `statuses` {id status} the results."
  [ctx & [{:keys [docs clauses statuses extra]
           :or {docs {"quality.md" quality}
                clauses "STD:5.1 Development plan\n"
                statuses {"spec-0001" "passed" "spec-0002" "passed"}}}]]
  (needs-audit!)
  (let [dir (s/project! {:results-dir "dhf/allure-results" :regulated gap/profile})]
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (s/write! dir "dhf/spec/needs.edn" (pr-str needs))
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/events.edn" (slurp (str (fs/file s/room-root "dhf/spec/events.edn"))))
    (s/write! dir "dhf/spec/demo/alpha.feature" (feature-text))
    (s/write! dir gap/std clauses)
    (doseq [[rel text] (merge docs extra)] (s/write! dir (str "dhf/documents/" rel) text))
    (doseq [[id st] statuses] (result! dir id st))
    (let [c (gap/git-commit! dir "the record")]
      (assoc ctx :dir dir :commit c))))

(defn- out [ctx k] (or (get-in ctx [:placeholders k]) (s/fail! (str "the scenario named no " k))))

(defn- out-file [ctx rel] (fs/file (out ctx "OUT") rel))

(defn- lines [f]
  (s/check! (fs/regular-file? f) (str f " does not exist"))
  (str/split-lines (slurp (str f))))

(defn- run [ctx] (kc/the-run ctx))

(defn- created-no-out! [ctx]
  (s/check! (not (fs/exists? (out ctx "OUT"))) (str "OUT was created\n" (s/show (run ctx)))))

;; ---------------------------------------------------------------------------
;; Only a releasable record has an audit copy

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx] (project! ctx {:statuses {"spec-0001" "passed" "spec-0002" "failed"}})))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (:dir ctx) ["release"])
          release-lines (s/err-lines release)]
      (s/check! (= 1 (:exit release)) (str "the release check itself passes\n" (s/show release)))
      (s/check! (seq release-lines) "the release check names nothing")
      (s/check! (every? (set (s/err-lines r)) release-lines)
                (str "the release check's findings are not printed unchanged\n" (s/show r) "\n" (s/show release)))
      (created-no-out! ctx))))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx] (assoc (project! ctx {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"}) :gap "STD:5.2")))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % (:gap ctx)) (s/err-lines r)) (str "no finding names " (:gap ctx) "\n" (s/show r)))
      (created-no-out! ctx))))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)
          l (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
      (s/check! (str/blank? (:out r)) "a task that cannot run printed a result")
      (s/check! (and l (str/includes? l "holds no :regulated")) (str "it does not say so\n" (s/show r))))))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (project! ctx)
          d (str (fs/file (s/tmp-dir) "OUT"))]
      (s/write! d "kept.txt" "an auditor's earlier copy\n")
      (assoc-in ctx [:placeholders "OUT"] d))))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)
          d (out ctx "OUT")
          l (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
      (s/check! (and l (str/includes? l d)) (str "the refusal does not name " d "\n" (s/show r)))
      (s/check! (= ["kept.txt"] (map #(str (fs/file-name %)) (fs/list-dir d))) "OUT holds other files now")
      (s/check! (= "an auditor's earlier copy\n" (slurp (str (fs/file d "kept.txt")))) "the file changed"))))

;; ---------------------------------------------------------------------------
;; Every document has an auditor's copy and an engineer's copy

(def ^:private plan-fields gap/plan-fields)

(defn- plan-doc [body] (gap/doc-text plan-fields (str "\n# Development plan\n\n" body)))

(defn- written
  "The body plan.md holds for the phrase `what`, and what the engineer's
  copy must hold of it."
  [what]
  (if-let [[_ line] (re-matches #"the line \"(.+)\"" what)]
    {:body (str line "\n") :lines [line]}
    (case what
      "a tag that opens on one line and closes on the next"
      {:body "We plan the work [[STD:5.1 and\nSTD:5.2]] and test it.\n"
       :lines ["We plan the work [[STD:5.1 and" "STD:5.2]] and test it."]}
      "a \"[[\" with no \"]]\" before the paragraph's blank line"
      {:body "We plan [[the work\nand test it.\n\nThe next paragraph ]] closes nothing.\n"
       :lines ["We plan [[the work" "and test it."]}
      "a fenced code block holding [[STD:5.1]]"
      {:body "Before the code.\n\n```\nnamed = \"[[STD:5.1]]\"\n```\n"
       :lines ["```" "named = \"[[STD:5.1]]\"" "```"]}
      (s/fail! (str "no fixture writes " what)))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (let [{:keys [body lines]} (written what)]
      (assoc (project! ctx {:docs {"quality.md" quality "plan.md" (plan-doc body)}})
             :written lines))))

(defn- index-of-seq [xs sub]
  (first (for [i (range (inc (- (count xs) (count sub))))
               :when (= sub (subvec (vec xs) i (+ i (count sub))))]
           i)))

(defstep #"OUT/(auditor|engineer)/plan\.md holds (.+)"
  (fn [ctx copy what]
    (let [ls (lines (out-file ctx (str copy "/plan.md")))
          held? (fn [sub] (some? (index-of-seq ls sub)))]
      (if-let [[_ line] (re-matches #"the line \"(.+)\"" what)]
        (s/check! (some #{line} ls) (str copy "/plan.md does not hold " (pr-str line) ": " (pr-str ls)))
        (case what
          "both lines without the tag"
          (let [i (index-of-seq (lines (out-file ctx "auditor/plan.md")) (:written ctx))
                got (subvec (vec ls) i (+ i 2))]
            (s/check! (= ["We plan the work" "and test it."] (mapv str/trim got))
                      (str "the two lines are " (pr-str got)))
            (s/check! (not-any? #(re-find #"\[\[|\]\]|STD:" %) got) (str "a line keeps the tag: " (pr-str got))))
          ("that line unchanged" "the paragraph unchanged" "the code block unchanged")
          (s/check! (held? (:written ctx)) (str copy "/plan.md does not hold " (pr-str (:written ctx)) ": " (pr-str ls)))
          (s/fail! (str "no check reads " what)))))))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (lines (out-file ctx "auditor/plan.md"))
          e (lines (out-file ctx "engineer/plan.md"))
          differ (keep-indexed (fn [i [x y]] (when (not= x y) i)) (map vector a e))]
      (s/check! (= (count a) (count e)) "the copies have different numbers of lines")
      (s/check! (= 1 (count differ)) (str "the copies differ on lines " (pr-str (map inc differ))))
      (s/check! (= (first (:written ctx)) (nth a (first differ))) "they differ on another line"))))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (project! ctx {:extra {"plan.md" (plan-doc "We plan.\n")
                           "risk/register.md" (gap/doc-text [["id" "RISK-001"] ["revision" "1"] ["title" "Risk register"]]
                                                            "\nEvery risk.\n")}})))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [copy ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (s/check! (fs/regular-file? (out-file ctx (str copy "/" f))) (str "OUT/" copy "/" f " does not exist")))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id (\S+), the revision (\S+) and the title \"([^\"]+)\""
  (fn [ctx id revision title]
    (assoc (project! ctx {:extra {"plan.md" (gap/doc-text [["id" id] ["revision" revision] ["title" (str "\"" title "\"")]]
                                                          "\nWe plan the work [[STD:5.1]].\n")}})
           :identity [id revision title])))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [c ["auditor" "engineer"]]
      (s/check! (fs/regular-file? (out-file ctx (str c "/plan.pdf"))) (str "no OUT/" c "/plan.pdf")))))

(defn- pdf-text [f & [first-page?]]
  (let [r (apply p/shell {:out :string :err :string :continue true}
                 (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext could not read " f ": " (:err r)))
    (:out r)))

(defstep #"the text of each one's first page holds (\S+), the revision (\S+) and \"([^\"]+)\""
  (fn [ctx id revision title]
    (doseq [c ["auditor" "engineer"]
            :let [t (pdf-text (out-file ctx (str c "/plan.pdf")) true)]]
      (s/check! (str/includes? t id) (str c "'s first page does not name " id ": " t))
      (s/check! (re-find (re-pattern (str "(?i)revision\\s+" (java.util.regex.Pattern/quote revision) "\\b")) t)
                (str c "'s first page does not name the revision " revision ": " t))
      (s/check! (str/includes? t title) (str c "'s first page does not name " title ": " t)))))

(defstep #"no text of OUT/engineer/plan\.pdf holds \"([^\"]+)\""
  (fn [ctx needle]
    (let [t (pdf-text (out-file ctx "engineer/plan.pdf"))]
      (s/check! (not (str/includes? t needle)) (str "the engineer's PDF holds " needle ": " t))
      (s/check! (str/includes? (pdf-text (out-file ctx "auditor/plan.pdf")) needle)
                "the auditor's PDF holds no audit tag either: the check reads nothing"))))

;; ---------------------------------------------------------------------------
;; The audit copy carries the evidence it rests on

(defstep #"a releasable regulated project whose clause (\S+) \"([^\"]+)\" is named on line (\d+) of dhf/documents/plan\.md and line (\d+) of dhf/documents/risk/register\.md"
  (fn [ctx k description pl rl]
    (let [pad (fn [fields n tail]
                ;; a document whose line n names the clause: the frontmatter,
                ;; the tag on line n (in the frontmatter when n falls there)
                (let [head (str/split-lines (gap/doc-text fields ""))
                      body (if (<= n (count head))
                             (update (vec head) (dec n) #(str % " [[" k "]]"))
                             (into (vec head) (concat (repeat (- n (count head) 1) "") [(str "We meet it [[" k "]].")])))]
                  (str (str/join "\n" body) "\n" tail)))
          plan (pad plan-fields (parse-long pl) "")
          register (pad [["id" "RISK-001"] ["title" "Risk register"] ["revision" "1"]] (parse-long rl) "")]
      (s/check! (= (parse-long pl) (gap/line-of plan k)) "the plan names it elsewhere")
      (s/check! (= (parse-long rl) (gap/line-of register k)) "the register names it elsewhere")
      (assoc (project! ctx {:clauses (str "include base.txt\n" k " " description "\n")
                            :docs {"plan.md" plan "risk/register.md" register
                                   "base.md" (gap/doc-text [["id" "BASE-001"] ["revision" "1"] ["title" "Base"]]
                                                           "\nThe base [[STD:4.1]].\n")}
                            :extra {"../clauses/base.txt" "STD:4.1 Quality system\n"}})
             :clause [k description]))))

(defn- section
  "The lines of the Markdown `text` under the heading `heading` (to the next
  heading of its level or higher)."
  [text heading]
  (let [ls (str/split-lines text)
        i (.indexOf (vec ls) heading)]
    (when (<= 0 i)
      (vec (take-while #(not (re-find #"^#{1,2} " %)) (drop (inc i) ls))))))

(defstep #"OUT/clauses\.md has, under (\S+), a row for (\S+) with \"([^\"]+)\", (\S+) and (\S+)"
  (fn [ctx list-file k description a b]
    (kc/exits! ctx 0)
    (let [text (slurp (str (out-file ctx "clauses.md")))
          sec (section text (str "## " list-file))
          row (first (filter #(str/starts-with? % (str "| " k " |")) sec))]
      (s/check! sec (str "clauses.md has no section " list-file ":\n" text))
      (s/check! row (str "no row for " k " under " list-file ":\n" text))
      (doseq [w [description a b]]
        (s/check! (str/includes? row w) (str "the row does not hold " w ": " row))))))

(defstep #"its clauses are in the clause list's order, includes in place"
  (fn [ctx]
    (let [text (slurp (str (out-file ctx "clauses.md")))
          rows (keep #(second (re-find #"^\| (\S+) \|" %)) (str/split-lines text))
          keys (remove #{"Clause" "---"} rows)]
      (s/check! (= ["STD:4.1" (first (:clause ctx))] keys) (str "the clauses are in the order " (pr-str keys))))))

(defstep #"a releasable regulated project whose need (\S+) is traced to by @(\S+) and @(\S+)"
  (fn [ctx need a b]
    (s/check! (= [a b] (map :id scenarios)) "the fixture's scenarios are others")
    (s/check! (contains? needs need) (str "the fixture declares no " need))
    (assoc (project! ctx) :need need)))

(defstep #"OUT/traceability\.md has, under (\S+) and its text, @(\S+) and @(\S+), each with its title, component and status (\S+)"
  (fn [ctx need a b status]
    (kc/exits! ctx 0)
    (let [text (slurp (str (out-file ctx "traceability.md")))
          sec (section text (str "## " need))]
      (s/check! sec (str "traceability.md has no section " need ":\n" text))
      (s/check! (= (get-in needs [need :text]) (first (remove str/blank? sec))) (str "the need's text is not under it:\n" text))
      (doseq [id [a b]
              :let [sc (first (filter #(= id (:id %)) scenarios))
                    row (first (filter #(str/includes? % (str "@" id " ")) sec))]]
        (s/check! row (str "no row for @" id " under " need ":\n" text))
        (doseq [w [(:title sc) "Alpha" status]]
          (s/check! (str/includes? row w) (str "the row of @" id " does not hold " w ": " row)))))))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (project! ctx)))

(defn- files-under [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str/replace (str (fs/relativize d f)) "\\" "/") (vec (fs/read-all-bytes f))])))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [src (files-under (fs/file (:dir ctx) "dhf/allure-results"))]
      (s/check! (seq src) "the results directory is empty")
      (s/check! (= src (files-under (out-file ctx "results"))) "OUT/results is not a copy of the results directory"))))

(defstep #"a releasable regulated project at a commit C"
  (fn [ctx] (project! ctx)))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defstep #"OUT/manifest\.edn holds the commit C, whether the working tree was clean, and every other file of OUT with its path relative to OUT and its SHA-256, sorted by path"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [m (edn/read-string (slurp (str (out-file ctx "manifest.edn"))))
          clean? (str/blank? (:out (p/shell {:dir (str (:dir ctx)) :out :string :err :string} "git" "status" "--porcelain")))
          want (vec (for [[rel _] (files-under (out ctx "OUT")) :when (not= "manifest.edn" rel)]
                      {:path rel :sha256 (sha256 (out-file ctx rel))}))]
      (s/check! (= (:commit ctx) (:commit m)) (str "the manifest names the commit " (pr-str (:commit m))))
      (s/check! (= clean? (:clean m)) (str "the manifest says clean " (pr-str (:clean m)) ", the tree is " clean?))
      (s/check! (= want (:files m)) (str "the manifest's files are " (pr-str (:files m)))))))

(defstep #"a releasable regulated project"
  (fn [ctx] (project! ctx)))

(defstep #"\"rdm-spec ([^\"]+)\" is run, then \"rdm-spec ([^\"]+)\""
  (fn [ctx a b]
    (let [c1 (kc/run-line ctx a)
          c2 (kc/run-line c1 b)]
      (assoc c2 :runs [(:run c1) (:run c2)]))))

(defstep #"every Markdown file, and the manifest's entry for it, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (doseq [r (:runs ctx)] (s/check! (zero? (:exit r)) (str "an audit failed\n" (s/show r))))
    (let [[o1 o2] (map #(out ctx %) ["OUT" "OUT2"])
          md (fn [d] (into (sorted-map) (filter #(str/ends-with? (key %) ".md") (files-under d))))
          entries (fn [d] (into {} (for [e (:files (edn/read-string (slurp (str (fs/file d "manifest.edn")))))
                                         :when (str/ends-with? (:path e) ".md")]
                                     [(:path e) e])))]
      (s/check! (seq (md o1)) "OUT holds no Markdown file")
      (s/check! (= (md o1) (md o2)) "the Markdown files differ")
      (s/check! (= (entries o1) (entries o2)) "the manifest's entries for them differ")
      (s/check! (= (set (keys (md o1))) (set (keys (entries o1)))) "the manifest does not name every Markdown file"))))
