(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature, in a copy of the
  room's allow-listed paths (a fixture), whose sources index, needs and wiki
  the scenario changes, with local git checkouts of a source as fixtures.

  The shared When (\"rdm-spec LINE\" is run, ... with that directory as DIR)
  and \"it passes\" are the kernel's (steps.kernel-command). \"a wiki page
  whose footnote cites C\" is the site's (a fresh copy of the room holding the
  page), so a Given before it that needs the sources index's history records
  that history in a repository of its own and points the scenario's git at it
  (GIT_DIR), which survives the fresh copy.

  Context keys: :dir the project, :run the last run, :sources-dir the
  --sources directory, :source the source a Given named, :citation and
  :citation-line the citation a Given added (its line in needs.edn), :record
  the record file a Given named, :dropped {:source :pin :commit}."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- room [ctx] (k/ensure-room! ctx))

(defn- add-source!
  "Adds the source `id` to the project's index, with its record file written
  unless `record?` is false."
  [dir id entry record?]
  (k/update-index! dir #(assoc % (keyword id) entry))
  (when (and record? (:record entry))
    (s/write! dir (str "dhf/raw/" (:record entry)) (str "# " id "\n\nA fixture source.\n"))))

(defn- line-naming
  "The first finding line of the last run holding every one of `parts`."
  [ctx & parts]
  (k/fails-with! ctx (fn [l] (every? #(str/includes? l (str %)) parts))
                 (str "names " (str/join ", " parts))))

(defn- cite-in-need!
  "Puts `citation` into UN-011's sources; ctx with :citation and its line."
  [ctx citation]
  (let [ctx (room ctx)
        line (k/cite! (:dir ctx) "UN-011" citation)]
    (assoc ctx :citation citation :citation-line line)))

(defn- where-cited [ctx] (str k/needs-rel ":" (:citation-line ctx) ":"))

(defn- set-need-sources! [dir need sources]
  (k/write-needs! dir (assoc-in (k/read-edn dir k/needs-rel) [need :sources] sources)))

(defn- git-history!
  "Commits each of `indexes` in turn as dhf/raw/sources.edn of a new
  repository in `dir`; returns the commits, oldest first."
  [dir indexes]
  (k/git! dir "init" "-q")
  (vec (for [[i idx] (map-indexed vector indexes)]
         (do (spit (str (fs/file dir k/index-rel)) (pr-str idx))
             (k/git! dir "add" "-A")
             (k/git! dir "commit" "-q" "-m" (str "the sources index, commit " (inc i)))
             (k/git! dir "rev-parse" "HEAD")))))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [ctx (room ctx)]
      (add-source! (:dir ctx) "demo" {:repository "https://example.org/demo" :record "demo.md" :pins [] :fenced []} true)
      (assoc ctx :source "demo"))))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx]
    (let [ctx (room ctx)]
      (add-source! (:dir ctx) "demo" {:record "demo.md" :pins ["abc1234"] :fenced []} true)
      (assoc ctx :source "demo"))))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx]
    (let [ctx (room ctx)]
      (add-source! (:dir ctx) "demo" {:repository "https://example.org/demo" :record "demo.md"
                                      :pins ["abc1234"] :fenced []} false)
      (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/raw/demo.md"))) "the record exists")
      (assoc ctx :source "demo" :record "demo.md"))))

(defstep #"the check fails and names the source"
  (fn [ctx]
    (line-naming ctx k/index-rel (:source ctx))
    nil))

(defstep #"it fails rule source-no-repository and names the source"
  (fn [ctx]
    (line-naming ctx k/index-rel "source-no-repository" (:source ctx))
    nil))

(defstep #"it fails rule source-no-record and names the source and the record file"
  (fn [ctx]
    (line-naming ctx k/index-rel "source-no-record" (:source ctx) (:record ctx))
    nil))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit no longer lists it"
  (fn [ctx]
    (let [ctx (room ctx)
          dir (:dir ctx)
          idx (k/index dir)]
      (s/check! (= ["17d68d2"] (get-in idx [:loyly :pins])) "the room's index does not pin loyly at 17d68d2")
      (let [[_ dropping] (git-history! dir [idx (dissoc idx :loyly)])]
        (assoc ctx :dropped {:source "loyly" :pin "17d68d2" :commit dropping})))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (let [{:keys [source pin commit]} (:dropped ctx)]
      (k/fails-with! ctx #(and (str/includes? % source) (str/includes? % pin)
                               (str/includes? % (subs commit 0 7)))
                     (str "names " source ", " pin " and " commit))
      nil)))

(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"([^\"]+)\""
  (fn [ctx reason]
    ;; The history lives in a repository of its own, which the scenario's git
    ;; reads through GIT_DIR: the next Given (the site's) makes a fresh copy.
    (let [repo (s/tmp-dir)
          idx (k/index s/room-root)
          kept (get-in idx [:rdm :pins])]
      (s/check! (seq kept) "the room's index pins rdm nowhere")
      (fs/create-dirs (fs/file repo "dhf/raw"))
      (git-history! repo [(update idx :rdm dissoc :retired) (assoc-in idx [:rdm :retired] reason)])
      (-> ctx
          (assoc :history repo)
          (assoc-in [:env "GIT_DIR"] (str (fs/file repo ".git")))))))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (not-any? #(str/includes? % "history") (k/warning-lines r))
                (str "the sources index's history was not read\n" (s/show r)))
      (s/check! (not-any? #(and (str/includes? % "source-pin-dropped") (re-find #"\brdm\b" %)) (s/err-lines r))
                (str "a dropped pin is reported for rdm\n" (s/show r)))
      nil)))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx]
    (line-naming ctx (:citation ctx) "retired")
    nil))

(defstep #"the need UN-004 whose sources hold \"([^\"]+)\""
  (fn [ctx text]
    (let [ctx (room ctx)]
      (set-need-sources! (:dir ctx) "UN-004" [text])
      (assoc ctx :citation text))))

(defstep #"it fails rule citation-malformed and names UN-004 and the source"
  (fn [ctx]
    (line-naming ctx "citation-malformed" "UN-004" (:citation ctx))
    nil))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defn- faithful-checkout!
  "A checkout of the room's source `id` at its pin, holding what the room's
  needs and wiki cite of it and its fenced paths, minus `without`."
  [ctx id & without]
  (let [sdir (k/sources-dir! ctx)
        pin (first (get-in (k/index s/room-root) [(keyword id) :pins]))]
    (k/checkout! sdir id pin (remove (set without) (k/cited-paths id)))
    (assoc ctx :sources-dir sdir :source id :pin pin)))

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [ctx]
    (k/needs-knowledge!)
    (-> ctx (faithful-checkout! "loyly") (faithful-checkout! "innolitics-rdm"))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          cs (:citations (k/result r))]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (doseq [src ["loyly" "innolitics-rdm"]
              :let [mine (filter #(= src (:source %)) cs)]]
        (s/check! (seq mine) (str "no citation of " src " was reported\n" (s/show r)))
        (s/check! (every? #(= :resolved (:status %)) mine)
                  (str "a citation of " src " was not resolved against its checkout: "
                       (pr-str (remove #(= :resolved (:status %)) mine)))))
      nil)))

(defstep #"a citation \"([^\"]+)\" and no source (\S+)"
  (fn [ctx citation source]
    (let [ctx (cite-in-need! ctx citation)]
      (s/check! (not (contains? (k/index (:dir ctx)) (keyword source))) (str "the index records " source))
      (assoc ctx :source source))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx citation] (cite-in-need! ctx citation)))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx source]
    (k/fails-with! ctx #(and (str/starts-with? % (where-cited ctx)) (str/includes? % source))
                   (str "at " (where-cited ctx) " names " source))
    nil))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [ctx]
    (let [ctx (room ctx)]
      (k/update-index! (:dir ctx) #(assoc-in % [:loyly :pins] ["17d68d2"]))
      (assoc ctx :source "loyly" :pins ["17d68d2"]))))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (k/fails-with! ctx #(and (str/starts-with? % (where-cited ctx)) (str/includes? % commit)
                               (every? (fn [p] (str/includes? % p)) (:pins ctx)))
                     (str "names " commit " and the pins " (:pins ctx))))
    nil))

(defstep #"a checkout of skallinen/loyly, holding README\.md at 17d68d2, given with --sources"
  (fn [ctx] (faithful-checkout! ctx "loyly")))

(defstep #"a checkout of skallinen/loyly, with no file (\S+) at 17d68d2, given with --sources"
  (fn [ctx path] (assoc (faithful-checkout! ctx "loyly" path) :missing-path path)))

(defstep #"no checkout of skallinen/loyly is given with --sources"
  (fn [ctx] (dissoc ctx :sources-dir)))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [ctx (cite-in-need! ctx citation)]
      (kc/run-line ctx line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str (:citation ctx) " was not reported\n" (s/show r)))
      (s/check! (every? #(= :resolved (:status %)) cs) (str "it did not resolve: " (pr-str cs)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (s/err-lines r))
                (str "a finding or warning names it\n" (s/show r)))
      nil)))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (where-cited ctx)) (str/includes? % (:missing-path ctx))
                             (str/includes? % (:pin ctx)))
                   (str "names " (:missing-path ctx) " and " (:pin ctx)))
    nil))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          cs (k/citations-of r (:citation ctx))]
      (s/check! (seq cs) (str (:citation ctx) " was not reported\n" (s/show r)))
      (s/check! (every? #(and (= :unchecked (:status %)) (seq (:reasons %))) cs)
                (str "not unchecked with a reason: " (pr-str cs)))
      (s/check! (some #(and (str/includes? % (:citation ctx)) (re-find #"not checked|unchecked" %))
                      (k/warning-lines r))
                (str "no warning says it is unchecked\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:citation ctx)) (k/finding-lines r))
                (str "it is reported as a finding\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path id]
    (let [ctx (room ctx)]
      (k/update-index! (:dir ctx) #(update-in % [(keyword id) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :fenced path :source id))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/starts-with? % (where-cited ctx)) (str/includes? % (:citation ctx))
                             (str/includes? (k/without % (:citation ctx)) (:fenced ctx)))
                   (str "at " (where-cited ctx) " names the fenced " (:fenced ctx)))
    nil))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources"
  (fn [ctx] (faithful-checkout! ctx "loyly")))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (line-naming ctx (:source ctx) (:pin ctx) (:fenced ctx))
    nil))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(defstep #"a feature file whose example step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (room ctx)
          dir (:dir ctx)
          idx (k/index dir)
          fenced? (fn [[_ src path]]
                    (some #(or (= % ".") (= % path) (str/starts-with? path (str % "/")))
                          (get-in idx [(keyword src) :fenced])))
          hit (first (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
                           l (str/split-lines (slurp f))
                           :when (re-find #"^\s*(Given|When|Then|And|But)\s" l)
                           m (re-seq k/citation-re l)
                           :when (fenced? m)]
                       [(str (fs/relativize dir f)) (first m)]))]
      (s/check! hit "no feature file's step quotes a citation of a fenced path")
      (assoc ctx :quoted (second hit)))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not-any? #(= (:quoted ctx) (:citation %)) (:citations (k/result r)))
                (str (:quoted ctx) " was resolved\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % (:quoted ctx)) (s/err-lines r))
                (str (:quoted ctx) " is named\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx]
    (let [ctx (room ctx)
          sdir (k/sources-dir! ctx)
          co (str (fs/file sdir "demo"))]
      (s/write! co "README.md" "A fixture source.\n")
      (k/git! co "init" "-q")
      (k/git! co "add" "-A")
      (k/git! co "commit" "-q" "-m" "a fixture checkout of demo")
      (let [pin (k/git! co "rev-parse" "--short=7" "HEAD")]
        (add-source! (:dir ctx) "demo" {:repository "https://example.org/demo" :record "demo.md"
                                        :pins [pin] :fenced []} true)
        (assoc ctx :sources-dir sdir :source "demo" :pin pin)))))

(defstep #"the need UN-004 citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx cited]
    (let [citation (str cited "@" (:pin ctx))]
      (set-need-sources! (:dir ctx) "UN-004" [citation])
      (assoc ctx :citation citation))))

(defstep #"it fails and names UN-004, the citation and why it did not resolve"
  (fn [ctx]
    (k/fails-with! ctx #(and (str/includes? % "UN-004") (str/includes? % (:citation ctx))
                             (re-find #"(?i)\bno\b|\bnot\b" (k/without % (:citation ctx))))
                   (str "names UN-004, " (:citation ctx) " and why"))
    nil))

(defstep #"the need UN-004 citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [ctx (room ctx)]
      (set-need-sources! (:dir ctx) "UN-004" [citation])
      (assoc ctx :citation citation))))

(defstep #"no checkout of loyly is given"
  (fn [ctx]
    (s/check! (not (:sources-dir ctx)) "a --sources directory was given")
    ctx))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (some #(and (str/includes? % (:citation ctx)) (str/includes? % "UN-004")
                            (re-find #"not checked|unchecked" %)
                            (re-find #"checkout|--sources" %))
                      (k/warning-lines r))
                (str "no warning says " (:citation ctx) " was not checked, and why\n" (s/show r)))
      nil)))
