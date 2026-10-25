(ns rdm-spec.compliance.gap
  "Gap analysis (dhf/spec/compliance/gap.feature): under the regulated profile
  (dhf/wiki/pages/regulated-profile.md), every clause of every declared clause
  list is named by an audit tag in a controlled document. A stage of
  `rdm-spec check` (and, quietly, of release, checklist and audit, which read
  its event); it runs only when the configuration holds :regulated, which the
  task table sees to.

  The configuration: {:regulated {:clause-lists [\"dhf/clauses/std.txt\" ..]
                                  :documents \"dhf/documents\"}}.

  What it reads (`source-of`): the working tree, except for the checklist,
  which reads the clause lists at the cycle's cut, and the audit copy, which
  reads them and the documents as committed at HEAD.

  A clause list is lines: `KEY description`, `include FILE` (relative to the
  including file), `# comment`, or blank. A key is a run of letters, digits
  and . _ : -. Each key is one clause, where it is first reached, includes in
  place. Cannot run (exit 2): a declared list or an include that names no
  readable file, a declared list that reaches no clause. A line of another
  shape is a finding (clause-line-malformed).

  An audit tag is [[ .. ]] in a document's body, outside code (fenced blocks,
  indented blocks after a blank line, inline code spans), never running past
  a blank line, and holding no [[ (the [[ nearest its ]] opens it). It
  names a clause when one of its key-shaped words, with
  trailing . : _ - dropped, is the key or a dotted descendant of it.

  Findings (exit 1): clause-unreferenced (a gap, at the clause's line),
  document-unidentified (no frontmatter id, revision or title),
  document-id-duplicate (one per file holding the id), clause-line-malformed.
  Warnings: clause-unknown (a tag that is one key no list holds),
  document-placeholder (a body line holding TODO).

  Publishes ClausesIndexed {:revision :clauses :documents :findings}
  (dhf/spec/events.edn); the result is {:gap {:revision :clause-lists :clauses
  :gaps :documents}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The configuration

(defn regulated
  "The request's :regulated, checked: {:clause-lists [rel] :documents rel}."
  [req]
  (let [r (get-in req [:config :regulated])
        bad #(command/cannot-run! (str "the configuration " command/config-file " has :regulated " (pr-str r)
                                       ": " % "; it is {:clause-lists [\"dhf/clauses/std.txt\" ...] :documents \"dhf/documents\"}"))]
    (cond
      (nil? r) (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated"))
      (not (map? r)) (bad "not a map")
      (not (and (sequential? (:clause-lists r)) (seq (:clause-lists r)) (every? string? (:clause-lists r))))
      (bad "it declares no clause list")
      (not (and (string? (:documents r)) (not (str/blank? (:documents r))))) (bad "it names no documents directory")
      :else {:clause-lists (vec (:clause-lists r)) :documents (:documents r)})))

;; ---------------------------------------------------------------------------
;; Paths: relative to the project, with / between names

(defn normalize
  "Pure: the relative path `rel` with . and .. resolved."
  [rel]
  (str/join "/" (reduce (fn [out part]
                          (case part
                            ("" ".") out
                            ".." (if (and (seq out) (not= ".." (peek out))) (pop out) (conj out part))
                            (conj out part)))
                        [] (str/split (str/replace (str rel) "\\" "/") #"/"))))

(defn dir-of [rel] (let [i (str/last-index-of rel "/")] (if i (subs rel 0 i) "")))

(defn resolve-include
  "Pure: the file an include in `from` names."
  [from file]
  (normalize (if (str/blank? (dir-of from)) file (str (dir-of from) "/" file))))

;; ---------------------------------------------------------------------------
;; Where files are read from: the working tree or a commit

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn commit-of
  "The commit `rev` names in the repository at `root`, or nil."
  [root rev]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn working-tree [root] {:root (str root) :commit nil})

(defn at-commit [root commit] {:root (str root) :commit commit})

(defn read-text
  "The text of the file `rel` in `src`, or nil when it holds none."
  [{:keys [root commit]} rel]
  (if commit
    (let [r (p/shell {:dir root :out :bytes :err :string :continue true :in ""} "git" "show" (str commit ":./" rel))]
      (when (zero? (:exit r)) (String. ^bytes (:out r) "UTF-8")))
    (let [f (fs/file root rel)]
      (when (fs/regular-file? f) (slurp (str f))))))

(defn markdown-files
  "Every .md file under the directory `dir` in `src`, relative to the
  project, sorted."
  [{:keys [root commit]} dir]
  (let [dir (normalize dir)]
    (vec (sort
          (if commit
            (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str dir "/"))]
              (when-not (zero? (:exit r))
                (command/cannot-run! (str "the documents at " commit " cannot be listed: " (str/trim (:err r)))))
              (filter #(str/ends-with? % ".md") (remove str/blank? (str/split (:out r) #"\x00"))))
            (let [d (fs/file root dir)]
              (when (fs/directory? d)
                (for [f (fs/glob d "**.md") :when (fs/regular-file? f)]
                  (str dir "/" (str/replace (str (fs/relativize d f)) "\\" "/"))))))))))

(defn- cycle-cut
  "The cut the cycle `name`'s cycle.edn names, or nil."
  [req name]
  (when (and name (re-matches #"[A-Za-z0-9._-]+" name))
    (let [f (command/path req (str (or (get-in req [:config :cycles-dir]) "dhf/cycles") "/" name "/cycle.edn"))]
      (when (fs/regular-file? f)
        (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (and (map? c) (string? (:cut c)) (not (str/blank? (:cut c))))
            (:cut c)))))))

(defn source-of
  "Where this run reads the clause lists and documents: at HEAD for the audit
  copy (a project with no commit cannot have one), at the cycle's cut for the
  checklist, else the working tree."
  [req]
  (let [root (:root req)]
    (case (:task req)
      "audit" (at-commit root (or (some-> (:state req) deref ::head)
                                  (commit-of root "HEAD")
                                  (command/cannot-run! "the repository has no commit at HEAD: an audit copy is made from what is committed")))
      "checklist" (if-let [c (some->> (cycle-cut req (first (:args req))) (commit-of root))]
                    (at-commit root c)
                    (working-tree root))
      (working-tree root))))

;; ---------------------------------------------------------------------------
;; Clause lists

(def key-chars "A-Za-z0-9._:-")
(def key-re (re-pattern (str "[" key-chars "]+")))

(defn parse-line
  "Pure: one line of a clause list: {:blank} {:comment} {:include file}
  {:clause key :description d} or {:malformed}."
  [line]
  (let [t (str/trim line)]
    (cond
      (str/blank? t) {:blank true}
      (str/starts-with? t "#") {:comment true}
      :else (if-let [[_ f] (re-matches #"include\s+(\S.*)" t)]
              {:include (str/trim f)}
              (if-let [[_ k d] (re-matches (re-pattern (str "([" key-chars "]+)(?:\\s+(.*))?")) t)]
                {:clause k :description (str/trim (or d ""))}
                {:malformed true})))))

(defn read-clauses
  "Reads the declared clause lists through `read` (rel -> text or nil):
  {:clauses [{:key :description :clause-list :line :declared}] :findings}.
  Each key once, where first reached, includes in place."
  [read declared]
  (let [seen-keys (atom #{})
        out (atom [])
        findings (atom [])
        ;; walks one file; returns how many clause lines it reached
        walk (fn walk [rel declared-list stack from]
               (let [text (read rel)]
                 (when (nil? text)
                   (command/cannot-run! (if from
                                          (str (:file from) ":" (:line from) ": the include names " rel
                                               ", which is no readable file")
                                          (str "the clause list " rel " is no readable file"))))
                 (reduce
                  (fn [reached [i line]]
                    (let [n (inc i)
                          parsed (parse-line line)]
                      (cond
                        (:include parsed)
                        (let [target (resolve-include rel (:include parsed))]
                          (if (some #{target} stack)
                            (do (swap! findings conj (command/finding rel n "clause-include-cycle"
                                                                      (str "the include of " target " goes round in a circle")))
                                reached)
                            (+ reached (walk target declared-list (conj stack target) {:file rel :line n}))))

                        (:clause parsed)
                        (do (when-not (@seen-keys (:clause parsed))
                              (swap! seen-keys conj (:clause parsed))
                              (swap! out conj {:key (:clause parsed) :description (:description parsed)
                                               :clause-list rel :line n :declared declared-list}))
                            (inc reached))

                        (:malformed parsed)
                        (do (swap! findings conj (command/finding rel n "clause-line-malformed"
                                                                  (str "the line " (pr-str (str/trim line))
                                                                       " is no clause, include, comment or blank")))
                            reached)

                        :else reached)))
                  0 (map-indexed vector (str/split-lines text)))))]
    (doseq [d (map normalize declared)]
      (when (zero? (walk d d [d] nil))
        (command/cannot-run! (str "the clause list " d " holds no clause"))))
    {:clauses @out :findings @findings}))

;; ---------------------------------------------------------------------------
;; Documents: frontmatter, code, audit tags

(defn- re-seq-pos
  "Pure: [start end] of every match of `re` in `text`."
  [re text]
  (let [m (re-matcher re text)]
    (loop [out []]
      (if (.find m) (recur (conj out [(.start m) (.end m)])) out))))

(defn split-frontmatter
  "Pure: [frontmatter-lines body-lines body-start] of a document's lines;
  frontmatter-lines nil when it has none. body-start is the 1-based line of
  the body's first line."
  [lines]
  (let [lines (vec lines)]
    (if (and (seq lines) (= "---" (str/trimr (first lines))))
      (if-let [end (first (keep-indexed (fn [i l] (when (and (pos? i) (#{"---" "..."} (str/trimr l))) i)) lines))]
        [(subvec lines 1 end) (subvec lines (inc end)) (+ 2 end)]
        [nil lines 1])
      [nil lines 1])))

(defn- unquote-value [v]
  (let [v (str/trim v)]
    (if (and (>= (count v) 2)
             (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                 (and (str/starts-with? v "'") (str/ends-with? v "'"))))
      (subs v 1 (dec (count v)))
      v)))

(defn frontmatter-map
  "Pure: the top-level `key: value` pairs of frontmatter lines, as
  {\"id\" \"PLAN-001\"}."
  [lines]
  (into {} (for [l lines
                 :let [[_ k v] (re-matches #"([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)" l)]
                 :when k]
             [k (unquote-value v)])))

(defn- fence-open [line]
  (when-let [[_ indent marks] (re-matches #"( {0,3})(`{3,}|~{3,}).*" line)]
    (when-not (and (str/starts-with? marks "`") (str/includes? (subs line (+ (count indent) (count marks))) "`"))
      marks)))

(defn- indented? [line] (boolean (re-find #"^(?: {4}|\t)" line)))

(defn code-lines
  "Pure: the set of indexes of `lines` (a body) that are code: inside a
  fenced block (the fences included), or an indented block, which starts
  only after a blank line (or the body's start) and runs over blank lines
  while indented lines follow."
  [lines]
  (let [lines (vec lines)
        n (count lines)]
    (loop [i 0 fence nil prev-blank? true out #{}]
      (if (>= i n)
        out
        (let [l (get lines i)
              blank? (str/blank? l)]
          (cond
            fence
            (let [closes? (re-matches (re-pattern (str " {0,3}" (java.util.regex.Pattern/quote (subs fence 0 1))
                                                       "{" (count fence) ",}\\s*")) l)]
              (recur (inc i) (when-not closes? fence) false (conj out i)))

            (fence-open l)
            (recur (inc i) (fence-open l) false (conj out i))

            (and (indented? l) (not blank?) prev-blank?)
            ;; an indented block: this line and every following indented or
            ;; blank line, while an indented line still follows
            (let [end (loop [j i last i]
                        (cond (>= j n) last
                              (indented? (get lines j)) (recur (inc j) j)
                              (str/blank? (get lines j)) (recur (inc j) last)
                              :else last))]
              (recur (inc end) nil false (into out (range i (inc end)))))

            :else (recur (inc i) nil blank? out)))))))

(defn mask-inline-code
  "Pure: `text` with every inline code span replaced by spaces, newlines
  kept, so positions stay. A span opens with a run of backticks and closes
  with the next run of the same length within its paragraph."
  [text]
  (let [text (str text)
        runs (vec (for [m (re-seq-pos #"`+" text)] m))
        sb (StringBuilder. text)]
    (loop [i 0]
      (when (< i (count runs))
        (let [[s e] (get runs i)
              len (- e s)
              j (first (filter #(let [[s2 e2] (get runs %)]
                                  (and (= len (- e2 s2))
                                       (not (re-find #"\n[ \t]*\n" (subs text e s2)))))
                               (range (inc i) (count runs))))]
          (if j
            (let [[_ e2] (get runs j)]
              (doseq [k (range s e2)]
                (when-not (= \newline (.charAt sb k)) (.setCharAt sb k \space)))
              (recur (inc j)))
            (recur (inc i))))))
    (str sb)))

(defn tag-spans
  "Pure: the audit tags of `text` (prose, code already masked) as [start end]
  character spans, end past the closing ]]: [[ .. ]] never running past a
  blank line."
  [text]
  (let [n (count text)]
    (loop [from 0 out []]
      (let [open (str/index-of text "[[" from)]
        (if (nil? open)
          out
          (let [close (str/index-of text "]]" (+ open 2))
                ;; a tag holds no [[: the one nearest the ]] opens it
                open (if close (max open (str/last-index-of text "[[" (- close 2))) open)
                inner (when close (subs text (+ open 2) close))]
            (if (and close (not (re-find #"\n[ \t]*\n" inner)))
              (recur (min n (+ close 2)) (conj out [open (+ close 2)]))
              (recur (+ open 2) out))))))))

(defn- line-at [text idx] (count (filter #(= \newline %) (subs text 0 idx))))

(defn tag-words
  "Pure: the key-shaped words of a tag's text, trailing . : _ - dropped."
  [inner]
  (vec (keep #(not-empty (str/replace % #"[._:-]+$" "")) (re-seq key-re inner))))

(defn names?
  "Pure: whether the tag word `w` names the clause key `k`: the key, or a
  dotted descendant of it."
  [w k]
  (or (= w k) (str/starts-with? w (str k "."))))

(defn single-key
  "Pure: the one key a tag's text is (a `: note` after it allowed), or nil."
  [inner]
  (let [t (str/trim inner)
        k (str/trim (first (str/split t #":\s" 2)))
        k (str/replace k #"[._:-]+$" "")]
    (when (and (not (str/blank? k)) (re-matches key-re k)) k)))

(defn prose-text
  "Pure: the body `lines` as one text with code blanked out (code lines
  turned to spaces, inline code spans masked), so positions and line numbers stay."
  [lines]
  (let [code (code-lines lines)]
    (mask-inline-code (str/join "\n" (map-indexed (fn [i l] (if (code i) (str/replace l #"." " ") l)) lines)))))

(defn body-tags
  "Pure: the audit tags of a document body: [{:line (1-based in the body)
  :inner text}]."
  [lines]
  (let [text (prose-text lines)]
    (vec (for [[s e] (tag-spans text)]
           {:line (inc (line-at text s)) :inner (subs text (+ s 2) (- e 2))}))))

(defn read-document
  "Pure: a document `rel` holding `text`: {:file :id :revision :title :tags
  [{:line :inner}] :todos [line] :frontmatter? bool}."
  [rel text]
  (let [lines (str/split-lines (str text))
        [front body start] (split-frontmatter lines)
        fm (frontmatter-map front)
        code (code-lines body)]
    {:file rel
     :frontmatter? (some? front)
     :id (not-empty (get fm "id"))
     :revision (not-empty (get fm "revision"))
     :title (not-empty (get fm "title"))
     :tags (vec (for [t (body-tags body)] (update t :line + (dec start))))
     :todos (vec (keep-indexed (fn [i l] (when (and (not (code i)) (re-find #"\bTODO\b" l)) (+ start i))) body))}))

;; ---------------------------------------------------------------------------
;; The analysis

(defn analyse
  "Pure: the clauses (as read-clauses gives them) and the documents (as
  read-document gives them): {:clauses (with :named-at) :findings}."
  [clauses docs]
  (let [keys* (mapv :key clauses)
        named (into {} (for [c clauses]
                         [(:key c) (vec (for [d docs t (:tags d)
                                              :when (some #(names? % (:key c)) (tag-words (:inner t)))]
                                          {:file (:file d) :line (:line t)}))]))
        clauses (mapv #(assoc % :named-at (get named (:key %))) clauses)
        gaps (for [c clauses :when (empty? (:named-at c))]
               (command/finding (:clause-list c) (:line c) "clause-unreferenced"
                                (str (:key c) " " (:description c) ": no audit tag in a controlled document names it"
                                     " (the clause list " (:declared c) ")")))
        unidentified (for [d docs
                           :let [missing (remove #(get d %) [:id :revision :title])]
                           :when (seq missing)]
                       (command/finding (:file d) nil "document-unidentified"
                                        (str "the document's frontmatter has no "
                                             (str/join ", " (map name missing))
                                             ", so it is no controlled document")))
        duplicates (for [[id ds] (sort-by key (group-by :id (filter :id docs)))
                         :when (> (count ds) 1)
                         d ds]
                     (command/finding (:file d) nil "document-id-duplicate"
                                      (str "the id " id " is held by " (count ds) " documents: "
                                           (str/join ", " (map :file ds)))))
        unknown (for [d docs t (:tags d)
                      :let [k (single-key (:inner t))]
                      :when (and k (not-any? #(names? k %) keys*))]
                  (command/warning (:file d) (:line t) "clause-unknown"
                                   (str "the audit tag names " k ", which no declared clause list holds")))
        placeholders (for [d docs l (:todos d)]
                       (command/warning (:file d) l "document-placeholder"
                                        "the document still holds TODO: a placeholder meets no clause"))]
    {:clauses clauses
     :findings (vec (concat gaps unidentified duplicates unknown placeholders))}))

(defn index
  "Reads and analyses the request's clause lists and documents:
  {:revision :clauses :documents :findings :clause-lists :source}."
  [req]
  (let [{:keys [clause-lists documents]} (regulated req)
        src (source-of req)
        {:keys [clauses findings]} (read-clauses #(read-text src %) clause-lists)
        docs (vec (for [rel (markdown-files src documents)]
                    (read-document rel (read-text src rel))))
        out (analyse clauses docs)]
    {:revision (or (:commit src) :working-tree)
     :source src
     :clause-lists (mapv normalize clause-lists)
     :clauses (:clauses out)
     :documents (mapv #(select-keys % [:file :id :revision :title]) docs)
     :findings (into (vec findings) (:findings out))}))

(defn gap
  "The gap stage: indexes the clauses once per run, publishes
  ClausesIndexed, and reports every gap."
  [req]
  (let [ix (or (some-> (:state req) deref ::index)
               (let [ix (index req)]
                 (when-let [st (:state req)] (swap! st assoc ::index ix))
                 (command/publish! req :compliance/gap :ClausesIndexed
                                   (select-keys ix [:revision :clauses :documents :findings]))
                 ix))]
    {:result {:gap {:revision (:revision ix)
                    :clause-lists (:clause-lists ix)
                    :clauses (count (:clauses ix))
                    :gaps (count (filter #(empty? (:named-at %)) (:clauses ix)))
                    :documents (count (:documents ix))}}
     :findings (:findings ix)}))
