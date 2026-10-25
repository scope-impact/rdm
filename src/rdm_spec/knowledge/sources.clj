(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (sources.edn in the configured
  :raw-dir) and resolves every citation of the needs (the configured :needs)
  and of the wiki's pages against it. It is the knowledge stage of
  `rdm-spec check` and `rdm-spec publish`: it runs the Wiki component's check
  too, and publishes KnowledgeIndexed.

  A citation is `<source>:<path>@<commit>`. It resolves when its source is
  recorded, its commit is one of the source's pins, its path is not fenced,
  and the path exists at that pin in the source's checkout. Checkouts are
  machine-local: `--sources DIR` names a directory holding one git checkout
  per source, named by the source's id. Without one, a citation's path is
  :unchecked, reported as a warning, never passed.

  The rules, each a stable name on the error stream:
    sources-index            the sources index is not an EDN map of sources
    source-no-repository     a source with no repository
    source-no-record         a source whose provenance record is missing
    source-no-pin            a source with no pin
    source-pin-dropped       a pin the index at HEAD lists and the working tree does not
    source-fence-unmatched   a fenced path that matches nothing at a pin of a given checkout
    citation-malformed       a need's citation not written <source>:<path>@<commit>
    citation-unknown-source  a citation naming no recorded source
    citation-not-pinned      a citation at a commit that is not one of its source's pins
    citation-fenced          a citation of a fenced path, or of a path inside a fenced directory
    citation-missing-path    a path that does not exist at its pin in the checkout
    citation-unchecked       (a warning) a citation whose path could not be checked, and why"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def index-name "sources.edn")

;; ---------------------------------------------------------------------------
;; Citations in text

(def citation-pattern
  "A citation, <source>:<path>@<commit>, the commit 7 to 40 hex digits."
  #"(?<![\w.:/@-])([A-Za-z0-9][\w.-]*):(?!/)([^\s@`\"'()\[\]<>:]+)@([0-9a-fA-F]{7,40})(?![0-9a-zA-Z])")

(defn parse-citation
  "{:citation :source :path :commit} of the citation `s`, or nil when `s` is
  not one."
  [s]
  (when-let [[c source path commit] (and (string? s) (re-matches citation-pattern (str/trim s)))]
    {:citation c :source source :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}], in
  order."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [c source path commit] (re-seq citation-pattern l)]
         {:citation c :source source :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn- git
  "Runs git in `dir`: {:exit :out}."
  [dir & args]
  (try
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                            :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                   "git" args)]
      {:exit (:exit r) :out (str/trim (:out r))})
    (catch Exception e {:exit -1 :out "" :error (ex-message e)})))

(def ^:private commit-of*
  (memoize (fn [checkout pin]
             (let [r (git checkout "rev-parse" "--verify" "--quiet" (str pin "^{commit}"))]
               (when (zero? (:exit r)) (:out r))))))

(defn commit-of
  "The full commit `pin` names in the git checkout `checkout`, or nil."
  [checkout pin]
  (commit-of* (str checkout) (str pin)))

(def ^:private exists-at*
  (memoize (fn [checkout commit path]
             (zero? (:exit (git checkout "cat-file" "-e" (str commit ":" path)))))))

(defn exists-at?
  "Whether `path` (a file or a directory) exists at `commit` in `checkout`."
  [checkout commit path]
  (exists-at* (str checkout) (str commit) (str/replace (str path) #"^\./|/+$" "")))

(defn checkout-dir
  "The checkout of the source `id` under `sources-dir`, or nil when there is
  none."
  [sources-dir id]
  (when sources-dir
    (let [d (fs/file sources-dir id)]
      (when (fs/directory? d) (str d)))))

;; ---------------------------------------------------------------------------
;; Resolving

(defn fence-of
  "The fenced path of `fenced` that `path` is or lies inside, or nil."
  [fenced path]
  (let [clean #(str/replace (str %) #"^\./|/+$" "")
        p (clean path)]
    (some (fn [f] (let [f (clean f)]
                    (when (and (seq f) (or (= p f) (str/starts-with? p (str f "/")))) f)))
          fenced)))

(defn resolve-citation
  "Resolves the citation `citation` (a string) against `sources` (the index,
  [{:id :repository :pins :fenced}]) and `sources-dir` (or nil):
  {:citation :source :path :commit :status :reasons :rule}, :status
  :resolved, :unresolved or :unchecked, :rule the first failing rule."
  [sources sources-dir citation]
  (if-let [{:keys [source path commit] :as c} (parse-citation citation)]
    (let [src (first (filter #(= source (:id %)) sources))
          pins (vec (:pins src))
          unresolved (fn [rule & reasons] (assoc c :status :unresolved :rule rule :reasons (vec reasons)))]
      (cond
        (nil? src)
        (unresolved "citation-unknown-source"
                    (str "no source " source " is recorded in the sources index; the sources are "
                         (if (seq sources) (str/join ", " (map :id sources)) "none")))

        :else
        (let [fence (fence-of (:fenced src) path)
              pinned? (some #{commit} pins)
              reasons (concat
                       (when-not pinned?
                         [["citation-not-pinned"
                           (str "the commit " commit " is not a pin of " source "; its pins are "
                                (if (seq pins) (str/join ", " pins) "none"))]])
                       (when fence
                         [["citation-fenced"
                           (if (= fence (str/replace path #"/+$" ""))
                             (str "the path " path " is fenced in " source
                                  ": a source's implementation is never cited")
                             (str "the path " path " lies in the fenced directory " fence " of " source
                                  ": a source's implementation is never cited"))]]))]
          (if (seq reasons)
            (apply unresolved (ffirst reasons) (map second reasons))
            (let [co (checkout-dir sources-dir source)
                  full (when co (commit-of co commit))]
              (cond
                (nil? sources-dir)
                (assoc c :status :unchecked :reasons
                       [(str "no checkout of " source " was given with --sources, so the path " path
                             " was not looked for at " commit)])
                (nil? co)
                (assoc c :status :unchecked :reasons
                       [(str "no checkout of " source " in " sources-dir " (a directory " source
                             "/ there), so the path " path " was not looked for at " commit)])
                (nil? full)
                (assoc c :status :unchecked :reasons
                       [(str "the checkout " co " holds no commit " commit
                             ", so the path " path " was not looked for at it")])
                (exists-at? co full path)
                (assoc c :status :resolved :reasons [])
                :else
                (unresolved "citation-missing-path"
                            (str "the path " path " does not exist at the pin " commit
                                 " in the checkout " co))))))))
    {:citation citation :source nil :path nil :commit nil :status :unresolved
     :rule "citation-malformed"
     :reasons [(str "\"" citation "\" is not a citation; a citation is <source>:<path>@<commit>")]}))

(defn citation-finding
  "The finding for the resolved citation `c` (with :file and :line), cited by
  `who` (\"the need UN-004\", \"the footnote [^1]\"): an error when it is
  :unresolved, a warning when :unchecked, nil when :resolved."
  [who c]
  (case (:status c)
    :unresolved (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved")
                                 (str who " cites " (:citation c) ", which does not resolve: "
                                      (str/join "; " (:reasons c))))
    :unchecked (command/warning (:file c) (:line c) "citation-unchecked"
                                (str who " cites " (:citation c) ", not checked: "
                                     (str/join "; " (:reasons c))))
    nil))

;; ---------------------------------------------------------------------------
;; The sources index

(defn- line-of
  "The 1-based line of `text` at or after `from` holding `needle`, or nil."
  ([text needle] (line-of text needle 1))
  ([text needle from]
   (some (fn [[i l]] (when (and (>= (inc i) from) (str/includes? l needle)) (inc i)))
         (map-indexed vector (str/split-lines (or text ""))))))

(defn- source-line [text id]
  (or (some (fn [[i l]] (when (re-find (re-pattern (str "(?<![\\w-]):" (java.util.regex.Pattern/quote id) "(?![\\w.-])")) l)
                          (inc i)))
            (map-indexed vector (str/split-lines (or text ""))))
      nil))

(defn- strings [x] (if (sequential? x) (vec (filter string? x)) []))

(defn index-sources
  "The sources of the index `idx` (the EDN map): [{:id :repository :pins
  :fenced :record}], sorted by id."
  [idx]
  (vec (sort-by :id
                (for [[k v] idx :when (map? v)]
                  {:id (if (keyword? k) (name k) (str k))
                   :repository (:repository v)
                   :record (:record v)
                   :pins (strings (:pins v))
                   :fenced (strings (:fenced v))}))))

(defn read-index
  "{:text :index :sources :findings} of the sources index file `f`, named
  `rel` in findings. A missing index records no source."
  [f rel]
  (if-not (fs/regular-file? f)
    {:text nil :sources [] :findings []}
    (let [text (slurp (str f))
          idx (try (edn/read-string text) (catch Exception e e))]
      (cond
        (instance? Exception idx)
        {:text text :sources []
         :findings [(command/finding rel nil "sources-index"
                                     (str "the sources index is not readable EDN: " (ex-message idx)))]}
        (not (or (nil? idx) (map? idx)))
        {:text text :sources []
         :findings [(command/finding rel nil "sources-index" "the sources index is not a map of sources")]}
        :else
        {:text text :index idx :sources (index-sources idx) :findings []}))))

(defn source-findings
  "The index rules for each of `sources` (as index-sources returns them),
  the index's text being `text`, its records in `raw-dir` (a File)."
  [rel text raw-dir sources]
  (vec (for [{:keys [id repository record pins]} sources
             :let [line (source-line text id)]
             f [(when (str/blank? (str repository))
                  (command/finding rel line "source-no-repository"
                                   (str "the source " id " names no repository")))
                (when (or (str/blank? (str record)) (not (fs/regular-file? (fs/file raw-dir (str record)))))
                  (command/finding rel line "source-no-record"
                                   (str "the source " id " has no provenance record"
                                        (when-not (str/blank? (str record)) (str ": there is no " record " beside the index")))))
                (when (empty? pins)
                  (command/finding rel line "source-no-pin"
                                   (str "the source " id " has no pin; every source is pinned at the commits it is cited at")))]
             :when f]
         f)))

(defn dropped-pin-findings
  "A finding for each pin a source had in `before` (the index at HEAD, as
  index-sources returns it) and no longer lists in `after`."
  [rel text before after]
  (let [now (into {} (map (juxt :id (comp set :pins))) after)]
    (vec (for [{:keys [id pins]} before
               pin pins
               :when (not (contains? (get now id #{}) pin))]
           (command/finding rel (or (source-line text id) nil) "source-pin-dropped"
                            (str "the source " id " no longer lists the pin " pin
                                 ", which the index at HEAD lists; a pin is never dropped, so citations made against it still resolve"))))))

(defn head-sources
  "The sources of the index at HEAD in the git repository `root`, or nil when
  there is no HEAD or the index is not there."
  [root rel]
  (let [r (git root "show" (str "HEAD:" rel))]
    (when (zero? (:exit r))
      (let [idx (try (edn/read-string (:out r)) (catch Exception _ nil))]
        (when (map? idx) (index-sources idx))))))

(defn fence-findings
  "A finding for each fenced path of a source with a checkout in
  `sources-dir` that matches nothing at one of its pins."
  [rel text sources-dir sources]
  (vec (for [{:keys [id pins fenced]} sources
             :let [co (checkout-dir sources-dir id)]
             :when co
             pin pins
             :let [full (commit-of co pin)]
             :when full
             path fenced
             :when (not (exists-at? co full path))]
         (command/finding rel (or (line-of text (str "\"" path "\"")) (source-line text id)) "source-fence-unmatched"
                          (str "the fenced path " path " of " id " matches nothing at the pin " pin
                               " in the checkout " co)))))

;; ---------------------------------------------------------------------------
;; The needs

(defn need-citations
  "Every citation of the needs file `f` (named `rel`), resolved:
  [{:citation :source :path :commit :file :line :need :status :reasons :rule}].
  An unreadable needs file has none (the spec check names it)."
  [f rel sources sources-dir]
  (when (fs/regular-file? f)
    (let [text (slurp (str f))
          needs (try (edn/read-string text) (catch Exception _ nil))]
      (when (map? needs)
        (vec (for [[id need] (sort-by (comp str key) needs)
                   :when (map? need)
                   :let [from (or (line-of text (pr-str (str id))) 1)]
                   s (if (sequential? (:sources need)) (:sources need) [])
                   :when (some? s)]
               (merge (resolve-citation sources sources-dir (str s))
                      {:citation (str s)
                       :file rel
                       :line (or (line-of text (pr-str (str s)) from) from)
                       :need (str id)})))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- event-finding [f] (select-keys f [:file :line :rule :message]))

(defn- sources-dir-of
  "The --sources directory, absolute, or nil; cannot run when it is not a
  directory."
  [req]
  (when-let [d (get-in req [:opts :sources])]
    (let [f (command/path req d)]
      (when-not (fs/directory? f)
        (command/cannot-run! (str "the sources directory " d " does not exist")))
      (str (fs/normalize (fs/absolutize f))))))

(defn- requiring-resolve-wiki []
  (try (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki)
       (catch Exception _ nil)))

(defn index-knowledge
  "Reads the sources and the wiki of the project the request names and
  resolves every citation: the KnowledgeIndexed payload, its :findings
  holding every finding and warning (with :severity)."
  [req]
  (let [{:keys [root config]} req
        raw-dir (str/replace (or (:raw-dir config) "dhf/raw") #"/+$" "")
        rel (str raw-dir "/" index-name)
        sources-dir (sources-dir-of req)
        {:keys [text sources] index-findings :findings} (read-index (command/path root rel) rel)
        before (head-sources root rel)
        needs-rel (or (:needs config) "dhf/spec/needs.edn")
        need-cites (or (need-citations (command/path root needs-rel) needs-rel sources sources-dir) [])
        wiki-check (requiring-resolve-wiki)
        wiki (if wiki-check
               (wiki-check root config {:sources sources :sources-dir sources-dir})
               {:pages [] :citations [] :findings []})
        findings (vec (concat index-findings
                              (source-findings rel text (command/path root raw-dir) sources)
                              (when before (dropped-pin-findings rel text before sources))
                              (when sources-dir (fence-findings rel text sources-dir sources))
                              (keep #(citation-finding (str "the need " (:need %)) %) need-cites)
                              (:findings wiki)))]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced]) sources)
     :pages (vec (:pages wiki))
     :citations (vec (concat (map #(dissoc % :rule) need-cites) (:citations wiki)))
     :findings findings}))

(defn check
  "The knowledge stage of `rdm-spec check` (and of `rdm-spec publish`):
  indexes the sources and the wiki, publishes KnowledgeIndexed and reports
  every finding; a citation that could not be checked is a warning."
  [req]
  (let [k (index-knowledge req)
        errors (remove command/warning? (:findings k))
        payload (assoc k :findings (mapv event-finding errors))]
    (command/publish! req :knowledge/sources :KnowledgeIndexed payload)
    {:result {:knowledge (assoc payload :passed? (empty? errors))}
     :findings (:findings k)}))
