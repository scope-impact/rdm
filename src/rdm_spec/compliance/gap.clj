(ns rdm-spec.compliance.gap
  "Gap analysis (dhf/spec/compliance/gap.feature): under the regulated
  profile, every clause of every declared clause list is named by an audit
  tag in a controlled document. A stage of `rdm-spec check`, run only when
  the configuration holds :regulated

    {:regulated {:clause-lists [\"dhf/clauses/std.txt\" ...] :documents \"dhf/documents\"}}

  It reads the clause lists and the controlled documents, in the working
  tree or, for a task over a cycle (checklist), at the cycle's cut, and
  publishes ClausesIndexed:

    {:revision  commit | :working-tree
     :clauses   [{:key :description :clause-list :line :named-at [{:file :line}]}]
                ; in the lists' order, includes in place, each key once;
                ; :clause-list is the file holding the clause's line
     :documents [{:file :id :revision :title}]
     :findings  [{:file :line :rule :message}]}   ; the warnings included

  Findings (exit 1): clause-unreferenced (a clause no audit tag names, at its
  clause list's line), clause-line-malformed, document-unidentified (no id,
  revision or title in its frontmatter), document-id-duplicate. Warnings:
  clause-unknown (an audit tag that is one key no clause list holds),
  document-placeholder (a line still holding TODO). Cannot run (exit 2): a
  :regulated of another shape, a clause list or include that names no
  readable file, a declared clause list holding no clause, no documents
  directory.

  The Markdown reading here (frontmatter, code, audit tags) is shared with
  the audit copy, in this context."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Keys

(def key-char "A-Za-z0-9._:-")

(def ^:private key-re (re-pattern (str "[" key-char "]+")))

(defn names-key?
  "Pure: whether the audit tag text `text` names the clause `k`: it holds the
  key or a dotted descendant of it, bounded by characters that are not key
  characters; a `: note` after the key is allowed."
  [text k]
  (let [q (java.util.regex.Pattern/quote k)
        re (re-pattern (str "(?<![" key-char "])" q
                            "(?=$|[^" key-char "]|\\.[" key-char "]|\\.(?![" key-char "])|:(?:\\s|$))"))]
    (boolean (re-find re (str text)))))

(defn single-key
  "Pure: the key when the tag text is one key (a `: note` after it allowed),
  else nil."
  [text]
  (second (re-matches (re-pattern (str "(?s)\\s*([" key-char "]*[A-Za-z0-9._-])(?::\\s.*)?\\s*")) (str text))))

;; ---------------------------------------------------------------------------
;; Clause lists

(defn parse-clause-list
  "Pure: the lines of a clause list: [{:kind :clause :key :description :line}
  | {:kind :include :file :line} | {:kind :malformed :text :line}]; comments
  and blank lines are dropped."
  [text]
  (vec (keep-indexed
        (fn [i raw]
          (let [l (str/trim raw)
                n (inc i)]
            (cond
              (or (str/blank? l) (str/starts-with? l "#")) nil
              (re-matches #"include\s+\S.*" l) {:kind :include :file (str/trim (subs l 7)) :line n}
              :else (if-let [[_ k d] (re-matches (re-pattern (str "([" key-char "]+)(?:\\s+(.*))?")) l)]
                      {:kind :clause :key k :description (str/trim (or d "")) :line n}
                      {:kind :malformed :text l :line n}))))
        (str/split-lines (str text)))))

(defn- normalize [rel]
  (let [parts (reduce (fn [acc part]
                        (cond (or (= "" part) (= "." part)) acc
                              (and (= ".." part) (seq acc) (not= ".." (peek acc))) (pop acc)
                              :else (conj acc part)))
                      [] (str/split (str rel) #"/"))]
    (str/join "/" parts)))

(defn- parent-of [rel]
  (let [i (str/last-index-of rel "/")] (if i (subs rel 0 i) "")))

(defn read-clause-lists
  "The clauses of the declared lists `lists`, read with `read-text` (rel ->
  text or nil): {:clauses [..] :findings [..]}, each key once, in order with
  includes in place. Cannot run on a list or include naming no readable file
  and on a declared list holding no clause."
  [read-text lists]
  (let [seen (atom #{})
        counts (atom {})
        clauses (atom [])
        findings (atom [])
        walk (fn walk [rel from]
               (if-let [n (get @counts rel)]
                 n
                 (let [text (read-text rel)]
                   (when (nil? text)
                     (command/cannot-run! (if from
                                            (str "the clause list " (:file from) " includes " rel " (line " (:line from)
                                                 "), which names no readable file")
                                            (str "the clause list " rel ", which :regulated declares, names no readable file"))))
                   ;; an include cycle counts nothing twice
                   (swap! counts assoc rel 0)
                   (let [n (reduce (fn [n {:keys [kind] :as l}]
                                     (case kind
                                       :include (+ n (walk (normalize (str (parent-of rel) "/" (:file l)))
                                                           {:file rel :line (:line l)}))
                                       :malformed (do (swap! findings conj
                                                             (command/finding rel (:line l) "clause-line-malformed"
                                                                              (str "the line " (pr-str (:text l))
                                                                                   " is no clause (KEY description),"
                                                                                   " include FILE, comment or blank line")))
                                                      n)
                                       :clause (do (when-not (@seen (:key l))
                                                     (swap! seen conj (:key l))
                                                     (swap! clauses conj {:key (:key l) :description (:description l)
                                                                          :clause-list rel :line (:line l)}))
                                                   (inc n))))
                                   0 (parse-clause-list text))]
                     (swap! counts assoc rel n)
                     n))))]
    (doseq [rel lists]
      (when (zero? (walk (normalize rel) nil))
        (command/cannot-run! (str "the clause list " rel " holds no clause: a declared list names at least one"))))
    {:clauses @clauses :findings @findings}))

;; ---------------------------------------------------------------------------
;; Markdown: frontmatter, code and audit tags

(defn frontmatter
  "Pure: the document's frontmatter, {:fields {\"id\" ..} :end offset} where
  :end is the offset just after its closing line; nil when it has none."
  [text]
  (let [text (str text)]
    (when (re-find #"^---[ \t]*\r?\n" text)
      (let [m (re-matcher #"(?m)^(---|\.\.\.)[ \t]*$" text)
            first-end (do (.find m) (.end m))]
        (when (.find m first-end)
          (let [body (subs text first-end (.start m))
                end (let [e (.end m)] (if (and (< e (count text)) (= \newline (.charAt text e))) (inc e) e))
                fields (into {} (for [l (str/split-lines body)
                                      :let [[_ k v] (re-matches #"\s*([A-Za-z0-9_-]+)\s*:\s*(.*?)\s*" l)]
                                      :when k]
                                  [k (let [v (str v)]
                                       (if-let [[_ q] (or (re-matches #"\"(.*)\"" v) (re-matches #"'(.*)'" v))] q v))]))]
            {:fields fields :end end}))))))

(defn- line-starts [text]
  (vec (cons 0 (keep-indexed (fn [i c] (when (= \newline c) (inc i))) (str text)))))

(defn line-at
  "Pure: the 1-based line of the offset `i` in a text whose line starts are `starts`."
  [starts i]
  (let [n (count (take-while #(<= % i) starts))] (max 1 n)))

(defn- fence-open [line]
  (second (re-matches #" {0,3}(`{3,}|~{3,}).*" line)))

(defn- paragraphs
  "Pure: [[start end] ...] offsets of the runs of prose lines of `text` from
  `from`: no blank line inside one, fenced code blocks left out."
  [text from]
  (let [starts (line-starts text)
        n (count text)]
    (loop [ls (filter #(>= % from) starts) fence nil para nil out []]
      (if (empty? ls)
        (if para (conj out [para n]) out)
        (let [s (first ls)
              e (let [i (str/index-of text "\n" s)] (if i i n))
              line (subs text s e)
              close-para (fn [o] (if para (conj o [para s]) o))]
          (cond
            fence (recur (rest ls)
                         (when-not (let [t (str/trim line)]
                                     (and (str/starts-with? t (subs fence 0 1))
                                          (re-matches (re-pattern (str (java.util.regex.Pattern/quote (subs fence 0 1)) "{" (count fence) ",}\\s*")) t)))
                           fence)
                         nil out)
            (fence-open line) (recur (rest ls) (fence-open line) nil (close-para out))
            (str/blank? line) (recur (rest ls) nil nil (close-para out))
            :else (recur (rest ls) nil (or para s) out)))))))

(defn audit-tags
  "Pure: every audit tag of the Markdown `text`: [{:start :end :line :text}],
  :start at its [[ and :end just after its ]]. Outside fenced code and code
  spans, the frontmatter included (a title may carry one); a tag never runs
  past a blank line."
  [text]
  (let [text (str text)
        from 0
        starts (line-starts text)]
    (vec
     (for [[ps pe] (paragraphs text from)
           tag (loop [i ps out []]
                 (if (>= i pe)
                   out
                   (let [c (.charAt text i)]
                     (cond
                       (= \` c)
                       (let [run (count (take-while #(= \` %) (subs text i pe)))
                             closing (re-matcher (re-pattern (str "(?<!`)`{" run "}(?!`)")) (subs text (+ i run) pe))]
                         (if (.find closing)
                           (recur (+ i run (.end closing)) out)
                           (recur (+ i run) out)))

                       (and (= \[ c) (< (inc i) pe) (= \[ (.charAt text (inc i))))
                       (let [close (str/index-of text "]]" (+ i 2))]
                         (if (and close (<= (+ close 2) pe))
                           (recur (+ close 2) (conj out {:start i :end (+ close 2) :line (line-at starts i)
                                                          :text (subs text (+ i 2) close)}))
                           (recur (+ i 2) out)))

                       :else (recur (inc i) out)))))]
       tag))))

(defn strip-tags
  "Pure: the engineer's copy of `text`: every audit tag removed with the
  white space before it on its line; a tag's own line breaks are kept, so
  every other line stays where it was."
  [text]
  (let [text (str text)]
    (loop [tags (audit-tags text) pos 0 sb (StringBuilder.)]
      (if (empty? tags)
        (str (.append sb (subs text pos)))
        (let [{:keys [start end]} (first tags)
              ws-start (loop [j start]
                         (if (and (> j pos) (#{\space \tab} (.charAt text (dec j)))) (recur (dec j)) j))
              breaks (apply str (filter #(= \newline %) (subs text start end)))]
          (.append sb (subs text pos ws-start))
          (.append sb breaks)
          (recur (rest tags) end sb))))))

;; ---------------------------------------------------------------------------
;; Reading the project, in the working tree or at a cut

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn commit-of
  "The commit `rev` names in the repository at `root`, or nil."
  [root rev]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn reader
  "{:read (fn [rel] text-or-nil) :list (fn [dir] [rel])} of the project at
  `root`, in the working tree (commit nil) or at `commit`."
  [root commit]
  (if commit
    {:read (fn [rel] (let [r (git root "show" (str commit ":" rel))] (when (zero? (:exit r)) (:out r))))
     :list (fn [dir] (let [r (git root "ls-tree" "-r" "--name-only" commit "--" (str dir "/"))]
                       (if (zero? (:exit r)) (vec (sort (remove str/blank? (str/split-lines (:out r))))) [])))
     :dir? (fn [dir] (let [r (git root "cat-file" "-t" (str commit ":" dir))]
                       (and (zero? (:exit r)) (= "tree" (str/trim (:out r))))))}
    {:read (fn [rel] (let [f (command/path root rel)] (when (fs/regular-file? f) (slurp (str f)))))
     :list (fn [dir] (let [d (command/path root dir)]
                       (if (fs/directory? d)
                         (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
                         [])))
     :dir? (fn [dir] (fs/directory? (command/path root dir)))}))

(defn- cycle-cut
  "The cut a cycle's cycle.edn names, for a task over a cycle; nil when none."
  [req]
  (when (= "checklist" (:task req))
    (let [cycle (first (:args req))
          cycles (str/replace (str (or (get-in req [:config :cycles-dir]) "dhf/cycles")) #"/+$" "")]
      (when (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
        (let [f (command/path req (str cycles "/" cycle "/cycle.edn"))]
          (when (fs/regular-file? f)
            (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
              (when (and (map? c) (string? (:cut c))) (:cut c)))))))))

(defn profile
  "The configuration's :regulated, {:clause-lists [..] :documents dir}, or
  cannot run."
  [config]
  (let [r (:regulated config)
        lists (:clause-lists r)]
    (when-not (and (map? r) (vector? lists) (seq lists) (every? #(and (string? %) (not (str/blank? %))) lists)
                   (string? (:documents r)) (not (str/blank? (:documents r))))
      (command/cannot-run! (str "the configuration " command/config-file " has :regulated " (pr-str r)
                                ": it must be {:clause-lists [\"FILE\" ...] :documents \"DIR\"}")))
    {:clause-lists lists :documents (str/replace (:documents r) #"/+$" "")}))

;; ---------------------------------------------------------------------------
;; Documents

(def identity-keys ["id" "revision" "title"])

(defn read-document
  "Pure: a controlled document `file` holding `text`, against the clause
  `keys`: {:file :id :revision :title :tags [{:line :text :names [key]}]
  :findings [..]}."
  [file text keys]
  (let [fm (frontmatter text)
        fields (:fields fm)
        missing (vec (remove #(not (str/blank? (get fields %))) identity-keys))
        tags (for [t (audit-tags text)]
               (assoc t :names (vec (filter #(names-key? (:text t) %) keys))))
        unidentified (when (seq missing)
                       [(command/finding file (when fm 1) "document-unidentified"
                                         (str "the controlled document has no " (str/join ", no " missing)
                                              (if fm " in its frontmatter" " (it has no frontmatter)")
                                              ": its frontmatter gives its id, revision and title"))])
        unknown (for [t tags
                      :let [k (single-key (:text t))]
                      :when (and k (empty? (:names t)))]
                  (command/warning file (:line t) "clause-unknown"
                                   (str "the audit tag [[" (str/trim (:text t)) "]] names " k ", which no clause list holds")))
        body-start (or (:end fm) 0)
        starts (line-starts text)
        placeholders (for [[i l] (map-indexed vector (str/split-lines (subs (str text) body-start)))
                           :when (str/includes? l "TODO")]
                       (command/warning file (+ (line-at starts body-start) i) "document-placeholder"
                                        (str "the document still holds a placeholder: " (str/trim l))))]
    {:file file
     :id (get fields "id") :revision (get fields "revision") :title (get fields "title")
     :tags (mapv #(select-keys % [:line :text :names]) tags)
     :findings (vec (concat unidentified unknown placeholders))}))

(defn duplicate-findings
  "Pure: a finding for every document whose id an earlier one carries."
  [docs]
  (vec (for [[id ds] (group-by :id (filter #(not (str/blank? (:id %))) docs))
             :when (> (count ds) 1)
             d (rest ds)]
         (command/finding (:file d) 1 "document-id-duplicate"
                          (str "the id " id " is also the id of " (:file (first ds))
                               ": a controlled document's id is unique")))))

;; ---------------------------------------------------------------------------
;; The index

(defn index
  "Pure over `read`: the clause index of the profile `prof` read with the
  reader `rd`: the ClausesIndexed payload less :revision, with :all-findings
  (findings and warnings, as findings) beside it."
  [rd prof]
  (let [{:keys [clauses findings]} (read-clause-lists (:read rd) (:clause-lists prof))
        docs-dir (:documents prof)
        _ (when-not ((:dir? rd) docs-dir)
            (command/cannot-run! (str "the documents directory " docs-dir ", which :regulated names, does not exist")))
        keys (mapv :key clauses)
        docs (vec (for [f ((:list rd) docs-dir) :when (str/ends-with? f ".md")]
                    (read-document f ((:read rd) f) keys)))
        named (reduce (fn [m {:keys [file tags]}]
                        (reduce (fn [m t] (reduce #(update %1 %2 (fnil conj []) {:file file :line (:line t)}) m (:names t)))
                                m tags))
                      {} docs)
        indexed (mapv #(assoc % :named-at (get named (:key %) [])) clauses)
        gaps (for [c indexed :when (empty? (:named-at c))]
               (command/finding (:clause-list c) (:line c) "clause-unreferenced"
                                (str (:key c) " " (pr-str (:description c)) " is named by no audit tag in a controlled document")))
        all (vec (concat findings gaps (mapcat :findings docs) (duplicate-findings docs)))]
    {:clauses indexed
     :documents (mapv #(select-keys % [:file :id :revision :title]) docs)
     :all-findings all}))

(defn gap
  "The gap stage: indexes the clause lists and the controlled documents
  (at the cycle's cut for a task over a cycle), publishes ClausesIndexed and
  reports every finding."
  [req]
  (let [prof (profile (:config req))
        cut (or (get-in req [:opts :cut]) (cycle-cut req))
        commit (when cut (commit-of (:root req) cut))]
    (if (and cut (nil? commit))
      ;; a cut the repository does not hold is the task's to refuse
      {:result {} :findings []}
      (let [{:keys [clauses documents all-findings]} (index (reader (:root req) commit) prof)]
        (command/publish! req :compliance/gap :ClausesIndexed
                          {:revision (or commit :working-tree)
                           :clauses clauses
                           :documents documents
                           :findings (mapv #(select-keys % [:file :line :rule :message]) all-findings)})
        {:result {:clauses {:lists (:clause-lists prof)
                            :clauses (count clauses)
                            :gaps (vec (keep #(when (empty? (:named-at %)) (:key %)) clauses))
                            :documents (count documents)}}
         :findings all-findings}))))
