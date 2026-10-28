(ns steps.knowledge-sources
  "Steps of the component Sources (dhf/spec/03-ground-the-spec/sources.feature).
  A source's checkout is a small git repository the scenario makes; its pin is
  that repository's commit, standing in for the commit the scenario names."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn sources [ctx] (edn/read-string (k/slurp-rel ctx "dhf/raw/sources.edn")))

(defn source!
  "Records the source ID in the index (and its record file), with M merged."
  [ctx id m]
  (let [m (merge {:repository (str "https://github.com/example/" (name id)) :record (str (name id) ".md") :pins ["17d68d2"] :fenced []} m)]
    (k/write-edn! ctx "dhf/raw/sources.edn" (assoc (sources ctx) id m))
    (when (:record m) (k/write! ctx (str "dhf/raw/" (:record m)) (str "# " (name id) "\n")))
    ctx))

(defn need! [ctx id citation]
  (k/write-edn! ctx "dhf/spec/needs.edn" (assoc (edn/read-string (k/slurp-rel ctx "dhf/spec/needs.edn"))
                                                id {:text "A need" :sources [citation]})))

(defn page!
  "A wiki page, listed in the index, whose text is TEXT."
  [ctx name text]
  (k/write! ctx (str "dhf/wiki/pages/" name ".md") text)
  (let [idx (k/slurp-rel ctx "dhf/wiki/index.md")]
    (when-not (str/includes? idx (str "(pages/" name ".md)"))
      (k/write! ctx "dhf/wiki/index.md" (str idx "\n- [" name "](pages/" name ".md): a page.\n"))))
  ctx)

(defn cited-page [citation]
  (str "# A page\n\nA claim.[^a]\n\n[^a]: `" citation "`, \"Somewhere\".\n"))

(defn checkout!
  "A checkout of the source NAME under DIR (the scenario's sources directory)
  holding FILES at one commit; returns ctx with the commit as (::pin name)."
  [ctx name files]
  (let [dir (str (:base ctx) "/sources/" name)
        c (assoc ctx :dir dir)]
    (fs/create-dirs dir)
    (k/git! c "init" "-q")
    (doseq [[f t] files] (k/write! c f t))
    (k/commit! c "the source")
    (let [pin (subs (k/git! c "rev-parse" "HEAD") 0 7)]
      (-> ctx
          (assoc-in [:ph "DIR"] (str (:base ctx) "/sources"))
          (assoc-in [::pin name] pin)))))

(defn pin [ctx name] (get-in ctx [::pin name] "17d68d2"))

(defn findings [ctx] (remove #(re-find #"(^|: )warning: " %) (k/err-lines ctx)))
(defn warnings [ctx] (filter #(re-find #"(^|: )warning: " %) (k/err-lines ctx)))

(defn base! [ctx] (if (:dir ctx) ctx (k/spec-project! ctx)))

;; ------------------------------------------------------------------ the index

(defstep #"the sources index names a source with a repository and no pin"
  (fn [ctx] (source! (base! ctx) :nopin {:pins []})))

(defstep #"the check fails and names the source"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "nopin") ctx))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [ctx] (let [ctx (source! (base! ctx) :norepo {})] (k/write-edn! ctx "dhf/raw/sources.edn" (update (sources ctx) :norepo dissoc :repository)) ctx)))

(defstep #"it fails rule source-no-repository and names the source"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "source-no-repository" "norepo") ctx))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [ctx] (let [ctx (source! (base! ctx) :norecord {:record "norecord.md"})] (k/delete! ctx "dhf/raw/norecord.md"))))

(defstep #"it fails rule source-no-record and names the source and the record file"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "source-no-record" "norecord" "norecord.md") ctx))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit keeps loyly with that pin replaced by another"
  (fn [ctx]
    (let [ctx (source! (base! ctx) :loyly {:pins ["17d68d2"]})]
      (k/commit! ctx "pin loyly")
      (source! ctx :loyly {:pins ["1234567"]})
      (k/commit! ctx "replace the pin")
      (assoc ctx ::dropper (subs (k/git! ctx "rev-parse" "HEAD") 0 7)))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "loyly" "17d68d2" (::dropper ctx)) ctx))

(defstep #"a commit of the sources index lists rdm pinned at 3763e08, a later commit removes rdm, and a later commit lists rdm again, pinned at 3763e08 and retired"
  (fn [ctx]
    (let [ctx (source! (base! ctx) :rdm {:pins ["3763e08"]})]
      (k/commit! ctx "rdm")
      (k/write-edn! ctx "dhf/raw/sources.edn" (dissoc (sources ctx) :rdm))
      (k/commit! ctx "no rdm")
      (source! ctx :rdm {:pins ["3763e08"] :retired "holds today's implementation"})
      (k/commit! ctx "rdm, retired"))))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx] (k/check! (not-any? #(and (str/includes? % "rdm") (str/includes? % "dropped")) (k/err-lines ctx)) "a dropped pin is reported: " (k/shows ctx)) ctx))

(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"holds today's implementation\""
  (fn [ctx]
    (let [ctx (source! (base! ctx) :rdm {:pins ["3763e08"]})]
      (k/commit! ctx "rdm")
      (source! ctx :rdm {:pins ["3763e08"] :retired "holds today's implementation"})
      (k/commit! ctx "rdm, retired"))))

(defstep #"a wiki page whose footnote cites \"rdm:README.md@3763e08\""
  (fn [ctx] (page! ctx "rdm-page" (cited-page "rdm:README.md@3763e08"))))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "rdm:README.md@3763e08" "retired") ctx))

(defstep #"the need UN-004 whose sources hold \"the loyly README\""
  (fn [ctx] (need! (base! ctx) "UN-004" "the loyly README")))

(defstep #"it fails rule citation-malformed and names UN-004 and the source"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "citation-malformed" "UN-004" "the loyly README") ctx))

;; ------------------------------------------------------------------ resolving

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [ctx]
    (let [ctx (-> (base! ctx)
                  (checkout! "loyly" {"README.md" "# Loyly\n"})
                  (checkout! "innolitics-rdm" {"README.md" "# rdm\n"}))]
      (source! ctx :loyly {:pins [(pin ctx "loyly")]})
      (source! ctx :innolitics-rdm {:pins [(pin ctx "innolitics-rdm")]})
      (need! ctx "UN-004" (str "loyly:README.md@" (pin ctx "loyly")))
      (need! ctx "UN-005" (str "innolitics-rdm:nothing.md@" (pin ctx "innolitics-rdm"))))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (not-any? #(str/includes? % "loyly:README.md") (k/err-lines ctx)) "the loyly citation did not resolve: " (k/shows ctx))
    (k/err-line-has! ctx "innolitics-rdm:nothing.md")
    ctx))

(defstep #"a citation \"innolitix:README.md@f21b508\" and no source innolitix"
  (fn [ctx] (need! (base! ctx) "UN-004" "innolitix:README.md@f21b508")))

(defstep #"the citation fails and names innolitix and where it was cited"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/needs.edn:" "innolitix") ctx))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [ctx] (source! (base! ctx) :loyly {:pins ["17d68d2"]})))

(defstep #"a citation \"loyly:README.md@0000000\""
  (fn [ctx] (need! ctx "UN-004" "loyly:README.md@0000000")))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "0000000" "17d68d2") ctx))

(defstep #"a checkout of skallinen/loyly, holding README.md at 17d68d2, given with --sources"
  (fn [ctx]
    (let [ctx (checkout! (base! ctx) "loyly" {"README.md" "# Loyly\n"})]
      (source! ctx :loyly {:pins [(pin ctx "loyly")]}))))

(defstep #"\"rdm-spec check --sources DIR\" resolves the citation \"loyly:([^\"]+)@17d68d2\""
  (fn [ctx path]
    (need! ctx "UN-004" (str "loyly:" path "@" (pin ctx "loyly")))
    (assoc (k/run-rdm ctx "check --sources DIR") ::citation (str "loyly:" path "@" (pin ctx "loyly")))))

(defstep #"it resolves"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % (::citation ctx)) (k/err-lines ctx)) "the citation is named: " (k/shows ctx))
    ctx))

(defstep #"a checkout of skallinen/loyly, with no file docs/plan.md at 17d68d2, given with --sources"
  (fn [ctx]
    (let [ctx (checkout! (base! ctx) "loyly" {"README.md" "# Loyly\n"})]
      (source! ctx :loyly {:pins [(pin ctx "loyly")]}))))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "docs/plan.md" (pin ctx "loyly")) ctx))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (source! (base! ctx) :loyly {:pins ["17d68d2"]})))

(defstep #"\"rdm-spec check\" resolves the citation \"loyly:README.md@17d68d2\""
  (fn [ctx] (need! ctx "UN-004" "loyly:README.md@17d68d2") (k/run-rdm ctx "check")))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (some #(and (str/includes? % "loyly:README.md@17d68d2") (str/includes? % "--sources")) (warnings ctx)) "no warning says why: " (k/shows ctx))
    ctx))

;; ------------------------------------------------------------------ fenced

(defstep #"the sources index fences \"harness/fence.sh\" in loyly"
  (fn [ctx] (source! (base! ctx) :loyly {:pins ["17d68d2"] :fenced ["harness/fence.sh"]})))

(defstep #"a citation \"(loyly:harness/fence.sh@17d68d2|innolitics-rdm:rdm/render.py@f21b508)\""
  (fn [ctx c] (need! ctx "UN-004" c)))

(defstep #"the citation fails and names the fenced path and where it was cited"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/needs.edn:" "harness/fence.sh" "fenced") ctx))

(defstep #"the sources index fences \"rdm\" in innolitics-rdm"
  (fn [ctx] (source! (base! ctx) :innolitics-rdm {:pins ["f21b508"] :fenced ["rdm"]})))

(defstep #"the citation fails and names the fenced directory and where it was cited"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/needs.edn:" "rdm/render.py" "fenced") ctx))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources"
  (fn [ctx]
    (let [ctx (checkout! (base! ctx) "loyly" {"README.md" "# Loyly\n" "harness/fence.sh" "echo\n" "docs/x.md" "x\n"})]
      (source! ctx :loyly {:pins [(pin ctx "loyly")]}))))

(defstep #"the sources index fences \"harness/missing.clj\" in loyly"
  (fn [ctx] (source! ctx :loyly {:pins [(pin ctx "loyly")] :fenced ["harness/missing.clj"]})))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "loyly" (pin ctx "loyly") "harness/missing.clj") ctx))

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [ctx]
    (let [ctx (source! (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                                         (k/feature-text "Thing" ["UN-001"]
                                                                         [{:id "spec-0001" :title "Quotes"
                                                                           :steps ["Given the citation \"loyly:harness/fence.sh@17d68d2\"" "Then it holds"]}])}})
                       :loyly {:pins ["17d68d2"] :fenced ["harness/fence.sh"]})]
      ctx)))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % "harness/fence.sh") (k/err-lines ctx)) "the quoted citation is named: " (k/shows ctx))
    ctx))

;; ------------------------------------------------------------------ needs

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx]
    (let [ctx (checkout! (base! ctx) "demo" {"README.md" "# demo\n"})]
      (source! ctx :demo {:pins ["abc1234" (pin ctx "demo")] :record "demo.md"}))))

(defstep #"the need UN-004 citing \"demo:docs/nothing.md\" at that pin, a path the checkout does not hold"
  (fn [ctx] (need! ctx "UN-004" (str "demo:docs/nothing.md@" (pin ctx "demo")))))

(defstep #"it fails and names UN-004, the citation and why it did not resolve"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "UN-004" (str "demo:docs/nothing.md@" (pin ctx "demo")) "does not hold") ctx))

(defstep #"the need UN-004 citing \"loyly:README.md@17d68d2\""
  (fn [ctx] (need! (source! (base! ctx) :loyly {:pins ["17d68d2"]}) "UN-004" "loyly:README.md@17d68d2")))

(defstep #"no checkout of loyly is given" (fn [ctx] ctx))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (k/check! (some #(and (str/includes? % "loyly:README.md@17d68d2") (str/includes? % "--sources")) (warnings ctx)) "no warning: " (k/shows ctx))
    ctx))

(defstep #"\"rdm-spec check --sources DIR\" is run with that directory as DIR"
  (fn [ctx] (k/run-rdm ctx "check --sources DIR")))
