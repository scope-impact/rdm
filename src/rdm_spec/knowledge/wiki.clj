(ns rdm-spec.knowledge.wiki
  "Component Wiki (knowledge context): the domain wiki, dhf/wiki/index.md and
  the pages under dhf/wiki/pages/. Every page indexed, nothing indexed that
  is not a page, every page footnoted to a source (its footnotes resolved by
  the sources component), no stack word."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.knowledge.sources :as sources]))

(defn index-links
  "Every link of the index to a page: [{:target :line}], targets relative to the wiki."
  [text]
  (for [[i l] (map-indexed vector (str/split-lines (or text "")))
        [_ t] (re-seq #"\]\(([^)\s#]+\.md)(?:#[^)]*)?\)" l)
        :when (not (re-find #"^[a-z]+:" t))]
    {:target (str/replace t #"^\./" "") :line (inc i)}))

(defn check
  "The wiki's findings at TREE."
  [tree cfg]
  (let [wd (:wiki-dir cfg)
        d (project/abs tree wd)
        idx (str wd "/index.md")]
    (if-not (fs/directory? d)
      []
      (let [text (project/read-text tree idx)
            links (index-links text)
            linked (set (map #(str wd "/" (:target %)) links))
            pages (vec (sort (for [f (fs/glob (str d "/pages") "**.md")] (project/rel tree f))))]
        (vec (concat
              (when-not text [(project/finding idx "wiki-no-index" "the wiki has no index")])
              (for [{:keys [target line]} links
                    :when (not (fs/regular-file? (project/abs tree (str wd "/" target))))]
                (project/finding idx line "wiki-entry-missing" (str "the index links " target ", and there is no such page")))
              (for [p pages :when (not (linked p))]
                (project/finding p "wiki-unindexed" (str "the index does not list the page " p ": every page is in the index")))
              (for [p pages
                    :when (not (re-find #"(?m)^\s*\[\^[^\]]+\]:" (or (project/read-text tree p) "")))]
                (project/finding p "wiki-uncited" (str "the page " p " has no footnote: every claim in the wiki cites a source")))
              (project/stack-word-findings tree wd (:stack-words cfg))))))))

(defn check-stage [{:keys [cfg]}]
  {:result {:wiki {:pages (count (filter #(str/includes? % "/pages/") (sources/wiki-files (project/root) cfg)))}}
   :findings (check (project/root) cfg)
   :warnings []})
