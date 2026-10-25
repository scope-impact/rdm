(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context
  (dhf/spec/knowledge/sources.feature): reads the provenance records and
  resolves citations against their pins. Its stage `check` is the first
  stage of `rdm-spec check` (and, for its event, of publish); it also runs
  the Wiki component's rules, since the wiki's footnotes are resolved here.

  The sources index is <raw-dir>/sources.edn (default dhf/raw/sources.edn):

    {:source-id {:repository \"https://...\" :record \"source-id.md\"
                 :pins [\"<commit>\"] :fenced [\"<path>\"] [:retired \"why\"]}}

  A missing index records no source. Citations, <source>:<path>@<commit>,
  are read from the needs' :sources and the wiki's pages and index, and
  nowhere else. Checkouts are given on the command line, --sources DIR,
  one per source at DIR/<source-id>, a git repository in which each pin
  names a commit; without one a citation's path is unchecked, a warning.

  Rules, each a stable name on the error stream:
    sources-unreadable      the sources index is not an EDN map of sources
    source-no-repository    a source with no repository
    source-no-pin           a source with no pin
    source-no-record        a source whose provenance record is not in the raw directory
    source-pin-dropped      a pin an earlier commit of the index held, dropped (names the commit)
    fenced-path-missing     a fenced path that matches nothing at a pin of the source's checkout
    source-pin-missing      a pin the source's checkout does not hold
    citation-malformed      a need's source that is not <source>:<path>@<commit>
    citation-unknown-source a citation naming no recorded source
    citation-retired        a citation of a retired source
    citation-pin            a citation at a commit that is not one of its source's pins
    citation-fenced         a citation of a fenced path, or of a path in a fenced directory
    citation-path-missing   a citation whose path does not exist at its pin in the checkout
    warning citation-unchecked  a citation whose path could not be checked, and why
  and the Wiki component's rules (rdm-spec.knowledge.wiki).

  Publishes KnowledgeIndexed (dhf/spec/events.edn). Result:
  {:knowledge {:sources n :pages n :citations n :resolved n :unresolved n :unchecked n}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.knowledge.wiki :as wiki]))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  "Runs git in `dir`: {:exit :out}; {:exit -1} when git cannot be run."
  [dir & args]
  (try
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args)]
      {:exit (:exit r) :out (:out r)})
    (catch Exception _ {:exit -1 :out ""})))

(defn- git-ok [dir & args]
  (let [r (apply git dir args)]
    (when (zero? (:exit r)) (:out r))))

;; ---------------------------------------------------------------------------
;; The sources index

(defn- kw-name [k] (if (keyword? k) (name k) (str k)))

(defn key-line
  "The line of `text` where the source `id` is keyed, or nil."
  [text id]
  (let [re (re-pattern (str "(^|[\\s{])" (java.util.regex.Pattern/quote (str ":" id)) "(\\s|$|\\{)"))]
    (some (fn [[n l]] (when (and (re-find re l) (not (str/starts-with? (str/triml l) ";"))) n))
          (wiki/lines text))))

(defn parse-index
  "{:sources [{:id :repository :record :pins :fenced :retired :line}] :error}
  from the index's text (nil: no index)."
  [rel text]
  (if (nil? text)
    {:sources []}
    (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
      (cond
        (and (map? v) (::error v)) {:sources [] :error (str "the sources index " rel " is not readable EDN: " (::error v))}
        (not (map? v)) {:sources [] :error (str "the sources index " rel " is not a map of sources, {:id {:repository :record :pins :fenced}}")}
        :else
        {:sources (vec (for [[k s] (sort-by (comp kw-name key) v)
                             :let [s (if (map? s) s {})
                                   id (kw-name k)]]
                         {:id id
                          :repository (:repository s)
                          :record (:record s)
                          :pins (vec (map str (if (sequential? (:pins s)) (:pins s) [])))
                          :fenced (vec (map str (if (sequential? (:fenced s)) (:fenced s) [])))
                          :retired (:retired s)
                          :line (key-line text id)}))}))))

(defn source-findings
  "The findings of each source's own record: repository, pins, record file."
  [rel sources record-exists?]
  (vec
   (apply concat
          (for [{:keys [id repository record pins line]} sources]
            (concat
             (when (str/blank? (str repository))
               [(command/finding rel line "source-no-repository"
                                 (str "the source " id " names no repository: every source is indexed with its repository"))])
             (when (empty? pins)
               [(command/finding rel line "source-no-pin"
                                 (str "the source " id " has no pin: a citation resolves only against a commit it is pinned to"))])
             (cond
               (str/blank? (str record))
               [(command/finding rel line "source-no-record"
                                 (str "the source " id " names no provenance record (:record \"" id ".md\")"))]
               (not (record-exists? record))
               [(command/finding rel line "source-no-record"
                                 (str "the source " id "'s provenance record " record " is not in the raw directory"))]))))))

;; ---------------------------------------------------------------------------
;; Pins are never dropped: the index's history

(defn- history
  "[[commit text]] of every committed version of `rel` in the repository
  holding `root`, oldest first, then [:working-tree text] when the working
  tree differs from the last. Empty when `root` is not in a repository."
  [root rel working-text]
  (if-let [out (git-ok root "log" "--format=%H" "--follow" "--" rel)]
    (let [commits (reverse (remove str/blank? (str/split-lines out)))
          versions (vec (for [c commits
                              :let [t (git-ok root "show" (str c ":./" rel))]
                              :when t]
                          [c t]))]
      (cond-> versions
        (and working-text (not= working-text (second (peek versions)))) (conj [:working-tree working-text])))
    []))

(defn dropped-pins
  "Every pin some version of the index held that the current version does
  not: [{:id :pin :commit}], :commit the first version without it."
  [versions current]
  (let [pins-of (fn [text] (into {} (for [s (:sources (parse-index "" text))] [(:id s) (set (:pins s))])))
        now (into {} (for [s current] [(:id s) (set (:pins s))]))]
    (loop [[[commit text] & more] versions seen {} out []]
      (if (nil? text)
        (vec (distinct (filter (fn [{:keys [id pin]}] (not (contains? (get now id #{}) pin))) out)))
        (let [ps (pins-of text)
              dropped (for [[id pins] seen pin pins
                            :when (not (contains? (get ps id #{}) pin))
                            :when (not (some #(and (= id (:id %)) (= pin (:pin %))) out))]
                        {:id id :pin pin :commit commit})]
          (recur more (merge-with into seen ps) (into out dropped)))))))

(defn- short-commit [root c]
  (if (= :working-tree c)
    "the working tree"
    (str "commit " (or (some-> (git-ok root "log" "-1" "--format=%h %s" c) str/trim) c))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn- checkout-dir [sources-dir id]
  (when sources-dir
    (let [d (fs/file sources-dir id)]
      (when (fs/directory? d) (str d)))))

(defn- pin-commit
  "The commit `pin` names in the checkout `co`, or nil."
  [co pin]
  (some-> (git-ok co "rev-parse" "--verify" "--quiet" (str pin "^{commit}")) str/trim not-empty))

(defn- path-at? [co commit path]
  (or (#{"." ""} path)
      (zero? (:exit (git co "cat-file" "-e" (str commit ":" (str/replace path #"^\./" "")))))))

(defn fenced-findings
  "A fenced path that matches nothing at a pin of a source's checkout; a pin
  the checkout does not hold."
  [rel sources sources-dir display-dir]
  (vec
   (apply concat
          (for [{:keys [id pins fenced line]} sources
                :let [co (checkout-dir sources-dir id)]
                :when co
                pin pins
                :let [c (pin-commit co pin)]]
            (if-not c
              [(command/finding rel line "source-pin-missing"
                                (str "the source " id " is pinned at " pin ", which its checkout " display-dir "/" id
                                     " does not hold"))]
              (for [f fenced :when (not (path-at? co c f))]
                (command/finding rel line "fenced-path-missing"
                                 (str "the source " id " fences \"" f "\", which matches nothing at its pin " pin
                                      " in the checkout " display-dir "/" id))))))))

;; ---------------------------------------------------------------------------
;; Resolving a citation

(def need-citation-re
  "A need's source, written whole: <source>:<path>@<commit>."
  #"([A-Za-z][A-Za-z0-9_-]*):([^\s@]+)@([0-9a-f]{7,40})")

(defn- fenced-by
  "The fenced entry of `fenced` covering `path`, or nil."
  [fenced path]
  (let [p (str/replace path #"^\./" "")]
    (first (filter (fn [f]
                     (let [f (str/replace (str/replace f #"^\./" "") #"/+$" "")]
                       (or (= f ".") (= f "") (= p f) (str/starts-with? p (str f "/")))))
                   fenced))))

(defn resolve-citation
  "{:status :resolved | :unresolved | :unchecked :reasons [{:rule :why}]}
  of the citation {:source :path :commit} against `by-id` (the sources by
  id) and the checkouts under `sources-dir` (nil: none given)."
  [{:keys [source path commit]} by-id sources-dir display-dir]
  (let [s (get by-id source)]
    (cond
      (nil? s)
      {:status :unresolved
       :reasons [{:rule "citation-unknown-source"
                  :why (str "no source " source " is recorded in the sources index")}]}

      :else
      (let [reasons (cond-> []
                      (:retired s)
                      (conj {:rule "citation-retired"
                             :why (str "its source " source " is retired (" (:retired s) "), and nothing may cite a retired source")})
                      (not (some #{commit} (:pins s)))
                      (conj {:rule "citation-pin"
                             :why (str "the commit " commit " is not a pin of " source ", whose pins are "
                                       (if (seq (:pins s)) (str/join ", " (:pins s)) "none"))})
                      (fenced-by (:fenced s) path)
                      (conj (let [f (fenced-by (:fenced s) path)]
                              {:rule "citation-fenced"
                               :why (str (if (= (str/replace f #"/+$" "") (str/replace path #"^\./" ""))
                                           "the path "
                                           "it lies in the fenced directory ")
                                         "\"" f "\" of " source ", which is fenced: a source's implementation is never cited")})))]
        (if (seq reasons)
          {:status :unresolved :reasons reasons}
          (let [co (checkout-dir sources-dir source)]
            (cond
              (nil? sources-dir)
              {:status :unchecked
               :reasons [{:rule "citation-unchecked"
                          :why (str "its path was not checked: no checkout was given (rdm-spec check --sources DIR, DIR/"
                                    source " a checkout of " source ")")}]}
              (nil? co)
              {:status :unchecked
               :reasons [{:rule "citation-unchecked"
                          :why (str "its path was not checked: --sources " display-dir " holds no checkout " source "/")}]}
              :else
              (let [c (pin-commit co commit)]
                (cond
                  (nil? c)
                  {:status :unresolved
                   :reasons [{:rule "citation-path-missing"
                              :why (str "the checkout " display-dir "/" source " does not hold the pin " commit)}]}
                  (not (path-at? co c path))
                  {:status :unresolved
                   :reasons [{:rule "citation-path-missing"
                              :why (str "the path " path " does not exist at the pin " commit " in the checkout "
                                        display-dir "/" source)}]}
                  :else {:status :resolved :reasons []})))))))))

;; ---------------------------------------------------------------------------
;; Citations of the needs and the wiki

(defn- read-text [root rel]
  (let [f (command/path root rel)]
    (when (fs/regular-file? f) (slurp (str f)))))

(defn need-citations
  "Every source of every need: [{:citation :need :file :line :malformed?
  :source :path :commit}]. The needs file unreadable gives none (the spec
  check reports it)."
  [needs-rel text]
  (let [v (when text (try (edn/read-string text) (catch Exception _ nil)))
        ls (vec (wiki/lines text))
        line-of (fn [need cited]
                  (let [start (or (some (fn [[n l]] (when (str/includes? l (pr-str need)) n)) ls) 1)]
                    (or (some (fn [[n l]] (when (and (>= n start) (str/includes? l (pr-str cited))) n)) ls)
                        start)))]
    (if-not (map? v)
      []
      (vec (for [[k n] (sort-by (comp str key) v)
                 :let [need (if (keyword? k) (name k) (str k))
                       srcs (:sources (if (map? n) n {}))]
                 :when (sequential? srcs)
                 cited srcs
                 :let [cited (str cited)
                       m (re-matches need-citation-re cited)]]
             (merge {:citation cited :need need :file needs-rel :line (line-of need cited)}
                    (if m
                      {:source (nth m 1) :path (nth m 2) :commit (nth m 3)}
                      {:malformed? true})))))))

(defn- citation-finding
  "The finding (or warning) of a citation that did not resolve."
  [{:keys [citation need footnote file line]} {:keys [rule why]} unchecked?]
  (let [who (cond need (str "the need " need " cites ")
                  footnote (str "the footnote " footnote " cites ")
                  :else "the page cites ")
        msg (str who citation (if unchecked? ": " ", which does not resolve: ") why)]
    (if unchecked?
      (command/warning file line rule msg)
      (command/finding file line rule msg))))

(defn index-knowledge
  "Reads the sources, the needs' and the wiki's citations, and resolves them:
  {:sources :pages :citations :findings :revision}."
  [root config {:keys [sources-dir display-dir]}]
  (let [cfg {:config config}
        raw-dir (str/replace (command/config-value cfg :raw-dir) #"/+$" "")
        index-rel (str raw-dir "/sources.edn")
        index-text (read-text root index-rel)
        {:keys [sources error]} (parse-index index-rel index-text)
        by-id (into {} (map (juxt :id identity)) sources)
        record? (fn [rec] (fs/regular-file? (command/path root (str raw-dir "/" rec))))
        dropped (when index-text (dropped-pins (history root index-rel index-text) sources))
        needs-rel (command/config-value cfg :needs)
        needs (need-citations needs-rel (read-text root needs-rel))
        wiki-dir (command/config-value cfg :wiki-dir)
        w (wiki/read-wiki root wiki-dir)
        wiki-cites (vec (mapcat (fn [{:keys [file text]}] (wiki/citations-in file text))
                                (concat (when (:index w) [(:index w)]) (:pages w))))
        resolved (vec (for [c (concat needs wiki-cites)]
                        (if (:malformed? c)
                          (assoc c :status :unresolved
                                 :reasons [{:rule "citation-malformed"
                                            :why (str "\"" (:citation c) "\" is not a citation, <source>:<path>@<commit>")}])
                          (merge c (resolve-citation c by-id sources-dir display-dir)))))
        findings (vec
                  (concat
                   (when error [(command/finding index-rel "sources-unreadable" error)])
                   (source-findings index-rel sources record?)
                   (for [{:keys [id pin commit]} dropped]
                     (command/finding index-rel (get-in by-id [id :line]) "source-pin-dropped"
                                      (str "the source " id " dropped its pin " pin " in " (short-commit root commit)
                                           ": a pin is never dropped (retire a source with :retired instead)")))
                   (fenced-findings index-rel sources sources-dir display-dir)
                   (wiki/wiki-findings root wiki-dir w (:stack-words config))
                   (for [c resolved
                         :when (= :unresolved (:status c))
                         reason (:reasons c)]
                     (if (:malformed? c)
                       (command/finding (:file c) (:line c) "citation-malformed"
                                        (str "the need " (:need c) " cites \"" (:citation c)
                                             "\", which is not a citation: write <source>:<path>@<commit>"))
                       (citation-finding c reason false)))
                   (for [c resolved
                         :when (= :unchecked (:status c))
                         reason (:reasons c)]
                     (citation-finding c reason true))))]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced]) sources)
     :pages (mapv :file (:pages w))
     :citations (mapv (fn [c] {:citation (:citation c) :source (:source c) :path (:path c) :commit (:commit c)
                               :file (:file c) :line (:line c) :need (:need c) :status (:status c)
                               :reasons (mapv :why (:reasons c))})
                      resolved)
     :findings findings}))

;; ---------------------------------------------------------------------------
;; The stage

(defn check
  "The Sources stage: see the namespace doc."
  [req]
  (let [given (get-in req [:opts :sources])
        sources-dir (when given
                      (let [d (command/path req given)]
                        (when-not (fs/directory? d)
                          (command/cannot-run! (str "--sources " given ": no such directory of source checkouts")))
                        (str d)))
        k (index-knowledge (:root req) (:config req) {:sources-dir sources-dir :display-dir given})
        cs (:citations k)
        n (fn [st] (count (filter #(= st (:status %)) cs)))]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      {:revision (:revision k)
                       :sources (:sources k)
                       :pages (:pages k)
                       :citations (:citations k)
                       :findings (mapv #(select-keys % [:file :line :rule :message]) (:findings k))})
    {:result {:knowledge {:sources (count (:sources k)) :pages (count (:pages k)) :citations (count cs)
                          :resolved (n :resolved) :unresolved (n :unresolved) :unchecked (n :unchecked)}}
     :findings (:findings k)}))
