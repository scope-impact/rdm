(ns steps.kernel-steps
  "The kernel's shared steps and fixture helpers. A step text used by the
  features of more than one context is defined here, once; each names the
  context keys it sets. Every fixture is a directory this file makes under
  TMPDIR, fenced from any enclosing repository, and removed after each scenario.

  Context keys (the kernel's):
    :dir    the project the scenario's commands run in (absolute)
    :top    the fixture's own top directory (the project's parent, or a monorepo root)
    :env    extra environment for the scenario's commands
    :exit :out :err   the last command's exit, standard output and error stream
    :edn    the last command's standard output read as EDN (nil when it is not)
    :vars   placeholders (ROOM, DIR, OUT, R, ...) to absolute paths
    :runs   every command run, in order: [{:args :exit :out :err}]"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint]
            [clojure.walk]
            [rdm-spec.kernel.runner :refer [defstep fail! attach!]]))

(defonce root (atom (str (fs/cwd))))
(defonce fixtures (atom []))
(defonce processes (atom []))

(defn tmp-root [] (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn cleanup!
  "Stops every process a scenario started and removes every fixture it made."
  []
  (doseq [pr @processes] (try (p/destroy-tree pr) (catch Exception _ nil)))
  (reset! processes [])
  (doseq [d @fixtures] (try (when (fs/exists? d) (fs/delete-tree d)) (catch Exception _ nil)))
  (reset! fixtures []))

(defn track-process! [pr] (swap! processes conj pr) pr)

(defn top-dir!
  "A new fixture directory; its parent is fenced so git finds no enclosing repository."
  []
  (let [d (str (fs/create-temp-dir {:dir (tmp-root) :prefix "fx-"}))]
    (swap! fixtures conj d)
    d))

(defn path [& parts] (str (apply fs/path parts)))

(defn write!
  "Writes text to path under dir, making its parents."
  [dir rel text]
  (let [f (fs/path dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str text))
    (str f)))

(defn slurp-in [dir rel] (let [f (fs/path dir rel)] (when (fs/exists? f) (slurp (str f)))))

(def git-env {"GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.invalid"
              "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.invalid"})

(defn base-env
  "The tests' environment, minus RESULTS_DIR, plus what the fixture needs."
  [ceiling extra]
  (merge (dissoc (into {} (System/getenv)) "RESULTS_DIR")
         git-env
         {"GIT_CEILING_DIRECTORIES" (str ceiling) "TMPDIR" (tmp-root)}
         extra))

(defn git!
  "git in dir, hooks off; fails the step when git fails."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                    :env (base-env (fs/parent dir) {})}
                   (into ["git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                          "-c" "init.defaultBranch=main"] (map str args)))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {})))
    (str/trim (:out r))))

(defn commit-all!
  "Commits everything in the repository at dir (hooks off); tags it when tags are given."
  [dir msg & tags]
  (git! dir "add" "-A" "-f" ".")
  (git! dir "commit" "-q" "--allow-empty" "--no-verify" "-m" msg)
  (doseq [t tags] (git! dir "tag" "-f" t))
  (git! dir "rev-parse" "HEAD"))

(defn tokens
  "Splits a command line on spaces, keeping 'single quoted' words whole."
  [s]
  (vec (map #(if (str/starts-with? % "'") (subs % 1 (dec (count %))) %)
            (re-seq #"'[^']*'|\S+" s))))

(defn rdm-argv []
  ["bb" "-cp" (str @root "/src") "-m" "rdm-spec.kernel.command"])

(defn read-edn-safe [s] (try (let [t (str/trim (str s))] (when-not (str/blank? t) (edn/read-string t))) (catch Exception _ nil)))

(defn run-rdm
  "Runs rdm-spec with args (a vector, or a string split into words) in dir.
  Returns {:exit :out :err :edn}."
  [args {:keys [dir env in ceiling timeout argv]}]
  (let [argv (into (or argv (rdm-argv)) (if (string? args) (tokens args) args))
        t0 (System/nanoTime)
        pr (p/process argv {:dir (str dir) :out :string :err :string :in (or in "")
                            :env (base-env (or ceiling (fs/parent dir)) env)})
        r (if timeout
            (let [x (deref pr (* 1000 timeout) ::timeout)]
              (if (= x ::timeout) (do (p/destroy-tree pr) (throw (ex-info (str "rdm-spec " args " ran past " timeout "s") {}))) x))
            @pr)
        res {:exit (:exit r) :out (:out r) :err (:err r) :edn (read-edn-safe (:out r)) :elapsed (/ (- (System/nanoTime) t0) 1e9)}]
    (attach! (str "rdm-spec " (if (string? args) args (str/join " " args)))
             (str "exit " (:exit r) "\n--- out\n" (:out r) "\n--- err\n" (:err r)))
    res))

(defn subst
  "Replaces placeholders (ROOM, DIR, OUT, ...) in a command line with the scenario's paths."
  [ctx s]
  (reduce (fn [s [k v]] (str/replace s (re-pattern (str "(?<![A-Za-z0-9_])" (java.util.regex.Pattern/quote k) "(?![A-Za-z0-9_])")) (str v)))
          s (sort-by (comp - count key) (:vars ctx))))

(defn rdm!
  "Runs rdm-spec in the scenario's project and records the run in the context."
  ([ctx args] (rdm! ctx args {}))
  ([ctx args opts]
   (let [args (if (string? args) (subst ctx args) args)
         r (run-rdm args (merge {:dir (:dir ctx) :env (:env ctx) :ceiling (:top ctx) :argv (:argv ctx)} opts))]
     (-> ctx (merge r) (update :runs (fnil conj []) (assoc r :args args))))))

(defn var-dir!
  "A placeholder directory (ROOM, DIR, OUT, ...) of the scenario: a path that does not exist yet, beside the project."
  [ctx k]
  (if (get-in ctx [:vars k])
    ctx
    (let [d (str (fs/path (or (:top ctx) (top-dir!)) (str (str/lower-case k) "-" (subs (str (random-uuid)) 0 8))))]
      (assoc-in ctx [:vars k] d))))

;; ------------------------------------------------------------------ projects

(defn init-project!
  "A project set up by rdm-spec init, in a new fixture; hooks off; returns its directory."
  ([] (init-project! "proj" []))
  ([name extra-args]
   (let [top (top-dir!)
         r (run-rdm (into ["init" name] extra-args) {:dir top :ceiling top})]
     (when-not (zero? (:exit r)) (throw (ex-info (str "init failed: " (:err r)) {})))
     (let [d (path top name)]
       (git! d "config" "core.hooksPath" "/dev/null")
       d))))

(defn edit-edn!
  "Rewrites an EDN file under dir with f."
  [dir rel f]
  (let [v (edn/read-string (slurp (path dir rel)))]
    (write! dir rel (binding [*print-namespace-maps* false] (with-out-str (clojure.pprint/pprint (f v)))))))

(defn config [dir] (edn/read-string (slurp (path dir "dhf/harness/config.edn"))))
(defn edit-config! [dir f] (edit-edn! dir "dhf/harness/config.edn" f))

(defn project-ctx
  "A context for a project at dir in fixture top."
  [dir]
  {:dir dir :top (str (fs/parent dir)) :vars {}})

(defn new-project
  "A context for a new project set up by rdm-spec."
  []
  (project-ctx (init-project!)))

(def kept-of-own
  "The paths a clean room of rdm-spec's own project holds (its configuration's :keep)."
  [".gitignore" "AGENTS.md" "CLAUDE.md" "LICENSE.txt" "README.md" "docs"
   "dhf/decisions" "dhf/harness" "dhf/raw" "dhf/spec" "dhf/storm" "dhf/views" "dhf/wiki" "plugins"])

(defn copy-own-project!
  "A copy of rdm-spec's own project: the paths its configuration keeps, as a
  clean room holds them, its configuration naming no plugin (the oracle is the
  scenario's to set) and no source fixture outside it. A git repository with one commit."
  []
  (let [top (top-dir!) d (path top "own")]
    (fs/create-dirs d)
    (doseq [k kept-of-own
            :let [src (fs/path @root k)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/path d k))
        (do (fs/create-dirs (fs/parent (fs/path d k))) (fs/copy src (fs/path d k)))))
    (when (fs/exists? (fs/path d "dhf/harness/oracle")) (fs/delete-tree (fs/path d "dhf/harness/oracle")))
    (edit-config! d #(-> % (dissoc :plugins) (update-in [:clean-room :keep] (fn [k] (vec k)))))
    (git! d "init" "-q")
    (commit-all! d "own project")
    (project-ctx d)))

;; ------------------------------------------------------------------ a small project with a spec

(defn canon
  "A form printed as the model hash prints it: maps and sets sorted by the printed form."
  [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canon k) " " (canon v))) (sort-by (fn [[k _]] (canon k)) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map canon x))) "}")
        (vector? x) (str "[" (str/join " " (map canon x)) "]")
        (seq? x) (str "(" (str/join " " (map canon x)) ")")
        :else (binding [*print-namespace-maps* false] (pr-str x))))

(defn sha256 [s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest d (.getBytes (str s) "UTF-8"))))))

(defn model-hash [dir rel] (sha256 (canon (edn/read-string (slurp (path dir rel))))))

(defn fresh-views!
  "Writes a view file pair for every view of the model, each recording the model's hash."
  ([dir] (fresh-views! dir "dhf/spec/model.edn" "dhf/views"))
  ([dir model-rel views-dir]
   (let [m (edn/read-string (slurp (path dir model-rel)))
         h (model-hash dir model-rel)]
     (doseq [{k :key} (:views m)]
       (write! dir (str views-dir "/" k ".mmd") (str "%% model-sha256: " h "\nC4Context\n"))
       (write! dir (str views-dir "/" k ".svg") (str "<!-- model-sha256: " h " -->\n<svg></svg>\n"))))))

(def shop-model
  {:system {:id :shop-system :name "Shop" :description "Sells things"}
   :people [{:id :buyer :name "Buyer" :actor #{:human} :description "Buys things"}]
   :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The shop's one program"
                 :contexts [{:id :shop :name "shop" :description "Selling"
                             :components [{:id :shop/cart :name "Cart" :description "Holds what is bought" :spec "shop/cart"}
                                          {:id :shop/till :name "Till" :description "Takes the money" :spec "shop/till"}]}]}]
   :relationships [{:from :buyer :to :app :description "Buys with"}]
   :views [{:key "shop-components" :level :component :context :shop :description "The shop's components"}]})

(def default-needs
  {"UN-001" {:text "A buyer buys." :sources ["loyly:README.md@17d68d2"]}
   "UN-002" {:text "A buyer pays." :sources ["loyly:README.md@17d68d2"]}
   "UN-003" {:text "A buyer sees." :sources ["loyly:README.md@17d68d2"]}})

(defn pp [x] (binding [*print-namespace-maps* false] (with-out-str (clojure.pprint/pprint x))))

(defn scenario-text
  "A scenario's lines: {:id :level :title :steps :tags :outline (rows)}."
  [{:keys [id level title steps tags examples wip indent]
    :or {level "e2e" steps ["Given a thing" "Then it holds"] indent "  "}}]
  (str indent (str/join " " (concat (when id [(str "@" id)]) (when level [(str "@" level)]) (map #(str "@" %) tags) (when wip ["@wip"]))) "\n"
       indent (if examples "Scenario Outline: " "Scenario: ") (or title (str "Scenario " id)) "\n"
       (str/join (for [s steps] (str indent "  " s "\n")))
       (when examples
         (str "\n" indent "  Examples:\n"
              (str/join (for [r examples] (str indent "    | " (str/join " | " r) " |\n")))))
       "\n"))

(defn feature-text
  "A feature file: {:name :tags :scenarios [scenario ...] :rule}."
  [{:keys [name tags scenarios rule] :or {tags ["UN-001"]}}]
  (str (when (seq tags) (str (str/join " " (map #(str "@" %) tags)) "\n"))
       "Feature: " (or name "A feature") "\n\n"
       (when rule (str "  Rule: " rule "\n\n"))
       (str/join (map #(scenario-text (if rule (assoc % :indent "    ") %)) scenarios))))

(defn spec-project!
  "A project set up by rdm-spec whose spec is the shop: the model above, needs
  UN-001 to UN-003 citing a recorded source, fresh views, and the given
  feature files {rel text} under dhf/spec. Returns its context."
  ([] (spec-project! {}))
  ([features & {:keys [model needs issued no-till prefix] :or {model shop-model needs default-needs}}]
   (let [ctx (if prefix (project-ctx (init-project! "proj" ["--id-prefix" prefix])) (new-project)) d (:dir ctx)
         features (if (or no-till (contains? features "shop/till.feature") (not= model shop-model))
                    features
                    (assoc features "shop/till.feature"
                           (feature-text {:name "Till" :scenarios [{:id (str (or prefix "spec") "-0098") :title "Till takes the money"}]})))]
     (write! d "dhf/spec/model.edn" (pp model))
     (write! d "dhf/spec/needs.edn" (pp needs))
     (write! d "dhf/raw/sources.edn" (pp {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []}}))
     (write! d "dhf/raw/loyly.md" "# loyly\n")
     (doseq [[rel text] features] (write! d (str "dhf/spec/" rel) text))
     (write! d "dhf/spec/ids.edn" (pr-str {:issued (or issued 99)}))
     (fresh-views! d)
     ctx)))

;; The step definitions of a fixture project whose every step passes, for
;; "rdm-spec run" as a fixture's :tests command.
(def catch-all-steps
  "(require '[rdm-spec.kernel.runner :refer [defstep]])\n(defstep #\".*\" (fn [ctx & _] ctx))\n")

;; ------------------------------------------------------------------ slices and folders

(def plan-text
  "# Slice 12: Several specs\n\nstatus: building\n\n## Goal\n\nSeveral specs.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- the parser\n\n**Out:**\n\n- nothing else\n\n## Decisions\n\n`decisions.md`.\n")

(def slices-model
  {:system {:id :shop-system :name "Shop" :description "Sells things"}
   :people [{:id :buyer :name "Buyer" :actor #{:human} :description "Buys things"}]
   :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The one program"
                 :contexts [{:id :spec :name "spec" :description "The spec"
                             :components [{:id :spec/parser :name "Parser" :description "Reads" :spec "02-write-the-spec/parser"}
                                          {:id :spec/stamper :name "Stamper" :description "Stamps" :spec "02-write-the-spec/stamper"}]}
                            {:id :measurement :name "measurement" :description "Measures"
                             :components [{:id :measurement/checklist :name "Checklist" :description "Lists" :spec "07-measure/checklist"}]}]}]
   :relationships [{:from :buyer :to :app :description "Uses"}]
   :views [{:key "spec-components" :level :component :context :spec :description "The spec"}
           {:key "measurement-components" :level :component :context :measurement :description "Measures"}]})

(defn slices-project!
  "A project under :spec-layout :slices: slice 02-write-the-spec (parser, stamper),
  07-measure (checklist) and 12-several-specs (parser, stamper, checklist), each
  planned with its decisions; extra files {rel text} under dhf/spec."
  ([] (slices-project! {}))
  ([files & {:keys [model]}]
   (let [ctx (spec-project! {} :model (or model slices-model) :no-till true) d (:dir ctx)
         sc (fn [ids] (vec (for [i ids] {:id (format "spec-%04d" i) :title (str "Scenario " i)})))]
     (edit-config! d #(assoc % :spec-layout :slices))
     (fs/delete-tree (path d "dhf/spec/shop"))
     (doseq [[slice comps] [["02-write-the-spec" {"parser" [1 2 3] "stamper" [4]}]
                            ["07-measure" {"checklist" [5 6]}]
                            ["12-several-specs" {"parser" [7 8] "stamper" [9] "checklist" [10]}]]]
       (write! d (str "dhf/spec/" slice "/plan.md") (str/replace plan-text "Slice 12: Several specs" (str "Slice " slice)))
       (write! d (str "dhf/spec/" slice "/decisions.md") "# Decisions\n\nNone yet.\n")
       (doseq [[c ids] comps]
         (write! d (str "dhf/spec/" slice "/" c ".feature") (feature-text {:name c :scenarios (sc ids)}))))
     (doseq [[rel text] files] (if text (write! d (str "dhf/spec/" rel) text) (fs/delete-if-exists (path d (str "dhf/spec/" rel)))))
     ctx)))


(defn wallet-repo!
  "A repository whose project is its folder wallet/: two cuts, spec-2 and
  spec-3, changing the scenarios of one component, a cycle 03 naming spec-3,
  and decoys outside wallet/ at the paths a root project would use."
  []
  (let [top (top-dir!) repo (path top "repo") w (path repo "wallet")
        init (run-rdm ["init" "wallet"] {:dir (doto repo fs/create-dirs) :ceiling top})]
    (when-not (zero? (:exit init)) (throw (ex-info (:err init) {})))
    (fs/delete-tree (fs/path w ".git"))
    (git! repo "init" "-q")
    (write! w "dhf/spec/model.edn" (pp shop-model))
    (write! w "dhf/spec/needs.edn" (pp default-needs))
    (write! w "dhf/raw/sources.edn" (pp {:loyly {:repository "r" :record "loyly.md" :pins ["17d68d2"] :fenced []}}))
    (write! w "dhf/raw/loyly.md" "# loyly\n")
    (write! w "dhf/spec/ids.edn" "{:issued 9}")
    (edit-config! w #(assoc % :implementation ["src" "test"]
                              :code-layout {:context ["src/{context}/" "test/steps/{context}_"] :component ["src/{context}/{component}.clj"]}
                              :verify [{:kind :tests :command (conj (rdm-argv) "run")}]))
    (fresh-views! w)
    (write! w "src/shop/cart.clj" "(ns cart)\n")
    (write! w "dhf/spec/shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]}))
    (write! w "dhf/spec/shop/till.feature" (feature-text {:name "Till" :scenarios [{:id "spec-0002"}]}))
    (write! repo "dhf/harness/config.edn" "{:decoy true}")
    (write! repo "dhf/spec/model.edn" "{:decoy :model}")
    (commit-all! repo "spec-2" "spec-2")
    (write! w "dhf/spec/shop/till.feature" (feature-text {:name "Till" :scenarios [{:id "spec-0002"} {:id "spec-0003" :title "More"}]}))
    (write! w "dhf/spec/ids.edn" "{:issued 9}")
    (commit-all! repo "spec-3" "spec-3")
    (write! w "dhf/cycles/03/cycle.edn" (pr-str {:cut "spec-3" :scope :project
                                                   :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent}
                                                   :spec-owner {:name "o" :actor :human}}))
    {:dir w :top top :repo repo :vars {}}))


;; ------------------------------------------------------------------ cycles

(def roles {:builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
            :spec-owner {:name "Sami" :actor :human}})

(defn cycle-edn! [dir n m] (write! dir (str "dhf/cycles/" n "/cycle.edn") (pp (merge roles m))))

(defn tests-check [] {:kind :tests :command (conj (rdm-argv) "run")})

(defn cycle-project!
  "A project ready to be verified: its spec (features, default the cart's spec-0001
  and spec-0002 and the till's spec-0098), :implementation src and test, its
  :tests check running rdm-spec run, committed and tagged spec-3, cycle 03
  naming spec-3; and a room ROOM whose step definitions pass every step."
  [& {:keys [features verify cfg room-steps no-cut model]}]
  (let [ctx (spec-project! (or features {"shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0002"}]})})
                           :model (or model shop-model))
        d (:dir ctx)
        room (path (:top ctx) "room")]
    (edit-config! d #(merge (assoc % :implementation ["src" "test"]
                                   :code-layout {:context ["src/{context}/" "test/steps/{context}_"] :component ["src/{context}/{component}.clj"]}
                                   :verify (or verify [(tests-check)]))
                            cfg))
    (git! d "init" "-q")
    (when-not no-cut (commit-all! d "spec-3" "spec-3"))
    (cycle-edn! d "03" {:cut "spec-3" :scope :project})
    (write! room "src/shop/app.clj" "(ns shop.app)\n")
    (write! room "test/steps/all.clj" (or room-steps catch-all-steps))
    (-> ctx (assoc-in [:vars "ROOM"] room))))

(defn recut!
  "Moves the tag spec-3 to a new commit of the project as it now stands."
  [ctx]
  (git! (:dir ctx) "tag" "-d" "spec-3")
  (commit-all! (:dir ctx) "spec-3" "spec-3")
  ctx)

(defn two-specs!
  "A project of two specs, auth and infra, under dhf/sdd, one folder each with
  its own model (one component each), needs, ledger and prefix."
  [& {:keys [auth-features infra-features auth-needs infra-needs auth-issued infra-issued]}]
  (let [ctx (new-project) d (:dir ctx)
        model (fn [n] {:system {:id (keyword (str n "-system")) :name n :description "d"}
                       :people [{:id :user :name "User" :actor #{:human} :description "d"}]
                       :containers [{:id :app :name "App" :kind :runs :technology "t" :description "d"
                                     :contexts [{:id (keyword n) :name n :description "d"
                                                 :components [{:id (keyword n "core") :name "Core" :description "d" :spec (str n "/core")}]}]}]
                       :relationships [{:from :user :to :app :description "uses"}]
                       :views [{:key (str n "-components") :level :component :context (keyword n) :description "d"}]})]
    (doseq [[n feats needs issued] [["auth" auth-features auth-needs auth-issued] ["infra" infra-features infra-needs infra-issued]]]
      (let [sd (str "dhf/sdd/" n)]
        (write! d (str sd "/model.edn") (pp (model n)))
        (write! d (str sd "/events.edn") "{}")
        (write! d (str sd "/needs.edn") (pp (or needs default-needs)))
        (write! d (str sd "/glossary.md") "# Glossary\n")
        (write! d (str sd "/ids.edn") (pr-str {:issued (or issued 9)}))
        (doseq [[rel text] feats] (write! d (str sd "/" rel) text))
        (fresh-views! d (str sd "/model.edn") (str "dhf/views/" n))))
    (write! d "dhf/raw/sources.edn" (pp {:loyly {:repository "r" :record "loyly.md" :pins ["17d68d2"] :fenced []}}))
    (write! d "dhf/raw/loyly.md" "# loyly\n")
    (edit-config! d #(-> % (assoc :specs [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"}
                                            {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}])
                           (update-in [:clean-room :keep] conj "dhf/sdd")))
    ctx))


(defn two-specs-cycle!
  "Two specs, auth (auth-0001, auth-0002) and infra (infra-0001), ready to be
  verified: committed and tagged spec-3, cycle 03 naming spec-3, and a room
  ROOM whose step definitions pass every step."
  []
  (let [ctx (two-specs! :auth-features {"auth/core.feature" (feature-text {:name "Core" :scenarios [{:id "auth-0001"} {:id "auth-0002"}]})}
                        :infra-features {"infra/core.feature" (feature-text {:name "Core" :scenarios [{:id "infra-0001"}]})})
        d (:dir ctx) room (path (:top ctx) "room")]
    (edit-config! d #(assoc % :implementation ["test"] :verify [(tests-check)]))
    (git! d "init" "-q")
    (commit-all! d "spec-3" "spec-3")
    (cycle-edn! d "03" {:cut "spec-3" :scope :project})
    (write! room "test/steps/all.clj" catch-all-steps)
    (assoc-in ctx [:vars "ROOM"] room)))

;; ------------------------------------------------------------------ packages

(defn build-jar!
  "rdm-spec packaged as a jar, as the way of working packages it: src/ and the
  plugin templates as classpath resources under rdm-spec/plugins/. Returns the jar's path."
  []
  (let [top (top-dir!) pkg (path top "pkg")]
    (fs/copy-tree (path @root "src") (path pkg "src"))
    (fs/create-dirs (path pkg "res/rdm-spec"))
    (fs/copy-tree (path @root "plugins") (path pkg "res/rdm-spec/plugins"))
    (let [r (p/shell {:dir pkg :out :string :err :string :continue true :env (base-env top {})}
                     "bb" "--classpath" "src:res" "uberjar" "rdm-spec.jar" "-m" "rdm-spec.kernel.command")]
      (when-not (zero? (:exit r)) (throw (ex-info (str "uberjar: " (:err r)) {}))))
    (fs/delete-tree (path pkg "src"))
    (fs/delete-tree (path pkg "res"))
    (path pkg "rdm-spec.jar")))

;; ------------------------------------------------------------------ plugins

(defn recorder
  "A plugin's script: records its input, environment, working directory and
  arguments under its name in the directory it runs in, then answers as told:
  {:exit n :err \"line\" :out \"line\" :sleep secs}."
  [n & [{:keys [exit err out sleep]}]]
  (str "(require '[clojure.string :as str])\n"
       "(let [in (slurp *in*) d (str \"plugin-" n "\")]\n"
       "  (.mkdirs (java.io.File. d))\n"
       "  (spit (str d \"/given.edn\") in)\n"
       "  (spit (str d \"/env.edn\") (pr-str (into {} (System/getenv))))\n"
       "  (spit (str d \"/pwd.txt\") (System/getProperty \"user.dir\"))\n"
       "  (spit (str d \"/args.edn\") (pr-str (vec *command-line-args*)))\n"
       "  (spit \"plugins-ran.txt\" (str \"" n " \" (System/nanoTime) \"\\n\") :append true))\n"
       (when out (str "(println " (pr-str out) ")\n(flush)\n"))
       (when err (str "(binding [*out* *err*] (println " (pr-str err) "))\n"))
       (when sleep (str "(Thread/sleep " (long (* 1000 sleep)) ")\n"))
       "(System/exit " (or exit 0) ")\n"))

(defn plugin!
  "Writes plugin n's script into the project and returns its :plugins entry."
  [ctx n entry & [answer]]
  (write! (:dir ctx) (str "dhf/harness/plugins/" n "/run.clj") (recorder n answer))
  (merge {:name n :command ["bb" (str "dhf/harness/plugins/" n "/run.clj")]} entry))

(defn plugins! [ctx entries] (edit-config! (:dir ctx) #(assoc % :plugins (vec entries))) ctx)

(defn plugin-file [ctx n f & [dir]] (slurp-in (or dir (:dir ctx)) (str "plugin-" n "/" f)))

(defn ran [ctx & [dir]] (vec (keep #(first (str/split % #" ")) (some-> (slurp-in (or dir (:dir ctx)) "plugins-ran.txt") str/split-lines))))

;; ------------------------------------------------------------------ the regulated profile

(declare result!)

(defn doc-text
  "A controlled document: frontmatter (id, revision, title, unless nil) and a body."
  [{:keys [id revision title body] :or {id "PLAN-001" revision "1" title "Development plan" body "We plan [[STD:5.1]].\n"}}]
  (str "---\n" (when id (str "id: " id "\n")) (when revision (str "revision: " revision "\n")) (when title (str "title: " title "\n")) "---\n\n"
       body))

(defn regulated-project!
  "A project set up by rdm-spec under the regulated profile: the clause list
  dhf/clauses/std.txt holding STD:5.1, and dhf/documents/plan.md naming it."
  [& {:keys [features clauses docs]}]
  (let [ctx (spec-project! (or features {"shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}))
        d (:dir ctx)]
    (edit-config! d #(-> % (assoc :regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})
                         (update-in [:clean-room :keep] into ["dhf/clauses" "dhf/documents"])))
    (write! d "dhf/clauses/std.txt" (or clauses "# The standard\nSTD:5.1 Development plan\n"))
    (doseq [[f t] (or docs {"plan.md" (doc-text {})})] (write! d (str "dhf/documents/" f) t))
    ctx))

(defn pass-all!
  "A passing result in the configured results directory for every stamped scenario of the cart and the till."
  [ctx ids]
  (doseq [id ids] (result! (path (:dir ctx) "dhf/allure-results") id "passed"))
  ctx)

(defn releasable!
  "A regulated project whose every scenario has a passing result, all committed."
  [& opts]
  (let [ctx (apply regulated-project! opts)]
    (pass-all! ctx ["spec-0001" "spec-0098"])
    (git! (:dir ctx) "init" "-q")
    (commit-all! (:dir ctx) "releasable")
    ctx))

;; ------------------------------------------------------------------ reading answers

(defn err-lines [ctx] (vec (remove str/blank? (str/split-lines (str (:err ctx))))))
(defn finding-lines [ctx] (vec (remove #(or (re-find #"(^|: )warning: " %) (str/starts-with? % "event: ")) (err-lines ctx))))
(defn warning-lines [ctx] (filterv #(re-find #"(^|: )warning: " %) (err-lines ctx)))

(defn expect!
  "Fails with what the command answered unless ok."
  [ok ctx what]
  (when-not ok
    (fail! (str what "; exit " (:exit ctx) "\nout: " (:out ctx) "\nerr: " (:err ctx)))))

(defn exits! [ctx code] (expect! (= code (:exit ctx)) ctx (str "expected exit " code)))

(defn err-has! [ctx & needles]
  (doseq [n needles] (expect! (str/includes? (str (:err ctx)) (str n)) ctx (str "the error stream does not name " n))))

(defn out-has! [ctx & needles]
  (doseq [n needles] (expect! (str/includes? (str (:out ctx)) (str n)) ctx (str "standard output does not name " n))))

(defn line-has!
  "Some one line of the error stream holds every needle."
  [ctx & needles]
  (expect! (some (fn [l] (every? #(str/includes? l (str %)) needles)) (err-lines ctx)) ctx
           (str "no line of the error stream holds all of " (pr-str needles))))

(defn finding-shape! [ctx]
  (doseq [l (err-lines ctx)]
    (expect! (re-matches #"^(\S+?:(\d+:)? )?(warning: )?[a-z][a-z0-9-]*: .+|^event: \S+ published-by \S+|^cannot-run: .+" l)
             ctx (str "a line not in the reporting shape: " l))))

(defn tree-snapshot
  "Every file under dir with its bytes' hash (.git excluded)."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [r (str (fs/relativize dir f))]
              :when (and (not (fs/directory? f)) (not (str/starts-with? r ".git/")) (not= r ".git"))]
          [r (if (fs/sym-link? f) (str "-> " (fs/read-link f)) (hash (slurp (str f))))])))

(defn json-file [f] (json/parse-string (slurp (str f))))

;; ------------------------------------------------------------------ Allure results for fixtures

(defn result!
  "Writes one Allure result into dir: spec id, status, and extra keys."
  ([dir spec status] (result! dir spec status {}))
  ([dir spec status extra]
   (fs/create-dirs dir)
   (let [uuid (str (random-uuid))
         f (path dir (str uuid "-result.json"))]
     (spit f (json/generate-string (merge {"uuid" uuid "name" (str spec) "status" status "stage" "finished"
                                           "labels" (if spec [{"name" "spec" "value" spec}] [])}
                                          extra)))
     f)))

;; ------------------------------------------------------------------ shared steps

;; Sets :dir :top :vars (a project set up by rdm-spec init).
(defstep #"a project set up by rdm-spec" (fn [_] (new-project)))
(defstep #"a project made by \"rdm-spec init\"" (fn [_] (new-project)))

;; Sets :dir :top :vars (a copy of rdm-spec's own project, its configured results directory absent).
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [_] (let [ctx (copy-own-project!)]
            (when (fs/exists? (path (:dir ctx) "dhf/allure-results")) (fs/delete-tree (path (:dir ctx) "dhf/allure-results")))
            ctx)))

;; Sets :exit :out :err :edn :runs. Runs rdm-spec in :dir (a project made first when there is none).
(defn when-run [ctx cmd suffix]
  (let [ctx (if (:dir ctx) ctx (new-project))
        ctx (reduce var-dir! ctx (filter #(re-find (re-pattern (str "(?<![A-Za-z0-9_])" % "(?![A-Za-z0-9_])")) cmd)
                                        ["ROOM" "DIR" "OUT2" "OUT" "R"]))]
    (case (str/trim suffix)
      "" (rdm! ctx cmd)
      "again" (rdm! ctx cmd)
      "in it" (rdm! ctx cmd)
      "in it again" (rdm! ctx cmd)
      "with no --results" (rdm! ctx cmd)
      "with that directory as DIR" (rdm! ctx cmd)
      "with a DIR that does not exist" (rdm! ctx cmd)
      "with that results directory as DIR" (rdm! ctx cmd)
      (throw (ex-info (str "the kernel's When does not know the suffix: " suffix) {})))))

(defstep #"\"rdm-spec ([^\"]+)\" is run((?: in it(?: again)?| again| with no --results| with that (?:results )?directory as DIR| with a DIR that does not exist)?)"
  (fn [ctx cmd suffix] (when-run ctx cmd suffix)))

;; Sets :dir :top :vars (a project set up by rdm-spec, whose :stack-words include "Cobalt").
(defstep #"the configured stack words include \"Cobalt\""
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (spec-project!))]
      (edit-config! (:dir ctx) #(update % :stack-words (fnil conj []) "Cobalt"))
      ctx)))

;; Sets :spec-before, the spec's files as they are, for "no file in the spec changes".
(defn keep-spec [ctx] (assoc ctx :spec-before (tree-snapshot (path (:dir ctx) "dhf/spec"))))

;; Reads :spec-before and :exit.
(defstep #"no file in the spec changes"
  (fn [ctx]
    (expect! (#{0 1} (:exit ctx)) ctx "the command did not run as it should")
    (expect! (:spec-before ctx) ctx "nothing kept the spec aside")
    (expect! (= (:spec-before ctx) (tree-snapshot (path (:dir ctx) "dhf/spec"))) ctx "a file in the spec changed")
    ctx))

;; Sets :dir :top :vars (a project whose one view records a hash that is not the model's).
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (spec-project! {"shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}))]
      (write! (:dir ctx) "dhf/views/shop-components.mmd" "%% model-sha256: 0000000000000000000000000000000000000000000000000000000000000000\nC4Component\n")
      (write! (:dir ctx) "dhf/views/shop-components.svg" "<!-- model-sha256: 0000000000000000000000000000000000000000000000000000000000000000 -->\n<svg></svg>\n")
      ctx)))

;; Sets :dir :top :vars :subject (spec-0013, an outline whose rows are x = a, b and c),
;; and :steps-ok (step definitions for every step are in the project).
(defstep #"a stamped scenario outline with three example rows"
  (fn [_]
    (let [ctx (spec-project! {"shop/cart.feature"
                              (feature-text {:name "Cart" :scenarios [{:id "spec-0013" :title "An outline <x>" :steps ["Given the row <x>" "Then it holds"]
                                                                        :examples [["x"] ["a"] ["b"] ["c"]]}]})}
                             :no-till true)]
      (write! (:dir ctx) "test/steps/all.clj" catch-all-steps)
      (assoc ctx :subject "spec-0013" :rows ["a" "b" "c"]))))

;; Reads :edn: the last command was a fence or a checklist; asks the same of a slice no cut holds.
(defn slice-refused!
  "Asks the last command's task (a fence or a checklist) of a slice no cut holds."
  [ctx]
    (if (:edn ctx)
      (if (get-in ctx [:edn :fence])
        (let [r (rdm! ctx ["fence" (path (:top ctx) "room2") "spec-3" "--slice" "99-nothing"])]
          (exits! r 2) (err-has! r "99-nothing"))
        (let [_ (write! (:dir ctx) "dhf/cycles/03/cycle.edn" (pr-str {:cut "spec-3" :scope [{:slice "99-nothing"}]
                                                                          :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent}
                                                                          :spec-owner {:name "o" :actor :human}}))
              r (rdm! ctx "checklist 03")]
          (exits! r 2) (err-has! r "99-nothing")))
      (fail! "nothing ran"))
    ctx)

(defstep #"a slice the cut does not hold is refused with exit 2, naming it" slice-refused!)

;; Sets :dir :top :vars (ROOM): a project whose cycle 03 names the cut spec-3 (made, with
;; a passing room, when no earlier Given made a project).
(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx]
    (if (:dir ctx)
      (do (cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope :project}) ctx)
      (cycle-project!))))

;; Sets :dir :top :vars (ROOM) :unstamped (the file and line of a scenario with no id at spec-3).
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [_]
    (let [ctx (cycle-project! :features {"shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:title "No id"}]})})]
      (assoc ctx :unstamped (str "dhf/spec/shop/cart.feature:"
                                 (inc (.indexOf (str/split-lines (slurp-in (:dir ctx) "dhf/spec/shop/cart.feature")) "  Scenario: No id")))))))

;; Reads :edn, and :edn-vars for the doc string's placeholders (symbols).
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (exits! ctx 0)
    (let [want (clojure.walk/postwalk #(if (and (symbol? %) (contains? (:edn-vars ctx) %)) (get (:edn-vars ctx) %) %) (edn/read-string doc))]
      (expect! (= want (:edn ctx)) ctx (str "it is " (:edn ctx) "\nnot   " want)))
    ctx))

;; Sets :exit :out :err :edn :runs, and fails unless verify passed.
(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (let [ctx (rdm! ctx "verify ROOM 03")] (exits! ctx 0) ctx)))

;; Sets :dir :top :vars :page (a project, made when there is none, whose indexed wiki page dhf/wiki/pages/a-page.md
;; footnotes the citation on its line 5).
(defstep #"a wiki page whose footnote cites \"(rdm:README.md@3763e08|loyly:README.md@17d68d2)\""
  (fn [ctx c]
    (let [ctx (if (:dir ctx) ctx (spec-project! {"shop/cart.feature" (feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}))]
      (write! (:dir ctx) "dhf/wiki/pages/a-page.md" (str "# A page\n\nA claim.[^a]\n\n[^a]: `" c "`, \"a section\".\n"))
      (write! (:dir ctx) "dhf/wiki/index.md" "# Wiki\n\n## Pages\n\n- [A page](pages/a-page.md): a page.\n")
      (assoc ctx :page "dhf/wiki/pages/a-page.md"))))

;; Reads :exit.
(defstep #"it exits (\d)" (fn [ctx n] (exits! ctx (parse-long n)) ctx))
(defstep #"it passes" (fn [ctx] (exits! ctx 0) ctx))
(defstep #"it fails" (fn [ctx] (exits! ctx 1) ctx))
