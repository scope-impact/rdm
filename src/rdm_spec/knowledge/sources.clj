(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (<raw-dir>/sources.edn) and its
  provenance records, and resolves citations, `<source>:<path>@<commit>`,
  against the sources' pins, their fenced paths and, when given, their
  checkouts. Its stage runs first in `rdm-spec check` (and `rdm-spec
  publish`): it reads the needs' citations and the wiki (through the Wiki
  component, which resolves its footnotes with this one) and publishes
  KnowledgeIndexed.

  Checkouts are machine-local and never committed: `--sources DIR` names a
  directory holding one git checkout per source, named by the source's id.

  The rules, each a stable name on the error stream:
    sources-index            the index is missing, unreadable or not a map
    sources-no-repository    a source names no repository
    sources-no-pin           a source lists no pin
    sources-no-record        a source's provenance record is missing
    sources-dropped-pin      a pin the index lists at HEAD is no longer listed
    sources-pin-missing      a checkout does not hold one of its source's pins
    sources-fence-unmatched  a fenced path matches nothing at a pin
    sources-fence-unchecked  (warning) fences not checked: no checkout
    citation-malformed       a need's source that is no citation
    citation-unknown-source  a citation naming no recorded source
    citation-fenced          a citation of a fenced path
    citation-unpinned        a citation at a commit that is not a pin
    citation-unresolved      a citation whose path does not exist at its commit
    citation-unchecked       (warning) a citation whose path was not checked

  Only the needs file and the wiki's pages are scanned for citations."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Citations

(def citation-pattern
  "A whole citation: source:path@commit."
  #"([A-Za-z0-9][\w.-]*):([^\s@`\"']+)@([0-9a-fA-F]{4,40})")

(def citation-in-text
  "A citation inside prose: not part of a longer word, path or URL."
  #"(?<![\w/.@:-])([A-Za-z0-9][\w.-]*):((?!/)[^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})(?![\w])")

(defn parse-citation
  "{:citation :source :path :commit} for a whole citation, or nil."
  [s]
  (when-let [[c src path commit] (re-matches citation-pattern (str/trim (str s)))]
    {:citation c :source src :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}]."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [c src path commit] (re-seq citation-in-text l)]
         {:citation c :source src :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; The index

(defn- line-of
  "The 1-based line of the first line of `text` matching `re` (comments
  skipped), or nil."
  [text re]
  (some (fn [[i l]] (when (and (not (str/starts-with? (str/triml l) ";")) (re-find re l)) (inc i)))
        (map-indexed vector (str/split-lines (or text "")))))

(defn- key-line [text id]
  (line-of text (re-pattern (str "(?<![\\w.-]):" (java.util.regex.Pattern/quote id) "(?![\\w.-])"))))

(defn index-file
  "The sources index's path in the project: <raw-dir>/sources.edn."
  [config]
  (str (or (:raw-dir config) "dhf/raw") "/sources.edn"))

(defn- id-of [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn parse-index
  "The sources in the index text `text` (named `file`):
  {:sources [{:id :repository :record :pins :fenced :line}] :error why-or-nil}."
  [file text]
  (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
    (cond
      (::error v) {:sources [] :error (str "the sources index " file " is not readable EDN: " (::error v))}
      (nil? v) {:sources []}
      (not (map? v)) {:sources [] :error (str "the sources index " file " is not a map of sources")}
      :else {:sources (vec (sort-by :id
                                    (for [[k s] v :let [id (id-of k) s (if (map? s) s {})]]
                                      {:id id
                                       :repository (:repository s)
                                       :record (:record s)
                                       :pins (vec (map str (:pins s)))
                                       :fenced (vec (map #(str/replace (str %) #"/+$" "") (:fenced s)))
                                       :line (key-line text id)})))})))

(defn read-index
  "The project's sources index: {:file :sources [..] :findings [..]}."
  [root config]
  (let [file (index-file config)
        f (command/path root file)]
    (if-not (fs/regular-file? f)
      {:file file :sources []
       :findings (if (fs/directory? (command/path root (or (:raw-dir config) "dhf/raw")))
                   [(command/finding file nil "sources-index" (str "there is no sources index " file))]
                   [])}
      (let [text (slurp (str f))
            {:keys [sources error]} (parse-index file text)]
        {:file file :text text :sources sources
         :findings (if error [(command/finding file nil "sources-index" error)] [])}))))

(defn index-findings
  "Findings for each source of the index: its repository, pins and record."
  [root config {:keys [file sources]}]
  (let [raw (or (:raw-dir config) "dhf/raw")]
    (vec (concat
          (for [s sources :when (str/blank? (:repository s))]
            (command/finding file (:line s) "sources-no-repository"
                             (str "the source " (:id s) " names no repository")))
          (for [s sources :when (empty? (:pins s))]
            (command/finding file (:line s) "sources-no-pin"
                             (str "the source " (:id s) " lists no pin; every source is pinned to the commits it is cited at")))
          (for [s sources
                :let [rec (:record s)]
                :when (or (str/blank? rec) (not (fs/regular-file? (command/path root (str raw "/" rec)))))]
            (command/finding file (:line s) "sources-no-record"
                             (if (str/blank? rec)
                               (str "the source " (:id s) " names no provenance record")
                               (str "the source " (:id s) "'s provenance record " raw "/" rec " does not exist"))))))))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  "Runs git in `dir`: {:exit :out}."
  [dir & args]
  (try
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                            :extra-env {"GIT_CONFIG_NOSYSTEM" "1"}}
                   "git" args)]
      {:exit (:exit r) :out (:out r)})
    (catch Exception e {:exit -1 :out "" :err (ex-message e)})))

(defn index-at-head
  "The index as committed at HEAD of the git repository holding `root`, or
  nil when there is none."
  [root file]
  (let [{:keys [exit out]} (git (str root) "show" (str "HEAD:./" file))]
    (when (zero? exit)
      (let [{:keys [sources error]} (parse-index file out)]
        (when-not error sources)))))

(defn dropped-pin-findings
  "A finding for each pin the index at HEAD lists that the working tree's
  index no longer lists. A pin is never dropped."
  [{:keys [file sources]} head-sources]
  (let [now (into {} (map (juxt :id identity) sources))]
    (vec (for [h head-sources
               :let [s (get now (:id h))
                     kept (set (:pins s))]
               pin (:pins h)
               :when (not (contains? kept pin))]
           (command/finding file (or (:line s) (:line h)) "sources-dropped-pin"
                            (str "the source " (:id h) " no longer lists the pin " pin
                                 ", which it lists at HEAD; a pin is never dropped, so a citation made against it still resolves"))))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn checkout-of
  "Where the checkout of the source `id` is, given the --sources directory
  `dir` (absolute or nil): {:dir path} or {:reason why it is not there}."
  [dir id]
  (cond
    (nil? dir) {:reason (str "no checkout of " id " was given with --sources")}
    (not (fs/directory? (fs/file dir id))) {:reason (str "the --sources directory holds no checkout " id "/")}
    (not (zero? (:exit (git (fs/file dir id) "rev-parse" "--git-dir"))))
    {:reason (str "the --sources directory's " id "/ is not a git checkout")}
    :else {:dir (str (fs/file dir id))}))

(defn- has-commit? [co commit]
  (zero? (:exit (git co "rev-parse" "--verify" "--quiet" (str commit "^{commit}")))))

(defn- has-path? [co commit path]
  (zero? (:exit (git co "cat-file" "-e" (str commit ":" path)))))

(defn- pin-of
  "The pin of `s` the commit `commit` names (either a prefix of the other)."
  [s commit]
  (let [c (str/lower-case commit)]
    (some #(let [p (str/lower-case %)] (when (or (str/starts-with? p c) (str/starts-with? c p)) %)) (:pins s))))

(defn fence-of
  "The fenced path of `s` that `path` is or lies inside, or nil."
  [s path]
  (some #(when (or (= path %) (str/starts-with? path (str % "/"))) %) (:fenced s)))

(defn fence-findings
  "With a checkout of a source: a finding for each fenced path that matches
  nothing at one of its pins (and for a pin the checkout does not hold).
  Without one: a warning that its fences were not checked."
  [{:keys [file sources]} sources-dir]
  (vec (for [s sources
             :when (seq (:fenced s))
             :let [{co :dir reason :reason} (checkout-of sources-dir (:id s))]
             f (if co
                 (for [pin (:pins s)
                       f (if (has-commit? co pin)
                           (for [path (:fenced s) :when (not (has-path? co pin path))]
                             (command/finding file (:line s) "sources-fence-unmatched"
                                              (str "the source " (:id s) " fences " path
                                                   ", which matches nothing at its pin " pin)))
                           [(command/finding file (:line s) "sources-pin-missing"
                                             (str "the checkout of " (:id s) " does not hold its pin " pin))])]
                   f)
                 [(command/warning file (:line s) "sources-fence-unchecked"
                                   (str "the fenced paths of " (:id s) " were not checked against its pins: " reason))])]
         f)))

;; ---------------------------------------------------------------------------
;; Resolving

(defn resolve-citation
  "Resolves the citation string `c` against `sources` (the index's) and the
  --sources directory `sources-dir` (or nil):
  {:citation :status :resolved|:unresolved|:unchecked :rule :reasons [why]}."
  [sources sources-dir c]
  (let [parsed (parse-citation c)
        by-id (into {} (map (juxt :id identity) sources))]
    (if-not parsed
      {:citation c :status :unresolved :rule "citation-malformed"
       :reasons [(str "it is no citation; a citation is <source>:<path>@<commit>")]}
      (let [{:keys [source path commit]} parsed
            s (get by-id source)]
        (if-not s
          {:citation c :status :unresolved :rule "citation-unknown-source"
           :reasons [(str "it names no recorded source " source
                          (when (seq sources) (str " (the recorded sources are " (str/join ", " (map :id sources)) ")")))]}
          (let [fence (fence-of s path)
                pin (pin-of s commit)
                reasons (cond-> []
                          fence (conj [:fenced (if (= fence path)
                                                 (str "it cites the fenced path " fence " of " source
                                                      "; a source's implementation is never cited")
                                                 (str "it cites " path ", inside the fenced directory " fence " of " source
                                                      "; a source's implementation is never cited"))])
                          (not pin) (conj [:unpinned (str "the commit " commit " is not one of " source "'s pins ("
                                                          (if (seq (:pins s)) (str/join ", " (:pins s)) "none") ")")]))]
            (if (seq reasons)
              {:citation c :status :unresolved
               :rule (if (= :fenced (ffirst reasons)) "citation-fenced" "citation-unpinned")
               :reasons (mapv second reasons)}
              (let [{co :dir why :reason} (checkout-of sources-dir source)]
                (cond
                  (nil? co)
                  {:citation c :status :unchecked :rule "citation-unchecked" :reasons [why]}
                  (not (has-commit? co commit))
                  {:citation c :status :unresolved :rule "citation-unresolved"
                   :reasons [(str "the checkout of " source " holds no commit " commit)]}
                  (not (has-path? co commit path))
                  {:citation c :status :unresolved :rule "citation-unresolved"
                   :reasons [(str path " does not exist at " commit " in " source)]}
                  :else
                  {:citation c :status :resolved :rule nil :reasons []})))))))))

(defn citation-finding
  "The finding (or warning) for a resolved citation `r`
  ({:citation :file :line :status :rule :reasons}), naming the citer `who`;
  nil when it resolved."
  [who {:keys [citation file line status rule reasons]}]
  (case status
    :unresolved (command/finding file line rule
                                 (str who " cites " citation ", which does not resolve: " (str/join "; " reasons)))
    :unchecked (command/warning file line rule
                                (str who " cites " citation ", which was not checked: " (str/join "; " reasons)))
    nil))

;; ---------------------------------------------------------------------------
;; The needs' citations

(defn need-citations
  "Every citation the needs file (`file`, text `text`) holds:
  [{:citation :file :line :need}]; nil when it is not readable (the spec
  check reports that)."
  [file text]
  (let [v (try (edn/read-string text) (catch Exception _ nil))
        lines (vec (str/split-lines (or text "")))]
    (when (map? v)
      (vec (for [[id n] (sort-by (comp str key) v)
                 :let [id (str id)
                       start (or (line-of text (re-pattern (str "\"" (java.util.regex.Pattern/quote id) "\""))) 1)]
                 c (when (map? n) (:sources n))
                 :let [c (str c)
                       q (str "\"" c "\"")
                       at (or (some (fn [i] (when (str/includes? (get lines (dec i) "") q) i))
                                    (range start (inc (count lines))))
                              start)]]
             {:citation c :file file :line at :need id})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- sources-dir
  "The --sources directory, absolute, or nil; cannot run when it is not one."
  [req]
  (when-let [d (get-in req [:opts :sources])]
    (let [f (command/path req d)]
      (when-not (fs/directory? f)
        (command/cannot-run! (str "the --sources directory " d " does not exist")))
      (str (fs/absolutize f)))))

(defn index-knowledge
  "Reads the index, the needs' and the wiki's citations, and resolves them:
  the KnowledgeIndexed payload, its :findings being every finding and warning."
  [root config sdir]
  (let [idx (read-index root config)
        head (when-not (seq (:findings idx)) (index-at-head root (:file idx)))
        sources (:sources idx)
        needs-file (or (:needs config) (str (or (:spec-dir config) "dhf/spec") "/needs.edn"))
        nf (command/path root needs-file)
        needs (when (fs/regular-file? nf) (need-citations needs-file (slurp (str nf))))
        need-res (vec (for [c needs] (merge c (resolve-citation sources sdir (:citation c)))))
        wiki-check (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki)
        wiki (wiki-check root config {:sources sources :sources-dir sdir})
        findings (vec (concat (:findings idx)
                              (index-findings root config idx)
                              (when head (dropped-pin-findings idx head))
                              (fence-findings idx sdir)
                              (keep #(citation-finding (:need %) %) need-res)
                              (:findings wiki)))]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced]) sources)
     :pages (vec (:pages wiki))
     :citations (mapv #(select-keys % [:citation :file :line :need :status :reasons])
                      (concat need-res (:citations wiki)))
     :findings findings}))

(defn check
  "The knowledge stage of `rdm-spec check` and `rdm-spec publish`: indexes
  the sources and the wiki, resolves every citation, publishes
  KnowledgeIndexed and reports its findings."
  [req]
  (let [payload (index-knowledge (:root req) (:config req) (sources-dir req))
        findings (:findings payload)
        errors (remove command/warning? findings)
        count-of (fn [st] (count (filter #(= st (:status %)) (:citations payload))))]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      (update payload :findings (fn [fs] (mapv #(select-keys % [:file :line :rule :message]) fs))))
    {:result {:knowledge {:sources (mapv :id (:sources payload))
                          :pages (:pages payload)
                          :citations (:citations payload)
                          :resolved (count-of :resolved)
                          :unresolved (count-of :unresolved)
                          :unchecked (count-of :unchecked)
                          :passed? (empty? errors)}}
     :findings findings}))
