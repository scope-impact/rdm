(ns steps.knowledge-wiki
  "Step definitions for dhf/spec/knowledge/wiki.feature. Each scenario runs
  the rdm-spec command in a copy of the room, whose wiki gets the scenario's
  page. \"the configured stack words include ...\" is shared with
  dhf/spec/spec/check.feature and defined there."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(def ^:private resolving
  "Citations that resolve in the room's sources: recorded sources, at their
  pins, of paths that are not fenced."
  ["loyly:README.md@17d68d2" "innolitics-rdm:README.md@f21b508"])

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          page (k/page! dir "unsourced"
                        (str "# Unsourced\n\n"
                             "The spec is the source of truth.\n\n"
                             "The code is a build product.\n\n"
                             "Regenerating the code measures the spec.\n"))]
      (assoc ctx :page page))))

(defstep #"the check fails and names the page"
  (fn [ctx]
    (k/fails-with! ctx (str (:page ctx) ":"))))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\", which does not resolve"
  (fn [ctx citation]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          [_ source path] (re-matches #"([^:]+):(.+)@\w+" citation)]
      ;; The scenario's When gives no checkout, and without one a path is
      ;; never judged (it is unchecked). The path is made not to resolve the
      ;; way the index alone shows: it is fenced in its source.
      (k/sources! dir #(update-in % [(keyword source) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (let [page (k/page! dir "unresolved" (k/footnoted-page "Unresolved" [citation]))]
        (assoc ctx :page page :citation citation :footnote "[^1]"
               :line (k/line-of dir page (str "`" citation "`")))))))

(defstep #"the check fails and names the page, the footnote and why it did not resolve"
  (fn [ctx]
    (k/fails-with! ctx (str (:page ctx) ":" (:line ctx) ":") (:footnote ctx) (:citation ctx))
    (let [l (k/error-with (:run ctx) (str (:page ctx) ":" (:line ctx) ":") (:citation ctx))]
      (s/check! (re-find (re-pattern (str (java.util.regex.Pattern/quote (:citation ctx)) ".*\\w{3,}.*\\w{3,}")) l)
                (str "the finding does not say why: " l)))))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (assoc ctx :page (k/page! dir "sourced" (k/footnoted-page "Sourced" resolving))))))

(defstep #"the page passes"
  (fn [ctx]
    (let [r (:run ctx)
          cs (filter #(= (:page ctx) (:file %)) (k/citations-of r))]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (some #{(:page ctx)} (get-in (k/result r) [:knowledge :pages]))
                (str "the page was not read\n" (s/show r)))
      (s/check! (= (count resolving) (count cs)) (str "the page's footnotes were not all read: " (pr-str cs)))
      (s/check! (not-any? #(= :failed (:status %)) cs) (str "a footnote failed: " (pr-str cs)))
      (s/check! (not (k/error-with r (:page ctx))) (str "a finding names the page\n" (s/show r))))))

(defstep #"the wiki page (\S+) and an index that does not link it"
  (fn [ctx page]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          [_ name] (re-matches #"dhf/wiki/pages/(.+)\.md" page)]
      (s/check! name (str page " is not a page under dhf/wiki/pages/"))
      (k/page! dir name (k/footnoted-page "Cut and fence" resolving) false)
      (s/check! (not (str/includes? (slurp (k/index-file dir)) (str "pages/" name ".md"))) "the index links the page")
      (assoc ctx :page page))))

(defstep #"an index linking (\S+) and no such page"
  (fn [ctx entry]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (k/list-page! dir "Scoring" entry)
      (assoc ctx :entry entry :line (k/line-of dir "dhf/wiki/index.md" (str "(" entry ")"))))))

(defstep #"the check fails and names the entry"
  (fn [ctx]
    (k/fails-with! ctx (str "dhf/wiki/index.md:" (:line ctx) ":") (:entry ctx))))

(defstep #"a wiki page that says the check is written in (\S+)"
  (fn [ctx word]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          sentence (str "The check is written in " word ".")
          page (k/page! dir "stack" (str "# Stack\n\n" sentence "[^1]\n\n[^1]: `" (first resolving) "`, \"A section\".\n"))]
      (assoc ctx :page page :word word :line (k/line-of dir page sentence)))))

(defstep #"the check fails and names the page, the line and the word"
  (fn [ctx]
    (k/fails-with! ctx (str (:page ctx) ":" (:line ctx) ":") (:word ctx))))
