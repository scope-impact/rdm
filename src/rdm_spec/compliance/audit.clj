(ns rdm-spec.compliance.audit
  "Audit copy (dhf/spec/compliance/audit.feature): `rdm-spec audit OUT
  [--results DIR]` writes what a team hands an auditor, only for a record
  the release check passes (the release check is the task's gate stage):

    OUT/auditor/<doc>.md and .pdf    every controlled document, audit tags kept
    OUT/engineer/<doc>.md and .pdf   the same, every audit tag removed with the
                                     white space before it
    OUT/clauses.md                   each clause list's clauses, where each is named
    OUT/traceability.md              each need, its scenarios and their status
    OUT/results/                     a copy of the results directory
    OUT/manifest.edn                 {:commit :clean :files [{:path :sha256}]}:
                                     every other file of OUT, sorted by path

  <doc> keeps its path under the documents directory. The Markdown and the
  manifest depend only on the commit and the results, so the same record
  gives the same files; the PDFs are rendered from the Markdown (the stack's
  document converter and typesetter), their first page naming the
  document's id, revision and title.

  Two stages: `prepare` runs first and refuses (exit 2) a configuration with
  no :regulated, an OUT that already exists, and a missing converter or
  typesetter, before anything is read; `audit`, after the release gate,
  writes OUT from SpecParsed, VerificationDerived and ClausesIndexed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

(def converter "pandoc")
(def typesetter "typst")

;; ---------------------------------------------------------------------------
;; The events this component reads

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed in the run's state."
  [req]
  (for [e [:SpecParsed :VerificationDerived :ClausesIndexed]]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; Refusing before anything is read

(defn prepare
  "The audit task's first stage: cannot run without :regulated, into an OUT
  that exists, or without the tools that render the PDFs."
  [req]
  (when-not (contains? (:config req) :regulated)
    (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated:"
                              " the audit copy is the regulated profile's, and this project did not declare it")))
  (gap/profile (:config req))
  (let [out (first (:args req))]
    (when (fs/exists? (command/path req out))
      (command/cannot-run! (str "OUT " out " already exists: the audit copy is written only into a new directory,"
                                " never into an existing one"))))
  (doseq [t [converter typesetter]]
    (when-not (fs/which t)
      (command/cannot-run! (str "the tool " t " is not installed (not on PATH), so no PDF can be written"))))
  {:result {} :findings []})

;; ---------------------------------------------------------------------------
;; Pure: the pages

(defn- cell [s] (-> (str s) (str/replace "|" "\\|") (str/replace #"\s*\n\s*" " ")))

(defn clauses-page
  "Pure: OUT/clauses.md from ClausesIndexed's clauses."
  [clauses]
  (let [lists (distinct (map :clause-list clauses))]
    (str "# Clauses\n\n"
         "Every clause of the declared clause lists, in their order with includes in place, and every audit tag"
         " naming it. Named is not met: a grader reads each place.\n"
         (str/join
          (for [l lists]
            (str "\n## " l "\n\n"
                 "| Clause | Description | Named at |\n"
                 "|---|---|---|\n"
                 (str/join
                  (for [c clauses :when (= l (:clause-list c))]
                    (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                         (if (seq (:named-at c))
                           (str/join ", " (for [{:keys [file line]} (:named-at c)] (str file ":" line)))
                           "named nowhere")
                         " |\n")))))))))

(defn- id-key [id]
  (vec (for [[_ d o] (re-seq #"(\d+)|(\D+)" (str id))] (if d [0 (parse-long d) ""] [1 0 o]))))

(defn- compare-keys [a b]
  (loop [a (seq a) b (seq b)]
    (cond (and (nil? a) (nil? b)) 0
          (nil? a) -1
          (nil? b) 1
          :else (let [c (compare (first a) (first b))] (if (zero? c) (recur (next a) (next b)) c)))))

(defn traceability-page
  "Pure: OUT/traceability.md from SpecParsed's scenarios and needs and
  VerificationDerived's statuses."
  [scenarios needs statuses]
  (let [needs (sort-by (comp id-key :id) compare-keys needs)
        stamped (filter :id scenarios)]
    (str "# Traceability\n\n"
         "Every user need, the scenarios that trace to it, and the status each was verified with.\n"
         (str/join
          (for [n needs
                :let [scs (filter #(some #{(:id n)} (:needs %)) stamped)]]
            (str "\n## " (:id n) "\n\n" (str/trim (str (:text n))) "\n\n"
                 (if (empty? scs)
                   "No scenario traces to this need.\n"
                   (str "| Scenario | Title | Component | Status |\n"
                        "|---|---|---|---|\n"
                        (str/join
                         (for [sc scs]
                           (str "| @" (:id sc) " | " (cell (:title sc)) " | "
                                (cell (or (:component-name sc) (some-> (:component sc) name) ""))
                                " | " (some-> (get statuses (:id sc)) name) " |\n")))))))))))

;; ---------------------------------------------------------------------------
;; Writing

(defn- sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f))))))

(defn- write! [out rel text]
  (let [f (fs/file out rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- sh! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str (first args) " failed (exit " (:exit r) "): " (str/trim (str (:err r) " " (:out r))))))
    (:out r)))

(defn pdf-source
  "Pure: the Markdown a document's PDF is rendered from: its title as the
  title, its id and revision first, then its body."
  [{:keys [id revision title]} body]
  (str "---\ntitle: " (pr-str (str title)) "\n---\n\n"
       "Document " id ", revision " revision "\n\n"
       body))

(defn- pdf! [tmp source pdf]
  (let [md (fs/file tmp "doc.md")
        typ (fs/file tmp "doc.typ")]
    (spit (str md) source)
    (sh! tmp converter "-s" "-t" "typst" "-V" "mainfont=DejaVu Sans" "-o" (str typ) (str md))
    (sh! tmp typesetter "compile" (str typ) (str pdf))))

(defn- git-state [root]
  (let [g (fn [& args] (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))
        head (g "rev-parse" "--verify" "--quiet" "HEAD")]
    (if (zero? (:exit head))
      {:commit (str/trim (:out head)) :clean (str/blank? (:out (g "status" "--porcelain")))}
      {:commit nil :clean false})))

(defn- manifest-text [{:keys [commit clean]} files]
  (str "{:commit " (pr-str commit) "\n :clean " (pr-str clean) "\n :files ["
       (str/join "\n         " (for [{:keys [path sha256]} files] (str "{:path " (pr-str path) " :sha256 " (pr-str sha256) "}")))
       "]}\n"))

(defn audit
  "The audit stage: writes OUT."
  [req]
  (let [out-arg (first (:args req))
        out (fs/absolutize (command/path req out-arg))
        prof (gap/profile (:config req))
        docs-dir (:documents prof)
        state (git-state (:root req))
        parsed (or (event req :SpecParsed) (command/cannot-run! "the spec was not read: no SpecParsed reached the audit copy"))
        derived (or (event req :VerificationDerived) (command/cannot-run! "no status was derived: no results were read"))
        clauses (or (event req :ClausesIndexed) (command/cannot-run! "the clauses were not indexed: no ClausesIndexed reached the audit copy"))
        tmp (fs/create-temp-dir {:prefix "rdm-spec-audit-"})]
    (try
      (fs/create-dirs out)
      (doseq [{:keys [file]} (:documents clauses)
              :let [text (slurp (str (command/path req file)))
                    under (subs file (inc (count docs-dir)))
                    pdf-rel (str/replace under #"\.md$" ".pdf")]
              [copy content] [["auditor" text] ["engineer" (gap/strip-tags text)]]]
        (write! out (str copy "/" under) content)
        ;; each copy's PDF names the document as that copy's frontmatter does
        (let [fm (gap/frontmatter content)
              fields (:fields fm)]
          (pdf! tmp (pdf-source {:id (get fields "id") :revision (get fields "revision") :title (get fields "title")}
                                (subs content (or (:end fm) 0)))
                (fs/file out copy pdf-rel))))
      (write! out "clauses.md" (clauses-page (:clauses clauses)))
      (write! out "traceability.md" (traceability-page (:scenarios parsed) (:needs parsed) (:statuses derived)))
      (when-let [rd (:results req)]
        (let [src (command/path req rd)]
          (fs/copy-tree src (fs/file out "results"))))
      (let [files (vec (sort-by :path (for [f (fs/glob out "**") :when (fs/regular-file? f)]
                                        {:path (str/replace (str (fs/relativize out f)) "\\" "/") :sha256 (sha256 f)})))]
        (write! out "manifest.edn" (manifest-text state files))
        {:result {:audit {:out out-arg :commit (:commit state) :clean (:clean state)
                          :documents (count (:documents clauses)) :files (inc (count files))}}
         :findings []})
      (catch Throwable e
        ;; never leave half an audit copy behind
        (fs/delete-tree out)
        (throw e))
      (finally (fs/delete-tree tmp)))))
