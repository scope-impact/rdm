(ns rdm-spec.knowledge.wiki
  "The Wiki component of the knowledge context (dhf/spec/knowledge/wiki.feature):
  the domain wiki, dhf/wiki/index.md and the pages under dhf/wiki/pages/
  (the configured :wiki-dir). Every page is listed in the index, every claim
  is footnoted to a source, and no page names an implementation.

  It reads the wiki and holds it to its rules; the footnotes' citations are
  resolved by Sources (rdm-spec.knowledge.sources), which hands `check` its
  resolver and publishes KnowledgeIndexed with the pages and citations read
  here. This namespace never requires Sources.

  The rules, each a finding's rule name:
    wiki-index                 the wiki directory holds pages but no index.md
    wiki-unlisted              a page the index does not link
    wiki-entry                 an index entry linking no page
    wiki-no-footnote           a page with no footnote: its claims cite nothing
    wiki-footnote-no-citation  a footnote that cites no <source>:<path>@<commit>
    wiki-footnote-undefined    a footnote reference with no footnote under it
    wiki-citation              a footnote's citation that does not resolve
                               (an error), or could not be checked (a warning)
    wiki-stack-word            a configured stack word in the index or a page"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def citation-re
  "A citation inside prose: <source>:<path>@<commit>."
  #"([A-Za-z0-9][\w.-]*):([^\s`@()\[\]<>\"',;]+)@([0-9a-fA-F]{4,40})(?![0-9A-Za-z])")

(defn citations-in
  "Every citation written in `text`, as strings, in order."
  [text]
  (mapv first (re-seq citation-re (or text ""))))

;; ---------------------------------------------------------------------------
;; Reading Markdown

(defn- numbered
  "[[line-number line] ...] of `text`, with a flag for lines inside a fenced
  code block (where links and footnotes are not read)."
  [text]
  (loop [ls (map-indexed vector (str/split-lines (or text ""))) fence? false acc []]
    (if-let [[[i l] & more] (seq ls)]
      (let [fence-line? (re-find #"^\s*(```|~~~)" l)]
        (recur more (if fence-line? (not fence?) fence?)
               (conj acc {:line (inc i) :text l :code? (or fence? (boolean fence-line?))})))
      acc)))

(def ^:private footnote-def-re #"^\[\^([^\]\s]+)\]:\s?(.*)$")

(defn footnotes
  "The footnote definitions of a page: [{:label :line :text}], each with its
  indented continuation lines."
  [text]
  (loop [ls (numbered text) cur nil acc []]
    (if-let [[{:keys [line code?] l :text} & more] (seq ls)]
      (let [m (when-not code? (re-matches footnote-def-re l))]
        (cond
          m (recur more {:label (nth m 1) :line line :text (nth m 2)} (cond-> acc cur (conj cur)))
          (and cur (not code?) (re-find #"^\s{2,}\S" l)) (recur more (update cur :text str "\n" (str/trim l)) acc)
          (and cur (str/blank? l)) (recur more cur acc)
          :else (recur more nil (cond-> acc cur (conj cur)))))
      (cond-> acc cur (conj cur)))))

(defn footnote-references
  "Every footnote reference [^label] of a page outside a definition's label:
  [{:label :line}]."
  [text]
  (vec (for [{:keys [line code?] l :text} (numbered text)
             :when (not code?)
             :let [l (if-let [m (re-find #"^\[\^[^\]\s]+\]:" l)] (subs l (count m)) l)]
             [_ label] (re-seq #"\[\^([^\]\s]+)\]" l)]
         {:label label :line line})))

(defn index-entries
  "The index's links to Markdown files: [{:target :line}], each target
  relative to the wiki directory, without its anchor."
  [text]
  (vec (for [{:keys [line code?] l :text} (numbered text)
             :when (not code?)
             [_ target] (re-seq #"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)" l)
             :let [target (first (str/split target #"#" 2))]
             :when (and (not (str/blank? target))
                        (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" target))
                        (not (str/starts-with? target "/"))
                        (str/ends-with? target ".md"))]
         {:target target :line line})))

;; ---------------------------------------------------------------------------
;; The wiki on disk

(defn- norm [f] (str (fs/normalize (fs/absolutize f))))

(defn read-wiki
  "The wiki of the project, as files: {:dir rel :exists? bool :index rel-or-nil
  :pages [rel]} (paths relative to the project root, sorted), or nil when no
  :wiki-dir is configured."
  [req]
  (when-let [wiki-dir (get-in req [:config :wiki-dir])]
    (let [dir (command/path req wiki-dir)
          exists? (fs/directory? dir)
          index (fs/file dir "index.md")
          pages-dir (fs/file dir "pages")]
      {:dir (command/rel req dir)
       :abs (norm dir)
       :exists? exists?
       :index (when (and exists? (fs/regular-file? index)) (command/rel req index))
       :pages (if (and exists? (fs/directory? pages-dir))
                (vec (sort (map #(command/rel req %) (filter fs/regular-file? (fs/glob pages-dir "**.md")))))
                [])})))

;; ---------------------------------------------------------------------------
;; The rules

(defn- word-pattern [w]
  (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])")))

(defn stack-word-findings
  "A finding for every line of `file` (its text `text`) naming one of `words`."
  [file text words]
  (let [pats (mapv (juxt identity word-pattern) words)]
    (for [[i l] (map-indexed vector (str/split-lines (or text "")))
          [w p] pats
          :when (re-find p l)]
      (command/finding file (inc i) "wiki-stack-word"
                       (str "names the stack word \"" w "\"; no wiki page names an implementation"
                            " (the stack lives in dhf/harness/stack.md)")))))

(defn- index-findings [req wiki index-text]
  (let [listed (set (for [{:keys [target]} (index-entries index-text)]
                      (norm (fs/file (:abs wiki) target))))]
    (concat
     (for [{:keys [target line]} (index-entries index-text)
           :let [f (fs/file (:abs wiki) target)]
           :when (not (fs/regular-file? f))]
       (command/finding (:index wiki) line "wiki-entry"
                        (str "the index lists " target ", which is no page: no file " (command/rel req f))))
     (for [p (:pages wiki)
           :when (not (contains? listed (norm (command/path req p))))]
       (command/finding p nil "wiki-unlisted"
                        (str "the page " p " is not listed in the index " (:index wiki)
                             "; every page is linked from it"))))))

(defn- page-structure-findings [page text]
  (let [defs (footnotes text)
        labels (set (map :label defs))]
    (concat
     (when (empty? defs)
       [(command/finding page nil "wiki-no-footnote"
                         (str "the page " page " has no footnote: every claim footnotes the source it came from,"
                              " as <source>:<path>@<commit>"))])
     (for [d defs :when (empty? (citations-in (:text d)))]
       (command/finding page (:line d) "wiki-footnote-no-citation"
                        (str "the footnote [^" (:label d) "] cites no source as <source>:<path>@<commit>")))
     (for [r (distinct (map #(select-keys % [:label :line]) (footnote-references text)))
           :when (not (contains? labels (:label r)))]
       (command/finding page (:line r) "wiki-footnote-undefined"
                        (str "the footnote reference [^" (:label r) "] has no footnote [^" (:label r) "]: under it"))))))

(defn page-citations
  "Every citation in the footnotes of `page` (its text `text`):
  [{:citation :file :line :footnote}]."
  [page text]
  (vec (for [d (footnotes text)
             c (citations-in (:text d))]
         {:citation c :file page :line (:line d) :footnote (str "[^" (:label d) "]")})))

(defn citation-finding
  "The finding for a footnote's citation `c` as resolved, or nil when it
  resolved: an error when it failed, a warning when it could not be checked."
  [c]
  (case (:status c)
    :failed (command/finding (:file c) (:line c) "wiki-citation"
                             (str "the footnote " (:footnote c) " of " (:file c) " cites " (:citation c)
                                  ", which does not resolve: " (:why c)))
    :unchecked (command/warning (:file c) (:line c) "wiki-citation"
                                (str "the footnote " (:footnote c) " of " (:file c) " cites " (:citation c)
                                     ", which was not checked: " (:why c)))
    nil))

(defn check
  "The wiki's part of `rdm-spec check`. `resolve` resolves one citation
  ({:citation :file :line ...} -> the same map with :status (:resolved, :failed
  or :unchecked), :why and the rest Sources adds). Returns {:pages [rel]
  :citations [resolved] :findings [..]}; nothing when the wiki directory does
  not exist."
  [req resolve]
  (let [wiki (read-wiki req)]
    (if-not (and wiki (:exists? wiki))
      {:pages [] :citations [] :findings []}
      (let [words (get-in req [:config :stack-words])
            slurp-rel #(slurp (str (command/path req %)))
            index-text (when (:index wiki) (slurp-rel (:index wiki)))
            texts (into {} (for [p (:pages wiki)] [p (slurp-rel p)]))
            citations (vec (for [p (:pages wiki) c (page-citations p (texts p))] (resolve c)))]
        {:pages (:pages wiki)
         :citations citations
         :findings
         (vec (concat
               (if (:index wiki)
                 (index-findings req wiki index-text)
                 (when (seq (:pages wiki))
                   [(command/finding (str (:dir wiki) "/index.md") nil "wiki-index"
                                     (str "the wiki has pages and no index " (:dir wiki) "/index.md listing them"))]))
               (mapcat #(page-structure-findings % (texts %)) (:pages wiki))
               (keep citation-finding citations)
               (when (seq words)
                 (concat (when (:index wiki) (stack-word-findings (:index wiki) index-text words))
                         (mapcat #(stack-word-findings % (texts %) words) (:pages wiki))))))}))))
