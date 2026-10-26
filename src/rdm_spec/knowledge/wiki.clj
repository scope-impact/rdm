(ns rdm-spec.knowledge.wiki
  "The wiki (dhf/spec/knowledge/wiki.feature): one index listing every page,
  every page footnoted, no page naming the stack. Its footnotes' citations are
  resolved with the sources."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(defn index-links
  "[[target line] ...] of every relative Markdown link to a .md file in the index."
  [text]
  (for [[i l] (map-indexed vector (str/split-lines (str text)))
        [_ target] (re-seq #"\]\(([^)\s#]+\.md)(?:#[^)]*)?\)" l)
        :when (not (re-find #"^[a-z]+:" target))]
    [target (inc i)]))

(defn footnoted? [text] (boolean (re-find #"(?m)^\s*\[\^[^\]]+\]:" (str text))))

(defn- normalize [path]
  (str/join "/" (reduce (fn [acc p] (cond (= p ".") acc (= p "..") (vec (butlast acc)) :else (conj acc p)))
                        [] (str/split path #"/"))))

;; Copied from the spec check: the wiki never names the stack either.
(defn stack-findings [files words]
  (for [[file text] files
        [i line] (map-indexed vector (str/split-lines (str text)))
        w words
        :when (re-find (re-pattern (str "(?<![\\p{L}\\p{N}])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}])")) line)]
    (cmd/finding file (inc i) "stack-word"
                 (str "names the stack word " w ": the wiki never names the stack, which lives in dhf/harness/stack.md"))))

(defn stage [{:keys [root config revision]}]
  (let [d (:wiki-dir config "dhf/wiki")
        index (str d "/index.md")
        itext (cmd/read-at root revision index)
        pages (filter #(str/ends-with? % ".md") (cmd/files-at root revision (str d "/pages")))
        links (index-links itext)
        linked (set (map (fn [[t _]] (normalize (str d "/" t))) links))]
    (if (nil? itext)
      {:findings (if (seq pages) [(cmd/finding index nil "wiki-no-index" "the wiki has pages and no index")] [])}
      {:findings
       (vec
        (concat
         (for [[t line] links :when (not (cmd/read-at root revision (normalize (str d "/" t))))]
           (cmd/finding index line "wiki-index-broken" (str "the index links " t ", and no such page exists")))
         (for [p pages :when (not (linked p))]
           (cmd/finding p nil "wiki-unindexed" (str "the page " p " is not listed in the index " index)))
         (for [p pages :when (not (footnoted? (cmd/read-at root revision p)))]
           (cmd/finding p nil "wiki-unsourced" (str "the page " p " has no footnote: every claim cites a source")))
         (stack-findings (for [p (cons index pages)] [p (cmd/read-at root revision p)]) (:stack-words config []))))})))
