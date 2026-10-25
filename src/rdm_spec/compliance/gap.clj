(ns rdm-spec.compliance.gap
  "Gap analysis, the compliance context's first component
  (dhf/spec/compliance/gap.feature; dhf/spec/glossary.md, \"The regulated
  profile\"): every clause of every declared clause list is named by an
  audit tag in a controlled document. A stage of `rdm-spec check`, run only
  when the configuration holds :regulated, {:clause-lists [path ..]
  :documents dir}; release, audit and checklist run it quietly and read what
  it publishes, ClausesIndexed.

  It reads the clause lists and the documents in the working tree, or at
  the commit the run's SpecParsed names (the checklist reads a cycle's cut).

  Clause lists: one line each, `KEY description`, `include FILE` (relative
  to the including file), a comment starting `#`, or blank. A key is one run
  of letters, digits and `. _ : -` holding at least one digit or colon. Any
  other line is a finding (clause-line-malformed), and so is an include that
  leads back to a list already being read (clause-include-cycle). A key
  listed twice is one clause, the first. A declared list or an include that
  cannot be read, and a declared list that holds no clause, stop the task
  (exit 2).

  Controlled documents: every Markdown file under the documents directory,
  with frontmatter `id`, `revision` and `title` (document-unidentified when
  one is missing; document-id-duplicate, at each file, when two share an
  id). An audit tag is `[[ ... ]]` outside code (a fenced block, an indented
  block that is not a list item's continuation, an inline code span), never
  running past a blank line. It names a clause when it holds the clause's
  key, or a dotted descendant of it (so every ancestor of the key it holds),
  bounded by characters that are not key characters, a `: note` after the
  key allowed. A tag in the frontmatter names nothing.

  Findings: clause-unreferenced (a gap, at the clause's list and line),
  and the ones above; warnings: clause-unknown (a tag that is one key no
  clause list holds) and document-placeholder (a line holding TODO).

  `strip-tags` is the engineer's copy of a document's text, used by the
  audit copy."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Reading: the working tree or a commit

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn reader
  "{:list (fn [dir] [rel]) :read (fn [rel] text-or-nil)} of the working
  tree under `root`, or of the commit `commit`."
  [root commit]
  (if commit
    {:list (fn [dir]
             (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str (strip-slash dir) "/"))]
               (if (zero? (:exit r)) (vec (sort (remove str/blank? (str/split (:out r) #"\u0000")))) [])))
     :read (fn [rel]
             (let [r (git root "show" (str commit ":./" rel))]
               (when (zero? (:exit r)) (:out r))))}
    {:list (fn [dir]
             (let [d (command/path root dir)]
               (if (fs/directory? d)
                 (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
                 [])))
     :read (fn [rel]
             (let [f (command/path root rel)]
               (when (fs/regular-file? f) (try (slurp (str f)) (catch Exception _ nil)))))}))

;; ---------------------------------------------------------------------------
;; Clause lists

(def key-chars "[A-Za-z0-9._:\\-]")

(defn key?
  "Whether `s` is a clause key: key characters only, at least one digit or colon."
  [s]
  (boolean (and (string? s) (re-matches (re-pattern (str key-chars "+")) s) (re-find #"[0-9:]" s))))

(defn classify
  "One line of a clause list: {:kind :blank | :comment | :include | :clause | :malformed}."
  [line]
  (let [t (str/trim line)]
    (cond
      (str/blank? t) {:kind :blank}
      (str/starts-with? t "#") {:kind :comment}
      :else
      (if-let [[_ f] (re-matches #"include\s+(\S.*)" t)]
        {:kind :include :file (str/trim f)}
        (let [[_ k d] (re-matches #"(\S+)\s+(\S.*)" t)]
          (if (key? k)
            {:kind :clause :key k :description (str/trim d)}
            {:kind :malformed}))))))

(defn- resolve-include [from target]
  (let [parent (some-> (fs/parent from) str)
        joined (if (or (nil? parent) (str/starts-with? target "/")) target (str parent "/" target))]
    (str/replace (str (fs/normalize joined)) "\\" "/")))

(defn- keys-of
  "Every clause key the list `file` holds, includes followed (cycles cut)."
  ([read-text file] (keys-of read-text file #{}))
  ([read-text file stack]
   (if (stack file)
     []
     (let [text (or (read-text file) "")]
       (vec (mapcat (fn [line]
                      (let [c (classify line)]
                        (case (:kind c)
                          :clause [(:key c)]
                          :include (keys-of read-text (resolve-include file (:file c)) (conj stack file))
                          [])))
                    (str/split-lines text)))))))

(defn list-files
  "Every clause list file the declared `lists` reach, includes followed
  (cycles cut), in reading order, each once."
  [read-text lists]
  (letfn [(walk [file stack]
            (when-not (some #{file} stack)
              (cons file (mapcat (fn [line]
                                   (let [c (classify line)]
                                     (when (= :include (:kind c))
                                       (walk (resolve-include file (:file c)) (conj stack file)))))
                                 (str/split-lines (or (read-text file) ""))))))]
    (vec (distinct (mapcat #(walk % []) lists)))))

(defn index-lists
  "{:clauses [clause] :findings [finding]} of the declared clause lists
  `lists`, read with `read-text`; cannot run when a list or an include
  cannot be read, or a declared list holds no clause."
  [read-text lists]
  (let [clauses (atom [])
        seen (atom #{})
        findings (atom [])]
    (letfn [(visit [file stack declared]
              (let [text (read-text file)
                    _ (when (nil? text)
                        (command/cannot-run! (if (seq stack)
                                               (str "the clause list " (peek stack) " includes " file ", which cannot be read")
                                               (str "the declared clause list " file " cannot be read"))))
                    stack (conj stack file)]
                (doseq [[i line] (map-indexed vector (str/split-lines text))
                        :let [n (inc i) c (classify line)]]
                  (case (:kind c)
                    (:blank :comment) nil
                    :malformed
                    (swap! findings conj
                           (command/finding file n "clause-line-malformed"
                                            (str "the line " (pr-str (str/trim line)) " is no clause (KEY description, the key"
                                                 " holding a digit or a colon), include FILE, comment or blank line")))
                    :include
                    (let [target (resolve-include file (:file c))]
                      (if (some #{target} stack)
                        (swap! findings conj
                               (command/finding file n "clause-include-cycle"
                                                (str "include " (:file c) " leads back to " target
                                                     ", a clause list already being read")))
                        (visit target stack declared)))
                    :clause
                    (when-not (@seen (:key c))
                      (swap! seen conj (:key c))
                      (swap! clauses conj {:key (:key c) :description (:description c) :clause-list file
                                           :line n :declared declared}))))))]
      (doseq [l lists]
        (visit l [] l)
        (when (empty? (keys-of read-text l))
          (command/cannot-run! (str "the clause list " l " holds no clause: a declared clause list holds at least one")))))
    {:clauses @clauses :findings @findings}))


;; ---------------------------------------------------------------------------
;; Documents: frontmatter, code and audit tags

(defn- split-lines* [text] (str/split (str text) #"\n" -1))

(defn frontmatter
  "{:end k :fields {name value} :lines {name line}} of the frontmatter of
  `lines` (0-based index k of its closing ---), or nil when there is none."
  [lines]
  (when (and (seq lines) (= "---" (str/trimr (first lines))))
    (when-let [k (some (fn [i] (when (#{"---" "..."} (str/trimr (nth lines i))) i)) (range 1 (count lines)))]
      (reduce (fn [m i]
                (if-let [[_ nm v] (re-matches #"([A-Za-z_][\w-]*)\s*:\s*(.*?)\s*" (nth lines i))]
                  (let [v (if-let [[_ q] (or (re-matches #"\"(.*)\"" v) (re-matches #"'(.*)'" v))] q v)]
                    (-> m (assoc-in [:fields nm] v) (assoc-in [:lines nm] (inc i))))
                  m))
              {:end k :fields {} :lines {}}
              (range 1 k)))))

(def ^:private fence-re #"^ {0,3}(`{3,}|~{3,})(.*)$")
(def ^:private list-item-re #"^ {0,3}(?:[-*+]|\d{1,9}[.)])(?:\s|$).*")

(defn- indented? [l] (boolean (re-find #"^(?: {4}|\t)" l)))

(defn code-lines
  "The set of indices of `lines` from `from` on that are code: a fenced
  block (fences included), or an indented block (four spaces or a tab after
  a blank line) that is not a list item's continuation."
  [lines from]
  (loop [i from fence nil prev-blank? true prev-code? false in-list? false code #{}]
    (if (>= i (count lines))
      code
      (let [l (nth lines i)
            blank? (str/blank? l)]
        (cond
          fence
          (let [[_ run] (re-matches fence-re l)
                closes? (and run (= (first run) (first fence)) (>= (count run) (count fence))
                             (str/blank? (subs l (+ (str/index-of l run) (count run)))))]
            (recur (inc i) (when-not closes? fence) false true in-list? (conj code i)))

          (re-matches fence-re l)
          (let [[_ run info] (re-matches fence-re l)]
            (if (and (= \` (first run)) (str/includes? info "`"))
              (recur (inc i) nil false false in-list? code)
              (recur (inc i) run false true in-list? (conj code i))))

          blank? (recur (inc i) nil true prev-code? in-list? code)

          (and (indented? l) (or prev-blank? prev-code?) (not in-list?))
          (recur (inc i) nil false true false (conj code i))

          (re-matches list-item-re l) (recur (inc i) nil false false true code)

          (indented? l) (recur (inc i) nil false false in-list? code)

          :else (recur (inc i) nil false false (if prev-blank? false in-list?) code))))))

(defn- scan
  "The audit tags in the paragraph text `s`, outside inline code spans:
  [{:start :end :text}] (offsets in `s`)."
  [s]
  (let [n (count s)
        run-at (fn [i] (loop [j i] (if (and (< j n) (= \` (.charAt ^String s j))) (recur (inc j)) (- j i))))]
    (loop [i 0 out []]
      (cond
        (>= i n) out

        (= \` (.charAt ^String s i))
        (let [m (run-at i)
              close (loop [j (+ i m)]
                      (cond (>= j n) nil
                            (= \` (.charAt ^String s j)) (let [r (run-at j)] (if (= r m) j (recur (+ j r))))
                            :else (recur (inc j))))]
          (recur (if close (+ close m) (+ i m)) out))

        (str/starts-with? (subs s i (min n (+ i 2))) "[[")
        (if-let [j (str/index-of s "]]" (+ i 2))]
          (recur (+ j 2) (conj out {:start i :end (+ j 2) :text (subs s (+ i 2) j)}))
          (recur (+ i 2) out))

        :else (recur (inc i) out)))))

(defn tag-spans
  "Every audit tag of the document text `text`: [{:start :end :line :text
  :frontmatter?}], offsets in `text`, lines from 1."
  [text]
  (let [lines (split-lines* text)
        offsets (vec (reductions + 0 (map #(inc (count %)) lines)))
        fm (frontmatter lines)
        body-from (if fm (inc (:end fm)) 0)
        code (code-lines lines body-from)
        line-of (fn [off] (inc (or (last (filter #(<= (nth offsets %) off) (range (count lines)))) 0)))
        ;; paragraphs: [first-index last-index frontmatter?]
        paras (concat
               (when fm (for [i (range 1 (:end fm))] [i i true]))
               (->> (range body-from (count lines))
                    (partition-by #(boolean (or (code %) (str/blank? (nth lines %)))))
                    (remove #(let [i (first %)] (or (code i) (str/blank? (nth lines i)))))
                    (map (fn [is] [(first is) (last is) false]))))]
    (vec (for [[a b fm?] paras
               :let [base (nth offsets a)
                     s (subs text base (+ (nth offsets b) (count (nth lines b))))]
               t (scan s)]
           (assoc t :start (+ base (:start t)) :end (+ base (:end t)) :line (line-of (+ base (:start t)))
                  :frontmatter? fm?)))))

(defn strip-tags
  "The engineer's copy of `text`: every audit tag removed with the space
  before it, its line breaks kept, so each line keeps its number."
  [text]
  (reduce (fn [t {:keys [start end]}]
            (let [ws (loop [i start] (if (and (pos? i) (#{\space \tab} (.charAt ^String t (dec i)))) (recur (dec i)) i))]
              (str (subs t 0 ws) (apply str (filter #{\newline} (subs t start end))) (subs t end))))
          text
          (reverse (tag-spans text))))

(defn- trim-key [tok] (str/replace tok #"[.:_\-]+$" ""))

(defn candidates
  "The keys a token names: itself and every dotted ancestor."
  [tok]
  (take-while some? (iterate #(when-let [i (str/last-index-of % ".")] (when (pos? i) (subs % 0 i))) tok)))

(defn named-keys
  "Every key of the set `clause-keys` the tag text `text` names."
  [clause-keys text]
  (set (for [tok (re-seq (re-pattern (str key-chars "+")) text)
             :let [k (trim-key tok)]
             :when (key? k)
             c (candidates k)
             :when (clause-keys c)]
         c)))

(defn one-key
  "The key the tag text is, when it is one key (a `: note` after it
  allowed), else nil."
  [text]
  (let [k (trim-key (str/trim (first (str/split text #":\s" 2))))]
    (when (key? k) k)))

(defn read-document
  "One controlled document: {:file :id :revision :title :frontmatter :tags
  [{:line :text}] :todo [line]}."
  [file text]
  (let [lines (split-lines* text)
        fm (frontmatter lines)
        body-from (if fm (inc (:end fm)) 0)
        f (:fields fm)]
    {:file file :id (get f "id") :revision (get f "revision") :title (get f "title")
     :frontmatter fm
     :tags (vec (for [t (tag-spans text) :when (not (:frontmatter? t))] (select-keys t [:line :text])))
     :todo (vec (for [i (range body-from (count lines)) :when (re-find #"\bTODO\b" (nth lines i))] (inc i)))}))

(defn document-findings
  "The findings about the documents' identity."
  [docs]
  (vec
   (concat
    (for [d docs
          :let [missing (filter #(str/blank? (get d %)) [:id :revision :title])]
          :when (seq missing)]
      (command/finding (:file d) (when (:frontmatter d) 1) "document-unidentified"
                       (str "the controlled document has no " (str/join ", no " (map name missing))
                            " in its frontmatter: a controlled document carries id, revision and title")))
    (for [[id ds] (group-by :id (remove #(str/blank? (:id %)) docs))
          :when (> (count ds) 1)
          d ds]
      (command/finding (:file d) (get-in d [:frontmatter :lines "id"]) "document-id-duplicate"
                       (str "the id " id " is carried by " (str/join " and " (map :file ds))
                            ": a controlled document's id is unique"))))))

;; ---------------------------------------------------------------------------
;; The index

(defn regulated
  "The configuration's :regulated, checked: {:clause-lists [..] :documents dir}."
  [req]
  (let [r (get-in req [:config :regulated])]
    (when-not (and (map? r) (sequential? (:clause-lists r)) (seq (:clause-lists r))
                   (every? string? (:clause-lists r)) (string? (:documents r)))
      (command/cannot-run! (str "the configuration's :regulated is " (pr-str r)
                                ": it is {:clause-lists [\"dhf/clauses/std.txt\" ...] :documents \"dhf/documents\"}")))
    r))

(defn subscriptions
  "Keeps the revision SpecParsed read, so the clauses are read at it too."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::revision (:revision payload)) nil)]])

(defn index
  "The clause lists and documents of the project, read and checked:
  ClausesIndexed's payload, its findings carrying :severity."
  [req]
  (let [{:keys [clause-lists documents]} (regulated req)
        rev (some-> (:state req) deref ::revision)
        commit (when (string? rev) rev)
        {list-files :list read-text :read} (reader (:root req) commit)
        docs-dir (strip-slash documents)
        _ (when (and (nil? commit) (not (fs/directory? (command/path req docs-dir))))
            (command/cannot-run! (str "the documents directory " docs-dir " that :regulated names does not exist")))
        {:keys [clauses findings]} (index-lists read-text (mapv strip-slash clause-lists))
        docs (vec (for [f (list-files docs-dir) :when (str/ends-with? f ".md")
                        :let [t (read-text f)] :when t]
                    (read-document f t)))
        ks (set (map :key clauses))
        named (reduce (fn [m [k at]] (update m k (fnil conj []) at))
                      {}
                      (for [d docs t (:tags d) k (sort (named-keys ks (:text t)))]
                        [k {:file (:file d) :line (:line t)}]))
        clauses (mapv #(assoc % :named-at (get named (:key %) [])) clauses)
        gaps (for [c clauses :when (empty? (:named-at c))]
               (command/finding (:clause-list c) (:line c) "clause-unreferenced"
                                (str (:key c) " " (:description c) ": no audit tag in a controlled document under "
                                     docs-dir " names it")))
        unknown (for [d docs t (:tags d)
                      :let [k (one-key (:text t))]
                      :when (and k (empty? (named-keys ks (:text t))))]
                  (command/warning (:file d) (:line t) "clause-unknown"
                                   (str "the audit tag [[" (str/trim (:text t)) "]] names " k ", which no declared clause list holds")))
        todos (for [d docs l (:todo d)]
                (command/warning (:file d) l "document-placeholder"
                                 "the document still holds TODO: a placeholder meets no clause"))]
    {:revision (or commit :working-tree)
     :clause-lists (mapv strip-slash clause-lists)
     :clauses clauses
     :documents (mapv #(select-keys % [:file :id :revision :title]) docs)
     :findings (vec (concat findings gaps (document-findings docs) unknown todos))}))

(defn gap
  "The gap stage: indexes the clauses, publishes ClausesIndexed, and
  reports every finding."
  [req]
  (let [idx (index req)]
    (command/publish! req :compliance/gap :ClausesIndexed (select-keys idx [:revision :clauses :documents :findings]))
    {:result {:gap {:clause-lists (:clause-lists idx)
                    :clauses (mapv :key (:clauses idx))
                    :gaps (vec (for [c (:clauses idx) :when (empty? (:named-at c))] (:key c)))
                    :documents (mapv :file (:documents idx))}}
     :findings (:findings idx)}))
