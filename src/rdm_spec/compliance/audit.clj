(ns rdm-spec.compliance.audit
  "Component Audit copy (compliance context): for a record the release check
  passes, and only of one commit, writes the auditor's copy (every audit tag
  kept) and the engineer's copy (every tag removed, with the space before
  it, line breaks kept) of every controlled document, each also as a PDF;
  the clauses page; the traceability page; the results; and a manifest
  pinning every file by SHA-256. The same commit and results give the same
  bytes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.compliance.gap :as gap]))

;; ------------------------------------------------------------------ the engineer's copy

(defn engineer-text
  "TEXT with every audit tag removed with the whitespace before it on its
  line, the line breaks inside a tag kept, so every line keeps its number."
  [text]
  (reduce (fn [t {:keys [start end]}]
            (let [s (loop [i start] (if (and (pos? i) (#{\space \tab} (.charAt ^String t (dec i)))) (recur (dec i)) i))
                  inner (subs t start end)
                  kept (apply str (filter #(= \newline %) inner))]
              (str (subs t 0 s) kept (subs t end))))
          text
          (sort-by :start > (:tags (gap/scan text)))))

;; ------------------------------------------------------------------ what it is made from

(defn sources-of
  "Every file the audit copy is made from: the configuration, the spec, the clause lists and the documents."
  [cfg]
  (let [root (project/root)
        reg (:regulated cfg)
        lists (distinct (concat (:clause-lists reg)
                                (for [l (:clause-lists reg) c (:clauses (gap/read-list root l l []))] (:clause-list c))))]
    (vec (distinct (concat [project/config-file]
                           (map :dir (project/specs cfg))
                           lists
                           [(:documents reg)])))))

(defn uncommitted
  "Every path under PATHS that differs from HEAD's tree, untracked and ignored files included."
  [paths]
  (let [root (project/root)
        prefix (project/git-prefix)
        r (apply project/git root "status" "--porcelain=v1" "-z" "--ignored=matching" "--untracked-files=all" "--" paths)]
    (when-not (= 0 (:exit r)) (project/cannot-run! (str "git status: " (project/one-line (:err r)))))
    (vec (sort (distinct (for [e (str/split (:out r) #"\u0000") :when (> (count e) 3)
                               :let [p (subs e 3)]]
                           (if (str/starts-with? p prefix) (subs p (count prefix)) p)))))))

;; ------------------------------------------------------------------ PDFs

(defn pdf!
  "Writes the Markdown document TEXT (its frontmatter FM) as a PDF at OUT,
  its first page naming the document's id, revision and title."
  [text fm out]
  (project/with-temp-dir [d "rdm-pdf-"]
    (let [body (str/replace-first text #"(?s)\A---\n.*?\n(---|\.\.\.)\n" "")
          md (str "# " (get fm "title") "\n\n"
                  "Document " (get fm "id") ", revision " (get fm "revision") "\n\n"
                  body)
          in (str d "/doc.md") typ (str d "/doc.typ")]
      (spit in md)
      (let [r (project/sh ["pandoc" "-s" "-f" "markdown-yaml_metadata_block" "-t" "typst" "-V" "mainfont=DejaVu Sans" in "-o" typ] {:dir d})]
        (cond (nil? (:exit r)) (project/cannot-run! (str "pandoc is not installed: " (:error r)))
              (not= 0 (:exit r)) (project/cannot-run! (str "pandoc could not convert a document: " (project/one-line (:err r))))))
      (spit typ (str "#set document(date: none)\n" (slurp typ)))
      (let [r (project/sh ["typst" "compile" typ out] {:dir d})]
        (cond (nil? (:exit r)) (project/cannot-run! (str "typst is not installed: " (:error r)))
              (not= 0 (:exit r)) (project/cannot-run! (str "typst could not compile a document: " (project/one-line (:err r)))))))))

;; ------------------------------------------------------------------ pages

(defn cell [s] (str/replace (str s) "|" "\\|"))

(defn clauses-md [cfg clauses]
  (str "# Clauses\n\nEvery clause of every declared clause list, includes in place, and where an audit tag names it.\n"
       (str/join
        (for [l (:clause-lists (:regulated cfg))]
          (str "\n## " l "\n\n| Clause | Description | Named in |\n|---|---|---|\n"
               (str/join (for [c clauses :when (= l (:declared c))]
                           (str "| " (cell (:key c)) " | " (cell (:description c)) " | "
                                (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c))) " |\n"))))))))

(defn traceability-md [parsed statuses]
  (let [scs (filter :id (:scenarios parsed))]
    (str "# Traceability\n\nEvery user need, the scenarios tracing to it, and their status.\n"
         (str/join
          (for [n (:needs parsed)
                :let [tracing (filter #(some #{(:id n)} (:needs %)) scs)]]
            (str "\n## " (:id n) "\n\n" (:text n) "\n\n"
                 (if (seq tracing)
                   (str "| Scenario | Title | Component | Status |\n|---|---|---|---|\n"
                        (str/join (for [sc tracing]
                                    (str "| @" (:id sc) " | " (cell (:title sc)) " | " (cell (:component-name sc)) " | "
                                         (name (get statuses (:id sc) :unverified)) " |\n"))))
                   "No scenario traces to it.\n")))))))

(defn sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (fs/read-all-bytes f))))))

;; ------------------------------------------------------------------ the task

(defn audit-task [{:keys [cfg args opts]}]
  (let [out-arg (first args)
        out (project/abs out-arg)
        reg (:regulated cfg)]
    (when-not reg (project/cannot-run! "the configuration holds no :regulated, so there is nothing to audit"))
    (when (or (fs/exists? out) (fs/sym-link? out))
      (project/cannot-run! (str "OUT " out-arg " already exists: the audit copy is written into a new directory, never into one")))
    (when-not (project/git-repo? (project/root))
      (project/cannot-run! "the project is not in a git repository: an audit copy is of one commit"))
    (let [dirty (uncommitted (sources-of cfg))]
      (if (seq dirty)
        (project/report {} (for [f dirty]
                             (project/finding f "audit-uncommitted"
                                              (str f " is not committed: an audit copy is of one commit, HEAD, or of nothing"))))
        (let [release ((requiring-resolve 'rdm-spec.verification.release/release-task) {:cfg cfg :opts opts})]
          (if (seq (:findings release))
            (assoc release :result {})
            (let [root (project/root)
                  commit (project/resolve-commit "HEAD")
                  parsed (bus/latest :SpecParsed)
                  statuses (:statuses (bus/latest :VerificationDerived))
                  clauses (:clauses (bus/latest :ClausesIndexed))
                  docs (gap/documents root reg)
                  rd (project/results-dir cfg opts)]
              (fs/create-dirs out)
              (doseq [f docs]
                (let [rel (project/rel (project/abs (:documents reg)) (project/abs f))
                      text (project/read-text root f)
                      eng (engineer-text text)]
                  (doseq [[copy t] [["auditor" text] ["engineer" eng]]]
                    (let [md (str out "/" copy "/" rel)]
                      (fs/create-dirs (fs/parent md))
                      (spit md t)
                      (pdf! t (:frontmatter (gap/scan t)) (str/replace md #"\.md$" ".pdf"))))))
              (spit (str out "/clauses.md") (clauses-md cfg clauses))
              (spit (str out "/traceability.md") (traceability-md parsed statuses))
              (doseq [f (sort (map str (fs/glob (:abs rd) "**"))) :when (fs/regular-file? f)]
                (let [to (str out "/results/" (project/rel (:abs rd) f))]
                  (fs/create-dirs (fs/parent to))
                  (fs/copy f to {:replace-existing true})))
              (let [files (sort (for [f (fs/glob out "**") :when (fs/regular-file? f)] (project/rel out f)))]
                (spit (str out "/manifest.edn")
                      (str "{:commit " (pr-str commit) "\n :files ["
                           (str/join "\n         " (for [f files] (pr-str {:path f :sha256 (sha256 (str out "/" f))})))
                           "]}\n"))
                (project/report {:audit {:out out-arg :commit commit :documents (count docs) :files (inc (count files))}}
                                []
                                (:warnings release))))))))))
