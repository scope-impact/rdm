(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (sources.edn in the configured
  :raw-dir), holds it to its rules, and resolves every citation of the needs
  (the configured :needs) and of the wiki (through the Wiki component)
  against the sources' pins, fenced paths and, where --sources DIR gives a
  checkout DIR/<source-id>, the files at each pin. Nothing else is scanned
  for citations: a feature file may quote one as an example.

  A citation is `<source>:<path>@<commit>`. It resolves in this order: its
  source is recorded, the source is not retired, the commit is one of the
  source's pins, the path is not fenced, and the path exists at the commit in
  the source's checkout. With no checkout, a citation that passes the rest is
  unchecked, with the reason: never passed.

  The rules, each a stable name on the error stream:
    sources-index-unreadable  the sources index is not an EDN map of sources
    sources-no-repository     a source with no repository
    sources-no-pin            a source with no pin
    sources-pin-dropped       a pin an earlier commit of the index listed and
                              the index lists no more (a pin is never dropped:
                              a source is retired instead)
    sources-fenced-missing    a fenced path matching nothing at a pin of a
                              given checkout
    sources-pin-unchecked     (warning) a given checkout not holding a pin
    citation-malformed        a need's source that is not a citation
    citation-unknown-source   a citation of no recorded source
    citation-retired          a citation of a retired source
    citation-unpinned         a citation at a commit that is not a pin
    citation-fenced           a citation of a fenced path, or inside a fenced
                              directory
    citation-path-missing     a citation whose path does not exist at its pin
    citation-unchecked        (warning) a citation no checkout could check

  The stage `check` (of `rdm-spec check` and `rdm-spec publish`) publishes
  KnowledgeIndexed and returns {:knowledge {:passed? :sources :pages
  :citations}} as its result."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Citations

(def citation-re
  "A citation: <source>:<path>@<commit>, the commit 4 to 40 hex digits."
  #"(?<![\w.:/-])([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>/][^\s@`\"'()\[\]<>]*)@([0-9a-fA-F]{4,40})(?![0-9A-Za-z])")

(defn parse-citation
  "{:citation :source :path :commit} of the citation `s`, nil when `s` is not one."
  [s]
  (when-let [[whole src path commit] (re-matches citation-re (str/trim (str s)))]
    {:citation whole :source src :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}], in order."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [whole src path commit] (re-seq citation-re l)]
         {:citation whole :source src :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; The sources index

(defn index-sources
  "The sources of the index map `idx`, sorted by id:
  [{:id :repository :record :pins :fenced} plus :retired when retired]."
  [idx]
  (vec (for [[k v] (sort-by (comp name key) idx)
             :let [v (if (map? v) v {})]]
         (cond-> {:id (name k)
                  :repository (:repository v)
                  :record (:record v)
                  :pins (vec (map str (:pins v)))
                  :fenced (vec (map str (:fenced v)))}
           (:retired v) (assoc :retired (:retired v))))))

(defn- code-part
  "`line` without its comment (a ; outside a string)."
  [line]
  (loop [i 0 in-str? false]
    (if (>= i (count line))
      line
      (let [c (.charAt ^String line i)]
        (cond
          (and in-str? (= c \\)) (recur (+ i 2) true)
          (= c \") (recur (inc i) (not in-str?))
          (and (not in-str?) (= c \;)) (subs line 0 i)
          :else (recur (inc i) in-str?))))))

(defn source-line
  "The line of `text` (the index) on which the source `id` is keyed, or nil."
  [text id]
  (let [re (re-pattern (str "(?<![\\w.:/*+!?-]):" (java.util.regex.Pattern/quote (str id)) "(?![\\w.:/*+!?-])"))]
    (some (fn [[i l]] (when (re-find re (code-part l)) (inc i)))
          (map-indexed vector (str/split-lines (or text ""))))))

(defn index-findings
  "The index's own rules: every source has a repository and a pin."
  [index-rel text sources]
  (vec (for [{:keys [id repository pins]} sources
             [rule bad? message] [["sources-no-repository" (str/blank? (str repository))
                                   (str "the source " id " names no repository")]
                                  ["sources-no-pin" (empty? pins)
                                   (str "the source " id " has no pin; a source names the commits it is cited at")]]
             :when bad?]
         (command/finding index-rel (source-line text id) rule message))))

(defn dropped-pins
  "Every pin an index of `history` (chronological [{:commit :index}]) listed
  and `current` (the index now) does not: [{:source :pin :commit}], :commit
  the commit that dropped it (:working-tree when it is not committed)."
  [history current]
  (let [states (concat (map (fn [{:keys [commit index]}] [commit index]) history)
                       [[:working-tree current]])
        pairs (fn [idx] (set (for [s (index-sources (if (map? idx) idx {})) pin (:pins s)] [(:id s) pin])))
        sets (mapv (fn [[c idx]] [c (pairs idx)]) states)
        final (second (peek sets))]
    (vec (sort-by (juxt :source :pin)
                  (for [pair (distinct (mapcat second sets))
                        :when (not (contains? final pair))
                        :let [last-i (last (keep-indexed (fn [i [_ ps]] (when (contains? ps pair) i)) sets))]]
                    {:source (first pair) :pin (second pair) :commit (first (nth sets (inc last-i)))})))))

(defn- git
  "Runs git in `dir`: {:exit :out}."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_OPTIONAL_LOCKS" "0"}}
                 "git" args)]
    {:exit (:exit r) :out (:out r)}))

(defn- git-ok? [dir & args] (zero? (:exit (apply git dir args))))

(defn index-history
  "Every committed version of the index `index-rel` in the repository at
  `root`, oldest first: [{:commit :index}]; [] outside a repository. A
  version that is not readable EDN is left out."
  [root index-rel]
  (if-not (and (fs/directory? root) (git-ok? root "rev-parse" "--is-inside-work-tree"))
    []
    (let [{:keys [exit out]} (git root "log" "--format=%H" "--reverse" "--" index-rel)]
      (if-not (zero? exit)
        []
        (vec (for [h (str/split-lines out)
                   :when (not (str/blank? h))
                   :let [{:keys [exit out]} (git root "show" (str h ":./" index-rel))
                         idx (when (zero? exit) (try (edn/read-string out) (catch Exception _ nil)))]
                   :when (map? idx)]
               {:commit (subs h 0 (min 7 (count h))) :index idx}))))))

(defn dropped-findings
  [index-rel text dropped]
  (vec (for [{:keys [source pin commit]} dropped]
         (command/finding index-rel (source-line text source) "sources-pin-dropped"
                          (str "the source " source " no longer lists its pin " pin ", dropped "
                               (if (= :working-tree commit) "in the working tree" (str "by commit " commit))
                               "; a pin is never dropped: retire the source instead (:retired, with the reason)")))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn checkout
  "The checkout of the source `id` in `sources-dir`, or nil."
  [sources-dir id]
  (when sources-dir
    (let [co (fs/file (str sources-dir) id)]
      (when (and (fs/directory? co) (git-ok? co "rev-parse" "--git-dir"))
        (str co)))))

(defn- holds-commit? [co commit] (git-ok? co "cat-file" "-e" (str commit "^{commit}")))

(defn- holds-path? [co commit path]
  (git-ok? co "cat-file" "-e" (str commit ":" (str/replace path #"^\./" ""))))

(defn fence-findings
  "Each fenced path that matches nothing at a pin of a given checkout; a
  warning for a pin the checkout does not hold."
  [index-rel text sources sources-dir]
  (vec (for [{:keys [id pins fenced]} sources
             :let [co (checkout sources-dir id)]
             :when co
             pin pins
             :let [held? (holds-commit? co pin)]
             f (if held? (remove #{"." "./" ""} fenced) [nil])
             :when (or (not held?) (not (holds-path? co pin (str/replace f #"/+$" ""))))]
         (if held?
           (command/finding index-rel (source-line text id) "sources-fenced-missing"
                            (str "the fenced path " f " of the source " id " matches nothing at the pin " pin
                                 " in the checkout " co))
           (command/warning index-rel (source-line text id) "sources-pin-unchecked"
                            (str "the checkout " co " of the source " id " does not hold its pin " pin
                                 ", so its fenced paths were not checked there"))))))

;; ---------------------------------------------------------------------------
;; Resolving

(defn- pinned? [pins commit]
  (let [c (str/lower-case commit)]
    (some #(let [p (str/lower-case %)] (or (= p c) (str/starts-with? c p) (str/starts-with? p c))) pins)))

(defn- fenced-by
  "The fenced entry of `fenced` that `path` is or lies inside: [entry :path|:directory], or nil."
  [fenced path]
  (some (fn [f]
          (let [f* (str/replace (str f) #"/+$" "")]
            (cond (#{"." ""} f*) [f :directory]
                  (= path f*) [f :path]
                  (str/starts-with? path (str f* "/")) [f :directory])))
        fenced))

(defn resolve-citation
  "Resolves the citation string `citation` against `sources` (the index's,
  as index-sources gives them) and the checkouts in `sources-dir` (nil: none
  given): {:citation :source :path :commit :status :reasons :rule}, :status
  :resolved, :unresolved or :unchecked, :rule naming why when it is not resolved."
  [sources sources-dir citation]
  (if-let [{:keys [source path commit] :as c} (parse-citation citation)]
    (let [src (first (filter #(= source (:id %)) sources))
          out (fn [status rule reason] (assoc c :status status :rule rule :reasons (if reason [reason] [])))
          fence (when src (fenced-by (:fenced src) path))
          co (when src (checkout sources-dir source))]
      (cond
        (nil? src)
        (out :unresolved "citation-unknown-source" (str "no source " source " is recorded in the sources index"))

        (:retired src)
        (out :unresolved "citation-retired" (str "the source " source " is retired (" (:retired src) "); nothing may cite it"))

        (not (pinned? (:pins src) commit))
        (out :unresolved "citation-unpinned"
             (str "the commit " commit " is not a pin of " source
                  (if (seq (:pins src)) (str " (its pins: " (str/join ", " (:pins src)) ")") " (it has no pin)")))

        fence
        (let [[f kind] fence]
          (out :unresolved "citation-fenced"
               (if (= :path kind)
                 (str "the path " path " is the fenced path " f " of " source "; a source's implementation is never cited")
                 (str "the path " path " lies inside the fenced directory " f " of " source
                      "; a source's implementation is never cited"))))

        (nil? sources-dir)
        (out :unchecked "citation-unchecked" (str "no checkout of " source " was given with --sources"))

        (nil? co)
        (out :unchecked "citation-unchecked" (str "no checkout of " source " in " sources-dir " (--sources)"))

        (not (holds-commit? co commit))
        (out :unchecked "citation-unchecked" (str "the checkout " co " does not hold the commit " commit))

        (not (holds-path? co commit path))
        (out :unresolved "citation-path-missing"
             (str "the path " path " does not exist at the pin " commit " in the checkout of " source))

        :else (out :resolved nil nil)))
    {:citation (str citation) :source nil :path nil :commit nil :status :unresolved :rule "citation-malformed"
     :reasons ["it is not a citation: <source>:<path>@<commit>"]}))

(defn citation-finding
  "The finding for the resolved citation `c` (with :file and :line), cited
  by `who` (\"the need UN-001\", \"the footnote [^1]\"): a warning when it is
  unchecked, nil when it resolved."
  [who c]
  (case (:status c)
    :resolved nil
    :unchecked (command/warning (:file c) (:line c) "citation-unchecked"
                                (str who " cites " (:citation c) ", which was not checked: "
                                     (str/join "; " (:reasons c))))
    (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved")
                     (str who " cites " (:citation c) ": " (str/join "; " (:reasons c))))))

;; ---------------------------------------------------------------------------
;; The needs

(defn- read-needs [text]
  (let [m (try (edn/read-string text) (catch Exception _ nil))]
    (when (map? m) m)))

(defn- line-of
  "The first line at or after `from` whose code holds `s`, or nil."
  [lines from s]
  (some (fn [i] (when (str/includes? (code-part (nth lines i)) s) (inc i)))
        (range (max 0 (dec (or from 1))) (count lines))))

(defn- need-lines
  "[[need-id key-line source]] for every non-blank source of every need of `text`."
  [text]
  (let [lines (vec (str/split-lines (or text "")))
        needs (read-needs text)]
    (for [[k v] (sort-by (comp str key) needs)
          :let [kl (line-of lines 1 (pr-str k))]
          s (when (map? v) (:sources v))
          :when (not (str/blank? (str s)))]
      [(str k) kl (str s)])))

(defn need-citations
  "The citations of the needs in `text` (needs.edn):
  [{:citation :source :path :commit :line :need}], in the file's order."
  [text]
  (let [lines (vec (str/split-lines (or text "")))]
    (vec (sort-by :line
                  (for [[need kl s] (need-lines text)
                        :let [c (parse-citation s)]
                        :when c]
                    (assoc c :need need :line (line-of lines kl (pr-str s))))))))

(defn malformed-need-sources
  "The sources of the needs map `needs` that are not citations: [{:need :source}]."
  [needs]
  (vec (for [[k v] (sort-by (comp str key) needs)
             s (when (map? v) (:sources v))
             :when (and (not (str/blank? (str s))) (nil? (parse-citation s)))]
         {:need (str k) :source (str s)})))

;; ---------------------------------------------------------------------------
;; The stage

(defn- event-finding [f] (select-keys f [:file :line :rule :message]))

(defn- run-wiki
  "The Wiki component's check, or a warning when it is not built."
  [root config opts]
  (let [f (try (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki) (catch Throwable _ nil))]
    (if f
      (f root config opts)
      {:pages [] :citations []
       :findings [(command/warning "wiki-not-built" "the Wiki component is not built, so the wiki was not checked")]})))

(defn check
  "The knowledge check: the sources index, the needs' citations and the
  wiki. Publishes KnowledgeIndexed. Cannot run when --sources names no directory."
  [req]
  (let [{:keys [root config opts]} req
        raw-dir (str/replace (or (:raw-dir config) "dhf/raw") #"/+$" "")
        index-rel (str raw-dir "/sources.edn")
        index-file (command/path root index-rel)
        sources-dir (when-let [d (:sources opts)]
                      (let [f (command/path root d)]
                        (when-not (fs/directory? f)
                          (command/cannot-run! (str "the sources directory " d " does not exist")))
                        (str (fs/absolutize f))))
        text (when (fs/regular-file? index-file) (slurp index-file))
        idx (when text (try (edn/read-string text) (catch Exception _ ::unreadable)))
        readable? (or (nil? text) (map? idx))
        idx (if (map? idx) idx {})
        sources (index-sources idx)
        needs-rel (:needs config)
        needs-file (when needs-rel (command/path root needs-rel))
        needs-text (when (and needs-file (fs/regular-file? needs-file)) (slurp needs-file))
        need-cites (vec (for [c (need-citations needs-text)]
                          (merge (resolve-citation sources sources-dir (:citation c))
                                 {:file needs-rel :line (:line c) :need (:need c)})))
        wiki (run-wiki root config {:sources sources :sources-dir sources-dir})
        citations (vec (concat (map #(dissoc % :rule) need-cites) (:citations wiki)))
        findings (vec (concat
                       (when-not readable?
                         [(command/finding index-rel nil "sources-index-unreadable"
                                           "the sources index is not an EDN map of sources")])
                       (index-findings index-rel text sources)
                       (when (map? idx) (dropped-findings index-rel text (dropped-pins (index-history root index-rel) idx)))
                       (fence-findings index-rel text sources sources-dir)
                       (for [{:keys [need source]} (some-> needs-text read-needs malformed-need-sources)]
                         (command/finding needs-rel (line-of (vec (str/split-lines needs-text)) 1 (pr-str source))
                                          "citation-malformed"
                                          (str "the need " need " cites " source
                                               ", which is not a citation: <source>:<path>@<commit>")))
                       (keep #(citation-finding (str "the need " (:need %)) %) need-cites)
                       (:findings wiki)))
        errors (remove command/warning? findings)
        sources* (mapv #(dissoc % :record) sources)]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      {:revision :working-tree
                       :sources sources*
                       :pages (vec (:pages wiki))
                       :citations citations
                       :findings (mapv event-finding errors)})
    {:result {:knowledge {:passed? (empty? errors)
                          :sources sources*
                          :pages (vec (:pages wiki))
                          :citations citations}}
     :findings findings}))
