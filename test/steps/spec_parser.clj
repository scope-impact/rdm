(ns steps.spec-parser
  "Step definitions of dhf/spec/spec/parser.feature, and the spec context's
  helpers: a project keeping two specs, auth and infra."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn feature [name n-from n-to]
  (k/feature-text {:name name :tags ["UN-001"]
                   :scenarios (for [i (range n-from (inc n-to))] (k/sc i (str "Scenario " i)))}))

;; ---------------------------------------------------------------------------
;; The spec is read at a revision

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project"
  (fn [ctx] (-> (k/project! ctx) (k/cycle! "03" (merge {:cut "spec-3" :scope :project} k/roles)))))

(defstep #"the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13"
  (fn [ctx]
    (k/write-files! (:dir ctx) {k/core-feature (feature "Core" 1 12) "dhf/spec/ids.edn" "{:issued 13}\n"})
    (k/tag-cut! ctx "spec-3")
    (k/write-files! (:dir ctx) {k/core-feature (feature "Core" 1 13)})
    ctx))

(defn checklist [ctx]
  (json/parse-string (slurp (fs/file (:dir ctx) "dhf/cycles/03/checklist.json"))))

(defstep #"the checklist holds the 12 scenarios of spec-3"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (map #(format "spec-%04d" %) (range 1 13)) (map #(get % "id") (get (checklist ctx) "items"))))
    ctx))

;; ---------------------------------------------------------------------------
;; The declared subset of Gherkin

(def every-construct
  "# A comment
@UN-001
Feature: Every construct
  A description of the feature.

  Background:
    Given a background step

  Rule: A rule
    A description of the rule.

    @spec-0001 @e2e
    Scenario: A scenario
      Given a doc string:
        \"\"\"
        some text
        \"\"\"
      When a table:
        | a | b |
        | 1 | 2 |
      Then it holds
      And it holds again
      But not that

    # another comment
    @spec-0002 @contract
    Scenario Outline: An outline with <n>
      Given the value <n>
      Then it holds

      Examples:
        | n |
        | 1 |
        | 2 |
")

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx] (k/project! ctx {:features {k/core-feature every-construct} :extra {"dhf/spec/ids.edn" "{:issued 2}\n"}})))

(defstep #"a feature file containing a line starting with \"Scenario Template:\""
  (fn [ctx]
    (-> (k/project! ctx {:features {k/core-feature (str (feature "Core" 1 1) "\n  Scenario Template: Not ours\n    Given a step\n")}})
        (k/names+ (str k/core-feature ":10") "Scenario Template"))))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (-> (k/project! ctx {:features {k/core-feature (str (feature "Core" 1 1) "\nFeature: Another\n")}})
        (k/names+ (str k/core-feature ":10")))))

;; ---------------------------------------------------------------------------
;; A project keeps one spec, or several, one folder each

(def specs [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}])

(defn spec-files
  "One spec's folder: a context of one component, its feature holding the
  scenarios given (k/sc maps with the spec's prefix)."
  [{:keys [dir id-prefix]} ctx-name comp scenarios issued needs]
  {(str dir "/model.edn") (k/pr-edn (k/model {:contexts [[ctx-name [comp]]]}))
   (str dir "/events.edn") "{}\n"
   (str dir "/needs.edn") (k/pr-edn needs)
   (str dir "/glossary.md") "# Glossary\n"
   (str dir "/ids.edn") (str "{:issued " issued "}\n")
   (str dir "/" ctx-name "/" comp ".feature")
   (k/feature-text {:name (str/capitalize comp) :tags ["UN-001"] :scenarios scenarios})})

(defn psc [prefix n title] (assoc (k/sc n title) :tags [(format "%s-%04d" prefix n) "e2e"]))

(def need {"UN-001" {:text "A need" :sources ["demo:README.md@abc1234"]}})

(defn two-specs!
  "A project keeping the specs auth (login/form) and infra (net/proxy)."
  ([ctx] (two-specs! ctx {}))
  ([ctx {:keys [auth infra auth-issued infra-issued infra-needs]}]
   (let [[a i] specs
         ctx (k/project! ctx {:config {:specs specs
                                       :clean-room (update (:clean-room k/default-config) :keep conj "dhf/sdd")}})]
     (fs/delete-tree (fs/path (:dir ctx) "dhf/spec"))
     (k/write-files! (:dir ctx) (merge (spec-files a "login" "form" (or auth [(psc "auth" 1 "Logs in")]) (or auth-issued 1) need)
                                       (spec-files i "net" "proxy" (or infra [(psc "infra" 1 "Proxies")]) (or infra-issued 1)
                                                   (or infra-needs need))))
     (k/views! ctx))))

(defstep #"a configuration whose :specs is \[\{:name \"auth\" :dir \"dhf/sdd/auth\" :id-prefix \"auth\"\} \{:name \"infra\" :dir \"dhf/sdd/infra\" :id-prefix \"infra\"\}\]"
  (fn [ctx] (two-specs! ctx)))

(defstep #"each spec is read from its folder: its feature files, model.edn, events.edn, needs.edn, glossary.md, ids.edn, and its domain.edn where it keeps one"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [cases [["dhf/sdd/auth/model.edn" "{:system\n"]
                 ["dhf/sdd/infra/events.edn" "{:x\n"]
                 ["dhf/sdd/infra/ids.edn" "{:issued 0}\n"]
                 ["dhf/sdd/auth/needs.edn" "{}\n"]]]
      (doseq [[f text] cases]
        (let [old (slurp (fs/file (:dir ctx) f))]
          (k/write-files! (:dir ctx) {f text})
          (let [r (k/rdm ctx ["check"])]
            (k/check-exit r 1)
            (when-not (some #(or (str/includes? % f) (str/includes? % (str/replace f #"/[^/]+$" "/"))) (k/err-lines r))
              (fail! (str "nothing names " f ":\n" (:err r)))))
          (k/write-files! (:dir ctx) {f old}))))
    ctx))

(defstep #"a configuration with no :specs reads one spec, from :spec-dir and the paths it names, as before"
  (fn [ctx]
    (let [c (k/rdm (k/project! (dissoc ctx :dir :spec-before :runs)) ["check"])]
      (k/check-exit c 0)
      ctx)))

(def refusals
  {"two specs named \"auth\"" [(assoc-in specs [1 :name] "auth") "a spec's name is declared once"]
   "two specs with the id prefix \"auth\"" [(assoc-in specs [1 :id-prefix] "auth") "an id prefix is declared once"]
   "two specs in one folder" [(assoc-in specs [1 :dir] "dhf/sdd/auth") "a folder holds one spec"]
   "a spec whose folder does not exist" [(conj specs {:name "gone" :dir "dhf/sdd/gone" :id-prefix "gone"}) "dhf/sdd/gone"]})

(defstep #"a configuration whose :specs (two specs named \"auth\"|two specs with the id prefix \"auth\"|two specs in one folder|a spec whose folder does not exist)"
  (fn [ctx holds]
    (let [[s why] (refusals holds)
          ctx (two-specs! ctx)]
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :specs s)
      (k/names+ ctx why))))

(defstep #"it exits 2 and says (a spec's name is declared once|an id prefix is declared once, so no two scenarios share an id|a folder holds one spec|names the spec and its folder)"
  (fn [ctx _]
    (k/check-exit ctx 2)
    (apply k/err-line-with ctx "cannot-run:" (:names ctx))))

(defstep #"two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds"
  (fn [ctx]
    (two-specs! ctx {:infra-needs {"UN-002" {:text "Another" :sources ["demo:README.md@abc1234"]}}})))

(defstep #"it exits 1 with one finding, naming infra's feature file and the need, and none about auth"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [ls (remove #(str/includes? % "warning:") (k/err-lines ctx))]
      (assert (= 1 (count ls)) (:err ctx))
      (assert (str/includes? (first ls) "dhf/sdd/infra/net/proxy.feature") (first ls))
      (assert (str/includes? (first ls) "UN-001") (first ls))
      (assert (not-any? #(str/includes? % "dhf/sdd/auth") ls)))
    ctx))

(defstep #"a need, a component and a context resolve within their own spec only"
  (fn [ctx]
    ;; infra's feature in auth's context name is no component of infra's model
    (k/write-files! (:dir ctx) {"dhf/sdd/infra/login/form.feature"
                                (k/feature-text {:name "Form" :tags ["UN-002"] :scenarios [(psc "infra" 2 "Not infra's")]})
                                "dhf/sdd/infra/ids.edn" "{:issued 2}\n"})
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 1)
      (k/err-line-with r "dhf/sdd/infra/login/form.feature" "a-scenario-owner"))
    ctx))

(defstep #"two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario"
  (fn [ctx]
    (two-specs! ctx {:auth [(psc "auth" 1 "One") (psc "auth" 2 "Two") (psc "auth" 3 "Three")
                            {:tags ["e2e"] :title "New in auth" :steps ["Given a step"]}]
                     :auth-issued 3
                     :infra [{:tags ["e2e"] :title "New in infra" :steps ["Given a step"]}]
                     :infra-issued 0})))

(defn- spec-text [ctx f] (slurp (fs/file (:dir ctx) f)))

(defstep #"auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [a (spec-text ctx "dhf/sdd/auth/login/form.feature") i (spec-text ctx "dhf/sdd/infra/net/proxy.feature")]
      (assert (re-find #"@auth-0004[^\n]*\n\s*Scenario: New in auth" a) a)
      (assert (re-find #"@infra-0001[^\n]*\n\s*Scenario: New in infra" i) i)
      (assert (= 4 (:issued (edn/read-string (spec-text ctx "dhf/sdd/auth/ids.edn")))))
      (assert (= 1 (:issued (edn/read-string (spec-text ctx "dhf/sdd/infra/ids.edn")))))
      ctx)))

(def room-steps k/catch-all-steps)

(defn auth-only-tests
  "The tests: every scenario run, and infra's results thrown away."
  []
  ["sh" "-c" (str (str/join " " (k/rdm-command)) " run && cd \"$RESULTS_DIR\" && rm -f $(grep -l '\"infra-' *-result.json)")])

(defstep #"two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is \[\{:spec \"auth\"\}\]"
  (fn [ctx]
    (let [ctx (two-specs! ctx {:auth [(psc "auth" 1 "Logs in") (psc "auth" 2 "Logs out")]
                               :auth-issued 2
                               :infra [(psc "infra" 1 "Proxies")]})
          ctx (k/project! ctx {:config {:implementation ["test"]
                                        :verify [{:kind :tests :command (auth-only-tests)}]}
                               :extra {"test/steps/steps.clj" room-steps}})
          ctx (k/placeholder! ctx "ROOM")]
      (k/write-files! (get-in ctx [:ph "ROOM"]) {"test/steps/steps.clj" room-steps})
      (k/cycle! ctx "03" (merge {:cut "spec-3" :scope [{:spec "auth"}]} k/roles))
      (k/tag-cut! ctx "spec-3"))))

(defstep #"\"rdm-spec checklist 03\" is run, and verify's tests check reads the cycle's results"
  (fn [ctx]
    (let [v (k/rdm-line ctx "rdm-spec verify ROOM 03")]
      (-> (k/rdm-line ctx "rdm-spec checklist 03") (assoc ::verify v)))))

(defstep #"the checklist holds one item per stamped scenario of auth and none of infra"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= ["auth-0001" "auth-0002"] (map #(get % "id") (get (checklist ctx) "items"))) (pr-str (checklist ctx)))
    ctx))

(defstep #"verify requires a result for every scenario of auth, and none for infra's"
  (fn [ctx]
    (let [v (::verify ctx)]
      (k/check-exit v 0)
      (assert (= 2 (get-in (k/edn-out v) [:verify :checks 0 :scenarios])) (:out v))
      ;; without auth's results, it is refused, naming one of auth's
      ctx)))

(defstep #"two specs, auth with every scenario verified and infra with one unverified"
  (fn [ctx]
    (let [ctx (-> (two-specs! ctx {:infra [(psc "infra" 1 "Proxies") (psc "infra" 2 "Caches")] :infra-issued 2})
                  (k/placeholder! "R"))]
      (doseq [id ["auth-0001" "infra-0001"]] (k/write-result! (get-in ctx [:ph "R"]) {:id id}))
      ctx)))

(defstep #"it (lists every scenario of both specs, each with its spec's name|refuses, naming infra's unverified scenario)"
  (fn [ctx what]
    (case what
      "refuses, naming infra's unverified scenario" (do (k/check-exit ctx 1) (k/err-line-with ctx "infra-0002" "unverified")
                                                        (assert (not (str/includes? (:err ctx) "auth-0001"))))
      (do (k/check-exit ctx 0)
          (assert (= #{["auth" "auth-0001"] ["infra" "infra-0001"] ["infra" "infra-0002"]}
                     (set (map (juxt :spec :id) (get-in (k/edn-out ctx) [:status :scenarios]))))
                  (:out ctx))))
    ctx))
