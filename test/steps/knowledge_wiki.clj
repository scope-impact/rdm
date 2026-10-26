(ns steps.knowledge-wiki
  "Step definitions of dhf/spec/knowledge/wiki.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.knowledge-sources :as ks]))

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx]
    (-> (k/wiki-page! (k/project! ctx) "dhf/wiki/pages/bare.md" "# Bare\n\nOne.\n\nTwo.\n\nThree.\n")
        (k/names+ "dhf/wiki/pages/bare.md"))))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources, with no docs/plan.md"
  (fn [ctx] (let [ctx (ks/checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"})]
              (-> (ks/loyly! ctx ["17d68d2"]) (k/names+ "docs/plan.md")))))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx]
    (let [ctx (ks/checkout! ctx "demo" "DEMOPIN" {"README.md" "# Demo\n"})
          ctx (ks/source! ctx "demo" {:repository "https://example.org/demo" :record "demo.md" :pins [(k/real-pin ctx "DEMOPIN")] :fenced []})
          c (k/real-pin ctx "demo:README.md@DEMOPIN")]
      (k/update-edn! ctx "dhf/spec/needs.edn" assoc-in ["UN-001" :sources] [c])
      (k/wiki-page! ctx "dhf/wiki/pages/sourced.md" (str "# Sourced\n\nA claim.[^a]\n\nAnother.[^b]\n\n[^a]: `" c "`, \"One\".\n[^b]: `" c "`, \"Two\".\n")))))

(defstep #"the page passes"
  (fn [ctx]
    (let [ctx (k/rdm ctx ["check" "--sources" (get-in ctx [:ph "DIR"])])]
      (k/check-exit ctx 0)
      (assert (not (str/includes? (:err ctx) "sourced.md")) (:err ctx))
      ctx)))

(defstep #"the wiki page dhf/wiki/pages/cut-and-fence.md and an index that does not link it"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/write-files! (:dir ctx) {"dhf/wiki/pages/cut-and-fence.md" "# Cut and fence\n\nA claim.[^1]\n\n[^1]: `demo:README.md@abc1234`, \"A\".\n"})
      (k/names+ ctx "dhf/wiki/pages/cut-and-fence.md"))))

(defstep #"an index linking pages/scoring.md and no such page"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          f (fs/file (k/guard! (:dir ctx)) "dhf/wiki/index.md")]
      (spit f (str (slurp f) "- [Scoring](pages/scoring.md)\n"))
      (k/names+ ctx "pages/scoring.md"))))

(defstep #"a wiki page that says the check is written in Cobalt"
  (fn [ctx]
    (-> (k/wiki-page! ctx "dhf/wiki/pages/cobalt.md" "# Cobalt\n\nThe check is written in Cobalt.[^1]\n\n[^1]: `demo:README.md@abc1234`, \"A\".\n")
        (k/names+ "dhf/wiki/pages/cobalt.md:3"))))
