(ns rdm-spec.compliance.audit
  "Audit copy (dhf/spec/compliance/audit.feature): `rdm-spec audit OUT
  [--results DIR]` writes what a team hands an auditor, only for a record the
  release check passes (the release stage is the task's gate):

    OUT/auditor/<doc>.md  .pdf   every controlled document, audit tags kept
    OUT/engineer/<doc>.md .pdf   the same, every audit tag removed with the space before it
    OUT/clauses.md               one section per declared clause list, included clauses in place,
                                 each clause with where it is named (FILE:LINE)
    OUT/traceability.md          each need, its scenarios with title, component and status
    OUT/results/                 a copy of every file of the results directory
    OUT/manifest.edn             {:commit :clean :files [{:path :sha256}]}, sorted by path

  <doc> is the document's path under the documents directory. A PDF's first
  page names the document's title, id and revision. The same commit and
  results give the same bytes, PDFs included.

  Stages: `prepare` (before the spec is read) refuses (exit 2) a project
  without :regulated, an OUT that exists, a missing PDF tool and a project
  with no commit; `audit` writes OUT. The scenarios arrive as SpecParsed,
  their statuses as VerificationDerived and the clauses as ClausesIndexed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]
   [:VerificationDerived (fn [payload] (swap! (:state req) assoc ::statuses payload) nil)]
   [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]])

;; ---------------------------------------------------------------------------
;; Tools and git

(def pdf-tools ["pandoc" "typst"])

(defn- on-path?
  "True when `tool` is an executable file on the PATH of `env`."
  [env tool]
  (boolean (some (fn [d] (let [f (fs/file d tool)] (and (fs/regular-file? f) (fs/executable? f))))
                 (remove str/blank? (str/split (str (get env "PATH")) #":")))))

(defn- sh [req dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :env (:env req)} args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn head-commit
  "The commit HEAD resolves to in the project, or nil."
  [req]
  (let [r (sh req (:root req) "git" "rev-parse" "--verify" "--quiet" "HEAD^{commit}")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn clean?
  "True when no tracked file of the project differs from HEAD (untracked
  files never count)."
  [req]
  (let [r (sh req (:root req) "git" "status" "--porcelain" "--untracked-files=no")]
    (and (zero? (:exit r)) (str/blank? (:out r)))))

;; ---------------------------------------------------------------------------
;; prepare

(defn- out-arg [req] (first (:args req)))

(defn prepare
  "The first stage: refuses what the audit copy cannot be made for."
  [req]
  (when-not (contains? (:config req) :regulated)
    (command/cannot-run! (str "the configuration " command/config-file
                              " holds no :regulated: the audit copy is made only under the regulated profile")))
  (let [out (out-arg req)]
    (when (or (fs/exists? (command/path req out)) (fs/sym-link? (command/path req out)))
      (command/cannot-run! (str out " already exists: the audit copy is written only into a new directory"))))
  (doseq [t pdf-tools]
    (when-not (on-path? (:env req) t)
      (command/cannot-run! (str "the tool " t " is not on PATH: the audit copy's PDFs need it"))))
  (when-not (head-commit req)
    (command/cannot-run! "the project has no commit, and the audit copy's manifest pins one"))
  {})

;; ---------------------------------------------------------------------------
;; Pages (pure)

(defn- cell [s] (str/replace (str/replace (str s) "|" "\\|") #"\s*\n\s*" " "))

(defn clauses-page
  "Pure: OUT/clauses.md from the declared `lists` (in order) and the
  ClausesIndexed `clauses`."
  [lists clauses]
  (str "# Clauses\n\n"
       "Every clause of every declared clause list, included clauses in place, "
       "with where an audit tag names it.\n"
       (str/join
        (for [l lists
              :let [cs (filter #(= l (:declared %)) clauses)]]
          (str "\n## " l "\n\n"
               "| Clause | Description | Named at |\n"
               "|---|---|---|\n"
               (str/join
                (for [c cs]
                  (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                       (if (seq (:named-at c))
                         (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c)))
                         "not named")
                       " |\n"))))))))

(defn- kw [x] (if (keyword? x) (subs (str x) 1) (str x)))

(defn traceability-page
  "Pure: OUT/traceability.md from SpecParsed's `spec` and the `statuses`
  {id status}."
  [spec statuses]
  (let [stamped (sort-by :id (filter :id (:scenarios spec)))
        row (fn [sc]
              (str "| @" (:id sc) " | " (cell (:title sc)) " | "
                   (cell (str (or (:component-name sc) "") (when (:component sc) (str " (" (kw (:component sc)) ")"))))
                   " | " (kw (get statuses (:id sc) :unverified)) " |\n"))
        table (fn [scs]
                (str "| Scenario | Title | Component | Status |\n|---|---|---|---|\n"
                     (str/join (map row scs))))
        need-ids (set (map :id (:needs spec)))
        untraced (remove #(some need-ids (:needs %)) stamped)]
    (str "# Traceability\n\n"
         "Each user need, the scenarios that trace to it, and each scenario's status.\n"
         (str/join
          (for [n (:needs spec)
                :let [scs (filter #(some #{(:id n)} (:needs %)) stamped)]]
            (str "\n## " (:id n) "\n\n" (str/trim (str (:text n))) "\n\n"
                 (if (seq scs) (table scs) "No scenario traces to this need.\n"))))
         (when (seq untraced)
           (str "\n## Traced to no need\n\n" (table untraced))))))

;; ---------------------------------------------------------------------------
;; Writing

(defn- write! [f content]
  (fs/create-dirs (fs/parent f))
  (spit (str f) content))

(defn- pdf-source
  "The Markdown a PDF is made from: the title as metadata, then the id and
  revision, then the document's body."
  [{:keys [id revision title]} text]
  (let [{:keys [body-offset]} (gap/split-frontmatter text)]
    (str "---\ntitle: " (json/generate-string (str title)) "\n---\n\n"
         (str id) ", revision " (str revision) "\n\n"
         (subs text body-offset))))

(defn- pdf!
  "Writes the PDF of the Markdown `source` to `out`, in the scratch directory `tmp`."
  [req tmp source out]
  (let [md (fs/file tmp "doc.md")
        typ (fs/file tmp "doc.typ")
        main (fs/file tmp "main.typ")
        pdf (fs/file tmp "doc.pdf")]
    (spit (str md) source)
    (let [r (sh req tmp "pandoc" "-f" "markdown" "-s" "-t" "typst" "-V" "mainfont=DejaVu Sans" "doc.md" "-o" "doc.typ")]
      (when-not (zero? (:exit r))
        (command/cannot-run! (str "pandoc could not write " out ": " (str/trim (:err r))))))
    (spit (str main) (str "#set document(date: none)\n" (slurp (str typ))))
    (let [r (sh req tmp "typst" "compile" "main.typ" "doc.pdf")]
      (when-not (and (zero? (:exit r)) (fs/regular-file? pdf))
        (command/cannot-run! (str "typst could not compile " out ": " (str/trim (str (:err r) (:out r)))))))
    (fs/create-dirs (fs/parent out))
    (fs/copy pdf out {:replace-existing true})))

(defn sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. (fs/file f))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- rel-to [dir f] (str/replace (str (fs/relativize (fs/absolutize dir) (fs/absolutize f))) "\\" "/"))

(defn manifest
  "The manifest of the directory `out`: every file but the manifest itself,
  with its SHA-256, sorted by path."
  [commit clean out]
  (array-map :commit commit
             :clean clean
             :files (vec (sort-by :path
                                  (for [f (fs/glob out "**")
                                        :when (fs/regular-file? f)
                                        :let [path (rel-to out f)]
                                        :when (not= "manifest.edn" path)]
                                    (array-map :path path :sha256 (sha256 f)))))))

(defn- write-all!
  [req out]
  (let [{:keys [clause-lists documents]} (gap/regulated req)
        docs-dir (str/replace documents #"/+$" "")
        rd (gap/reader (:root req) nil)
        indexed (or (some-> (:state req) deref ::clauses)
                    (command/cannot-run! "the clauses were not read: no ClausesIndexed reached the audit copy"))
        spec (or (some-> (:state req) deref ::spec)
                 (command/cannot-run! "the spec was not read: no SpecParsed reached the audit copy"))
        statuses (:statuses (some-> (:state req) deref ::statuses))
        by-file (into {} (map (juxt :file identity) (:documents indexed)))
        tmp (fs/create-temp-dir {:prefix "rdm-spec-audit-"})]
    (try
      (doseq [d (:documents indexed)
              :let [file (:file d)
                    text ((:read rd) file)
                    under (rel-to (command/path req docs-dir) (command/path req file))
                    pdf-name (str (str/replace under #"\.md$" "") ".pdf")
                    eng (gap/strip-tags text)
                    ident (get by-file file)]]
        (write! (fs/file out "auditor" under) text)
        (write! (fs/file out "engineer" under) eng)
        (pdf! req tmp (pdf-source ident text) (fs/file out "auditor" pdf-name))
        (pdf! req tmp (pdf-source ident eng) (fs/file out "engineer" pdf-name)))
      (finally (fs/delete-tree tmp)))
    (write! (fs/file out "clauses.md")
            (clauses-page (mapv gap/normalize-rel clause-lists) (:clauses indexed)))
    (write! (fs/file out "traceability.md") (traceability-page spec statuses))
    (let [rdir (command/path req (:results req))]
      (fs/create-dirs (fs/file out "results"))
      (when (fs/directory? rdir)
        (doseq [f (sort (map str (fs/glob rdir "**")))
                :when (fs/regular-file? f)]
          (let [dest (fs/file out "results" (rel-to rdir f))]
            (fs/create-dirs (fs/parent dest))
            (fs/copy f dest {:replace-existing true})))))
    (let [m (manifest (head-commit req) (clean? req) out)]
      (spit (str (fs/file out "manifest.edn"))
            (str (binding [*print-namespace-maps* false] (pr-str m)) "\n"))
      {:documents (count (:documents indexed)) :files (inc (count (:files m)))
       :commit (:commit m) :clean (:clean m)})))

(defn audit
  "The audit stage: writes OUT, or nothing when it cannot be written whole."
  [req]
  (let [given (out-arg req)
        out (command/path req given)]
    (when (fs/exists? out)
      (command/cannot-run! (str given " already exists: the audit copy is written only into a new directory")))
    (try
      (fs/create-dirs out)
      (let [summary (write-all! req (fs/absolutize out))]
        {:result {:audit (merge {:out given} summary)}})
      (catch Throwable e
        (try (fs/delete-tree out) (catch Exception _ nil))
        (throw e)))))
