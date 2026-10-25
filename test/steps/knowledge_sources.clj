(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's spec, sources index, needs and wiki, changed by the scenario, with
  local git checkouts of a source where the scenario gives --sources.

  Shared steps used here and defined elsewhere: the kernel's \"rdm-spec ...\"
  runs, \"a wiki page whose footnote cites C\" (the site's: a fresh copy of the
  room whose wiki holds a page citing C) and \"it passes\".

  Context keys: :dir the project, :run the last run, :sources-dir the
  --sources directory, :source a source id, :pin, :citation, :cite-line (the
  line of needs.edn holding it), :need, :drop-commit."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

(def ^:private room-index (delay (k/index s/room-root)))

(defn- index-of [ctx] (k/index (:dir ctx)))

(defn- fails! [ctx pred what] (k/fails-with! ctx pred what))

(defn- at-citation?
  "True when `line` is a finding where the scenario's citation was cited."
  [ctx line]
  (str/starts-with? line (str k/needs-rel ":" (:cite-line ctx) ":")))

(defn- cite
  "Cites `citation` from the need `need` in the scenario's project."
  ([ctx citation] (cite ctx "UN-011" citation))
  ([ctx need citation]
   (let [ctx (k/ensure-room! ctx)]
     (assoc ctx :citation citation :need need :cite-line (k/cite! (:dir ctx) need citation)))))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc % :unpinned {:repository "https://example.org/unpinned"
                                                        :record "unpinned.md" :fenced []}))
      (assoc ctx :source "unpinned"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (fails! ctx #(and (str/starts-with? % (str k/index-rel ":")) (str/includes? % (:source ctx)))
            (str "in " k/index-rel " naming " (:source ctx)))))

(defstep #"a commit of the sources index pins (\S+) at (\S+), and a later commit no longer lists it"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)
          dir (:dir ctx)]
      (s/check! (some #{pin} (get-in (index-of ctx) [(keyword id) :pins])) (str id " is not pinned at " pin))
      (k/git! dir "init" "-q")
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the sources index pins it")
      (k/update-index! dir #(update-in % [(keyword id) :pins] (fn [ps] (vec (remove #{pin} ps)))))
      (k/git! dir "add" "-A")
      (k/git! dir "commit" "-q" "-m" "the sources index drops it")
      (assoc ctx :source id :pin pin :drop-commit (subs (k/git! dir "rev-parse" "HEAD") 0 7)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (fails! ctx #(and (str/starts-with? % (str k/index-rel ":"))
                      (str/includes? % (:source ctx))
                      (str/includes? % (:pin ctx))
                      (str/includes? % (:drop-commit ctx)))
            (str "naming " (:source ctx) ", " (:pin ctx) " and " (:drop-commit ctx)))))

(defstep #"the sources index keeps (\S+) with its pins and :retired \"([^\"]+)\""
  ;; The room's own index is the fixture: the next Given makes a fresh copy
  ;; of the room (the site's meaning of it), so the copy holds this source.
  (fn [ctx id reason]
    (k/needs-knowledge!)
    (let [src (get @room-index (keyword id))]
      (s/check! (seq (:pins src)) (str "the room's sources index keeps no pin of " id))
      (s/check! (str/includes? (str (:retired src)) reason)
                (str "the room's sources index does not retire " id " saying " reason))
      (assoc ctx :source id))))

(defstep #"the check reports no dropped pin for (\S+)"
  (fn [ctx id]
    (let [r (:run ctx)]
      (s/check! r "no command was run")
      (s/check! (not-any? #(and (str/includes? % "dropped") (str/includes? % id)) (k/finding-lines r))
                (str "a dropped pin of " id " was reported\n" (s/show r))))))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx]
    (fails! ctx #(and (str/starts-with? % (str (:page ctx) ":"))
                      (str/includes? % (:citation ctx))
                      (str/includes? (k/without % (:citation ctx)) "retired"))
            (str "in " (:page ctx) " naming " (:citation ctx) " and that its source is retired"))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defn- checkout-of!
  "A checkout of the source `id` at `pin`, holding what the room cites of it
  but `missing`, under the scenario's sources directory."
  [ctx id pin & [missing]]
  (let [sdir (k/sources-dir! ctx)]
    (s/check! (some #{pin} (get-in @room-index [(keyword id) :pins])) (str id " is not pinned at " pin))
    (k/checkout! sdir id pin (remove #{missing} (k/cited-paths id)))
    (assoc ctx :sources-dir sdir)))

(defstep #"a directory holding (\S+)/ and (\S+)/, checkouts of those sources"
  (fn [ctx a b]
    (k/needs-knowledge!)
    (let [ctx (reduce (fn [ctx id] (checkout-of! ctx id (first (get-in @room-index [(keyword id) :pins]))))
                      ctx [a b])]
      (assoc ctx :checked [a b]))))

(defstep #"citations of (\S+) and (\S+) are checked against those checkouts"
  (fn [ctx a b]
    (let [r (:run ctx)
          cs (filter #(#{a b} (:source %)) (:citations (k/result r)))]
      (s/check! r "no command was run")
      (s/check! (= #{a b} (set (map :source cs))) (str "no citations of both " a " and " b "\n" (s/show r)))
      (doseq [c cs]
        (s/check! (= :resolved (:status c)) (str (:citation c) " is " (:status c) "\n" (s/show r)))))))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation id]
    (let [ctx (cite ctx citation)]
      (s/check! (not (contains? (index-of ctx) (keyword id))) (str "the source " id " is recorded"))
      ctx)))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cite ctx citation)))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx id]
    (fails! ctx #(and (at-citation? ctx %) (str/includes? % id))
            (str "at " k/needs-rel ":" (:cite-line ctx) " naming " id))))

(defstep #"the source (\S+) is pinned only at (\S+)"
  (fn [ctx id pin]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(assoc-in % [(keyword id) :pins] [pin]))
      (assoc ctx :source id :pin pin))))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (fails! ctx #(and (at-citation? ctx %)
                        (str/includes? (k/without % (:citation ctx)) commit)
                        (str/includes? % (:pin ctx)))
              (str "naming " commit " and the pin " (:pin ctx))))))

(defstep #"a checkout of (\S+), holding (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (k/needs-knowledge!)
    (let [id (k/source-id @room-index repo)
          ctx (checkout-of! ctx id pin)]
      (s/check! (some #{path} (k/cited-paths id)) (str "the checkout holds no " path))
      ctx)))

(defstep #"a checkout of (\S+), with no file (\S+) at (\S+), given with --sources"
  (fn [ctx repo path pin]
    (k/needs-knowledge!)
    (checkout-of! ctx (k/source-id @room-index repo) pin path)))

(defstep #"a checkout of (\S+) at (\S+), given with --sources"
  (fn [ctx repo pin]
    (k/needs-knowledge!)
    (let [id (k/source-id @room-index repo)]
      (assoc (checkout-of! ctx id pin) :source id :pin pin))))

(defstep #"no checkout of (\S+) is given with --sources"
  (fn [ctx _repo] (dissoc ctx :sources-dir)))

(defstep #"no checkout of (\S+) is given"
  (fn [ctx _id] (dissoc ctx :sources-dir)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (k/run-check (cite ctx citation) line)))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (:run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str (:citation ctx) " was not read\n" (s/show r)))
      (s/check! (every? #(= :resolved (:status %)) cs) (str (:citation ctx) " does not resolve\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a finding or warning names " (:citation ctx) "\n" (s/show r))))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path pin] (re-matches #"[^:]+:(.+)@(\w+)" (:citation ctx))]
      (fails! ctx #(and (at-citation? ctx %)
                        (str/includes? (k/without % (:citation ctx)) path)
                        (str/includes? (k/without % (:citation ctx)) pin))
              (str "naming " path " and " pin)))))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (:run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str (:citation ctx) " was not read\n" (s/show r)))
      (s/check! (every? #(and (= :unchecked (:status %)) (seq (:reasons %))) cs)
                (str (:citation ctx) " is not unchecked with a reason\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:citation ctx)) (re-find #"(?i)not checked|unchecked" %))
                      (k/warning-lines r))
                (str "no warning names " (:citation ctx) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (k/ensure-room! ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defstep #"the citation fails and names the fenced (path|directory) and where it was cited"
  (fn [ctx kind]
    (fails! ctx #(and (at-citation? ctx %)
                      (str/includes? (k/without % (:citation ctx)) (:fenced ctx))
                      (str/includes? % (str "fenced " kind)))
            (str "at " k/needs-rel ":" (:cite-line ctx) " naming the fenced " kind " " (:fenced ctx)))))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (fails! ctx #(and (str/starts-with? % (str k/index-rel ":"))
                      (str/includes? % (:source ctx))
                      (str/includes? % (:pin ctx))
                      (str/includes? % (:fenced ctx)))
            (str "naming " (:source ctx) ", " (:pin ctx) " and " (:fenced ctx)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(def ^:private quoted-citation "loyly:harness/fence.sh@17d68d2")

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (k/ensure-room! ctx)
          features (map str (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature"))
          quoting (filter #(str/includes? (slurp %) (str "\"" quoted-citation "\"")) features)]
      (s/check! (some #{"harness/fence.sh"} (get-in (index-of ctx) [:loyly :fenced])) "harness/fence.sh is not fenced")
      (s/check! (seq quoting) (str "no feature file quotes " quoted-citation))
      (k/generate-views! (:dir ctx))
      (assoc ctx :citation quoted-citation))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some? (k/result r)) (str "no knowledge result\n" (s/show r)))
      (s/check! (empty? (k/citations-of r (:citation ctx))) (str (:citation ctx) " was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r)) (s/show r)))))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source (\S+), pinned at the commit of a checkout given with --sources"
  (fn [ctx id]
    (let [ctx (k/ensure-room! ctx)
          sdir (k/sources-dir! ctx)
          co (str (fs/file sdir id))]
      (s/write! co "README.md" (str "The source " id ".\n"))
      (k/git! co "init" "-q")
      (k/git! co "add" "-A")
      (k/git! co "commit" "-q" "-m" (str "a fixture checkout of " id))
      (let [pin (subs (k/git! co "rev-parse" "HEAD") 0 7)]
        (k/update-index! (:dir ctx) #(assoc % (keyword id) {:repository (str "https://example.org/" id)
                                                            :record (str id ".md") :pins [pin] :fenced []}))
        (assoc ctx :sources-dir sdir :source id :pin pin)))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx need citation]
    (s/check! (not (fs/exists? (fs/file (:sources-dir ctx) (:source ctx) (str/replace citation #"^[^:]+:" ""))))
              (str "the checkout holds " citation))
    (cite ctx need (str citation "@" (:pin ctx)))))

(defstep #"it fails and names (UN-\d+), the citation and why it did not resolve"
  (fn [ctx need]
    (fails! ctx #(and (at-citation? ctx %)
                      (str/includes? % need)
                      (str/includes? % (:citation ctx))
                      (re-find #"(?i)not|no " (k/without % (:citation ctx))))
            (str "naming " need ", " (:citation ctx) " and why"))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\""
  (fn [ctx need citation] (cite ctx need citation)))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (:run ctx)
          w (first (filter #(and (str/includes? % (:citation ctx))
                                 (str/includes? % (:need ctx))
                                 (re-find #"(?i)not checked" %))
                           (k/warning-lines r)))]
      (s/check! w (str "no warning that " (:citation ctx) " was not checked\n" (s/show r)))
      (s/check! (re-find #"(?i)no checkout|--sources" (k/without w (:citation ctx)))
                (str "the warning says no why: " w)))))
