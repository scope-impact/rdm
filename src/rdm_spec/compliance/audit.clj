(ns rdm-spec.compliance.audit
  "The Audit copy, the compliance context's second component
  (dhf/spec/compliance/audit.feature; dhf/spec/glossary.md, \"Audit copy\"):
  what a team hands an auditor, of one commit, for a record the release
  check passes.

  The task audit OUT runs, in order (rdm-spec.kernel.command's task table):

    prepare  cannot run (exit 2) without :regulated, when OUT exists, or
             outside a git repository with a commit; names (exit 1,
             audit-uncommitted) every file the audit copy is made from that
             differs from HEAD's tree: the configuration, the spec (with the
             model, needs, events and ledger), every clause list the declared
             ones reach, and the documents, untracked and ignored files
             included. When it names one, the audit stage writes nothing.
    parse, gap (quietly), release (the gate: a record it refuses has no
             audit copy, its findings printed unchanged)
    audit    writes OUT, from HEAD's tree (the documents) and from what the
             run published (SpecParsed, VerificationDerived, ClausesIndexed),
             which are HEAD's too once prepare found nothing:

    OUT/auditor/<doc>.md, .pdf    every controlled document, audit tags kept
    OUT/engineer/<doc>.md, .pdf   every audit tag removed (gap/strip-tags)
    OUT/clauses.md                one section per declared clause list,
                                  included clauses in place, where each is met
    OUT/traceability.md           each need, its scenarios and their status
    OUT/results/                  a copy of every file of the results directory
    OUT/manifest.edn              {:commit HEAD :files [{:path :sha256}]},
                                  every other file, sorted by path

  OUT is built beside itself and moved into place whole, so a run that fails
  leaves no OUT. A PDF is Pandoc's Typst output (the document's id and
  revision first, its title as the title, `#set document(date: none)` first)
  compiled by Typst: the same commit and results give the same bytes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; What the run published

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed in the run's state."
  [req]
  (for [[e k] [[:SpecParsed ::spec] [:VerificationDerived ::derived] [:ClausesIndexed ::clauses]]]
    [e (fn [payload] (some-> (:state req) (swap! assoc k payload)) nil)]))

(defn- state [req k] (some-> (:state req) deref (get k)))

;; ---------------------------------------------------------------------------
;; git

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn status-paths
  "Pure: every path of `git status --porcelain=v1 -z` output, a rename's
  both paths included."
  [out]
  (loop [[t & more] (remove str/blank? (str/split (str out) #"\u0000")) acc []]
    (if (nil? t)
      acc
      (let [xy (subs t 0 (min 2 (count t)))
            path (if (> (count t) 3) (subs t 3) "")]
        (if (and (#{\R \C} (first xy)) (seq more))
          (recur (rest more) (conj acc path (first more)))
          (recur more (conj acc path)))))))

(defn under?
  "Whether the path `rel` is one of `inputs` or lies under one."
  [inputs rel]
  (boolean (some #(or (= rel %) (str/starts-with? rel (str % "/"))) inputs)))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn inputs
  "Every path, relative to the project root, the audit copy is made from."
  [req]
  (let [{:keys [clause-lists documents]} (gap/regulated req)
        {read-text :read} (gap/reader (:root req) nil)]
    (vec (distinct (map strip-slash
                        (concat [command/config-file]
                                (map #(command/config-value req %) [:spec-dir :model :needs :events :ledger])
                                (gap/list-files read-text (mapv strip-slash clause-lists))
                                [documents]))))))

(defn- uncommitted
  "Every input path that differs from HEAD's tree, sorted."
  [req ins]
  (let [root (:root req)
        prefix (str/trim (:out (git root "rev-parse" "--show-prefix")))
        r (apply git root "status" "--porcelain=v1" "-z" "--ignored=traditional" "--untracked-files=all" "--" ins)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git status failed: " (str/trim (:err r)))))
    (vec (sort (distinct (for [path (status-paths (:out r))
                               :let [rel (if (and (seq prefix) (str/starts-with? path prefix)) (subs path (count prefix)) path)]
                               :when (under? ins rel)]
                           rel))))))

;; ---------------------------------------------------------------------------
;; The prepare stage

(defn prepare
  "The stage before the audit: refuses what cannot be audited (see the
  namespace doc)."
  [req]
  (let [config (:config req)
        _ (when-not (contains? config :regulated)
            (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated:"
                                      " only a regulated project has an audit copy")))
        _ (gap/regulated req)
        shown (str (first (:args req)))
        out (command/path req shown)
        _ (when (or (fs/exists? out) (fs/sym-link? out))
            (command/cannot-run! (str "OUT " shown " already exists: the audit copy is never written into"
                                      " an existing directory")))
        head (let [r (git (:root req) "rev-parse" "--verify" "--quiet" "HEAD^{commit}")]
               (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))
        _ (when-not head
            (command/cannot-run! (str (:root req) " has no commit: the audit copy is of one commit")))
        findings (vec (for [rel (uncommitted req (inputs req))]
                        (command/finding rel "audit-uncommitted"
                                         (str rel " is not committed: it differs from HEAD's tree (" (subs head 0 (min 12 (count head)))
                                              "), and the audit copy is of one commit; commit it or remove it"))))]
    (swap! (:state req) assoc ::commit head ::refused (boolean (seq findings)))
    {:result {} :findings findings}))

;; ---------------------------------------------------------------------------
;; Pages

(defn- cell [s] (-> (str s) (str/replace "|" "\\|") (str/replace #"\s*\n\s*" " ")))

(defn- row [& cells] (str "| " (str/join " | " (map cell cells)) " |\n"))

(defn clauses-page
  "Pure: OUT/clauses.md of the declared lists and ClausesIndexed's clauses."
  [declared clauses]
  (str "# Clauses\n\nWhere each clause of each declared clause list is met: the audit tags naming it,"
       " as FILE:LINE.\n"
       (str/join
        (for [l declared]
          (str "\n## " l "\n\n"
               (row "Clause" "Description" "Clause list" "Where it is met")
               "|---|---|---|---|\n"
               (str/join (for [c clauses :when (= l (:declared c))]
                           (row (:key c) (:description c) (str (:clause-list c) ":" (:line c))
                                (if (seq (:named-at c))
                                  (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c)))
                                  "not met")))))))))

(defn- scenario-rows [scs statuses]
  (str (row "Scenario" "Title" "Component" "Status")
       "|---|---|---|---|\n"
       (str/join (for [sc scs]
                   (row (str "@" (:id sc)) (:title sc)
                        (str (:context-name sc) " / " (:component-name sc))
                        (name (or (get statuses (:id sc)) :unverified)))))))

(defn traceability-page
  "Pure: OUT/traceability.md of the needs, the stamped scenarios and their
  statuses."
  [needs scenarios statuses]
  (let [need-ids (set (map :id needs))
        untraced (remove #(some need-ids (:needs %)) scenarios)]
    (str "# Traceability\n\nEach user need, the scenarios tracing to it, and each one's status.\n"
         (str/join
          (for [n (sort-by :id needs)
                :let [scs (filter #(some #{(:id n)} (:needs %)) scenarios)]]
            (str "\n## " (:id n) "\n\n" (:text n) "\n\n"
                 (if (seq scs)
                   (scenario-rows scs statuses)
                   (str "No scenario traces to " (:id n) ".\n")))))
         (when (seq untraced)
           (str "\n## Scenarios tracing to no declared need\n\n" (scenario-rows untraced statuses))))))

(defn manifest-text
  "Pure: OUT/manifest.edn of the commit and the files ({:path :sha256})."
  [commit files]
  (str "{:commit " (pr-str commit) "\n :files ["
       (str/join "\n         " (for [f (sort-by :path files)] (pr-str (array-map :path (:path f) :sha256 (:sha256 f)))))
       "]}\n"))

;; ---------------------------------------------------------------------------
;; PDFs

(defn pdf-input
  "Pure: {:title :body} of a document's text for its PDF: the frontmatter
  left out, the id and revision first."
  [text]
  (let [lines (str/split (str text) #"\n" -1)
        fm (gap/frontmatter lines)
        f (:fields fm)
        body (str/join "\n" (if fm (drop (inc (:end fm)) lines) lines))
        head (str/join ", " (remove str/blank? [(get f "id") (when-not (str/blank? (get f "revision"))
                                                                 (str "revision " (get f "revision")))]))]
    {:title (get f "title")
     :body (str (when-not (str/blank? head) (str head "\n\n")) (str/replace body #"^\n+" ""))}))

(defn- need-tool! [t]
  (when-not (fs/which t)
    (command/cannot-run! (str t " is not installed, so the audit copy's PDFs cannot be written"))))

(defn- pdf!
  "Writes the PDF of the document text `text` to `out`."
  [text out work]
  (let [{:keys [title body]} (pdf-input text)
        r (apply p/shell {:in body :out :string :err :string :continue true}
                 (concat ["pandoc" "-f" "markdown" "-s" "-t" "typst" "-V" "mainfont=DejaVu Sans"]
                         (when-not (str/blank? title) ["-M" (str "title=" title)])))
        _ (when-not (zero? (:exit r))
            (command/cannot-run! (str "pandoc could not convert " out ": " (str/trim (:err r)))))
        typ (str (fs/file work "document.typ"))
        _ (spit typ (str "#set document(date: none)\n" (:out r)))
        c (p/shell {:out :string :err :string :continue true :in ""} "typst" "compile" typ (str out))]
    (when-not (zero? (:exit c))
      (command/cannot-run! (str "typst could not compile " out ": " (str/trim (str (:err c) (:out c))))))))

;; ---------------------------------------------------------------------------
;; The audit stage

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (fs/read-all-bytes f))))))

(defn- files-of
  "Every file under `d`: [[relative-path file]], sorted by path."
  [d]
  (vec (sort-by first (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                        [(str/replace (str (fs/relativize d f)) "\\" "/") f]))))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- build!
  "Writes the audit copy into the directory `d`; returns how many documents."
  [req d commit]
  (let [{:keys [clause-lists documents]} (gap/regulated req)
        docs-dir (strip-slash documents)
        {list-files :list read-text :read} (gap/reader (:root req) commit)
        docs (vec (for [f (list-files docs-dir) :when (str/ends-with? f ".md")
                        :let [t (read-text f)] :when t]
                    [(subs f (inc (count docs-dir))) t]))
        spec (or (state req ::spec) (command/cannot-run! "the spec was not read: no SpecParsed event reached the audit"))
        derived (or (state req ::derived)
                    (command/cannot-run! "no status was derived: no VerificationDerived event reached the audit"))
        clauses (or (state req ::clauses)
                    (command/cannot-run! "the clauses were not read: no ClausesIndexed event reached the audit"))
        results (command/path req (:results req))
        work (str (fs/create-temp-dir {:prefix "rdm-spec-audit-"}))]
    (try
      (doseq [[rel text] docs
              [copy t] [["auditor" text] ["engineer" (gap/strip-tags text)]]
              :let [md (str copy "/" rel)]]
        (write! d md t)
        (pdf! t (fs/file d (str/replace md #"\.md$" ".pdf")) work))
      (finally (fs/delete-tree work)))
    (write! d "clauses.md" (clauses-page (mapv strip-slash clause-lists) (:clauses clauses)))
    (write! d "traceability.md" (traceability-page (:needs spec) (filterv :id (:scenarios spec)) (:statuses derived)))
    (doseq [[rel f] (files-of results)]
      (let [to (fs/file d "results" rel)]
        (fs/create-dirs (fs/parent to))
        (fs/copy f to)))
    (write! d "manifest.edn" (manifest-text commit (for [[rel f] (files-of d)] {:path rel :sha256 (sha256 f)})))
    (count docs)))

(defn audit
  "The audit stage: writes OUT, unless prepare named a file not committed."
  [req]
  (if (or (state req ::refused) (nil? (state req ::commit)))
    {:result {} :findings []}
    (let [commit (state req ::commit)
          shown (str (first (:args req)))
          out (fs/absolutize (command/path req shown))
          parent (fs/parent out)
          _ (need-tool! "pandoc")
          _ (need-tool! "typst")
          _ (fs/create-dirs parent)
          tmp (fs/create-temp-dir {:dir parent :prefix ".audit-"})
          n (try (let [n (build! req tmp commit)]
                   (fs/set-posix-file-permissions tmp "rwxr-xr-x")
                   (fs/move tmp out)
                   n)
                 (catch Throwable e (fs/delete-tree tmp) (throw e)))]
      {:result {:audit {:out shown :commit commit :documents n
                        :files (count (files-of out))}}
       :findings []})))
