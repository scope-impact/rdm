(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki, changed by the scenario, with
  local git checkouts of a source built by steps.knowledge-support.

  The two scenarios of the rule \"A need's citations resolve\" (spec-0135 and
  spec-0187) share every step's wording with test/steps/spec_check.clj, which
  defines them; they are not defined again here, so no step matches twice."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(def sources-rel k/index-rel)

(defn- finding-on
  "The run's finding lines on `file` (any line)."
  [ctx file]
  (filter #(str/starts-with? % (str file ":")) (k/finding-lines (:run ctx))))

(defn- cited!
  "Adds `citation` to a need of the scenario's project (once); ctx with
  :citation and :cited-at (needs.edn:LINE)."
  [ctx citation]
  (let [ctx (k/ensure-room! ctx)
        line (k/cite! (:dir ctx) citation)]
    (assoc ctx :citation citation :cited-at (str k/needs-rel ":" line))))

(defn- checkout-of!
  "A checkout of the source `repo` (owner/name or id) pinned at `pin`, in the
  scenario's sources directory, holding what the room cites of it except
  `without`: ctx with :sources-dir, :source and :pin."
  [ctx repo pin without]
  (let [ctx (k/ensure-room! ctx)
        idx (k/index (:dir ctx))
        id (k/source-id idx repo)
        sdir (k/sources-dir! ctx)]
    (s/check! (some #{pin} (get-in idx [(keyword id) :pins])) (str id " is not pinned at " pin))
    (k/checkout! sdir id pin (remove (set without) (k/cited-paths id)))
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defn- citation-of
  "The run's one reported citation `citation`, failing when there is none."
  [ctx citation]
  (or (first (k/citations-of (:run ctx) citation))
      (s/fail! (str "the run reported no citation " citation "\n" (s/show (:run ctx))))))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (s/write! dir "dhf/raw/unpinned.md" "# unpinned\n\nA source recorded with no pin.\n")
      (k/update-index! dir #(assoc % :unpinned {:repository "https://example.org/unpinned"
                                                :record "unpinned.md" :pins [] :fenced []}))
      (assoc ctx :source "unpinned"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str sources-rel ":")) (str/includes? % (:source ctx)))
                   (str "in " sources-rel " names " (:source ctx)))))

(defstep #"the sources index at HEAD pins (\S+) at (\S+)"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (s/check! (some #{pin} (get-in (k/index dir) [(keyword id) :pins])) (str id " is not pinned at " pin))
      (k/git! dir "init" "-q")
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the spec, with its sources index")
      (assoc ctx :source id :pin pin))))

(defstep #"the index is changed so that (\S+) is no longer listed, and \"rdm-spec ([^\"]*)\" is run"
  (fn [ctx pin line]
    ;; another pin stands in its place, so the source is still pinned
    (k/update-index! (:dir ctx) #(update-in % [(keyword (:source ctx)) :pins]
                                            (fn [pins] (conj (vec (remove #{pin} pins)) "abcdef0"))))
    (k/run-check ctx line)))

(defstep #"the check fails and names the source and the dropped pin"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str sources-rel ":"))
                             (str/includes? % (:source ctx)) (str/includes? % (:pin ctx)))
                   (str "in " sources-rel " names " (:source ctx) " and " (:pin ctx)))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (let [ctx (k/ensure-room! ctx)
          idx (k/index (:dir ctx))
          sdir (k/sources-dir! ctx)]
      (doseq [id [a b]]
        (k/checkout! sdir id (first (get-in idx [(keyword id) :pins])) (k/cited-paths id)))
      (assoc ctx :sources-dir sdir :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cites (:citations (k/result r))]
      (s/check! (seq cites) (str "the run reported no citations\n" (s/show r)))
      (doseq [id [a b]
              :let [mine (filter #(= id (:source %)) cites)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "citations of " id " not resolved against the checkout: "
                       (pr-str (remove #(= :resolved (:status %)) mine)) "\n" (s/show r))))
      ;; a source with no checkout in the directory is not checked
      (s/check! (every? #(= :unchecked (:status %)) (remove #(#{a b} (:source %)) cites))
                (str "a citation of another source was checked\n" (s/show r))))))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation id]
    (let [ctx (k/ensure-room! ctx)]
      (s/check! (not (contains? (k/index (:dir ctx)) (keyword id))) (str "the source " id " is recorded"))
      (assoc (cited! ctx citation) :source id))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx id]
    (k/fails-with! ctx #(and (str/starts-with? % (str (:cited-at ctx) ":")) (str/includes? % id)
                             (str/includes? % (:citation ctx)))
                   (str "at " (:cited-at ctx) " names " id))))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc-in % [(keyword id) :pins] [pin]))
      (assoc ctx :source id :pins [pin]))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cited! ctx citation)))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (k/fails-with! ctx #(and (str/starts-with? % (str (:cited-at ctx) ":"))
                               (str/includes? (k/without % (:citation ctx)) commit)
                               (every? (fn [p] (str/includes? % p)) (:pins ctx)))
                     (str "at " (:cited-at ctx) " names " commit " and the pins " (:pins ctx))))))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (let [ctx (checkout-of! ctx repo pin [])]
      (s/check! (fs/regular-file? (fs/file (:sources-dir ctx) (:source ctx) path)) (str "the checkout holds no " path))
      ctx)))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (checkout-of! ctx repo pin [path])))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin]
    (checkout-of! ctx repo pin [])))

(defstep #"no checkout of (\S+) is given with --sources"
  (fn [ctx _repo]
    (dissoc (k/ensure-room! ctx) :sources-dir)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [ctx (k/ensure-room! ctx)
          needs (slurp (str (fs/file (:dir ctx) k/needs-rel)))
          ctx (if (str/includes? needs (str "\"" citation "\"")) (assoc ctx :citation citation) (cited! ctx citation))]
      (k/run-check ctx line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [c (citation-of ctx (:citation ctx))]
      (s/check! (= :resolved (:status c)) (str "the citation is " (pr-str c) "\n" (s/show (:run ctx))))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines (:run ctx)))
                (str "a line names " (:citation ctx) "\n" (s/show (:run ctx)))))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path pin] (re-matches #"[^:]+:(.+)@(\w+)" (:citation ctx))]
      (s/check! (= :unresolved (:status (citation-of ctx (:citation ctx)))) "the citation is not unresolved")
      (k/fails-with! ctx #(let [rest* (k/without % (:citation ctx))]
                            (and (str/includes? % (:citation ctx)) (str/includes? rest* path) (str/includes? rest* pin)))
                     (str "names " (:citation ctx) ", " path " and " pin)))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          c (citation-of ctx (:citation ctx))
          warned (filter #(and (str/includes? % "warning:") (str/includes? % (:citation ctx))) (s/err-lines r))]
      (s/check! (= :unchecked (:status c)) (str "the citation is " (pr-str c)))
      (s/check! (seq (:reasons c)) "no reason is given")
      (s/check! (seq warned) (str "no warning names " (:citation ctx) "\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (k/finding-lines r))
                (str "a finding names " (:citation ctx) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defn- fenced-cited! [ctx]
  (k/fails-with! ctx #(and (str/starts-with? % (str (:cited-at ctx) ":"))
                           (str/includes? (k/without % (:citation ctx)) (:fenced ctx))
                           (re-find #"fenced" %))
                 (str "at " (:cited-at ctx) " names the fenced " (:fenced ctx))))

(defstep #"the citation fails and names the fenced path and where it was cited"
  (fn [ctx] (fenced-cited! ctx)))

(defstep #"the citation fails and names the fenced directory and where it was cited"
  (fn [ctx] (fenced-cited! ctx)))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str sources-rel ":")) (str/includes? % (:source ctx))
                             (str/includes? % (:pin ctx)) (str/includes? % (:fenced ctx)))
                   (str "in " sources-rel " names " (:source ctx) ", " (:pin ctx) " and " (:fenced ctx)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(def quoted-citation "loyly:impl/booking.clj@17d68d2")

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (apply s/needs-built! k/check-ns)
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)
          f (fs/file dir "dhf/spec/knowledge/sources.feature")
          text (slurp (str f))
          step (re-find #"(?m)^\s+(?:Given|And) a citation \"loyly:harness/fence\.sh@17d68d2\"\n" text)]
      (s/check! step "sources.feature has no step quoting a fenced citation to extend")
      (spit (str f) (str/replace-first text step (str step "      And a citation \"" quoted-citation "\"\n")))
      (k/generate-views! dir)
      (assoc ctx :citation quoted-citation))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (empty? (k/citations-of r (:citation ctx))) (str "the quoted citation was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a line names " (:citation ctx) "\n" (s/show r))))))
