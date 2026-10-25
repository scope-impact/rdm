(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature, in a copy of the room's
  spec whose parser feature file (and, for an id used twice, the spec check's)
  is replaced by a fixture holding the scenario under test."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(def parser-file "dhf/spec/spec/parser.feature")
(def check-file "dhf/spec/spec/check.feature")

(def fixture-id "spec-0051")

(defn- room
  "ctx with a copy of the room's spec in :dir; the spec check must be built."
  [ctx]
  (s/needs-built! 'rdm-spec.spec.check 'rdm-spec.spec.parser)
  (f/ensure-room! ctx))

(defn- fixture!
  "Replaces the parser's feature file with one scenario (fixture-feature's
  options `opts`); the scenario's id, when it has one, is kept as :id."
  [ctx opts]
  (let [ctx (room ctx)
        text (f/fixture-feature opts)]
    (f/feature! (:dir ctx) "spec/parser" text)
    (assoc ctx :file parser-file :text text :name (or (:name opts) "A fixture scenario")
           :line (f/scenario-line text (or (:name opts) "A fixture scenario")))))

(defn- stamped!
  "A stamped scenario carrying `tags` besides its id."
  [ctx tags & [opts]]
  (assoc (fixture! ctx (merge {:tags (str/trim (str "@" fixture-id " " tags))} opts)) :id fixture-id))

(defn- findings-naming
  "The finding lines (warnings excluded) naming every one of `parts`."
  [run & parts]
  (filter (fn [l] (and (not (str/includes? l "warning:")) (every? #(str/includes? l %) parts)))
          (f/finding-lines run)))

;; ---------------------------------------------------------------------------
;; One id per scenario

(defstep #"a scenario tagged both @(\S+) and @(\S+)"
  (fn [ctx a b]
    (assoc (fixture! ctx {:tags (str "@" a " @" b " @e2e") :name "A scenario with two ids"}) :ids [a b])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx]
    (apply f/fails-naming! ctx (:name ctx) (:ids ctx))))

(defstep #"two scenarios both tagged @(\S+)"
  (fn [ctx id]
    (let [ctx (room ctx)
          opts (fn [name feature] {:tags (str "@" id " @e2e") :name name :feature-name feature})]
      (f/features! (:dir ctx) {"spec/parser" (f/fixture-feature (opts "The first holder" "Parser"))
                               "spec/check" (f/fixture-feature (opts "The second holder" "Spec check"))})
      (assoc ctx :id id :files [parser-file check-file]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (apply f/fails-naming! ctx (:id ctx) (:files ctx))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx line]
    (let [line (parse-long line)
          ;; fixture-feature puts the scenario on line 8 when nothing leads it
          lead (- line 8)
          _ (s/check! (>= lead 0) "the line is too early for the fixture")
          ctx (fixture! ctx {:tags "@e2e" :leading (repeat lead "# a comment placing the scenario")})]
      (s/check! (= line (:line ctx)) (str "the scenario is on line " (:line ctx)))
      ctx)))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx line]
    (f/fails-naming! ctx (str (:file ctx) ":" line ":") "rdm-spec stamp")))

;; ---------------------------------------------------------------------------
;; One level per scenario

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (stamped! ctx "@e2e @contract")))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (stamped! ctx "")))

(defstep #"a stamped scenario tagged (@[^\s@]+)"
  (fn [ctx tag] (assoc (stamped! ctx tag) :tag tag)))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (not= 2 (:exit r)) (s/show r))
      (s/check! (empty? (findings-naming r (:id ctx)))
                (str "a finding names " (:id ctx) "\n" (s/show r))))))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx]
    (f/fails-naming! ctx (:id ctx) "@e2e" "@contract")))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx]
    (f/fails-naming! ctx (:id ctx) "@e2e" "@contract")))

(defstep #"it fails, names the scenario's id and (@\S+), and lists the tags a scenario may carry"
  (fn [ctx tag]
    (f/fails-naming! ctx (:id ctx) tag "@spec-" "@e2e" "@contract" "@UN-" "@wip")))

;; ---------------------------------------------------------------------------
;; Needs

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need]
    (s/needs-built! 'rdm-spec.verification.trace)
    (assoc (stamped! ctx "@e2e" {:feature-tags (str "@" need)}) :need need)))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (let [own (f/tags-of (:dir ctx) (:name ctx))]
      (s/check! (seq own) "no scenario under the feature")
      (s/check! (not-any? #(re-matches #"@UN-\d+" %) own) (str "the scenario carries " (pr-str own)))
      ctx)))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (:run ctx)
          out (f/result r)]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some #(= (:id ctx) (:id %)) (:scenarios out))
                (str "the trace does not list " (:id ctx) "\n" (s/show r))))))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (stamped! ctx "@e2e" {:feature-tags nil})))

(defstep #"it fails and names the scenario's id"
  (fn [ctx]
    (f/fails-naming! ctx (:id ctx))))

(defstep #"the declared needs are (UN-\d+) to (UN-\d+)"
  (fn [ctx from to]
    (let [ctx (room ctx)
          n #(parse-long (subs % 3))
          lo (n from) hi (n to)]
      (f/needs! (:dir ctx) (fn [needs] (into {} (filter #(<= lo (n (key %)) hi) needs))))
      ctx)))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc (stamped! ctx (str "@e2e @" need)) :need need)))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need]
    (f/fails-naming! ctx need (:id ctx))))

;; ---------------------------------------------------------------------------
;; Sources

(defn- set-sources! [ctx need sources]
  (f/needs! (:dir ctx) (fn [needs] (assoc-in needs [need :sources] sources))))

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (let [ctx (room ctx)]
      (set-sources! ctx need [])
      (assoc ctx :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need]
    (f/fails-naming! ctx need)))

(defn- git!
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
                                      "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (str/trim (:out r))))

(defstep #"the recorded source (\S+), pinned at the commit of a checkout given with --sources"
  (fn [ctx id]
    (s/needs-built! 'rdm-spec.knowledge.sources)
    (let [ctx (room ctx)
          sdir (s/tmp-dir)
          checkout (str (fs/file sdir id))
          _ (s/write! checkout "README.md" "A demo source.\n")
          _ (git! checkout "init" "-q")
          _ (git! checkout "add" "-A")
          _ (git! checkout "commit" "-q" "-m" "a demo source")
          pin (git! checkout "rev-parse" "--short=7" "HEAD")
          index (fs/file (:dir ctx) "dhf/raw/sources.edn")]
      (spit (str index) (pr-str (assoc (edn/read-string (slurp (str index)))
                                       (keyword id) {:repository (str "https://example.org/" id)
                                                     :record (str id ".md") :pins [pin] :fenced []})))
      (s/write! (:dir ctx) (str "dhf/raw/" id ".md") (str "# " id "\n\nA demo source.\n"))
      (assoc ctx :sources-dir sdir :source id :pin pin))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\" at that pin, a path the checkout does not hold"
  (fn [ctx need citation]
    (let [citation (str citation "@" (:pin ctx))]
      (set-sources! ctx need [citation])
      (assoc ctx :need need :citation citation))))

(defstep #"it fails and names (UN-\d+), the citation and why it did not resolve"
  (fn [ctx need]
    (let [r (:run ctx)
          lines (findings-naming r need (:citation ctx))]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (seq lines) (str "no finding names " need " and " (:citation ctx) "\n" (s/show r)))
      ;; why: more than the need and the citation, saying it does not resolve
      (s/check! (some #(re-find #"(?i)not|no |missing|unresolved" (str/replace % (:citation ctx) "")) lines)
                (str "no finding says why\n" (s/show r))))))

(defstep #"the need (UN-\d+) citing \"([^\"]+)\""
  (fn [ctx need citation]
    (apply s/needs-built! f/check-stages)
    (f/ensure-shared-steps!)
    (let [ctx (room ctx)]
      (f/generate-views! (:dir ctx))
      (set-sources! ctx need [citation])
      (assoc ctx :need need :citation citation))))

(defstep #"no checkout of (\S+) is given"
  (fn [ctx _source]
    (dissoc ctx :sources-dir)))

(defstep #"it warns that the citation was not checked, and why"
  (fn [ctx]
    (let [r (:run ctx)
          lines (filter #(and (str/includes? % "warning:") (str/includes? % (:citation ctx)))
                        (s/err-lines r))]
      (s/check! (seq lines) (str "no warning names " (:citation ctx) "\n" (s/show r)))
      (s/check! (some #(re-find #"not checked" %) lines) (str "no warning says it was not checked\n" (s/show r)))
      (s/check! (some #(re-find #"(?i)no checkout|because|:.*:.*:" %) lines) (str "no warning says why\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx]
    (apply s/needs-built! f/check-stages)
    (f/ensure-shared-steps!)
    (let [ctx (stamped! ctx "@e2e @wip")]
      (f/generate-views! (:dir ctx))
      ctx)))

;; "it passes" is shared in wording with dhf/spec/project/init.feature, whose
;; scenarios run before this feature's: defined here, when loading, unless a
;; file loaded earlier defines it, so it is there for both and never twice.
(when-not (some #(re-matches (:pattern %) "it passes") @rdm-spec.kernel.runner/*registry*)
  (defstep #"it passes"
    (fn [ctx]
      (let [r (:run ctx)]
        (s/check! r "no command was run")
        (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))))

;; ---------------------------------------------------------------------------
;; No stack word

;; Shared in wording with dhf/spec/knowledge/wiki.feature: defined here only
;; when no file loaded earlier defines it, so the two never collide.
(when-not (some #(re-matches (:pattern %) "the configured stack words include \"Cobalt\"")
                @rdm-spec.kernel.runner/*registry*)
  (defstep #"the configured stack words include \"([^\"]+)\""
    (fn [ctx word]
      (let [ctx (f/ensure-room! ctx)]
        (f/config! (:dir ctx) (fn [c] (update c :stack-words #(vec (distinct (conj (vec %) word))))))
        (assoc ctx :stack-word word)))))

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [ctx (stamped! ctx "@e2e" {:description [(str "The parser is written in " word ".")]})
          line (some (fn [[i l]] (when (str/includes? l word) (inc i)))
                     (map-indexed vector (str/split-lines (:text ctx))))]
      (assoc ctx :word word :word-line line))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx]
    (f/fails-naming! ctx (str (:file ctx) ":" (:word-line ctx) ":") (:word ctx))))
