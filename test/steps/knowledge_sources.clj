(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki. A citation the scenario makes
  is added to a need (UN-011), whose line in needs.edn is where it is cited.

  The steps of @spec-0135 and @spec-0187 share their wording with the spec
  context's step file, which defines them at the cut; they are not defined
  here twice (one step text, one meaning)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc % :unpinned {:repository "https://example.org/unpinned"
                                                       :record "unpinned.md" :pins [] :fenced []}))
      (s/write! (:dir ctx) "dhf/raw/unpinned.md" "# unpinned\n\nA source recorded with no pin.\n")
      (assoc ctx :source "unpinned"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % k/index-rel) (str/includes? % (:source ctx)))
                   (str "in " k/index-rel " names " (:source ctx)))))

(defstep #"the sources index at HEAD pins (\S+) at (\S+)"
  (fn [ctx source pin]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (k/update-index! dir #(update-in % [(keyword source) :pins] (fn [ps] (vec (distinct (conj (vec ps) pin))))))
      (k/git! dir "init" "-q")
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the spec, its sources pinned")
      (assoc ctx :source source :pin pin))))

(defstep #"the index is changed so that (\S+) is no longer listed, and \"rdm-spec ([^\"]*)\" is run"
  (fn [ctx pin line]
    ;; another pin stays listed, so only the dropped one is at fault
    (k/update-index! (:dir ctx) #(update-in % [(keyword (:source ctx)) :pins]
                                            (fn [ps] (conj (vec (remove #{pin} ps)) "0abc123"))))
    (k/run-check (assoc ctx :pin pin) line)))

(defstep #"the check fails and names the source and the dropped pin"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % k/index-rel) (str/includes? % (:source ctx))
                             (str/includes? % (:pin ctx)))
                   (str "in " k/index-rel " names " (:source ctx) " and " (:pin ctx)))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defn- faithful-checkout!
  "ctx with a checkout of the source (repository owner/name or id) under its
  :sources-dir, holding what the room cites of it except `missing`."
  [ctx repo & missing]
  (let [id (k/source-id (k/read-edn s/room-root k/index-rel) repo)
        pin (first (:pins (get (k/read-edn s/room-root k/index-rel) (keyword id))))
        sdir (k/sources-dir! ctx)]
    (k/checkout! sdir id pin (remove (set missing) (k/cited-paths id)))
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (k/needs-knowledge!)
    (-> ctx (faithful-checkout! a) (faithful-checkout! b) (assoc :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cs (:citations (k/result r))]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (doseq [id [a b]
              :let [mine (filter #(str/starts-with? (:citation %) (str id ":")) cs)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "not every citation of " id " resolved against its checkout: " (pr-str mine))))
      (s/check! (every? #(not= :resolved (:status %))
                        (remove #(some (fn [id] (str/starts-with? (:citation %) (str id ":"))) [a b]) cs))
                "a citation of a source with no checkout resolved"))))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation source]
    (let [ctx (k/ensure-room! ctx)]
      (s/check! (not (contains? (k/index (:dir ctx)) (keyword source))) (str "the index records " source))
      (assoc ctx :citation citation :cited-at (k/cite! (:dir ctx) citation) :source source))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation]
    (let [ctx (k/ensure-room! ctx)]
      (assoc ctx :citation citation :cited-at (k/cite! (:dir ctx) citation)))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx what]
    (k/fails-with! ctx #(and (str/starts-with? % (str k/needs-rel ":" (:cited-at ctx) ":"))
                             (str/includes? % (:citation ctx))
                             (str/includes? (k/without % (:citation ctx)) what))
                   (str "at " k/needs-rel ":" (:cited-at ctx) " names " what))))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx source pin]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc-in % [(keyword source) :pins] [pin]))
      (assoc ctx :source source :pins [pin]))))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (k/fails-with! ctx #(let [rest* (k/without % (:citation ctx))]
                            (and (str/includes? % (:citation ctx)) (str/includes? rest* commit)
                                 (every? (fn [p] (str/includes? rest* p)) (:pins ctx))))
                     (str "names " (:citation ctx) ", the commit and the pins " (pr-str (:pins ctx)))))))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (k/needs-knowledge!)
    (let [ctx (faithful-checkout! ctx repo)]
      (s/check! (= pin (:pin ctx)) (str "the source is pinned at " (:pin ctx)))
      (s/check! (fs/exists? (fs/file (:sources-dir ctx) (:source ctx) path)) (str "the checkout holds no " path))
      ctx)))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (k/needs-knowledge!)
    (let [ctx (faithful-checkout! ctx repo path)]
      (s/check! (= pin (:pin ctx)) (str "the source is pinned at " (:pin ctx)))
      (s/check! (not (fs/exists? (fs/file (:sources-dir ctx) (:source ctx) path))) (str "the checkout holds " path))
      ctx)))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin]
    (k/needs-knowledge!)
    (let [ctx (faithful-checkout! ctx repo)]
      (s/check! (= pin (:pin ctx)) (str "the source is pinned at " (:pin ctx)))
      ctx)))

(defstep #"no checkout of (\S+) is given with --sources"
  (fn [ctx _repo]
    (dissoc (k/ensure-room! ctx) :sources-dir)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [ctx (k/ensure-room! ctx)
          at (k/cite! (:dir ctx) citation)]
      (k/run-check (assoc ctx :citation citation :cited-at at) line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (:run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (seq cs) (str "the run reports no citation " (:citation ctx) "\n" (s/show r)))
      (s/check! (every? #(= :resolved (:status %)) cs) (str "it did not resolve: " (pr-str cs)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a finding or warning names " (:citation ctx) "\n" (s/show r))))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path pin] (re-matches #"[^:]+:([^@]+)@(.+)" (:citation ctx))]
      (k/fails-with! ctx #(let [rest* (k/without % (:citation ctx))]
                            (and (str/includes? % (:citation ctx)) (str/includes? rest* path) (str/includes? rest* pin)))
                     (str "names " (:citation ctx) ", why: its path and its pin")))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          cs (k/citations-of r (:citation ctx))
          warns (filter #(str/includes? % (:citation ctx)) (k/warning-lines r))]
      (s/check! (seq cs) (str "the run reports no citation " (:citation ctx) "\n" (s/show r)))
      (s/check! (every? #(and (= :unchecked (:status %)) (seq (:reasons %))) cs)
                (str "not reported unchecked with a reason: " (pr-str cs)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (k/finding-lines r))
                (str "the unchecked citation fails\n" (s/show r)))
      (s/check! (some #(re-find #"not checked: \S" %) warns) (str "no warning says it was not checked, and why\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path source]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword source) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source source))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str k/needs-rel ":" (:cited-at ctx) ":"))
                             (str/includes? % (:citation ctx))
                             (re-find (re-pattern (str "fenced (path|directory) " (java.util.regex.Pattern/quote (:fenced ctx))
                                                       "(?![\\w./-])"))
                                      (k/without % (:citation ctx))))
                   (str "at " k/needs-rel ":" (:cited-at ctx) " names the fenced " (:fenced ctx)))))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % k/index-rel)
                             (every? (fn [x] (str/includes? % x)) [(:source ctx) (:pin ctx) (:fenced ctx)]))
                   (str "in " k/index-rel " names " (:source ctx) ", " (:pin ctx) " and " (:fenced ctx)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(def ^:private quoted-citation "loyly:harness/check.sh@17d68d2")

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (apply s/needs-built! k/check-ns)
    (let [ctx (k/ensure-room! ctx)
          f (fs/file (:dir ctx) "dhf/spec/knowledge/wiki.feature")
          text (slurp (str f))
          [_ path] (re-matches #"[^:]+:([^@]+)@.*" quoted-citation)
          ;; the step is added under the first Given, so the scenario stays whole
          text' (str/replace-first text #"(?m)^([ \t]+)(Given [^\n]*\n)"
                                   (str "$1$2$1And the example quotes \"" quoted-citation "\"\n"))]
      (s/check! (some #{path} (get-in (k/index (:dir ctx)) [:loyly :fenced])) (str path " is not fenced"))
      (s/check! (not= text text') "no Given to quote the citation under")
      (spit (str f) text')
      (k/generate-views! (:dir ctx))
      (assoc ctx :citation quoted-citation))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (empty? (k/citations-of r (:citation ctx))) (str "the quoted citation was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "the error stream names the quoted citation\n" (s/show r))))))
