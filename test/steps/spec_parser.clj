(ns steps.spec-parser
  "Steps of the component Parser (dhf/spec/02-write-the-spec/parser.feature)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn n-scenarios [n]
  (k/feature-text "Thing" ["UN-001"] (for [i (range 1 (inc n))] {:id (format "spec-%04d" i) :title (str "Scenario " i)})))

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project"
  (fn [ctx] (assoc ctx ::cycle true)))

(defstep #"the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature" (n-scenarios 12)}})]
      (k/cut-project! ctx)
      (k/write! ctx "dhf/spec/demo/thing.feature" (n-scenarios 13))
      (k/write! ctx "dhf/spec/ids.edn" "{:issued 13}\n"))))

(defstep #"the checklist holds the 12 scenarios of spec-3"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [cl (json/parse-string (k/slurp-rel ctx "dhf/cycles/03/checklist.json"))]
      (k/check! (= (for [i (range 1 13)] (format "spec-%04d" i)) (map #(get % "id") (get cl "items"))) "the checklist holds " (pr-str (map #(get % "id") (get cl "items"))))
      ctx)))

(def every-construct
  "# A comment
@UN-001
Feature: Every construct
  A feature using every construct of the subset.

  Background:
    Given a background

  Rule: A rule

    @spec-0001 @e2e
    Scenario: A scenario
      Given a table
        | a | b |
        | 1 | 2 |
      When a doc string
        \"\"\"
        some text
        \"\"\"
      Then it holds
      And it still holds
      But nothing else

    @spec-0002 @contract
    Scenario Outline: An outline of <x>
      Given <x>
      Then it holds

      Examples:
        | x |
        | 1 |
        | 2 |
")

(defn one-file [ctx text]
  (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature" text} :ledger 2}))

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx] (one-file ctx every-construct)))

(defstep #"a feature file containing a line starting with \"Scenario Template:\""
  (fn [ctx]
    (one-file ctx "@UN-001\nFeature: Thing\n\n  @spec-0001 @e2e\n  Scenario: Fine\n    Given a thing\n\n  @spec-0002 @e2e\n  Scenario Template: Not in the subset\n    Given a thing\n")))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:9:" "Scenario Template") ctx))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (one-file ctx "@UN-001\nFeature: Thing\n\n  @spec-0001 @e2e\n  Scenario: Fine\n    Given a thing\n\nFeature: Another\n")))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:8:") ctx))

;; ------------------------------------------------------------------ several specs

(defn spec-folder!
  "Writes the spec NAME into dhf/sdd/NAME: a kernel component login, FEATURE its feature file."
  [ctx name feature & [{:keys [needs issued]}]]
  (let [d (str "dhf/sdd/" name)]
    (k/write-edn! ctx (str d "/model.edn") {:system {:id (keyword name) :name name :description "One spec"}
                                             :kernel [{:id :kernel/login :name "Login" :spec "auth/login" :description "Logs in"}]})
    (k/write-edn! ctx (str d "/events.edn") {})
    (k/write-edn! ctx (str d "/needs.edn") (or needs {"UN-001" {:text "A need" :sources ["demo:README.md@abc1234"]}}))
    (k/write! ctx (str d "/glossary.md") "# Glossary\n")
    (k/write! ctx (str d "/ids.edn") (str "{:issued " (or issued (k/issued-of {"f" feature})) "}\n"))
    (k/write! ctx (str d "/auth/login.feature") feature)
    ctx))

(defn feature-of [prefix need ids & [unstamped]]
  (str "@" need "\nFeature: Login\n"
       (str/join (for [i ids] (k/scenario-text {:id (format "%s-%04d" prefix i) :title (str "Login " i)})))
       (when unstamped "\n  @e2e\n  Scenario: Not stamped yet\n    Given a thing\n")))

(defn two-specs!
  "A project of two specs, auth and infra, under dhf/sdd."
  [ctx & [{:keys [auth infra auth-opts infra-opts]}]]
  (let [ctx (k/spec-project! ctx {:features {}})]
    (k/delete! ctx "dhf/spec/demo")
    (spec-folder! ctx "auth" (or auth (feature-of "auth" "UN-001" [1 2])) auth-opts)
    (spec-folder! ctx "infra" (or infra (feature-of "infra" "UN-001" [1])) infra-opts)
    (k/config! ctx assoc :specs [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}])
    ctx))

(defstep #"a configuration whose :specs is \[\{:name \"auth\" :dir \"dhf/sdd/auth\" :id-prefix \"auth\"\} \{:name \"infra\" :dir \"dhf/sdd/infra\" :id-prefix \"infra\"\}\]"
  (fn [ctx] (two-specs! ctx)))

(defstep #"each spec is read from its folder: its feature files, model.edn, events.edn, needs.edn, glossary.md, ids.edn, and its domain.edn where it keeps one"
  (fn [ctx]
    (k/exits! ctx 0)
    (do
      (k/write! ctx "dhf/sdd/infra/model.edn" "{:broken")
      (let [r (k/run-rdm ctx "check")]
        (k/exits! r 1)
        (k/err-has! r "dhf/sdd/infra/model.edn"))
      (k/write! ctx "dhf/sdd/infra/model.edn" (pr-str {:system {:id :infra :name "infra" :description "One spec"}
                                                        :kernel [{:id :kernel/login :name "Login" :spec "auth/login" :description "Logs in"}]}))
      (k/write! ctx "dhf/sdd/infra/ids.edn" "{:issued 0}\n")
      (let [r (k/run-rdm ctx "check")]
        (k/exits! r 1)
        (k/err-line-has! r "infra-0001" "ledger")))
    ctx))

(defstep #"a configuration with no :specs reads one spec, from :spec-dir and the paths it names, as before"
  (fn [ctx]
    (let [one (k/run-rdm (k/spec-project! (dissoc ctx :dir :base)) "check")]
      (k/exits! one 0)
      ctx)))

(defstep #"a configuration whose :specs (two specs named \"auth\"|two specs with the id prefix \"auth\"|two specs in one folder|a spec whose folder does not exist)"
  (fn [ctx holds]
    (let [ctx (two-specs! ctx)]
      (k/config! ctx assoc :specs
                 (case holds
                   "two specs named \"auth\"" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "auth" :dir "dhf/sdd/infra" :id-prefix "infra"}]
                   "two specs with the id prefix \"auth\"" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "auth"}]
                   "two specs in one folder" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/auth" :id-prefix "infra"}]
                   "a spec whose folder does not exist" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/nowhere" :id-prefix "infra"}]))
      ctx)))

(defstep #"it exits 2 and says (a spec's name is declared once|an id prefix is declared once, so no two scenarios share an id|a folder holds one spec|names the spec and its folder)"
  (fn [ctx why]
    (k/exits! ctx 2)
    (if (= why "names the spec and its folder")
      (k/err-line-has! ctx "infra" "dhf/sdd/nowhere")
      (k/err-has! ctx why))
    ctx))

(defstep #"two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds"
  (fn [ctx]
    (two-specs! ctx {:infra (feature-of "infra" "UN-001" [1])
                     :infra-opts {:needs {"UN-002" {:text "Another" :sources ["demo:README.md@abc1234"]}}}})))

(defstep #"it exits 1 with one finding, naming infra's feature file and the need, and none about auth"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [fs (remove #(re-find #"warning: " %) (k/err-lines ctx))]
      (k/check! (= 1 (count fs)) "the findings are " (pr-str fs))
      (k/check! (and (str/includes? (first fs) "dhf/sdd/infra/auth/login.feature") (str/includes? (first fs) "UN-001")) "the finding is " (first fs))
      (k/check! (not-any? #(str/includes? % "dhf/sdd/auth/") fs) "a finding names auth"))
    ctx))

(defstep #"a need, a component and a context resolve within their own spec only"
  (fn [ctx]
    (k/write-edn! ctx "dhf/sdd/infra/model.edn" {:system {:id :infra :name "infra" :description "One spec"}
                                                  :kernel [{:id :kernel/other :name "Other" :spec "auth/other" :description "Else"}]})
    (k/write-edn! ctx "dhf/sdd/infra/needs.edn" {"UN-001" {:text "A need" :sources ["demo:README.md@abc1234"]}})
    (let [r (k/run-rdm ctx "check")]
      (k/exits! r 1)
      (k/err-line-has! r "dhf/sdd/infra/auth/login.feature"))
    ctx))

(defstep #"two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario"
  (fn [ctx]
    (two-specs! ctx {:auth (feature-of "auth" "UN-001" [1 2 3] true)
                     :infra (feature-of "infra" "UN-001" [] true)
                     :infra-opts {:issued 0}})))

(defstep #"auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (str/includes? (k/slurp-rel ctx "dhf/sdd/auth/auth/login.feature") "@auth-0004") "auth's was not stamped @auth-0004")
    (k/check! (str/includes? (k/slurp-rel ctx "dhf/sdd/infra/auth/login.feature") "@infra-0001") "infra's was not stamped @infra-0001")
    (k/check! (= {:issued 4} (edn/read-string (k/slurp-rel ctx "dhf/sdd/auth/ids.edn"))) "auth's ledger is not 4")
    (k/check! (= {:issued 1} (edn/read-string (k/slurp-rel ctx "dhf/sdd/infra/ids.edn"))) "infra's ledger is not 1")
    ctx))

(defn tests-script
  "A test command that runs the scenarios of SPEC-DIR only, with rdm-spec run, into RESULTS_DIR."
  [spec-name]
  (str "#!/bin/sh\n"
       "cat > only.clj <<'CLJ'\n"
       "(let [c (clojure.edn/read-string (slurp \"dhf/harness/config.edn\"))]\n"
       "  (spit \"dhf/harness/config.edn\" (pr-str (update c :specs (fn [ss] (filterv #(= " (pr-str spec-name) " (:name %)) ss))))))\n"
       "CLJ\n"
       "bb only.clj && exec " (str/join " " (k/rdm-command-line)) " run\n"))

(defstep #"two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is \[\{:spec \"auth\"\}\]"
  (fn [ctx]
    (let [ctx (two-specs! ctx)]
      (k/write! ctx "dhf/steps/any.clj" k/catch-all-steps)
      (k/write! ctx "src/kernel/login.clj" "(ns kernel.login)\n")
      (k/config! ctx assoc :steps-dir "dhf/steps" :implementation ["src"]
                 :code-layout {:context ["src/{context}/"] :component ["src/{context}/{component}.clj"]}
                 :verify [{:kind :tests :command ["sh" "tests.sh"]}])
      (k/write! ctx "tests.sh" (tests-script "auth"))
      (k/commit-tag! ctx "spec-3")
      (k/write-edn! ctx "dhf/cycles/03/cycle.edn" {:cut "spec-3" :scope [{:spec "auth"}]
                                                   :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent}
                                                   :spec-owner {:name "o" :actor :human}})
      (k/write! (assoc ctx :dir (str (:base ctx) "/room")) "src/kernel/login.clj" "(ns kernel.login)\n")
      (assoc-in ctx [:ph "ROOM"] (str (:base ctx) "/room")))))

(defstep #"\"rdm-spec checklist 03\" is run, and verify's tests check reads the cycle's results"
  (fn [ctx]
    (let [ctx (k/run-rdm ctx "checklist 03")]
      (assoc ctx ::checklist (json/parse-string (k/slurp-rel ctx "dhf/cycles/03/checklist.json"))))))

(defstep #"the checklist holds one item per stamped scenario of auth and none of infra"
  (fn [ctx]
    (k/check! (= ["auth-0001" "auth-0002"] (map #(get % "id") (get (::checklist ctx) "items"))) "the checklist holds " (pr-str (::checklist ctx)))
    ctx))

(defstep #"verify requires a result for every scenario of auth, and none for infra's"
  (fn [ctx]
    (let [r (k/run-rdm ctx "verify ROOM 03")]
      (k/exits! r 0))
    (k/write! ctx "tests.sh" (tests-script "infra"))
    (k/git! ctx "tag" "-d" "spec-3")
    (k/commit-tag! ctx "spec-3")
    (let [r (k/run-rdm ctx "verify ROOM 03")]
      (k/exits! r 1)
      (k/err-has! r "auth-0001"))
    ctx))

(defstep #"two specs, auth with every scenario verified and infra with one unverified"
  (fn [ctx]
    (let [ctx (two-specs! ctx)
          d (str (:base ctx) "/r1")]
      (k/result-json! ctx d {:spec "auth-0001"})
      (k/result-json! ctx d {:spec "auth-0002"})
      (assoc-in ctx [:ph "R"] d))))

(defstep #"it (lists every scenario of both specs, each with its spec's name|refuses, naming infra's unverified scenario)"
  (fn [ctx answers]
    (case answers
      "lists every scenario of both specs, each with its spec's name"
      (let [ss (get-in ctx [:result :status :scenarios])]
        (k/exits! ctx 0)
        (k/check! (= #{["auth-0001" "auth"] ["auth-0002" "auth"] ["infra-0001" "infra"]} (set (map (juxt :id :spec) ss))) "status lists " (pr-str ss)))
      "refuses, naming infra's unverified scenario"
      (do (k/exits! ctx 1) (k/err-line-has! ctx "infra-0001" "unverified")))
    ctx))
