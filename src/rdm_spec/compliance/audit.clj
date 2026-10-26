(ns rdm-spec.compliance.audit
  "The audit copy (dhf/spec/compliance/audit.feature): of one commit and only
  of a record the release check passes. It writes the auditor's copy (every
  audit tag kept) and the engineer's copy (every tag removed) of each
  controlled document, each also as a PDF, the clauses page, the traceability
  page, the results and a manifest pinning every file."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(defn- out-path [root args] (fs/absolutize (fs/path root (first args))))

(defn precondition-stage
  "No :regulated, no audit copy; an existing OUT is never written into."
  [{:keys [root config args]}]
  (when-not (:regulated config)
    (cmd/cannot-run! "the configuration holds no :regulated: there is no audit copy without the regulated profile"))
  (when (fs/exists? (out-path root args))
    (cmd/cannot-run! (str "the directory " (first args) " already exists: an audit copy is written only into a new directory")))
  {})

(defn made-from
  "The paths an audit copy is made from."
  [config]
  (let [{:keys [clause-lists documents]} (:regulated config)]
    (distinct (concat ["dhf/harness/config.edn" (:spec-dir config "dhf/spec")]
                      clause-lists
                      (keep #(some-> (fs/parent %) str) clause-lists)
                      [(or documents "dhf/documents")]))))

(defn committed-stage
  "A gate: every file the copy is made from is HEAD's, untracked and ignored ones included."
  [{:keys [root config]}]
  (when-not (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" "HEAD")))
    (cmd/cannot-run! "the repository has no commit: an audit copy is of one commit"))
  (let [{:keys [out]} (apply cmd/git root "status" "--porcelain" "--ignored" "--untracked-files=all" "--" (made-from config))
        paths (distinct (for [l (str/split-lines out) :when (> (count l) 3)]
                          (let [p (subs l 3)] (last (str/split p #" -> ")))))]
    {:findings (vec (for [p paths]
                      (cmd/finding p nil "not-committed"
                                   (str p " is not committed: an audit copy is of one commit, and every file it is made from must be HEAD's"))))}))

;; ---------------------------------------------------------------------------
;; PDFs

(defn- pdf!
  "Writes the PDF of a document's Markdown: Pandoc to Typst, then Typst."
  [root md-text header out-file]
  (let [dir (cmd/temp-dir "rdm-spec-pdf-")]
    (try
      (let [in (fs/path dir "in.md") typ (fs/path dir "in.typ")
            _ (spit (fs/file in) (str header "\n\n" md-text))
            r (cmd/sh {:dir (str dir)} "pandoc" "-f" "markdown-yaml_metadata_block" "-s" "-t" "typst"
                      "-V" "mainfont=DejaVu Sans" "-o" (str typ) (str in))]
        (when (:not-found r) (cmd/cannot-run! "pandoc is not installed: no PDF can be written"))
        (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "pandoc failed: " (:err r))))
        (spit (fs/file typ) (str "#set document(date: none)\n" (slurp (fs/file typ))))
        (let [t (cmd/sh {:dir (str dir) :env {"SOURCE_DATE_EPOCH" "0"}} "typst" "compile" (str typ) (str out-file))]
          (when (:not-found t) (cmd/cannot-run! "typst is not installed: no PDF can be written"))
          (when-not (= 0 (:exit t)) (cmd/cannot-run! (str "typst failed: " (:err t))))))
      (finally (fs/delete-tree dir)))))

(defn- body-of [text]
  (let [lines (str/split-lines (str text))]
    (if-let [e (gap/frontmatter-end lines)]
      (str/join "\n" (drop (inc e) lines))
      (str text))))

(defn- sha256 [f]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes f))]
    (apply str (map #(format "%02x" %) d))))

;; ---------------------------------------------------------------------------
;; The copy

(defn stage [{:keys [root config args verification] :as ctx}]
  (let [out (out-path root args)
        {:keys [documents]} (:regulated config)
        docs-dir (or documents "dhf/documents")
        {:keys [clauses]} (bus/latest :ClausesIndexed)
        {:keys [scenarios needs]} (bus/latest :SpecParsed)
        statuses (or (:statuses (bus/latest :VerificationDerived)) {})
        commit (cmd/git! root "rev-parse" "HEAD")
        doc-files (filter #(str/ends-with? % ".md") (cmd/files-at root nil docs-dir))]
    (fs/create-dirs out)
    ;; the two copies of every controlled document
    (doseq [f doc-files]
      (let [rel (str (fs/relativize (fs/path root docs-dir) (fs/path root f)))
            text (slurp (fs/file root f))
            fm (gap/frontmatter text)
            header (str "**" (:id fm) "**, revision " (:revision fm) ": " (:title fm))
            engineer (gap/strip-tags text)
            eng-fm (gap/frontmatter engineer)
            eng-header (str "**" (:id eng-fm) "**, revision " (:revision eng-fm) ": " (:title eng-fm))
            pdf-name (str/replace rel #"\.md$" ".pdf")]
        (doseq [[copy t h] [["auditor" text header] ["engineer" engineer eng-header]]]
          (let [md (fs/path out copy rel)]
            (fs/create-dirs (fs/parent md))
            (spit (fs/file md) t)
            (pdf! root (body-of t) h (fs/path out copy pdf-name))))))
    ;; the clauses page: one section per declared clause list, includes in place
    (spit (fs/file out "clauses.md")
          (str "# Clauses\n\nEvery clause of every declared clause list, and where an audit tag names it. Named is not met: a grader reads each.\n"
               (str/join
                (for [l (get-in config [:regulated :clause-lists])]
                  (str "\n## " l "\n\n| Clause | Description | Named in |\n|---|---|---|\n"
                       (str/join (for [c clauses :when (= l (:declared c))]
                                   (str "| " (:key c) " | " (:description c) " | "
                                        (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c))) " |\n"))))))))
    ;; the traceability page
    (spit (fs/file out "traceability.md")
          (str "# Traceability\n\nEach user need, the scenarios that trace to it and their status.\n"
               (str/join
                (for [n needs]
                  (str "\n## " (:id n) "\n\n" (:text n) "\n\n| Scenario | Title | Component | Status |\n|---|---|---|---|\n"
                       (str/join (for [s scenarios :when (and (:id s) (some #{(:id n)} (:needs s)))]
                                   (str "| @" (:id s) " | " (:title s) " | " (:component-name s) " | "
                                        (name (get statuses (:id s) :unverified)) " |\n"))))))))
    ;; the results the record was verified by
    (let [rdir (fs/absolutize (fs/path root (cmd/results-dir ctx)))]
      (when (fs/directory? rdir)
        (doseq [f (sort (map str (fs/list-dir rdir))) :when (fs/regular-file? f)]
          (fs/create-dirs (fs/path out "results"))
          (fs/copy f (fs/path out "results" (fs/file-name f)) {:replace-existing true}))))
    ;; the manifest
    (let [files (->> (fs/glob out "**") (filter fs/regular-file?) (map #(str (fs/relativize out %))) sort)]
      (spit (fs/file out "manifest.edn")
            (with-out-str (pp/pprint {:commit commit :files (vec (for [f files] {:path f :sha256 (sha256 (fs/path out f))}))})))
      {:result {:audit {:out (first args) :commit commit :documents (count doc-files) :files (inc (count files))}}})))
