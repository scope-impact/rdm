(ns rdm-spec.knowledge.wiki
  "The Wiki component of the knowledge context (dhf/spec/knowledge/
  wiki.feature): the domain wiki, the configured :wiki-dir. Its index is
  index.md there; every other Markdown file under it is a page (pages/ by
  convention). Every page is in the index, every index entry is a page, every
  page carries footnotes, each citing a source, and no wiki file names a
  configured stack word. Footnotes are resolved with the Sources component.

  The rules, each a stable name on the error stream:
    wiki-no-index            pages exist and there is no index
    wiki-unindexed           a page the index does not link
    wiki-index-dangling      an index entry linking no page
    wiki-no-footnote         a page with no footnote
    wiki-footnote-uncited    a footnote citing no source
    wiki-footnote-undefined  a footnote reference with no footnote
    wiki-stack-word          a wiki file naming a configured stack word
  and the Sources component's citation-* rules for its citations."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.knowledge.sources :as sources]))

(def index-name "index.md")

(defn wiki-files
  "Every Markdown file under the wiki directory `dir` (a java.io.File):
  [{:file rel-to-dir :abs path}], sorted."
  [dir]
  (if (fs/directory? dir)
    (vec (sort-by :file
                  (for [f (fs/glob dir "**.md") :when (fs/regular-file? f)]
                    {:file (str/replace (str (fs/relativize dir f)) "\\" "/") :abs (str f)})))
    []))

;; ---------------------------------------------------------------------------
;; The index

(def ^:private link-pattern #"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")

(defn index-entries
  "The index's links to files of the wiki: [{:target :path :line}], :path
  normalised relative to the wiki directory. URLs, anchors and absolute paths
  are not entries."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             [_ target] (re-seq link-pattern l)
             :when (not (or (re-find #"^[a-zA-Z][\w+.-]*:" target)
                            (str/starts-with? target "#")
                            (str/starts-with? target "/")))
             :let [t (str/replace target #"#.*$" "")]
             :when (not (str/blank? t))]
         {:target target
          :path (str/replace (str (fs/normalize (fs/path t))) "\\" "/")
          :line (inc i)})))

;; ---------------------------------------------------------------------------
;; Footnotes

(defn footnotes
  "The footnote definitions of a page's `text`: [{:label :line :text}], the
  text including the definition's indented continuation lines."
  [text]
  (let [lines (vec (str/split-lines (or text "")))]
    (vec (for [[i l] (map-indexed vector lines)
               :let [[_ label rest*] (re-matches #"\s{0,3}\[\^([^\]\s]+)\]:\s?(.*)" l)]
               :when label]
           {:label label :line (inc i)
            :text (str/join "\n" (cons rest* (take-while #(re-matches #"(?:\s{2,}|\t)\S.*" %)
                                                         (subvec lines (inc i)))))}))))

(defn footnote-references
  "The footnote references of `text`: [{:label :line}] (definitions excluded)."
  [text]
  (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
             :let [l (str/replace l #"^\s{0,3}\[\^[^\]\s]+\]:" "")]
             [_ label] (re-seq #"\[\^([^\]\s]+)\](?!:)" l)]
         {:label label :line (inc i)})))

(defn- footnote-at
  "The label of the footnote whose definition spans `line`, or nil."
  [notes line]
  (some (fn [{:keys [label text] l :line}]
          (when (<= l line (+ l (count (str/split-lines text)) -1)) label))
        notes))

;; ---------------------------------------------------------------------------
;; Rules

(defn stack-word-findings
  "A finding for each line of `text` (the file `file`) naming one of
  `words`, matched as a whole word, whatever its case."
  [file text words]
  (let [res (for [w words :when (not (str/blank? w))]
              [w (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w)
                                  "(?![\\p{L}\\p{N}_])"))])]
    (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
               [w re] res
               :when (re-find re l)]
           (command/finding file (inc i) "wiki-stack-word"
                            (str "names the stack word \"" w "\"; the wiki never names an implementation"))))))

(defn page-findings
  "The footnote rules for one page, `file` with `text`."
  [file text]
  (let [notes (footnotes text)
        labels (set (map :label notes))]
    (vec (concat
          (when (empty? notes)
            [(command/finding file nil "wiki-no-footnote"
                              (str "the page " file " has no footnote; every claim in the wiki footnotes the source it came from"))])
          (for [n notes :when (empty? (sources/citations-in (:text n)))]
            (command/finding file (:line n) "wiki-footnote-uncited"
                             (str "the footnote [^" (:label n) "] cites no source; a footnote cites <source>:<path>@<commit>")))
          (for [r (footnote-references text) :when (not (contains? labels (:label r)))]
            (command/finding file (:line r) "wiki-footnote-undefined"
                             (str "the footnote reference [^" (:label r) "] has no footnote")))))))

(defn index-findings
  "The index rules: every page is linked from the index, every entry is a page."
  [wiki-dir index-rel index-text page-rels exists?]
  (let [entries (index-entries index-text)
        linked (set (map :path entries))]
    (vec (concat
          (for [e entries :when (not (exists? (:path e)))]
            (command/finding index-rel (:line e) "wiki-index-dangling"
                             (str "the index entry " (:target e) " links no page: there is no " wiki-dir "/" (:path e))))
          (for [p page-rels :when (not (contains? linked p))]
            (command/finding (str wiki-dir "/" p) nil "wiki-unindexed"
                             (str "the page " wiki-dir "/" p " is not listed in the index " index-rel)))))))

;; ---------------------------------------------------------------------------
;; The check

(defn check-wiki
  "Checks the wiki of the project at `root` and resolves its citations
  against `sources` (the index's) and `sources-dir` (or nil):
  {:pages [path] :citations [{:citation :file :line :need nil :status :reasons}] :findings [..]}."
  [root config {:keys [sources sources-dir]}]
  (let [wiki-dir (str/replace (or (:wiki-dir config) "dhf/wiki") #"/+$" "")
        dir (command/path root wiki-dir)
        files (wiki-files dir)
        index (first (filter #(= index-name (:file %)) files))
        pages (vec (remove #(= index-name (:file %)) files))
        rel #(str wiki-dir "/" %)
        index-rel (rel index-name)
        texts (into {} (for [f files] [(:file f) (slurp (:abs f))]))
        cites (vec (for [f files
                         :let [text (get texts (:file f))
                               notes (footnotes text)]
                         c (sources/citations-in text)]
                     (let [r (sources/resolve-citation sources sources-dir (:citation c))
                           label (footnote-at notes (:line c))]
                       (merge r {:file (rel (:file f)) :line (:line c) :need nil :footnote label}))))
        citation-findings (keep (fn [c]
                                  (sources/citation-finding
                                   (if (:footnote c) (str "the footnote [^" (:footnote c) "]") "the page")
                                   c))
                                cites)]
    {:pages (mapv #(rel (:file %)) pages)
     :citations (mapv #(dissoc % :footnote :rule) cites)
     :findings (vec (concat
                     (when (and (nil? index) (seq pages))
                       [(command/finding index-rel nil "wiki-no-index"
                                         (str "the wiki has pages and no index " index-rel))])
                     (when index
                       (index-findings wiki-dir index-rel (get texts index-name) (map :file pages)
                                       #(fs/regular-file? (fs/file dir %))))
                     (mapcat #(page-findings (rel (:file %)) (get texts (:file %))) pages)
                     (mapcat #(stack-word-findings (rel (:file %)) (get texts (:file %)) (:stack-words config)) files)
                     citation-findings))}))
