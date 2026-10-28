(ns steps.project-init
  "Steps of the component Init (dhf/spec/01-start-a-project/init.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep data-table]]
            [steps.kernel-shared :as k]))

(defn cwd! [ctx] (let [b (k/scratch! "scenario-")] (assoc ctx :base b :dir b)))

(defn tree
  "Every file under DIR but .git, relative to it."
  [dir]
  (set (for [f (fs/glob dir "**" {:hidden true}) :when (and (fs/regular-file? f) (not (str/starts-with? (str (fs/relativize dir f)) ".git/")))]
         (str (fs/relativize dir f)))))

(defn holds? [dir rel what]
  (let [t (slurp (str dir "/" rel))
        e #(edn/read-string t)]
    (case rel
      "dhf/spec/model.edn" (= #{:system} (set (keys (e))))
      "dhf/spec/events.edn" (= {} (e))
      "dhf/spec/needs.edn" (= {} (e))
      "dhf/spec/ids.edn" (= {:issued 0} (e))
      "dhf/spec/glossary.md" (str/starts-with? t "# Glossary")
      "dhf/spec/README.md" (every? #(str/includes? t %) ["historyId" "statusDetails" "parameters" "labels" "links" "attachments" ":allure" ":epic :context"])
      "dhf/harness/config.edn" (let [c (e)]
                                 (and (:id-prefix c) (:spec-dir c) (:results-dir c) (:cycles-dir c) (:views-dir c)
                                      (= [] (:implementation c))
                                      (= {:implementation true :measurements ["dhf/cycles"]} (select-keys (get-in c [:clean-room :deny]) [:implementation :measurements]))
                                      (every? (set (get-in c [:clean-room :deny :generated])) ["site" "site-src"])
                                      (every? (set (get-in c [:clean-room :keep])) [".gitignore" ".githooks" ".github" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"])
                                      (= {:watch ["/" "~"] :tmp "/tmp"} (:agency c))
                                      (= 1 (count (:verify c))) (= :tests (:kind (first (:verify c)))) (empty? (:command (first (:verify c))))))
      "dhf/harness/stack.md" (and (str/includes? t "| What | Choice |") (not (re-find #"\n\|[^-\n|][^\n]*\|\s*[^\n|]+\|\s*$" (str/replace t "| What | Choice |" ""))))
      "dhf/raw/README.md" (str/includes? t "Rules")
      "dhf/raw/sources.edn" (= {} (e))
      "dhf/wiki/index.md" (and (str/includes? t "Rules") (str/includes? t "## Pages"))
      "dhf/decisions/README.md" (str/includes? (str/lower-case t) "decision")
      ".githooks/pre-commit" (str/includes? t "rdm-spec check")
      ".github/workflows/cut.yml" (and (str/includes? t "rdm-spec cut") (str/includes? t "push"))
      ".gitignore" (every? #(str/includes? t %) ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]))))

(defstep #"no directory at the path \"sauna\"" (fn [ctx] (cwd! ctx)))

(defstep #"\"sauna\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (k/path ctx "sauna")
          rows (rest (data-table))]
      (k/check! (fs/directory? (str d "/.git")) "sauna is not a git repository")
      (k/check! (not= 0 (:exit (k/sh ctx d "git" "rev-parse" "--verify" "HEAD"))) "sauna has a commit")
      (k/check! (= ".githooks" (str/trim (:out (k/sh ctx d "git" "config" "core.hooksPath")))) "core.hooksPath is not .githooks")
      (k/check! (= (set (map first rows)) (tree d)) "the working tree holds " (pr-str (sort (tree d))))
      (doseq [[rel what] rows] (k/check! (holds? d rel what) rel " does not hold " what))
      ctx)))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (:init (:result ctx))]
      (k/check! (= (tree (k/path ctx "sauna")) (set (:wrote r))) "init lists " (pr-str (:wrote r)))
      (k/check! (seq (:next r)) "init lists no next step")
      ctx)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx] (let [ctx (cwd! ctx) d (k/scratch! "dir-")] (assoc-in ctx [:ph "DIR"] d))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (str (get-in ctx [:ph "DIR"]) "/sauna")]
      (k/check! (fs/directory? (str d "/.git")) "DIR/sauna is not a git repository")
      (k/check! (fs/exists? (str d "/dhf/harness/config.edn")) "DIR/sauna holds no skeleton")
      ctx)))

(defstep #"a directory at the path \"sauna\""
  (fn [ctx] (let [ctx (cwd! ctx)] (k/write! ctx "sauna/mine.txt" "mine\n"))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "sauna") ctx))

(defstep #"nothing in \"sauna\" changes"
  (fn [ctx]
    (k/check! (= #{"mine.txt"} (tree (k/path ctx "sauna"))) "sauna now holds " (pr-str (tree (k/path ctx "sauna"))))
    (k/check! (= "mine\n" (k/slurp-rel ctx "sauna/mine.txt")) "a file in sauna changed")
    ctx))

(defn prefix-of [ctx] (:id-prefix (edn/read-string (k/slurp-rel ctx "sauna/dhf/harness/config.edn"))))

(defstep #"the harness configuration sets the id prefix to \"sauna\""
  (fn [ctx] (k/exits! ctx 0) (k/check! (= "sauna" (prefix-of ctx)) "the id prefix is " (prefix-of ctx)) ctx))

(defstep #"it (sets the id prefix to \"spec\"|exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens|exits 2 and says the prefix UN is taken by need tags)"
  (fn [ctx outcome]
    (if (str/starts-with? outcome "sets")
      (do (k/exits! ctx 0) (k/check! (= "spec" (prefix-of ctx)) "the id prefix is " (prefix-of ctx)))
      (do (k/exits! ctx 2)
          (k/err-has! ctx (if (str/includes? outcome "UN") "the prefix UN is taken by need tags"
                              "a prefix starts with a letter and holds letters, digits and single hyphens"))
          (k/check! (not (fs/exists? (k/path ctx "sauna"))) "init wrote sauna")))
    ctx))

(defstep #"a project just initialised at \"sauna\""
  (fn [ctx] (let [ctx (cwd! ctx)] (k/exits! (k/run-rdm ctx "init sauna") 0) ctx)))

(defstep #"\"rdm-spec check\" is run in \"sauna\""
  (fn [ctx] (k/run-rdm ctx "check" {:dir (k/path ctx "sauna")})))
