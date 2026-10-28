(ns rdm-spec.compliance.gap
  "Component Gap analysis (compliance context): under the regulated profile,
  reads the declared clause lists (includes in place) and the controlled
  documents, and finds every clause no audit tag names. An audit tag is
  [[ ... ]] outside code and frontmatter, never past a blank line, naming a
  clause when it holds its key or a dotted descendant of it. A stage of the
  check; publishes ClausesIndexed. Without :regulated nothing of it runs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

;; ------------------------------------------------------------------ clause lists

(def key-re #"[A-Za-z0-9._:-]+")

(defn key? [s] (and (re-matches key-re s) (re-find #"[0-9:]" s)))

(defn read-list
  "The clauses of the clause list PATH (relative to the root), includes in
  place: {:clauses [...] :findings [...]}."
  [tree path declared stack]
  (let [text (project/read-text tree path)]
    (when (nil? text)
      (project/cannot-run! (if (seq stack)
                             (str "the clause list " (peek stack) " includes " path ", which is not a readable file")
                             (str "the declared clause list " path " is not a readable file"))))
    (reduce
     (fn [acc [i line]]
       (let [t (str/trim line) n (inc i)]
         (cond
           (or (str/blank? t) (str/starts-with? t "#")) acc
           (re-find #"^include\s+\S" t)
           (let [target (str/trim (subs t 7))
                 p (project/rel tree (project/abs (fs/parent (project/abs tree path)) target))]
             (if (some #{p} (conj stack path))
               (update acc :findings conj (project/finding path n "clause-include-cycle"
                                                           (str "the include of " p " leads back to a list already being read")))
               (let [r (read-list tree p declared (conj stack path))]
                 (-> acc (update :clauses into (:clauses r)) (update :findings into (:findings r))))))
           :else
           (let [[k d] (str/split t #"\s+" 2)]
             (if (key? k)
               (update acc :clauses conj {:key k :description (or d "") :clause-list path :line n :declared declared})
               (update acc :findings conj (project/finding path n "clause-malformed"
                                                           (str "the line " (pr-str t) " is not a clause (KEY description), an include or a comment"))))))))
     {:clauses [] :findings []}
     (map-indexed vector (str/split-lines text)))))

;; ------------------------------------------------------------------ documents

(defn- lines-with-offsets [text]
  (loop [i 0 start 0 out []]
    (if (> start (count text))
      out
      (let [nl (str/index-of text "\n" start)
            end (or nl (count text))]
        (if nl
          (recur (inc i) (inc nl) (conj out {:n (inc i) :start start :text (subs text start end)}))
          (if (< start (count text)) (conj out {:n (inc i) :start start :text (subs text start end)}) out))))))

(defn- mask-code-spans
  "LINE with every inline code span's characters replaced by spaces."
  [line]
  (let [sb (StringBuilder. ^String line)]
    (loop [i 0]
      (when-let [m (re-find #"`+" (subs line i))]
        (let [open (str/index-of line m i)
              close (str/index-of line m (+ open (count m)))]
          (when close
            (doseq [j (range open (+ close (count m)))] (.setCharAt sb j \space))
            (recur (+ close (count m)))))))
    (str sb)))

(defn- list-item? [s] (boolean (re-find #"^\s*([-*+]|\d+[.)])\s+" s)))
(defn- indented? [s] (boolean (re-find #"^( {4}|\t)" s)))

(defn scan
  "A controlled document's TEXT: {:frontmatter {k v} :fm-lines #{n} :tags
  [{:start :end :line :text :frontmatter?}] :todos [n]}. Tags are found
  outside code, never past a blank line; frontmatter tags are kept apart."
  [text]
  (let [ls (lines-with-offsets text)
        fm-end (when (and (seq ls) (= "---" (str/trim (:text (first ls)))))
                 (some #(when (and (> (:n %) 1) (#{"---" "..."} (str/trim (:text %)))) (:n %)) ls))
        fm-lines (if fm-end (set (range 1 (inc fm-end))) #{})
        frontmatter (into {} (for [l ls :when (and (fm-lines (:n l)) (not= 1 (:n l)) (not= fm-end (:n l)))
                                   :let [[_ k v] (re-matches #"\s*([A-Za-z0-9_-]+)\s*:\s*(.*)" (:text l))] :when k]
                               [k (str/trim (str/replace v #"^['\"]|['\"]$" ""))]))
        ;; classify body lines: :text, :code or :blank
        classified (loop [ls (remove #(fm-lines (:n %)) ls) fence nil prev-blank? true in-list? false code? false out []]
                     (if (empty? ls)
                       out
                       (let [l (first ls) t (:text l)]
                         (cond
                           fence (recur (rest ls) (if (re-find (re-pattern (str "^\\s{0,3}" fence)) t) nil fence) false in-list? false (conj out (assoc l :kind :code)))
                           (re-find #"^\s{0,3}(```|~~~)" t) (recur (rest ls) (second (re-find #"^\s{0,3}(```|~~~)" t)) false in-list? false (conj out (assoc l :kind :code)))
                           (str/blank? t) (recur (rest ls) nil true in-list? code? (conj out (assoc l :kind :blank)))
                           (and (indented? t) (or code? (and prev-blank? (not in-list?)))) (recur (rest ls) nil false in-list? true (conj out (assoc l :kind :code)))
                           :else (recur (rest ls) nil false (or (list-item? t) (and in-list? (or (indented? t) (not prev-blank?)))) false
                                        (conj out (assoc l :kind :text)))))))
        paragraphs (->> classified
                        (partition-by #(= :text (:kind %)))
                        (filter #(= :text (:kind (first %)))))
        tags (vec (concat
                   (for [p paragraphs
                         :let [start (:start (first p))
                               masked (str/join "\n" (map #(mask-code-spans (:text %)) p))
                               matcher (re-matcher #"\[\[[\s\S]*?\]\]" masked)]
                         [s e] (loop [out []] (if (.find matcher) (recur (conj out [(.start matcher) (.end matcher)])) out))]
                     (let [abs-s (+ start s) abs-e (+ start e)]
                       {:start abs-s :end abs-e :text (subs text (+ abs-s 2) (- abs-e 2))
                        :line (:n (last (filter #(<= (:start %) abs-s) ls)))}))
                   (for [l ls :when (and (fm-lines (:n l)) (not= 1 (:n l)) (not= fm-end (:n l)))
                         [s e] (let [m (re-matcher #"\[\[.*?\]\]" (:text l))] (loop [out []] (if (.find m) (recur (conj out [(.start m) (.end m)])) out)))]
                     {:start (+ (:start l) s) :end (+ (:start l) e) :text (subs (:text l) (+ s 2) (- e 2)) :line (:n l) :frontmatter? true})))]
    {:frontmatter frontmatter
     :fm-lines fm-lines
     :tags (vec (sort-by :start tags))
     :todos (vec (for [l classified :when (and (#{:text :code} (:kind l)) (re-find #"\bTODO\b" (:text l)))] (:n l)))}))

(defn tag-keys
  "The keys an audit tag's TEXT holds, a trailing : note allowed."
  [text]
  (vec (distinct (for [t (re-seq key-re text)
                       :let [t (str/replace t #"[.:]+$" "")]
                       :when (key? t)]
                   t))))

(defn names?
  "Whether the tag key T names the clause K: it is K, or a dotted descendant of it."
  [k t]
  (or (= k t) (str/starts-with? t (str k "."))))

(defn documents [tree reg]
  (let [d (project/abs tree (:documents reg))]
    (if (fs/directory? d)
      (vec (sort (for [f (fs/glob d "**.md")] (project/rel tree f))))
      [])))

;; ------------------------------------------------------------------ the index

(defn index
  "The ClausesIndexed data of the project at TREE, :warnings beside :findings."
  [tree cfg]
  (let [reg (:regulated cfg)
        lists (for [l (:clause-lists reg)]
                (let [r (read-list tree l l [])]
                  (when (empty? (:clauses r))
                    (project/cannot-run! (str "the clause list " l " holds no clause")))
                  r))
        all (mapcat :clauses lists)
        clauses (vals (reduce (fn [m c] (if (contains? m (:key c)) m (assoc m (:key c) c))) (array-map) all))
        clauses (sort-by #(.indexOf ^java.util.List (vec (distinct (map :key all))) (:key %)) clauses)
        docs (for [f (documents tree reg)]
               (assoc (scan (project/read-text tree f)) :file f))
        named-at (fn [k] (vec (for [d docs t (:tags d) :when (not (:frontmatter? t)) tk (tag-keys (:text t)) :when (names? k tk)]
                                {:file (:file d) :line (:line t)})))
        clauses (vec (for [c clauses] (assoc c :named-at (vec (distinct (named-at (:key c)))))))
        keys-known (map :key clauses)
        findings (concat
                  (mapcat :findings lists)
                  (for [c clauses :when (empty? (:named-at c))]
                    (project/finding (:clause-list c) (:line c) "clause-unreferenced"
                                     (str (:key c) " " (:description c) " is named by no audit tag in a controlled document")))
                  (for [d docs field ["id" "revision" "title"] :when (str/blank? (get (:frontmatter d) field))]
                    (project/finding (:file d) 1 "document-unidentified"
                                     (str (:file d) " has no " field " in its frontmatter: a controlled document carries its id, revision and title")))
                  (for [[id ds] (group-by #(get (:frontmatter %) "id") docs) :when (and id (not (str/blank? id)) (> (count ds) 1))]
                    (project/finding (:file (first ds)) 1 "document-id-duplicate"
                                     (str "the id " id " is held by " (str/join " and " (map :file ds)) ": a document's id is unique"))))
        warnings (concat
                  (for [d docs t (:tags d) :when (not (:frontmatter? t))
                        :let [head (str/trim (first (str/split (:text t) #":\s" 2)))
                              ks (tag-keys head)]
                        :when (and (= 1 (count ks)) (= head (first ks))
                                   (not (some #(names? % (first ks)) keys-known)))]
                    (project/warning (:file d) (:line t) "clause-unknown" (str "the audit tag names " (first ks) ", which no declared clause list holds")))
                  (for [d docs n (:todos d)]
                    (project/warning (:file d) n "document-placeholder" (str (:file d) " still holds a placeholder (TODO): it meets no clause yet"))))]
    {:revision :working-tree
     :clauses clauses
     :documents (vec (for [d docs] {:file (:file d) :id (get (:frontmatter d) "id") :revision (get (:frontmatter d) "revision")
                                    :title (get (:frontmatter d) "title")}))
     :findings (vec findings)
     :warnings (vec warnings)}))

(defn index!
  "Indexes the clauses and documents (of TREE at REVISION, the working tree by
  default) and publishes ClausesIndexed; nil without :regulated."
  [cfg & [tree revision]]
  (when (:regulated cfg)
    (let [k (cond-> (index (or tree (project/root)) cfg) revision (assoc :revision revision))]
      (bus/publish! :compliance/gap :ClausesIndexed (assoc k :findings (into (:findings k) (:warnings k))))
      k)))

(defn check-stage [{:keys [cfg]}]
  (if-let [k (index! cfg)]
    {:result {:clauses {:declared (count (:clauses k)) :gaps (count (filter #(empty? (:named-at %)) (:clauses k)))}}
     :findings (:findings k)
     :warnings (:warnings k)}
    {:result nil :findings [] :warnings []}))
