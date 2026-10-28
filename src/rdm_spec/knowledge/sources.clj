(ns rdm-spec.knowledge.sources
  "Reads the provenance records and resolves citations against their pins
  (dhf/spec/03-ground-the-spec/sources.feature). Citations are read from the
  needs and the wiki, nothing else; checkouts are given on the command line,
  one per source, named by its id."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]))

(def citation-re #"([A-Za-z0-9][A-Za-z0-9._-]*):([^@\s`'\"]+)@([0-9a-fA-F]{7,40})")

(defn parse-citation
  "A citation <source>:<path>@<commit>, or nil when the string is not one."
  [s]
  (when-let [[_ source path commit] (re-matches citation-re (str/trim (str s)))]
    {:source source :path path :commit commit}))

(defn pin-match? [a b] (let [a (str/lower-case a) b (str/lower-case b)] (or (str/starts-with? a b) (str/starts-with? b a))))

(defn fenced-by
  "The fenced path that covers path, or nil."
  [fenced path]
  (some #(when (or (= "." %) (= path %) (str/starts-with? path (str (str/replace % #"/$" "") "/"))) %) fenced))

(defn- git-has? [checkout commit path]
  (zero? (:exit (project/sh checkout "git" "cat-file" "-e" (str commit ":" path)))))

(defn resolve-citation
  "Resolves a parsed citation against the index and, when given, the checkouts directory.
  {:status :resolved | :unresolved | :unchecked, :why, :reason}."
  [{:keys [source path commit] :as c} idx checkouts]
  (let [s (get idx (keyword source))]
    (cond
      (nil? s) {:status :unresolved :why :unknown-source :reason (str "no source " source " is recorded in the sources index")}
      (:retired s) {:status :unresolved :why :retired :reason (str "its source " source " is retired (" (:retired s) "): nothing may cite it")}
      (not (some #(pin-match? commit %) (:pins s)))
      {:status :unresolved :why :unpinned :reason (str "the commit " commit " is not one of " source "'s pins: " (str/join ", " (:pins s)))}
      (fenced-by (:fenced s) path)
      {:status :unresolved :why :fenced :reason (str "it cites " path ", inside " source "'s fenced path " (fenced-by (:fenced s) path) ": a source's implementation is never cited")}
      (nil? checkouts) {:status :unchecked :why :no-sources :reason "no --sources was given, so its path was not checked"}
      (not (fs/directory? (fs/path checkouts source)))
      {:status :unchecked :why :no-checkout :reason (str "no checkout of " source " in the --sources directory, so its path was not checked")}
      (git-has? (str (fs/path checkouts source)) commit path) {:status :resolved}
      :else {:status :unresolved :why :missing-path :reason (str "no " path " at " commit " in " source)})))

(def why-rule {:unknown-source "citation-unknown-source" :retired "citation-retired" :unpinned "citation-unpinned"
               :fenced "citation-fenced" :missing-path "citation-unresolved"})

(defn source-findings
  "A source is indexed with its repository, its pins and its record."
  [idx raw-files file text]
  (vec (mapcat (fn [[k s]]
                 (let [line (project/line-of text (str k))
                       f (fn [rule msg] (project/finding file line rule msg))]
                   (concat
                    (when (empty? (:pins s)) [(f "source-no-pin" (str "the source " (name k) " has no pin"))])
                    (when (str/blank? (str (:repository s))) [(f "source-no-repository" (str "the source " (name k) " names no repository"))])
                    (cond (nil? (:record s)) [(f "source-no-record" (str "the source " (name k) " names no record"))]
                          (not (contains? raw-files (:record s)))
                          [(f "source-no-record" (str "the source " (name k) "'s record " (:record s) " is not in the raw directory"))]))))
               idx)))

(defn dropped-pins
  "[[source pin commit]] for every pin some earlier version of the index had
  that the index as it stands lacks, with the commit that dropped it.
  history is [[commit index] ...], oldest first; current is the index as it stands."
  [history current]
  (let [versions (conj (vec history) ["the working tree" current])]
    (vec (distinct
          (for [[k s] current
                pin (distinct (mapcat (fn [[_ ix]] (get-in ix [k :pins])) history))
                :when (not (some #(pin-match? pin %) (:pins s)))
                :let [last-with (last (keep-indexed (fn [i [_ ix]] (when (some #(pin-match? pin %) (get-in ix [k :pins])) i)) versions))
                      dropper (first (nth versions (inc last-with)))]]
            [(name k) pin dropper])))))

(defn footnote-citations
  "The citations of a Markdown file's footnote definitions: [{:citation :file :line :footnote}]."
  [file text]
  (vec (for [[i line] (map-indexed vector (str/split-lines (str text)))
             :let [[_ label] (re-find #"^\s*\[\^([^\]]+)\]:" line)]
             :when label
             [m] (re-seq citation-re line)]
         {:citation m :file file :line (inc i) :footnote label})))

(defn- history [root rel]
  (let [r (project/git root "log" "--format=%H" "--reverse" "--" rel)]
    (if (and (zero? (:exit r)) (not (str/blank? (:out r))))
      (vec (for [h (str/split-lines (str/trim (:out r)))
                 :let [s (project/git root "show" (str h ":./" rel))]
                 :when (zero? (:exit s))]
             [(subs h 0 7) (try (edn/read-string (:out s)) (catch Exception _ {}))]))
      [])))

(defn need-citations
  "The citations of the needs, each on the line it is written: [{:citation :need :file :line :raw}]."
  [needs needs-file text]
  (vec (for [n needs
             src (:sources n)]
         {:raw src :need (:id n) :file needs-file
          :line (or (project/line-of text (pr-str (str src)) (or (:line n) 1)) (:line n))})))

(declare parse-specs-lenient)

(defn index
  "check's stage: reads the sources index, resolves every citation of the needs
  and the wiki, and publishes KnowledgeIndexed."
  [run]
  (let [root (:root run) cfg (:cfg run)
        raw (:raw-dir cfg)
        sfile (str raw "/sources.edn")
        checkouts (some-> (get-in run [:opts "--sources"]) (#(str (fs/absolutize (fs/path root %)))))
        _ (when (and checkouts (not (fs/directory? checkouts)))
            (project/cannot-run! (str "the --sources directory " (get-in run [:opts "--sources"]) " does not exist")))
        stext (when (fs/exists? (fs/path root sfile)) (slurp (str (fs/path root sfile))))
        [idx idx-finding] (cond (nil? stext) [{} nil]
                                :else (try (let [m (edn/read-string stext)]
                                             (if (map? m) [m nil] [{} (project/finding sfile 1 "sources-unreadable" "the sources index is not a map")]))
                                           (catch Exception e [{} (project/finding sfile 1 "sources-unreadable" (ex-message e))])))
        raw-files (set (map #(str (fs/relativize (fs/path root raw) %)) (when (fs/directory? (fs/path root raw)) (fs/list-dir (fs/path root raw)))))
        specs (parse-specs-lenient root cfg)
        need-cits (vec (mapcat (fn [{:keys [needs needs-file text]}] (need-citations needs needs-file text)) specs))
        wiki (:wiki-dir cfg)
        pages (filter #(str/ends-with? % ".md") (project/files-under root wiki))
        wiki-cits (vec (mapcat #(footnote-citations % (slurp (str (fs/path root %)))) pages))
        resolved (vec (for [c (concat need-cits wiki-cits)
                            :let [cit (or (:citation c) (:raw c))
                                  p (parse-citation cit)]]
                        (assoc c :citation cit :parsed p
                               :res (if p (resolve-citation p idx checkouts) {:status :unresolved :why :malformed}))))
        where (fn [c] (if (:need c) (:need c) (str "the footnote [^" (:footnote c) "]")))
        cit-findings
        (vec (for [c resolved
                   :let [{:keys [status why reason]} (:res c)]
                   :when (not= status :resolved)]
               (if (= why :malformed)
                 (project/finding (:file c) (:line c) "citation-malformed"
                                  (str (:need c) " cites \"" (:citation c) "\", which is not a citation <source>:<path>@<commit>"))
                 (if (= status :unchecked)
                   (project/warning (:file c) (:line c) "citation-unchecked" (str (:citation c) " (" (where c) ") was not checked: " reason))
                   (project/finding (:file c) (:line c) (why-rule why) (str (where c) " cites " (:citation c) ": " reason))))))
        fence-findings
        (when checkouts
          (vec (for [[k s] idx
                     :let [co (fs/path checkouts (name k))]
                     :when (and (fs/directory? co) (not (:retired s)))
                     pin (:pins s)
                     f (:fenced s)
                     :when (and (not= "." f) (not (git-has? (str co) pin f)))]
                 (project/finding sfile (project/line-of stext (str k)) "source-fenced-missing"
                                  (str "the source " (name k) " fences " f ", which matches nothing at its pin " pin)))))
        dropped (when stext
                  (for [[s pin commit] (dropped-pins (history root sfile) idx)]
                    (project/finding sfile (project/line-of stext (str (keyword s))) "source-pin-dropped"
                                     (str "the source " s " dropped its pin " pin " in " commit ": a pin is never dropped"))))
        findings (vec (concat (when idx-finding [idx-finding]) (when stext (source-findings idx raw-files sfile stext))
                              dropped fence-findings cit-findings))]
    (bus/publish! :KnowledgeIndexed :knowledge/sources
                  {:revision :working-tree
                   :sources (vec (for [[k s] idx] {:id k :repository (:repository s) :pins (:pins s) :fenced (:fenced s)}))
                   :pages (vec pages)
                   :citations (vec (for [c resolved :let [p (:parsed c)]]
                                     {:citation (:citation c) :source (:source p) :path (:path p) :commit (:commit p)
                                      :file (:file c) :line (:line c) :need (:need c) :status (get-in c [:res :status])
                                      :reasons (vec (keep identity [(get-in c [:res :reason])]))}))
                   :findings (mapv #(select-keys % [:file :line :rule :message]) findings)})
    {:findings findings :knowledge {:index idx}}))

(defn parse-specs-lenient
  "Each spec's needs and the text of its needs file."
  [root cfg]
  (let [decls ((requiring-resolve 'rdm-spec.spec.parser/spec-decls) cfg)]
    (for [d decls
          :let [f (fs/path root (:needs-file d))]
          :when (fs/exists? f)
          :let [text (slurp (str f))
                m (try (edn/read-string text) (catch Exception _ nil))]
          :when (map? m)]
      {:needs-file (:needs-file d) :text text
       :needs (for [[k v] m] {:id (str k) :sources (:sources v) :line (project/line-of text (pr-str (str k)))})})))
