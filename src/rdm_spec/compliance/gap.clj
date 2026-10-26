(ns rdm-spec.compliance.gap
  "Gap analysis (dhf/spec/compliance/gap.feature), under the regulated
  profile only: every clause of every declared clause list must be named by
  an audit tag in a controlled document's body. Audit tags are read outside
  code and never past a blank line. Publishes ClausesIndexed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

;; ---------------------------------------------------------------------------
;; Clause lists

(def key-re #"[A-Za-z0-9._:-]+")

(defn key? [s] (boolean (and (re-matches key-re s) (re-find #"[0-9:]" s))))

(defn clause-line
  "{:key :description} of a clause line, or nil."
  [line]
  (when-let [[_ k d] (re-matches #"([A-Za-z0-9._:-]+)(?:\s+(.*))?" (str/trim line))]
    (when (key? k) {:key k :description (str/trim (or d ""))})))

(defn- normalize [path]
  (str/join "/" (reduce (fn [acc p] (cond (or (= p ".") (= p "")) acc (= p "..") (vec (butlast acc)) :else (conj acc p)))
                        [] (str/split path #"/"))))

(defn read-lists
  "Reads the declared clause lists, includes in place: {:clauses [..] :findings [..]}.
  Cannot run when a list or an include cannot be read, or a declared list holds no clause."
  [root rev lists]
  (let [findings (atom [])
        seen (atom #{})
        read-list (fn read-list [file declared stack]
                    (let [text (or (cmd/read-at root rev file)
                                   (cmd/cannot-run! (str "the clause list " file " cannot be read")))]
                      (vec
                       (mapcat
                        (fn [[i raw]]
                          (let [line (str/trim raw) n (inc i)]
                            (cond
                              (or (str/blank? line) (str/starts-with? line "#")) []
                              (str/starts-with? line "include ")
                              (let [target (normalize (str (some-> (fs/parent file) str (str "/")) (str/trim (subs line 8))))]
                                (if (some #{target} stack)
                                  (do (swap! findings conj (cmd/finding file n "clause-include-cycle"
                                                                        (str "the include of " target " leads back to a list already being read")))
                                      [])
                                  (read-list target declared (conj stack target))))
                              :else
                              (if-let [c (clause-line line)]
                                [(assoc c :clause-list file :line n :declared declared)]
                                (do (swap! findings conj (cmd/finding file n "clause-line-malformed"
                                                                      (str "the line \"" line "\" is no clause (KEY description), include, comment or blank line")))
                                    [])))))
                        (map-indexed vector (str/split-lines text))))))
        clauses (vec (mapcat (fn [l]
                               (let [cs (read-list l l [l])]
                                 (when (empty? cs) (cmd/cannot-run! (str "the clause list " l " holds no clause")))
                                 cs))
                             lists))]
    {:clauses (vec (remove nil? (for [c clauses] (when-not (@seen (:key c)) (swap! seen conj (:key c)) c))))
     :findings @findings}))

;; ---------------------------------------------------------------------------
;; Documents and their audit tags

(defn frontmatter-end
  "The index of the frontmatter's closing line, or nil when there is none."
  [lines]
  (when (= "---" (str/trim (first lines)))
    (first (keep-indexed (fn [i l] (when (and (pos? i) (= "---" (str/trim l))) i)) lines))))

(defn frontmatter [text]
  (let [lines (str/split-lines (str text))]
    (if-let [e (frontmatter-end lines)]
      (into {} (for [l (subvec (vec lines) 1 e) :let [[_ k v] (re-matches #"\s*([A-Za-z_-]+)\s*:\s*(.*)" l)] :when k]
                 [(keyword k) (str/trim (str/replace v #"^\"|\"$" ""))]))
      {})))

(def list-re #"^\s{0,3}([-*+]|\d+[.)])\s.*")

(defn- classify
  "One kind per line: :fm, :blank, :code or :text."
  [lines skip-fm]
  (let [fm-end (frontmatter-end lines)]
    (loop [i 0 out [] fence nil prev-blank true last-kind nil in-list false]
      (if (>= i (count lines))
        out
        (let [l (nth lines i)
              t (str/trim l)
              indented (re-find #"^(    |\t)" l)]
          (cond
            (and fm-end (<= i fm-end))
            (recur (inc i) (conj out (if skip-fm :skip :fm)) nil true nil false)
            fence
            (recur (inc i) (conj out :code) (if (str/starts-with? t fence) nil fence) false :code in-list)
            (str/blank? l)
            (recur (inc i) (conj out :blank) nil true last-kind in-list)
            (and (not indented) (or (str/starts-with? t "```") (str/starts-with? t "~~~")))
            (recur (inc i) (conj out :code) (subs t 0 3) false :code false)
            indented
            (cond
              in-list (recur (inc i) (conj out :text) nil false :text true)
              (or prev-blank (= last-kind :code) (nil? last-kind)) (recur (inc i) (conj out :code) nil false :code false)
              :else (recur (inc i) (conj out :text) nil false :text in-list))
            :else
            (let [li (boolean (re-matches list-re l))]
              (recur (inc i) (conj out :text) nil false :text (or li (and in-list (not prev-blank)))))))))))

(defn- blank-inline-code [s]
  (str/replace s #"(`+)([\s\S]*?)\1" (fn [[m]] (str/replace m #"[^\n]" " "))))

(defn re-seq-pos [re s]
  (let [m (re-matcher re s)]
    (loop [out []]
      (if (.find m)
        (recur (conj out {:start (.start m) :end (.end m) :group (.group m 1)}))
        out))))

(defn tags
  "Every audit tag of a text: [{:text :line :start :end}], outside code,
  never across a blank line; with :skip-frontmatter, none in the frontmatter."
  ([text] (tags text {}))
  ([text {:keys [skip-frontmatter]}]
   (let [text (str text)
         lines (str/split text #"\n" -1)
         offsets (vec (reductions + 0 (map #(inc (count %)) lines)))
         kinds (classify lines skip-frontmatter)
         paras (partition-by #(contains? #{:text :fm} (nth kinds %)) (range (count lines)))]
     (vec
      (for [p paras
            :when (contains? #{:text :fm} (nth kinds (first p)))
            :let [start (offsets (first p))
                  body (blank-inline-code (str/join "\n" (map lines p)))]
            m (re-seq-pos #"(?s)\[\[(.*?)\]\]" body)]
        {:text (:group m)
         :start (+ start (:start m)) :end (+ start (:end m))
         :line (+ (first p) 1 (count (filter #{\newline} (subs body 0 (:start m)))))})))))

(defn tokens [tag-text]
  (for [t (re-seq key-re tag-text)
        :let [t (str/replace t #"[.:]+$" "")]
        :when (key? t)]
    t))

(defn names?
  "Does the token name the clause key (the key, or a dotted descendant)?"
  [token k]
  (or (= token k) (str/starts-with? token (str k "."))))

(defn named-keys [tag-texts clause-keys]
  (set (for [t tag-texts tok (tokens t) k clause-keys :when (names? tok k)] k)))

(defn strip-tags
  "The engineer's copy: every audit tag removed with the space before it,
  its line breaks kept."
  [text]
  (reduce (fn [s {:keys [start end]}]
            (let [b (loop [b start] (if (and (pos? b) (#{\space \tab} (.charAt ^String s (dec b)))) (recur (dec b)) b))]
              (str (subs s 0 b) (apply str (filter #{\newline} (subs s start end))) (subs s end))))
          (str text)
          (reverse (tags text))))

;; ---------------------------------------------------------------------------
;; The stage

(defn analyse
  "The clauses, the documents and every finding, at a revision."
  [root rev config]
  (let [{:keys [clause-lists documents]} (:regulated config)
        {:keys [clauses findings]} (read-lists root rev clause-lists)
        doc-files (filter #(str/ends-with? % ".md") (cmd/files-at root rev (or documents "dhf/documents")))
        docs (vec (for [f doc-files :let [t (cmd/read-at root rev f)]]
                    (merge {:file f :text t} (select-keys (frontmatter t) [:id :revision :title]))))
        keys (map :key clauses)
        all-tags (for [d docs t (tags (:text d) {:skip-frontmatter true})] (assoc t :file (:file d)))
        named-at (fn [k] (vec (for [t all-tags :when (some #(names? % k) (tokens (:text t)))] {:file (:file t) :line (:line t)})))
        clauses (mapv #(assoc % :named-at (named-at (:key %))) clauses)
        by-id (group-by :id (filter :id docs))
        findings (vec
                  (concat
                   findings
                   (for [c clauses :when (empty? (:named-at c))]
                     (cmd/finding (:clause-list c) (:line c) "clause-unreferenced"
                                  (str (:key c) " " (:description c) " is named by no audit tag in a controlled document")))
                   (for [d docs :let [missing (remove #(get d %) [:id :revision :title])] :when (seq missing)]
                     (cmd/finding (:file d) nil "document-unidentified"
                                  (str "the document has no " (str/join ", " (map name missing)) " in its frontmatter: a controlled document carries its id, revision and title")))
                   (for [[id ds] by-id :when (> (count ds) 1) d ds]
                     (cmd/finding (:file d) nil "document-id-duplicate"
                                  (str "the id " id " is used by " (str/join " and " (map :file ds)))))))
        warnings (vec
                  (concat
                   (for [t all-tags
                         :let [txt (str/replace (str/trim (:text t)) #":\s.*$" "")]
                         :when (and (key? txt) (not-any? #(names? txt %) keys))]
                     (cmd/finding (:file t) (:line t) "clause-unknown" (str "the audit tag names " txt ", which no declared clause list holds")))
                   (for [d docs
                         :let [ls (str/split-lines (str (:text d))) fe (or (frontmatter-end ls) -1)]
                         [i l] (map-indexed vector ls)
                         :when (and (> i fe) (re-find #"\bTODO\b" l))]
                     (cmd/finding (:file d) (inc i) "document-placeholder" "the document still holds a placeholder (TODO)"))))]
    {:clauses clauses :documents docs :findings findings :warnings warnings}))

(defn stage [{:keys [root config revision]}]
  (if-not (:regulated config)
    {}
    (let [{:keys [clauses documents findings warnings]} (analyse root revision config)]
      (bus/publish! :compliance/gap :ClausesIndexed
                    {:revision (if (cmd/working-tree? revision) :working-tree revision)
                     :clauses (mapv #(select-keys % [:key :description :clause-list :line :declared :named-at]) clauses)
                     :documents (mapv #(select-keys % [:file :id :revision :title]) documents)
                     :findings (into findings (map #(assoc % :warning? true) warnings))})
      {:findings findings :warnings warnings})))
