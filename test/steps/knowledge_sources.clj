(ns steps.knowledge-sources
  "Steps of the component Sources (03-ground-the-spec/sources.feature), and the knowledge context's fixtures. A checkout is
  a git repository the fixture makes; its pin is that commit, which stands for
  the commit the scenario names (17d68d2, f21b508)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def sources-of
  {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []}
   :innolitics-rdm {:repository "https://github.com/innolitics/rdm" :record "innolitics-rdm.md" :pins ["f21b508"] :fenced []}})

(defn project!
  "A project whose sources index holds loyly and innolitics-rdm and whose need UN-001 cites loyly."
  [& [idx]]
  (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})})
        d (:dir ctx)]
    (k/write! d "dhf/raw/sources.edn" (k/pp (or idx sources-of)))
    (k/write! d "dhf/raw/innolitics-rdm.md" "# innolitics-rdm\n")
    ctx))

(defn ensure-project [ctx] (if (:dir ctx) ctx (project!)))

(defn sources [ctx] (clojure.edn/read-string (k/slurp-in (:dir ctx) "dhf/raw/sources.edn")))
(defn set-sources! [ctx idx] (k/write! (:dir ctx) "dhf/raw/sources.edn" (k/pp idx)) ctx)

(defn pin-of [ctx source default] (get-in ctx [:pins source] default))

(defn cite!
  "Adds a citation to the need UN-001's sources (the need UN-004 when given), substituting fixture pins."
  [ctx citation & [need]]
  (let [c (reduce (fn [c [orig real]] (str/replace c orig real)) citation (:pin-subst ctx))
        needs (clojure.edn/read-string (k/slurp-in (:dir ctx) "dhf/spec/needs.edn"))
        n (or need "UN-001")
        needs (update needs n (fn [v] (update (or v {:text "A need."}) :sources (fnil conj []) c)))]
    (k/write! (:dir ctx) "dhf/spec/needs.edn" (k/pp needs))
    (assoc ctx :citation c)))

(defn checkout!
  "A checkout of source name in the --sources directory DIR, holding files at one commit, which becomes the source's pin."
  [ctx name files orig-pin]
  (let [ctx (ensure-project ctx)
        dir (or (get-in ctx [:vars "DIR"]) (k/path (:top ctx) "sources"))
        co (k/path dir name)]
    (fs/create-dirs co)
    (k/git! co "init" "-q")
    (doseq [[f t] files] (k/write! co f t))
    (k/commit-all! co "pinned")
    (let [sha (subs (k/git! co "rev-parse" "HEAD") 0 7)
          idx (sources ctx)
          idx (if (contains? idx (keyword name))
                (assoc-in idx [(keyword name) :pins] [sha])
                (assoc idx (keyword name) {:repository (str "https://example.invalid/" name) :record (str name ".md") :pins [sha] :fenced []}))]
      (k/write! (:dir ctx) (str "dhf/raw/" name ".md") (str "# " name "\n"))
      (set-sources! ctx idx)
      ;; the need that cites loyly cites the checkout's pin
      (k/write! (:dir ctx) "dhf/spec/needs.edn" (str/replace (k/slurp-in (:dir ctx) "dhf/spec/needs.edn") (str name ":README.md@" orig-pin) (str name ":README.md@" sha)))
      (-> ctx (assoc-in [:vars "DIR"] dir) (assoc-in [:pins name] sha) (update :pin-subst assoc orig-pin sha)))))

;; ------------------------------------------------------------------ the index

(defstep #"the sources index names a source with a repository and no pin"
  (fn [_] (let [ctx (project!)] (set-sources! ctx (assoc (sources ctx) :demo {:repository "r" :record "loyly.md" :pins [] :fenced []})))))

(defstep #"the sources index names a source with a pin and no repository"
  (fn [_] (let [ctx (project!)] (set-sources! ctx (assoc (sources ctx) :demo {:record "loyly.md" :pins ["abcdef0"] :fenced []})))))

(defstep #"the sources index names a source whose record file is not in dhf/raw"
  (fn [_] (let [ctx (project!)] (set-sources! ctx (assoc (sources ctx) :demo {:repository "r" :record "demo.md" :pins ["abcdef0"] :fenced []})))))

(defstep #"the check fails and names the source"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/raw/sources.edn" "demo") ctx))

(defstep #"it fails rule (source-no-repository|source-no-record) and names the source( and the record file)?"
  (fn [ctx rule record]
    (k/exits! ctx 1) (k/line-has! ctx rule "demo")
    (when record (k/line-has! ctx rule "demo.md"))
    ctx))

(defstep #"a commit of the sources index pins loyly at 17d68d2, and a later commit keeps loyly with that pin replaced by another"
  (fn [_]
    (let [ctx (project!) d (:dir ctx)]
      (k/commit-all! d "pinned")
      (set-sources! ctx (assoc-in (sources ctx) [:loyly :pins] ["aaaaaaa"]))
      (let [c (k/commit-all! d "repinned")] (assoc ctx :dropper (subs c 0 7))))))

(defstep #"the check fails and names the source, the dropped pin and the commit that dropped it"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "loyly" "17d68d2" (:dropper ctx)) ctx))

(defn- rdm-history! [retire-only?]
  (let [ctx (project!) d (:dir ctx)
        rdm {:repository "https://github.com/scope-impact/rdm" :record "rdm.md" :pins ["3763e08"] :fenced ["."]}]
    (k/write! d "dhf/raw/rdm.md" "# rdm\n")
    (set-sources! ctx (assoc (sources ctx) :rdm rdm))
    (k/commit-all! d "lists rdm")
    (when-not retire-only?
      (set-sources! ctx (dissoc (sources ctx) :rdm))
      (k/commit-all! d "removes rdm"))
    (set-sources! ctx (assoc (sources ctx) :rdm (assoc rdm :retired "holds today's implementation")))
    (k/commit-all! d "retires rdm")
    ctx))

(defstep #"a commit of the sources index lists rdm pinned at 3763e08, a later commit removes rdm, and a later commit lists rdm again, pinned at 3763e08 and retired"
  (fn [_] (rdm-history! false)))

(defstep #"a commit of the sources index lists rdm with its pins, and a later commit keeps them and adds :retired \"holds today's implementation\""
  (fn [_] (rdm-history! true)))

(defstep #"the check reports no dropped pin for rdm"
  (fn [ctx]
    (k/expect! (#{0 1} (:exit ctx)) ctx "the check did not run")
    (k/expect! (not (some #(and (str/includes? % "rdm") (re-find #"dropped|pin-dropped" %)) (k/err-lines ctx))) ctx "a dropped pin for rdm")
    ctx))

(defstep #"it fails and names the citation and that its source is retired"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/wiki/pages/a-page.md:5:" "rdm:README.md@3763e08" "retired") ctx))

(defstep #"the need UN-004 whose sources hold \"the loyly README\""
  (fn [_] (cite! (project!) "the loyly README" "UN-004")))

(defstep #"it fails rule citation-malformed and names UN-004 and the source"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/needs.edn:" "citation-malformed" "UN-004" "the loyly README") ctx))

;; ------------------------------------------------------------------ citations

(defstep #"dhf/spec/needs.edn whose needs UN-001 and UN-004 both cite \"loyly:README.md@17d68d2\", on lines 3 and 9, and no --sources given"
  (fn [_]
    (let [ctx (project!)]
      (k/write! (:dir ctx) "dhf/spec/needs.edn"
                (str ";; needs\n"
                     "{\"UN-001\" {:text \"One.\"\n"
                     "            :sources [\"loyly:README.md@17d68d2\"]}\n"
                     " \"UN-002\" {:text \"Two.\" :sources [\"innolitics-rdm:README.md@f21b508\"]}\n"
                     " \"UN-003\" {:text \"Three.\" :sources [\"innolitics-rdm:README.md@f21b508\"]}\n"
                     ";; more\n"
                     ";; and more\n"
                     " \"UN-004\" {:text \"Four.\"\n"
                     "            :sources [\"loyly:README.md@17d68d2\"]}}\n"))
      ctx)))

(defstep #"it warns citation-unchecked twice, naming dhf/spec/needs.edn:3 for UN-001 and dhf/spec/needs.edn:9 for UN-004"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [ws (filter #(and (str/includes? % "citation-unchecked") (str/includes? % "loyly:README.md")) (k/warning-lines ctx))]
      (k/expect! (= 2 (count ws)) ctx "not two warnings")
      (k/expect! (some #(and (str/starts-with? % "dhf/spec/needs.edn:3:") (str/includes? % "UN-001")) ws) ctx "UN-001 not on line 3")
      (k/expect! (some #(and (str/starts-with? % "dhf/spec/needs.edn:9:") (str/includes? % "UN-004")) ws) ctx "UN-004 not on line 9"))
    ctx))

(defstep #"a directory holding loyly/ and innolitics-rdm/, checkouts of those sources"
  (fn [_]
    (-> (project!)
        (checkout! "loyly" {"README.md" "# Löyly\n"} "17d68d2")
        (checkout! "innolitics-rdm" {"README.md" "# RDM\n"} "f21b508"))))

(defstep #"citations of loyly and innolitics-rdm are checked against those checkouts"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (not (str/includes? (str (:err ctx)) "citation-unchecked")) ctx "a citation was not checked")
    ctx))

(defstep #"a citation \"([^\"]+)\"(?: and no source innolitix)?"
  (fn [ctx c] (cite! (ensure-project ctx) c)))

(defstep #"the citation fails and names innolitix and where it was cited"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/needs.edn:" "innolitix" "UN-001") ctx))

(defstep #"the source loyly is pinned only at 17d68d2"
  (fn [_] (project!)))

(defstep #"the citation fails and names the commit and the source's pins"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/needs.edn:" "0000000" "17d68d2") ctx))

(defstep #"a checkout of skallinen/loyly, holding README.md at 17d68d2, given with --sources"
  (fn [ctx] (checkout! ctx "loyly" {"README.md" "# Löyly\n"} "17d68d2")))

(defstep #"a checkout of skallinen/loyly, with no file docs/plan.md at 17d68d2, given with --sources"
  (fn [ctx] (checkout! ctx "loyly" {"README.md" "# Löyly\n"} "17d68d2")))

(defstep #"a checkout of skallinen/loyly at 17d68d2, given with --sources(?:, with no docs/plan.md)?"
  (fn [ctx] (checkout! ctx "loyly" {"README.md" "# Löyly\n" "harness/README.md" "# h\n"} "17d68d2")))

(defstep #"\"rdm-spec check( --sources DIR)?\" resolves the citation \"([^\"]+)\""
  (fn [ctx sources c]
    (let [ctx (cite! (ensure-project ctx) c)]
      (k/rdm! ctx (if sources "check --sources DIR" "check")))))

(defstep #"\"rdm-spec check\" is given no --sources at all"
  (fn [ctx] (ensure-project ctx)))

(defstep #"it resolves"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (not (some #(str/includes? % (:citation ctx)) (k/err-lines ctx))) ctx "the citation is named")
    ctx))

(defstep #"the citation fails and names the path and the pin"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/needs.edn:" "docs/plan.md" (get-in ctx [:pins "loyly"])) ctx))

(defstep #"it is reported as unchecked, with the reason"
  (fn [ctx] (k/exits! ctx 0) (k/line-has! ctx "warning: citation-unchecked" "loyly:README.md@17d68d2" "--sources") ctx))

(defstep #"the sources index fences \"([^\"]+)\" in (loyly|innolitics-rdm)"
  (fn [ctx path source]
    (let [ctx (ensure-project ctx)]
      (set-sources! ctx (update-in (sources ctx) [(keyword source) :fenced] conj path)))))

(defstep #"the citation fails and names the fenced (path|directory) and where it was cited"
  (fn [ctx what]
    (k/exits! ctx 1)
    (k/line-has! ctx "dhf/spec/needs.edn:" "UN-001" (if (= what "path") "harness/fence.sh" "rdm"))
    (k/line-has! ctx "citation-fenced")
    ctx))

(defstep #"the check fails and names the source, the pin and the path"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "loyly" (get-in ctx [:pins "loyly"]) "harness/missing.clj") ctx))

(defstep #"a feature file whose Given step quotes a citation of a fenced path"
  (fn [_]
    (let [ctx (project! (assoc-in sources-of [:loyly :fenced] ["harness/fence.sh"]))]
      (k/write! (:dir ctx) "dhf/spec/shop/cart.feature"
                (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :steps ["Given the citation \"loyly:harness/fence.sh@17d68d2\"" "Then it holds"]}]}))
      ctx)))

(defstep #"the quoted citation is not resolved, and the check passes"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (not (str/includes? (str (:err ctx)) "fence.sh")) ctx "the quoted citation was resolved") ctx))

(defstep #"the recorded source demo, pinned at the commit of a checkout given with --sources"
  (fn [ctx] (checkout! ctx "demo" {"README.md" "# demo\n"} "0000000")))

(defstep #"the need UN-004 citing \"demo:docs/nothing.md\" at that pin, a path the checkout does not hold"
  (fn [ctx] (cite! ctx (str "demo:docs/nothing.md@" (get-in ctx [:pins "demo"])) "UN-004")))

(defstep #"it fails and names UN-004, the citation and why it did not resolve"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "UN-004" "demo:docs/nothing.md" "no docs/nothing.md") ctx))

(defstep #"the need UN-004 citing \"loyly:README.md@17d68d2\""
  (fn [ctx] (cite! (ensure-project ctx) "loyly:README.md@17d68d2" "UN-004")))

(defstep #"no checkout of loyly is given" (fn [ctx] ctx))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx] (k/line-has! ctx "warning: citation-unchecked" "UN-004" "loyly:README.md@17d68d2" "no --sources") ctx))

