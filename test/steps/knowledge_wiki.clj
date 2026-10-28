(ns steps.knowledge-wiki
  "Steps of the component Wiki (03-ground-the-spec/wiki.feature)."
  (:require [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.knowledge-sources :refer [ensure-project]]
            [rdm-spec.kernel.runner :refer [defstep]]))

;; ------------------------------------------------------------------ the wiki

(defn page! [ctx name text & {:keys [indexed] :or {indexed true}}]
  (let [ctx (ensure-project ctx) d (:dir ctx)]
    (k/write! d (str "dhf/wiki/pages/" name ".md") text)
    (when indexed
      (let [idx (k/slurp-in d "dhf/wiki/index.md")]
        (k/write! d "dhf/wiki/index.md" (str idx "\n- [" name "](pages/" name ".md): a page.\n"))))
    (assoc ctx :page (str "dhf/wiki/pages/" name ".md"))))

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx] (page! ctx "plain" "# Plain\n\nOne claim.\n\nTwo claims.\n\nThree claims.\n")))

(defstep #"the check fails and names the page"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx (:page ctx)) ctx))

(defstep #"a wiki page whose footnote cites \"loyly:docs/plan.md@17d68d2\""
  (fn [ctx] (page! ctx "plan" (str "# Plan\n\nA claim.[^p]\n\n[^p]: `loyly:docs/plan.md@" (get-in ctx [:pins "loyly"]) "`, \"a section\".\n"))))

(defstep #"the check fails and names the page, the footnote and why it did not resolve"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/wiki/pages/plan.md:5:" "[^p]" "docs/plan.md") ctx))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx] (page! ctx "good" "# Good\n\nA claim.[^a] Another.[^b]\n\n[^a]: `loyly:README.md@17d68d2`, \"one\".\n[^b]: `innolitics-rdm:README.md@f21b508`, \"two\".\n")))

(defstep #"the page passes"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (not (some #(str/starts-with? % "dhf/wiki/pages/good.md") (k/finding-lines ctx))) ctx "a finding names the page") ctx))

(defstep #"the wiki page dhf/wiki/pages/cut-and-fence.md and an index that does not link it"
  (fn [ctx] (page! ctx "cut-and-fence" "# Cut\n\nA claim.[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"one\".\n" :indexed false)))

(defstep #"an index linking pages/scoring.md and no such page"
  (fn [ctx]
    (let [ctx (ensure-project ctx)]
      (k/write! (:dir ctx) "dhf/wiki/index.md" "# Wiki\n\n## Pages\n\n- [Scoring](pages/scoring.md): how scores are kept.\n")
      ctx)))

(defstep #"the check fails and names the entry"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/wiki/index.md:5:" "pages/scoring.md") ctx))

(defstep #"a wiki page that says the check is written in Cobalt"
  (fn [ctx] (page! ctx "cobalt" "# The check\n\nThe check is written in Cobalt.[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"one\".\n")))

(defstep #"the check fails and names the page, the line and the word"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/wiki/pages/cobalt.md:3:" "Cobalt") ctx))
