(ns rdm-spec.knowledge.wiki
  "The Wiki component of the knowledge context (dhf/spec/knowledge/wiki.feature):
  the domain wiki, every page indexed, every claim footnoted to a source, no
  page naming the stack.

  The wiki is the configured :wiki-dir (default dhf/wiki): its index,
  index.md, and its pages, every other Markdown file under it. The index
  lists pages with Markdown links, relative to the index.

  Pure functions over the wiki as read; the Sources component resolves the
  citations they find and reports the wiki's findings with its own.

  Rules, each a stable name on the error stream:
    wiki-no-index           the wiki directory holds no index.md
    wiki-page-unlisted      a page the index does not link
    wiki-index-entry-missing  an index entry naming no page
    wiki-no-footnote        a page with no footnote: its claims cite nothing
    wiki-footnote-uncited   a footnote holding no citation
    wiki-stack-word         a line of a page or the index naming a configured stack word"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Reading

(defn read-wiki
  "The wiki under the project `root` at `wiki-dir` (relative):
  {:dir :index {:file :text} or nil :pages [{:file :text}]}, paths relative
  to the project, sorted."
  [root wiki-dir]
  (let [dir (command/path root wiki-dir)
        rel #(command/rel root %)
        index-f (fs/file dir "index.md")]
    {:dir (str/replace (str wiki-dir) #"/+$" "")
     :exists? (fs/directory? dir)
     :index (when (fs/regular-file? index-f) {:file (rel index-f) :text (slurp (str index-f))})
     :pages (if (fs/directory? dir)
              (vec (sort-by :file
                            (for [f (fs/glob dir "**.md")
                                  :when (and (fs/regular-file? f) (not= (fs/normalize f) (fs/normalize index-f)))]
                              {:file (rel f) :text (slurp (str f))})))
              [])}))

(defn lines [text] (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (str text))))

;; ---------------------------------------------------------------------------
;; Citations

(def citation-re
  "A citation as a wiki line writes it: <source>:<path>@<commit>, the commit
  seven to forty hex digits."
  #"(?<![\w./:-])([A-Za-z][A-Za-z0-9_-]*):(?!//)([^\s`'\"()\[\]<>@,;]+)@([0-9a-f]{7,40})(?![0-9A-Za-z])")

(def footnote-def-re #"^\s{0,4}\[\^([^\]\s]+)\]:\s?(.*)$")

(defn citations-in
  "Every citation in `text` (a page or the index of `file`): [{:citation
  :source :path :commit :file :line :footnote}], :footnote the label of the
  footnote it is written in, or nil."
  [file text]
  (vec (for [[n l] (lines text)
             :let [fn-label (second (re-matches footnote-def-re l))]
             [whole source path commit] (re-seq citation-re l)]
         {:citation whole :source source :path path :commit commit :file file :line n
          :footnote (when fn-label (str "[^" fn-label "]"))})))

(defn footnotes
  "The footnote definitions of `text`: [{:label \"[^1]\" :line :text}]."
  [text]
  (vec (for [[n l] (lines text)
             :let [[_ label body] (re-matches footnote-def-re l)]
             :when label]
         {:label (str "[^" label "]") :line n :text body})))

;; ---------------------------------------------------------------------------
;; The index

(def ^:private link-re #"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

(defn index-entries
  "The pages the index `index` links: [{:target :file :line}], :file the
  linked path relative to the project (inside the wiki only)."
  [root wiki-dir {:keys [file text]}]
  (let [wiki (fs/normalize (fs/absolutize (command/path root wiki-dir)))]
    (vec (for [[n l] (lines text)
               [_ target] (re-seq link-re l)
               :let [t (first (str/split target #"#"))]
               :when (and (not (str/blank? t))
                          (not (re-find #"^[a-z][a-z0-9+.-]*:" t))
                          (str/ends-with? t ".md"))
               :let [abs (fs/normalize (fs/absolutize (fs/file (fs/parent (command/path root file)) t)))]
               :when (fs/starts-with? abs wiki)]
           {:target t :file (command/rel root abs) :line n}))))

;; ---------------------------------------------------------------------------
;; The rules

(defn stack-word-findings
  "A finding for each line of the wiki's files naming a configured stack word."
  [files words]
  (let [res (for [w (remove str/blank? (map str words))]
              [w (re-pattern (str "(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])"))])]
    (vec (for [{:keys [file text]} files
               [n l] (lines text)
               [w re] res
               :when (re-find re l)]
           (command/finding file n "wiki-stack-word"
                            (str "names the stack word " w ": no wiki page names an implementation"
                                 " (the stack lives in dhf/harness/stack.md)"))))))

(defn wiki-findings
  "Every finding of the wiki's own rules (citations are resolved by Sources)."
  [root wiki-dir {:keys [index pages exists?] :as wiki} stack-words]
  (if-not exists?
    []
    (let [entries (when index (index-entries root wiki-dir index))
          listed (set (map :file entries))
          page-files (set (map :file pages))]
      (vec
       (concat
        (when-not index
          [(command/finding (str (:dir wiki) "/index.md") "wiki-no-index"
                            "the wiki has no index: every page is listed in index.md")])
        (when index
          (for [p pages :when (not (listed (:file p)))]
            (command/finding (:file p) "wiki-page-unlisted"
                             (str "the page " (:file p) " is not linked from the index " (:file index)
                                  ": every page is listed in it"))))
        (for [e entries :when (not (page-files (:file e)))]
          (command/finding (:file index) (:line e) "wiki-index-entry-missing"
                           (str "the index links " (:target e) ", but there is no page " (:file e))))
        (for [p pages :when (empty? (footnotes (:text p)))]
          (command/finding (:file p) "wiki-no-footnote"
                           (str "the page " (:file p) " has no footnote: every claim in the wiki cites a source,"
                                " <source>:<path>@<commit>, in a footnote")))
        (for [{:keys [file text]} (concat (when index [index]) pages)
              fnote (footnotes text)
              :when (empty? (citations-in file (:text fnote)))]
          (command/finding file (:line fnote) "wiki-footnote-uncited"
                           (str "the footnote " (:label fnote) " cites no source: write <source>:<path>@<commit>")))
        (stack-word-findings (concat (when index [index]) pages) stack-words))))))
