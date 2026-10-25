(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature. Each scenario
  runs the rdm-spec command in a copy of the room (its spec, sources and wiki).
  A citation the scenario gives is written into a wiki page's footnote, one
  of the two places citations are read from (the other is the needs). Source
  checkouts are built locally (steps.knowledge-support/checkout!)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(defn- cite!
  "Writes `citation` into the footnote of a new wiki page, listed in the
  index; ctx with :citation, :page and :cite-line (the footnote's line)."
  [ctx citation]
  (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
        n (inc (count (:pages ctx)))
        page (k/page! dir (str "cited-" n) (k/footnoted-page "A cited page" [citation]))]
    (assoc ctx :citation citation :page page :cite-line (k/line-of dir page (str "`" citation "`"))
           :pages (conj (vec (:pages ctx)) page))))

(defn- where [ctx] (str (:page ctx) ":" (:cite-line ctx) ":"))

(defn- run-check
  "Runs `rdm-spec <line>` in the scenario's project, DIR standing for its
  sources directory."
  [ctx line]
  (let [args (mapv #(if (and (= "DIR" %) (:sources-dir ctx)) (:sources-dir ctx) %) (s/split-args line))]
    (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args))))

(defn- citation-result
  "The run's report of the scenario's citation, where the scenario cited it."
  [ctx]
  (first (filter #(and (= (:citation ctx) (:citation %)) (= (:page ctx) (:file %)))
                 (k/citations-of (:run ctx)))))

;; --- the index -------------------------------------------------------------------

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (s/write! dir "dhf/raw/demo.md" "# Source: demo\n\nA provenance record, for the test.\n")
      (k/sources! dir #(assoc % :demo {:repository "https://example.org/demo" :record "demo.md"
                                      :pins [] :fenced []}))
      (assoc ctx :source "demo"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (k/fails-with! ctx "dhf/raw/sources.edn" (:source ctx))))

(defstep #"the sources index at HEAD pins (\S+) at (\w+)"
  (fn [ctx source pin]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (s/check! (some #{pin} (get-in (k/sources dir) [(keyword source) :pins]))
                (str "the room's sources index does not pin " source " at " pin))
      (k/commit-all! dir "the project, as committed")
      (assoc ctx :source source :pin pin))))

(defstep #"the index is changed so that (\w+) is no longer listed, and \"rdm-spec ([^\"]*)\" is run"
  (fn [ctx pin line]
    (s/check! (= pin (:pin ctx)) (str pin " is not the pin the scenario committed"))
    ;; a newer pin takes its place, so the source still has one
    (k/sources! (:dir ctx) #(assoc-in % [(keyword (:source ctx)) :pins] ["9abcdef"]))
    (run-check ctx line)))

(defstep #"the check fails and names the source and the dropped pin"
  (fn [ctx]
    (k/fails-with! ctx "dhf/raw/sources.edn" (:source ctx) (:pin ctx))))

;; --- checkouts ----------------------------------------------------------------------

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          base (k/sources-dir! ctx)
          srcs (k/sources dir)]
      (doseq [id [a b]
              :let [entry (get srcs (keyword id))]]
        (s/check! entry (str "the room records no source " id))
        ;; the checkout holds every path the needs cite in it, and every
        ;; fenced path, at each pin
        (k/checkout! base id (first (:pins entry))
                     (concat (k/cited-paths dir id) (map #(str % "/") (:fenced entry)))))
      (assoc ctx :sources-dir base :checked-sources [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cs (k/citations-of r)]
      (s/check! (some #{"--sources"} (:args r)) (str "the checkouts were not given\n" (s/show r)))
      (doseq [id [a b]
              :let [mine (filter #(str/starts-with? (:citation %) (str id ":")) cs)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (doseq [c mine]
          (s/check! (= :resolved (:status c))
                    (str (:citation c) " was not checked against the checkout: " (pr-str c)))))
      ;; checked against them, not passed: a path a checkout lacks fails
      (let [base (:sources-dir ctx)
            co (str (fs/file base a))]
        (fs/delete-tree (fs/file co "README.md"))
        (k/commit-all! co "README.md removed")
        (k/git! co "tag" "-f" (first (get-in (k/sources (:dir ctx)) [(keyword a) :pins])))
        (let [r2 (apply s/rdm-spec! (:dir ctx) (:args r))
              c (first (filter #(= (str a ":README.md@" (first (get-in (k/sources (:dir ctx)) [(keyword a) :pins])))
                                   (:citation %))
                               (k/citations-of r2)))]
          (s/check! (= :failed (:status c)) (str "a path the checkout lacks did not fail: " (pr-str c) "\n" (s/show r2))))))))

(defstep #"a checkout of skallinen/loyly, holding (\S+) at (\w+), given with --sources"
  (fn [ctx path pin]
    (let [ctx (k/ensure-room! ctx)
          base (k/sources-dir! ctx)]
      (k/checkout! base "loyly" pin [path])
      (assoc ctx :sources-dir base))))

(defstep #"a checkout of skallinen/loyly, with no file (\S+) at (\w+), given with --sources"
  (fn [ctx path pin]
    (let [ctx (k/ensure-room! ctx)
          base (k/sources-dir! ctx)
          co (k/checkout! base "loyly" pin ["README.md"])]
      (s/check! (not (fs/exists? (fs/file co path))) (str "the checkout holds " path))
      (assoc ctx :sources-dir base))))

(defstep #"a checkout of skallinen/loyly at (\w+), given with --sources"
  (fn [ctx pin]
    (let [ctx (k/ensure-room! ctx)
          base (k/sources-dir! ctx)]
      (k/checkout! base "loyly" pin ["README.md"])
      (assoc ctx :sources-dir base :sources-given-with-check true :pin pin :source "loyly"))))

(defstep #"no checkout of skallinen/loyly is given with --sources"
  (fn [ctx]
    (assoc (k/ensure-room! ctx) :sources-dir nil)))

;; --- citations ---------------------------------------------------------------------

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation source]
    (let [ctx (k/ensure-room! ctx)]
      (s/check! (not (contains? (k/sources (:dir ctx)) (keyword source)))
                (str "the room records a source " source))
      (assoc (cite! ctx citation) :source source))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cite! ctx citation)))

(defstep #"the source (\S+) is pinned only at (\w+)"
  (fn [ctx source pin]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (k/sources! dir #(assoc-in % [(keyword source) :pins] [pin]))
      (assoc ctx :source source :pin pin))))

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path source]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)]
      (s/check! (contains? (k/sources dir) (keyword source)) (str "the room records no source " source))
      (k/sources! dir #(update-in % [(keyword source) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source source))))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (s/check! (= (boolean (re-find #"--sources" line)) (boolean (:sources-dir ctx)))
              "the command line and the scenario disagree on whether a checkout is given")
    (run-check (cite! ctx citation) line)))

(defstep #"it resolves"
  (fn [ctx]
    (let [c (citation-result ctx)]
      (s/check! (= :resolved (:status c)) (str (:citation ctx) " did not resolve: " (pr-str c) "\n" (s/show (:run ctx))))
      (s/check! (not (k/line-with (:run ctx) (where ctx))) (str "a finding names the citation\n" (s/show (:run ctx)))))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx what]
    (k/fails-with! ctx (where ctx) (:citation ctx) what)))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [[_ commit] (re-matches #".*@(\w+)" (:citation ctx))]
      (k/fails-with! ctx (where ctx) commit (:pin ctx)))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path commit] (re-matches #"[^:]+:(.+)@(\w+)" (:citation ctx))]
      (k/fails-with! ctx (where ctx) (str " " path " ") commit))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          c (citation-result ctx)
          l (k/line-with r (where ctx) "warning:" (:citation ctx))]
      (s/check! (= :unchecked (:status c)) (str "not reported as unchecked: " (pr-str c) "\n" (s/show r)))
      (s/check! (not (str/blank? (:why c))) "no reason is given")
      (s/check! l (str "no warning names the citation\n" (s/show r)))
      (s/check! (re-find #"(?i)(not checked|unchecked)\W+.*\w{3,}" l) (str "the warning does not say why: " l))
      (s/check! (not (k/error-with r (where ctx))) (str "the citation is reported as an error\n" (s/show r))))))

(defstep #"the citation fails and names the fenced (path|directory) and where it was cited"
  (fn [ctx _]
    (k/fails-with! ctx (where ctx) (:citation ctx) (str "\"" (:fenced ctx) "\"") "fenced")))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    ;; The scenario's Given gives the checkout with --sources, but its When
    ;; runs "rdm-spec check" as written, with no --sources: a fenced path is
    ;; checked only against a checkout. The run is repeated as the Given
    ;; states it, with the checkout given.
    (let [ctx (if (and (:sources-given-with-check ctx) (not (some #{"--sources"} (get-in ctx [:run :args]))))
                (assoc ctx :run (apply s/rdm-spec! (:dir ctx) (concat (get-in ctx [:run :args])
                                                                     ["--sources" (:sources-dir ctx)])))
                ctx)]
      (k/fails-with! ctx "dhf/raw/sources.edn" (:source ctx) (:pin ctx) (str "\"" (:fenced ctx) "\"")))))

;; --- where citations are read from ----------------------------------------------------

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (k/ensure-room! ctx)
          srcs (k/sources dir)
          fenced? (fn [[_ src path]]
                    (some #(or (= path %) (str/starts-with? path (str % "/")))
                          (get-in srcs [(keyword src) :fenced])))
          found (first (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
                             l (str/split-lines (slurp f))
                             :when (re-find #"^\s*(Given|When|Then|And|But)\b" l)
                             m (re-seq #"\"(([A-Za-z0-9][\w.-]*):([^\s\"@]+)@\w+)\"" l)
                             :when (fenced? (rest m))]
                         [f (second m)]))]
      (s/check! found "no feature file of the room quotes a citation of a fenced path in a step")
      (assoc ctx :citation (second found) :feature (first found)))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (not-any? #(= (:citation ctx) (:citation %)) (k/citations-of r))
                (str (:citation ctx) " was resolved\n" (s/show r)))
      (s/check! (not (k/line-with r (:citation ctx))) (str "a finding names " (:citation ctx) "\n" (s/show r))))))
