(ns steps.knowledge-wiki
  "Step definitions for dhf/spec/knowledge/wiki.feature, in a copy of the
  room's spec, sources and wiki, whose wiki the scenario adds pages to.

  Two steps share their wording with other features: \"a wiki page whose
  footnote cites ...\" (dhf/spec/publishing/site.feature) and \"the
  configured stack words include ...\" (dhf/spec/spec/check.feature). Each is
  defined here only when no other step file defines it (the first at run
  time, by the step before it in the scenario), so never twice."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(def footnoted-paragraph
  "A paragraph with its footnote, citing a recorded, pinned source."
  "The code is built from the spec, and can be deleted and built again.[^1]")

(defn- page!
  "Writes the wiki page `rel` (under dhf/wiki) holding `body`, listed in the
  index unless `listed?` is false. Returns ctx with :page (its path)."
  ([ctx rel body] (page! ctx rel body true))
  ([ctx rel body listed?]
   (let [ctx (k/ensure-room! ctx)
         dir (:dir ctx)]
     (s/write! dir (str k/wiki-rel "/" rel) body)
     (when listed?
       (spit (str (fs/file dir k/wiki-rel "index.md")) (str "\n- [A page](" rel ")\n") :append true))
     (assoc ctx :page (str k/wiki-rel "/" rel) :page-text body))))

(defn- site-page!
  "The meaning of \"a wiki page whose footnote cites C\": a fresh copy of the
  room whose wiki holds a page, listed in the index, one footnote citing C."
  [ctx citation]
  (k/needs-knowledge!)
  (let [ctx (assoc ctx :dir (s/copy-of-room!))
        ctx (page! ctx "regeneration.md"
                   (str "# Regeneration\n\n" footnoted-paragraph "\n\n[^1]: `" citation "`, \"What to steal\" 1.\n"))]
    (assoc ctx :citation citation :footnote "[^1]")))

;; ---------------------------------------------------------------------------
;; Every claim in the wiki cites a source

(defstep #"a wiki page with three paragraphs and no footnote"
  (fn [ctx]
    (page! ctx "pages/unsourced.md"
           (str "# Unsourced\n\nThe spec is the source of truth.\n\n"
                "The code is a build product.\n\nA regeneration is a measurement.\n"))))

(defstep #"the check fails and names the page"
  (fn [ctx]
    (k/fails-with! ctx #(str/starts-with? % (str (:page ctx) ":")) (str "names the page " (:page ctx)))))

(defstep #"a checkout of (\S+) at (\S+), given with --sources, with no (\S+)"
  (fn [ctx repo pin path]
    (k/needs-knowledge!)
    (k/ensure-step! "a wiki page whose footnote cites \"loyly:README.md@17d68d2\""
                    #"a wiki page whose footnote cites \"([^\"]+)\""
                    site-page!)
    (let [idx (k/read-edn s/room-root k/index-rel)
          id (k/source-id idx repo)
          sdir (k/sources-dir! ctx)]
      (s/check! (some #{pin} (get-in idx [(keyword id) :pins])) (str id " is not pinned at " pin))
      (k/checkout! sdir id pin (remove #{path} (k/cited-paths id)))
      (assoc ctx :sources-dir sdir :source id :pin pin))))

(defstep #"the check fails and names the page, the footnote and why it did not resolve"
  (fn [ctx]
    (let [page (or (:page ctx) (str k/wiki-rel "/" (:wiki-page ctx) ".md"))
          ;; the site's definition of the Given keeps the page, not the citation
          ctx (assoc ctx :citation (or (:citation ctx)
                                       (second (re-find #"\[\^1\]: `([^`]+)`" (slurp (str (fs/file (:dir ctx) page)))))))]
      (k/fails-with! ctx #(and (str/starts-with? % (str page ":"))
                               (str/includes? % (or (:footnote ctx) "[^1]"))
                               (str/includes? % (:citation ctx))
                               (re-find #"(?i)not|no " (k/without % (:citation ctx))))
                     (str "names " page ", the footnote, " (:citation ctx) " and why")))))

(defstep #"a wiki page whose every footnote cites a resolving source"
  (fn [ctx]
    (page! ctx "pages/regeneration.md"
           (str "# Regeneration\n\n" footnoted-paragraph "\n\n"
                "The spec carries the product; the build is measured against it.[^2]\n\n"
                "[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"
                "[^2]: `innolitics-rdm:README.md@f21b508`, the design goals.\n"))))

(defstep #"the page passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (not-any? #(str/includes? % (:page ctx)) (k/finding-lines r))
                (str "a finding names " (:page ctx) "\n" (s/show r)))
      (s/check! (some #{(:page ctx)} (:pages (k/result r))) (str "the page was not read\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; The wiki has one index, and every page is in it

(defstep #"the wiki page (\S+) and an index that does not link it"
  (fn [ctx path]
    (let [rel (subs path (inc (count k/wiki-rel)))
          ctx (page! ctx rel (str "# A page\n\n" footnoted-paragraph "\n\n[^1]: `loyly:README.md@17d68d2`.\n") false)
          index (slurp (str (fs/file (:dir ctx) k/wiki-rel "index.md")))]
      (s/check! (= path (:page ctx)) (str "the page is " (:page ctx)))
      (s/check! (not (str/includes? index rel)) "the index links the page")
      ctx)))

(defstep #"an index linking (\S+) and no such page"
  (fn [ctx rel]
    (let [ctx (k/ensure-room! ctx)
          index (str k/wiki-rel "/index.md")]
      (spit (str (fs/file (:dir ctx) index)) (str "\n- [Scoring](" rel ")\n") :append true)
      (s/check! (not (fs/exists? (fs/file (:dir ctx) k/wiki-rel rel))) (str "the page " rel " exists"))
      (assoc ctx :entry rel :index index))))

(defstep #"the check fails and names the entry"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str (:index ctx) ":")) (str/includes? % (:entry ctx)))
                   (str "in " (:index ctx) " names " (:entry ctx)))))

;; ---------------------------------------------------------------------------
;; The wiki never names an implementation

;; Shared in wording with dhf/spec/spec/check.feature: the same meaning there.
(when-not (some #(re-matches (:pattern %) "the configured stack words include \"Cobalt\"")
                @rdm-spec.kernel.runner/*registry*)
  (defstep #"the configured stack words include \"([^\"]+)\""
    (fn [ctx word]
      (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))
            cfg (fs/file (:dir ctx) "dhf/harness/config.edn")
            c (k/read-edn (:dir ctx) "dhf/harness/config.edn")]
        (spit (str cfg) (pr-str (update c :stack-words #(vec (distinct (conj (vec %) word))))))
        (assoc ctx :stack-word word)))))

(defstep #"a wiki page that says the check is written in (\S+)"
  (fn [ctx word]
    (let [ctx (page! ctx "pages/the-check.md"
                     (str "# The check\n\n" footnoted-paragraph "\n\nThe check is written in " word ".[^1]\n\n"
                          "[^1]: `loyly:README.md@17d68d2`.\n"))]
      (assoc ctx :word word :word-line 5))))

(defstep #"the check fails and names the page, the line and the word"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str (:page ctx) ":" (:word-line ctx) ":")) (str/includes? % (:word ctx)))
                   (str "names " (:page ctx) ":" (:word-line ctx) " and " (:word ctx)))))
