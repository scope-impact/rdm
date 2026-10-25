(ns steps.compliance-audit
  "Step definitions for dhf/spec/compliance/audit.feature: `rdm-spec audit
  OUT` in a releasable regulated project (steps.compliance-support). The
  When \"rdm-spec audit OUT\" is run, `it exits N` and the placeholders OUT
  and OUT2 are the kernel's."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- dir [ctx] (or (:dir ctx) (s/fail! "no project was made")))

(defn- no-out! [ctx]
  (let [out (cs/out-dir ctx "OUT")]
    (s/check! (not (fs/exists? out)) (str out " was created"))))

(defn- files-under
  "{relative path bytes-as-vector} of every file under `d`."
  [d]
  (into (sorted-map)
        (for [f (fs/glob d "**") :when (fs/regular-file? f)]
          [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defn- pdf-text [f & [first-page?]]
  (s/check! (fs/regular-file? f) (str "no file " f))
  (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                 (concat ["pdftotext"] (when first-page? ["-f" "1" "-l" "1"]) [(str f) "-"]))]
    (s/check! (zero? (:exit r)) (str "pdftotext failed on " f ": " (:err r)))
    (:out r)))

;; ---------------------------------------------------------------------------
;; Only a releasable record has an audit copy

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx] (cs/releasable! ctx {"spec-0001" "failed" "spec-0002" "passed"})))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          release (kc/run! ctx (dir ctx) ["release"])
          want (remove #(str/starts-with? % "event: ") (s/err-lines release))]
      (s/check! (= 1 (:exit release)) (str "the release check passed\n" (s/show release)))
      (s/check! (seq want) "the release check printed no finding")
      (s/check! (every? (set (s/err-lines r)) want)
                (str "the audit copy did not print the release check's findings unchanged\nrelease:\n"
                     (s/show release) "\naudit:\n" (s/show r)))
      (no-out! ctx))))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (dir ctx) "dhf/clauses/std.txt" "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n")
      (cs/commit! (assoc ctx :clause "STD:5.2")))))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % (:clause ctx)) (s/err-lines r))
                (str "no line names " (:clause ctx) "\n" (s/show r)))
      (no-out! ctx))))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx] (kc/refused-naming! ctx "configuration" "no :regulated") nil))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)
          out (str (fs/file (kc/tmp-dir) "out"))]
      (s/write! out "kept.txt" "an auditor's earlier copy\n")
      (assoc-in ctx [:placeholders "OUT"] out))))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (let [out (cs/out-dir ctx "OUT")]
      (kc/refused-naming! ctx out)
      (s/check! (= {"kept.txt" (vec (.getBytes "an auditor's earlier copy\n" "UTF-8"))} (files-under out))
                (str "the directory " out " changed: " (keys (files-under out)))))))

;; ---------------------------------------------------------------------------
;; Two copies of every document

(def ^:private split-tag ["We plan the work [[STD:5.1" "the plan]] and keep it."])
(def ^:private open-paragraph "An open [[ tag\nthat never closes.")
(def ^:private code-block "```\n[[STD:5.1]]\n```")

(defn- written
  "{:body text :line the one line} of what the Given wrote into plan.md."
  [what]
  (if-let [[_ line] (re-matches #"the line \"(.*)\"" what)]
    {:body line :line line}
    (case what
      "a tag that opens on one line and closes on the next" {:body (str/join "\n" split-tag)}
      "a \"[[\" with no \"]]\" before the paragraph's blank line" {:body (str open-paragraph "\n\nThe next paragraph.")}
      "a fenced code block holding [[STD:5.1]]" {:body code-block}
      (s/fail! (str "no fixture for " (pr-str what))))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md holds (.+)"
  (fn [ctx what]
    (let [ctx (cs/releasable! ctx)
          w (written what)]
      (s/write! (dir ctx) "dhf/documents/plan.md" (cs/document {} (:body w)))
      (cs/commit! (assoc ctx :written w)))))

(defn- holds-lines? [lines want]
  (let [n (count want)]
    (some #(= want (subvec lines % (+ % n))) (range 0 (inc (- (count lines) n))))))

(defstep #"OUT/(auditor|engineer)/plan\.md holds (.+)"
  (fn [ctx side kept]
    (kc/exits! ctx 0)
    (let [lines (cs/lines-of (cs/out-file ctx (str "OUT/" side "/plan.md")))
          want (if-let [[_ line] (re-matches #"the line \"(.*)\"" kept)]
                 [line]
                 (case kept
                   "both lines without the tag" ["We plan the work" "and keep it."]
                   "that line unchanged" [(get-in ctx [:written :line])]
                   "the paragraph unchanged" (str/split-lines open-paragraph)
                   "the code block unchanged" (str/split-lines code-block)
                   (s/fail! (str "no expectation for " (pr-str kept)))))]
      (s/check! (holds-lines? (vec lines) want)
                (str side "/plan.md does not hold " (pr-str want) ":\n" (str/join "\n" lines))))))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (vec (cs/lines-of (cs/out-file ctx "OUT/auditor/plan.md")))
          e (vec (cs/lines-of (cs/out-file ctx "OUT/engineer/plan.md")))
          i (.indexOf a (get-in ctx [:written :line]))]
      (s/check! (= (count a) (count e)) "the two copies have different numbers of lines")
      (s/check! (= [i] (vec (keep-indexed (fn [j [x y]] (when (not= x y) j)) (map vector a e))))
                (str "the copies differ elsewhere:\n" (str/join "\n" a) "\n---\n" (str/join "\n" e))))))

(defstep #"a releasable regulated project with dhf/documents/plan\.md and dhf/documents/risk/register\.md"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (dir ctx) "dhf/documents/risk/register.md"
                (cs/document {:id "RISK-001" :title "Risk register"} "Every risk is evaluated [[STD:5.1]]."))
      (cs/commit! ctx))))

(defstep #"OUT/auditor and OUT/engineer each hold plan\.md and risk/register\.md"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [side ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (s/check! (fs/regular-file? (cs/out-file ctx (str "OUT/" side "/" f))) (str "no OUT/" side "/" f)))))

(defstep #"a releasable regulated project whose dhf/documents/plan\.md has the id (\S+), the revision (\S+) and the title \"([^\"]*)\""
  (fn [ctx id revision title]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (dir ctx) "dhf/documents/plan.md"
                (cs/document {:id id :revision revision :title title} "We plan the work [[STD:5.1]]."))
      (cs/commit! (assoc ctx :identity [id (str "revision " revision) title])))))

(defstep #"OUT/auditor/plan\.pdf and OUT/engineer/plan\.pdf exist"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [f ["OUT/auditor/plan.pdf" "OUT/engineer/plan.pdf"]]
      (s/check! (fs/regular-file? (cs/out-file ctx f)) (str "no " f)))))

(defstep #"the text of each one's first page holds (\S+), the revision (\S+) and \"([^\"]*)\""
  (fn [ctx id revision title]
    (doseq [f ["OUT/auditor/plan.pdf" "OUT/engineer/plan.pdf"]
            :let [t (str/replace (pdf-text (cs/out-file ctx f) true) #"\s+" " ")]
            w [id (str "revision " revision) title]]
      (s/check! (str/includes? t w) (str "the first page of " f " does not hold " w ":\n" t)))))

(defstep #"no text of (OUT/\S+) holds \"([^\"]*)\""
  (fn [ctx f w]
    (let [t (pdf-text (cs/out-file ctx f))]
      (s/check! (not (str/includes? t w)) (str f " holds " w ":\n" t)))))

;; ---------------------------------------------------------------------------
;; The evidence

(defstep #"a releasable regulated project whose dhf/clauses/std\.txt holds \"([^\"]*)\" then \"([^\"]*)\", and whose dhf/clauses/base\.txt holds \"([^\"]*)\""
  (fn [ctx a b c]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (dir ctx) "dhf/clauses/std.txt" (str a "\n" b "\n"))
      (s/write! (dir ctx) "dhf/clauses/base.txt" (str c "\n"))
      ;; the included clause is met elsewhere, so the record stays releasable
      (s/write! (dir ctx) "dhf/documents/quality.md"
                (cs/document {:id "QUAL-001" :title "Quality manual"}
                             (str "This manual meets [[" (first (str/split c #"\s+")) "]].")))
      (cs/commit! ctx))))

(defn- tagged-at
  "A document whose line `n` names `k` in an audit tag (frontmatter lines
  1-5, a blank line 6)."
  [ident k n]
  (s/check! (> n 6) "a tag cannot stand in the frontmatter")
  (cs/document ident (str/join "\n" (concat (repeat (- n 7) "Filler.") [(str "This meets [[" k "]].")]))))

(defstep #"(\S+) is named on line (\d+) of (\S+) and line (\d+) of (\S+)"
  (fn [ctx k n1 f1 n2 f2]
    (s/write! (dir ctx) f1 (tagged-at {:id "PLAN-001"} k (parse-long n1)))
    (s/write! (dir ctx) f2 (tagged-at {:id "RISK-001" :title "Risk register"} k (parse-long n2)))
    (cs/commit! ctx)))

(defn- sections
  "[[heading [line ..]] ..] of a Markdown page's level-2 sections."
  [lines]
  (reduce (fn [out l]
            (if-let [[_ h] (re-matches #"## (.*)" l)]
              (conj out [h []])
              (if (seq out) (update-in out [(dec (count out)) 1] conj l) out)))
          [] lines))

(defstep #"OUT/clauses\.md has one section per declared clause list, (\S+), and no section for (\S+)"
  (fn [ctx declared included]
    (kc/exits! ctx 0)
    (let [hs (map first (sections (cs/lines-of (cs/out-file ctx "OUT/clauses.md"))))]
      (s/check! (= [declared] (vec hs)) (str "the sections are " (pr-str hs)))
      (s/check! (not-any? #(str/includes? % included) hs) (str "a section names " included)))))

(defstep #"under it (\S+) comes first, where its include stands, then a row for (\S+) with \"([^\"]*)\", (\S+) and (\S+)"
  (fn [ctx first-key k description at1 at2]
    (let [[[_ body]] (sections (cs/lines-of (cs/out-file ctx "OUT/clauses.md")))
          rows (filter #(str/starts-with? % "|") body)
          keys* (keep #(second (re-find #"^\|\s*([^|\s]+)\s*\|" %)) rows)
          clause-keys (remove #{"Clause" "---"} keys*)
          row (first (filter #(re-find (re-pattern (str "^\\|\\s*" (java.util.regex.Pattern/quote k) "\\s*\\|")) %) rows))]
      (s/check! (= [first-key k] (vec clause-keys)) (str "the clauses come as " (pr-str clause-keys)))
      (s/check! (and row (every? #(str/includes? row %) [description at1 at2]))
                (str "the row of " k " is " (pr-str row))))))

(defstep #"a releasable regulated project whose need (\S+) is traced to by @(\S+) and @(\S+)"
  (fn [ctx need a b]
    (s/check! (= ["UN-001" "spec-0001" "spec-0002"] [need a b]) "the fixture traces UN-001 to spec-0001 and spec-0002")
    (assoc (cs/releasable! ctx) :need need :traced [a b])))

(defstep #"OUT/traceability\.md has, under (\S+) and its text, @(\S+) and @(\S+), each with its title, component and status verified"
  (fn [ctx need a b]
    (kc/exits! ctx 0)
    (let [secs (into {} (sections (cs/lines-of (cs/out-file ctx "OUT/traceability.md"))))
          body (get secs need)
          text (get-in cs/demo-needs [need :text])]
      (s/check! body (str "no section for " need ": " (pr-str (keys secs))))
      (s/check! (some #(str/includes? % text) body) (str "the section does not hold the need's text " text))
      (doseq [id [a b]
              :let [row (first (filter #(str/includes? % (str "@" id " ")) body))]]
        (s/check! (and row (every? #(str/includes? row %) [(get cs/scenario-titles id) "Alpha" "verified"]))
                  (str "the row of @" id " is " (pr-str row)))))))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx]
    (let [ctx (cs/releasable! ctx)]
      (s/write! (dir ctx) (str cs/results-dir "/environment.properties") "os=fixture\n")
      (cs/commit! ctx))))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [want (files-under (fs/file (dir ctx) cs/results-dir))
          got (files-under (cs/out-file ctx "OUT/results"))]
      (s/check! (seq want) "the results directory is empty")
      (s/check! (= want got) (str "OUT/results holds " (pr-str (keys got)) ", the results " (pr-str (keys want)))))))

(defstep #"a releasable regulated project"
  (fn [ctx] (cs/releasable! ctx)))

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (fs/read-all-bytes f))))))

(defn- sha256-of [out path] (sha256 (fs/file out path)))

(defstep #"OUT/manifest\.edn holds exactly \{:commit :clean :files\}: the commit HEAD resolves to, :clean true when no tracked file differs from it \(untracked files never count\), and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [out (cs/out-dir ctx "OUT")
          m (edn/read-string (slurp (str (fs/file out "manifest.edn"))))
          head (str/trim (cs/git! (dir ctx) "rev-parse" "HEAD"))
          files (sort (for [f (fs/glob out "**") :when (fs/regular-file? f)
                            :let [rel (str (fs/relativize out f))]
                            :when (not= "manifest.edn" rel)]
                        rel))]
      (s/check! (fs/exists? (fs/file (dir ctx) "untracked.txt")) "the fixture lost its untracked file")
      (s/check! (= #{:commit :clean :files} (set (keys m))) (str "the manifest's keys are " (pr-str (keys m))))
      (s/check! (= head (:commit m)) (str "the commit is " (:commit m) ", HEAD " head))
      (s/check! (true? (:clean m)) "the manifest says the record is not clean")
      (s/check! (= (vec files) (mapv :path (:files m))) (str "the manifest lists " (pr-str (map :path (:files m)))))
      (doseq [{:keys [path sha256] :as e} (:files m)]
        (s/check! (= #{:path :sha256} (set (keys e))) (str "an entry holds " (pr-str (keys e))))
        (s/check! (= sha256 (sha256-of out path)) (str "the hash of " path " is wrong"))))))


(defstep #"\"rdm-spec ([^\"]*)\" is run, then \"rdm-spec ([^\"]*)\""
  (fn [ctx a b]
    (let [ctx (kc/run-line ctx a)
          first-run (:run ctx)
          ctx (kc/run-line ctx b)]
      (assoc ctx :runs [first-run (:run ctx)]))))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (doseq [r (:runs ctx)]
      (s/check! (zero? (:exit r)) (str "a run did not pass\n" (s/show r))))
    (let [a (files-under (cs/out-dir ctx "OUT"))
          b (files-under (cs/out-dir ctx "OUT2"))]
      (s/check! (some #(str/ends-with? % ".pdf") (keys a)) "OUT holds no PDF")
      (s/check! (contains? a "manifest.edn") "OUT holds no manifest")
      (s/check! (= (keys a) (keys b)) (str "the files differ: " (pr-str (keys a)) " " (pr-str (keys b))))
      (doseq [k (keys a)]
        (s/check! (= (get a k) (get b k)) (str k " differs between OUT and OUT2"))))))
