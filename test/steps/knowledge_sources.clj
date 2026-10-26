(ns steps.knowledge-sources
  "Step definitions of dhf/spec/knowledge/sources.feature, and the knowledge
  context's helpers: source checkouts and the sources index."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn sources [ctx] (edn/read-string (slurp (fs/file (k/guard! (:dir ctx)) "dhf/raw/sources.edn"))))

(defn source!
  "Adds (or replaces) a source in the index, with its record unless :no-record."
  [ctx id m & {:keys [no-record]}]
  (let [ctx (k/project! ctx)]
    (k/update-edn! ctx "dhf/raw/sources.edn" assoc (keyword id) m)
    (when-not no-record (k/write-files! (:dir ctx) {(str "dhf/raw/" (:record m)) (str "# Source: " id "\n")}))
    ctx))

(defn cite!
  "Adds a citation to the sources of the need (UN-001 unless named)."
  ([ctx citation] (cite! ctx "UN-001" citation))
  ([ctx need citation]
   (k/update-edn! ctx "dhf/spec/needs.edn"
                  (fn [m] (update m need (fn [n] (-> (or n {:text (str "The need " need)})
                                                     (update :sources (fnil conj []) (k/real-pin ctx citation)))))))))

(defn checkout!
  "A checkout DIR/<id> of a source holding `files`, its commit recorded as the
  pin `written` stands for. Sets :ph DIR and :pins."
  [ctx id written files]
  (let [ctx (k/placeholder! (k/project! ctx) "DIR")
        d (str (fs/path (get-in ctx [:ph "DIR"]) id))]
    (k/git-init! d)
    (k/write-files! d files)
    (k/commit! d "the source")
    (let [sha (subs (k/git d "rev-parse" "HEAD") 0 7)]
      (assoc-in ctx [:pins written] sha))))

(defn loyly! [ctx pins & {:as more}]
  (source! ctx "loyly" (merge {:repository "https://github.com/skallinen/loyly" :record "loyly.md"
                               :pins (mapv #(k/real-pin ctx %) pins) :fenced []}
                              more)))

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx] (-> (source! ctx "lonely" {:repository "https://example.org/lonely" :record "lonely.md" :pins []})
                (k/names+ "lonely"))))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx] (-> (source! ctx "lonely" {:record "lonely.md" :pins ["abc1234"]}) (k/names+ "lonely"))))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx] (-> (source! ctx "lonely" {:repository "https://example.org/lonely" :record "lonely.md" :pins ["abc1234"]} :no-record true)
                (k/names+ "lonely" "lonely.md"))))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit keeps loyly with that pin replaced by another"
  (fn [ctx]
    (let [ctx (loyly! ctx ["17d68d2"])]
      (k/commit! (:dir ctx) "pin loyly")
      (let [ctx (loyly! ctx ["aaaaaaa"])
            sha (k/commit! (:dir ctx) "replace the pin")]
        (k/names+ ctx "loyly" "17d68d2" (subs sha 0 7))))))

(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"holds today's implementation\""
  (fn [ctx]
    (let [ctx (source! ctx "rdm" {:repository "https://github.com/scope-impact/rdm" :record "rdm.md" :pins ["3763e08"] :fenced []})]
      (k/commit! (:dir ctx) "rdm")
      (source! ctx "rdm" {:repository "https://github.com/scope-impact/rdm" :record "rdm.md" :pins ["3763e08"] :fenced []
                          :retired "holds today's implementation"})
      (k/commit! (:dir ctx) "retire rdm")
      (k/names+ ctx "retired"))))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx]
    (assert (not-any? #(and (str/includes? % "rdm") (str/includes? % "dropped")) (k/err-lines ctx)) (:err ctx))
    ctx))

(defstep #"the need UN-004 whose sources hold \"the loyly README\""
  (fn [ctx]
    (-> (k/project! ctx) (cite! "UN-004" "the loyly README") (k/names+ "UN-004" "the loyly README"))))

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [ctx]
    (let [ctx (-> (k/project! ctx)
                  (checkout! "loyly" "17d68d2" {"README.md" "# Loyly\n"})
                  (checkout! "innolitics-rdm" "f21b508" {"README.md" "# RDM\n"}))]
      (-> ctx
          (loyly! ["17d68d2"])
          (source! "innolitics-rdm" {:repository "https://github.com/innolitics/rdm" :record "innolitics-rdm.md"
                                     :pins [(k/real-pin ctx "f21b508")] :fenced []})
          (cite! "loyly:README.md@17d68d2")
          (cite! "innolitics-rdm:README.md@f21b508")))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(and (str/includes? % "unchecked") (or (str/includes? % "loyly") (str/includes? % "innolitics-rdm")))
                      (k/err-lines ctx))
            (:err ctx))
    ;; and a path neither holds fails against them
    (cite! ctx "loyly:missing.md@17d68d2")
    (let [r (k/rdm ctx ["check" "--sources" (get-in ctx [:ph "DIR"])])]
      (k/check-exit r 1)
      (k/err-line-with r "loyly:missing.md"))
    ctx))

(defstep #"a citation \"(.+)\""
  (fn [ctx c]
    (let [ctx (k/project! ctx)
          [_ src] (re-find #"^([^:]+):" c)]
      (-> (cite! ctx c) (k/names+ "dhf/spec/needs.edn")))))

(defstep #"a citation \"innolitix:README.md@f21b508\" and no source innolitix"
  (fn [ctx] (-> (k/project! ctx) (cite! "innolitix:README.md@f21b508") (k/names+ "innolitix" "dhf/spec/needs.edn"))))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [ctx] (-> (loyly! ctx ["17d68d2"]) (k/names+ "0000000" "17d68d2"))))

(defstep #"a checkout of skallinen/loyly, holding README.md at 17d68d2, given with --sources"
  (fn [ctx] (let [ctx (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"})] (loyly! ctx ["17d68d2"]))))

(defstep #"a checkout of skallinen/loyly, with no file docs/plan.md at 17d68d2, given with --sources"
  (fn [ctx] (let [ctx (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"})]
              (-> (loyly! ctx ["17d68d2"]) (k/names+ "docs/plan.md" (k/real-pin ctx "17d68d2"))))))

(defstep #"\"rdm-spec check --sources DIR\" resolves the citation \"(.+)\""
  (fn [ctx c]
    (let [ctx (cite! ctx c)]
      (k/rdm ctx ["check" "--sources" (get-in ctx [:ph "DIR"])]))))

(defstep #"it resolves"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(str/includes? % "loyly:README.md") (k/err-lines ctx)) (:err ctx))
    ctx))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (loyly! ctx ["17d68d2"])))

(defstep #"\"rdm-spec check\" resolves the citation \"(.+)\""
  (fn [ctx c] (k/rdm (cite! ctx c) ["check"])))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (k/err-line-with ctx "warning:" "loyly:README.md@17d68d2" "--sources")
    (assert (not-any? #(and (str/includes? % "loyly:README.md") (not (str/includes? % "warning:"))) (k/err-lines ctx)))
    ctx))

(defstep #"the sources index fences \"(.+)\" in (\S+)"
  (fn [ctx path src]
    (let [ctx (k/project! ctx)
          existing (get (sources ctx) (keyword src))
          m (or existing (if (= src "loyly")
                           {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins [(k/real-pin ctx "17d68d2")]}
                           {:repository (str "https://github.com/x/" src) :record (str src ".md") :pins ["f21b508"]}))]
      (-> (source! ctx src (update m :fenced (fnil conj []) path))
          (k/names+ path)))))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources"
  (fn [ctx] (let [ctx (checkout! ctx "loyly" "17d68d2" {"README.md" "# Loyly\n"})]
              (-> (loyly! ctx ["17d68d2"]) (k/names+ "loyly" (k/real-pin ctx "17d68d2"))))))

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (loyly! ctx ["17d68d2"] :fenced ["harness/fence.sh"])]
      (k/write-files! (:dir ctx) {"dhf/spec/kernel/core.feature"
                                  (k/feature-text {:name "Core" :tags ["UN-001"]
                                                   :scenarios [(k/sc 1 "Quotes" ["Given the citation \"loyly:harness/fence.sh@17d68d2\""])]})})
      ctx)))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (str/includes? (:err ctx) "fence.sh")) (:err ctx))
    ctx))

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx]
    (let [ctx (checkout! ctx "demo" "DEMOPIN" {"README.md" "# Demo\n"})]
      (source! ctx "demo" {:repository "https://example.org/demo" :record "demo.md" :pins [(k/real-pin ctx "DEMOPIN")] :fenced []}))))

(defstep #"the need UN-004 citing \"demo:docs/nothing.md\" at that pin, a path the checkout does not hold"
  (fn [ctx]
    (let [c (k/real-pin ctx "demo:docs/nothing.md@DEMOPIN")]
      (-> (cite! ctx "UN-004" c) (k/names+ "UN-004" c "docs/nothing.md")))))

(defstep #"the need UN-004 citing \"loyly:README.md@17d68d2\""
  (fn [ctx] (-> (loyly! ctx ["17d68d2"]) (cite! "UN-004" "loyly:README.md@17d68d2"))))

(defstep #"no checkout of loyly is given"
  (fn [ctx] ctx))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (k/err-line-with ctx "warning:" "loyly:README.md@17d68d2" "not checked")))
