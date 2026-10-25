(ns rdm-spec.compliance.audit
  "Audit copy (dhf/spec/compliance/audit.feature): `rdm-spec audit OUT`
  writes what a team hands an auditor, only for a record the release check
  passes (its gate), from the clause lists and documents as committed at
  HEAD:

    OUT/auditor/<doc>.md and .pdf   every controlled document, audit tags kept
    OUT/engineer/<doc>.md and .pdf  the same, every audit tag removed with the
                                    white space before it, frontmatter included
    OUT/clauses.md                  per declared clause list, each clause
                                    (includes in place) and where it is named
    OUT/traceability.md             each need, its scenarios and their status
    OUT/results/                    a copy of the results directory
    OUT/manifest.edn                {:commit :clean :files [{:path :sha256}]}

  <doc> is the document's path under the documents directory. :clean is
  false when any file under the clause lists' or the documents' directories
  differs from HEAD, untracked files included. The same commit and results
  give the same bytes.

  `prepare` runs first and refuses (exit 2) a project without :regulated, an
  OUT that exists, a repository with no commit, or a missing PDF tool. The
  scenarios (SpecParsed), their statuses (VerificationDerived) and the
  clauses (ClausesIndexed) arrive on the bus."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

(def pdf-tools ["pandoc" "typst"])

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed in the run's state."
  [req]
  (for [e [:SpecParsed :VerificationDerived :ClausesIndexed]]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

(defn- event [req e]
  (or (some-> (:state req) deref (get-in [::events e]))
      (command/cannot-run! (str "no " (name e) " event reached the audit copy"))))

;; ---------------------------------------------------------------------------
;; Before anything runs

(defn- out-arg [req] (first (:args req)))

(defn prepare
  "Refuses what the audit copy cannot be made of, before the release check
  runs, and fixes the commit it is made from."
  [req]
  (gap/regulated req)
  (let [out (out-arg req)
        f (command/path req out)]
    (when (or (fs/exists? f) (fs/sym-link? f))
      (command/cannot-run! (str out " exists: the audit copy is written only into a directory that does not exist")))
    (let [head (or (gap/commit-of (:root req) "HEAD")
                   (command/cannot-run! "the repository has no commit at HEAD: an audit copy is made from what is committed"))]
      (doseq [t pdf-tools]
        (when-not (fs/which t)
          (command/cannot-run! (str "the tool " t " is not on PATH: the audit copy's PDFs need it"))))
      (swap! (:state req) assoc :rdm-spec.compliance.gap/head head)
      {})))

;; ---------------------------------------------------------------------------
;; The engineer's copy

(defn- remove-spans
  "Pure: `text` without the spans [s e], each with the spaces and tabs
  before it; a newline inside a span is kept, so lines stay lines."
  [text spans]
  (loop [spans (reverse (sort spans)) t text]
    (if-let [[[s e] & more] (seq spans)]
      (let [s' (loop [i s] (if (and (pos? i) (#{\space \tab} (.charAt ^String t (dec i)))) (recur (dec i)) i))
            kept (apply str (filter #(= \newline %) (subs t s e)))]
        (recur more (str (subs t 0 s') kept (subs t e))))
      t)))

(defn engineer-copy
  "Pure: a document's text with every audit tag removed, with the white
  space before it, in the body (outside code) and in the frontmatter."
  [text]
  (let [lines (str/split (str text) #"\n" -1)
        [front _ start] (gap/split-frontmatter lines)
        head-n (if front (dec start) 0)
        head (subvec (vec lines) 0 head-n)
        body (subvec (vec lines) head-n)
        head' (mapv #(remove-spans % (gap/tag-spans %)) head)
        body-text (str/join "\n" body)
        body' (remove-spans body-text (gap/tag-spans (gap/prose-text body)))]
    (str/join "\n" (concat head' [body']))))

;; ---------------------------------------------------------------------------
;; PDFs

(defn- sh! [env in & args]
  (let [r (apply p/shell {:out :string :err :string :continue true :in in :extra-env env} args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str (first args) " failed: " (str/trim (str (:err r))))))
    (:out r)))

(defn pdf-input
  "Pure: the Markdown a PDF is made from: the document's body, opened by its
  id and revision; its title goes in as metadata."
  [text]
  (let [lines (str/split-lines (str text))
        [front body _] (gap/split-frontmatter lines)
        fm (gap/frontmatter-map front)]
    {:title (get fm "title")
     :markdown (str "**" (or (get fm "id") "no id") "**, revision " (or (get fm "revision") "none") "\n\n"
                    (str/join "\n" body) "\n")}))

(defn write-pdf!
  "Writes the PDF of the document text `text` to `pdf`."
  [text pdf tmp]
  (let [{:keys [title markdown]} (pdf-input text)
        typ (sh! {} markdown "pandoc" "-s" "-f" "markdown" "-t" "typst" "-V" "mainfont=DejaVu Sans"
                  "-M" (str "title=" (or title "")))
        typ-file (fs/file tmp "doc.typ")]
    (spit typ-file (str "#set document(date: none)\n" typ))
    (fs/create-dirs (fs/parent pdf))
    (sh! {} "" "typst" "compile" (str typ-file) (str pdf))))

;; ---------------------------------------------------------------------------
;; The pages

(defn- cell [s] (str/replace (str/replace (str s) "|" "\\|") #"\s*\n\s*" " "))

(defn clauses-page
  "Pure: OUT/clauses.md from the declared lists and the clauses
  (ClausesIndexed's), one section per declared list."
  [commit declared clauses]
  (str "# Clauses\n\n"
       "Where each clause of each declared clause list is named, at commit " commit ".\n"
       (str/join
        (for [d declared
              :let [cs (filter #(= d (:declared %)) clauses)]]
          (str "\n## " d "\n\n"
               "| Clause | Description | Clause list | Named at |\n|---|---|---|---|\n"
               (str/join (for [c cs]
                           (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                                (:clause-list c) ":" (:line c) " | "
                                (if (seq (:named-at c))
                                  (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c)))
                                  "not named")
                                " |\n"))))))))

(defn traceability-page
  "Pure: OUT/traceability.md: each need, its text, and the scenarios tracing
  to it with their title, component and status."
  [needs scenarios statuses]
  (let [needs (sort-by :id (if (map? needs)
                             (for [[id n] needs] (assoc n :id id))
                             needs))
        stamped (filter :id scenarios)]
    (str "# Traceability\n"
         (str/join
          (for [{:keys [id text]} needs
                :let [scs (filter #(some #{id} (:needs %)) stamped)]]
            (str "\n## " id "\n\n" (str/trim (str text)) "\n\n"
                 (if (seq scs)
                   (str "| Scenario | Title | Component | Status |\n|---|---|---|---|\n"
                        (str/join (for [sc (sort-by :id scs)]
                                    (str "| @" (:id sc) " | " (cell (:title sc)) " | "
                                         (cell (or (:component-name sc) (some-> (:component sc) name))) " | "
                                         (some-> (get statuses (:id sc)) name) " |\n"))))
                   "No scenario traces to it.\n")))))))

;; ---------------------------------------------------------------------------
;; The manifest

(defn sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defn- files-under [dir]
  (vec (sort (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
               (str/replace (str (fs/relativize dir f)) "\\" "/")))))

(defn clean?
  "Whether no file under `dirs` differs from HEAD, untracked included."
  [root dirs]
  (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""}
                 "git" "status" "--porcelain" "--untracked-files=all" "--" (map #(if (str/blank? %) "." %) dirs))]
    (and (zero? (:exit r)) (str/blank? (:out r)))))

(defn manifest-text
  "Pure: the manifest, keys in the order the contract gives."
  [commit clean files]
  (str (pr-str (array-map :commit commit :clean clean
                          :files (mapv (fn [[path sha]] (array-map :path path :sha256 sha)) files)))
       "\n"))

;; ---------------------------------------------------------------------------
;; The stage

(defn- under [documents rel]
  (let [prefix (str (gap/normalize documents) "/")]
    (if (str/starts-with? rel prefix) (subs rel (count prefix)) rel)))

(defn- write-copy! [out rel text]
  (let [f (fs/file out rel)]
    (fs/create-dirs (fs/parent f))
    (spit f text)))

(defn- write-all! [req out]
  (let [{:keys [clause-lists documents]} (gap/regulated req)
        clauses-ev (event req :ClausesIndexed)
        spec (event req :SpecParsed)
        verified (event req :VerificationDerived)
        commit (:revision clauses-ev)
        src (gap/at-commit (:root req) commit)
        tmp (fs/create-temp-dir {:prefix "rdm-spec-audit-"})]
    (try
      (doseq [{:keys [file]} (:documents clauses-ev)
              :let [text (gap/read-text src file)
                    rel (under documents file)
                    pdf-rel (str (str/replace rel #"\.md$" "") ".pdf")
                    eng (engineer-copy text)]]
        (write-copy! out (str "auditor/" rel) text)
        (write-copy! out (str "engineer/" rel) eng)
        (write-pdf! text (fs/file out "auditor" pdf-rel) tmp)
        (write-pdf! eng (fs/file out "engineer" pdf-rel) tmp))
      (finally (fs/delete-tree tmp)))
    (write-copy! out "clauses.md" (clauses-page commit (mapv gap/normalize clause-lists) (:clauses clauses-ev)))
    (write-copy! out "traceability.md" (traceability-page (:needs spec) (:scenarios spec) (:statuses verified)))
    (let [results (command/path req (:results req))]
      (doseq [f (files-under results)]
        (fs/create-dirs (fs/parent (fs/file out "results" f)))
        (fs/copy (fs/file results f) (fs/file out "results" f) {:replace-existing true})))
    (let [dirs (distinct (concat (map gap/dir-of (map gap/normalize clause-lists))
                                 (map (comp gap/dir-of :clause-list) (:clauses clauses-ev))
                                 [(gap/normalize documents)]))
          clean (clean? (:root req) dirs)
          files (for [f (files-under out) :when (not= "manifest.edn" f)] [f (sha256 (fs/file out f))])]
      (write-copy! out "manifest.edn" (manifest-text commit clean files))
      {:commit commit :clean clean :documents (count (:documents clauses-ev)) :files (inc (count files))})))

(defn audit
  "Writes the audit copy into OUT, which does not exist yet."
  [req]
  (let [out (command/path req (out-arg req))]
    (fs/create-dirs out)
    (try
      {:result {:audit (assoc (write-all! req out) :out (out-arg req))}}
      (catch Throwable e
        (fs/delete-tree out)
        (throw e)))))
