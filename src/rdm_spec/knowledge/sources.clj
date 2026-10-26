(ns rdm-spec.knowledge.sources
  "The sources (dhf/spec/knowledge/sources.feature): reads the sources index
  and its history, and resolves every citation of the needs and the wiki
  against its source's pins, fenced paths and, given --sources DIR, its
  checkout. Publishes KnowledgeIndexed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def citation-re #"([A-Za-z0-9][\w.-]*):([^\s`@\"'()\[\]]+)@([0-9a-fA-F]{4,40})")

(defn parse-citation
  "{:source :path :commit} of a citation `source:path@commit`, or nil."
  [s]
  (when-let [[_ src path commit] (re-matches citation-re (str/trim (str s)))]
    {:source src :path path :commit commit :citation (str/trim s)}))

(defn citations-in
  "Every citation written in a text."
  [text]
  (for [[c src path commit] (re-seq citation-re (str text))]
    {:citation c :source src :path path :commit commit}))

(defn fenced? [fences path]
  (some #(or (= "." %) (= % path) (str/starts-with? path (str (str/replace % #"/$" "") "/"))) fences))

(defn pinned? [pins commit]
  (some #(or (str/starts-with? commit %) (str/starts-with? % commit)) pins))

(defn line-of [text s]
  (when (and text s)
    (when-let [i (str/index-of text s)]
      (inc (count (filter #{\newline} (subs text 0 i)))))))

(defn- in-checkout?
  "Whether `commit:path` exists in the git checkout co; :no-commit when the
  checkout lacks the commit."
  [co commit path]
  (let [c (cmd/git co "cat-file" "-e" (str commit "^{commit}"))]
    (if-not (= 0 (:exit c))
      :no-commit
      (= 0 (:exit (cmd/git co "cat-file" "-e" (str commit ":" path)))))))

(defn judge
  "Judges one citation: {:status :resolved|:unresolved|:unchecked :rule :reason}.
  checkouts is a directory of checkouts named by source id, or nil."
  [index checkouts {:keys [source path commit citation]}]
  (let [src (get index source)]
    (cond
      (nil? src)
      {:status :unresolved :rule "citation-unknown-source"
       :reason (str "cites the source " source ", which dhf/raw/sources.edn does not record")}
      (:retired src)
      {:status :unresolved :rule "citation-retired"
       :reason (str "cites " source ", whose source is retired (" (:retired src) "): nothing may cite it")}
      (not (pinned? (:pins src) commit))
      {:status :unresolved :rule "citation-unpinned"
       :reason (str "cites " source " at " commit ", which is not one of its pins: " (str/join ", " (:pins src)))}
      (fenced? (:fenced src) path)
      {:status :unresolved :rule "citation-fenced"
       :reason (str "cites " path ", which is fenced in " source " (fenced: "
                    (str/join ", " (filter #(fenced? [%] path) (:fenced src))) "): a source's implementation is never cited")}
      (nil? checkouts)
      {:status :unchecked :rule "citation-unchecked"
       :reason (str "not checked: no checkout of " source " was given (rdm-spec check --sources DIR)")}
      (not (fs/directory? (fs/path checkouts source)))
      {:status :unchecked :rule "citation-unchecked"
       :reason (str "not checked: the sources directory holds no checkout " source "/")}
      :else
      (let [r (in-checkout? (str (fs/path checkouts source)) commit path)]
        (case r
          true {:status :resolved}
          :no-commit {:status :unresolved :rule "citation-unresolved"
                      :reason (str "the checkout of " source " does not hold the pin " commit)}
          {:status :unresolved :rule "citation-unresolved"
           :reason (str path " does not exist at the pin " commit " of " source)})))))

(defn dropped-pins
  "Every pin a later version of the index dropped: versions are [[by index] ...], oldest first."
  [versions]
  (vec
   (distinct
    (for [[[_ older] [by newer]] (partition 2 1 versions)
          [id src] older
          :let [kept (set (get-in newer [id :pins]))]
          pin (:pins src)
          :when (not (kept pin))]
      {:source (name id) :pin pin :by by}))))

(defn- read-index-text [text]
  (try {:value (cmd/read-edn text)} (catch Exception e {:error (ex-message e)})))

(defn index-versions
  "The index at every commit that changed it, then the working tree when it differs."
  [root path]
  (let [{:keys [exit out]} (cmd/git root "log" "--format=%H" "--reverse" "--" path)
        commits (if (= 0 exit) (remove str/blank? (str/split-lines out)) [])
        committed (for [c commits
                        :let [v (:value (read-index-text (cmd/read-at root c path)))]
                        :when (map? v)]
                    [(subs c 0 7) v])
        wt (:value (read-index-text (cmd/read-at root nil path)))]
    (cond-> (vec committed)
      (and (map? wt) (not= wt (second (last committed)))) (conj ["the working tree" wt]))))

(defn wiki-files [root rev config]
  (let [d (:wiki-dir config "dhf/wiki")]
    (vec (filter #(str/ends-with? % ".md")
                 (concat (when (cmd/read-at root rev (str d "/index.md")) [(str d "/index.md")])
                         (cmd/files-at root rev (str d "/pages")))))))

(defn stage
  "The sources' stage: index, history and citations; publishes KnowledgeIndexed."
  [{:keys [root config revision opts]}]
  (let [raw (:raw-dir config "dhf/raw")
        path (str raw "/sources.edn")
        text (cmd/read-at root revision path)
        {:keys [value error]} (read-index-text text)
        checkouts (when-let [d (:sources opts)]
                    (let [a (fs/absolutize (fs/path root d))]
                      (when-not (fs/directory? a) (cmd/cannot-run! (str "the sources directory " d " does not exist")))
                      (str a)))
        index (into {} (for [[k v] (when (map? value) value)] [(name k) v]))
        f (fn [needle rule msg] (cmd/finding path (line-of text needle) rule msg))
        index-findings
        (concat
         (cond
           (nil? text) [(cmd/finding path nil "sources-unreadable" "the sources index is missing")]
           (not (map? value)) [(cmd/finding path nil "sources-unreadable" (str "the sources index is not an EDN map" (when error (str ": " error))))])
         (for [[id s] (sort-by key index)
               x (concat
                  (when (empty? (:pins s)) [(f (str ":" id) "source-no-pin" (str "the source " id " has no pin"))])
                  (when (str/blank? (:repository s)) [(f (str ":" id) "source-no-repository" (str "the source " id " names no repository"))])
                  (when (or (str/blank? (:record s)) (not (cmd/read-at root revision (str raw "/" (:record s)))))
                    [(f (str ":" id) "source-no-record"
                        (str "the source " id "'s record " (or (:record s) "(none named)") " is not in " raw))]))]
           x)
         (when (cmd/working-tree? revision)
           (for [{:keys [source pin by]} (dropped-pins (index-versions root path))]
             (f (str ":" source) "source-pin-dropped"
                (str "the source " source " dropped its pin " pin " in " (if (= by "the working tree") by (str "the commit " by))
                     ": a pin is never dropped; retire a source instead")))))
        ;; fenced paths that match nothing, against the checkouts given
        fence-findings
        (when checkouts
          (for [[id s] (sort-by key index)
                :let [co (fs/path checkouts id)]
                :when (and (fs/directory? co) (not (:retired s)))
                pin (:pins s)
                fence (:fenced s)
                :when (not= "." fence)
                :let [r (in-checkout? (str co) pin fence)]
                :when (not (true? r))]
            (f (str "\"" fence "\"") "fence-matches-nothing"
               (str "the fenced path " fence " of the source " id " matches nothing at the pin " pin))))
        needs-path (:needs config "dhf/spec/needs.edn")
        needs-text (cmd/read-at root revision needs-path)
        needs (try (cmd/read-edn needs-text) (catch Exception _ nil))
        need-cites (for [[nid n] (when (map? needs) (sort-by key needs)) s (:sources n)]
                     (let [p (parse-citation s)]
                       (assoc (or p {:citation (str s)}) :need (name nid) :file needs-path
                              :line (line-of needs-text (str "\"" s "\"")) :malformed (nil? p))))
        pages (wiki-files root revision config)
        wiki-cites (for [p pages
                         :let [t (cmd/read-at root revision p)]
                         [i l] (map-indexed vector (str/split-lines (str t)))
                         :when (re-find #"^\s*\[\^[^\]]+\]:" l)
                         c (citations-in l)]
                     (assoc c :file p :line (inc i) :need nil
                            :footnote (second (re-find #"^\s*\[\^([^\]]+)\]:" l))))
        judged (for [c (concat need-cites wiki-cites)]
                 (if (:malformed c)
                   (assoc c :status :unresolved :rule "citation-malformed"
                          :reason (str (:need c) " cites \"" (:citation c) "\", which is not a citation source:path@commit"))
                   (merge c (judge index checkouts c))))
        where (fn [c] (cond (:need c) (str (:need c) " ")
                            (:footnote c) (str "the footnote [^" (:footnote c) "] ")
                            :else ""))
        cite-findings (for [c judged :when (= :unresolved (:status c))]
                        (cmd/finding (:file c) (:line c) (:rule c)
                                     (if (= "citation-malformed" (:rule c))
                                       (:reason c)
                                       (str (where c) "cites " (:citation c) ": " (:reason c)))))
        cite-warnings (for [c judged :when (= :unchecked (:status c))]
                        (cmd/finding (:file c) (:line c) "citation-unchecked" (str (where c) "cites " (:citation c) ", " (:reason c))))
        findings (vec (concat index-findings fence-findings cite-findings))]
    (bus/publish! :knowledge/sources :KnowledgeIndexed
                  {:revision (if (cmd/working-tree? revision) :working-tree revision)
                   :sources (vec (for [[id s] (sort-by key index)]
                                   {:id id :repository (:repository s) :pins (vec (:pins s)) :fenced (vec (:fenced s))
                                    :retired (:retired s)}))
                   :pages pages
                   :citations (vec (for [c judged]
                                     (-> (select-keys c [:citation :source :path :commit :file :line :need :status])
                                         (assoc :reasons (if (:reason c) [(:reason c)] [])))))
                   :findings findings})
    {:findings findings :warnings (vec cite-warnings)}))
