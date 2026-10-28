(ns rdm-spec.knowledge.sources
  "Component Sources (knowledge context): reads the provenance records (the
  sources index and each source's record), and resolves every citation of
  the needs and the wiki against its source's pins, its fenced paths and,
  when a checkout is given, the cited path at the pin. Publishes
  KnowledgeIndexed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

(def citation-re #"([A-Za-z0-9][A-Za-z0-9_.-]*):([^\s`@\"'(),]+)@([0-9a-fA-F]{7,40})")

(defn index-path [cfg] (str (:raw-dir cfg) "/sources.edn"))

(defn read-index
  "The sources index as {:sources {id m} :findings}."
  [tree cfg]
  (let [p (index-path cfg)
        r (project/read-edn-file tree p)]
    (cond
      (:missing r) {:sources {} :findings (if (fs/directory? (project/abs tree (:raw-dir cfg)))
                                             [(project/finding p "sources-unreadable" "the sources index is missing")] [])}
      (:error r) {:sources {} :findings [(project/finding p (get-in r [:error :line]) "sources-unreadable"
                                                          (str "the sources index is not readable EDN: " (get-in r [:error :message])))]}
      (not (map? (:value r))) {:sources {} :findings [(project/finding p "sources-unreadable" "the sources index is not a map")]}
      :else {:sources (:value r) :findings []})))

(defn line-of [text s]
  (when text
    (some (fn [[i l]] (when (str/includes? l s) (inc i))) (map-indexed vector (str/split-lines text)))))

(defn source-findings [tree cfg sources]
  (let [p (index-path cfg)
        text (project/read-text tree p)]
    (for [[id m] sources
          :let [n (name id)
                line (or (line-of text (str id)) 1)
                f #(project/finding p line %1 (str "the source " n " " %2))]
          finding (concat
                   (when-not (and (string? (:repository m)) (not (str/blank? (:repository m))))
                     [(f "source-no-repository" "names no repository")])
                   (when-not (and (sequential? (:pins m)) (seq (:pins m)))
                     [(f "source-no-pin" "has no pin: a source is pinned to the commits it is cited at")])
                   (if-not (string? (:record m))
                     [(f "source-no-record" "names no record file")]
                     (when-not (fs/regular-file? (project/abs tree (str (:raw-dir cfg) "/" (:record m))))
                       [(f "source-no-record" (str "has no record file " (:record m) " in " (:raw-dir cfg)))])))]
      finding)))

;; ------------------------------------------------------------------ dropped pins

(defn dropped-pin-findings
  "A pin is dropped when the index as it stands lacks a pin some earlier commit of it had."
  [cfg sources]
  (let [root (project/root)
        p (index-path cfg)]
    (when (project/git-repo? root)
      (let [shas (remove str/blank? (str/split-lines (str (:out (project/git root "log" "--format=%H" "--reverse" "--" p)))))
            states (concat
                    (for [sha shas]
                      (let [r (project/git root "show" (str sha ":./" p))
                            v (when (= 0 (:exit r)) (:value (project/read-edn-text (:out r))))]
                        {:at (subs sha 0 7) :pins (when (map? v) (set (for [[id m] v pin (when (sequential? (:pins m)) (:pins m))] [id pin])))}))
                    [{:at :working-tree :pins (set (for [[id m] sources pin (when (sequential? (:pins m)) (:pins m))] [id pin]))}])
            states (vec states)
            current (:pins (peek states))
            ever (into #{} (mapcat :pins (butlast states)))]
        (for [[id pin :as k] (sort-by str ever)
              :when (not (contains? current k))
              :let [last-had (last (keep-indexed (fn [i s] (when (contains? (:pins s) k) i)) states))
                    dropper (:at (nth states (inc last-had)))]]
          (project/finding p (or (line-of (project/read-text root p) (str id)) 1) "pin-dropped"
                           (str "the source " (name id) " dropped the pin " pin " it had, in "
                                (if (= :working-tree dropper) "the working tree" (str "the commit " dropper))
                                ": a pin is never dropped")))))))

;; ------------------------------------------------------------------ citations

(defn need-citations [tree spec]
  (let [p (:needs spec)
        text (project/read-text tree p)
        r (when text (project/read-edn-text text))
        needs (when (map? (:value r)) (:value r))]
    (for [[id m] (sort-by key needs)
          s (when (sequential? (:sources m)) (:sources m))]
      (let [s (str s)
            m (re-matches citation-re (str/trim s))]
        (if m
          {:citation (first m) :source (nth m 1) :path (nth m 2) :commit (nth m 3) :file p
           :line (or (line-of text s) 1) :need (str id)}
          {:malformed s :file p :line (or (line-of text s) 1) :need (str id)})))))

(defn wiki-files
  "The wiki's index and every page, relative to the project."
  [tree cfg]
  (let [d (project/abs tree (:wiki-dir cfg))]
    (if (fs/directory? d)
      (vec (concat (when (fs/regular-file? (str d "/index.md")) [(str (:wiki-dir cfg) "/index.md")])
                   (sort (for [f (fs/glob (str d "/pages") "**.md")] (project/rel tree f)))))
      [])))

(defn wiki-citations [tree cfg]
  (for [f (wiki-files tree cfg)
        [i line] (map-indexed vector (str/split-lines (or (project/read-text tree f) "")))
        :when (re-find #"^\s*\[\^[^\]]+\]:" line)
        m (re-seq citation-re line)]
    {:citation (first m) :source (nth m 1) :path (nth m 2) :commit (nth m 3) :file f :line (inc i) :need nil
     :footnote (second (re-find #"^\s*\[\^([^\]]+)\]:" line))}))

(defn- pin-matches? [pin commit]
  (let [a (str/lower-case (str pin)) b (str/lower-case (str commit))]
    (or (str/starts-with? a b) (str/starts-with? b a))))

(defn- fenced-by [m path]
  (some (fn [f] (let [f (str/replace (str f) #"/+$" "")]
                  (when (or (= f ".") (= path f) (str/starts-with? path (str f "/"))) f)))
        (:fenced m)))

(defn checkout-of [sources-dir id]
  (when sources-dir
    (let [d (project/abs sources-dir (name id))]
      (when (fs/directory? d) d))))

(defn in-checkout? [dir commit path]
  (= 0 (:exit (project/git dir "cat-file" "-e" (str commit ":" path)))))

(defn resolve-citation
  "A citation with its :status and :reasons, and the finding or warning it makes."
  [sources sources-dir c]
  (let [who (if (:need c) (str (:need c) " cites " (:citation c)) (str "the footnote "
                                                                       (when (:footnote c) (str "[^" (:footnote c) "] "))
                                                                       "cites " (:citation c)))
        id (keyword (:source c))
        m (get sources id)
        bad (fn [rule why] (assoc c :status :unresolved :reasons [why]
                                  :finding (project/finding (:file c) (:line c) rule (str who ": " why))))]
    (cond
      (nil? m) (bad "citation-source-unknown" (str "it names no recorded source " (:source c) " in the sources index"))
      (:retired m) (bad "citation-retired" (str "its source " (:source c) " is retired (" (:retired m) "), and nothing may cite it"))
      (not (some #(pin-matches? % (:commit c)) (:pins m)))
      (bad "citation-pin-unknown" (str (:commit c) " is not one of " (:source c) "'s pins (" (str/join ", " (:pins m)) ")"))
      (fenced-by m (:path c)) (bad "citation-fenced" (str (:path c) " is in " (:source c) "'s fenced path "
                                                          (fenced-by m (:path c)) ": a source's implementation is never cited"))
      :else
      (if-let [ck (checkout-of sources-dir id)]
        (cond
          (not= 0 (:exit (project/git ck "cat-file" "-e" (str (:commit c) "^{commit}"))))
          (bad "citation-unresolved" (str "the checkout of " (:source c) " does not hold the commit " (:commit c)))
          (not (in-checkout? ck (:commit c) (:path c)))
          (bad "citation-unresolved" (str "the checkout of " (:source c) " does not hold " (:path c) " at " (:commit c)))
          :else (assoc c :status :resolved :reasons []))
        (let [why (str "no checkout of " (:source c) " was given (rdm-spec check --sources DIR), so its path was not checked")]
          (assoc c :status :unchecked :reasons [why]
                 :warning (project/warning (:file c) (:line c) "citation-unchecked" (str who ": " why))))))))

(defn fence-findings
  "With a checkout, every fenced path matches something at every pin."
  [cfg sources sources-dir]
  (for [[id m] sources
        :let [ck (checkout-of sources-dir id)]
        :when ck
        pin (:pins m)
        :when (= 0 (:exit (project/git ck "cat-file" "-e" (str pin "^{commit}"))))
        f (:fenced m)
        :when (and (not= "." f) (not (in-checkout? ck pin f)))]
    (project/finding (index-path cfg) "source-fence-unmatched"
                     (str "the source " (name id) " fences " f ", which matches nothing at its pin " pin))))

(defn index
  "Reads the sources and the wiki's citations at TREE: the KnowledgeIndexed
  data, with :warnings beside its findings."
  [tree cfg sources-dir]
  (let [{:keys [sources findings]} (read-index tree cfg)
        sources (if (map? sources) sources {})
        cites (concat (mapcat #(need-citations tree %) (project/specs cfg)) (wiki-citations tree cfg))
        malformed (for [c cites :when (:malformed c)]
                    (project/finding (:file c) (:line c) "citation-malformed"
                                     (str (:need c) " cites \"" (:malformed c) "\", which is not a citation <source>:<path>@<commit>")))
        resolved (for [c cites :when (not (:malformed c))] (resolve-citation sources sources-dir c))
        all-findings (vec (concat findings (source-findings tree cfg sources) (dropped-pin-findings cfg sources)
                                  malformed (keep :finding resolved) (fence-findings cfg sources sources-dir)))
        warnings (vec (keep :warning resolved))]
    {:revision :working-tree
     :sources (vec (for [[id m] sources] {:id id :repository (:repository m) :pins (vec (:pins m)) :fenced (vec (:fenced m))}))
     :pages (vec (filter #(str/includes? % "/pages/") (wiki-files tree cfg)))
     :citations (vec (for [c resolved] (dissoc c :finding :warning :footnote)))
     :findings all-findings
     :warnings warnings}))

(defn index-stage
  "Indexes the sources and resolves every citation; publishes KnowledgeIndexed."
  [{:keys [cfg opts]}]
  (let [sources-dir (some-> (get opts "--sources") project/abs)]
    (when (and sources-dir (not (fs/directory? sources-dir)))
      (project/cannot-run! (str "--sources names " (get opts "--sources") ", which is not a directory")))
    (let [k (index (project/root) cfg sources-dir)]
      (bus/publish! :knowledge/sources :KnowledgeIndexed (assoc k :findings (into (:findings k) (:warnings k))))
      {:result {:sources {:sources (count (:sources k)) :citations (count (:citations k))
                          :unchecked (count (filter #(= :unchecked (:status %)) (:citations k)))}}
       :findings (:findings k)
       :warnings (:warnings k)})))

(defn source-url
  "Where a reader reads the cited PATH at COMMIT of the source ID, or nil."
  [sources id path commit]
  (when-let [repo (:repository (get sources (keyword id)))]
    (let [repo (str/replace repo #"(\.git)?/*$" "")]
      (str repo "/blob/" commit "/" path))))

(defn publish-stage
  "Indexes the sources and the wiki for a reader, publishing KnowledgeIndexed;
  its findings are the check's, never a reader's."
  [{:keys [cfg]}]
  (let [k (index (project/root) cfg nil)]
    (bus/publish! :knowledge/sources :KnowledgeIndexed (assoc k :findings (into (:findings k) (:warnings k))))
    {:result nil :findings [] :warnings []}))
