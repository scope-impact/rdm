(ns rdm-spec.knowledge.wiki
  "The domain wiki: every page indexed, every claim footnoted to a source, no
  implementation named (dhf/spec/03-ground-the-spec/wiki.feature). Footnotes
  are resolved by Sources, which reads the wiki's citations."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(defn stack-findings
  "Each line naming a configured stack word, one finding per word."
  [file text words]
  (vec (for [[i line] (map-indexed vector (str/split-lines (str text)))
             w words
             :when (re-find (re-pattern (str "(?<![A-Za-z0-9])" (java.util.regex.Pattern/quote w) "(?![A-Za-z0-9])")) line)]
         (project/finding file (inc i) "stack-word" (str "names " w ", a stack word: the wiki never names an implementation")))))

(defn page-findings
  "A page with no footnote, and every stack word it names."
  [file text words]
  (vec (concat
        (when-not (re-find #"(?m)^\s*\[\^[^\]]+\]:" (str text))
          [(project/finding file "wiki-uncited" "the page has no footnote: every claim in the wiki cites a source")])
        (stack-findings file text words))))

(defn index-links [text]
  (vec (for [[i line] (map-indexed vector (str/split-lines (str text)))
             [_ target] (re-seq #"\]\(([^)\s#]+\.md)(?:#[^)]*)?\)" line)
             :when (not (re-find #"^[a-z]+:" target))]
         {:target target :line (inc i)})))

(defn index-findings
  "A page the index does not list, and an index entry with no page."
  [wiki-dir index-text pages]
  (let [index (str wiki-dir "/index.md")
        links (index-links index-text)
        linked (set (map #(str (fs/normalize (fs/path wiki-dir (:target %)))) links))]
    (vec (concat
          (for [p pages :when (not (linked p))]
            (project/finding p "wiki-unindexed" (str "the wiki's index does not list " p ": every page is in the index")))
          (for [{:keys [target line]} links
                :let [p (str (fs/normalize (fs/path wiki-dir target)))]
                :when (not (some #{p} pages))]
            (project/finding index line "wiki-page-missing" (str "the index links " target ", and there is no such page")))))))

(defn check
  "check's stage: the wiki's index and pages."
  [run]
  (let [root (:root run) cfg (:cfg run) dir (:wiki-dir cfg)
        index (str dir "/index.md")
        words (:stack-words cfg)]
    (if-not (fs/directory? (fs/path root dir))
      {}
      (let [pages (vec (remove #{index} (filter #(str/ends-with? % ".md") (project/files-under root dir))))
            itext (when (fs/exists? (fs/path root index)) (slurp (str (fs/path root index))))]
        {:findings (vec (concat
                         (if itext
                           (concat (index-findings dir itext pages) (stack-findings index itext words))
                           (when (seq pages) [(project/finding index "wiki-no-index" "the wiki has pages and no index.md")]))
                         (mapcat #(page-findings % (slurp (str (fs/path root %))) words) pages)))}))))
