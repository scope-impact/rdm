(ns rdm-spec.compliance.gap
  "Gap analysis, under the regulated profile: every clause of every declared
  clause list is named by an audit tag in a controlled document; clause lists
  read as clause lines, comments and includes; every controlled document
  carries its identity (dhf/spec/10-regulated-profile/gap.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]))

;; ------------------------------------------------------------------ keys and tags

(def key-run #"[A-Za-z0-9._:-]+")

(defn key? [s] (boolean (and (re-matches #"[A-Za-z0-9._:-]+" (str s)) (re-find #"[0-9:]" (str s)))))

(defn- tokens [text]
  (for [t (re-seq key-run (str text))
        :let [t (str/replace t #"[.:-]+$" "")]
        :when (key? t)]
    t))

(defn names?
  "Whether an audit tag's text names the clause key: the key, or a dotted descendant of it, bounded by non-key characters."
  [tag-text k]
  (boolean (some #(or (= % k) (str/starts-with? % (str k "."))) (tokens tag-text))))

(defn- lines-with-offsets [text]
  (let [ls (str/split (str text) #"\n" -1)]
    (vec (map vector (reductions + 0 (map #(inc (count %)) ls)) ls))))

(defn frontmatter-end
  "The index of the line closing the frontmatter, or nil when the text has none."
  [lines]
  (when (= "---" (str/trim (second (first lines))))
    (some (fn [i] (when (= "---" (str/trim (second (nth lines i)))) i)) (range 1 (count lines)))))

(defn mask
  "The text with its code (fenced and indented blocks, inline spans) and, unless keep-frontmatter, its frontmatter blanked out, newlines kept."
  [text & {:keys [keep-frontmatter]}]
  (let [text (str text)
        lines (lines-with-offsets text)
        fm (frontmatter-end lines)
        sb (StringBuilder. text)
        blank! (fn [start end] (doseq [k (range start end) :when (not= \newline (.charAt sb k))] (.setCharAt sb k \space)))]
    (loop [idx 0 fenced? false list? false prev-blank? true prev-code? false]
      (when (< idx (count lines))
        (let [[off line] (nth lines idx)
              end (+ off (count line))
              t (str/trim line)
              blank? (str/blank? line)
              indented? (re-find #"^(    |\t)" line)]
          (cond
            (and fm (<= idx fm))
            (do (when-not keep-frontmatter (blank! off end)) (recur (inc idx) false false true false))
            (or (str/starts-with? t "```") (str/starts-with? t "~~~"))
            (do (blank! off end) (recur (inc idx) (not fenced?) list? false true))
            fenced? (do (blank! off end) (recur (inc idx) true list? false true))
            blank? (recur (inc idx) false list? true prev-code?)
            (and indented? (not list?) (or prev-blank? prev-code?))
            (do (blank! off end) (recur (inc idx) false false false true))
            :else
            (let [item? (re-find #"^\s{0,3}([-*+]|\d+[.)])\s" line)
                  list? (cond item? true indented? list? :else false)]
              (doseq [m (re-seq #"`[^`\n]*`" line)
                      :let [s (str/index-of line m)]]
                (blank! (+ off s) (+ off s (count m))))
              (recur (inc idx) false list? false false))))))
    (str sb)))

(def tag-re #"\[\[((?:(?!\n[ \t]*\n)[\s\S])*?)\]\]")

(defn tag-spans
  "Every audit tag of a text outside code, never past a blank line: [{:start :end :text :line}]."
  [text & opts]
  (let [text (str text)
        masked (apply mask text opts)
        m (re-matcher tag-re masked)]
    (loop [acc []]
      (if (.find m)
        (let [s (.start m) e (.end m)]
          (recur (conj acc {:start s :end e :text (subs text (+ s 2) (- e 2))
                            :line (inc (count (filter #{\newline} (subs text 0 s))))})))
        acc))))

(defn tags [text] (mapv #(select-keys % [:text :line]) (tag-spans text)))

;; ------------------------------------------------------------------ documents

(defn frontmatter [text]
  (let [lines (lines-with-offsets (str text))
        fm (frontmatter-end lines)]
    (if fm
      {:frontmatter (into {} (for [[_ l] (subvec (vec lines) 1 fm)
                                   :let [[_ k v] (re-matches #"\s*([A-Za-z_-]+)\s*:\s*(.*?)\s*" l)]
                                   :when k]
                               [k (str/replace v #"^[\"']|[\"']$" "")]))
       :end fm}
      {:frontmatter nil})))

(defn placeholder-warnings [file text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (str text))) :when (str/includes? l "TODO")]
         (project/warning file (inc i) "document-placeholder" "the document still holds a placeholder: TODO"))))

(defn documents
  "Every controlled document under the documents directory: [{:file :rel :id :revision :title :text}]."
  [root dir]
  (vec (for [f (filter #(str/ends-with? % ".md") (project/files-under root dir))
             :let [text (slurp (str (fs/path root f)))
                   {fm :frontmatter} (frontmatter text)]]
         {:file f :rel (subs f (inc (count dir))) :text text :fm? (some? fm)
          :id (get fm "id") :revision (get fm "revision") :title (get fm "title")})))

(defn document-findings [docs]
  (vec (concat
        (for [d docs
              :let [missing (if (:fm? d) (filter #(str/blank? (get d %)) [:id :revision :title]) [:id :revision :title])]
              :when (seq missing)]
          (project/finding (:file d) "document-unidentified"
                           (str "the document has no " (str/join ", no " (map name missing)) " in its frontmatter: a controlled document carries its id, revision and title")))
        (for [[id ds] (group-by :id (filter :id docs)) :when (> (count ds) 1)
              d (rest ds)]
          (project/finding (:file d) "document-id-duplicate"
                           (str "the id " id " is the id of " (str/join " and " (map :file ds)) ": an id names one document"))))))

;; ------------------------------------------------------------------ clause lists

(defn parse-list
  "A clause list's lines: {:entries [{:kind :clause|:include ...}] :findings [malformed lines]}."
  [file text]
  (reduce (fn [acc [i line]]
            (let [t (str/trim line) n (inc i)]
              (cond
                (or (str/blank? t) (str/starts-with? t "#")) acc
                (str/starts-with? t "include ") (update acc :entries conj {:kind :include :target (str/trim (subs t 8)) :line n})
                :else
                (let [[k desc] (str/split t #"\s+" 2)]
                  (if (key? k)
                    (update acc :entries conj {:kind :clause :key k :description (str desc) :line n})
                    (update acc :findings conj (project/finding file n "clause-line-malformed"
                                                                (str "the line \"" t "\" is no clause, include, comment or blank line: a clause key holds a digit or a colon"))))))))
          {:entries [] :findings []}
          (map-indexed vector (str/split-lines (str text)))))

(defn- norm [p] (str/replace (str (fs/normalize (fs/path p))) "\\" "/"))

(defn read-clauses
  "Reads the declared clause lists, includes in place, each key once:
  {:clauses [...] :findings [...] :files [...]}; cannot run on a missing list or one that holds no clause."
  [root cfg]
  (let [lists (get-in cfg [:regulated :clause-lists] [])
        files (atom #{})
        walk (fn walk [file declared stack]
               (let [p (fs/path root file)]
                 (when-not (fs/exists? p) (project/cannot-run! (str "the clause list " file " cannot be read: there is no such file")))
                 (swap! files conj file)
                 (let [{:keys [entries findings]} (parse-list file (slurp (str p)))]
                   (reduce (fn [acc e]
                             (if (= :clause (:kind e))
                               (update acc :clauses conj {:key (:key e) :description (:description e) :clause-list file :line (:line e) :declared declared})
                               (let [target (norm (str (or (some-> (fs/parent file) str) ".") "/" (:target e)))]
                                 (if (some #{target} stack)
                                   (update acc :findings conj (project/finding file (:line e) "clause-include-cycle"
                                                                               (str "the include of " target " leads back to a list already being read")))
                                   (let [sub (walk target declared (conj stack target))]
                                     (-> acc (update :clauses into (:clauses sub)) (update :findings into (:findings sub))))))))
                           {:clauses [] :findings (vec findings)}
                           entries))))
        per (for [l lists] (let [r (walk l l [l])]
                             (when (empty? (:clauses r)) (project/cannot-run! (str "the clause list " l " holds no clause")))
                             r))
        all (vec (mapcat :clauses per))
        seen (atom #{})]
    {:clauses (vec (for [c all :when (not (@seen (:key c)))] (do (swap! seen conj (:key c)) c)))
     :findings (vec (mapcat :findings per))
     :files (vec (sort @files))}))

;; ------------------------------------------------------------------ the stage

(defn index
  "check's stage under the regulated profile: clauses, documents, audit tags; publishes ClausesIndexed."
  [run]
  (let [cfg (:cfg run)]
    (if-not (contains? cfg :regulated)
      {}
      (let [root (:root run)
            {:keys [clauses findings]} (read-clauses root cfg)
            ddir (get-in cfg [:regulated :documents] "dhf/documents")
            docs (documents root ddir)
            tagged (for [d docs t (tag-spans (:text d))] (assoc t :file (:file d)))
            named-at (fn [k] (vec (for [t tagged :when (names? (:text t) k)] {:file (:file t) :line (:line t)})))
            clauses (mapv #(assoc % :named-at (named-at (:key %))) clauses)
            keys* (map :key clauses)
            unknown (for [t tagged
                          :let [ts (tokens (:text t))]
                          :when (and (= 1 (count ts)) (= (str/trim (:text t)) (first ts))
                                     (not-any? #(names? (:text t) %) keys*))]
                      (project/warning (:file t) (:line t) "clause-unknown" (str "the audit tag names " (first ts) ", which no clause list holds")))
            gaps (for [c clauses :when (empty? (:named-at c))]
                   (project/finding (:clause-list c) (:line c) "clause-unreferenced"
                                    (str (:key c) " " (:description c) " is named by no audit tag in a controlled document")))
            all (vec (concat findings gaps (document-findings docs) unknown (mapcat #(placeholder-warnings (:file %) (:text %)) docs)))]
        (bus/publish! :ClausesIndexed :compliance/gap
                      {:revision :working-tree
                       :clauses (mapv #(select-keys % [:key :description :clause-list :line :declared :named-at]) clauses)
                       :documents (mapv #(select-keys % [:file :id :revision :title]) docs)
                       :findings (mapv #(select-keys % [:file :line :rule :message]) all)})
        {:findings all :clauses clauses :documents docs}))))
