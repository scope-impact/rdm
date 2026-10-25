(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (sources.edn in the configured
  :raw-dir) and resolves every citation of the needs and the wiki against it,
  and, when checkouts are given (--sources DIR, one checkout per source named
  by its id), against the cited file at its pin.

  A citation is `<source>:<path>@<commit>`. It resolves when its source is
  recorded and not retired, its commit is one of the source's pins, its path
  is not fenced, and its path exists at that pin in the source's checkout.
  Without a checkout, a citation that passes the rest is unchecked, never
  resolved: a warning, with the reason.

  `check` is the knowledge context's stage of `rdm-spec check` (and, quiet,
  of publish): the index's own rules, the index's history (a pin is never
  dropped), the needs' citations, then the wiki (rdm-spec.knowledge.wiki,
  whose footnotes are resolved here). It publishes KnowledgeIndexed.

  The rules, each a stable name on the error stream:
    sources-unreadable      the index is missing or not an EDN map of sources
    source-unreadable       a source's entry is not a map
    source-no-repository    a source with no repository
    source-no-pin           a source with no pin
    source-no-record        a source whose record file is not in the raw directory
    source-pin-dropped      a pin a commit of the index listed and the index no longer does
    fenced-path-missing     a fenced path that matches nothing at a pin of a checkout
    citation-malformed      a need's source that is not a citation
    citation-unknown-source a citation naming no recorded source
    citation-retired        a citation of a retired source
    citation-unpinned       a citation at a commit that is not one of its source's pins
    citation-fenced         a citation of a fenced path, or of a path inside one
    citation-path-missing   a cited path that does not exist at its pin
  and these warnings:
    citation-unchecked      a citation whose path could not be checked, and why
    fenced-path-unchecked   a fenced path no checkout could check at a pin
    sources-history-unchecked  the index's history could not be read"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def index-name "sources.edn")

;; ---------------------------------------------------------------------------
;; Citations

(def citation-pattern
  "A citation inside text: source, path and commit."
  #"(?<![\w.-])([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>/][^\s@`\"'()\[\]<>]*)@([0-9a-fA-F]{4,40})(?![0-9A-Za-z])")

(def ^:private whole-citation
  #"([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>/][^\s@`\"'()\[\]<>]*)@([0-9a-fA-F]{4,40})")

(defn parse-citation
  "{:citation :source :path :commit} of a citation written alone, or nil
  when `s` is not one."
  [s]
  (when (string? s)
    (when-let [[c src path commit] (re-matches whole-citation (str/trim s))]
      {:citation c :source src :path path :commit commit})))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}], in
  the order they appear."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [c src path commit] (re-seq citation-pattern l)]
         {:citation c :source src :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  "Runs git in `dir`: {:exit :out :err}; exit -1 when git cannot be run."
  [dir & args]
  (try (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args)]
         {:exit (:exit r) :out (:out r) :err (:err r)})
       (catch Exception e {:exit -1 :out "" :err (ex-message e)})))

(defn- exists-at?
  "true / false whether `path` exists at `pin` in the checkout `co`; nil
  when the checkout holds no commit `pin`."
  [co pin path]
  (when (zero? (:exit (git co "rev-parse" "--verify" "--quiet" (str pin "^{commit}"))))
    (or (= "." path)
        (zero? (:exit (git co "cat-file" "-e" (str pin "^{commit}:" (str/replace path #"^\./" ""))))))))

;; ---------------------------------------------------------------------------
;; Resolving

(defn fenced-by
  "The fenced entry of `source` that `path` is or lies inside, or nil."
  [source path]
  (let [p (str/replace (str path) #"^\./|/+$" "")]
    (some (fn [f]
            (let [f* (str/replace (str f) #"^\./|/+$" "")]
              (when (or (= "." f) (= "" f*) (= p f*) (str/starts-with? p (str f* "/")))
                f)))
          (:fenced source))))

(defn checkout-of
  "The checkout directory of the source `id` under `sources-dir`, or nil."
  [sources-dir id]
  (when sources-dir
    (let [d (fs/file sources-dir id)]
      (when (fs/directory? d) (str d)))))

(defn resolve-citation
  "Resolves `citation` against `sources` ([{:id :repository :pins :fenced
  :retired}]) and the checkouts under `sources-dir` (or nil):
  {:citation :source :path :commit :status :resolved|:unresolved|:unchecked
   :reasons [why] :rule rule-or-nil}."
  [sources sources-dir citation]
  (let [{:keys [source path commit] :as c} (parse-citation citation)
        base (merge {:citation citation :source source :path path :commit commit} (dissoc c :citation))
        out (fn [status rule reason] (assoc base :status status :rule rule :reasons (if reason [reason] [])))
        src (some #(when (= source (:id %)) %) sources)]
    (cond
      (nil? c)
      (out :unresolved "citation-malformed"
           (str "\"" citation "\" is not a citation: a citation is <source>:<path>@<commit>"))

      (nil? src)
      (out :unresolved "citation-unknown-source"
           (str "no source " source " is recorded in the sources index"))

      (:retired src)
      (out :unresolved "citation-retired"
           (str "its source " source " is retired (" (:retired src) "), and nothing may cite a retired source"))

      (not (some #{commit} (:pins src)))
      (out :unresolved "citation-unpinned"
           (str "the commit " commit " is not a pin of " source ", whose pins are "
                (if (seq (:pins src)) (str/join ", " (:pins src)) "none")))

      (fenced-by src path)
      (let [f (fenced-by src path)
            f* (str/replace (str f) #"/+$" "")]
        (out :unresolved "citation-fenced"
             (if (= f* (str/replace path #"/+$" ""))
               (str "the path " path " is fenced in " source ": a source's implementation is never cited")
               (str "the path " path " lies inside " f* ", fenced in " source
                    ": a source's implementation is never cited"))))

      :else
      (let [co (checkout-of sources-dir source)]
        (cond
          (nil? sources-dir)
          (out :unchecked nil (str "no checkout of " source " was given (rdm-spec check --sources DIR, with DIR/"
                                   source "), so its path was not checked"))
          (nil? co)
          (out :unchecked nil (str "the sources directory " sources-dir " holds no checkout " source
                                   ", so its path was not checked"))
          :else
          (case (exists-at? co commit path)
            true (out :resolved nil nil)
            false (out :unresolved "citation-path-missing"
                       (str "the path " path " does not exist at the pin " commit " of " source))
            (out :unchecked nil (str "the checkout of " source " holds no commit " commit
                                     ", so its path was not checked"))))))))

(defn citation-finding
  "The finding for a resolved citation `c` (with :file and :line), cited by
  `label` (\"the need UN-004\", \"the footnote [^1]\"): nil when it
  resolved, a warning when it is unchecked, else a finding."
  [label c]
  (case (:status c)
    :resolved nil
    :unchecked (command/warning (:file c) (:line c) "citation-unchecked"
                                (str label " cites " (:citation c) ", which was not checked: "
                                     (str/join "; " (:reasons c))))
    (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved")
                     (str label " cites " (:citation c) ", which does not resolve: "
                          (str/join "; " (:reasons c))))))

;; ---------------------------------------------------------------------------
;; The index

(defn- line-of-key
  "The line of `text` where the key `k` (a keyword) is written, or nil."
  [text k]
  (let [re (re-pattern (str "(?<![\\w:-])" (java.util.regex.Pattern/quote (str k)) "(?![\\w.-])"))]
    (some (fn [[i l]] (when (and (re-find re l) (not (str/starts-with? (str/triml l) ";"))) (inc i)))
          (map-indexed vector (str/split-lines (or text ""))))))

(defn read-index
  "The index as the text `text` holds it: {:sources [{:id :repository
  :record :pins :fenced :retired :line}] :error message-or-nil}."
  [text]
  (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
    (cond
      (and (map? v) (::error v)) {:sources [] :error (str "it is not readable EDN: " (::error v))}
      (not (map? v)) {:sources [] :error "it is not a map of sources, {:source-id {:repository :record :pins :fenced}}"}
      :else
      {:sources (vec (for [[k s] (sort-by (comp str key) v)
                           :let [id (if (keyword? k) (name k) (str k))]]
                       (if (map? s)
                         {:id id
                          :repository (:repository s)
                          :record (:record s)
                          :pins (if (sequential? (:pins s)) (vec (map str (:pins s))) [])
                          :fenced (if (sequential? (:fenced s)) (vec (map str (:fenced s))) [])
                          :retired (:retired s)
                          :line (line-of-key text (if (keyword? k) k (keyword id)))}
                         {:id id :unreadable true :line (line-of-key text k)})))
       :error nil})))

(defn index-findings
  "The rules on the index's own entries (`file` its path, `raw-dir` the
  directory records live in, `record?` whether a record file exists)."
  [file raw-dir sources record?]
  (vec (mapcat
        (fn [{:keys [id line repository record pins unreadable]}]
          (if unreadable
            [(command/finding file line "source-unreadable"
                              (str "the source " id " is not a map {:repository :record :pins :fenced}"))]
            (concat
             (when (str/blank? (str repository))
               [(command/finding file line "source-no-repository"
                                 (str "the source " id " names no repository: a source is a repository at a pin"))])
             (when (empty? pins)
               [(command/finding file line "source-no-pin"
                                 (str "the source " id " has no pin: a source is cited at a commit it is pinned to"))])
             (cond
               (str/blank? (str record))
               [(command/finding file line "source-no-record"
                                 (str "the source " id " names no record: its provenance record, a .md file in " raw-dir))]
               (not (record? record))
               [(command/finding file line "source-no-record"
                                 (str "the source " id "'s record " record " is not in " raw-dir))]))))
        sources)))

;; ---------------------------------------------------------------------------
;; History: a pin is never dropped

(defn dropped-pins
  "Pure: every [source pin] some version of the index lists and `current`
  (the sources now) does not, from `versions` [{:commit :sources}] oldest
  first: [{:source :pin :commit dropped-in-or-nil}] (nil: the working tree
  dropped it)."
  [versions current]
  (let [now (set (for [s current p (:pins s)] [(:id s) p]))
        listed (fn [v] (set (for [s (:sources v) p (:pins s)] [(:id s) p])))
        indexed (map-indexed vector versions)]
    (vec (for [pair (distinct (for [v versions x (listed v)] x))
               :when (not (now pair))
               :let [last-i (last (keep (fn [[i v]] (when ((listed v) pair) i)) indexed))
                     dropper (some (fn [[i v]] (when (and (> i last-i) (not ((listed v) pair))) v)) indexed)]]
           {:source (first pair) :pin (second pair) :commit (:commit dropper)}))))

(defn- index-history
  "{:versions [{:commit :sources}] oldest first} of the committed index at
  `rel`, or {:unchecked why} when no history can be read."
  [root rel]
  (let [top (git root "rev-parse" "--show-toplevel")]
    (if-not (zero? (:exit top))
      {:unchecked "the project is not in a git repository"}
      (let [r (git root "log" "--format=%H" "--reverse" "--" rel)]
        (if-not (zero? (:exit r))
          {:versions []}
          {:versions (vec (for [c (remove str/blank? (str/split-lines (:out r)))
                                :let [t (git root "show" (str c ":./" rel))]
                                :when (zero? (:exit t))
                                :let [{:keys [sources error]} (read-index (:out t))]
                                :when (nil? error)]
                            {:commit c :sources sources}))})))))

(defn history-findings
  "The findings and warnings of the index's history."
  [root rel sources]
  (let [{:keys [versions unchecked]} (index-history root rel)
        line-of (into {} (map (juxt :id :line) sources))]
    (if unchecked
      [(command/warning rel nil "sources-history-unchecked"
                        (str "the index's history was not read, so no dropped pin was looked for: " unchecked))]
      (vec (for [{:keys [source pin commit]} (dropped-pins versions sources)]
             (command/finding rel (line-of source) "source-pin-dropped"
                              (str "the source " source " dropped its pin " pin " in "
                                   (if commit (str "the commit " (subs commit 0 (min 12 (count commit))))
                                       "the working tree")
                                   ": a pin is never dropped; retire the source instead (:retired and why)")))))))

;; ---------------------------------------------------------------------------
;; Fenced paths at their pins

(defn fenced-findings
  "A finding for every fenced path that matches nothing at a pin of a
  source whose checkout is given."
  [rel sources sources-dir]
  (vec (for [{:keys [id pins fenced line]} sources
             :let [co (checkout-of sources-dir id)]
             :when co
             pin pins
             f fenced
             :when (not= "." f)
             :let [e (exists-at? co pin f)]
             :when (not e)]
         (if (nil? e)
           (command/warning rel line "fenced-path-unchecked"
                            (str "the fenced path " f " of " id " was not checked: the checkout holds no commit " pin))
           (command/finding rel line "fenced-path-missing"
                            (str "the source " id " fences " f ", which matches nothing at its pin " pin))))))

;; ---------------------------------------------------------------------------
;; The needs' citations

(defn- need-citation-line
  "The first line of the needs file's `text` from the need's own line on
  holding `citation` (quoted), else the need's own line."
  [text citation need-line]
  (let [hits (keep (fn [[i l]] (when (str/includes? l (pr-str citation)) (inc i)))
                   (map-indexed vector (str/split-lines (or text ""))))]
    (or (first (filter #(>= % (or need-line 0)) hits)) (first hits) need-line)))

(defn- need-line [text id]
  (let [lines (map-indexed vector (str/split-lines (or text "")))
        key-re (re-pattern (str "^[\\s{]*" (java.util.regex.Pattern/quote (pr-str id))))]
    (or (some (fn [[i l]] (when (re-find key-re l) (inc i))) lines)
        (some (fn [[i l]] (when (and (str/includes? l (pr-str id)) (not (str/starts-with? (str/triml l) ";")))
                            (inc i)))
              lines))))

(defn read-needs
  "[{:id :sources :line}] of the needs file's text, or nil when it cannot be
  read (the spec check names why)."
  [text]
  (let [v (try (edn/read-string text) (catch Exception _ nil))]
    (when (map? v)
      (vec (for [[k n] (sort-by (comp str key) v)
                 :let [id (if (keyword? k) (name k) (str k))]]
             {:id id
              :sources (let [s (when (map? n) (:sources n))] (if (sequential? s) (vec s) []))
              :line (need-line text id)})))))

(defn need-citations
  "Every source of every need, resolved: [{:citation ... :file :line :need}]."
  [needs-rel text sources sources-dir]
  (vec (for [{:keys [id line] :as n} (read-needs text)
             s (:sources n)]
         (let [c (if (string? s) s (pr-str s))]
           (merge (resolve-citation sources sources-dir c)
                  {:file needs-rel :line (need-citation-line text c line) :need id})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn- sources-dir
  "The --sources directory, as given, or nil; a missing one cannot be run with."
  [req]
  (when-let [d (get-in req [:opts :sources])]
    (when (true? d) (command/cannot-run! "--sources needs a directory: rdm-spec check --sources DIR"))
    (let [f (command/path req d)]
      (when-not (fs/directory? f)
        (command/cannot-run! (str "the sources directory " d " does not exist")))
      (str (fs/absolutize f)))))

(defn- check-wiki
  "The wiki's check (rdm-spec.knowledge.wiki), its footnotes resolved here."
  [req sources sdir]
  (let [f (or (try (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki) (catch Throwable _ nil))
              (command/cannot-run! "the wiki's check (rdm-spec.knowledge.wiki) is not built, so the check cannot run"))]
    (f (:root req) (:config req) {:sources sources :sources-dir sdir})))

(defn payload
  "KnowledgeIndexed's payload: what events.edn says it carries."
  [{:keys [sources pages citations findings]}]
  {:revision :working-tree
   :sources (mapv #(select-keys % [:id :repository :pins :fenced :retired]) sources)
   :pages (vec pages)
   :citations (mapv #(select-keys % [:citation :source :path :commit :file :line :need :status :reasons]) citations)
   :findings (mapv #(select-keys % [:file :line :rule :message :severity]) findings)})

(defn check
  "The knowledge context's stage of `rdm-spec check`: see the namespace doc.
  Result {:knowledge {:passed? :sources [id] :pages [path] :citations [..]}}."
  [req]
  (let [raw-dir (strip-slash (command/config-value req :raw-dir))
        index-rel (str raw-dir "/" index-name)
        index-file (command/path req index-rel)
        sdir (sources-dir req)
        text (when (fs/regular-file? index-file) (slurp (str index-file)))
        {:keys [sources error]} (if text (read-index text) {:sources [] :error "it does not exist"})
        readable (vec (remove :unreadable sources))
        own (concat
             (when error
               [(command/finding index-rel nil "sources-unreadable"
                                 (str "the sources index " index-rel " cannot be read: " error))])
             (index-findings index-rel raw-dir sources
                             #(fs/regular-file? (command/path req (str raw-dir "/" %))))
             (when text (history-findings (:root req) index-rel readable))
             (fenced-findings index-rel readable sdir))
        needs-rel (or (get-in req [:config :needs])
                      (str (strip-slash (or (get-in req [:config :spec-dir]) "dhf/spec")) "/needs.edn"))
        needs-file (command/path req needs-rel)
        needs-cites (if (fs/regular-file? needs-file)
                      (need-citations needs-rel (slurp (str needs-file)) readable sdir)
                      [])
        need-findings (keep #(citation-finding (str "the need " (:need %)) %) needs-cites)
        wiki (check-wiki req readable sdir)
        citations (vec (concat needs-cites (:citations wiki)))
        findings (vec (concat own need-findings (:findings wiki)))
        report {:sources readable :pages (:pages wiki) :citations citations :findings findings}]
    (command/publish! req :knowledge/sources :KnowledgeIndexed (payload report))
    {:result {:knowledge {:passed? (not-any? #(not (command/warning? %)) findings)
                          :sources (mapv :id readable)
                          :pages (vec (:pages wiki))
                          :citations (mapv #(select-keys % [:citation :source :path :commit :file :line :need
                                                            :status :reasons])
                                           citations)}}
     :findings findings}))
