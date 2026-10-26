(ns steps.project-init
  "Step definitions of dhf/spec/project/init.feature, and the project
  context's helpers."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def skeleton
  ["dhf/spec/model.edn" "dhf/spec/events.edn" "dhf/spec/needs.edn" "dhf/spec/ids.edn" "dhf/spec/glossary.md"
   "dhf/spec/README.md" "dhf/harness/config.edn" "dhf/harness/stack.md" "dhf/raw/README.md" "dhf/raw/sources.edn"
   "dhf/wiki/index.md" "dhf/decisions/README.md" ".githooks/pre-commit" ".github/workflows/cut.yml" ".gitignore"])

(defn tree
  "Every file under dir, but .git, relative, sorted."
  [dir]
  (->> (fs/glob dir "**" {:hidden true})
       (filter #(or (fs/regular-file? %) (fs/sym-link? %)))
       (map #(str (fs/relativize dir %)))
       (remove #(str/starts-with? % ".git/"))
       sort vec))

(defn in-base
  "Commands run in the scenario's own directory: init makes the project."
  [ctx]
  (let [ctx (k/base! ctx)] (if (:dir ctx) ctx (assoc ctx :dir (:base ctx)))))

(defn sauna [ctx] (str (fs/path (k/guard! (:dir ctx)) "sauna")))

(defn read-edn-file [d p] (edn/read-string (slurp (fs/file d p))))

(defn check-skeleton
  "Asserts dir holds exactly the skeleton, each file what the table says."
  [dir]
  (assert (= (sort skeleton) (tree dir)) (pr-str (tree dir)))
  (let [m (read-edn-file dir "dhf/spec/model.edn")
        cfg (read-edn-file dir "dhf/harness/config.edn")
        txt #(slurp (fs/file dir %))]
    (assert (:system m) "the model holds the system")
    (assert (every? empty? (vals (dissoc m :system))) (pr-str m))
    (assert (= {} (read-edn-file dir "dhf/spec/events.edn")))
    (assert (= {} (read-edn-file dir "dhf/spec/needs.edn")))
    (assert (= {:issued 0} (read-edn-file dir "dhf/spec/ids.edn")))
    (assert (= {} (read-edn-file dir "dhf/raw/sources.edn")))
    (doseq [s ["historyId" "subSuite" "statusDetails" "framework" "planned" "dhf/spec/needs.edn#" ":allure" ":epic :context"]]
      (assert (str/includes? (txt "dhf/spec/README.md") s) (str "the spec README does not hold " s)))
    (assert (string? (:id-prefix cfg)))
    (doseq [k [:spec-dir :ledger :model :events :needs :views-dir :raw-dir :wiki-dir :results-dir :cycles-dir]]
      (assert (contains? cfg k) (str "the configuration lacks " k)))
    (assert (= true (get-in cfg [:clean-room :deny :implementation])))
    (assert (= ["dhf/cycles"] (get-in cfg [:clean-room :deny :measurements])))
    (assert (some #{"site"} (get-in cfg [:clean-room :deny :generated])))
    (assert (= #{".gitignore" ".githooks" ".github" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"}
               (set (get-in cfg [:clean-room :keep]))))
    (assert (= {:watch ["/" "~"] :tmp "/tmp"} (:agency cfg)))
    (assert (= [:tests] (map :kind (:verify cfg))))
    (assert (nil? (:command (first (:verify cfg)))))
    (assert (empty? (:implementation cfg)))
    (assert (not (str/includes? (txt "dhf/harness/stack.md") "| Clojure")))
    (assert (str/includes? (txt ".githooks/pre-commit") "rdm-spec check"))
    (assert (= ".githooks" (k/git-config dir "core.hooksPath")))
    (assert (str/includes? (txt ".github/workflows/cut.yml") "rdm-spec cut"))
    (assert (str/includes? (txt ".github/workflows/cut.yml") "push"))
    (assert (= #{"dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"}
               (set (remove #(or (str/blank? %) (str/starts-with? % "#")) (str/split-lines (txt ".gitignore"))))))))

(defstep #"no directory at the path \"sauna\""
  (fn [ctx] (in-base ctx)))

(defstep #"\"sauna\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx table]
    (k/check-exit ctx 0)
    (let [d (sauna ctx)]
      (assert (= (sort skeleton) (sort (map first (rest table)))) "the table and the skeleton differ")
      (assert (fs/directory? (fs/path d ".git")))
      (assert (not= 0 (:exit (k/sh d {} "git" "rev-parse" "--verify" "HEAD"))) "a commit was made")
      (check-skeleton d)
      ctx)))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (:init (k/edn-out ctx))]
      (assert (= (sort skeleton) (sort (:wrote r))) (pr-str r))
      (assert (seq (:next r)))
      ctx)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx] (-> (in-base ctx) (k/placeholder! "DIR"))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (k/check-exit ctx 0)
    (check-skeleton (str (fs/path (get-in ctx [:ph "DIR"]) "sauna")))
    ctx))

(defstep #"a directory at the path \"sauna\""
  (fn [ctx]
    (let [ctx (in-base ctx)]
      (k/write-files! (sauna ctx) {"notes.txt" "mine\n"})
      (assoc ctx ::before (tree (sauna ctx))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "sauna")))

(defstep #"nothing in \"sauna\" changes"
  (fn [ctx]
    (assert (= (::before ctx) (tree (sauna ctx))))
    (assert (= "mine\n" (slurp (fs/file (sauna ctx) "notes.txt"))))
    ctx))

(defstep #"the harness configuration sets the id prefix to \"sauna\""
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "sauna" (:id-prefix (read-edn-file (sauna ctx) "dhf/harness/config.edn"))))
    ctx))

(defstep #"it sets the id prefix to \"spec\""
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "spec" (:id-prefix (read-edn-file (sauna ctx) "dhf/harness/config.edn"))))
    ctx))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "starts with a letter" "letters, digits and single hyphens")
    (assert (not (fs/exists? (sauna ctx)))) ctx))

(defstep #"it exits 2 and says the prefix UN is taken by need tags"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "UN" "need tags")
    (assert (not (fs/exists? (sauna ctx)))) ctx))

(defstep #"a project just initialised at \"sauna\""
  (fn [ctx]
    (let [ctx (in-base ctx)
          r (k/rdm ctx ["init" "sauna"])]
      (k/check-exit r 0)
      (k/git (sauna ctx) "config" "core.hooksPath" "/dev/null")
      ctx)))

(defstep #"\"rdm-spec check\" is run in \"sauna\""
  (fn [ctx] (k/rdm ctx (sauna ctx) ["check"])))
