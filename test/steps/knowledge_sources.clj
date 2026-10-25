(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki, changed by the scenario, with
  local git checkouts of the sources (knowledge_support) given with --sources.

  A citation a scenario adds is cited by a need (UN-011 unless the step names
  one), so \"where it was cited\" is its line of dhf/spec/needs.edn.

  \"it passes\" is shared in wording with dhf/spec/project/init.feature and
  dhf/spec/spec/check.feature: defined here, when loading, unless a file
  loaded earlier defines it, so it is there for all three and never twice."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- passing-room!
  "ctx with a fresh copy of the room whose views are generated, so a whole
  check of it can pass."
  [ctx]
  (k/needs-knowledge!)
  (apply s/needs-built! k/check-ns)
  (let [dir (s/copy-of-room!)]
    (k/generate-views! dir)
    (assoc ctx :dir dir)))

(defn- cite!
  "Cites `citation` from `need` in the scenario's project: ctx with
  :citation and :cited-at (needs.edn:LINE)."
  ([ctx citation] (cite! ctx "UN-011" citation))
  ([ctx need citation]
   (let [ctx (k/ensure-room! ctx)
         line (k/cite! (:dir ctx) need citation)]
     (assoc ctx :citation citation :need need :cited-at (str k/needs-rel ":" line ":")))))

(defn- set-need-sources!
  "Replaces the citations of `need` with `citations`; ctx with :cited-at."
  [ctx need citations]
  (let [dir (:dir ctx)
        needs (assoc-in (k/read-edn dir k/needs-rel) [need :sources] (vec citations))]
    (k/write-needs! dir needs)
    (let [line (some (fn [[i l]] (when (str/includes? l (str "\"" (first citations) "\"")) (inc i)))
                     (map-indexed vector (str/split-lines (slurp (str (fs/file dir k/needs-rel))))))]
      (assoc ctx :need need :citation (first citations) :cited-at (str k/needs-rel ":" line ":")))))

(defn- checkout-of!
  "A checkout of the source `repo` (an id or owner/name) at `pin` under the
  scenario's sources directory, holding `paths-fn` of what the room cites of
  it: ctx with :sources-dir, :source and :pin."
  [ctx repo pin paths-fn]
  (k/needs-knowledge!)
  (let [idx (k/index s/room-root)
        id (k/source-id idx repo)
        sdir (k/sources-dir! ctx)]
    (s/check! (some #{pin} (get-in idx [(keyword id) :pins])) (str id " is not pinned at " pin))
    (k/checkout! sdir id pin (paths-fn (k/cited-paths id)))
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defn- finding-at
  "The run's first finding line that starts with `where` and, with the
  citation taken out, contains every one of `parts`."
  [ctx where parts what]
  (k/fails-with! ctx
                 (fn [l]
                   (and (str/starts-with? l where)
                        (let [rest* (if (:citation ctx) (k/without l (:citation ctx)) l)]
                          (every? #(str/includes? rest* %) parts))))
                 what))

(defn- citation-parts [c]
  (let [[_ source path commit] (re-matches #"([^:]+):(.+)@([0-9a-fA-F]+)" c)]
    {:source source :path path :commit commit}))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (k/update-index! dir #(assoc % :unpinned {:repository "https://example.org/unpinned"
                                                 :record "unpinned.md" :pins [] :fenced []}))
      (s/write! dir "dhf/raw/unpinned.md" "# unpinned\n\nA source recorded with no pin.\n")
      (assoc ctx :source "unpinned"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (finding-at ctx k/index-rel [(:source ctx)] (str "in " k/index-rel " naming " (:source ctx)))))

(defstep #"a commit of the sources index pins (\S+) at (\S+), and a later commit no longer lists it"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)
          kw (keyword id)]
      (s/check! (some #{pin} (get-in (k/index dir) [kw :pins])) (str "the room's index does not pin " id " at " pin))
      (k/git! dir "init" "-q")
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the sources as recorded")
      ;; the commit that drops the pin, for a newer one
      (k/update-index! dir #(update-in % [kw :pins] (fn [ps] (conj (vec (remove #{pin} ps)) "abcdef1"))))
      (k/git! dir "commit" "-q" "-am" "repin the source")
      (let [dropper (k/git! dir "rev-parse" "HEAD")]
        ;; a later commit of the index that changes something else
        (k/update-index! dir #(assoc-in % [:agent-skills :fenced] ["impl-later"]))
        (k/git! dir "commit" "-q" "-am" "a later change to the index")
        (assoc ctx :source id :pin pin :dropper dropper)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (finding-at ctx k/index-rel [(:source ctx) (:pin ctx) (subs (:dropper ctx) 0 7)]
                (str "in " k/index-rel " naming " (:source ctx) ", " (:pin ctx) " and " (:dropper ctx)))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (k/needs-knowledge!)
    (let [idx (k/index s/room-root)
          sdir (k/sources-dir! ctx)]
      (doseq [id [a b]
              :let [[pin & more] (get-in idx [(keyword id) :pins])
                    co (k/checkout! sdir id pin (k/cited-paths id))]]
        (doseq [p more] (k/git! co "tag" p)))
      (assoc ctx :sources-dir sdir :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cites (:citations (k/result r))]
      (s/check! (some? (k/result r)) (str "no knowledge result\n" (s/show r)))
      (doseq [id [a b]
              :let [mine (filter #(= id (str (:source %))) cites)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "citations of " id " not resolved against the checkout: "
                       (pr-str (remove #(= :resolved (:status %)) mine))))))))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation source]
    (let [ctx (k/ensure-room! ctx)]
      (s/check! (not (contains? (k/index (:dir ctx)) (keyword source))) (str source " is recorded"))
      (assoc (cite! ctx citation) :source source))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx source]
    (finding-at ctx (:cited-at ctx) [source] (str "at " (:cited-at ctx) " naming " source))))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc-in % [(keyword id) :pins] [pin]))
      (assoc ctx :source id :pins [pin]))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cite! ctx citation)))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [{:keys [commit]} (citation-parts (:citation ctx))]
      (finding-at ctx (:cited-at ctx) (cons commit (:pins ctx))
                  (str "at " (:cited-at ctx) " naming " commit " and the pins " (:pins ctx))))))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (checkout-of! ctx repo pin #(vec (distinct (conj % path))))))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (checkout-of! ctx repo pin #(vec (remove #{path} %)))))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin]
    (checkout-of! ctx repo pin identity)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (-> (cite! ctx citation)
        (k/run-check line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (:run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str "the citation " (:citation ctx) " was not reported\n" (s/show r)))
      (s/check! (every? #(= :resolved (:status %)) cs) (str "not resolved: " (pr-str cs)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a line names the citation\n" (s/show r))))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [{:keys [path commit]} (citation-parts (:citation ctx))]
      (finding-at ctx (:cited-at ctx) [path commit] (str "at " (:cited-at ctx) " naming " path " and " commit)))))

(defstep #"no checkout of (\S+) is given with --sources"
  (fn [ctx repo]
    (k/needs-knowledge!)
    (assoc (dissoc ctx :sources-dir) :source (k/source-id (k/index s/room-root) repo))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          c (:citation ctx)
          cs (k/citations-of r c)
          warnings (filter #(str/includes? % c) (k/warning-lines r))]
      (s/check! (seq cs) (str "the citation " c " was not reported\n" (s/show r)))
      (s/check! (every? #(= :unchecked (:status %)) cs) (str "not reported unchecked: " (pr-str cs)))
      (s/check! (every? #(seq (:reasons %)) cs) (str "no reason given: " (pr-str cs)))
      (s/check! (some #(re-find #"not checked" %) warnings) (str "no warning says " c " was not checked\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % c) (k/finding-lines r)) (str "a finding names " c "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx]
    (finding-at ctx (:cited-at ctx) [(:fenced ctx) "fenced"]
                (str "at " (:cited-at ctx) " naming the fenced " (:fenced ctx)))))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (finding-at ctx k/index-rel [(:source ctx) (:pin ctx) (:fenced ctx)]
                (str "in " k/index-rel " naming " (:source ctx) ", " (:pin ctx) " and " (:fenced ctx)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(def ^:private quoted-fenced "loyly:harness/fence.sh@17d68d2")

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (passing-room! ctx)
          dir (:dir ctx)
          {:keys [source path]} (citation-parts quoted-fenced)
          fenced (get-in (k/index dir) [(keyword source) :fenced])
          features (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))
          quoting (filter #(str/includes? (slurp %) (str "\"" quoted-fenced "\"")) features)]
      (s/check! (some #(or (= % path) (str/starts-with? path (str % "/"))) fenced)
                (str path " is not fenced in " source))
      (when (empty? quoting)
        ;; add the quote as an example step of the first scenario of this feature
        (let [f (str (fs/file dir "dhf/spec/knowledge/sources.feature"))
              text (slurp f)]
          (spit f (str/replace-first text #"(?m)^(\s*)(Given .*)$"
                                     (str "$1$2\n$1And a citation \"" quoted-fenced "\"")))))
      (assoc ctx :citation quoted-fenced))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)
          c (:citation ctx)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (empty? (k/citations-of r c)) (str "the quoted citation was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % c) (s/err-lines r)) (str "a line names " c "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source (\S+), pinned at the commit of a checkout given with --sources"
  (fn [ctx id]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)
          sdir (k/sources-dir! ctx)
          co (str (fs/file sdir id))]
      (s/write! co "README.md" (str "A demo source, " id ".\n"))
      (k/git! co "init" "-q")
      (k/git! co "add" "-A")
      (k/git! co "commit" "-q" "-m" (str "the source " id))
      (let [pin (k/git! co "rev-parse" "--short=7" "HEAD")]
        (k/update-index! dir #(assoc % (keyword id) {:repository (str "https://example.org/" id)
                                                     :record (str id ".md") :pins [pin] :fenced []}))
        (s/write! dir (str "dhf/raw/" id ".md") (str "# " id "\n\nA demo source.\n"))
        (assoc ctx :sources-dir sdir :source id :pin pin)))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx need citation]
    (set-need-sources! ctx need [(str citation "@" (:pin ctx))])))

(defstep #"it fails and names (UN-\d+), the citation and why it did not resolve"
  (fn [ctx need]
    (k/fails-with! ctx (fn [l]
                         (and (str/includes? l need) (str/includes? l (:citation ctx))
                              (re-find #"(?i)not|no |missing" (k/without l (:citation ctx)))))
                   (str "naming " need ", " (:citation ctx) " and why"))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\""
  (fn [ctx need citation]
    (-> (passing-room! ctx)
        (set-need-sources! need [citation]))))

(defstep #"no checkout of (\S+) is given"
  (fn [ctx _source]
    (dissoc ctx :sources-dir)))

(when-not (some #(re-matches (:pattern %) "it passes") @rdm-spec.kernel.runner/*registry*)
  (defstep #"it passes"
    (fn [ctx]
      (let [r (:run ctx)]
        (s/check! r "no command was run")
        (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (:run ctx)
          c (:citation ctx)
          lines (filter #(str/includes? % c) (k/warning-lines r))]
      (s/check! (seq lines) (str "no warning names " c "\n" (s/show r)))
      (s/check! (some #(and (re-find #"not checked" %)
                            (re-find #"(?i)no checkout|because|does not|is not" (k/without % c)))
                      lines)
                (str "no warning says it was not checked, and why\n" (s/show r))))))
