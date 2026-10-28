(ns steps.knowledge-wiki
  "Steps of the component Wiki (dhf/spec/03-ground-the-spec/wiki.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.knowledge-sources :as s]))

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx] (s/page! (s/base! ctx) "bare" "# Bare\n\nOne.\n\nTwo.\n\nThree.\n")))

(defstep #"the check fails and names the page"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/err-line-has! ctx (some #(when (str/includes? (str (:err ctx)) %) %) ["dhf/wiki/pages/bare.md" "dhf/wiki/pages/cut-and-fence.md"]))
    ctx))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources, with no docs/plan.md"
  (fn [ctx]
    (let [ctx (s/checkout! (s/base! ctx) "loyly" {"README.md" "# Loyly\n"})]
      (s/source! ctx :loyly {:pins [(s/pin ctx "loyly")]}))))

(defstep #"a wiki page whose footnote cites \"loyly:docs/plan.md@17d68d2\""
  (fn [ctx] (s/page! ctx "plan" (s/cited-page (str "loyly:docs/plan.md@" (s/pin ctx "loyly"))))))

(defstep #"the check fails and names the page, the footnote and why it did not resolve"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/wiki/pages/plan.md:5" "loyly:docs/plan.md" "does not hold") ctx))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx]
    (let [ctx (s/checkout! (s/base! ctx) "loyly" {"README.md" "# Loyly\n"})]
      (s/source! ctx :loyly {:pins [(s/pin ctx "loyly")]})
      (s/page! ctx "good" (s/cited-page (str "loyly:README.md@" (s/pin ctx "loyly"))))
      ctx)))

(defstep #"the page passes"
  (fn [ctx]
    (let [ctx (k/run-rdm ctx "check --sources DIR")]
      (k/exits! ctx 0)
      (k/check! (not-any? #(str/includes? % "good.md") (k/err-lines ctx)) "a line names the page: " (k/shows ctx))
      ctx)))

(defstep #"the wiki page dhf/wiki/pages/cut-and-fence.md and an index that does not link it"
  (fn [ctx]
    (let [ctx (s/base! ctx)]
      (k/write! ctx "dhf/wiki/pages/cut-and-fence.md" (s/cited-page "demo:README.md@abc1234")))))

(defstep #"an index linking pages/scoring.md and no such page"
  (fn [ctx]
    (let [ctx (s/base! ctx)]
      (k/write! ctx "dhf/wiki/index.md" (str (k/slurp-rel ctx "dhf/wiki/index.md") "\n- [Scoring](pages/scoring.md): how a cycle is scored.\n")))))

(defstep #"the check fails and names the entry"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/wiki/index.md" "pages/scoring.md") ctx))

(defstep #"a wiki page that says the check is written in Cobalt"
  (fn [ctx] (s/page! ctx "cobalt" "# Cobalt\n\nThe check is written in Cobalt.[^a]\n\n[^a]: `demo:README.md@abc1234`, \"x\".\n")))

(defstep #"the check fails and names the page, the line and the word"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/wiki/pages/cobalt.md:3:" "Cobalt") ctx))
