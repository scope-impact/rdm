(ns rdm-spec.compliance.audit
  "Audit copy (dhf/spec/compliance/audit.feature): `rdm-spec audit OUT`
  writes what a team hands an auditor, only for a record the release check
  passes (the task's gate) and only of one commit.

  Two stages of the task (rdm-spec.kernel.command/tasks):
    prepare  runs first: refuses (exit 2) a project without :regulated, an
             OUT that exists, a missing PDF tool or a repository with no
             HEAD; and finds (exit 1, rule not-committed) every file the copy
             is made from (the spec, the clause lists, the documents) that
             differs from HEAD, untracked or ignored included. With such a
             finding nothing is written.
    audit    runs after the release gate and writes OUT:
               auditor/<doc>.md and .pdf   every audit tag kept
               engineer/<doc>.md and .pdf  every audit tag removed with the
                                           space before it, line breaks kept
               clauses.md                  one section per declared clause list
               traceability.md             each need, its scenarios, their status
               results/                    a copy of the results directory
               manifest.edn                {:commit :files [{:path :sha256}]}

  It reads the scenarios (SpecParsed), their statuses (VerificationDerived)
  and the clauses and documents (ClausesIndexed) from the bus. The same
  commit and results give the same bytes, the PDFs included (the PDF tools
  are in dhf/harness/stack.md)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed in the run's state."
  [req]
  (for [e [:SpecParsed :VerificationDerived :ClausesIndexed]]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; git

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn head-commit
  "The commit HEAD resolves to in the repository at `root`, or nil."
  [root]
  (let [r (git root "rev-parse" "--verify" "--quiet" "HEAD^{commit}")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn parse-status
  "Pure: `git status --porcelain=v1 -z` output as [{:file :code}]."
  [out]
  (loop [entries (remove str/blank? (str/split (str out) #"\u0000")) acc []]
    (if-let [e (first entries)]
      (let [code (subs e 0 (min 2 (count e)))
            file (if (> (count e) 3) (subs e 3) "")
            renamed? (some #{\R \C} code)]
        (recur (if renamed? (drop 2 entries) (rest entries)) (conj acc {:file file :code code})))
      acc)))

(defn- describe [code]
  (cond (= "??" code) "untracked"
        (= "!!" code) "untracked and ignored"
        (str/includes? code "D") "deleted"
        (some #{\R \C} code) "renamed"
        (str/includes? code "A") "added but not committed"
        :else "changed"))

(defn uncommitted
  "[{:file :code}]: every file under `paths` (relative to `root`) that
  differs from HEAD, untracked and ignored files included."
  [root paths]
  (let [r (apply git root "status" "--porcelain=v1" "-z" "--untracked-files=all" "--ignored=matching" "--" paths)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git status failed: " (str/trim (:err r)))))
    (vec (sort-by :file (parse-status (:out r))))))

;; ---------------------------------------------------------------------------
;; The stage before the gate

(defn- out-arg [req] (first (:args req)))

(defn prepare
  "Refuses what cannot be audited and finds what is not committed; leaves
  {:out :commit :refused?} in the run's state."
  [req]
  (when-not (contains? (:config req) :regulated)
    (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated:"
                              " only a regulated project has an audit copy")))
  (let [profile (gap/profile req)
        out (out-arg req)
        out-f (command/path req out)]
    (when (or (fs/exists? out-f) (fs/sym-link? out-f))
      (command/cannot-run! (str out " exists: the audit copy is written only into a directory that does not exist")))
    (doseq [tool ["pandoc" "typst"]]
      (when-not (fs/which tool)
        (command/cannot-run! (str "no " tool " on PATH: the audit copy's PDFs need it"))))
    (let [commit (or (head-commit (:root req))
                     (command/cannot-run! "HEAD names no commit: an audit copy is of one commit"))
          lists (gap/read-lists (:clause-lists profile) (:read (gap/reader (:root req) nil)))
          paths (vec (distinct (concat [(command/config-value req :spec-dir) (:documents profile)]
                                       (:files lists))))
          dirty (uncommitted (:root req) paths)
          findings (vec (for [{:keys [file code]} dirty]
                          (command/finding file "not-committed"
                                           (str file " is not committed (" (describe code) "): the audit copy is of"
                                                " one commit, and this file differs from HEAD " (subs commit 0 12)))))]
      (swap! (:state req) assoc ::prepared {:out out :commit commit :refused? (boolean (seq findings))})
      {:findings findings})))

;; ---------------------------------------------------------------------------
;; Pages

(defn- cell [s] (str/replace (str s) "|" "\\|"))

(defn clauses-page
  "Pure: OUT/clauses.md: one section per declared clause list, its clauses
  in order with included clauses in place, each with where it is named."
  [declared clauses]
  (str "# Clauses\n\n"
       "Every clause of every declared clause list, and the audit tags that name it.\n"
       (str/join
        (for [d declared
              :let [cs (filter #(= d (:declared %)) clauses)]]
          (str "\n## " d "\n\n"
               (if (empty? cs)
                 "Every clause of this list is listed under an earlier one.\n"
                 (str "| Clause | Description | Written at | Named at |\n"
                      "|---|---|---|---|\n"
                      (str/join
                       (for [{:keys [key description clause-list line named-at]} cs]
                         (str "| " (cell key) " | " (cell description) " | " (cell (str clause-list ":" line)) " | "
                              (if (seq named-at)
                                (str/join ", " (map #(cell (str (:file %) ":" (:line %))) named-at))
                                "not named")
                              " |\n"))))))))))

(defn traceability-page
  "Pure: OUT/traceability.md: each need with its text, the scenarios that
  trace to it, and each one's title, component and status."
  [needs scenarios statuses]
  (str "# Traceability\n\n"
       "Each user need, the scenarios that trace to it, and the status each was verified with.\n"
       (str/join
        (for [{:keys [id text]} needs
              :let [scs (filter #(and (:id %) (some #{id} (:needs %))) scenarios)]]
          (str "\n## " id "\n\n" text "\n\n"
               (if (empty? scs)
                 "No scenario traces to it.\n"
                 (str "| Scenario | Title | Component | Status |\n"
                      "|---|---|---|---|\n"
                      (str/join
                       (for [sc scs]
                         (str "| @" (cell (:id sc)) " | " (cell (or (:title sc) (:name sc))) " | "
                              (cell (or (:component-name sc) (some-> (:component sc) name))) " | "
                              (name (get statuses (:id sc) :unverified)) " |\n"))))))))))

(defn manifest-text
  "Pure: OUT/manifest.edn: the commit and every other file with its SHA-256,
  sorted by path."
  [commit files]
  (str "{:commit " (pr-str commit) "\n :files\n ["
       (str/join "\n  " (for [{:keys [path sha256]} (sort-by :path files)]
                          (str "{:path " (pr-str path) " :sha256 " (pr-str sha256) "}")))
       "]}\n"))

;; ---------------------------------------------------------------------------
;; PDFs

(defn- md-escape [s] (str/replace (str s) #"[!-/:-@\[-`{-~]" #(str "\\" %)))

(defn pdf-markdown
  "Pure: the Markdown a copy's PDF is made from: its body (the frontmatter
  removed), after a first paragraph naming the document's id and revision."
  [text {:strs [id revision]}]
  (let [lines (str/split (str text) #"\n" -1)
        end (gap/frontmatter-end lines)
        body (str/join "\n" (if end (drop (inc end) lines) lines))]
    (str "Document " (md-escape id) ", revision " (md-escape revision) "\n\n" body)))

(defn- sh! [args opts what]
  (let [r (apply p/shell (merge {:out :string :err :string :continue true :in ""} opts) args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str what " failed: " (str/trim (str (:err r))))))
    (:out r)))

(defn write-pdf!
  "Writes the PDF of a copy (`text`, as Markdown) to `pdf`."
  [text pdf tmp]
  (let [front (gap/frontmatter text)
        typ (fs/file tmp "doc.typ")
        typst (sh! ["pandoc" "-f" "markdown" "-s" "-t" "typst" "-V" "mainfont=DejaVu Sans"
                     "-M" (str "title=" (get front "title" ""))]
                    {:in (pdf-markdown text front)} "pandoc")]
    (spit (str typ) (str "#set document(date: none)\n" typst))
    (sh! ["typst" "compile" (str typ) (str pdf)] {} "typst")
    (fs/delete-if-exists typ)))

;; ---------------------------------------------------------------------------
;; Writing OUT

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defn- files-under [dir]
  (vec (sort (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
               (str/replace (str (fs/relativize dir f)) "\\" "/")))))

(defn- write! [f text]
  (fs/create-dirs (fs/parent f))
  (spit (str f) text))

(defn- write-out!
  [req out-f {:keys [commit]}]
  (let [profile (gap/profile req)
        clauses (or (event req :ClausesIndexed)
                    (command/cannot-run! "the clauses were not read: no ClausesIndexed event reached the audit copy"))
        parsed (or (event req :SpecParsed)
                   (command/cannot-run! "the spec was not read: no SpecParsed event reached the audit copy"))
        statuses (:statuses (or (event req :VerificationDerived)
                                (command/cannot-run! "no status was derived: no VerificationDerived event reached the audit copy")))
        docs-dir (:documents profile)
        tmp (str (fs/create-temp-dir {:prefix "rdm-spec-audit-"}))]
    (try
      (doseq [{:keys [file]} (:documents clauses)
              :let [text (slurp (str (command/path req file)))
                    rel (str/replace (str (fs/relativize (fs/path docs-dir) (fs/path file))) "\\" "/")
                    pdf-rel (str/replace rel #"(?i)\.md$" ".pdf")]
              [copy t] [["auditor" text] ["engineer" (gap/strip-tags text)]]]
        (write! (fs/file out-f copy rel) t)
        (write-pdf! t (fs/file out-f copy pdf-rel) tmp))
      (finally (fs/delete-tree tmp)))
    (write! (fs/file out-f "clauses.md") (clauses-page (:clause-lists profile) (:clauses clauses)))
    (write! (fs/file out-f "traceability.md") (traceability-page (:needs parsed) (:scenarios parsed) statuses))
    (let [rd (command/path req (:results req))]
      (doseq [f (files-under rd)]
        (fs/create-dirs (fs/parent (fs/file out-f "results" f)))
        (fs/copy (fs/file rd f) (fs/file out-f "results" f) {:replace-existing true})))
    (let [files (for [f (files-under out-f)] {:path f :sha256 (sha256 (fs/file out-f f))})]
      (write! (fs/file out-f "manifest.edn") (manifest-text commit files))
      {:documents (count (:documents clauses)) :files (inc (count files))})))

(defn audit
  "Writes the audit copy to OUT, unless prepare found what is not committed."
  [req]
  (let [{:keys [out refused?] :as prepared}
        (or (some-> (:state req) deref ::prepared)
            (command/cannot-run! "the audit copy was not prepared"))]
    (if refused?
      {:result {} :findings []}
      (let [out-f (command/path req out)]
        (when (fs/exists? out-f)
          (command/cannot-run! (str out " exists: the audit copy is written only into a directory that does not exist")))
        (try
          (fs/create-dirs out-f)
          (let [{:keys [documents files]} (write-out! req out-f prepared)]
            {:result {:audit {:out out :commit (:commit prepared) :documents documents :files files}}
             :findings []})
          (catch Throwable e
            (fs/delete-tree out-f)
            (throw e)))))))
