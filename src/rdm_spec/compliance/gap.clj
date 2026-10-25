(ns rdm-spec.compliance.gap
  "Gap analysis (dhf/spec/compliance/gap.feature), a stage of `rdm-spec
  check` and a quiet stage of checklist, release and audit, run only under
  the regulated profile: every clause of every declared clause list is named
  by an audit tag in a controlled document (dhf/spec/glossary.md, \"The
  regulated profile\").

  The configuration's :regulated is {:clause-lists [path ..] :documents dir}.
  Clause lists and documents are read at the revision SpecParsed carries (a
  cut, for checklist), else in the working tree. The stage publishes
  ClausesIndexed:

    {:revision  \"<commit>\" | :working-tree
     :clauses   [{:key :description :clause-list :line :declared :named-at [{:file :line}]}]
     :documents [{:file :id :revision :title}]
     :findings  [{:file :line :rule :message :severity}]}

  :clause-list and :line are where the clause's line stands (an included
  file, for an included clause); :declared is the declared list it was
  reached through. Clauses come in the declared lists' order, includes in
  place, each key once.

  Findings (exit 1):
    clause-unreferenced   CLAUSE-LIST:LINE, a clause no audit tag names (a gap)
    clause-line-malformed CLAUSE-LIST:LINE, a line that is no clause, include or comment
    document-unidentified DOCUMENT, frontmatter without an id, a revision or a title
    document-duplicate-id DOCUMENT, an id another controlled document has too
  Warnings:
    clause-unknown        DOCUMENT:LINE, an audit tag that is one key no clause list holds
    document-placeholder  DOCUMENT:LINE, a line still holding TODO
  Refusals (exit 2): no :regulated as declared, a clause list or include that
  is no readable file, a declared list holding no clause, a missing documents
  directory. Under release and audit, which relay gaps only, a document's own
  finding (any finding but a gap or a warning) stops the task as a refusal.

  Audit tags (also used by the audit copy): `[[ ... ]]` in a document's body,
  outside inline code and fenced code blocks, never running past a blank
  line (`audit-tags`); `strip-tags` removes them with the space before."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Reading files: the working tree or a revision

(defn- git [root & args]
  (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args)]
    {:exit (:exit r) :out (:out r)}))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn reader
  "{:list (fn [dir] [rel]) :read (fn [rel] text-or-nil) :dir? (fn [dir])}:
  the working tree under `root`, or `commit` of its repository."
  [root commit]
  (if commit
    {:list (fn [dir]
             (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str (strip-slash dir) "/"))]
               (if (zero? (:exit r))
                 (vec (sort (remove str/blank? (str/split (:out r) #"\u0000"))))
                 [])))
     :read (fn [rel]
             (let [r (git root "show" (str commit ":./" rel))]
               (when (zero? (:exit r)) (:out r))))
     :dir? (constantly true)}
    {:list (fn [dir]
             (let [d (command/path root dir)]
               (if (fs/directory? d)
                 (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                              (command/rel root f))))
                 [])))
     :read (fn [rel]
             (let [f (command/path root rel)]
               (when (fs/regular-file? f) (slurp (str f)))))
     :dir? (fn [dir] (fs/directory? (command/path root dir)))}))

(defn- lines-of [text] (str/split (str text) #"\r?\n" -1))

;; ---------------------------------------------------------------------------
;; Clause lists

(def key-chars "A-Za-z0-9._:\\-")

(def key-re (re-pattern (str "[" key-chars "]+")))

(defn- key-char? [c] (boolean (and c (re-matches (re-pattern (str "[" key-chars "]")) (str c)))))

(defn normalize-rel [rel]
  (str/replace (str (fs/normalize (fs/path rel))) "\\" "/"))

(defn parse-clause-list
  "Pure: the lines of a clause list's `text` (the file `file`) as
  [{:line n :clause {:key :description}} | {:line n :include rel} |
  {:line n :malformed text}], comments and blanks left out. An include is
  resolved relative to `file`."
  [file text]
  (vec (keep-indexed
        (fn [i raw]
          (let [l (str/trim raw)
                n (inc i)]
            (cond
              (or (str/blank? l) (str/starts-with? l "#")) nil
              (re-matches #"include\s+\S.*" l)
              (let [target (str/trim (subs l (count "include")))
                    parent (fs/parent (fs/path file))]
                {:line n :include (normalize-rel (if parent (str (fs/path parent target)) target))})
              :else
              (if-let [[_ k d] (re-matches (re-pattern (str "([" key-chars "]+)(?:\\s+(.*))?")) l)]
                {:line n :clause {:key k :description (str/trim (str d))}}
                {:line n :malformed l}))))
        (lines-of text))))

(declare parse-all)

(defn read-clauses
  "Every clause of the declared `lists`, read with `rd`, in order, includes
  in place, each key once: {:clauses [..] :findings [..]}. Refuses an
  unreadable list or include, and a declared list holding no clause."
  [rd lists]
  (let [seen-keys (atom #{})
        clauses (atom [])
        findings (atom [])
        walk (fn walk [file declared visiting from]
               (let [text ((:read rd) file)]
                 (when (nil? text)
                   (command/cannot-run!
                    (if from
                      (str (:file from) ":" (:line from) ": the include names " file ", which is not a readable file")
                      (str "the clause list " file " is not a readable file"))))
                 (doseq [{:keys [line clause include malformed]} (parse-clause-list file text)]
                   (cond
                     include (when-not (contains? visiting include)
                               (walk include declared (conj visiting include) {:file file :line line}))
                     malformed (swap! findings conj
                                      (command/finding file line "clause-line-malformed"
                                                       (str "the line " (pr-str malformed)
                                                            " is no clause (KEY description), include or comment")))
                     (contains? @seen-keys (:key clause)) nil
                     :else (do (swap! seen-keys conj (:key clause))
                               (swap! clauses conj (assoc clause :clause-list file :line line
                                                          :declared declared)))))))]
    (doseq [l lists]
      (let [l (normalize-rel l)]
        (walk l l #{l} nil)
        (when (empty? (filter :clause (parse-all rd l)))
          (command/cannot-run! (str "the clause list " l " holds no clause")))))
    {:clauses @clauses :findings @findings}))

(defn- parse-all
  "Every parsed line of the list `file` and its includes (for counting)."
  ([rd file] (parse-all rd file #{file}))
  ([rd file visiting]
   (let [ls (parse-clause-list file (or ((:read rd) file) ""))]
     (vec (mapcat (fn [{:keys [include] :as l}]
                    (if (and include (not (contains? visiting include)))
                      (parse-all rd include (conj visiting include))
                      [l]))
                  ls)))))

;; ---------------------------------------------------------------------------
;; Documents: frontmatter and body

(defn split-frontmatter
  "Pure: {:frontmatter text-or-nil :body-offset n :body-line n} of a
  document's `text`: the frontmatter between a first line --- and the next
  --- (or ...); the body starts after it (offset into text, 1-based line)."
  [text]
  (let [text (str text)]
    (if-let [m (re-find #"^---[ \t]*\r?\n" text)]
      (let [rest-text (subs text (count m))
            matcher (re-matcher #"(?m)^(?:---|\.\.\.)[ \t]*(?:\r?\n|$)" rest-text)]
        (if (.find matcher)
          (let [fm (subs rest-text 0 (.start matcher))
                off (+ (count m) (.end matcher))]
            {:frontmatter fm :body-offset off
             :body-line (inc (count (filter #(= \newline %) (subs text 0 off))))})
          {:frontmatter nil :body-offset 0 :body-line 1}))
      {:frontmatter nil :body-offset 0 :body-line 1})))

(defn identity-of
  "Pure: {:id :revision :title :error} from a document's frontmatter text;
  each value a string, nil when missing."
  [fm]
  (if (nil? fm)
    {:error "it has no frontmatter"}
    (let [m (try (yaml/parse-string fm) (catch Exception e {::error (ex-message e)}))]
      (cond
        (::error m) {:error (str "its frontmatter is not readable YAML: " (::error m))}
        (not (map? m)) {:error "its frontmatter is not a map"}
        :else (into {} (for [k [:id :revision :title]
                             :let [v (get m k)]]
                         [k (when (and (some? v) (not (str/blank? (str v)))) (str v))]))))))

;; ---------------------------------------------------------------------------
;; Audit tags

(defn- fence-line? [l] (re-find #"^ {0,3}(```|~~~)" l))

(defn paragraphs
  "Pure: the paragraphs of `text` from `offset` on, as [start end) offsets
  into text: runs of lines that are neither blank nor in a fenced code block
  (fence lines included)."
  [text offset]
  (let [text (str text)
        n (count text)]
    (loop [pos offset fence nil cur nil out []]
      (if (>= pos n)
        (if cur (conj out [cur n]) out)
        (let [nl (str/index-of text "\n" pos)
              end (if nl nl n)
              next-pos (if nl (inc nl) n)
              l (subs text pos end)
              fl (fence-line? l)]
          (cond
            fence (recur next-pos (if (and fl (str/starts-with? (str/triml l) fence)) nil fence) nil out)
            fl (recur next-pos (subs (str/triml l) 0 3) nil (if cur (conj out [cur pos]) out))
            (str/blank? l) (recur next-pos nil nil (if cur (conj out [cur pos]) out))
            :else (recur next-pos nil (or cur pos) out)))))))

(defn- code-mask
  "Pure: the set of offsets in [start end) of `text` inside inline code
  spans (backticks included)."
  [text start end]
  (loop [i start masked (transient #{})]
    (if (>= i end)
      (persistent! masked)
      (if (= \` (.charAt ^String text i))
        (let [j (loop [j i] (if (and (< j end) (= \` (.charAt ^String text j))) (recur (inc j)) j))
              run (- j i)
              close (loop [k j]
                      (when (< k end)
                        (if (= \` (.charAt ^String text k))
                          (let [e (loop [e k] (if (and (< e end) (= \` (.charAt ^String text e))) (recur (inc e)) e))]
                            (if (= run (- e k)) k (recur e)))
                          (recur (inc k)))))]
          (if close
            (recur (+ close run) (reduce conj! masked (range i (+ close run))))
            (recur j masked)))
        (recur (inc i) masked)))))

(defn- line-at [text offset]
  (inc (count (filter #(= \newline %) (subs text 0 offset)))))

(defn audit-tags
  "Pure: every audit tag of a document's `text`: [{:start :end :text :line}],
  start and end offsets of [[ and past ]], text what is between, line the
  line of [[. Frontmatter holds none."
  [text]
  (let [text (str text)
        {:keys [body-offset]} (split-frontmatter text)]
    (vec (mapcat
          (fn [[s e]]
            (let [masked (code-mask text s e)
                  free? (fn [i] (and (not (masked i)) (not (masked (inc i)))))
                  find-from (fn [token from]
                              (loop [i from]
                                (when-let [j (str/index-of text token i)]
                                  (when (< (+ j 1) e)
                                    (if (free? j) j (recur (inc j)))))))]
              (loop [from s out []]
                (if-let [o (find-from "[[" from)]
                  (if-let [c (find-from "]]" (+ o 2))]
                    (recur (+ c 2) (conj out {:start o :end (+ c 2) :text (subs text (+ o 2) c)
                                              :line (line-at text o)}))
                    out)
                  out))))
          (paragraphs text body-offset)))))

(defn names?
  "Pure: true when the audit tag text `tag-text` names the clause `k`: it
  holds k, or a dotted descendant of it, bounded by characters that are not
  key characters, a `: note` after the key allowed."
  [tag-text k]
  (let [t (str tag-text)]
    (loop [from 0]
      (if-let [i (str/index-of t k from)]
        (let [before (when (pos? i) (.charAt t (dec i)))
              after (subs t (+ i (count k)))
              run (or (re-find (re-pattern (str "^[" key-chars "]+")) after) "")]
          (if (and (not (key-char? before))
                   (or (= "" run) (str/starts-with? run ".") (= ":" run)))
            true
            (recur (inc i))))
        false))))

(defn single-key
  "Pure: the one key a tag text is (a `: note` after it allowed), or nil."
  [tag-text]
  (let [t (str/trim (str tag-text))
        t (if-let [[_ k] (re-matches #"(\S+?):\s.*" t)] k t)
        t (str/replace t #"[:.]$" "")]
    (when (re-matches key-re t) t)))

(defn strip-tags
  "Pure: `text` with every audit tag removed, with the space before it; a
  tag running over lines leaves its line breaks, so the lines stay."
  [text]
  (let [text (str text)
        tags (audit-tags text)]
    (loop [ts (reverse tags) out text]
      (if-let [{:keys [start end]} (first ts)]
        (let [inner (subs out start end)
              breaks (count (filter #(= \newline %) inner))
              s (loop [s start]
                  (if (and (pos? s) (#{\space \tab} (.charAt ^String out (dec s)))) (recur (dec s)) s))
              e (if (pos? breaks)
                  (loop [e end] (if (and (< e (count out)) (#{\space \tab} (.charAt ^String out e))) (recur (inc e)) e))
                  end)]
          (recur (rest ts) (str (subs out 0 s) (apply str (repeat breaks "\n")) (subs out e))))
        out))))

;; ---------------------------------------------------------------------------
;; The analysis

(defn analyse
  "Pure: the analysis of `clauses` (read-clauses') against `documents`
  ([{:file :text}], sorted): {:clauses :documents :findings}."
  [clauses documents]
  (let [docs (for [{:keys [file text]} documents
                   :let [{:keys [frontmatter]} (split-frontmatter text)]]
               (merge {:file file :text text :tags (audit-tags text)} (identity-of frontmatter)))
        keys* (map :key clauses)
        named-at (fn [k] (vec (for [d docs t (:tags d) :when (names? (:text t) k)]
                                {:file (:file d) :line (:line t)})))
        clauses (mapv #(assoc % :named-at (vec (distinct (named-at (:key %))))) clauses)
        by-id (group-by :id (filter :id docs))
        unidentified (for [d docs
                           :let [missing (if (:error d) [] (filter #(nil? (get d %)) [:id :revision :title]))]
                           :when (or (:error d) (seq missing))]
                       (command/finding (:file d) nil "document-unidentified"
                                        (if (:error d)
                                          (str "the document is not controlled: " (:error d)
                                               "; a controlled document carries an id, a revision and a title")
                                          (str "the document has no " (str/join ", no " (map name missing))
                                               ": a controlled document carries an id, a revision and a title"))))
        duplicates (for [d docs
                         :let [others (remove #(= (:file d) (:file %)) (get by-id (:id d)))]
                         :when (and (:id d) (seq others))]
                     (command/finding (:file d) nil "document-duplicate-id"
                                      (str "the id " (:id d) " is also the id of "
                                           (str/join ", " (map :file others))
                                           ": a controlled document's id is unique")))
        gaps (for [c clauses :when (empty? (:named-at c))]
               (command/finding (:clause-list c) (:line c) "clause-unreferenced"
                                (str (:key c) " " (:description c)
                                     ": no audit tag in a controlled document names this clause")))
        unknown (for [d docs t (:tags d)
                      :when (not-any? #(names? (:text t) %) keys*)
                      :let [k (single-key (:text t))]
                      :when k]
                  (command/warning (:file d) (:line t) "clause-unknown"
                                   (str "the audit tag names " k ", which no declared clause list holds")))
        placeholders (for [d docs
                           [i l] (map-indexed vector (lines-of (:text d)))
                           :when (re-find #"\bTODO\b" l)]
                       (command/warning (:file d) (inc i) "document-placeholder"
                                        (str "the document still holds a placeholder: " (str/trim l))))]
    {:clauses clauses
     :documents (mapv #(select-keys % [:file :id :revision :title]) docs)
     :findings (vec (concat gaps unidentified duplicates unknown placeholders))}))

;; ---------------------------------------------------------------------------
;; The stage

(defn subscriptions
  "Keeps the revision SpecParsed read, so the clauses are read there too."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::revision (:revision payload)) nil)]])

(defn regulated
  "The configuration's :regulated, checked: {:clause-lists [..] :documents dir}."
  [req]
  (let [r (get-in req [:config :regulated])]
    (when-not (map? r)
      (command/cannot-run! (str "the configuration " command/config-file " holds no :regulated")))
    (when-not (and (sequential? (:clause-lists r)) (seq (:clause-lists r)) (every? string? (:clause-lists r)))
      (command/cannot-run! (str "the configuration's :regulated declares no :clause-lists, a vector of paths")))
    (when-not (and (string? (:documents r)) (not (str/blank? (:documents r))))
      (command/cannot-run! (str "the configuration's :regulated declares no :documents directory")))
    r))

(defn index
  "Reads and analyses the clause lists and documents of the request at
  `revision` (a commit, or nil/:working-tree): the ClausesIndexed payload."
  [req revision]
  (let [{:keys [clause-lists documents]} (regulated req)
        commit (when (string? revision) revision)
        rd (reader (:root req) commit)
        docs-dir (strip-slash documents)
        _ (when-not ((:dir? rd) docs-dir)
            (command/cannot-run! (str "the documents directory " docs-dir " does not exist")))
        {:keys [clauses findings]} (read-clauses rd clause-lists)
        docs (vec (for [f ((:list rd) docs-dir) :when (str/ends-with? f ".md")]
                    {:file f :text ((:read rd) f)}))
        out (analyse clauses docs)]
    (assoc out :revision (or commit :working-tree)
           :findings (vec (concat findings (:findings out))))))

(def ^:private relays-gaps-only #{"release" "audit"})

(defn gap
  "The gap stage: indexes the clauses, publishes ClausesIndexed, and reports
  every finding. Under release and audit a document's own finding refuses."
  [req]
  (let [revision (some-> (:state req) deref ::revision)
        payload (index req revision)
        findings (:findings payload)
        own (remove #(or (command/warning? %) (= "clause-unreferenced" (:rule %))) findings)]
    (when (and (relays-gaps-only (:task req)) (seq own))
      (command/cannot-run! (str "the controlled documents are not releasable: "
                                (str/join "; " (map command/format-finding own)))))
    (command/publish! req :compliance/gap :ClausesIndexed
                      (update payload :findings (fn [fs] (mapv #(select-keys % [:file :line :rule :message :severity]) fs))))
    (swap! (:state req) assoc ::indexed payload)
    {:result {:gap {:revision (:revision payload)
                    :clauses (count (:clauses payload))
                    :documents (count (:documents payload))
                    :gaps (vec (for [c (:clauses payload) :when (empty? (:named-at c))] (:key c)))}}
     :findings findings}))
