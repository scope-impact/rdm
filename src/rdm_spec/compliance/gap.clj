(ns rdm-spec.compliance.gap
  "Gap analysis (dhf/spec/compliance/gap.feature): under the regulated
  profile (a configuration holding :regulated {:clause-lists [..] :documents
  dir}), every clause of every declared clause list must be named by an
  audit tag in a controlled document. A stage of `rdm-spec check`, and run
  quietly before the release check and the checklist, which read what it
  publishes: ClausesIndexed (dhf/spec/events.edn).

  Clause lists (glossary \"Clause list\"): lines `KEY description`, `include
  FILE` (relative to the including file), `# comment` or blank. Any other
  line is a finding (clause-list-line), and so is an include leading back to
  a list already being read (clause-list-include-cycle). A declared list
  that holds no clause, or an include that names no readable file, stops the
  task (exit 2). A key listed twice is one clause, where it was first
  written.

  Controlled documents (glossary \"Controlled document\"): the Markdown files
  under the documents directory, with frontmatter id, revision and title
  (document-unidentified), the id unique (document-id-duplicate).

  Audit tags (glossary \"Audit tag\"): `[[ ... ]]` outside code (a fenced
  block, an indented block that is not a list item's continuation, an inline
  code span) and outside the frontmatter, never past a blank line. A tag
  names a clause when its text holds the key, or a dotted descendant of it,
  as a whole run of key characters (a `: note` after the key allowed).

  Findings: clause-unreferenced (a gap, at the clause's list and line),
  document-unidentified, document-id-duplicate, clause-list-line,
  clause-list-include-cycle. Warnings: clause-unknown (a tag that is one key
  no clause list holds), document-placeholder (a line holding TODO).

  The scanner (`scan`) is also the audit copy's: it finds the tags the
  engineer's copy removes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Keys

(def key-char-re #"[A-Za-z0-9._:\-]")

(defn key-char? [c] (boolean (and c (re-matches key-char-re (str c)))))

(def key-re #"[A-Za-z0-9._:\-]+")

;; ---------------------------------------------------------------------------
;; Clause lists: pure parsing of one list's text

(defn parse-clause-line
  "Pure: one line of a clause list: {:kind :blank|:comment|:include|:clause
  |:malformed ...}."
  [line]
  (let [t (str/trim line)]
    (cond
      (str/blank? t) {:kind :blank}
      (str/starts-with? t "#") {:kind :comment}
      :else
      (if-let [[_ f] (re-matches #"include\s+(\S.*?)" t)]
        {:kind :include :file f}
        (if-let [[_ k d] (re-matches #"([A-Za-z0-9._:\-]+)\s+(\S.*?)" t)]
          {:kind :clause :key k :description d}
          {:kind :malformed})))))

(defn- normalize [rel] (str/replace (str (fs/normalize rel)) "\\" "/"))

(defn resolve-include
  "Pure: the path of `file` included from the list `from` (both relative to
  the project), relative to the including file's directory."
  [from file]
  (if (fs/absolute? file)
    file
    (let [parent (fs/parent from)]
      (normalize (if parent (str parent "/" file) file)))))

(defn read-lists
  "Reads the declared clause lists through `read-file` (rel -> text or nil):
  {:lists [{:declared rel :entries [{:key :description :clause-list :line}]}]
  :findings [..] :files [every list file read]}. Cannot run when a declared
  list or an include cannot be read, or a declared list holds no clause."
  [declared read-file]
  (let [findings (atom [])
        files (atom [])
        walk (fn walk [rel stack]
               (let [text (read-file rel)]
                 (when (nil? text)
                   (command/cannot-run! (if (empty? stack)
                                          (str "the declared clause list " rel " cannot be read")
                                          (str "the clause list " (first stack) " includes " rel
                                               ", which names no readable file"))))
                 (swap! files conj rel)
                 (vec (mapcat
                       (fn [i line]
                         (let [n (inc i)
                               {:keys [kind] :as l} (parse-clause-line line)]
                           (case kind
                             (:blank :comment) []
                             :clause [{:key (:key l) :description (:description l) :clause-list rel :line n}]
                             :include (let [target (resolve-include rel (:file l))]
                                        (if (some #{target} (cons rel stack))
                                          (do (swap! findings conj
                                                     (command/finding rel n "clause-list-include-cycle"
                                                                      (str "include " (:file l) " leads back to " target
                                                                           ", a clause list already being read")))
                                              [])
                                          (walk target (cons rel stack))))
                             :malformed (do (swap! findings conj
                                                   (command/finding rel n "clause-list-line"
                                                                    (str "the line " (pr-str line) " is not a clause (KEY description),"
                                                                         " an include (include FILE), a comment (#) or blank")))
                                            []))))
                       (range) (str/split-lines text)))))
        lists (vec (for [d declared]
                     (let [entries (walk d ())]
                       (when (empty? entries)
                         (command/cannot-run! (str "the clause list " d " holds no clause")))
                       {:declared d :entries entries})))]
    {:lists lists :findings @findings :files (vec (distinct @files))}))

(defn index-clauses
  "Pure: every clause once, in the lists' order with includes in place, where
  it was first written: [{:key :description :clause-list :line :declared}]."
  [lists]
  (first (reduce (fn [[acc seen] {:keys [declared entries]}]
                   (reduce (fn [[acc seen] e]
                             (if (seen (:key e))
                               [acc seen]
                               [(conj acc (assoc e :declared declared)) (conj seen (:key e))]))
                           [acc seen] entries))
                 [[] #{}] lists)))

;; ---------------------------------------------------------------------------
;; Documents: frontmatter, code and audit tags

(defn frontmatter-end
  "Pure: the index of the line closing the frontmatter that opens `lines`, or
  nil when there is none."
  [lines]
  (when (and (seq lines) (= "---" (str/trimr (first lines))))
    (first (keep-indexed (fn [i l] (when (and (pos? i) (#{"---" "..."} (str/trimr l))) i)) lines))))

(defn frontmatter
  "Pure: the frontmatter's fields {\"id\" \"PLAN-001\" ...} of the text."
  [text]
  (let [lines (str/split-lines (str text))]
    (if-let [end (frontmatter-end lines)]
      (into {} (for [l (subvec (vec lines) 1 end)
                     :let [[_ k v] (re-matches #"([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*?)\s*" l)]
                     :when k]
                 [k (let [v (str v)]
                      (if (and (>= (count v) 2) (#{\" \'} (first v)) (= (first v) (last v)))
                        (subs v 1 (dec (count v)))
                        v))]))
      {})))

(def ^:private list-item-re #"^ {0,3}(?:[-*+]|\d{1,9}[.)])(?:[ \t]|$).*")

(defn- indented? [l] (boolean (re-find #"^(?: {4}|\t)" l)))

(defn- fence-open [l] (second (re-find #"^ {0,3}(`{3,}|~{3,})" l)))

(defn line-kinds
  "Pure: the kind of each line of `lines`: :front (frontmatter), :code (a
  fenced or indented code block), :blank or :text. An indented line after a
  blank is code unless it continues a list item."
  [lines]
  (let [lines (vec lines)
        fm (frontmatter-end lines)]
    (loop [i 0 kinds [] fence nil in-list? false prev :start]
      (if (>= i (count lines))
        kinds
        (let [l (nth lines i)
              blank? (str/blank? l)]
          (cond
            (and fm (<= i fm))
            (recur (inc i) (conj kinds :front) nil false :start)

            fence
            (let [closes? (re-matches (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote (subs fence 0 1))
                                                       "{" (count fence) ",}\\s*$")) l)]
              (recur (inc i) (conj kinds :code) (when-not closes? fence) in-list? (if closes? :text :code)))

            blank?
            (recur (inc i) (conj kinds :blank) nil in-list? (if (= prev :code) :code-blank :blank))

            (and (indented? l) (not in-list?) (#{:start :blank :code :code-blank} prev))
            (recur (inc i) (conj kinds :code) nil in-list? :code)

            (fence-open l)
            (recur (inc i) (conj kinds :code) (fence-open l) false :code)

            (re-matches list-item-re l)
            (recur (inc i) (conj kinds :text) nil true :text)

            (and in-list? (or (indented? l) (= prev :text) (re-find #"^\s" l)))
            (recur (inc i) (conj kinds :text) nil true :text)

            :else
            (recur (inc i) (conj kinds :text) nil false :text)))))))

(defn- code-spans
  "Pure: [[start end) ...] of the inline code spans of `s`."
  [s]
  (let [n (count s)]
    (loop [i 0 spans []]
      (if (>= i n)
        spans
        (if (= \` (.charAt ^String s i))
          (let [j (loop [j i] (if (and (< j n) (= \` (.charAt ^String s j))) (recur (inc j)) j))
                run (- j i)
                close (loop [k j]
                        (when (< k n)
                          (if (= \` (.charAt ^String s k))
                            (let [e (loop [e k] (if (and (< e n) (= \` (.charAt ^String s e))) (recur (inc e)) e))]
                              (if (= run (- e k)) e (recur e)))
                            (recur (inc k)))))]
            (if close
              (recur close (conj spans [i close]))
              (recur j spans)))
          (recur (inc i) spans))))))

(defn- in-spans? [spans i] (some (fn [[a b]] (and (<= a i) (< i b))) spans))

(defn- find-outside [s from needle spans]
  (loop [from from]
    (let [i (str/index-of s needle from)]
      (cond (nil? i) nil
            (or (in-spans? spans i) (in-spans? spans (inc i))) (recur (inc i))
            :else i))))

(defn- region-tags
  "Pure: the tags of one region (consecutive lines, joined by newlines,
  starting at the 0-based line `first-line` and character offset `offset`):
  [{:start :end :text :line}] with offsets into the whole text."
  [s first-line offset]
  (let [spans (code-spans s)]
    (loop [from 0 tags []]
      (let [o (find-outside s from "[[" spans)
            c (when o (find-outside s (+ o 2) "]]" spans))]
        (if (and o c)
          (recur (+ c 2)
                 (conj tags {:start (+ offset o) :end (+ offset c 2)
                             :text (subs s (+ o 2) c)
                             :line (+ 1 first-line (count (filter #{\newline} (subs s 0 o))))}))
          tags)))))

(defn scan
  "Pure: every audit tag of a document's text, outside code and never past a
  blank line: [{:start :end :text :line :front?}], :start and :end offsets
  into `text` (the tag with its brackets), :line 1-based, :front? when the
  tag is in the frontmatter (where it names nothing)."
  [text]
  (let [text (str text)
        lines (str/split text #"\n" -1)
        kinds (line-kinds lines)
        starts (vec (reductions (fn [o l] (+ o (count l) 1)) 0 lines))
        ;; regions: runs of :text lines, and each :front line alone
        regions (loop [i 0 cur nil out []]
                  (if (>= i (count lines))
                    (cond-> out cur (conj cur))
                    (let [k (nth kinds i nil)]
                      (cond
                        (= :front k) (recur (inc i) nil (cond-> out cur (conj cur) true (conj {:from i :to i :front? true})))
                        (= :text k) (recur (inc i) (if cur (assoc cur :to i) {:from i :to i}) out)
                        :else (recur (inc i) nil (cond-> out cur (conj cur)))))))]
    (vec (for [{:keys [from to front?]} regions
               t (region-tags (str/join "\n" (subvec lines from (inc to))) from (nth starts from))]
           (assoc t :front? (boolean front?))))))

(defn todo-lines
  "Pure: the 1-based numbers of the lines outside code holding TODO."
  [text]
  (let [lines (str/split (str text) #"\n" -1)
        kinds (line-kinds lines)]
    (vec (for [[i l] (map-indexed vector lines)
               :when (and (not= :code (nth kinds i nil)) (re-find #"\bTODO\b" l))]
           (inc i)))))

(defn key-runs
  "Pure: the runs of key characters of a tag's text, a trailing `:` (before a
  note) dropped."
  [s]
  (vec (for [r (re-seq key-re (str s))
             :let [r (str/replace r #":+$" "")]
             :when (not (str/blank? r))]
         r)))

(defn ancestors-and-self
  "Pure: the run itself and every dotted ancestor of it (\"A:5.1.a\" ->
  \"A:5.1.a\" \"A:5.1\" \"A:5\")."
  [run]
  (vec (cons run (for [i (range (dec (count run)) 0 -1)
                       :when (= \. (.charAt ^String run i))]
                   (subs run 0 i)))))

(defn names
  "Pure: the keys of `keys` (a set) the tag text names."
  [keys text]
  (vec (distinct (for [r (key-runs text) k (ancestors-and-self r) :when (contains? keys k)] k))))

(defn one-key
  "Pure: the key a tag's text is, when it is one key (a `: note` after it
  allowed), else nil."
  [text]
  (let [t (str/trim (str text))]
    (or (when-let [[_ k] (re-matches #"([A-Za-z0-9._:\-]*[A-Za-z0-9._\-])" t)] k)
        (when-let [[_ k] (re-matches #"(?s)([A-Za-z0-9._:\-]*[A-Za-z0-9._\-]):(?:\s.*)?" t)] k))))

(defn strip-tags
  "Pure: `text` without its audit tags (the engineer's copy): each tag is
  removed with the spaces and tabs before it on its line, its line breaks
  kept, so every line keeps its number."
  [text]
  (let [text (str text)]
    (loop [tags (scan text) pos 0 sb (StringBuilder.)]
      (if-let [{:keys [start end]} (first tags)]
        (let [s (loop [s start] (if (and (> s pos) (#{\space \tab} (.charAt text (dec s)))) (recur (dec s)) s))]
          (.append sb (subs text pos s))
          (.append sb (apply str (filter #{\newline} (subs text start end))))
          (recur (rest tags) end sb))
        (str (.append sb (subs text pos)))))))

;; ---------------------------------------------------------------------------
;; Reading: the working tree, or the cut the task names

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn- commit-of [root rev]
  (when (and rev (not (str/blank? (str rev))))
    (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
      (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))))

(defn- cycle-cut
  "The cut a cycle's cycle.edn names (the checklist reads the clauses at it)."
  [req]
  (let [cycles (get-in req [:config :cycles-dir])
        cycle (first (:args req))]
    (when (and cycles (= "checklist" (:task req)) (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
      (let [f (command/path req (str (str/replace cycles #"/+$" "") "/" cycle "/cycle.edn"))]
        (when (fs/regular-file? f)
          (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
            (when (and (map? c) (string? (:cut c))) (:cut c))))))))

(defn reader
  "{:revision :read (rel -> text or nil) :list (dir -> [rel])}: the working
  tree, or the commit `commit`."
  [root commit]
  (if commit
    {:revision commit
     :read (fn [rel] (let [r (git root "show" (str commit ":./" rel))] (when (zero? (:exit r)) (:out r))))
     :list (fn [dir] (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str (str/replace dir #"/+$" "") "/"))]
                       (if (zero? (:exit r)) (vec (sort (remove str/blank? (str/split (:out r) #"\u0000")))) [])))}
    {:revision :working-tree
     :read (fn [rel] (let [f (command/path root rel)] (when (fs/regular-file? f) (slurp (str f)))))
     :list (fn [dir] (let [d (command/path root dir)]
                       (if (fs/directory? d)
                         (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
                         [])))}))

(defn profile
  "The configuration's :regulated, checked: {:clause-lists [rel] :documents
  rel}. Cannot run when it is not that shape."
  [req]
  (let [r (get-in req [:config :regulated])
        lists (:clause-lists r)]
    (when-not (and (map? r) (sequential? lists) (seq lists) (every? string? lists)
                   (string? (:documents r)) (not (str/blank? (:documents r))))
      (command/cannot-run! (str "the configuration " command/config-file " holds :regulated " (pr-str r)
                                ", not {:clause-lists [\"FILE\" ...] :documents \"DIR\"}")))
    {:clause-lists (vec lists) :documents (str/replace (:documents r) #"/+$" "")}))

;; ---------------------------------------------------------------------------
;; The analysis

(defn analyse
  "Pure but for `rd` (a reader): ClausesIndexed's payload, with :files the
  clause list files read."
  [{:keys [clause-lists documents]} rd]
  (let [{:keys [lists findings files]} (read-lists clause-lists (:read rd))
        clauses (index-clauses lists)
        keys (set (map :key clauses))
        docs (vec (for [rel ((:list rd) documents)
                        :when (str/ends-with? (str/lower-case rel) ".md")]
                    (let [text ((:read rd) rel)
                          fm (frontmatter text)]
                      {:file rel :text text :front fm :tags (scan text)})))
        named-at (reduce (fn [m {:keys [file tags]}]
                           (reduce (fn [m t]
                                     (reduce #(update %1 %2 (fnil conj []) {:file file :line (:line t)})
                                             m (names keys (:text t))))
                                   m (remove :front? tags)))
                         {} docs)
        clauses (mapv #(assoc % :named-at (vec (distinct (get named-at (:key %) [])))) clauses)
        unidentified (for [{:keys [file front]} docs
                           :let [missing (filter #(str/blank? (get front %)) ["id" "revision" "title"])]
                           :when (seq missing)]
                       (command/finding file "document-unidentified"
                                        (str "the controlled document has no " (str/join ", no " missing)
                                             " in its frontmatter")))
        duplicates (for [[id ds] (group-by #(get-in % [:front "id"]) docs)
                         :when (and (not (str/blank? id)) (> (count ds) 1))
                         d (rest ds)]
                     (command/finding (:file d) "document-id-duplicate"
                                      (str "the id " id " is also the id of " (:file (first ds))
                                           ": an id names one controlled document")))
        gaps (for [c clauses :when (empty? (:named-at c))]
               (command/finding (:clause-list c) (:line c) "clause-unreferenced"
                                (str (:key c) " " (:description c) ": no audit tag in a controlled document names it")))
        unknown (for [{:keys [file tags]} docs
                      t (remove :front? tags)
                      :let [k (one-key (:text t))]
                      :when (and k (empty? (names keys (:text t))))]
                  (command/warning file (:line t) "clause-unknown"
                                   (str k ": the audit tag names a key no declared clause list holds")))
        placeholders (for [{:keys [file text]} docs
                           n (todo-lines text)]
                       (command/warning file n "document-placeholder"
                                        "the document still holds TODO: a placeholder meets no clause"))]
    {:revision (:revision rd)
     :clauses clauses
     :documents (mapv (fn [{:keys [file front]}]
                        {:file file :id (get front "id") :revision (get front "revision") :title (get front "title")})
                      docs)
     :findings (vec (concat findings gaps unidentified duplicates unknown placeholders))
     :files files}))

(defn index
  "Reads the clause lists and the documents of the request's project, at the
  cut the task names or in the working tree: ClausesIndexed's payload and
  :files."
  [req]
  (let [cut (or (get-in req [:opts :cut]) (cycle-cut req))
        commit (when cut (commit-of (:root req) cut))]
    (analyse (profile req) (reader (:root req) commit))))

(defn gap
  "The gap stage: publishes ClausesIndexed and reports its findings."
  [req]
  (let [payload (index req)
        event (dissoc payload :files)]
    (command/publish! req :compliance/gap :ClausesIndexed event)
    (when-let [st (:state req)] (swap! st assoc ::clauses event))
    {:result {:gap {:clauses (count (:clauses event))
                    :gaps (count (filter #(empty? (:named-at %)) (:clauses event)))
                    :documents (count (:documents event))}}
     :findings (:findings event)}))
