(ns rdm-spec.compliance.audit
  "The Audit copy, the compliance context's second component
  (dhf/spec/compliance/audit.feature; dhf/spec/glossary.md, \"Audit copy\"):
  `rdm-spec audit OUT [--results DIR]` writes what a team hands an auditor,
  only for a record the release check passes and only of one commit, HEAD.

  The task, in order: `revision` (HEAD, the commit the copy is of; cannot
  run without :regulated, with an OUT that exists, or outside a git
  repository with a commit), the gate `committed` (every file the copy is
  made from, the configuration, the spec, the clause lists and the
  documents, is HEAD's: an untracked, ignored, changed or deleted one is a
  finding, audit-uncommitted, naming it), the spec and the clauses read at
  HEAD, the release check as a gate (its findings printed unchanged), then
  `audit`, which reads the scenarios (SpecParsed), their statuses
  (VerificationDerived) and the clauses (ClausesIndexed) and writes:

    OUT/auditor/<doc>      every controlled document, every audit tag kept
    OUT/engineer/<doc>     every audit tag removed with the space before it
                           (in the frontmatter too), its line breaks kept
    OUT/auditor/<doc>.pdf and OUT/engineer/<doc>.pdf, each naming on its
                           first page the document's id, revision and title
    OUT/clauses.md         one section per declared clause list, included
                           clauses in place, each with where it is named
    OUT/traceability.md    each need, its text, and the scenarios tracing
                           to it with their title, component and status
    OUT/results/           a copy of every file of the results directory
    OUT/manifest.edn       {:commit HEAD :files [{:path :sha256}]}: every
                           other file of OUT, sorted by path

  <doc> is the document's path under the documents directory. The same
  commit and results give the same bytes, PDFs included. A copy that cannot
  be finished (a missing tool) leaves no OUT.

  Result: {:audit {:out OUT :commit C :documents n :files n}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; git

(defn- git [req & args]
  (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""} "git" args)
       (catch Exception e (command/cannot-run! (str "git cannot be run: " (ex-message e))))))

(defn- show-at [req commit rel]
  (let [r (git req "show" (str commit ":" rel))]
    (when (zero? (:exit r)) (:out r))))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

;; ---------------------------------------------------------------------------
;; Before anything is read

(defn revision
  "The commit the audit copy is of: HEAD. The task cannot run without the
  regulated profile, into an OUT that exists, or without a commit."
  [req]
  (when-not (contains? (:config req) :regulated)
    (command/cannot-run! (str "the configuration holds no :regulated: there is no audit copy without the"
                              " regulated profile's clause lists and controlled documents")))
  (let [out (first (:args req))]
    (when (fs/exists? (command/path req out))
      (command/cannot-run! (str out " exists: the audit copy is written into a new directory, never into one"))))
  (let [r (git req "rev-parse" "--verify" "-q" "HEAD^{commit}")]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str (:root req) " has no commit: an audit copy is of one commit, HEAD")))
    (str/trim (:out r))))

(defn- made-from
  "The paths the audit copy is made from, at HEAD and in the working tree."
  [req commit]
  (let [{:keys [clause-lists documents]} (get-in req [:config :regulated])
        lists (mapv strip-slash clause-lists)
        working (:read (gap/reader (:root req) nil))
        at-head (:read (gap/reader (:root req) commit))]
    (vec (distinct (concat [command/config-file
                            (strip-slash (command/config-value req :spec-dir))
                            (strip-slash documents)]
                           (gap/list-files working lists)
                           (gap/list-files at-head lists))))))

(defn- why-of [code]
  (cond (= "??" code) "untracked"
        (= "!!" code) "ignored by git"
        (str/includes? code "D") "deleted in the working tree"
        :else "changed since HEAD"))

(defn committed
  "The audit's first gate: every file the copy is made from is HEAD's."
  [req]
  (let [commit (:revision req)
        r (apply git req "status" "--porcelain=v1" "-z" "--ignored=matching" "--untracked-files=all" "--"
                 (made-from req commit))
        entries (loop [[x & more] (str/split (:out r) #"\u0000") out []]
                  (cond (nil? x) out
                        (str/blank? x) (recur more out)
                        :else (let [code (subs x 0 2) path (subs x 3)]
                                (if (re-find #"[RC]" code)
                                  (recur (rest more) (conj out [code path]))
                                  (recur more (conj out [code path]))))))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git cannot tell what is committed: " (str/trim (:err r)))))
    {:result {}
     :findings (vec (for [[code path] (sort-by second entries)]
                      (command/finding path "audit-uncommitted"
                                       (str "is not committed (" (why-of code) "): an audit copy is of one commit,"
                                            " HEAD, and every file it is made from is HEAD's; no OUT was written"))))}))

;; ---------------------------------------------------------------------------
;; Pure: the pages

(defn- cell [s] (str/replace (str s) "|" "\\|"))

(defn clauses-page
  "clauses.md: a section per declared clause list `declared`, its
  clauses (included ones in place) with where each is named."
  [declared clauses]
  (str "# Clauses\n\nEvery clause of every declared clause list, with the audit tags that name it."
       " Named is not met: the auditor reads each place.\n"
       (str/join
        (for [l declared
              :let [cs (filter #(= l (:declared %)) clauses)]]
          (str "\n## " l "\n\n| Clause | Description | Named in |\n|---|---|---|\n"
               (str/join (for [c cs]
                           (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                                (if (seq (:named-at c))
                                  (str/join ", " (for [{:keys [file line]} (:named-at c)] (str file ":" line)))
                                  "named nowhere")
                                " |\n"))))))))

(defn traceability-page
  "traceability.md: each need, its text, and every stamped scenario tracing
  to it with its title, component and status."
  [needs scenarios statuses]
  (str "# Traceability\n\nEach user need, and every scenario that traces to it, with its status.\n"
       (str/join
        (for [n needs
              :let [scs (sort-by :id (filter #(some #{(:id n)} (:needs %)) scenarios))]]
          (str "\n## " (:id n) "\n\n" (:text n) "\n\n"
               (if (seq scs)
                 (str "| Scenario | Title | Component | Status |\n|---|---|---|---|\n"
                      (str/join (for [sc scs]
                                  (str "| @" (:id sc) " | " (cell (:title sc)) " | " (cell (:component-name sc)) " | "
                                       (some-> (get statuses (:id sc)) name) " |\n"))))
                 "No scenario traces to it.\n"))))))

(defn manifest
  "The manifest of the commit `commit` and the files {path sha256}."
  [commit files]
  {:commit commit :files (vec (for [[path h] (sort-by key files)] {:path path :sha256 h}))})

(defn pdf-source
  "The Markdown a document's PDF is made from: the copy, the document's id
  and revision named first after its frontmatter, as its first page shows."
  [text {:keys [id revision]}]
  (let [ls (str/split (str text) #"\n" -1)
        fm (gap/frontmatter ls)
        line (str "**" id ", revision " revision "**")]
    (if fm
      (str/join "\n" (concat (take (inc (:end fm)) ls) ["" line ""] (drop (inc (:end fm)) ls)))
      (str line "\n\n" text))))

;; ---------------------------------------------------------------------------
;; Writing OUT

(defn- sha256-of [f]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes f))]
    (apply str (map #(format "%02x" %) d))))

(defn- run-tool! [dir & args]
  (let [r (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} args)
               (catch java.io.IOException e
                 (command/cannot-run! (str (first args) " cannot be run (" (ex-message e) "): the audit copy's PDFs need it"
                                           " (dhf/harness/stack.md)"))))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str (first args) " failed: " (str/trim (str (:err r) (:out r))))))))

(defn- pdf! [md out-file]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-audit-pdf-"})]
    (try
      (spit (str (fs/file tmp "in.md")) md)
      (run-tool! tmp "pandoc" "-s" "-f" "markdown" "-t" "typst" "-V" "mainfont=DejaVu Sans" "in.md" "-o" "pandoc.typ")
      (spit (str (fs/file tmp "in.typ")) (str "#set document(date: none)\n" (slurp (str (fs/file tmp "pandoc.typ")))))
      (run-tool! tmp "typst" "compile" "in.typ" "out.pdf")
      (fs/create-dirs (fs/parent out-file))
      (fs/copy (fs/file tmp "out.pdf") out-file {:replace-existing true})
      (finally (fs/delete-tree tmp)))))

(defn- write! [f text]
  (fs/create-dirs (fs/parent f))
  (spit (str f) text))

(defn subscriptions
  "Keeps SpecParsed, VerificationDerived and ClausesIndexed."
  [req]
  [[:SpecParsed (fn [x] (swap! (:state req) assoc ::spec x) nil)]
   [:VerificationDerived (fn [x] (swap! (:state req) assoc ::verification x) nil)]
   [:ClausesIndexed (fn [x] (swap! (:state req) assoc ::clauses x) nil)]])

(defn audit
  "The audit copy's stage: writes OUT."
  [req]
  (let [out-arg (first (:args req))
        out (command/path req out-arg)
        commit (:revision req)
        st (some-> (:state req) deref)
        spec (or (::spec st) (command/cannot-run! "no SpecParsed reached the audit copy"))
        statuses (:statuses (or (::verification st) (command/cannot-run! "no VerificationDerived reached the audit copy")))
        clauses (or (::clauses st) (command/cannot-run! "no ClausesIndexed reached the audit copy"))
        {:keys [clause-lists documents]} (get-in req [:config :regulated])
        docs-dir (strip-slash documents)
        results (:results req)]
    (when (fs/exists? out)
      (command/cannot-run! (str out-arg " exists: the audit copy is written into a new directory, never into one")))
    (try
      (fs/create-dirs out)
      (doseq [d (sort-by :file (:documents clauses))
              :let [text (or (show-at req commit (:file d))
                             (command/cannot-run! (str (:file d) " is not in HEAD")))
                    rel (subs (:file d) (inc (count docs-dir)))
                    pdf-rel (str/replace rel #"\.md$" ".pdf")
                    engineer (gap/strip-tags text)]]
        (write! (fs/file out "auditor" rel) text)
        (write! (fs/file out "engineer" rel) engineer)
        (pdf! (pdf-source text d) (fs/file out "auditor" pdf-rel))
        (pdf! (pdf-source engineer d) (fs/file out "engineer" pdf-rel)))
      (write! (fs/file out "clauses.md") (clauses-page (mapv strip-slash clause-lists) (:clauses clauses)))
      (write! (fs/file out "traceability.md")
              (traceability-page (:needs spec) (filter :id (:scenarios spec)) statuses))
      (let [rd (command/path req results)]
        (doseq [f (sort (map str (fs/glob rd "**"))) :when (fs/regular-file? f)
                :let [dest (fs/file out "results" (str (fs/relativize rd f)))]]
          (fs/create-dirs (fs/parent dest))
          (fs/copy f dest {:replace-existing true})))
      (let [files (into {} (for [f (fs/glob out "**") :when (fs/regular-file? f)]
                             [(str/replace (str (fs/relativize out f)) "\\" "/") (sha256-of f)]))]
        (write! (fs/file out "manifest.edn")
                (binding [*print-namespace-maps* false] (str (pr-str (manifest commit files)) "\n")))
        {:result {:audit {:out out-arg :commit commit :documents (count (:documents clauses)) :files (inc (count files))}}
         :findings []})
      (catch Throwable e
        (fs/delete-tree out)
        (throw e)))))
