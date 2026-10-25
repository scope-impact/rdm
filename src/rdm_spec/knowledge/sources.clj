(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context (dhf/spec/knowledge/
  sources.feature): reads the sources index (sources.edn under the configured
  :raw-dir) and its history, and resolves every citation of the needs and the
  wiki against the recorded sources, their pins, their fenced paths and,
  when given with --sources DIR, a checkout of each source (DIR/<source id>).
  It runs the Wiki component, publishes KnowledgeIndexed and reports every
  finding; nothing else is scanned for citations.

  A citation is <source>:<path>@<commit>. Its status is :resolved (the path
  exists at the pin in the source's checkout), :unresolved (a finding) or
  :unchecked (no checkout to check the path in: a warning, never a pass).

  The rules, each a stable name on the error stream:
    sources-unreadable       the sources index is not an EDN map of sources
    source-no-repository     a source with no :repository
    source-no-pin            a source with no pin
    source-no-record         a source whose provenance record is not in the raw directory
    source-pin-dropped       a pin an earlier commit of the index listed and the index no longer lists
    source-fenced-missing    a fenced path that matches nothing at a pin of the source's checkout
    citation-malformed       a need's source that is not a citation
    citation-unknown-source  a citation naming no recorded source
    citation-retired         a citation of a retired source
    citation-unpinned        a citation at a commit that is not one of its source's pins
    citation-fenced          a citation of a fenced path, or of a path inside a fenced directory
    citation-path-missing    a citation whose path does not exist at its pin in the checkout
    citation-unchecked       (a warning) a citation whose path could not be checked"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def index-name "sources.edn")

;; ---------------------------------------------------------------------------
;; Citations

(def ^:private citation-body
  "<source>:<path>@<commit>: an id, a relative path, a hexadecimal commit."
  "([A-Za-z0-9][A-Za-z0-9._-]*):(?!/)([^\\s@`\"'()\\[\\]<>]+)@([0-9a-fA-F]{4,40})")

(def ^:private citation-whole (re-pattern citation-body))

(def ^:private citation-in-text
  (re-pattern (str "(?<![\\w./:@-])" citation-body "(?![\\w])")))

(defn parse-citation
  "{:citation :source :path :commit} when `s` is one citation, else nil."
  [s]
  (when-let [[whole source path commit] (and (string? s) (re-matches citation-whole (str/trim s)))]
    {:citation whole :source source :path path :commit commit}))

(defn citations-in
  "Every citation in `text`: [{:citation :source :path :commit :line}]."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [whole source path commit] (re-seq citation-in-text l)]
         {:citation whole :source source :path path :commit commit :line (inc i)})))

;; ---------------------------------------------------------------------------
;; The index

(defn- strip-slashes [p] (-> (str p) (str/replace #"^\./+" "") (str/replace #"/+$" "")))

(defn index->sources
  "The index map as sources: [{:id :repository :record :pins :fenced
  :retired}], sorted by id, :id a string."
  [index]
  (vec (sort-by :id
                (for [[k v] index]
                  (let [v (if (map? v) v {})]
                    (cond-> {:id (if (keyword? k) (name k) (str k))
                             :repository (:repository v)
                             :record (:record v)
                             :pins (vec (map str (filter some? (:pins v))))
                             :fenced (vec (map str (filter some? (:fenced v))))}
                      (:retired v) (assoc :retired (:retired v))))))))

(defn read-index
  "{:index map} from the index text, nil text meaning no index ({:index {}}),
  or {:error message}."
  [text]
  (if (nil? text)
    {:index {}}
    (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
      (cond (and (map? v) (::error v)) {:error (str "is not readable EDN: " (::error v))}
            (and (map? v) (every? map? (vals v))) {:index v}
            :else {:error "is not a map of sources, {:source-id {:repository :record :pins :fenced}}"}))))

(defn- source-line
  "The line of the index text on which the source `id` is keyed, or nil."
  [text id]
  (some (fn [[i l]] (when (re-find (re-pattern (str "(?<![\\w-]):" (java.util.regex.Pattern/quote id) "(?![\\w.-])")) l)
                      (inc i)))
        (map-indexed vector (str/split-lines (or text "")))))

(defn index-findings
  "The index's own rules: a repository, a pin and a record for each source.
  `record?` says whether a record file exists."
  [index-rel text sources record?]
  (vec (for [s sources
             f [(when (str/blank? (str (:repository s)))
                  ["source-no-repository" (str "the source " (:id s) " names no :repository")])
                (when (empty? (:pins s))
                  ["source-no-pin" (str "the source " (:id s) " has no pin; a source is cited at a commit it is pinned to,"
                                        " so it lists every pin it was ever cited at (:pins)")])
                (when-not (and (string? (:record s)) (record? (:record s)))
                  ["source-no-record" (str "the source " (:id s) " has no provenance record"
                                           (when (string? (:record s)) (str " " (:record s)))
                                           " beside the index")])]
             :when f]
         (command/finding index-rel (source-line text (:id s)) (first f) (second f)))))

;; ---------------------------------------------------------------------------
;; Git

(defn- git
  "Runs git in `dir`: {:exit :out}, or nil when git cannot run."
  [dir & args]
  (try (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                               :extra-env {"GIT_CONFIG_NOSYSTEM" "1"}}
                      "git" args)]
         {:exit (:exit r) :out (:out r)})
       (catch Exception _ nil)))

(defn- git-ok [dir & args]
  (let [r (apply git dir args)]
    (when (and r (zero? (:exit r))) (:out r))))

;; ---------------------------------------------------------------------------
;; Pins are never dropped

(defn pins-of
  "#{[source-id pin]} of an index map."
  [index]
  (set (for [s (index->sources index) pin (:pins s)] [(:id s) pin])))

(defn dropped-pins
  "Pins dropped along `versions` ([{:commit c-or-nil :index map}], oldest
  first, the last the index as it stands): [{:source :pin :commit}] for every
  pin an earlier version listed and the last does not, :commit the version
  after the last one that listed it (nil: the working tree)."
  [versions]
  (let [now (pins-of (:index (last versions)))
        vs (vec versions)]
    (vec (sort-by (juxt :source :pin)
                  (for [sp (distinct (mapcat (comp sort pins-of :index) (butlast vs)))
                        :when (not (contains? now sp))
                        :let [last-at (last (keep-indexed (fn [i v] (when (contains? (pins-of (:index v)) sp) i)) vs))
                              by (get vs (inc last-at))]]
                    {:source (first sp) :pin (second sp) :commit (:commit by)})))))

(defn- index-history
  "Every committed version of the index `index-rel` in the git repository
  holding `root`, oldest first: [{:commit :index}]; [] outside git. A
  version that cannot be read is skipped."
  [root index-rel]
  (if-let [out (git-ok root "log" "--format=%H" "--reverse" "--" index-rel)]
    (vec (for [c (str/split-lines out)
               :when (not (str/blank? c))
               :let [text (git-ok root "show" (str c ":./" index-rel))
                     {:keys [index]} (when text (read-index text))]
               :when index]
           {:commit c :index index}))
    []))

(defn dropped-findings
  [root index-rel text index]
  (let [history (index-history root index-rel)]
    (if (empty? history)
      []
      (vec (for [{:keys [source pin commit]} (dropped-pins (conj history {:commit nil :index index}))]
             (command/finding index-rel (source-line text source) "source-pin-dropped"
                              (str "the source " source " drops the pin " pin
                                   (if commit (str " in commit " (subs commit 0 (min 7 (count commit))))
                                       " in the working tree")
                                   "; a pin is never dropped, so a citation made against it still resolves"
                                   " (retire a source with :retired, never delete it)")))))))

;; ---------------------------------------------------------------------------
;; Resolving

(defn- pin-matches?
  "Whether `commit` names the pin `pin`: equal, or one an abbreviation of
  the other, whatever the case."
  [commit pin]
  (let [a (str/lower-case (str commit)) b (str/lower-case (str pin))]
    (or (= a b) (str/starts-with? a b) (str/starts-with? b a))))

(defn fenced-by
  "The fenced path of `source` that `path` is, or lies inside; nil when none."
  [source path]
  (let [p (strip-slashes path)]
    (some (fn [f] (let [f* (strip-slashes f)]
                    (when (or (= f* "") (= f* ".") (= p f*) (str/starts-with? p (str f* "/"))) f)))
          (:fenced source))))

(defn checkout-dir
  "The checkout of the source `id` in `sources-dir`, or nil when none."
  [sources-dir id]
  (when sources-dir
    (let [d (fs/file sources-dir id)]
      (when (fs/directory? d) (str d)))))

(defn- has-commit? [co commit]
  (some? (git-ok co "rev-parse" "--verify" "--quiet" (str commit "^{commit}"))))

(defn- exists-at? [co commit path]
  (let [p (strip-slashes path)]
    (some? (git-ok co "cat-file" "-e" (str commit ":" (if (#{"" "."} p) "" p))))))

(defn resolve-citation
  "Resolves the citation string `citation` against `sources` (index->sources)
  and the checkouts in `sources-dir` (or nil): {:citation :source :path
  :commit :status :rule :reasons}. :rule is the finding's rule (nil when
  :resolved)."
  [sources sources-dir citation]
  (let [{:keys [source path commit] :as c} (or (parse-citation citation)
                                               {:citation citation :source nil :path nil :commit nil})
        src (first (filter #(= source (str (:id %))) sources))
        out (fn [status rule & reasons] (assoc c :status status :rule rule :reasons (vec reasons)))]
    (cond
      (nil? source)
      (out :unresolved "citation-malformed"
           "it is not a citation; a citation is <source>:<path>@<commit>")

      (nil? src)
      (out :unresolved "citation-unknown-source"
           (str source " is not a recorded source"
                (if (seq sources) (str " (the sources are " (str/join ", " (map :id sources)) ")") " (none is recorded)")))

      (:retired src)
      (out :unresolved "citation-retired"
           (str "its source " source " is retired (" (:retired src) "); nothing may cite a retired source"))

      (not-any? #(pin-matches? commit %) (:pins src))
      (out :unresolved "citation-unpinned"
           (str commit " is not a pin of " source ", whose pins are "
                (if (seq (:pins src)) (str/join ", " (:pins src)) "none")))

      (fenced-by src path)
      (out :unresolved "citation-fenced"
           (let [f (fenced-by src path)]
             (str (if (= (strip-slashes f) (strip-slashes path))
                    (str "the path " f " is fenced in " source)
                    (str "the path " path " is inside " f ", fenced in " source))
                  "; a source's implementation is never cited")))

      :else
      (let [co (checkout-dir sources-dir source)]
        (cond
          (nil? co)
          (out :unchecked "citation-unchecked"
               (str "no checkout of " source " was given with --sources, so " path " at " commit " was not checked"))

          (not (has-commit? co commit))
          (out :unchecked "citation-unchecked"
               (str "the checkout of " source " holds no commit " commit ", so " path " was not checked"))

          (not (exists-at? co commit path))
          (out :unresolved "citation-path-missing"
               (str "the path " path " does not exist at " commit " in the checkout of " source))

          :else (out :resolved nil))))))

(defn citation-finding
  "The finding (or warning, when unchecked) for the resolved citation `c`,
  cited at its :file and :line by `where` (\"UN-004\", \"the footnote [^1]\"),
  or nil when it resolved."
  [where c]
  (when (not= :resolved (:status c))
    (let [msg (str where " cites " (:citation c) ": " (str/join "; " (:reasons c)))]
      (if (= :unchecked (:status c))
        (command/warning (:file c) (:line c) (or (:rule c) "citation-unchecked") msg)
        (command/finding (:file c) (:line c) (or (:rule c) "citation-unresolved") msg)))))

(defn fenced-findings
  "A finding for each fenced path of a source that matches nothing at one of
  its pins, in its checkout. A source with no checkout, or a checkout
  without the pin, is not checked."
  [index-rel text sources sources-dir]
  (vec (for [s sources
             :let [co (checkout-dir sources-dir (:id s))]
             :when co
             pin (:pins s)
             :when (has-commit? co pin)
             f (:fenced s)
             :when (not (exists-at? co pin f))]
         (command/finding index-rel (source-line text (:id s)) "source-fenced-missing"
                          (str "the source " (:id s) " fences " f ", which matches nothing at its pin " pin)))))

;; ---------------------------------------------------------------------------
;; The needs

(defn- need-line
  "The line of the needs text on which `citation` is cited by `need`."
  [lines need citation]
  (let [start (or (some (fn [[i l]] (when (str/includes? l (pr-str need)) i)) (map-indexed vector lines)) 0)
        q (pr-str citation)]
    (or (some (fn [[i l]] (when (str/includes? l q) (inc i))) (drop start (map-indexed vector lines)))
        (inc start))))

(defn need-citations
  "Every source of every need in the needs file's `text`: [{:need :cited
  :line}], in the file's order. Nil text or a file that is not a map of
  needs has none (the spec check names it)."
  [text]
  (let [v (try (edn/read-string (or text "")) (catch Exception _ nil))
        lines (vec (str/split-lines (or text "")))]
    (if-not (map? v)
      []
      (vec (sort-by (juxt :line :need)
                    (for [[k n] v
                          :when (map? n)
                          cited (let [srcs (:sources n)] (if (sequential? srcs) srcs []))]
                      {:need (str k) :cited cited :line (need-line lines (str k) (str cited))}))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- read-text [f] (when (fs/regular-file? f) (slurp (str f))))

(defn- wiki-check
  "The Wiki component's check, when it is built."
  [root config ctx]
  (if-let [f (try (requiring-resolve 'rdm-spec.knowledge.wiki/check-wiki) (catch Exception _ nil))]
    (f root config ctx)
    {:pages [] :citations [] :findings []}))

(defn index
  "Reads the project's sources and checks them, its needs' citations and its
  wiki: the KnowledgeIndexed payload with :warnings beside it."
  [root config sources-dir]
  (let [raw-dir (str/replace (or (:raw-dir config) "dhf/raw") #"/+$" "")
        index-rel (str raw-dir "/" index-name)
        text (read-text (command/path root index-rel))
        {idx :index err :error} (read-index text)
        sources (index->sources idx)
        needs-rel (or (:needs config) (str (or (:spec-dir config) "dhf/spec") "/needs.edn"))
        need-cites (vec (for [{:keys [need cited line]} (need-citations (read-text (command/path root needs-rel)))]
                          (merge (resolve-citation sources sources-dir (str cited))
                                 {:file needs-rel :line line :need need :cited cited})))
        wiki (wiki-check root config {:sources sources :sources-dir sources-dir})
        malformed? #(= "citation-malformed" (:rule %))
        need-findings (keep #(citation-finding (:need %)
                                               (if (malformed? %)
                                                 (assoc % :citation (pr-str (str (:cited %))))
                                                 %))
                            need-cites)
        all (vec (concat
                  (when err [(command/finding index-rel nil "sources-unreadable" (str "the sources index " index-rel " " err))])
                  (index-findings index-rel text sources #(fs/regular-file? (command/path root (str raw-dir "/" %))))
                  (when text (dropped-findings root index-rel text idx))
                  (fenced-findings index-rel text sources sources-dir)
                  need-findings
                  (:findings wiki)))]
    {:revision :working-tree
     :sources (mapv #(select-keys % [:id :repository :pins :fenced :retired]) sources)
     :pages (vec (:pages wiki))
     :citations (vec (concat (for [c need-cites :when (not (malformed? c))]
                               (select-keys c [:citation :source :path :commit :file :line :need :status :reasons]))
                             (for [c (:citations wiki)]
                               (select-keys c [:citation :source :path :commit :file :line :need :status :reasons]))))
     :findings all}))

(defn check
  "The sources stage of `rdm-spec check` and `rdm-spec publish`: indexes the
  sources, the needs' citations and the wiki, publishes KnowledgeIndexed and
  reports every finding. --sources DIR gives the checkouts."
  [req]
  (let [given (:sources (:opts req))
        sources-dir (when given
                      (let [d (command/path req given)]
                        (when-not (fs/directory? d)
                          (command/cannot-run! (str "the sources directory " given " does not exist")))
                        (str (fs/absolutize d))))
        {:keys [findings] :as k} (index (:root req) (:config req) sources-dir)
        errors (vec (remove command/warning? findings))]
    (command/publish! req :knowledge/sources :KnowledgeIndexed
                      {:revision (:revision k)
                       :sources (:sources k)
                       :pages (:pages k)
                       :citations (:citations k)
                       :findings (mapv #(select-keys % [:file :line :rule :message]) errors)})
    {:result {:knowledge (dissoc k :findings)}
     :findings findings}))
