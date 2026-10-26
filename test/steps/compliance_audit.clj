(ns steps.compliance-audit
  "Step definitions of dhf/spec/compliance/audit.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def plan "dhf/documents/plan.md")

(defn out [ctx] (k/guard! (get-in ctx [:ph "OUT"])))
(defn out-file [ctx p] (fs/file (out ctx) p))

(defn plan-doc [body] (k/doc-text {:id "PLAN-001" :revision "1" :title "Development plan"} body))

(defn releasable!
  "A regulated project whose every clause is named and every scenario has a
  passing result, all committed."
  ([ctx] (releasable! ctx {}))
  ([ctx opts]
   (let [ctx (k/regulated! ctx opts)]
     (k/pass-all! ctx ["spec-0001"])
     (k/commit! (:dir ctx) "releasable")
     ctx)))

(defn recommit! [ctx] (k/commit! (:dir ctx) "change") ctx)

(defstep #"a regulated project whose every clause is named, and a stamped scenario whose result failed"
  (fn [ctx]
    (let [ctx (k/regulated! ctx)]
      (k/write-result! (fs/path (:dir ctx) "dhf/allure-results") {:id "spec-0001" :status "failed"})
      (k/commit! (:dir ctx) "failing")
      ctx)))

(defstep #"it exits 1, prints the release check's findings unchanged, and creates no OUT"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (k/rdm ctx ["release"])]
      (k/check-exit r 1)
      (doseq [l (remove #(str/includes? % "warning:") (k/err-lines r))]
        (assert (some #{l} (k/err-lines ctx)) (str "not printed: " l))))
    (assert (not (fs/exists? (out ctx))))
    ctx))

(defstep #"a regulated project whose every scenario is verified, and a clause no audit tag names"
  (fn [ctx] (releasable! ctx {:clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"})))

(defstep #"it exits 1, names the clause, and creates no OUT"
  (fn [ctx] (k/check-exit ctx 1) (k/err-has ctx "STD:5.2") (assert (not (fs/exists? (out ctx)))) ctx))

(defstep #"it exits 2 and says the configuration holds no :regulated"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx ":regulated") (assert (not (fs/exists? (out ctx)))) ctx))

(defstep #"a releasable regulated project and an existing directory OUT holding one file"
  (fn [ctx]
    (let [ctx (-> (releasable! ctx) (k/placeholder! "OUT"))]
      (k/write-files! (out ctx) {"mine.txt" "mine\n"})
      ctx)))

(defstep #"it exits 2, names OUT, and the file in it is unchanged"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-has ctx (out ctx))
    (assert (= ["mine.txt"] (map #(str (fs/file-name %)) (fs/list-dir (out ctx)))))
    (assert (= "mine\n" (slurp (out-file ctx "mine.txt"))))
    ctx))

(def plan-forms
  {"a tag that opens on one line and closes on the next" ["We plan [[STD:5.1\nthe plan]] the work.\n" "We plan\n the work.\n"]
   "a \"[[\" with no \"]]\" before the paragraph's blank line" ["We plan [[STD:5.1 and more\n\nA new paragraph [[STD:5.1]].\n" "We plan [[STD:5.1 and more\n\nA new paragraph.\n"]
   "a fenced code block holding [[STD:5.1]]" ["We plan [[STD:5.1]].\n\n```\ncode [[STD:5.1]]\n```\n" "We plan.\n\n```\ncode [[STD:5.1]]\n```\n"]
   "an indented code block holding [[STD:5.1]]" ["We plan [[STD:5.1]].\n\n    code [[STD:5.1]]\n" "We plan.\n\n    code [[STD:5.1]]\n"]})

(defstep #"a releasable regulated project whose dhf/documents/plan.md holds (.+)"
  (fn [ctx what]
    (let [m (re-matches #"the line \"(.*)\"" what)
          body (cond m (str (second m) "\n" (when-not (re-find #"(?<!`)\[\[STD:5.1\]\](?!`)" (second m)) "Also [[STD:5.1]].\n"))
                     (plan-forms what) (first (plan-forms what))
                     (re-matches #"the title .*" what) "We plan [[STD:5.1]].\n"
                     :else (fail! (str "no such plan: " what)))
          doc (if (re-matches #"the title .*" what)
                (k/doc-text {:id "PLAN-001" :revision "1" :title "Development plan [[STD:5.1]]"} body)
                (plan-doc body))]
      (-> (releasable! ctx {:docs {plan doc}}) (assoc ::what what)))))

(defn lines-of [f] (str/split-lines (slurp f)))

(defstep #"OUT/auditor/plan.md holds the line \"We plan the work \[\[STD:5.1\]\].\""
  (fn [ctx]
    (assert (some #{"We plan the work [[STD:5.1]]."} (lines-of (out-file ctx "auditor/plan.md"))))
    ctx))

(defstep #"OUT/engineer/plan.md holds (.+)"
  (fn [ctx what]
    (k/check-exit ctx 0)
    (let [eng (slurp (out-file ctx "engineer/plan.md"))
          m (re-matches #"the line \"(.*)\"" what)
          src (::what ctx)]
      (cond
        m (assert (some #{(second m)} (str/split-lines eng)) eng)
        (= what "that line unchanged") (assert (str/includes? eng (second (re-matches #"the line \"(.*)\"" src))) eng)
        (= what "the title \"Development plan\"") (assert (some #{"title: Development plan"} (str/split-lines eng)) eng)
        :else (assert (str/includes? eng (second (plan-forms src))) eng))
      ctx)))

(defstep #"apart from that line the two copies are identical"
  (fn [ctx]
    (let [a (lines-of (out-file ctx "auditor/plan.md")) e (lines-of (out-file ctx "engineer/plan.md"))]
      (assert (= (count a) (count e)))
      (assert (= 1 (count (filter false? (map = a e))))))
    ctx))

(defstep #"a releasable regulated project with dhf/documents/plan.md and dhf/documents/risk/register.md"
  (fn [ctx]
    (releasable! ctx {:docs {plan (plan-doc "We plan [[STD:5.1]].\n")
                             "dhf/documents/risk/register.md" (k/doc-text {:id "RISK-001" :revision "1" :title "Risks"} "Risks.\n")}})))

(defstep #"OUT/auditor and OUT/engineer each hold plan.md and risk/register.md"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [c ["auditor" "engineer"] f ["plan.md" "risk/register.md"]]
      (assert (fs/exists? (out-file ctx (str c "/" f))) (str c "/" f)))
    ctx))

(defstep #"a releasable regulated project whose dhf/documents/plan.md has the id PLAN-001, the revision 3 and the title \"Development plan\""
  (fn [ctx]
    (releasable! ctx {:docs {plan (k/doc-text {:id "PLAN-001" :revision "3" :title "Development plan"} "# Scope\n\nWe plan [[STD:5.1]].\n")}})))

(defstep #"OUT/auditor/plan.pdf and OUT/engineer/plan.pdf exist"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [c ["auditor" "engineer"]] (assert (fs/exists? (out-file ctx (str c "/plan.pdf")))))
    ctx))

(defn pdf-text [ctx f & [first-page]]
  (:out (apply k/sh (out ctx) {} (concat ["pdftotext"] (when first-page ["-f" "1" "-l" "1"]) [(str (out-file ctx f)) "-"]))))

(defstep #"the text of each one's first page holds PLAN-001, the revision 3 and \"Development plan\""
  (fn [ctx]
    (doseq [c ["auditor" "engineer"]]
      (let [t (pdf-text ctx (str c "/plan.pdf") true)]
        (assert (and (str/includes? t "PLAN-001") (str/includes? t "3") (str/includes? t "Development plan")) t)))
    ctx))

(defstep #"no text of OUT/engineer/plan.pdf holds \"\[\[\""
  (fn [ctx]
    (assert (not (str/includes? (pdf-text ctx "engineer/plan.pdf") "[[")))
    (assert (str/includes? (pdf-text ctx "auditor/plan.pdf") "[["))
    ctx))

(defstep #"a releasable regulated project whose dhf/clauses/std.txt holds \"include base.txt\" then \"STD:5.1 Development plan\", and whose dhf/clauses/base.txt holds \"STD:4.1 Quality system\""
  (fn [ctx]
    (k/regulated! ctx {:clauses "include base.txt\nSTD:5.1 Development plan\n"
                       :opts {:extra {"dhf/clauses/base.txt" "STD:4.1 Quality system\n"}}})))

(defstep #"STD:5.1 is named on line 8 of dhf/documents/plan.md and line 9 of dhf/documents/risk/register.md"
  (fn [ctx]
    ;; frontmatter is lines 1 to 5
    (k/write-files! (:dir ctx) {plan (plan-doc "# Plan\n\nWe plan [[STD:5.1]].\nThe system [[STD:4.1]].\n")
                                "dhf/documents/risk/register.md" (k/doc-text {:id "RISK-001" :revision "1" :title "Risks"} "# Risks\n\nOne.\nWe manage [[STD:5.1]].\n")})
    (k/pass-all! ctx ["spec-0001"])
    (k/commit! (:dir ctx) "named")
    ctx))

(defn section [text heading]
  (let [i (or (str/index-of text heading) (fail! (str "no section " heading ": " text)))
        rest (subs text (+ i (count heading)))
        j (str/index-of rest "\n## ")]
    (if j (subs rest 0 j) rest)))

(defstep #"OUT/clauses.md has one section per declared clause list, dhf/clauses/std.txt, and no section for dhf/clauses/base.txt"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (slurp (out-file ctx "clauses.md"))
          hs (filter #(str/starts-with? % "## ") (str/split-lines t))]
      (assert (= ["## dhf/clauses/std.txt"] hs) (pr-str hs))
      ctx)))

(defstep #"under it STD:4.1 comes first, where its include stands, then a row for STD:5.1 with \"Development plan\", dhf/documents/plan.md:8 and dhf/documents/risk/register.md:9"
  (fn [ctx]
    (let [s (section (slurp (out-file ctx "clauses.md")) "## dhf/clauses/std.txt")
          row (first (filter #(str/includes? % "STD:5.1") (str/split-lines s)))]
      (assert (< (str/index-of s "STD:4.1") (str/index-of s "STD:5.1")) s)
      (assert (and row (str/includes? row "Development plan") (str/includes? row "dhf/documents/plan.md:8")
                   (str/includes? row "dhf/documents/risk/register.md:9")) s)
      ctx)))

(defstep #"a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002"
  (fn [ctx]
    (let [ctx (releasable! ctx {:opts {:features {"dhf/spec/kernel/core.feature"
                                                 (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "First") (k/sc 2 "Second")]})}}})]
      (k/pass-all! ctx ["spec-0002"])
      (k/commit! (:dir ctx) "two")
      ctx)))

(defstep #"OUT/traceability.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [s (section (slurp (out-file ctx "traceability.md")) "## UN-001")]
      (assert (str/includes? s "A user need") s)
      (doseq [[id title] [["spec-0001" "First"] ["spec-0002" "Second"]]]
        (let [row (first (filter #(str/includes? % id) (str/split-lines s)))]
          (assert (and row (str/includes? row title) (str/includes? row "Core") (str/includes? row "verified")) s)))
      ctx)))

(defstep #"a releasable regulated project and its results directory"
  (fn [ctx] (releasable! ctx)))

(defstep #"OUT/results holds a copy of every file of the results directory"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [src (fs/path (:dir ctx) "dhf/allure-results")]
      (doseq [f (fs/list-dir src)]
        (assert (= (slurp (fs/file f)) (slurp (out-file ctx (str "results/" (fs/file-name f))))))))
    ctx))

(defstep #"a releasable regulated project"
  (fn [ctx] (releasable! ctx)))

(defn sha256 [f]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes f))]
    (apply str (map #(format "%02x" %) d))))

(defstep #"OUT/manifest.edn holds exactly \{:commit :files\}: the commit HEAD resolves to, and every other file of OUT as \{:path :sha256\}, its path relative to OUT, sorted by path"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [m (edn/read-string (slurp (out-file ctx "manifest.edn")))
          files (->> (fs/glob (out ctx) "**") (filter fs/regular-file?) (map #(str (fs/relativize (out ctx) %)))
                     (remove #{"manifest.edn"}) sort)]
      (assert (= #{:commit :files} (set (keys m))))
      (assert (= (k/git (:dir ctx) "rev-parse" "HEAD") (:commit m)))
      (assert (= (vec (for [f files] {:path f :sha256 (sha256 (out-file ctx f))})) (:files m)) (pr-str m))
      ctx)))

(defstep #"a releasable regulated project, all committed"
  (fn [ctx] (releasable! ctx)))

(def changes
  {"an untracked document dhf/documents/draft.md" [#(k/write-files! (:dir %) {"dhf/documents/draft.md" (plan-doc "Draft [[STD:5.1]].\n")}) "dhf/documents/draft.md"]
   "a document dhf/documents/draft.md that git ignores" [#(k/write-files! (:dir %) {".gitignore" "dhf/documents/draft.md\n" "dhf/documents/draft.md" (plan-doc "Draft.\n")}) "dhf/documents/draft.md"]
   "an uncommitted change to a document" [#(k/write-files! (:dir %) {plan (plan-doc "We plan more [[STD:5.1]].\n")}) plan]
   "an uncommitted change to a scenario's title" [#(k/write-files! (:dir %) {"dhf/spec/kernel/core.feature" (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Renamed")]})}) "dhf/spec/kernel/core.feature"]
   "an uncommitted change to the clause list" [#(k/write-files! (:dir %) {"dhf/clauses/std.txt" "STD:5.1 Development plan, renamed\n"}) "dhf/clauses/std.txt"]
   "an uncommitted change to the configuration" [#(k/update-edn! % "dhf/harness/config.edn" assoc :stack-words ["Cobalt"]) "dhf/harness/config.edn"]})

(defstep #"(an untracked document dhf/documents/draft.md|a document dhf/documents/draft.md that git ignores|an uncommitted change to (?:a document|a scenario's title|the clause list|the configuration))"
  (fn [ctx what]
    (let [[f file] (changes what)]
      (when (= what "a document dhf/documents/draft.md that git ignores")
        (k/write-files! (:dir ctx) {".gitignore" "dhf/documents/draft.md\n"})
        (k/commit! (:dir ctx) "ignore"))
      (f ctx)
      (assoc ctx ::file file))))

(defstep #"it exits 1, names (.+) as not committed, and creates no OUT"
  (fn [ctx _]
    (k/check-exit ctx 1)
    (k/err-line-with ctx (::file ctx) "not committed")
    (assert (not (fs/exists? (out ctx))))
    ctx))

(defstep #"\"rdm-spec audit OUT\" is run, then \"rdm-spec audit OUT2\""
  (fn [ctx]
    (let [a (k/rdm-line ctx "rdm-spec audit OUT")
          b (k/rdm-line a "rdm-spec audit OUT2")]
      (k/check-exit a 0)
      b)))

(defstep #"every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [tree (fn [d] (->> (fs/glob d "**") (filter fs/regular-file?) (map #(str (fs/relativize d %))) sort))
          a (get-in ctx [:ph "OUT"]) b (get-in ctx [:ph "OUT2"])]
      (assert (= (tree a) (tree b)))
      (assert (some #(str/ends-with? % ".pdf") (tree a)))
      (doseq [f (tree a)]
        (assert (java.util.Arrays/equals (fs/read-all-bytes (fs/path a f)) (fs/read-all-bytes (fs/path b f))) f)))
    ctx))
