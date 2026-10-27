(ns steps.knowledge-sources
  "Step definitions of dhf/spec/knowledge/sources.feature, and the helpers the
  wiki's step file uses: checkouts given with --sources, and sources written
  into the fixture's index.

  A pin a step writes (17d68d2, DEMOPIN) stands for the commit a checkout the
  scenario made holds: the kernel's :pins maps it (k/real-pin)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def index "dhf/raw/sources.edn")

(defn- sources [ctx] (edn/read-string (slurp (fs/file (k/guard! (:dir ctx)) index))))

(defn checkout!
  "DIR/<src>, a checkout of the source src whose one commit holds files; the
  pin the step writes (pin) stands for that commit from now on."
  [ctx src pin files]
  (let [ctx (k/project! ctx)
        ctx (if (get-in ctx [:ph "DIR"]) ctx (k/placeholder! ctx "DIR"))
        d (str (fs/path (get-in ctx [:ph "DIR"]) src))]
    (k/git-init! d)
    (k/write-files! d (merge {".keep" ""} files))
    (k/commit! d (str "the source " src))
    (assoc-in ctx [:pins pin] (k/git d "rev-parse" "--short=7" "HEAD"))))

(defn source!
  "The source id in the fixture's index, as m, with its record in dhf/raw."
  [ctx id m]
  (let [ctx (k/project! ctx)
        rec (fs/file (:dir ctx) "dhf/raw" (str id ".md"))]
    (when-not (fs/exists? rec) (spit rec (str "# Source: " id "\n")))
    (k/update-edn! ctx index assoc (keyword id) m)))

(defn loyly!
  "The source loyly, pinned at pins (each as a step writes it)."
  [ctx pins]
  (source! ctx "loyly" {:repository "https://github.com/skallinen/loyly" :record "loyly.md"
                        :pins (mapv #(k/real-pin ctx %) pins) :fenced []}))

(defn- need-cites!
  "The need id, citing citation (its pins made real) among its sources."
  [ctx id citation]
  (let [ctx (k/project! ctx)]
    (k/update-edn! ctx "dhf/spec/needs.edn" update id
                   (fn [n] (update (or n {:text (str "The need " id)}) :sources (fnil conj []) (k/real-pin ctx citation))))))

(defn- needs-line [ctx s]
  (k/line-of (slurp (fs/file (:dir ctx) "dhf/spec/needs.edn")) s))

(defn- index-commit!
  "Rewrites the index with f, then commits the project; the commit, short."
  [ctx f]
  (k/update-edn! ctx index f)
  (subs (k/commit! (:dir ctx) "the sources index") 0 7))

;; ---------------------------------------------------------------------------
;; A source is indexed with its repository, its pins and its record

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx]
    (-> (source! ctx "nopin" {:repository "https://example.org/nopin" :record "nopin.md" :fenced []})
        (k/names+ "nopin" "dhf/raw/sources.edn"))))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx]
    (-> (source! ctx "norepo" {:record "norepo.md" :pins ["abc1234"] :fenced []})
        (k/names+ "norepo"))))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (-> (k/update-edn! ctx index assoc :gone {:repository "https://example.org/gone" :record "gone.md"
                                                 :pins ["abc1234"] :fenced []})
          (k/names+ "gone" "dhf/raw/gone.md")))))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit keeps loyly with that pin replaced by another"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          _ (index-commit! ctx #(assoc-in % [:loyly :pins] ["17d68d2"]))
          dropper (index-commit! ctx #(assoc-in % [:loyly :pins] ["0a1b2c3"]))]
      (k/names+ ctx "loyly" "17d68d2" dropper "pin-dropped"))))

(def rdm-source {:repository "https://github.com/scope-impact/rdm" :record "rdm.md" :pins ["3763e08"] :fenced ["."]})

(defstep #"a commit of the sources index lists rdm pinned at 3763e08, a later commit removes rdm, and a later commit lists rdm again, pinned at 3763e08 and retired"
  (fn [ctx]
    (let [ctx (source! ctx "rdm" rdm-source)]
      (index-commit! ctx identity)
      (index-commit! ctx #(dissoc % :rdm))
      (index-commit! ctx #(assoc % :rdm (assoc rdm-source :retired "holds today's implementation")))
      ctx)))

(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"holds today's implementation\""
  (fn [ctx]
    (let [ctx (source! ctx "rdm" rdm-source)]
      (index-commit! ctx identity)
      (index-commit! ctx #(assoc-in % [:rdm :retired] "holds today's implementation"))
      (k/names+ ctx "retired"))))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx]
    (when-not (#{0 1} (:exit ctx)) (fail! (str "the check did not run: it exited " (:exit ctx) "\n" (:err ctx))))
    (assert (map? (k/edn-out ctx)) (:out ctx))
    (doseq [l (k/err-lines ctx)]
      (when (and (str/includes? l "rdm") (re-find #"(?i)dropped|pin-dropped" l))
        (fail! (str "a dropped pin is reported for rdm: " l))))
    ctx))

(defstep #"the need (UN-\d+) whose sources hold \"([^\"]+)\""
  (fn [ctx id s]
    (let [ctx (k/project! ctx)]
      (k/update-edn! ctx "dhf/spec/needs.edn" assoc id {:text (str "The need " id) :sources [s]})
      (k/names+ ctx id s))))

;; ---------------------------------------------------------------------------
;; A citation resolves to a source, a pin and a path

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [ctx]
    (let [ctx (-> (k/project! ctx)
                  (checkout! "loyly" "17d68d2" {"README.md" "# Loyly\n"})
                  (checkout! "innolitics-rdm" "f21b508" {"README.md" "# rdm\n"}))
          ctx (-> (loyly! ctx ["17d68d2"])
                  (source! "innolitics-rdm" {:repository "https://github.com/innolitics/rdm" :record "innolitics-rdm.md"
                                             :pins [(k/real-pin ctx "f21b508")] :fenced []}))]
      (-> ctx
          (need-cites! "UN-001" "loyly:README.md@17d68d2")
          (need-cites! "UN-001" "innolitics-rdm:docs/absent.md@f21b508")))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (let [present (k/real-pin ctx "loyly:README.md@17d68d2")
          absent (k/real-pin ctx "innolitics-rdm:docs/absent.md@f21b508")]
      ;; the one the checkout does not hold fails; the one it holds resolves
      (k/check-exit ctx 1)
      (k/err-line-with ctx absent "docs/absent.md")
      (assert (not-any? #(str/includes? % present) (k/err-lines ctx)) (:err ctx))
      ;; and nothing of either source is left unchecked
      (assert (not-any? #(and (str/includes? % "warning") (re-find #"loyly|innolitics-rdm" %)) (k/err-lines ctx)) (:err ctx))
      ctx)))

(defstep #"a citation \"([^\"]+)\" and no source innolitix"
  (fn [ctx c]
    (let [ctx (need-cites! ctx "UN-001" c)]
      (assert (not (contains? (sources ctx) :innolitix)))
      (k/names+ ctx "innolitix" (str "dhf/spec/needs.edn:" (needs-line ctx c))))))

(defstep #"a citation \"([^\"]+)\""
  (fn [ctx c]
    (let [ctx (need-cites! ctx "UN-001" c)]
      (k/names+ ctx (str "dhf/spec/needs.edn:" (needs-line ctx (k/real-pin ctx c)))))))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [ctx] (-> (loyly! ctx ["17d68d2"]) (k/names+ "0000000" "17d68d2"))))

(defstep #"a checkout of skallinen/loyly, holding README.md at 17d68d2, given with --sources"
  (fn [ctx] (-> (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"}) (loyly! ["17d68d2"]))))

(defstep #"a checkout of skallinen/loyly, with no file docs/plan.md at 17d68d2, given with --sources"
  (fn [ctx]
    (let [ctx (-> (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"}) (loyly! ["17d68d2"]))]
      (k/names+ ctx "docs/plan.md" (k/real-pin ctx "17d68d2")))))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources"
  (fn [ctx]
    (let [ctx (-> (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"}) (loyly! ["17d68d2"]))]
      (k/names+ ctx "loyly" (k/real-pin ctx "17d68d2")))))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (k/project! ctx)))

(defstep #"\"(rdm-spec check(?: --sources DIR)?)\" resolves the citation \"([^\"]+)\""
  (fn [ctx line c]
    (let [ctx (need-cites! ctx "UN-001" c)]
      (-> (k/rdm-line ctx line) (assoc ::citation (k/real-pin ctx c))))))

(defstep #"it resolves"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(str/includes? % (::citation ctx)) (k/err-lines ctx)) (:err ctx))
    ctx))

(defn- unchecked-warning [ctx c]
  (k/check-exit ctx 0)
  (let [l (first (filter #(str/includes? % c) (k/err-lines ctx)))]
    (when-not l (fail! (str "nothing names " c ":\n" (:err ctx))))
    (assert (str/includes? l "warning: ") (str "not a warning: " l))
    (assert (str/includes? l "not checked") l)
    (assert (str/includes? l "--sources") (str "no reason: " l))
    ctx))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx] (unchecked-warning ctx (::citation ctx))))

;; ---------------------------------------------------------------------------
;; A source's implementation is fenced

(defstep #"the sources index fences \"([^\"]+)\" in (\S+)"
  (fn [ctx path src]
    (let [ctx (k/project! ctx)
          ctx (if (contains? (sources ctx) (keyword src))
                ctx
                (source! ctx src {:repository (str "https://github.com/innolitics/" src) :record (str src ".md")
                                  :pins ["f21b508"] :fenced []}))]
      (-> (k/update-edn! ctx index update-in [(keyword src) :fenced] (fnil conj []) path)
          (k/names+ path)))))

;; ---------------------------------------------------------------------------
;; Citations are read from the needs and the wiki, nothing else

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {k/core-feature
                                          (k/feature-text {:name "Quoting" :tags ["UN-001"]
                                                           :scenarios [(k/sc 1 "Quotes"
                                                                             ["Given a citation \"loyly:harness/fence.sh@17d68d2\""
                                                                              "When it is read"
                                                                              "Then nothing happens"])]})}})]
      (k/update-edn! ctx index update-in [:loyly :fenced] (fnil conj []) "harness/fence.sh"))))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (str/includes? (:err ctx) "harness/fence.sh")) (:err ctx))
    ctx))

;; ---------------------------------------------------------------------------
;; A need's citations resolve

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx]
    (let [ctx (checkout! ctx "demo" "DEMOPIN" {"README.md" "# Demo\n"})]
      (source! ctx "demo" {:repository "https://example.org/demo" :record "demo.md"
                           :pins [(k/real-pin ctx "DEMOPIN")] :fenced []}))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx id c]
    (let [cited (str c "@DEMOPIN")
          ctx (need-cites! ctx id cited)]
      (k/names+ ctx id (k/real-pin ctx cited) "does not exist"))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\""
  (fn [ctx id c]
    (-> (need-cites! ctx id c) (assoc ::citation c))))

(defstep #"no checkout of loyly is given"
  (fn [ctx]
    (assert (not (get-in ctx [:ph "DIR"])) "a checkout directory was made")
    ctx))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx] (unchecked-warning ctx (::citation ctx))))
