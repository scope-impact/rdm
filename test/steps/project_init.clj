(ns steps.project-init
  "Steps of the component Init (01-start-a-project/init.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def skeleton
  ["dhf/spec/model.edn" "dhf/spec/events.edn" "dhf/spec/needs.edn" "dhf/spec/ids.edn" "dhf/spec/glossary.md"
   "dhf/spec/README.md" "dhf/harness/config.edn" "dhf/harness/stack.md" "dhf/raw/README.md" "dhf/raw/sources.edn"
   "dhf/wiki/index.md" "dhf/decisions/README.md" ".githooks/pre-commit" ".github/workflows/cut.yml" ".gitignore"])

(defn holds-skeleton!
  "The skeleton at dir, as init writes it."
  [ctx dir]
  (doseq [f skeleton] (k/expect! (fs/exists? (k/path dir f)) ctx (str "no " f)))
  (let [m (edn/read-string (slurp (k/path dir "dhf/spec/model.edn")))]
    (k/expect! (and (:system m) (= #{:system} (set (keys m)))) ctx "the model is not the system and nothing else"))
  (k/expect! (= {} (edn/read-string (slurp (k/path dir "dhf/spec/events.edn")))) ctx "the event catalogue is not empty")
  (k/expect! (= {} (edn/read-string (slurp (k/path dir "dhf/spec/needs.edn")))) ctx "the needs are not empty")
  (k/expect! (= {:issued 0} (edn/read-string (slurp (k/path dir "dhf/spec/ids.edn")))) ctx "the ledger is not at 0")
  (k/expect! (= {} (edn/read-string (slurp (k/path dir "dhf/raw/sources.edn")))) ctx "the sources are not empty")
  (let [c (edn/read-string (slurp (k/path dir "dhf/harness/config.edn")))]
    (k/expect! (= {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src" "dhf/allure-results"]}
                  (get-in c [:clean-room :deny])) ctx "the deny-list is not the implementation, dhf/cycles and the generated site")
    (k/expect! (= #{".gitignore" ".githooks" ".github" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"}
                  (set (get-in c [:clean-room :keep]))) ctx "the kept paths are not the skeleton's")
    (k/expect! (= {:watch ["/" "~"] :tmp "/tmp"} (:agency c)) ctx "not the watched directories")
    (k/expect! (and (= 1 (count (:verify c))) (= :tests (:kind (first (:verify c)))) (empty? (:command (first (:verify c)))))
               ctx "not one :tests check with no command")
    (k/expect! (empty? (:implementation c)) ctx "implementation paths are set")
    (doseq [k [:id-prefix :spec-dir :results-dir :cycles-dir :views-dir :raw-dir :wiki-dir]]
      (k/expect! (contains? c k) ctx (str "the configuration has no " k))))
  (let [readme (slurp (k/path dir "dhf/spec/README.md"))]
    (doseq [w ["historyId" "statusDetails" "subSuite" "framework" "planned" "links" ":epic :context" "parameters"]]
      (k/expect! (str/includes? readme w) ctx (str "the spec's README does not state " w))))
  (k/expect! (str/includes? (slurp (k/path dir ".githooks/pre-commit")) "rdm-spec check") ctx "the hook does not run rdm-spec check")
  (k/expect! (fs/executable? (k/path dir ".githooks/pre-commit")) ctx "the hook is not executable")
  (k/expect! (str/includes? (slurp (k/path dir ".github/workflows/cut.yml")) "rdm-spec cut") ctx "the workflow does not run rdm-spec cut")
  (let [gi (set (str/split-lines (slurp (k/path dir ".gitignore"))))]
    (doseq [l ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]]
      (k/expect! (gi l) ctx (str ".gitignore lacks " l))))
  ctx)

(defstep #"no directory at the path \"sauna\"" (fn [_] (let [top (k/top-dir!)] {:dir top :top top :vars {}})))

(defstep #"no directory at DIR/sauna"
  (fn [_] (let [top (k/top-dir!) d (k/path top "elsewhere")] (fs/create-dirs d) {:dir top :top top :vars {"DIR" d}})))

(defstep #"a directory at the path \"sauna\""
  (fn [_] (let [top (k/top-dir!)]
            (k/write! top "sauna/notes.txt" "mine\n")
            {:dir top :top top :vars {} :before (k/tree-snapshot (k/path top "sauna"))})))

(defstep #"\"sauna\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx table]
    (k/exits! ctx 0)
    (let [d (k/path (:dir ctx) "sauna")
          files (set (keys (k/tree-snapshot d)))
          listed (set (map first (rest table)))]
      (k/expect! (fs/exists? (k/path d ".git")) ctx "sauna is not a git repository")
      (k/expect! (not (zero? (:exit (babashka.process/shell {:dir d :continue true :out :string :err :string}
                                                            "git" "rev-parse" "--verify" "HEAD")))) ctx "sauna has a commit")
      (k/expect! (= listed files) ctx (str "the working tree holds " (sort files)))
      (k/expect! (= ".githooks" (k/git! d "config" "--file" ".git/config" "core.hooksPath")) ctx "core.hooksPath is not .githooks")
      (holds-skeleton! ctx d))))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (:init (:edn ctx))]
      (k/expect! (= (set skeleton) (set (:wrote r))) ctx "init does not list every file it wrote")
      (k/expect! (seq (:next r)) ctx "init names no next steps"))
    ctx))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (k/path (get-in ctx [:vars "DIR"]) "sauna")]
      (k/expect! (fs/exists? (k/path d ".git")) ctx "not a git repository")
      (holds-skeleton! ctx d))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "sauna") ctx))

(defstep #"nothing in \"sauna\" changes"
  (fn [ctx] (k/expect! (= (:before ctx) (k/tree-snapshot (k/path (:dir ctx) "sauna"))) ctx "sauna changed") ctx))

(defstep #"the harness configuration sets the id prefix to \"sauna\""
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= "sauna" (:id-prefix (k/config (k/path (:dir ctx) "sauna")))) ctx "the prefix is not sauna")
    ctx))

(defstep #"it (sets the id prefix to \"spec\"|exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens|exits 2 and says the prefix UN is taken by need tags)"
  (fn [ctx outcome]
    (cond
      (str/starts-with? outcome "sets")
      (do (k/exits! ctx 0)
          (k/expect! (= "spec" (:id-prefix (k/config (k/path (:dir ctx) "sauna")))) ctx "the prefix is not spec"))
      (str/includes? outcome "starts with a letter")
      (do (k/exits! ctx 2) (k/err-has! ctx "9lives" "starts with a letter")
          (k/expect! (not (fs/exists? (k/path (:dir ctx) "sauna"))) ctx "it wrote sauna"))
      :else
      (do (k/exits! ctx 2) (k/err-has! ctx "UN" "need tags")
          (k/expect! (not (fs/exists? (k/path (:dir ctx) "sauna"))) ctx "it wrote sauna")))
    ctx))

(defstep #"a project just initialised at \"sauna\""
  (fn [_] (let [d (k/init-project! "sauna" [])] (k/project-ctx d))))

(defstep #"\"rdm-spec check\" is run in \"sauna\""
  (fn [ctx] (k/rdm! ctx "check")))
