(ns steps.compliance-gap
  "Step definitions for dhf/spec/compliance/gap.feature, and the compliance
  context's shared Given \"a project set up by rdm-spec whose configuration
  holds no :regulated\" (audit.feature uses it too).

  A regulated project (`regulated!`) is a copy of the room's allow-list (so
  every other stage of the check passes) whose configuration holds
  :regulated with the one clause list dhf/clauses/std.txt and the documents
  directory dhf/documents; by default the list holds STD:5.1 and
  dhf/documents/plan.md, with frontmatter, names it. The Givens rewrite
  either. The When and `it exits N` are the kernel's; \"it exits 2 and names
  dhf/...\" is matched by the regeneration context's step of that text.

  Helpers (a contract for the compliance step files):
    profile                 the :regulated a regulated project declares
    (doc-text fm body)      a document: frontmatter fields {\"id\" ..} then body
    (set-config! dir f)     the project's configuration rewritten as (f config)
    (git-commit! dir msg)   every file committed, with an identity"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn needs-gap! [] (s/needs-built! 'rdm-spec.compliance.gap))

(def profile {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(def std "dhf/clauses/std.txt")
(def plan "dhf/documents/plan.md")

(defn doc-text
  "A document: frontmatter with `fields` (in the order given) and `body`."
  [fields body]
  (str "---\n" (str/join (for [[k v] fields] (str k ": " v "\n"))) "---\n" body))

(def plan-fields [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan"]])

(defn plan-text [body] (doc-text plan-fields body))

(defn set-config!
  "Rewrites the configuration of the project `dir` as (f config)."
  [dir f]
  (let [file (fs/file dir "dhf/harness/config.edn")]
    (spit (str file) (pr-str (f (edn/read-string (slurp (str file))))))))

(defn git-commit!
  "Commits every file of `dir` (a repository made when it is none), with an
  identity. Returns the commit."
  [dir message]
  (let [env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
             "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}
        git (fn [& args]
              (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env env}
                             "git" args)]
                (s/check! (zero? (:exit r)) (str "git " (str/join " " args) ": " (:err r)))
                (str/trim (:out r))))]
    (when-not (fs/exists? (fs/file dir ".git")) (git "init" "-q"))
    (git "add" "-A" ".")
    (git "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "commit" "-q" "--allow-empty" "-m" message)
    (git "rev-parse" "HEAD")))

(defn regulated!
  "ctx with a regulated project: a copy of the room, :regulated declared."
  [ctx]
  (needs-gap!)
  (let [dir (s/copy-of-room!)]
    (set-config! dir #(assoc % :regulated profile))
    (s/write! dir std "# A fixture standard\nSTD:5.1 Development plan\n")
    (s/write! dir plan (plan-text "\nWe plan the work [[STD:5.1]].\n"))
    (assoc ctx :dir dir)))

(defn- write! [ctx rel text] (s/write! (:dir ctx) rel text) ctx)

(defn- run [ctx] (kc/the-run ctx))

(defn- lines-with [ctx & words]
  (filter (fn [l] (every? #(str/includes? l %) words)) (s/err-lines (run ctx))))

(defn line-of
  "The 1-based line of `text` holding `needle`."
  [text needle]
  (some (fn [[i l]] (when (str/includes? l needle) (inc i))) (map-indexed vector (str/split-lines text))))

;; ---------------------------------------------------------------------------
;; Without :regulated, nothing of the profile runs

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx]
    (let [parent (s/tmp-dir)
          r (kc/run! ctx parent ["init" "project"])
          dir (str (fs/file parent "project"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/check! (not (contains? (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))) :regulated))
                "the configuration holds :regulated")
      (assoc ctx :dir dir))))

(defstep #"a file dhf/documents/plan\.md that names no clause"
  (fn [ctx] (write! ctx plan (plan-text "\nWe plan the work.\n"))))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (doseq [l (s/err-lines r)]
        (s/check! (not (re-find #"(?i)clause|dhf/documents|dhf/clauses|document-" l))
                  (str "a line names the profile: " l))))))

;; ---------------------------------------------------------------------------
;; A clause list is read as clause lines, comments and includes

(defstep #"a regulated project"
  (fn [ctx] (regulated! ctx)))

(defstep #"dhf/clauses/std\.txt holds:"
  (fn [ctx doc] (write! ctx std (str doc "\n"))))

(defstep #"(dhf/clauses/\S+) holds \"([^\"]*)\""
  (fn [ctx rel line] (write! ctx rel (str line "\n"))))

(defstep #"no document names any clause"
  (fn [ctx] (write! ctx plan (plan-text "\nWe plan the work, naming nothing.\n"))))

(defstep #"it exits 1 with one clause-unreferenced finding for each of (.+), and none for any other key"
  (fn [ctx keys-text]
    (let [r (kc/exits! ctx 1)
          want (str/split keys-text #",\s*|\s+and\s+")
          found (filter #(str/includes? % ": clause-unreferenced: ") (s/err-lines r))]
      (doseq [k want]
        (s/check! (= 1 (count (filter #(str/includes? % (str " " k " ")) found)))
                  (str "not one clause-unreferenced finding for " k "\n" (s/show r))))
      (s/check! (= (count want) (count found)) (str "clause-unreferenced findings: " (pr-str found))))))

(defstep #"dhf/clauses/std\.txt holds only comments"
  (fn [ctx] (write! ctx std "# A standard\n# with no clause yet\n")))

(defstep #"it exits 2 and says the clause list (\S+) holds no clause"
  (fn [ctx rel]
    (let [r (kc/exits! ctx 2)
          l (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
      (s/check! (str/blank? (:out r)) "a task that cannot run printed a result")
      (s/check! (and l (str/includes? l rel) (str/includes? l "holds no clause"))
                (str "it does not say " rel " holds no clause\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A clause is met only by an audit tag naming it

(defstep #"dhf/clauses/std\.txt holds the clauses STD:5\.1 and STD:5\.2"
  (fn [ctx]
    (let [text "# The standard\nSTD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"]
      (assoc (write! ctx std text) :clause-line (line-of text "STD:5.2")))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and nothing else"
  (fn [ctx] (write! ctx plan (plan-text "\nWe plan the work [[STD:5.1]].\n"))))

(defstep #"it prints a clause-unreferenced finding naming dhf/clauses/std\.txt, the line of STD:5\.2, the key and its description"
  (fn [ctx]
    (s/check! (seq (filter #(str/starts-with? % (str std ":" (:clause-line ctx) ": clause-unreferenced: "))
                           (lines-with ctx "STD:5.2" "Requirements analysis")))
              (str "no such finding\n" (s/show (run ctx))))))

(defstep #"dhf/clauses/std\.txt holds the one clause STD:5\.1"
  (fn [ctx] (write! ctx std "STD:5.1 Development plan\n")))

(defstep #"dhf/documents/plan\.md holds the line (.+)"
  (fn [ctx line] (write! ctx plan (plan-text (str "\n" line "\n")))))

(defn- unreferenced? [ctx k]
  (let [r (run ctx)]
    (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
    (boolean (seq (filter #(and (str/includes? % ": clause-unreferenced: ") (str/includes? % (str " " k " ")))
                          (s/err-lines r))))))

(defstep #"the clause (\S+) is named"
  (fn [ctx k]
    (s/check! (not (unreferenced? ctx k)) (str k " is not named\n" (s/show (run ctx))))
    (kc/exits! ctx 0)
    nil))

(defstep #"the clause (\S+) is not named"
  (fn [ctx k]
    (s/check! (unreferenced? ctx k) (str k " is named\n" (s/show (run ctx))))
    (kc/exits! ctx 1)
    nil))

(defstep #"dhf/documents/plan\.md holds \"([^\"]+)\", a blank line, and \"([^\"]+)\""
  (fn [ctx a b] (write! ctx plan (plan-text (str "\n" a "\n\n" b "\n")))))

(defstep #"dhf/documents/plan\.md names STD:5\.1 and STD:9\.9 in audit tags"
  (fn [ctx]
    (let [text (plan-text "\nWe plan the work [[STD:5.1]].\nWe also do [[STD:9.9]].\n")]
      (assoc (write! ctx plan text) :tag-line (line-of text "STD:9.9")))))

(defstep #"it prints a clause-unknown warning naming dhf/documents/plan\.md, the line of the tag and STD:9\.9"
  (fn [ctx]
    (s/check! (seq (filter #(str/starts-with? % (str plan ":" (:tag-line ctx) ": warning: clause-unknown: "))
                           (lines-with ctx "STD:9.9")))
              (str "no such warning\n" (s/show (run ctx))))))

;; ---------------------------------------------------------------------------
;; A placeholder is a warning

(defstep #"dhf/documents/plan\.md names STD:5\.1 in an audit tag and holds a line \"([^\"]+)\""
  (fn [ctx line]
    (let [text (plan-text (str "\nWe plan the work [[STD:5.1]].\n\n" line "\n"))]
      (assoc (write! ctx plan text) :todo-line (line-of text "TODO")))))

(defstep #"it prints a document-placeholder warning naming dhf/documents/plan\.md and the line holding TODO"
  (fn [ctx]
    (s/check! (seq (filter #(str/starts-with? % (str plan ":" (:todo-line ctx) ": warning: document-placeholder: "))
                           (s/err-lines (run ctx))))
              (str "no such warning\n" (s/show (run ctx))))))

;; ---------------------------------------------------------------------------
;; A controlled document carries its identity

(defstep #"dhf/documents/plan\.md has frontmatter with an id and a title but no revision"
  (fn [ctx]
    (write! ctx plan (doc-text [["id" "PLAN-001"] ["title" "Development plan"]] "\nWe plan the work [[STD:5.1]].\n"))))

(defstep #"it prints a document-unidentified finding naming dhf/documents/plan\.md and the missing revision"
  (fn [ctx]
    (s/check! (seq (filter #(re-find (re-pattern (str "^" (java.util.regex.Pattern/quote plan) "(:\\d+)?: document-unidentified: ")) %)
                           (lines-with ctx "revision")))
              (str "no such finding\n" (s/show (run ctx))))))

(defstep #"dhf/documents/plan\.md and dhf/documents/old-plan\.md both have the id (\S+)"
  (fn [ctx id]
    (-> ctx
        (write! plan (doc-text [["id" id] ["revision" "2"] ["title" "Development plan"]] "\nWe plan the work [[STD:5.1]].\n"))
        (write! "dhf/documents/old-plan.md" (doc-text [["id" id] ["revision" "1"] ["title" "Old development plan"]]
                                                      "\nWe planned the work.\n")))))

(defstep #"it exits 1 and names the id (\S+) and both files"
  (fn [ctx id]
    (kc/exits! ctx 1)
    (s/check! (seq (lines-with ctx id plan "dhf/documents/old-plan.md"))
              (str "no finding names " id " and both files\n" (s/show (run ctx))))))
