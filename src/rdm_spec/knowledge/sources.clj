(ns rdm-spec.knowledge.sources
  "Sources (dhf/spec/knowledge/sources.feature): reads the provenance records,
  the sources index, and resolves every citation of the needs and the wiki's
  pages against its source's pins, its fenced paths and, when --sources DIR
  gives one, the source's checkout DIR/<id>. Publishes KnowledgeIndexed.

  A pin is dropped when the index as it stands lacks a pin some earlier
  commit of it had (spec-0121); a pin removed and later restored is not
  dropped (spec-0365)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

;; ---------------------------------------------------------------------------
;; Citations

(def citation-re #"([A-Za-z0-9][A-Za-z0-9_.-]*):([^\s@`'\"]+)@([0-9a-f]{7,40})")

(defn parse-citation
  "A citation, <source>:<path>@<commit>, as {:citation :source :path :commit}; nil when s is none."
  [s]
  (when-let [[c src path commit] (and (string? s) (re-matches citation-re (str/trim s)))]
    {:citation c :source src :path path :commit commit}))

(defn page-citations
  "Every citation in a page's footnote definitions, [{:citation :source :path :commit :line}]."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (str text)))
             :when (re-find #"^\s*\[\^[^\]]+\]:" l)
             [c] (re-seq citation-re l)]
         (assoc (parse-citation c) :line (inc i)))))

(defn fenced-by
  "The fenced entry that covers path, or nil. \".\" fences the whole source."
  [fenced path]
  (first (filter (fn [f]
                   (let [f (str/replace (str f) #"/+$" "")]
                     (or (= f ".") (= f "") (= path f) (str/starts-with? path (str f "/")))))
                 fenced)))

(defn pin-of
  "The pin among pins that commit names (the same commit, either written
  longer), or nil."
  [pins commit]
  (first (filter #(or (str/starts-with? commit %) (str/starts-with? % commit)) (map str pins))))

;; ---------------------------------------------------------------------------
;; Dropped pins

(defn- pins-in [index] (set (for [[id v] index :when (map? v) p (:pins v)] [id (str p)])))

(defn dropped-pins
  "Every pin some earlier commit of the index had that the index as it
  stands lacks: [{:source :pin :commit}], :commit the commit that last
  dropped it (the first commit after its last one holding it), nil when only
  the index as it stands lacks it. history is [[commit index] ...], oldest
  first; an unreadable index (nil) is skipped."
  [history current]
  (let [now (pins-in current)
        readable (filter (comp map? second) history)
        ever (distinct (for [[_ idx] readable sp (sort (pins-in idx))] sp))]
    (vec
     (for [[src pin :as sp] ever
           :when (not (now sp))]
       (let [last-holding (last (keep-indexed (fn [i [_ idx]] (when ((pins-in idx) sp) i)) readable))
             dropper (first (drop (inc last-holding) readable))]
         {:source src :pin pin :commit (first dropper)})))))

;; ---------------------------------------------------------------------------
;; Reading

(defn key-line
  "The line where the index names the key k first, comments aside; nil when none."
  [text k]
  (let [re (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote (str k)) "(?![\\w-])"))]
    (first (keep-indexed (fn [i l] (when (and (not (re-find #"^\s*;" l)) (re-find re (str/replace l #";.*$" ""))) (inc i)))
                         (str/split-lines (str text))))))

(defn- text-line [text s]
  (when s (first (keep-indexed (fn [i l] (when (str/includes? l s) (inc i))) (str/split-lines (str text))))))

(defn- read-index [text]
  (try (let [v (cmd/read-edn text)] (if (or (nil? v) (map? v)) (or v {}) ::unreadable))
       (catch Exception _ ::unreadable)))

(defn- history
  "[[commit index] ...] of every commit of the index reachable from rev
  (HEAD for the working tree), oldest first."
  [root rev path]
  (let [from (if (cmd/working-tree? rev) "HEAD" rev)
        {:keys [exit out]} (cmd/git root "log" "--format=%H" "--reverse" from "--" path)]
    (if (= 0 exit)
      (vec (for [c (remove str/blank? (str/split-lines out))]
             (let [idx (read-index (cmd/read-at root c path))]
               [c (when (map? idx) idx)])))
      [])))

(defn- short-commit [c] (when c (subs c 0 (min 7 (count c)))))

;; ---------------------------------------------------------------------------
;; Resolving

(defn- git-ok? [dir & args] (= 0 (:exit (apply cmd/git dir args))))

(defn- resolve-citation
  "A citation with its :status and :reasons, and the rule of its failure."
  [{:keys [source path commit] :as c} index checkouts]
  (let [src (get index (keyword source))
        fail (fn [rule why] (assoc c :status :unresolved :reasons [why] :rule rule))]
    (cond
      (not (map? src))
      (fail "citation-unknown-source" (str "no source " source " is recorded in the sources index"))
      (:retired src)
      (fail "citation-retired" (str "its source " source " is retired (" (:retired src) "): nothing may cite it"))
      (not (pin-of (:pins src) commit))
      (fail "citation-unpinned" (str "the commit " commit " is not one of " source "'s pins: "
                                     (str/join ", " (map str (:pins src)))))
      (fenced-by (:fenced src) path)
      (let [f (fenced-by (:fenced src) path)]
        (fail "citation-fenced" (str path " is fenced in " source " by its fenced path " f
                                     ": a source's implementation is never cited")))
      (nil? checkouts)
      (assoc c :status :unchecked :rule "citation-unchecked"
             :reasons [(str "not checked: no --sources was given, so no checkout of " source " was read")])
      (not (fs/directory? (fs/path (:dir checkouts) source)))
      (assoc c :status :unchecked :rule "citation-unchecked"
             :reasons [(str "not checked: the directory given with --sources, " (:named checkouts)
                            ", holds no checkout of " source)])
      :else
      (let [co (str (fs/path (:dir checkouts) source))]
        (cond
          (not (git-ok? co "cat-file" "-e" (str commit "^{commit}")))
          (fail "citation-unresolved" (str "the checkout of " source " does not hold the commit " commit))
          (not (git-ok? co "cat-file" "-e" (str commit ":" path)))
          (fail "citation-unresolved" (str "the path " path " does not exist at " commit " in the checkout of " source))
          :else (assoc c :status :resolved :reasons []))))))

(defn- checkouts-of [root opts]
  (when-let [d (:sources opts)]
    (let [abs (fs/absolutize (fs/path root d))]
      (when-not (fs/directory? abs)
        (cmd/cannot-run! (str "the directory given with --sources, " d ", does not exist")))
      {:dir (str abs) :named d})))

(defn stage
  "Reads the sources index, its history and every citation; publishes KnowledgeIndexed."
  [{:keys [root config revision opts]}]
  (let [raw (:raw-dir config "dhf/raw")
        index-path (str raw "/sources.edn")
        itext (cmd/read-at root revision index-path)
        parsed (if itext (read-index itext) {})
        index (if (map? parsed) parsed {})
        checkouts (checkouts-of root opts)
        src-line #(key-line itext %)
        needs-path (:needs config "dhf/spec/needs.edn")
        ntext (cmd/read-at root revision needs-path)
        needs (try (let [m (cmd/read-edn ntext)] (if (map? m) m {})) (catch Exception _ {}))
        wiki (:wiki-dir config "dhf/wiki")
        pages (vec (filter #(str/ends-with? % ".md") (cmd/files-at root revision (str wiki "/pages"))))
        ;; the index itself
        index-findings
        (concat
         (when (= ::unreadable parsed)
           [(cmd/finding index-path nil "sources-unreadable" "the sources index is not an EDN map")])
         (for [[id v] (sort-by key index)
               :let [n (name id) line (src-line id)]
               f (if-not (map? v)
                   [(cmd/finding index-path line "source-unreadable" (str "the source " n " is not a map"))]
                   (concat
                    (when (str/blank? (str (:repository v)))
                      [(cmd/finding index-path line "source-no-repository"
                                    (str "the source " n " names no repository"))])
                    (when (empty? (:pins v))
                      [(cmd/finding index-path line "source-no-pin"
                                    (str "the source " n " has no pin: a source is pinned at every commit it is cited at"))])
                    (let [rec (when (:record v) (str raw "/" (:record v)))]
                      (when-not (and rec (cmd/read-at root revision rec))
                        [(cmd/finding index-path line "source-no-record"
                                      (if rec
                                        (str "the record " rec " of the source " n " is not in " raw)
                                        (str "the source " n " names no record in " raw)))]))))]
           f))
        dropped
        (when (map? parsed)
          (for [{:keys [source pin commit]} (dropped-pins (history root revision index-path) index)]
            (cmd/finding index-path (src-line source) "pin-dropped"
                         (str "the source " (name source) " dropped the pin " pin
                              (if commit (str " in the commit " (short-commit commit)) " in the working tree")
                              ": a pin is never dropped; retire a source instead"))))
        fenced-findings
        (when checkouts
          (for [[id v] (sort-by key index)
                :when (map? v)
                :let [co (str (fs/path (:dir checkouts) (name id)))]
                :when (fs/directory? co)
                pin (:pins v)
                f (if-not (git-ok? co "cat-file" "-e" (str pin "^{commit}"))
                    [(cmd/finding index-path (src-line id) "pin-not-in-checkout"
                                  (str "the checkout of " (name id) " does not hold its pin " pin))]
                    (for [p (:fenced v)
                          :when (not (fenced-by [p] "")) ; "." fences everything, so matches
                          :when (str/blank? (:out (cmd/git co "ls-tree" "--name-only" (str pin) "--" (str/replace (str p) #"/+$" ""))))]
                      (cmd/finding index-path (src-line id) "fenced-path-missing"
                                   (str "the fenced path " p " of " (name id) " matches nothing at its pin " pin))))]
            f))
        ;; the citations
        need-entries
        (for [[nid nv] (sort-by (comp name key) needs)
              :let [nid (name nid)]
              s (when (map? nv) (:sources nv))]
          {:need nid :written s :file needs-path
           :line (or (text-line ntext (str "\"" s "\"")) (text-line ntext (str "\"" nid "\"")) (text-line ntext nid))})
        malformed (for [e need-entries :when (not (parse-citation (:written e)))]
                    (cmd/finding (:file e) (:line e) "citation-malformed"
                                 (str (:need e) " cites " (pr-str (str (:written e)))
                                      ", which is not a citation: a citation is <source>:<path>@<commit>")))
        cited (concat
               (for [e need-entries :let [c (parse-citation (:written e))] :when c]
                 (assoc c :file (:file e) :line (:line e) :need (:need e)))
               (for [p pages c (page-citations (cmd/read-at root revision p))]
                 (assoc c :file p :need nil)))
        resolved (mapv #(resolve-citation % index checkouts) cited)
        about (fn [c] (str (when (:need c) (str (:need c) " cites ")) (:citation c) ": " (first (:reasons c))))
        cite-findings (for [c resolved :when (= :unresolved (:status c))]
                        (cmd/finding (:file c) (:line c) (:rule c) (about c)))
        warnings (for [c resolved :when (= :unchecked (:status c))]
                   (cmd/finding (:file c) (:line c) "citation-unchecked" (about c)))
        findings (vec (concat index-findings dropped fenced-findings malformed cite-findings))]
    (bus/publish! :knowledge/sources :KnowledgeIndexed
                  {:revision (if (cmd/working-tree? revision) :working-tree revision)
                   :sources (vec (for [[id v] (sort-by key index) :when (map? v)]
                                   {:id (name id) :repository (:repository v)
                                    :pins (vec (:pins v)) :fenced (vec (:fenced v))}))
                   :pages pages
                   :citations (mapv #(select-keys % [:citation :source :path :commit :file :line :need :status :reasons])
                                    resolved)
                   :findings findings})
    {:findings findings
     :warnings (vec warnings)
     :result {:sources (merge {:resolved 0 :unresolved 0 :unchecked 0}
                              (frequencies (map :status resolved)))}}))
