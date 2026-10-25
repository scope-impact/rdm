(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the provenance records (the sources index,
  <raw-dir>/sources.edn) and resolves citations against their pins. A stage
  of `rdm-spec check`; it checks the wiki with the Wiki component and
  publishes KnowledgeIndexed.

  A citation is `<source>:<path>@<commit>`. Citations are read from the
  needs (their :sources) and the wiki's files, nothing else. One resolves when
  its source is recorded and not retired, its path is not fenced, its commit
  is one of the source's pins and, with a checkout of the source, the path
  exists at that commit. Checkouts are machine-local, so they are given on
  the command line (--sources DIR), one per source named by its id; without
  one a citation's path is unchecked: a warning, never a pass.

  The rules, each a stable name on the error stream:
    sources-unreadable      the index is missing or not an EDN map
    source-no-repository    a source recording no :repository
    source-no-pin           a source recording no pin
    source-no-record        a source whose record file is not in the raw directory
    source-pin-dropped      a pin an earlier commit of the index recorded,
                            which the index no longer holds (named with the
                            commit that dropped it); a source is retired,
                            never deleted
    source-fenced-missing   a fenced path matching nothing at a pin of a checkout
    citation-malformed      a need's source that is not a citation
    citation-unknown-source a citation naming no recorded source
    citation-retired        a citation of a retired source
    citation-fenced         a citation of a fenced path, or of one inside a
                            fenced directory
    citation-unpinned       a citation at a commit that is not one of its source's pins
    citation-path-missing   a citation whose path does not exist at its pin
    citation-unchecked      (a warning) a citation whose path could not be checked

  KnowledgeIndexed, as dhf/spec/events.edn shapes it; the stage's result is
  {:knowledge {:sources :pages :citations}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Citations

(def ^:private citation-pattern
  #"(?<![\w.-])([A-Za-z0-9][A-Za-z0-9_.-]*):([^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})(?![0-9A-Za-z_])")

(defn parse-citation
  "{:source :path :commit} of the citation `s`, or nil when `s` is not one."
  [s]
  (when-let [[_ source path commit] (re-matches citation-pattern (str/trim (str s)))]
    {:source source :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}]."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [m source path commit] (re-seq citation-pattern l)]
         {:citation m :source source :path path :commit commit :line (inc i)})))

(defn- pinned? [pins commit]
  (let [c (str/lower-case commit)]
    (some (fn [p] (let [p (str/lower-case (str p))]
                    (or (str/starts-with? p c) (str/starts-with? c p))))
          pins)))

(defn fenced-by
  "The fenced entry of `source` that `path` is or lies inside, or nil."
  [source path]
  (let [norm #(str/replace (str/replace (str %) #"^\./" "") #"/+$" "")
        p (norm path)]
    (some (fn [f] (let [f (norm f)]
                    (when (or (= f ".") (= f "") (= p f) (str/starts-with? p (str f "/"))) f)))
          (:fenced source))))

(defn- git [dir & args]
  (try
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args)]
      {:exit (:exit r) :out (:out r) :err (:err r)})
    (catch Exception e {:exit -1 :out "" :err (ex-message e)})))

(defn- commit-in? [dir commit]
  (zero? (:exit (git dir "rev-parse" "--verify" "--quiet" (str commit "^{commit}")))))

(defn- exists-at? [dir commit path]
  (zero? (:exit (git dir "cat-file" "-e" (str commit ":" (str/replace path #"/+$" ""))))))

(defn- checkout-of [sources-dir id]
  (when sources-dir
    (let [d (fs/file sources-dir id)]
      (when (fs/directory? d) (str d)))))

(defn resolve-citation
  "Resolves `citation` against the recorded `sources` ([{:id :repository
  :pins :fenced :retired}]) and the checkouts under `sources-dir` (or nil):
  {:citation :source :path :commit :status :resolved|:unresolved|:unchecked
   :reasons [why] :rule rule-when-unresolved}."
  [sources sources-dir citation]
  (let [{:keys [source path commit] :as parsed} (parse-citation citation)
        src (when parsed (first (filter #(= source (str (:id %))) sources)))
        base (merge {:citation citation :source source :path path :commit commit} {})
        no (fn [rule why] (assoc base :status :unresolved :rule rule :reasons [why]))]
    (cond
      (nil? parsed)
      (no "citation-malformed" "it is not a citation <source>:<path>@<commit>")

      (nil? src)
      (no "citation-unknown-source" (str "no source " source " is recorded in the sources index"))

      (:retired src)
      (no "citation-retired" (str "its source " source " is retired (" (:retired src) "), and nothing may cite a retired source"))

      (fenced-by src path)
      (let [f (fenced-by src path)]
        (no "citation-fenced"
            (if (= f (str/replace path #"/+$" ""))
              (str "the path " path " is fenced in " source ": a source's implementation is never cited")
              (str "the path " path " lies inside the fenced directory " f " of " source
                   ": a source's implementation is never cited"))))

      (not (pinned? (:pins src) commit))
      (no "citation-unpinned" (str "the commit " commit " is not a pin of " source " (its pins: "
                                   (if (seq (:pins src)) (str/join ", " (:pins src)) "none") ")"))

      :else
      (let [co (checkout-of sources-dir source)]
        (cond
          (nil? sources-dir)
          (assoc base :status :unchecked
                 :reasons [(str "no checkout of " source " was given (rdm-spec check --sources DIR), so the path "
                                path " was not checked at " commit)])

          (nil? co)
          (assoc base :status :unchecked
                 :reasons [(str "the sources directory holds no checkout " source "/, so the path " path
                                " was not checked at " commit)])

          (not (commit-in? co commit))
          (assoc base :status :unchecked
                 :reasons [(str "the checkout of " source " does not hold the commit " commit
                                ", so the path " path " was not checked")])

          (exists-at? co commit path)
          (assoc base :status :resolved :reasons [])

          :else
          (no "citation-path-missing" (str "the path " path " does not exist at the pin " commit " of " source)))))))

(defn citation-finding
  "The finding (or warning) for the resolved citation `c` ({:file :line
  ...}), cited by `where` (\"the need UN-004\", \"the footnote [^1]\");
  nil when it resolved."
  [where c]
  (case (:status c)
    :resolved nil
    :unchecked (command/warning (:file c) (:line c) "citation-unchecked"
                                (str where " cites " (:citation c) ", which was not checked: "
                                     (str/join "; " (:reasons c))))
    (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved")
                     (str where " cites " (:citation c) ": " (str/join "; " (:reasons c))))))

;; ---------------------------------------------------------------------------
;; The index

(defn- strip [s] (str/replace (str s) #"/+$" ""))

(defn raw-dir [config] (strip (or (:raw-dir config) "dhf/raw")))

(defn index-rel [config] (str (raw-dir config) "/sources.edn"))

(defn- key-line
  "The line of `text` where the key `id` of the index is written, or nil."
  [text id]
  (let [re (re-pattern (str "(?<![\\w.-]):" (java.util.regex.Pattern/quote (str id)) "(?![\\w.-])"))]
    (some (fn [[i l]] (when (re-find re (str/replace l #";.*$" "")) (inc i)))
          (map-indexed vector (str/split-lines (or text ""))))))

(defn- read-edn-text [text]
  (try {:value (edn/read-string text)} (catch Exception e {:error (ex-message e)})))

(defn- sources-of
  "The index map as [{:id :repository :pins :fenced :retired :record}]."
  [index]
  (vec (for [[k v] (sort-by (comp str key) index)
             :let [v (if (map? v) v {})]]
         {:id (if (keyword? k) (name k) (str k))
          :repository (:repository v)
          :pins (vec (map str (:pins v)))
          :fenced (vec (map str (:fenced v)))
          :retired (:retired v)
          :record (:record v)})))

(defn index-findings
  "The rules a source keeps: a repository, a pin, a record in the raw
  directory (`record?` says whether a record file exists)."
  [rel text sources record?]
  (vec (for [s sources
             :let [line (key-line text (:id s))
                   f (fn [rule msg] (command/finding rel line rule msg))]
             finding (remove nil?
                             [(when (str/blank? (str (:repository s)))
                                (f "source-no-repository" (str "the source " (:id s) " records no :repository")))
                              (when (empty? (:pins s))
                                (f "source-no-pin" (str "the source " (:id s) " records no pin: a source is cited at"
                                                        " a pinned commit, and every pin ever cited stays listed")))
                              (cond
                                (str/blank? (str (:record s)))
                                (f "source-no-record" (str "the source " (:id s) " records no :record, its provenance record"))
                                (not (record? (:record s)))
                                (f "source-no-record" (str "the source " (:id s) "'s record " (:record s)
                                                           " is not in the raw directory")))])]
         finding)))

;; ---------------------------------------------------------------------------
;; Pins, through the index's history

(defn- index-at
  "The index as `commit` holds it, or nil."
  [root commit rel]
  (let [r (git root "show" (str commit ":./" rel))]
    (when (zero? (:exit r))
      (let [{:keys [value]} (read-edn-text (:out r))]
        (when (map? value) value)))))

(defn- history
  "[{:commit :subject}] of the commits that changed the index, oldest first;
  [] outside a git repository."
  [root rel]
  (let [r (git root "log" "--reverse" "--format=%H %s" "--" rel)]
    (if (zero? (:exit r))
      (vec (for [l (str/split-lines (:out r)) :when (not (str/blank? l))
                 :let [[c s] (str/split l #" " 2)]]
             {:commit c :subject (or s "")}))
      [])))

(defn- pins-by-id [index] (into {} (for [s (sources-of index)] [(:id s) (set (:pins s))])))

(defn dropped-pins
  "Pure: every pin some version recorded that the latest version lacks,
  with the version that dropped it: [{:id :pin :by version}], `versions`
  being [{:index map :name ..}] oldest first, the latest last."
  [versions]
  (let [latest (pins-by-id (:index (last versions)))
        drops (atom {})]
    (doseq [[a b] (partition 2 1 versions)
            :let [pa (pins-by-id (:index a)) pb (pins-by-id (:index b))]
            [id pins] pa
            pin pins
            :when (not (contains? (get pb id #{}) pin))]
      (swap! drops assoc [id pin] b))
    (vec (for [[[id pin] v] (sort-by key @drops)
               :when (not (contains? (get latest id #{}) pin))]
           {:id id :pin pin :by v :deleted? (not (contains? latest id))}))))

(defn pin-findings
  "A finding for every pin the index's history recorded and the index no
  longer holds."
  [root rel text index]
  (let [hist (history root rel)
        versions (concat (keep (fn [{:keys [commit subject]}]
                                 (when-let [i (index-at root commit rel)]
                                   {:index i :name (str "the commit " (subs commit 0 7) " (" subject ")")}))
                               hist)
                         [{:index index :name "the working tree"}])]
    (if (< (count versions) 2)
      []
      (vec (for [{:keys [id pin by deleted?]} (dropped-pins (vec versions))]
             (command/finding rel (key-line text id) "source-pin-dropped"
                              (str "the source " id " dropped the pin " pin " in " (:name by)
                                   (when deleted? ", which deleted the source")
                                   ": a pin is never dropped and a source is retired, never deleted,"
                                   " so a citation made against it still resolves")))))))

(defn fenced-findings
  "With checkouts: a finding for every fenced path that matches nothing at
  a pin of its source."
  [rel text sources sources-dir]
  (vec (for [s sources
             :let [co (checkout-of sources-dir (:id s))]
             :when co
             pin (:pins s)
             :when (commit-in? co pin)
             f (:fenced s)
             :when (not (#{"." ""} (strip f)))
             :when (not (exists-at? co pin f))]
         (command/finding rel (key-line text (:id s)) "source-fenced-missing"
                          (str "the fenced path " f " of " (:id s) " matches nothing at the pin " pin)))))

;; ---------------------------------------------------------------------------
;; The needs

(defn- line-of
  "The first line of `text` holding `needle`, at or after the line `from`."
  ([text needle] (line-of text needle 1))
  ([text needle from]
   (some (fn [[i l]] (when (and (>= (inc i) from) (str/includes? l needle)) (inc i)))
         (map-indexed vector (str/split-lines (or text ""))))))

(defn need-citations
  "Every source of every need, resolved: [{citation .. :file :line :need}],
  in the needs' order."
  [rel text needs sources sources-dir]
  (vec (for [[id need] (sort-by (comp str key) needs)
             :when (map? need)
             c (:sources need)]
         (let [at (or (line-of text (pr-str (str id))) 1)]
           (merge (resolve-citation sources sources-dir (str c))
                  {:file rel :line (or (line-of text (pr-str (str c)) at) at)
                   :need (str id)})))))

(defn- need-finding [c]
  (if (= "citation-malformed" (:rule c))
    (command/finding (:file c) (:line c) "citation-malformed"
                     (str "the need " (:need c) " rests on \"" (:citation c)
                          "\", which is not a citation <source>:<path>@<commit>"))
    (citation-finding (str "the need " (:need c)) c)))

;; ---------------------------------------------------------------------------
;; The stage

(defn index
  "Reads and checks the sources, the needs' citations and the wiki of the
  request's project: KnowledgeIndexed's payload."
  [req]
  (let [root (:root req)
        config (:config req)
        rel (index-rel config)
        f (command/path req rel)
        text (when (fs/regular-file? f) (slurp (str f)))
        {:keys [value error]} (when text (read-edn-text text))
        unreadable (cond
                     (nil? text) [(command/finding rel nil "sources-unreadable" (str "there is no sources index " rel))]
                     error [(command/finding rel nil "sources-unreadable" (str "the sources index is not EDN: " error))]
                     (not (map? value)) [(command/finding rel nil "sources-unreadable" "the sources index is not an EDN map")]
                     :else [])
        idx (if (map? value) value {})
        sources (sources-of idx)
        sources-dir (when-let [d (get-in req [:opts :sources])]
                      (let [abs (command/path req d)]
                        (when-not (fs/directory? abs)
                          (command/cannot-run! (str "the sources directory " d " does not exist")))
                        (str abs)))
        record? #(fs/regular-file? (command/path req (str (raw-dir config) "/" %)))
        needs-rel (or (:needs config) "dhf/spec/needs.edn")
        needs-file (command/path req needs-rel)
        needs-text (when (fs/regular-file? needs-file) (slurp (str needs-file)))
        needs (when needs-text (:value (read-edn-text needs-text)))
        needs-cites (if (map? needs) (need-citations needs-rel needs-text needs sources sources-dir) [])
        check-wiki (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki)
        wiki (check-wiki root config {:sources sources :sources-dir sources-dir})
        findings (vec (concat unreadable
                              (when (map? value)
                                (concat (index-findings rel text sources record?)
                                        (pin-findings root rel text idx)
                                        (fenced-findings rel text sources sources-dir)))
                              (keep need-finding needs-cites)
                              (:findings wiki)))
        cite-keys [:citation :source :path :commit :file :line :need :status :reasons]]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced]) sources)
     :pages (vec (:pages wiki))
     :citations (mapv #(select-keys % cite-keys) (concat needs-cites (:citations wiki)))
     :findings findings}))

(defn check
  "The sources stage of `rdm-spec check`: reads the sources, the needs'
  citations and the wiki, publishes KnowledgeIndexed and reports every
  finding. Result {:knowledge {:sources :pages :citations}}."
  [req]
  (let [payload (index req)]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      (update payload :findings (fn [fs*] (mapv #(select-keys % [:file :line :rule :message :severity]) fs*))))
    {:result {:knowledge (select-keys payload [:sources :pages :citations])}
     :findings (:findings payload)}))
