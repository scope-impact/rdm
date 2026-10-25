(ns rdm-spec.knowledge.sources
  "The Sources component of the knowledge context
  (dhf/spec/knowledge/sources.feature): reads the sources index (sources.edn
  under the configured :raw-dir) and the provenance records beside it, and
  resolves every citation `<source>:<path>@<commit>` against its source's
  pins, its fenced paths and, when one is given, a checkout of it.

  Its stage of `rdm-spec check` (and of `rdm-spec publish`) reads the needs'
  citations (the configured :needs) and the wiki's (rdm-spec.knowledge.wiki,
  handed this component's resolver), and nothing else: a citation quoted in a
  feature file is never resolved. It publishes KnowledgeIndexed.

  Checkouts are machine-local, so never configured: `--sources DIR` names a
  directory holding one git checkout per source, named by the source's id
  (DIR/loyly, DIR/innolitics-rdm). Without a checkout of its source, a
  citation's path is reported unchecked (a warning), never passed.

  A citation resolves to one of:
    :resolved   its source is recorded, its commit is one of the pins, its path
                is not fenced, and the checkout holds the path at the commit
    :failed     any of those is false (every reason is kept)
    :unchecked  everything checkable without a checkout holds, and no
                checkout (or none holding the commit) was given

  The rules, each a finding's rule name:
    sources-index        the sources index is absent, unreadable or not a map
    source-entry         a source whose entry is not a map
    source-repository    a source with no repository
    source-pin           a source with no pin
    source-record        a source with no provenance record beside the index
    source-fenced        a source whose :fenced is not a list of paths
    pin-dropped          a pin (or a source) the index at HEAD has and the
                         working tree's no longer lists
    fenced-path-missing  a fenced path that matches nothing at a pin, in the
                         given checkout
    checkout             a checkout that is not a git repository, or does not
                         hold a pin (a warning)
    need-citation        a need's citation that does not resolve (an error)
                         or was not checked (a warning)
    wiki-*               the wiki's rules (rdm-spec.knowledge.wiki)

  Its result, under :knowledge in the task's merged result:
    {:revision :working-tree :sources [id] :pages [path]
     :citations [{:citation :file :line :status :why}]}"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.knowledge.wiki :as wiki]))

;; ---------------------------------------------------------------------------
;; Citations

(def citation-form "<source>:<path>@<commit>")

(defn parse-citation
  "{:source :path :commit} of a citation written alone, or nil when it is not
  one."
  [s]
  (when (string? s)
    (when-let [[_ source path commit] (re-matches #"\s*`?([A-Za-z0-9][\w.-]*):([^\s@`]+)@([0-9a-fA-F]{4,40})`?\s*" s)]
      {:source source
       :path (-> path (str/replace #"^\./" "") (str/replace #"/+$" ""))
       :commit (str/lower-case commit)})))

(defn pin-matches?
  "True when the commit `c` names the pin `pin` (either abbreviates the other)."
  [pin c]
  (let [a (str/lower-case (str pin)) b (str/lower-case (str c))]
    (and (seq a) (seq b) (or (str/starts-with? a b) (str/starts-with? b a)))))

(defn fence-of
  "The fenced entry of `fenced` that `path` is, or lies under; nil when none."
  [fenced path]
  (some (fn [f]
          (let [f (-> (str f) (str/replace #"^\./" "") (str/replace #"/+$" ""))]
            (when (and (seq f) (or (= path f) (str/starts-with? path (str f "/"))))
              f)))
        fenced))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  "Runs git in `dir`: {:exit :out}."
  [dir & args]
  (try
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
      {:exit (:exit r) :out (:out r)})
    (catch Exception e {:exit -1 :out "" :err (ex-message e)})))

(defn git-repo? [dir]
  (and (fs/directory? dir) (zero? (:exit (git dir "rev-parse" "--git-dir")))))

(defn holds-commit? [dir commit]
  (zero? (:exit (git dir "cat-file" "-e" (str commit "^{commit}")))))

(defn holds-path? [dir commit path]
  (zero? (:exit (git dir "cat-file" "-e" (str commit ":" path)))))

;; ---------------------------------------------------------------------------
;; The index

(defn- sid [k] (if (keyword? k) (name k) (str k)))

(defn- line-of-key
  "The line of `text` holding the key `id` (as :id or \"id\"), outside comments."
  [text id]
  (let [re (re-pattern (str "(^|[\\s{\\[])(:|\")" (java.util.regex.Pattern/quote id) "\"?(?=[\\s{}]|$)"))]
    (some (fn [[i l]] (when (re-find re (first (str/split l #";" 2))) (inc i)))
          (map-indexed vector (str/split-lines (or text ""))))))

(defn index-file [req]
  (str (command/config-value req :raw-dir) "/sources.edn"))

(defn read-index
  "The sources index: {:file rel :text :sources {id entry} :error why-or-nil}."
  [req]
  (let [rel (index-file req)
        f (command/path req rel)]
    (if-not (fs/regular-file? f)
      {:file rel :sources {} :error (str "there is no sources index " rel)}
      (let [text (slurp (str f))
            v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
        (cond
          (and (map? v) (::error v)) {:file rel :text text :sources {} :error (str rel " is not readable: " (::error v))}
          (nil? v) {:file rel :text text :sources {}}
          (not (map? v)) {:file rel :text text :sources {} :error (str rel " is not a map of source id to source")}
          :else {:file rel :text text :sources (into (sorted-map) (for [[k e] v] [(sid k) e]))})))))

(defn- pins-of [entry] (let [ps (when (map? entry) (:pins entry))] (if (sequential? ps) (mapv str ps) [])))
(defn- fenced-of [entry] (let [fs* (when (map? entry) (:fenced entry))] (if (sequential? fs*) (mapv str fs*) [])))

(defn head-index
  "The sources index as committed at HEAD, or nil when the project is no git
  repository, has no HEAD or HEAD holds no readable index."
  [req rel]
  (let [r (git (:root req) "show" (str "HEAD:./" rel))]
    (when (zero? (:exit r))
      (try (let [v (edn/read-string (:out r))]
             (when (map? v) (into {} (for [[k e] v] [(sid k) e]))))
           (catch Exception _ nil)))))

(defn index-findings
  "Findings for the index itself: every source has a repository, a pin and a
  record; no pin it had at HEAD is dropped."
  [req {:keys [file text sources error]}]
  (let [raw (command/config-value req :raw-dir)
        at (fn [id] (line-of-key text id))]
    (concat
     (when error [(command/finding file nil "sources-index" error)])
     (for [[id e] sources :when (not (map? e))]
       (command/finding file (at id) "source-entry"
                        (str "the source " id " is not a map of :repository, :record, :pins and :fenced")))
     (for [[id e] sources :when (map? e) :when (str/blank? (str (or (:repository e) "")))]
       (command/finding file (at id) "source-repository" (str "the source " id " names no repository")))
     (for [[id e] sources :when (map? e) :when (empty? (pins-of e))]
       (command/finding file (at id) "source-pin"
                        (str "the source " id " has no pin: every source is pinned at the commit it is cited at")))
     (for [[id e] sources :when (map? e)
           :let [rec (:record e)]
           :when (or (str/blank? (str (or rec "")))
                     (not (fs/regular-file? (command/path req (str raw "/" rec)))))]
       (command/finding file (at id) "source-record"
                        (if (str/blank? (str (or rec "")))
                          (str "the source " id " names no provenance record")
                          (str "the source " id "'s provenance record " raw "/" rec " does not exist"))))
     (for [[id e] sources :when (map? e)
           :let [fz (:fenced e)]
           :when (and (some? fz) (not (and (sequential? fz) (every? string? fz))))]
       (command/finding file (at id) "source-fenced" (str "the source " id "'s :fenced is not a list of paths")))
     (when-let [head (when-not error (head-index req file))]
       (for [[id he] (sort-by key head)
             pin (pins-of he)
             :let [e (get sources id)]
             :when (not (some #{pin} (pins-of e)))]
         (command/finding file (if e (at id) nil) "pin-dropped"
                          (if e
                            (str "the source " id " no longer lists the pin " pin ", which it had at HEAD;"
                                 " a pin is never dropped: a citation made against it must still resolve")
                            (str "the source " id ", pinned at " pin " at HEAD, is no longer indexed;"
                                 " a pin is never dropped: a citation made against it must still resolve"))))))))

;; ---------------------------------------------------------------------------
;; Checkouts

(defn checkouts
  "{:dir rel-or-nil :given? bool}: the directory given with --sources. Cannot
  run when it is given and is no directory."
  [req]
  (let [d (get-in req [:opts :sources])]
    (when (and d (not (fs/directory? (command/path req d))))
      (command/cannot-run! (str "the sources directory " d " given with --sources does not exist")))
    {:dir d :given? (some? d)}))

(defn- checkout-of
  "{:dir abs :repo? bool :exists? bool} of the source `id`'s checkout, or nil
  when no --sources was given."
  [req co id]
  (when (:given? co)
    (let [d (command/path req (str (:dir co) "/" id))]
      {:dir (str d) :name (str (:dir co) "/" id) :exists? (fs/directory? d)
       :repo? (git-repo? d)})))

;; ---------------------------------------------------------------------------
;; Resolving

(defn resolver
  "A function resolving one citation ({:citation :file :line ...}) against
  the index and the checkouts, memoised per citation text: the same map with
  :source :path :commit :status :resolved? :checked? :why and :reasons."
  [req index co]
  (let [sources (:sources index)
        repo-cache (atom {})
        co-of (fn [id] (or (get @repo-cache id)
                           (let [c (checkout-of req co id)] (swap! repo-cache assoc id c) c)))
        judge
        (memoize
         (fn [text]
           (let [{:keys [source path commit] :as parsed} (parse-citation text)
                 entry (when parsed (get sources source))
                 pins (pins-of entry)
                 fenced (fenced-of entry)
                 fence (when parsed (fence-of fenced path))
                 reasons
                 (cond
                   (nil? parsed)
                   [{:rule "citation-form" :message (str text " is not a citation of the form " citation-form)}]
                   (not (contains? sources source))
                   [{:rule "citation-source"
                     :message (str "no source " source " is recorded in " (:file index)
                                   (when (seq sources) (str " (its sources are " (str/join ", " (keys sources)) ")")))}]
                   :else
                   (concat
                    (when-not (some #(pin-matches? % commit) pins)
                      [{:rule "citation-pin"
                        :message (str "the commit " commit " is not a pin of " source ", "
                                      (if (seq pins) (str "whose pins are " (str/join ", " pins)) "which has no pin"))}])
                    (when fence
                      [{:rule "citation-fenced"
                        :message (if (= fence path)
                                   (str "the path \"" path "\" is fenced in " source
                                        ": a source's implementation is never cited")
                                   (str "the path " path " lies in the fenced directory \"" fence "\" of " source
                                        ": a source's implementation is never cited"))}])))
                 checkout (when (and parsed (empty? reasons)) (co-of source))
                 [status why-rule why]
                 (cond
                   (seq reasons) [:failed nil nil]
                   (nil? checkout)
                   [:unchecked "citation-unchecked"
                    (str "no checkout of " source " was given with --sources, so whether " path
                         " exists at " commit " is unchecked")]
                   (not (:exists? checkout))
                   [:unchecked "citation-unchecked"
                    (str "the directory given with --sources holds no checkout " (:name checkout)
                         ", so whether " path " exists at " commit " is unchecked")]
                   (not (:repo? checkout))
                   [:unchecked "citation-unchecked"
                    (str "the checkout " (:name checkout) " is not a git repository, so whether " path
                         " exists at " commit " is unchecked")]
                   (not (holds-commit? (:dir checkout) commit))
                   [:unchecked "citation-unchecked"
                    (str "the checkout " (:name checkout) " does not hold the commit " commit
                         ", so whether " path " exists there is unchecked")]
                   (not (holds-path? (:dir checkout) commit path))
                   [:failed "citation-path"
                    (str "the path " path " does not exist at the pin " commit " in the checkout " (:name checkout))]
                   :else [:resolved nil nil])
                 reasons (vec (if why-rule (conj (vec reasons) {:rule why-rule :message why}) reasons))]
             (merge (select-keys parsed [:source :path :commit])
                    {:status status
                     :resolved? (= :resolved status)
                     :checked? (not= :unchecked status)
                     :why (when (seq reasons) (str/join "; " (map :message reasons)))
                     :reasons reasons}))))]
    (fn [c] (merge c (judge (:citation c))))))

;; ---------------------------------------------------------------------------
;; The needs' citations

(defn need-citations
  "Every citation of the needs file: [{:citation :need :file :line}], in the
  needs' order. Nothing when the file is absent or unreadable (the spec check
  reports that)."
  [req]
  (when-let [rel (get-in req [:config :needs])]
    (let [f (command/path req rel)]
      (when (fs/regular-file? f)
        (let [text (slurp (str f))
              lines (vec (str/split-lines text))
              needs (try (edn/read-string text) (catch Exception _ nil))
              line-after (fn [from s]
                           (or (some (fn [i] (when (str/includes? (first (str/split (nth lines i) #";" 2)) s) (inc i)))
                                     (range (max 0 (dec (or from 1))) (count lines)))
                               from))]
          (when (map? needs)
            (vec (for [[n need] (sort-by (comp str key) needs)
                       :let [sources (when (map? need) (:sources need))]
                       :when (sequential? sources)
                       :let [n (sid n)
                             start (line-after 1 (str "\"" n "\""))]
                       s sources]
                   {:citation (str s) :need n :file (command/rel req f)
                    :line (line-after start (pr-str (str s)))}))))))))

(defn need-citation-finding
  "The finding for a need's citation as resolved, or nil when it resolved."
  [c]
  (case (:status c)
    :failed (command/finding (:file c) (:line c) "need-citation"
                             (str (:need c) " cites " (:citation c) ", which does not resolve: " (:why c)))
    :unchecked (command/warning (:file c) (:line c) "need-citation"
                                (str (:need c) " cites " (:citation c) ", which was not checked: " (:why c)))
    nil))

;; ---------------------------------------------------------------------------
;; Fenced paths, against the checkouts

(defn fence-findings
  "With checkouts given: a fenced path that matches nothing at a pin the
  checkout holds is a finding; a pin the checkout does not hold, a warning."
  [req index co]
  (when (:given? co)
    (for [[id e] (:sources index)
          :when (map? e)
          :let [c (checkout-of req co id)]
          :when (:exists? c)
          f (if (:repo? c)
              (for [pin (pins-of e)
                    f (if (holds-commit? (:dir c) pin)
                        (for [path (fenced-of e)
                              :let [path (-> path (str/replace #"^\./" "") (str/replace #"/+$" ""))]
                              :when (not (holds-path? (:dir c) pin path))]
                          (command/finding (:file index) (line-of-key (:text index) id) "fenced-path-missing"
                                           (str "the source " id " fences \"" path "\", which matches nothing at its pin "
                                                pin " in the checkout " (:name c))))
                        [(command/warning (:file index) (line-of-key (:text index) id) "checkout"
                                          (str "the checkout " (:name c) " does not hold the pin " pin " of " id
                                               "; its fenced paths there were not checked"))])]
                f)
              [(command/warning (:file index) (line-of-key (:text index) id) "checkout"
                                (str (:name c) " is not a git repository; " id "'s citations were not checked"))])]
      f)))

;; ---------------------------------------------------------------------------
;; The stage

(defn- event-citation [c]
  (select-keys c [:citation :file :line :resolved? :checked? :why :status :reasons :need :footnote
                  :source :path :commit]))

(defn index
  "Reads the sources and the wiki and resolves every citation:
  {:revision :sources :pages :citations :findings}, KnowledgeIndexed's payload."
  [req]
  (let [co (checkouts req)
        idx (read-index req)
        resolve (resolver req idx co)
        needs (mapv resolve (need-citations req))
        w (wiki/check req resolve)
        findings (vec (concat (index-findings req idx)
                              (fence-findings req idx co)
                              (keep need-citation-finding needs)
                              (:findings w)))]
    {:revision :working-tree
     :sources (vec (for [[id e] (:sources idx)]
                     {:id id
                      :repository (when (map? e) (:repository e))
                      :record (when (map? e) (:record e))
                      :pin (last (pins-of e))
                      :pins (pins-of e)
                      :fenced (fenced-of e)}))
     :pages (vec (:pages w))
     :citations (mapv event-citation (concat needs (:citations w)))
     :findings findings}))

(defn check
  "Sources' stage of `rdm-spec check` and `rdm-spec publish`: indexes the
  sources and the wiki, publishes KnowledgeIndexed and reports every finding."
  [req]
  (let [k (index req)]
    (command/publish! req :knowledge/sources :KnowledgeIndexed k)
    {:result {:knowledge {:revision (:revision k)
                          :sources (mapv :id (:sources k))
                          :pages (:pages k)
                          :citations (mapv #(select-keys % [:citation :file :line :status :why]) (:citations k))}}
     :findings (:findings k)}))
