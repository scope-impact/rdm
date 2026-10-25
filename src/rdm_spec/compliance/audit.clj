(ns rdm-spec.compliance.audit
  "The Audit copy, the compliance context's second component
  (dhf/spec/compliance/audit.feature; dhf/spec/glossary.md, \"Audit copy\"):
  `rdm-spec audit OUT [--results DIR]` writes what a team hands an auditor,
  only for a record the release check passes, and only of one commit.

  The task's stages: `prepare` (this namespace), the parser, the gap stage
  (quiet), the release check (a gate), then `audit` (this namespace).

  prepare cannot run (exit 2) on a configuration holding no :regulated, an
  OUT that exists (never written into), a project with no commit, or no
  document converter or typesetter on PATH; it finds (exit 1) every file
  the audit copy is made from (the configuration, the spec, the clause
  lists, the documents) that differs from HEAD, untracked included
  (audit-uncommitted). Then nothing is written.

  audit writes, from the scenarios (SpecParsed), their statuses
  (VerificationDerived, published by the release check) and the clauses
  (ClausesIndexed):

    OUT/auditor/<doc>.md and .pdf   every controlled document, audit tags kept
    OUT/engineer/<doc>.md and .pdf  the same, every audit tag removed
    OUT/clauses.md                  one section per declared clause list
    OUT/traceability.md             each need, its scenarios and their status
    OUT/results/                    a copy of the results directory
    OUT/manifest.edn                {:commit :files [{:path :sha256}]}, sorted

  The same commit and results give the same bytes, PDFs included. Its
  result: {:audit {:out OUT :commit C :documents n :files n}}."
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

(defn- event [req e]
  (or (some-> (:state req) deref (get-in [::events e]))
      (command/cannot-run! (str "no " (name e) " event reached the audit copy, so it cannot be written"))))

;; ---------------------------------------------------------------------------
;; git

(defn- git [req & args]
  (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""} "git" args))

(defn head
  "The commit HEAD resolves to, or nil."
  [req]
  (let [r (git req "rev-parse" "--verify" "--quiet" "HEAD^{commit}")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn uncommitted
  "Every path under `paths` that differs from HEAD, untracked included,
  sorted."
  [req paths]
  (let [r (apply git req "status" "--porcelain=v1" "-z" "--untracked-files=all" "--" paths)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git status failed: " (str/trim (:err r)))))
    (loop [[e & more] (remove str/blank? (str/split (:out r) #"\u0000")) out []]
      (if (nil? e)
        (vec (sort (distinct out)))
        (let [xy (subs e 0 2)
              path (subs e 3)]
          (if (some #{\R \C} xy)
            (recur (rest more) (conj out path (first more)))
            (recur more (conj out path))))))))

;; ---------------------------------------------------------------------------
;; prepare

(defn- out-arg [req] (str (first (:args req))))

(defn prepare
  "The audit task's first stage: what must hold before anything is read."
  [req]
  (when-not (contains? (:config req) :regulated)
    (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated:"
                              " only a project under the regulated profile has an audit copy")))
  (let [{:keys [clause-lists documents]} (gap/regulated req)
        out (out-arg req)]
    (when (fs/exists? (command/path req out))
      (command/cannot-run! (str out " exists: the audit copy is never written into an existing directory")))
    (doseq [tool ["pandoc" "typst"]]
      (when-not (fs/which tool)
        (command/cannot-run! (str "the audit copy's PDFs need " tool ", which is not installed (not on PATH)"))))
    (let [commit (or (head req)
                     (command/cannot-run! "the project has no commit: an audit copy is of one commit"))
          {read-text :read} (gap/reader (:root req) nil)
          paths (vec (distinct (concat [command/config-file (command/config-value req :spec-dir)]
                                       (gap/list-files read-text (mapv #(str/replace % #"/+$" "") clause-lists))
                                       [(str/replace documents #"/+$" "")])))
          dirty (uncommitted req paths)
          findings (vec (for [f dirty]
                          (command/finding f "audit-uncommitted"
                                           (str f " is not committed: the audit copy is of one commit, HEAD "
                                                (subs commit 0 (min 12 (count commit))) ", or of nothing"))))]
      (swap! (:state req) assoc ::commit commit ::blocked? (boolean (seq findings)))
      {:findings findings})))

;; ---------------------------------------------------------------------------
;; Pages

(defn- cell [s] (str/replace (str s) "|" "\\|"))

(defn clauses-page
  "OUT/clauses.md: one section per declared clause list, its clauses in
  order, included ones in place, each with where it is named."
  [lists clauses]
  (str "# Clauses\n\n"
       "Every clause of every declared clause list, included clauses in place, with where an audit tag names it.\n"
       (str/join
        (for [l lists
              :let [cs (filter #(= l (:declared %)) clauses)]]
          (str "\n## " l "\n\n"
               "| Clause | Description | Named at |\n|---|---|---|\n"
               (str/join (for [c cs]
                           (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                                (cell (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c)))) " |\n"))))))))

(defn traceability-page
  "OUT/traceability.md: each need with its text, its scenarios and their status."
  [spec statuses]
  (let [scs (filter :id (:scenarios spec))]
    (str "# Traceability\n\n"
         "Each user need, the scenarios that trace to it, and the status the results give each.\n"
         (str/join
          (for [n (:needs spec)
                :let [mine (filter #(some #{(:id n)} (:needs %)) scs)]]
            (str "\n## " (:id n) "\n\n" (when (:text n) (str (:text n) "\n\n"))
                 (if (empty? mine)
                   (str "No scenario traces to " (:id n) ".\n")
                   (str "| Scenario | Title | Component | Context | Level | Status |\n|---|---|---|---|---|---|\n"
                        (str/join (for [sc mine]
                                    (str "| @" (:id sc) " | " (cell (:title sc)) " | " (cell (:component-name sc)) " | "
                                         (cell (:context-name sc)) " | " (some-> (:level sc) name) " | "
                                         (name (get statuses (:id sc) :unverified)) " |\n")))))))))))

(defn manifest-text
  "OUT/manifest.edn's text."
  [commit files]
  (str "{:commit " (pr-str commit) "\n :files ["
       (str/join "\n         " (for [{:keys [path sha256]} files] (str "{:path " (pr-str path) " :sha256 " (pr-str sha256) "}")))
       "]}\n"))

(defn sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (fs/read-all-bytes f))))))

;; ---------------------------------------------------------------------------
;; PDFs

(defn- sh! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str (first args) " failed: " (str/trim (str (:err r)))) {})))
    r))

(defn pdf-markdown
  "The Markdown a copy's PDF is made from: its identity first, then its body."
  [text]
  (let [lines (str/split text #"\n" -1)
        fm (gap/frontmatter lines)
        f (:fields fm)
        body (str/join "\n" (if fm (drop (inc (:end fm)) lines) lines))]
    {:title (or (get f "title") "")
     :markdown (str "**" (get f "id") "**, revision " (get f "revision") "\n\n" body)}))

(defn write-pdf!
  "Writes the PDF of the copy `text` to `pdf`, through `work` (a scratch directory)."
  [work text pdf]
  (let [{:keys [title markdown]} (pdf-markdown text)
        md (fs/file work "copy.md")
        typ (fs/file work "copy.typ")]
    (spit (str md) markdown)
    (sh! work "pandoc" "-s" "-f" "markdown-yaml_metadata_block" "-t" "typst"
          "-V" "mainfont=DejaVu Sans" "-M" (str "title=" title) "-o" (str typ) (str md))
    (spit (str typ) (str "#set document(date: none)\n" (slurp (str typ))))
    (sh! work "typst" "compile" (str typ) (str pdf))
    (fs/delete-if-exists md)
    (fs/delete-if-exists typ)))

;; ---------------------------------------------------------------------------
;; audit

(defn- write-out! [req out-dir commit]
  (let [spec (event req :SpecParsed)
        derived (event req :VerificationDerived)
        clauses (event req :ClausesIndexed)
        {:keys [clause-lists]} (gap/regulated req)
        {read-text :read} (gap/reader (:root req) nil)
        work (fs/create-temp-dir {:prefix "rdm-spec-audit-"})]
    (try
      (doseq [{:keys [file]} (:documents clauses)
              :let [rel (str (fs/relativize (fs/normalize (fs/file (str/replace (get-in req [:config :regulated :documents]) #"/+$" "")))
                                            (fs/normalize (fs/file file))))
                    text (or (read-text file) (command/cannot-run! (str file " cannot be read")))
                    pdf-rel (str/replace rel #"\.md$" ".pdf")]
              [who copy] [["auditor" text] ["engineer" (gap/strip-tags text)]]]
        (let [f (fs/file out-dir who rel)]
          (fs/create-dirs (fs/parent f))
          (spit (str f) copy)
          (write-pdf! work copy (fs/file out-dir who pdf-rel))))
      (spit (str (fs/file out-dir "clauses.md"))
            (clauses-page (mapv #(str/replace % #"/+$" "") clause-lists) (:clauses clauses)))
      (spit (str (fs/file out-dir "traceability.md")) (traceability-page spec (:statuses derived)))
      (let [rd (command/path req (:results req))]
        (fs/copy-tree rd (fs/file out-dir "results")))
      (let [files (sort (for [f (fs/glob out-dir "**") :when (fs/regular-file? f)]
                          (str/replace (str (fs/relativize out-dir f)) "\\" "/")))
            entries (vec (for [f files] {:path f :sha256 (sha256 (fs/file out-dir f))}))]
        (spit (str (fs/file out-dir "manifest.edn")) (manifest-text commit entries))
        {:documents (count (:documents clauses)) :files (inc (count files))})
      (finally (fs/delete-tree work)))))

(defn audit
  "The audit stage: writes OUT, unless prepare found what is not committed."
  [req]
  (let [{::keys [commit blocked?]} @(:state req)]
    (if blocked?
      {:findings []}
      (let [out (out-arg req)
            out-dir (command/path req out)]
        (when (fs/exists? out-dir)
          (command/cannot-run! (str out " exists: the audit copy is never written into an existing directory")))
        (fs/create-dirs out-dir)
        (try
          (let [{:keys [documents files]} (write-out! req out-dir commit)]
            {:result {:audit {:out out :commit commit :documents documents :files files}}
             :findings []})
          (catch Throwable e
            (fs/delete-tree out-dir)
            (if (command/cannot-run? e)
              (throw e)
              (command/cannot-run! (str "the audit copy could not be written, so " out " was removed: " (ex-message e))))))))))
