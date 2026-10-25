(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki (a fixture), which the Givens
  change; source checkouts are fresh git repositories made by
  steps.knowledge-support/checkout!.

  The shared When (\"rdm-spec ...\" is run), \"it passes\" and \"a wiki page
  whose footnote cites ...\" are the kernel's (steps.kernel-command).

  Context keys: :dir the project, :run the last run, :source the source a
  Given named, :citation and :cited-at {:file :line} the citation a Given
  wrote, :sources-dir the directory of checkouts (DIR), :pin, :fenced."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- source! [ctx id m]
  (let [ctx (k/ensure-room! ctx)]
    (k/update-index! (:dir ctx) #(assoc % (keyword id) m))
    (assoc ctx :source id)))

(defn- record! [dir file] (s/write! dir (str "dhf/raw/" file) (str "# " file "\n\nA fixture source record.\n")))

(defn- line-naming
  "The run's first finding line holding every one of `parts`, or a failure."
  [ctx & parts]
  (k/fails-with! ctx (fn [l] (every? #(str/includes? l (str %)) parts))
                 (str "naming all of " (pr-str parts))))

(defn- cite-in-need!
  "Writes `citation` among the sources of `need`; ctx with :citation and
  :cited-at."
  [ctx need citation]
  (let [ctx (k/ensure-room! ctx)
        line (k/cite! (:dir ctx) need citation)]
    (assoc ctx :citation citation :need need :cited-at {:file k/needs-rel :line line})))

(defn- at-citation!
  "The finding line at the place the citation was written, naming `parts`."
  [ctx & parts]
  (let [{:keys [file line]} (:cited-at ctx)]
    (k/fails-with! ctx (fn [l] (and (str/starts-with? l (str file ":" line ":"))
                                   (every? #(str/includes? l (str %)) parts)))
                   (str "at " file ":" line " naming " (pr-str parts)))))

(defn- loyly-checkout!
  "A checkout of loyly at `pin` under the scenario's sources directory,
  holding what the room cites of it, minus `without`."
  [ctx repo pin without]
  (let [ctx (k/ensure-room! ctx)
        idx (k/index (:dir ctx))
        id (k/source-id idx repo)
        sdir (k/sources-dir! ctx)]
    (s/check! (some #{pin} (get-in idx [(keyword id) :pins])) (str id " is not pinned at " pin))
    (k/checkout! sdir id pin (remove #{without} (k/cited-paths id)))
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defn- commit-all! [dir msg]
  (when-not (fs/exists? (fs/file dir ".git")) (k/git! dir "init" "-q"))
  (k/git! dir "add" "-A")
  (k/git! dir "commit" "-q" "--allow-empty" "-m" msg)
  (k/git! dir "rev-parse" "HEAD"))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (source! ctx "demo" {:repository "https://example.org/demo" :record "demo.md" :pins [] :fenced []})]
      (record! (:dir ctx) "demo.md")
      ctx)))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx]
    (let [ctx (source! ctx "demo" {:record "demo.md" :pins ["abc1234"] :fenced []})]
      (record! (:dir ctx) "demo.md")
      ctx)))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx]
    (let [ctx (source! ctx "demo" {:repository "https://example.org/demo" :record "demo-missing.md"
                                   :pins ["abc1234"] :fenced []})]
      (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/raw/demo-missing.md"))) "the record exists")
      (assoc ctx :record "demo-missing.md"))))

(defstep #"the check fails and names the source"
  (fn [ctx] (line-naming ctx (str k/index-rel ":") (:source ctx)) nil))

(defstep #"it fails rule (\S+) and names the source"
  (fn [ctx rule] (line-naming ctx (str ": " rule ": ") (:source ctx)) nil))

(defstep #"it fails rule (\S+) and names the source and the record file"
  (fn [ctx rule] (line-naming ctx (str ": " rule ": ") (:source ctx) (:record ctx)) nil))

(defstep #"a commit of the sources index pins (\S+) at (\S+), and a later commit keeps \1 with that pin replaced by another"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (k/update-index! dir #(assoc-in % [(keyword id) :pins] [pin]))
      (commit-all! dir "the sources index pins it")
      (k/update-index! dir #(assoc-in % [(keyword id) :pins] ["abcdef1"]))
      (let [c (commit-all! dir "the pin replaced")]
        (assoc ctx :source id :pin pin :dropped-by c)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (line-naming ctx (:source ctx) (:pin ctx) (subs (:dropped-by ctx) 0 7))
    nil))

(defstep #"a commit of the sources index lists (\S+) with its pins, and a later commit keeps them and adds :retired \"([^\"]+)\""
  (fn [ctx id why]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)
          kid (keyword id)]
      (s/check! (seq (get-in (k/index dir) [kid :pins])) (str id " has no pins"))
      (k/update-index! dir #(update % kid dissoc :retired))
      (commit-all! dir "the index lists it")
      (k/update-index! dir #(assoc-in % [kid :retired] why))
      (commit-all! dir "the source retired")
      (assoc ctx :source id))))

(defstep #"the check reports no dropped pin for (\S+)"
  (fn [ctx id]
    (let [r (kc/the-run ctx)]
      (s/check! (not-any? #(and (str/includes? % "source-pin-dropped") (str/includes? % id)) (s/err-lines r))
                (str "a dropped pin is reported for " id "\n" (s/show r)))
      nil)))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx] (line-naming ctx (:citation ctx) "retired") nil))

(defstep #"the need (UN-\d+) whose sources hold \"([^\"]+)\""
  (fn [ctx need text] (cite-in-need! ctx need text)))

(defstep #"it fails rule (\S+) and names (UN-\d+) and the source"
  (fn [ctx rule need] (line-naming ctx (str ": " rule ": ") need (:citation ctx)) nil))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (let [ctx (k/ensure-room! ctx)
          idx (k/index (:dir ctx))
          sdir (k/sources-dir! ctx)]
      (doseq [id [a b]
              :let [pins (get-in idx [(keyword id) :pins])]]
        (s/check! (seq pins) (str id " has no pin"))
        (k/checkout! sdir id (first pins) (k/cited-paths id)))
      (assoc ctx :sources-dir sdir :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (kc/the-run ctx)
          cites (:citations (k/result r))]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (doseq [id [a b]
              :let [mine (filter #(= id (:source %)) cites)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "a citation of " id " was not checked against its checkout: "
                       (pr-str (remove #(= :resolved (:status %)) mine)))))
      nil)))

(defstep #"a citation \"([^\"]+)\"(?: and no source (\S+))?"
  (fn [ctx citation absent]
    (let [ctx (k/ensure-room! ctx)]
      (when absent
        (s/check! (not (contains? (k/index (:dir ctx)) (keyword absent))) (str "the source " absent " is recorded")))
      (cite-in-need! ctx "UN-011" citation))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx id] (at-citation! ctx id) nil))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)]
      (s/check! (= [pin] (get-in (k/index (:dir ctx)) [(keyword id) :pins])) (str id " is not pinned only at " pin))
      (assoc ctx :source id :pins [pin]))))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (apply at-citation! ctx commit (:pins ctx))
      nil)))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (let [ctx (loyly-checkout! ctx repo pin nil)]
      (s/check! (fs/exists? (fs/file (:sources-dir ctx) (:source ctx) path)) (str "the checkout holds no " path))
      ctx)))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin] (loyly-checkout! ctx repo pin path)))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin] (loyly-checkout! ctx repo pin nil)))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (dissoc (k/ensure-room! ctx) :sources-dir)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [ctx (k/ensure-room! ctx)
          text (slurp (str (fs/file (:dir ctx) k/needs-rel)))
          ctx (if (str/includes? text (str "\"" citation "\""))
                (assoc ctx :citation citation)
                (cite-in-need! ctx "UN-011" citation))]
      (s/check! (= (some? (:sources-dir ctx)) (str/includes? line "--sources"))
                "the command line and the checkouts disagree")
      (kc/run-line ctx line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str "the citation " (:citation ctx) " was not reported\n" (s/show r)))
      (s/check! (every? #(= :resolved (:status %)) cs) (str "it did not resolve: " (pr-str cs)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (k/finding-lines r))
                (str "a finding names " (:citation ctx) "\n" (s/show r)))
      nil)))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ _ path pin] (re-matches #"([^:]+):(.+)@(\S+)" (:citation ctx))]
      (line-naming ctx path pin)
      nil)))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str "the citation " (:citation ctx) " was not reported\n" (s/show r)))
      (s/check! (every? #(= :unchecked (:status %)) cs) (str "it was not reported unchecked: " (pr-str cs)))
      (s/check! (every? #(seq (:reasons %)) cs) (str "no reason was given: " (pr-str cs)))
      (s/check! (some #(and (str/includes? % (:citation ctx)) (str/includes? % "warning: ")) (s/err-lines r))
                (str "no warning names " (:citation ctx) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx] (at-citation! ctx (:fenced ctx) "fenced") nil))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx] (line-naming ctx (:source ctx) (:pin ctx) (:fenced ctx)) nil))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(def ^:private quoted-fenced "loyly:harness/fence.sh@17d68d2")

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)
          f (fs/file (:dir ctx) "dhf/spec/knowledge/sources.feature")
          text (slurp (str f))
          given "Given a feature file whose Given step quotes a citation of a fenced path"
          changed (str/replace text given (str "Given a feature file whose Given step quotes \"" quoted-fenced "\""))]
      (s/check! (not= text changed) "the fixture's Given was not found")
      (s/check! (some #(str/ends-with? quoted-fenced (str ":" % "@17d68d2"))
                      (get-in (k/index (:dir ctx)) [:loyly :fenced]))
                "the quoted citation is not of a fenced path")
      (spit (str f) changed)
      (assoc ctx :citation quoted-fenced))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (empty? (k/citations-of r (:citation ctx))) (str "the quoted citation was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a line names the quoted citation\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source (\S+), pinned at the commit of a checkout given with --sources"
  (fn [ctx id]
    (let [ctx (k/ensure-room! ctx)
          sdir (k/sources-dir! ctx)
          co (str (fs/file sdir id))]
      (s/write! co "README.md" "A fixture source.\n")
      (k/git! co "init" "-q")
      (k/git! co "add" "-A")
      (k/git! co "commit" "-q" "-m" "a fixture checkout")
      (let [pin (subs (k/git! co "rev-parse" "HEAD") 0 7)]
        (k/update-index! (:dir ctx) #(assoc % (keyword id) {:repository (str "https://example.org/" id)
                                                            :record (str id ".md") :pins [pin] :fenced []}))
        (record! (:dir ctx) (str id ".md"))
        (assoc ctx :sources-dir sdir :source id :pin pin)))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx need cited]
    (let [path (second (str/split cited #":" 2))]
      (s/check! (not (fs/exists? (fs/file (:sources-dir ctx) (:source ctx) path))) (str "the checkout holds " path))
      (cite-in-need! ctx need (str cited "@" (:pin ctx))))))

(defstep #"it fails and names (UN-\d+), the citation and why it did not resolve"
  (fn [ctx need]
    (k/fails-with! ctx #(and (str/includes? % need) (str/includes? % (:citation ctx))
                             (re-find #"(?i)\bnot\b|\bno\b" (k/without % (:citation ctx))))
                   (str "naming " need ", " (:citation ctx) " and why"))
    nil))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\""
  (fn [ctx need citation]
    (let [ctx (k/ensure-room! ctx)
          sources (get-in (k/read-edn (:dir ctx) k/needs-rel) [need :sources])]
      (if (some #{citation} sources)
        (assoc ctx :citation citation :need need)
        (cite-in-need! ctx need citation)))))

(defstep #"no checkout of (\S+) is given"
  (fn [ctx _] (dissoc ctx :sources-dir)))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (some #(and (str/includes? % "warning: ") (str/includes? % (:citation ctx))
                            (re-find #"not checked" %))
                      (s/err-lines r))
                (str "no warning says " (:citation ctx) " was not checked, and why\n" (s/show r)))
      nil)))
