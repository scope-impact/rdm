(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (sources.edn in the configured
  :raw-dir), the needs' citations and, through the Wiki component, the wiki's,
  and resolves every citation against the index's pins and fences and, when
  `--sources DIR` is given, against the source's checkout, DIR/<source id>.
  It is the first stage of `rdm-spec check` and `rdm-spec publish`, and
  publishes KnowledgeIndexed.

  A citation is `<source>:<path>@<commit>`. It resolves when its source is
  recorded, its path is not fenced, its commit is one of the source's pins and
  its path exists at that commit in the source's checkout. Without a checkout
  its path cannot be checked: it is reported :unchecked, with the reason, as a
  warning and never as a pass.

  The rules, each a stable name on the error stream:
    sources-unreadable      the sources index is not an EDN map of sources
    source-no-repository    a source with no repository
    source-no-pin           a source with no pin
    source-no-record        a source whose provenance record is missing
    source-pin-dropped      a pin a commit of the index's history (or the
                            working tree) no longer lists
    source-fence-unmatched  a fenced path that matches nothing at a pin
    citation-malformed      a need's source that is not a citation
    citation-unknown-source a citation of a source the index does not record
    citation-fenced         a citation of a fenced path, or inside a fenced
                            directory
    citation-unpinned       a citation at a commit that is not a pin
    citation-path-missing   a citation whose path does not exist at its pin
    citation-unchecked      (warning) a citation whose path could not be checked"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def index-name "sources.edn")

;; ---------------------------------------------------------------------------
;; Citations

(def citation-pattern
  "A citation in running text: <source>:<path>@<commit>. Not inside a URL
  (no // after the colon) and not glued to a longer word."
  #"(?<![\w/.@:-])([A-Za-z0-9][\w.-]*):((?!/)[^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})(?![\w])")

(defn parse-citation
  "{:citation :source :path :commit} of the citation `s` (the whole string),
  or nil when it is not one."
  [s]
  (when-let [[whole source path commit] (re-matches citation-pattern (str/trim (str s)))]
    {:citation whole :source source :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}], in
  the order written."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [whole source path commit] (re-seq citation-pattern l)]
         {:citation whole :source source :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; The sources index

(defn- line-of
  "The 1-based line of `lines` that first satisfies `pred` at or after
  line `from` (1-based), else nil."
  ([lines pred] (line-of lines pred 1))
  ([lines pred from]
   (some (fn [[i l]] (when (and (>= (inc i) from) (pred l)) (inc i)))
         (map-indexed vector lines))))

(defn- key-line [lines k]
  (let [re (re-pattern (str "(?:^|[\\s{,])" (java.util.regex.Pattern/quote (str k)) "(?=[\\s{,]|$)"))]
    (line-of lines #(and (not (str/starts-with? (str/triml %) ";")) (re-find re %)))))

(defn- strings [x] (if (sequential? x) (vec (keep #(when (some? %) (str %)) x)) []))

(defn read-index
  "The sources index `text` read: {:sources [{:id :repository :record :pins
  :fenced :line}] :error message-or-nil}; :id is the source's name, a string."
  [text]
  (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))
        lines (str/split-lines (or text ""))]
    (cond
      (::error v) {:sources [] :error (str "is not readable EDN: " (::error v))}
      (nil? v) {:sources [] :error nil}
      (not (map? v)) {:sources [] :error "is not a map of sources, {:source-id {:repository :record :pins :fenced}}"}
      :else
      {:sources (vec (for [[k s] v
                           :let [s (if (map? s) s {})]]
                       {:id (if (keyword? k) (name k) (str k))
                        :repository (some-> (:repository s) str)
                        :record (some-> (:record s) str)
                        :pins (strings (:pins s))
                        :fenced (strings (:fenced s))
                        :line (key-line lines k)}))
       :error nil})))

(defn- pins-of
  "{source-id #{pin}} of the index `text`, or nil when it is unreadable."
  [text]
  (let [{:keys [sources error]} (read-index text)]
    (when-not error
      (into {} (for [s sources] [(:id s) (set (:pins s))])))))

(defn- source-findings
  "The rules every recorded source keeps."
  [index-rel raw-dir-file sources]
  (vec (mapcat (fn [{:keys [id repository record pins line]}]
                 (concat
                  (when (str/blank? repository)
                    [(command/finding index-rel line "source-no-repository"
                                      (str "the source " id " names no repository"))])
                  (when (empty? pins)
                    [(command/finding index-rel line "source-no-pin"
                                      (str "the source " id " has no pin; a source is cited at a commit it is pinned to"))])
                  (cond
                    (str/blank? record)
                    [(command/finding index-rel line "source-no-record"
                                      (str "the source " id " names no provenance record"))]
                    (not (fs/regular-file? (fs/file raw-dir-file record)))
                    [(command/finding index-rel line "source-no-record"
                                      (str "the source " id "'s provenance record " record " does not exist beside the index"))])))
               sources)))

;; ---------------------------------------------------------------------------
;; Git

(defn- git
  "Runs git in `dir`: {:exit :out}, never throwing (exit -1 when git cannot
  be run)."
  [dir args & [in]]
  (try
    (let [r (apply p/shell (cond-> {:dir (str dir) :out :string :err :string :continue true}
                             in (assoc :in in))
                   "git" args)]
      {:exit (:exit r) :out (:out r)})
    (catch Exception _ {:exit -1 :out ""})))

;; ---------------------------------------------------------------------------
;; Pins dropped in the index's history

(defn- index-history
  "The commits touching the index `rel` in the repository at `root`, oldest
  first: [{:commit :parents [..] :subject}]; [] when it is no repository."
  [root rel]
  (let [{:keys [exit out]} (git root ["log" "--topo-order" "--reverse" "--parents" "--format=%H %P%x09%s" "--" rel])]
    (if (zero? exit)
      (vec (for [l (str/split-lines out)
                 :when (not (str/blank? l))
                 :let [[shas subject] (str/split l #"\t" 2)
                       [c & ps] (str/split (str/trim shas) #" ")]]
             {:commit c :parents (vec ps) :subject (or subject "")}))
      [])))

(defn- index-at
  "{source #{pin}} of the index `rel` at `commit`: {} when the commit does not
  hold it, nil when it is unreadable there."
  [root rel commit]
  (let [{:keys [exit out]} (git root ["show" (str commit ":" rel)])]
    (if (zero? exit) (pins-of out) {})))

(defn dropped-pins
  "Every pin some version of the index listed and the current one (`now`,
  {source #{pin}}) does not: [{:source :pin :commit :subject}], :commit being
  the last commit that dropped it (nil: the working tree, uncommitted)."
  [root rel now]
  (let [history (index-history root (str/replace rel "\\" "/"))
        versions (atom {})
        version (fn [c] (or (get @versions c)
                            (let [v (or (index-at root rel c) {})] (swap! versions assoc c v) v)))
        head (some-> (git root ["rev-parse" "HEAD"]) :out str/trim not-empty)
        steps (concat
               (for [{:keys [commit parents subject]} history
                     parent parents]
                 [parent commit subject])
               (when (and head (seq history))
                 [[head nil "the working tree"]]))
        drops (reduce (fn [acc [parent child subject]]
                        (let [before (version parent)
                              after (if child (version child) now)]
                          (into acc (for [[src pins] (sort-by key before)
                                          pin (sort pins)
                                          :when (not (contains? (get after src #{}) pin))]
                                      [[src pin] {:source src :pin pin :commit child :subject subject}]))))
                      {} steps)]
    (vec (for [[[src pin] d] (sort-by key drops)
               :when (not (contains? (get now src #{}) pin))]
           d))))

(defn- drop-findings [index-rel sources drops]
  (let [line (into {} (map (juxt :id :line) sources))]
    (vec (for [{:keys [source pin commit subject]} drops]
           (command/finding index-rel (get line source) "source-pin-dropped"
                            (str "the source " source " no longer lists the pin " pin ", dropped by "
                                 (if commit
                                   (str "the commit " (subs commit 0 (min 7 (count commit))) " (\"" subject "\")")
                                   "the working tree, uncommitted")
                                 "; a pin is never dropped: a citation made against it must still resolve"))))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn- checkout-state
  "What a source's checkout can answer: {:dir :reason}, :reason (why its
  paths cannot be checked) nil when it is a git checkout."
  [sources-dir sources-arg id]
  (cond
    (nil? sources-dir)
    {:reason (str "no checkout of " id " was given with --sources")}

    (not (fs/directory? (fs/file sources-dir id)))
    {:reason (str "no checkout of " id " is in " sources-arg " (" sources-arg "/" id ")")}

    :else
    (let [dir (str (fs/file sources-dir id))
          {:keys [exit]} (git dir ["rev-parse" "--git-dir"])]
      (if (zero? exit)
        {:dir dir}
        {:reason (str sources-arg "/" id " is not a git checkout of " id)}))))

(defn- object-types
  "{expression type-or-nil} for each object expression (`rev:path`,
  `rev^{commit}`) in the checkout `dir`, asked in one batch."
  [dir exprs]
  (let [exprs (vec (distinct exprs))]
    (if (empty? exprs)
      {}
      (let [{:keys [exit out]} (git dir ["cat-file" "--batch-check"] (str (str/join "\n" exprs) "\n"))
            lines (str/split-lines (or out ""))]
        (if (and (zero? exit) (= (count lines) (count exprs)))
          (into {} (map (fn [e l]
                          [e (second (re-matches #"[0-9a-f]{7,64} (\w+) \d+" l))])
                        exprs lines))
          (into {} (for [e exprs]
                     [e (let [r (git dir ["cat-file" "-t" e])]
                          (when (zero? (:exit r)) (str/trim (:out r))))])))))))

(defn make-resolver
  "A context for resolving citations: the index's `sources` and the
  directory of checkouts `sources-dir` (a file, or nil) given as
  `sources-arg`. Answers about checkouts are asked once and kept."
  [sources sources-dir sources-arg]
  {:sources (into {} (map (juxt :id identity) sources))
   :sources-dir sources-dir
   :sources-arg (or sources-arg (some-> sources-dir str))
   :checkouts (atom {})
   :objects (atom {})})

(defn- checkout [resolver id]
  (or (get @(:checkouts resolver) id)
      (let [st (checkout-state (:sources-dir resolver) (:sources-arg resolver) id)]
        (swap! (:checkouts resolver) assoc id st)
        st)))

(defn- object-type
  "The type of `expr` in the checkout of `id`, or nil when it has none."
  [resolver id dir expr]
  (let [k [id expr]]
    (if (contains? @(:objects resolver) k)
      (get @(:objects resolver) k)
      (let [t (get (object-types dir [expr]) expr)]
        (swap! (:objects resolver) assoc k t)
        t))))

(defn prefetch!
  "Asks each checkout, in one batch, about every expression in `by-source`
  ({id [expr]}), so resolving needs no further process."
  [resolver by-source]
  (doseq [[id exprs] by-source
          :let [{:keys [dir]} (checkout resolver id)]
          :when dir
          :let [todo (remove #(contains? @(:objects resolver) [id %]) exprs)]
          :when (seq todo)
          [e t] (object-types dir todo)]
    (swap! (:objects resolver) assoc [id e] t)))

;; ---------------------------------------------------------------------------
;; Resolving

(defn- pin-match?
  "True when the commit `c` is the pin `p`: equal, or one an abbreviation of
  the other."
  [c p]
  (let [c (str/lower-case c) p (str/lower-case p)]
    (or (= c p) (str/starts-with? c p) (str/starts-with? p c))))

(defn fenced-by
  "The fenced path of `fenced` that `path` is, or lies inside; else nil."
  [fenced path]
  (let [norm #(str/replace (str/replace (str %) #"^\./+" "") #"/+$" "")
        path (norm path)]
    (some (fn [f] (let [f (norm f)]
                    (when (and (not (str/blank? f)) (or (= path f) (str/starts-with? path (str f "/")))) f)))
          fenced)))

(defn- outcome [status rule reason] {:status status :rule rule :reasons [reason]})

(defn- resolve-with
  [resolver {:keys [citation source path commit] :as parts}]
  (let [src (get (:sources resolver) source)
        result
        (cond
          (nil? src)
          (outcome :unresolved "citation-unknown-source"
                   (str "no source " source " is recorded in the sources index"))

          (fenced-by (:fenced src) path)
          (let [f (fenced-by (:fenced src) path)]
            (outcome :unresolved "citation-fenced"
                     (if (= f (str/replace path #"/+$" ""))
                       (str "the path " path " is fenced in " source "; a source's implementation is not cited")
                       (str "the path " path " is inside the fenced directory " f " of " source
                            "; a source's implementation is not cited"))))

          (not-any? #(pin-match? commit %) (:pins src))
          (outcome :unresolved "citation-unpinned"
                   (str "the commit " commit " is not a pin of " source ", whose pins are "
                        (if (seq (:pins src)) (str/join ", " (:pins src)) "none")))

          :else
          (let [pin (or (first (filter #(= (str/lower-case commit) (str/lower-case %)) (:pins src)))
                        (first (filter #(pin-match? commit %) (:pins src))))
                {:keys [dir reason]} (checkout resolver source)]
            (cond
              reason
              (outcome :unchecked "citation-unchecked" (str "not checked: " reason))

              (not (object-type resolver source dir (str commit "^{commit}")))
              (outcome :unchecked "citation-unchecked"
                       (str "not checked: the checkout of " source " does not hold the commit " commit))

              (not (object-type resolver source dir (str commit ":" path)))
              (outcome :unresolved "citation-path-missing"
                       (str "the path " path " does not exist at " commit
                            (when (not= pin commit) (str " (the pin " pin ")"))
                            " in the checkout of " source))

              :else {:status :resolved :rule nil :reasons []})))]
    (merge {:citation citation :source source :path path :commit commit} result)))

(defn resolve-citation
  "Resolves the citation `citation` (a string) against the recorded `sources`
  ([{:id :pins :fenced ...}]) and the checkouts under `sources-dir` (or nil),
  or against a resolver (make-resolver): {:citation :source :path :commit
  :status :reasons :rule}, :status one of :resolved, :unresolved and
  :unchecked, :rule the finding's rule (nil when resolved)."
  ([resolver citation]
   (if-let [parts (parse-citation citation)]
     (resolve-with resolver parts)
     {:citation (str citation) :source nil :path nil :commit nil :status :unresolved
      :rule "citation-malformed"
      :reasons [(str "\"" citation "\" is not a citation, <source>:<path>@<commit>")]}))
  ([sources sources-dir citation]
   (let [resolver (if (and (map? sources) (:checkouts sources))
                    sources
                    (make-resolver sources (some-> sources-dir fs/file) (some-> sources-dir str)))]
     (resolve-citation resolver citation))))

(defn citation-finding
  "The finding for the resolved citation `c` (with :file and :line) made by
  `who` (\"UN-004\", \"the footnote [^1]\"), or nil when it resolved; a
  citation that could not be checked is a warning."
  [who c]
  (case (:status c)
    :resolved nil
    :unchecked (command/warning (:file c) (:line c) (or (:rule c) "citation-unchecked")
                                (str who " cites " (:citation c) ", which was " (str/join "; " (:reasons c))))
    (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved")
                     (str who " cites " (:citation c) ", which does not resolve: " (str/join "; " (:reasons c))))))

;; ---------------------------------------------------------------------------
;; Fences at the pins

(defn- fence-findings
  "A fenced path that matches nothing at a pin, for every source whose
  checkout holds that pin."
  [resolver index-rel sources]
  (vec (for [{:keys [id pins fenced line]} sources
             :let [{:keys [dir]} (checkout resolver id)]
             :when dir
             pin pins
             :when (object-type resolver id dir (str pin "^{commit}"))
             f fenced
             :when (not (object-type resolver id dir (str pin ":" (str/replace f #"/+$" ""))))]
         (command/finding index-rel line "source-fence-unmatched"
                          (str "the source " id " fences " f ", which matches nothing at its pin " pin
                               " in the checkout; a fence names a path that exists")))))

;; ---------------------------------------------------------------------------
;; The needs

(defn- read-needs
  "The needs of the needs file `f` (relative `rel`): [{:id :sources :line}]
  in the file's order, or [] when it is missing or unreadable (the spec check
  reports that)."
  [f]
  (if-not (fs/regular-file? f)
    []
    (let [text (slurp (str f))
          v (try (edn/read-string text) (catch Exception _ nil))
          lines (str/split-lines text)]
      (if-not (map? v)
        []
        (->> (for [[k n] v
                   :let [id (if (keyword? k) (name k) (str k))
                         line (key-line lines (pr-str k))]]
               {:id id :line line :sources (if (map? n) (:sources n) nil) :lines lines})
             (sort-by (juxt #(or (:line %) Long/MAX_VALUE) :id))
             vec)))))

(defn need-citations
  "Every citation of the needs, resolved: [{:citation :source :path :commit
  :file :line :need :status :reasons :rule}]."
  [resolver needs-rel needs]
  (vec (for [{:keys [id line sources lines]} needs
             :when (sequential? sources)
             s sources
             :let [s (str s)
                   at (or (line-of lines #(str/includes? % (pr-str s)) (or line 1))
                          (line-of lines #(str/includes? % (pr-str s)))
                          line)]]
         (merge (resolve-citation resolver s) {:file needs-rel :line at :need id}))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- slash [s] (str/replace (str s) #"/+$" ""))

(defn- sources-dir-of
  "The --sources directory, or nil; cannot run when it is given and is not a
  directory."
  [req]
  (when-let [d (not-empty (str (or (get-in req [:opts :sources]) "")))]
    (let [f (command/path req d)]
      (when-not (fs/directory? f)
        (command/cannot-run! (str "the sources directory " d " given with --sources does not exist")))
      f)))

(defn- wiki-citations
  "Every citation written in the wiki's Markdown files (for asking the
  checkouts ahead; the Wiki component reads the wiki)."
  [req]
  (let [dir (command/path req (slash (or (get-in req [:config :wiki-dir]) "dhf/wiki")))]
    (if (fs/directory? dir)
      (vec (mapcat #(citations-in (slurp (str %))) (fs/glob dir "**.md")))
      [])))

(defn- event-citation [c]
  (select-keys c [:citation :source :path :commit :file :line :need :status :reasons]))

(defn index-knowledge
  "Reads the sources, the needs and the wiki of the project of `req` and
  resolves every citation: {:revision :sources :pages :citations :findings},
  KnowledgeIndexed's payload."
  [req]
  (let [config (:config req)
        raw-dir (slash (or (:raw-dir config) "dhf/raw"))
        index-rel (str raw-dir "/" index-name)
        index-file (command/path req index-rel)
        index-text (when (fs/regular-file? index-file) (slurp (str index-file)))
        {:keys [sources error]} (if index-text (read-index index-text) {:sources [] :error nil})
        sources-arg (get-in req [:opts :sources])
        sdir (sources-dir-of req)
        resolver (make-resolver sources sdir sources-arg)
        needs-rel (slash (or (:needs config) "dhf/spec/needs.edn"))
        needs (read-needs (command/path req needs-rel))
        ;; ask each checkout once, about everything
        _ (when sdir
            (prefetch! resolver
                       (merge-with into
                                   (into {} (for [s sources]
                                              [(:id s) (vec (concat (for [p (:pins s)] (str p "^{commit}"))
                                                                    (for [p (:pins s) f (:fenced s)]
                                                                      (str p ":" (slash f)))))]))
                                   (reduce (fn [m c] (update m (:source c) (fnil conj [])
                                                             (str (:commit c) "^{commit}")
                                                             (str (:commit c) ":" (:path c))))
                                           {}
                                           (concat
                                            (keep parse-citation (mapcat #(when (sequential? (:sources %)) (:sources %)) needs))
                                            (wiki-citations req))))))
        need-cites (need-citations resolver needs-rel needs)
        check-wiki (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki)
        wiki (check-wiki (:root req) config {:sources resolver :sources-dir sdir})
        drops (if (and index-text (nil? error))
                (dropped-pins (:root req) index-rel (into {} (for [s sources] [(:id s) (set (:pins s))])))
                [])
        findings (vec (concat
                       (when error [(command/finding index-rel nil "sources-unreadable" (str "the sources index " error))])
                       (source-findings index-rel (command/path req raw-dir) sources)
                       (drop-findings index-rel sources drops)
                       (fence-findings resolver index-rel sources)
                       (keep #(citation-finding (:need %) %) need-cites)
                       (:findings wiki)))]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced]) sources)
     :pages (vec (:pages wiki))
     :citations (vec (concat (map event-citation need-cites) (map event-citation (:citations wiki))))
     :findings findings}))

(defn check
  "The sources stage of `rdm-spec check` and `rdm-spec publish`: indexes the
  knowledge, publishes KnowledgeIndexed and reports its findings."
  [req]
  (let [k (index-knowledge req)]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      (update k :findings (fn [fs*] (mapv #(select-keys % [:file :line :rule :message]) fs*))))
    {:result {:knowledge (dissoc k :findings)}
     :findings (:findings k)}))
