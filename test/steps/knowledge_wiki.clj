(ns steps.knowledge-wiki
  "Step definitions for dhf/spec/knowledge/wiki.feature: `rdm-spec check` on
  a copy of the room whose wiki a step changes (steps.knowledge-support).

  The shared steps (\"rdm-spec LINE\" is run, a wiki page whose footnote
  cites C, the configured stack words include W) are the kernel's
  (steps.kernel-command); the checkout of loyly is steps.knowledge-sources'.

  Context keys set here: :dir the project; :page the page (relative to the
  project); :entry an index entry with no page; :word-line the line naming
  the stack word."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx]
    (let [dir (k/project ctx)
          page (k/add-wiki-page! dir "plain.md"
                                 (str "# Plain\n\nThe first paragraph says something.\n\n"
                                      "The second says more.\n\nThe third says it all again.\n"))]
      (assoc ctx :dir dir :page page))))

(defstep #"the check fails and names the page"
  (fn [ctx] (k/fails-naming! ctx (str (:page ctx) ":")) nil))

(defstep #"the check fails and names the page, the footnote and why it did not resolve"
  (fn [ctx]
    (let [l (k/fails-naming! ctx (str (:page ctx) ":") (:footnote ctx) (:citation ctx) "docs/plan.md")]
      (s/check! (re-find #"(?i)does not (exist|hold)|no file" l)
                (str "the finding does not say why the citation did not resolve: " l))
      nil)))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx]
    (let [dir (k/project ctx)
          page (k/add-wiki-page! dir "resolving.md"
                                 (str "# Resolving\n\nThe checklist is derived, never written.[^1]\n\n"
                                      "An id is never reused.[^2]\n\n"
                                      "[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"
                                      "[^2]: `loyly:harness/spec.feature@17d68d2`, \"Ids\".\n"))]
      (assoc ctx :dir dir :page page))))

(defstep #"the page passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not (k/line-naming (k/findings ctx) [(:page ctx)]))
                (str "a finding names the page\n" (s/show r)))
      nil)))

(defstep #"the wiki page (\S+) and an index that does not link it"
  (fn [ctx rel]
    (let [dir (k/project ctx)]
      (s/write! dir rel (str "# Cut and fence\n\nA cut is a tag.[^1]\n\n"
                             "[^1]: `loyly:README.md@17d68d2`, \"The idea\".\n"))
      (s/check! (not (str/includes? (slurp (str (fs/file dir (k/wiki-rel dir) "index.md"))) (fs/file-name rel)))
                "the index links the page")
      (assoc ctx :dir dir :page rel))))

(defstep #"an index linking (\S+) and no such page"
  (fn [ctx entry]
    (let [dir (k/project ctx)
          wiki (k/wiki-rel dir)]
      (s/check! (not (fs/exists? (fs/file dir wiki entry))) (str entry " exists"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [Scoring](" entry ")\n") :append true)
      (assoc ctx :dir dir :entry entry))))

(defstep #"the check fails and names the entry"
  (fn [ctx] (k/fails-naming! ctx (:entry ctx)) nil))

(defstep #"a wiki page that says the check is written in (\S+)"
  (fn [ctx word]
    (let [dir (k/project ctx)
          page (k/add-wiki-page! dir "language.md"
                                 (str "# Language\n\nThe check reads the spec.[^1]\n\n"
                                      "The check is written in " word ".\n\n"
                                      "[^1]: `loyly:README.md@17d68d2`, \"The idea\".\n"))]
      (assoc ctx :dir dir :page page :word-line 5))))

(defstep #"the check fails and names the page, the line and the word"
  (fn [ctx]
    (k/fails-naming! ctx (str (:page ctx) ":" (:word-line ctx) ":") (:stack-word ctx))
    nil))
