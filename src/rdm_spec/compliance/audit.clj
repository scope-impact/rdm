(ns rdm-spec.compliance.audit
  "The audit copy: for a record the release check passes, and of one commit
  only, the auditor's copy (audit tags kept) and the engineer's copy (audit
  tags removed) of every controlled document, each also as a PDF; the clauses
  and where each is met; the traceability; the results; and a manifest
  pinning every file (dhf/spec/10-regulated-profile/audit.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.verification.release :as release]))

(defn out-problem [out]
  (when (fs/exists? out) (str out " already exists: an audit copy is never written into an existing directory")))

(defn engineer
  "A document's text with every audit tag removed with the space before it, in
  the frontmatter too, its line breaks kept; code left as it is."
  [text]
  (let [spans (gap/tag-spans text :keep-frontmatter true)]
    (reduce (fn [t {:keys [start end]}]
              (let [s (loop [s start] (if (and (pos? s) (#{\space \tab} (.charAt ^String t (dec s)))) (recur (dec s)) s))
                    newlines (apply str (filter #{\newline} (subs t start end)))]
                (str (subs t 0 s) newlines (subs t end))))
            text (reverse spans))))

(defn sorted-files [fs*] (vec (sort-by :path fs*)))

(defn sha256-file [f]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest d (fs/read-all-bytes f))))))

(defn- body-of [text]
  (let [lines (str/split (str text) #"\n" -1)
        {:keys [end]} (gap/frontmatter text)]
    (str/join "\n" (if end (drop (inc end) lines) lines))))

(defn- pdf! [md out-pdf]
  (let [tmp (project/temp-dir "audit-pdf-")]
    (try
      (let [in (str (fs/path tmp "in.md")) typ (str (fs/path tmp "in.typ"))]
        (spit in md)
        (let [r (project/run-cmd ["pandoc" "-s" "-f" "markdown" "-t" "typst" "-V" "mainfont=DejaVu Sans" "-o" typ in] {:dir tmp :seconds 120})]
          (when (:not-found? r) (project/cannot-run! "pandoc is not installed: the audit copy's PDFs cannot be written"))
          (when-not (zero? (:exit r)) (project/cannot-run! (str "pandoc cannot write " out-pdf ": " (project/one-line (:err r))))))
        (spit typ (str "#set document(date: none)\n" (slurp typ)))
        (let [r (project/run-cmd ["typst" "compile" typ (str out-pdf)] {:dir tmp :seconds 120})]
          (when (:not-found? r) (project/cannot-run! "typst is not installed: the audit copy's PDFs cannot be written"))
          (when-not (zero? (:exit r)) (project/cannot-run! (str "typst cannot write " out-pdf ": " (project/one-line (:err r)))))))
      (finally (fs/delete-tree tmp)))))

(defn- pdf-source [d text]
  (let [{fm :frontmatter} (gap/frontmatter text)
        title (str/trim (str (get fm "title" (:title d))))]
    (str "# " title "\n\n"
         "Document " (get fm "id" (:id d)) ", revision " (get fm "revision" (:revision d)) "\n\n"
         (body-of text) "\n")))

(defn uncommitted
  "The paths among those given that differ from HEAD's tree, untracked and ignored files included, relative to the project."
  [root paths]
  (let [pre (cut/prefix root)
        r (apply project/git root "status" "--porcelain" "--ignored" "-uall" "--" (filter #(fs/exists? (fs/path root %)) paths))]
    (vec (sort (distinct (for [l (str/split-lines (:out r)) :when (> (count l) 3)
                               :let [p (subs l 3) p (if (str/includes? p " -> ") (second (str/split p #" -> ")) p)
                                     p (str/replace p #"^\"|\"$" "")]]
                           (if (str/starts-with? p pre) (subs p (count pre)) p)))))))

(defn audit [run]
  (let [root (:root run) cfg (:cfg run)]
    (when-not (contains? cfg :regulated)
      (project/cannot-run! "the configuration holds no :regulated: there is no audit copy without the regulated profile"))
    (let [out-arg (first (:args run))
          out (str (fs/absolutize (fs/path root out-arg)))]
      (when-let [p (out-problem out)] (project/cannot-run! (str out-arg " already exists: an audit copy is never written into an existing directory")))
      (when-not (cut/repo? root) (project/cannot-run! "the project is no git repository: an audit copy is of one commit"))
      (let [ddir (get-in cfg [:regulated :documents] "dhf/documents")
            {:keys [files]} (gap/read-clauses root cfg)
            made-of (concat [project/config-path (:spec-dir cfg) ddir] files)
            dirty (uncommitted root made-of)]
        (if (seq dirty)
          {:exit 1 :findings (vec (for [p dirty] (project/finding p "audit-uncommitted" (str p " is not committed: an audit copy is of one commit, HEAD's"))))
           :result {:audit {:written false}}}
          (let [rel (release/release-check run)]
            (if (seq (:findings rel))
              {:exit 1 :findings (:findings rel) :result {:audit {:written false}}}
              (let [{:keys [statuses scenarios reading dir]} (:derived rel)
                    {:keys [clauses]} (gap/index run)
                    docs (gap/documents root ddir)
                    commit (str/trim (project/git! root "rev-parse" "HEAD"))]
                (fs/create-dirs out)
                (doseq [d docs
                        :let [eng (engineer (:text d))
                              base (str/replace (:rel d) #"\.md$" "")]]
                  (doseq [[who text] [["auditor" (:text d)] ["engineer" eng]]]
                    (let [f (fs/path out who (:rel d))]
                      (fs/create-dirs (fs/parent f))
                      (spit (str f) text)
                      (pdf! (pdf-source d text) (fs/path out who (str base ".pdf"))))))
                (spit (str (fs/path out "clauses.md"))
                      (str "# Clauses\n\nEvery declared clause list, its includes in place, and where each clause is met.\n"
                           (str/join (for [l (get-in cfg [:regulated :clause-lists])]
                                       (str "\n## " l "\n\n| Clause | Description | Named at |\n|---|---|---|\n"
                                            (str/join (for [c clauses :when (= l (:declared c))]
                                                        (str "| " (:key c) " | " (:description c) " | "
                                                             (str/join ", " (map #(str (:file %) ":" (:line %)) (:named-at c))) " |\n"))))))))
                (spit (str (fs/path out "traceability.md"))
                      (str "# Traceability\n\nEach user need, the scenarios that trace to it and their status.\n"
                           (str/join (for [spec (:specs reading) n (sort-by :id (:needs spec))]
                                       (str "\n## " (:id n) "\n\n" (:text n) "\n\n"
                                            (str/join (for [sc (:scenarios spec) :when (and (:id sc) (some #{(:id n)} (:needs sc)))]
                                                        (str "- @" (:id sc) " " (:title sc) " (" (:component-name sc) "): " (some-> (statuses (:id sc)) name) "\n"))))))))
                (let [rdir (fs/path root dir)]
                  (fs/create-dirs (fs/path out "results"))
                  (doseq [f (sort (map str (fs/list-dir rdir))) :when (fs/regular-file? f)]
                    (fs/copy f (fs/path out "results" (fs/file-name f)))))
                (let [all (sorted-files (for [f (fs/glob out "**") :when (fs/regular-file? f)]
                                          {:path (str (fs/relativize out f)) :sha256 (sha256-file f)}))]
                  (spit (str (fs/path out "manifest.edn")) (project/pprint-str {:commit commit :files all})))
                {:result {:audit {:out out-arg :commit commit :documents (count docs) :clauses (count clauses)}}}))))))))
