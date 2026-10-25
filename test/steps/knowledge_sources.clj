(ns steps.knowledge-sources
  "Step definitions for dhf/spec/knowledge/sources.feature: `rdm-spec check`
  on a copy of the room or a small project, its sources index, needs and
  checkouts set up by the steps (steps.knowledge-support).

  The shared steps (\"rdm-spec LINE\" is run, it passes, a wiki page whose
  footnote cites C) are the kernel's (steps.kernel-command).

  Context keys set here: :dir the project; :sources-dir the directory of
  checkouts given as DIR; :source the source a step added; :record its
  record file; :pin and :dropped-by (spec-0121); :history the sources
  index's two versions (spec-0236); :cited-at {:file :line} where a
  citation was written; :citation the citation; :need the need; :fenced the
  fenced path; :quoted the citation a feature file quotes; :run the run."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.knowledge-support :as k]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (let [dir (k/project ctx)]
      (k/update-sources! dir #(assoc % :unpinned {:repository "https://example.invalid/unpinned"
                                                  :record "unpinned.md" :pins [] :fenced []}))
      (s/write! dir "dhf/raw/unpinned.md" "# unpinned\n")
      (assoc ctx :dir dir :source "unpinned"))))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx]
    (let [dir (k/project ctx)]
      (k/update-sources! dir #(assoc % :nowhere {:record "nowhere.md" :pins ["abc1234"] :fenced []}))
      (s/write! dir "dhf/raw/nowhere.md" "# nowhere\n")
      (assoc ctx :dir dir :source "nowhere"))))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx]
    (let [dir (k/project ctx)]
      (k/update-sources! dir #(assoc % :unrecorded {:repository "https://example.invalid/unrecorded"
                                                    :record "unrecorded.md" :pins ["abc1234"] :fenced []}))
      (assoc ctx :dir dir :source "unrecorded" :record "unrecorded.md"))))

(defstep #"the check fails and names the source"
  (fn [ctx] (k/fails-naming! ctx (:source ctx)) nil))

(defstep #"it fails rule (source-no-repository|source-no-record|citation-malformed) and names (.+)"
  (fn [ctx rule what]
    (let [parts (case what
                  "the source" [(:source ctx)]
                  "the source and the record file" [(:source ctx) (:record ctx)]
                  "UN-004 and the source" ["UN-004" (:citation ctx)])]
      (apply k/fails-rule-naming! ctx rule parts)
      nil)))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit keeps loyly with that pin replaced by another"
  (fn [ctx]
    (let [dir (k/project ctx)]
      (k/commit! dir "loyly pinned at 17d68d2")
      (k/update-sources! dir #(assoc-in % [:loyly :pins] ["abcdef0"]))
      (let [h (k/commit! dir "loyly's pin replaced")]
        (assoc ctx :dir dir :source "loyly" :pin "17d68d2" :dropped-by h)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx]
    (k/fails-naming! ctx (:source ctx) (:pin ctx) (subs (:dropped-by ctx) 0 7))
    nil))

;; spec-0236. The kernel's "a wiki page whose footnote cites C" that follows
;; makes a new copy of the room, so the history is written again into
;; whatever project the scenario ends with (retired!), before the Then reads it.
(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"([^\"]+)\""
  (fn [ctx reason]
    (assoc ctx :retired-reason reason :source "rdm")))

(defn- retired!
  "Writes into the scenario's project a commit listing rdm with its pins and
  a later one adding :retired, then runs the check again: ctx with :run."
  [ctx]
  (let [dir (:dir ctx)
        current (k/read-edn dir (k/sources-rel dir))
        rdm (get current :rdm)]
    (s/check! (seq (:pins rdm)) "the project's sources index holds no rdm with pins")
    (when-not (:history-written ctx)
      (k/write-edn! dir (k/sources-rel dir) (assoc current :rdm (dissoc rdm :retired)))
      (k/commit! dir "rdm listed with its pins")
      (k/write-edn! dir (k/sources-rel dir) (assoc current :rdm (assoc rdm :retired (:retired-reason ctx))))
      (k/commit! dir "rdm retired"))
    (assoc ctx :history-written true :run (kc/run! ctx dir ["check"]))))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx]
    (let [ctx (retired! ctx)
          r (:run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (not (k/line-naming (s/err-lines r) [": source-pin-dropped: " "rdm"]))
                (str "a dropped pin of rdm is reported\n" (s/show r)))
      ctx)))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx]
    (k/fails-naming! ctx (:citation ctx) "retired")
    nil))

(defstep #"the need UN-004 whose sources hold \"([^\"]+)\""
  (fn [ctx text]
    (let [dir (k/project ctx)]
      (k/update-needs! dir #(assoc-in % ["UN-004" :sources] [text]))
      (assoc ctx :dir dir :need "UN-004" :citation text))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [ctx]
    (let [sd (s/tmp-dir)
          _ (k/checkout! sd "loyly" {"README.md" "# loyly\n"} "17d68d2")
          _ (k/checkout! sd "innolitics-rdm" {"README.md" "# rdm\n"} "f21b508")
          dir (k/small-project! ctx ["loyly:README.md@17d68d2" "innolitics-rdm:docs/absent.md@f21b508"
                                     "agent-skills:README.md@da65901"])]
      (k/update-sources! dir #(assoc %
                                     :innolitics-rdm {:repository "https://github.com/innolitics/rdm"
                                                      :record "innolitics-rdm.md" :pins ["f21b508"] :fenced []}
                                     :agent-skills {:repository "https://github.com/scope-impact/agent-skills"
                                                    :record "agent-skills.md" :pins ["da65901"] :fenced []}))
      (s/write! dir "dhf/raw/innolitics-rdm.md" "# innolitics-rdm\n")
      (s/write! dir "dhf/raw/agent-skills.md" "# agent-skills\n")
      (assoc ctx :dir dir :sources-dir sd))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          lines (s/err-lines r)]
      ;; loyly's README is at its pin in the checkout: it resolves, so nothing names it
      (s/check! (not (k/line-naming lines ["loyly:README.md@17d68d2"]))
                (str "the resolving citation of loyly is reported\n" (s/show r)))
      ;; innolitics-rdm's checkout does not hold docs/absent.md: checked, it fails
      (k/fails-naming! ctx "innolitics-rdm:docs/absent.md@f21b508" "docs/absent.md" "f21b508")
      ;; agent-skills has no checkout in the directory: unchecked, said in a warning
      (s/check! (k/line-naming (k/warnings ctx) ["agent-skills:README.md@da65901"])
                (str "the citation of a source with no checkout is not reported unchecked\n" (s/show r)))
      nil)))

(defstep #"a citation \"([^\"]+)\"(?: and no source (\S+))?"
  (fn [ctx citation absent]
    (let [dir (k/project ctx)]
      (when absent
        (s/check! (not (contains? (k/read-edn dir (k/sources-rel dir)) (keyword absent)))
                  (str "the sources index records " absent)))
      (assoc ctx :dir dir :citation citation :cited-at (k/cite-in-need! dir "UN-004" citation)))))

(defn- where [ctx]
  (let [{:keys [file line]} (:cited-at ctx)]
    (str file ":" line ":")))

(defstep #"the citation fails and names (\S+) and where it was cited"
  (fn [ctx source]
    (k/fails-naming! ctx (where ctx) source)
    nil))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [ctx]
    (let [dir (k/project ctx)]
      (k/update-sources! dir #(assoc-in % [:loyly :pins] ["17d68d2"]))
      (assoc ctx :dir dir))))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx]
    (let [commit (last (str/split (:citation ctx) #"@"))]
      (k/fails-naming! ctx (where ctx) commit "17d68d2")
      nil)))

(defstep #"a checkout of skallinen/loyly(?:,| at 17d68d2,) (holding README\.md at 17d68d2, given with --sources|with no file docs/plan\.md at 17d68d2, given with --sources|given with --sources|given with --sources, with no docs/plan\.md)"
  (fn [ctx _]
    (let [sd (s/tmp-dir)]
      (k/checkout! sd "loyly" {"README.md" "# loyly\n" "harness/README.md" "# the harness\n"} "17d68d2")
      (assoc ctx :sources-dir sd))))

(defstep #"\"rdm-spec ([^\"]*)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line citation]
    (let [dir (or (:dir ctx) (k/small-project! ctx [citation]))
          ctx (assoc ctx :dir dir :citation citation)]
      (kc/run-line ctx line))))

(defstep #"it resolves"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not (k/line-naming (s/err-lines r) [(:citation ctx)]))
                (str "the citation is reported, as a finding or a warning\n" (s/show r)))
      nil)))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx]
    (let [[_ path pin] (re-matches #"[^:]+:(.+)@(\S+)" (:citation ctx))]
      (k/fails-naming! ctx (:citation ctx) path pin)
      nil)))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (dissoc ctx :sources-dir)))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          l (k/line-naming (k/warnings ctx) [(:citation ctx) ": warning: citation-unchecked: "])]
      (s/check! l (str "no warning says the citation is unchecked\n" (s/show r)))
      (s/check! (str/includes? l "--sources") (str "the warning gives no reason (no --sources)\n" l))
      nil)))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path source]
    (let [dir (k/project ctx)]
      (k/update-sources! dir #(update-in % [(keyword source) :fenced] (fn [f] (vec (distinct (conj (vec f) path))))))
      (assoc ctx :dir dir :fenced path :source source))))

(defstep #"the citation fails and names the fenced (?:path|directory) and where it was cited"
  (fn [ctx]
    (k/fails-rule-naming! ctx "citation-fenced" (where ctx) (str "\"" (:fenced ctx) "\""))
    nil))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx]
    (k/fails-naming! ctx (:source ctx) "17d68d2" (:fenced ctx))
    nil))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [ctx]
    (let [dir (k/project ctx)
          f (fs/file dir "dhf/spec/knowledge/wiki.feature")
          text (slurp (str f))
          quoted "loyly:harness/fence.sh@17d68d2"
          anchor "      Given a wiki page whose every footnote cites a resolving source\n"
          changed (str/replace-first text anchor
                                     (str anchor "      And a note quoting \"" quoted "\"\n"))]
      (s/check! (not= text changed) "the wiki feature holds no step to quote beside")
      (s/check! (some #{"harness/fence.sh"} (get-in (k/read-edn dir (k/sources-rel dir)) [:loyly :fenced]))
                "loyly does not fence harness/fence.sh")
      (spit (str f) changed)
      (assoc ctx :dir dir :quoted quoted))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not (k/line-naming (s/err-lines r) [(:quoted ctx)]))
                (str "the quoted citation is reported\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx]
    (let [sd (s/tmp-dir)
          h (k/checkout! sd "demo" {"README.md" "# demo\n"} nil)
          pin (subs h 0 7)
          dir (k/project ctx)]
      (k/update-sources! dir #(assoc % :demo {:repository "https://example.invalid/demo" :record "demo.md"
                                              :pins [pin] :fenced []}))
      (s/write! dir "dhf/raw/demo.md" "# demo\n")
      (assoc ctx :dir dir :sources-dir sd :pin pin))))

(defstep #"the need UN-004 citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx cited]
    (let [citation (str cited "@" (:pin ctx))]
      (k/update-needs! (:dir ctx) #(assoc-in % ["UN-004" :sources] [citation]))
      (assoc ctx :need "UN-004" :citation citation))))

(defstep #"it fails and names UN-004, the citation and why it did not resolve"
  (fn [ctx]
    (let [l (k/fails-naming! ctx "UN-004" (:citation ctx) "docs/nothing.md")]
      (s/check! (re-find #"(?i)does not (exist|hold)|not at|no file" l)
                (str "the finding does not say why the citation did not resolve: " l))
      nil)))

(defstep #"the need UN-004 citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (k/project ctx)]
      (k/update-needs! dir #(assoc-in % ["UN-004" :sources] [citation]))
      (assoc ctx :dir dir :need "UN-004" :citation citation))))

(defstep #"no checkout of loyly is given"
  (fn [ctx] (dissoc ctx :sources-dir)))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          l (k/line-naming (k/warnings ctx) [(:citation ctx) "citation-unchecked" "UN-004"])]
      (s/check! l (str "no warning names the unchecked citation of UN-004\n" (s/show r)))
      (s/check! (re-find #"no checkout|--sources" l) (str "the warning does not say why: " l))
      nil)))
