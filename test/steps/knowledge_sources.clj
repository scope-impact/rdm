(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki (steps.knowledge-support),
  changed by the scenario: a source added or re-pinned in the index, a
  citation added to a need, a local git checkout of a source given with
  --sources, or the copy made a git repository whose index has a history.

  A citation a scenario adds goes into a need (UN-011 unless the scenario
  names one), so \"where it was cited\" is dhf/spec/needs.edn and its line.

  Shared steps used, defined elsewhere: the When \"rdm-spec ...\" is run
  (kernel/command), \"it passes\" (spec/check) and \"a wiki page whose
  footnote cites ...\" (publishing/site)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- room [ctx] (k/ensure-room! ctx))

(defn- cite-in-room!
  "Adds `citation` to the need `need` of the scenario's copy: ctx with
  :citation and :where (needs.edn:LINE)."
  [ctx need citation]
  (let [ctx (room ctx)
        _ (k/cite! (:dir ctx) need citation)
        ;; one need per line (k/write-needs!): the citation is on its need's line
        line (some (fn [[i l]] (when (and (str/includes? l (str "\"" need "\""))
                                          (str/includes? l (str "\"" citation "\"")))
                                 (inc i)))
                   (map-indexed vector (str/split-lines (slurp (str (fs/file (:dir ctx) k/needs-rel))))))]
    (s/check! line (str need " does not cite " citation))
    (assoc ctx :citation citation :need need :where (str k/needs-rel ":" line ":"))))

(defn- cited-at!
  "The run failed with a finding at the citation's place naming it; returns
  the finding line."
  [ctx pred what]
  (k/fails-with! ctx #(and (str/starts-with? % (:where ctx))
                           (str/includes? % (:citation ctx))
                           (pred %))
                 (str "at " (:where ctx) " naming " (:citation ctx) " " what)))

(defn- word-re
  "Matches `w` standing alone (not inside an id like innolitics-rdm)."
  [w]
  (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote w) "(?![\\w-])")))

(defn- git-room!
  "Makes the scenario's copy a git repository with everything committed:
  ctx with :dir."
  [ctx]
  (let [ctx (room ctx)
        dir (:dir ctx)]
    (when-not (fs/exists? (fs/file dir ".git"))
      (k/git! dir "init" "-q")
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the project"))
    ctx))

(defn- commit-index!
  "Rewrites the copy's sources index as (f index) and commits it; returns
  the commit's full hash."
  [dir f message]
  (k/update-index! dir f)
  (k/git! dir "add" "-A")
  (k/git! dir "commit" "-q" "-m" message)
  (k/git! dir "rev-parse" "HEAD"))

(defn- checkout-of!
  "A checkout of the source `repo` (owner/name or id) at `pin` holding
  `paths`, under the scenario's sources directory: ctx with :sources-dir,
  :source and :pin."
  [ctx repo pin paths]
  (k/needs-knowledge!)
  (let [idx (k/read-edn s/room-root k/index-rel)
        id (k/source-id idx repo)
        sdir (k/sources-dir! ctx)]
    (s/check! (some #{pin} (get-in idx [(keyword id) :pins])) (str id " is not pinned at " pin))
    (k/checkout! sdir id pin paths)
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defn- statuses
  "The statuses the run reported for `citation`."
  [run citation]
  (set (map :status (k/citations-of run citation))))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (room ctx)
          dir (:dir ctx)]
      (s/write! dir "dhf/raw/unpinned.md" "# example/unpinned\n\nA source with no pin yet.\n")
      (k/update-index! dir #(assoc % :unpinned {:repository "https://example.org/example/unpinned"
                                                :record "unpinned.md" :pins [] :fenced []}))
      (assoc ctx :source "unpinned"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (str k/index-rel))
                             (re-find (word-re (:source ctx)) %)
                             (re-find #"(?i)pin" %))
                   (str "in " k/index-rel " naming " (:source ctx) " and its missing pin"))))

(defstep #"a commit of the sources index pins (\S+) at (\S+), and a later commit no longer lists it"
  (fn [ctx id pin]
    (let [ctx (git-room! ctx)
          dir (:dir ctx)]
      (s/check! (some #{pin} (get-in (k/index dir) [(keyword id) :pins])) (str id " is not pinned at " pin))
      (let [c (commit-index! dir #(assoc-in % [(keyword id) :pins] ["a1b2c3d"])
                             (str "re-pin " id ", dropping " pin))]
        (assoc ctx :source id :pin pin :dropped-by c)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (k/fails-with! ctx #(and (re-find (word-re (:source ctx)) %)
                             (str/includes? % (:pin ctx))
                             (str/includes? % (subs (:dropped-by ctx) 0 7)))
                   (str "naming " (:source ctx) ", " (:pin ctx) " and " (subs (:dropped-by ctx) 0 7)))))

(defstep #"a commit of the sources index lists (\S+) with its pins, and a later commit keeps them and adds :retired \"([^\"]+)\""
  (fn [ctx id reason]
    (let [ctx (room ctx)
          dir (:dir ctx)
          k* (keyword id)
          pins (get-in (k/index dir) [k* :pins])]
      (s/check! (seq pins) (str id " has no pins in the room's index"))
      (k/update-index! dir #(update % k* dissoc :retired))
      (let [ctx (git-room! ctx)]
        (commit-index! dir #(assoc-in % [k* :retired] reason) (str "retire " id))
        (s/check! (= pins (get-in (k/index dir) [k* :pins])) "the pins changed")
        (assoc ctx :source id)))))

(defstep #"the check reports no dropped pin for (\S+)"
  (fn [ctx id]
    (let [r (:run ctx)]
      (s/check! r "no command was run")
      (s/check! (not-any? #(and (re-find (word-re id) %) (re-find #"(?i)drop" %)) (s/err-lines r))
                (str "a dropped pin is reported for " id "\n" (s/show r))))))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/includes? % (:citation ctx)) (re-find #"(?i)retired" %))
                   (str "naming " (:citation ctx) " and that its source is retired"))))

(defstep #"the need (UN-\d+) whose sources hold \"([^\"]+)\""
  (fn [ctx need text]
    (let [ctx (room ctx)]
      (k/write-needs! (:dir ctx) (assoc-in (k/read-edn (:dir ctx) k/needs-rel) [need :sources] [text]))
      (assoc ctx :need need :source-text text))))

(defstep #"it fails rule (\S+) and names (UN-\d+) and the source"
  (fn [ctx rule need]
    (k/fails-with! ctx #(and (str/starts-with? % (str k/needs-rel ":"))
                             (str/includes? % (str ": " rule ": "))
                             (str/includes? % need)
                             (str/includes? % (:source-text ctx)))
                   (str rule " naming " need " and \"" (:source-text ctx) "\""))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (let [ctx (room ctx)
          idx (k/index (:dir ctx))
          sdir (k/sources-dir! ctx)]
      (doseq [id [a b]]
        (k/checkout! sdir id (first (get-in idx [(keyword id) :pins])) (k/cited-paths id)))
      (assoc ctx :sources-dir sdir :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cs (:citations (k/result r))]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (doseq [id [a b]
              :let [mine (filter #(= id (:source %)) cs)]]
        (s/check! (seq mine) (str "no citation of " id " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "a citation of " id " was not checked against its checkout: "
                       (pr-str (remove #(= :resolved (:status %)) mine))))))))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation id]
    (let [ctx (cite-in-room! ctx "UN-011" citation)]
      (s/check! (not (contains? (k/index (:dir ctx)) (keyword id))) (str id " is a recorded source"))
      (assoc ctx :source id))))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx id]
    (cited-at! ctx #(re-find (word-re id) (k/without % (:citation ctx))) (str "and " id))))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx id pin]
    (let [ctx (room ctx)]
      (s/check! (= [pin] (get-in (k/index (:dir ctx)) [(keyword id) :pins])) (str id " is not pinned only at " pin))
      (assoc ctx :source id :pins [pin]))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cite-in-room! ctx "UN-011" citation)))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (second (re-find #"@(\S+)$" (:citation ctx)))]
      (cited-at! ctx #(let [rest* (k/without % (:citation ctx))]
                        (and (str/includes? rest* commit)
                             (every? (fn [p] (str/includes? rest* p)) (:pins ctx))))
                 (str "and " commit " and the pins " (:pins ctx))))))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (checkout-of! ctx repo pin [path])))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (checkout-of! ctx repo pin (remove #{path} ["README.md"]))))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin]
    (let [id (k/source-id (k/read-edn s/room-root k/index-rel) repo)]
      (checkout-of! ctx repo pin (k/cited-paths id)))))

(defstep #"no checkout of (\S+) is given(?: with --sources)?"
  (fn [ctx repo]
    (s/check! (nil? (:sources-dir ctx)) "a sources directory was given")
    (assoc ctx :source (k/source-id (k/read-edn s/room-root k/index-rel) repo))))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [ctx (cite-in-room! ctx "UN-011" citation)]
      (k/run-check ctx line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= #{:resolved} (statuses r (:citation ctx)))
                (str (:citation ctx) " did not resolve\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str (:citation ctx) " is named on the error stream\n" (s/show r))))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path pin] (re-find #":([^@]+)@(\S+)$" (:citation ctx))]
      (s/check! (= #{:unresolved} (statuses (:run ctx) (:citation ctx))) "the citation is not reported unresolved")
      (cited-at! ctx #(let [rest* (k/without % (:citation ctx))]
                        (and (str/includes? rest* path) (str/includes? rest* pin)))
                 (str "and " path " and " pin)))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          warn (first (filter #(and (str/includes? % (:citation ctx)) (str/starts-with? % (:where ctx)))
                              (k/warning-lines r)))]
      (s/check! (= #{:unchecked} (statuses r (:citation ctx)))
                (str (:citation ctx) " is not reported unchecked\n" (s/show r)))
      (s/check! (every? #(seq (:reasons %)) (k/citations-of r (:citation ctx))) "no reason is given")
      (s/check! warn (str "no warning names " (:citation ctx) "\n" (s/show r)))
      (s/check! (re-find #"(?i)no checkout" warn) (str "the warning gives no reason: " warn))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (k/finding-lines r))
                (str (:citation ctx) " is a finding\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (room ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx]
    (cited-at! ctx #(and (str/includes? (k/without % (:citation ctx)) (:fenced ctx))
                         (re-find #"(?i)fenced" %))
               (str "and the fenced " (:fenced ctx)))))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % k/index-rel)
                             (re-find (word-re (:source ctx)) %)
                             (str/includes? % (:pin ctx))
                             (str/includes? % (:fenced ctx)))
                   (str "naming " (:source ctx) ", " (:pin ctx) " and " (:fenced ctx)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (room ctx)
          dir (:dir ctx)
          rel "dhf/spec/knowledge/sources.feature"
          citation "loyly:harness/fence.sh@17d68d2"
          text (slurp (str (fs/file dir rel)))
          changed (str/replace-first text #"(\n\s+Then the check fails and names the source\n)"
                                     (str "$1      And the example quotes \"" citation "\"\n"))]
      (s/check! (not= text changed) (str "no step to follow in " rel))
      (s/check! (some #{"harness/fence.sh"} (get-in (k/index dir) [:loyly :fenced])) "harness/fence.sh is not fenced")
      (spit (str (fs/file dir rel)) changed)
      (assoc ctx :citation citation))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (empty? (k/citations-of r (:citation ctx))) (str (:citation ctx) " was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str (:citation ctx) " is named\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source (\S+), pinned at the commit of a checkout given with --sources"
  (fn [ctx id]
    (let [ctx (room ctx)
          dir (:dir ctx)
          sdir (k/sources-dir! ctx)
          co (str (fs/file sdir id))]
      (s/write! co "README.md" "A demonstration source.\n")
      (k/git! co "init" "-q")
      (k/git! co "add" "-A")
      (k/git! co "commit" "-q" "-m" "a demonstration source")
      (let [pin (subs (k/git! co "rev-parse" "HEAD") 0 7)]
        (s/write! dir (str "dhf/raw/" id ".md") (str "# example/" id "\n\nA demonstration source.\n"))
        (k/update-index! dir #(assoc % (keyword id) {:repository (str "https://example.org/example/" id)
                                                     :record (str id ".md") :pins [pin] :fenced []}))
        (assoc ctx :sources-dir sdir :source id :pin pin)))))

(defstep #"the need (UN-\d+) citing \"([^\"@]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx need cited]
    (cite-in-room! ctx need (str cited "@" (:pin ctx)))))

(defstep #"it fails and names (UN-\d+), the citation and why it did not resolve"
  (fn [ctx need]
    (let [path (second (re-find #":([^@]+)@" (:citation ctx)))]
      (cited-at! ctx #(let [rest* (k/without % (:citation ctx))]
                        (and (str/includes? rest* need)
                             (str/includes? rest* path)
                             (re-find #"(?i)not|no " rest*)))
                 (str "and " need " and why")))))

(defstep #"the need (UN-\d+) citing \"([^\"]+@[^\"]+)\""
  (fn [ctx need citation]
    (cite-in-room! ctx need citation)))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (:run ctx)
          warn (first (filter #(str/includes? % (:citation ctx)) (k/warning-lines r)))]
      (s/check! warn (str "no warning names " (:citation ctx) "\n" (s/show r)))
      (s/check! (re-find #"(?i)not checked|unchecked" warn) (str "the warning does not say it was not checked: " warn))
      (s/check! (re-find #"(?i)no checkout|--sources" warn) (str "the warning does not say why: " warn)))))
