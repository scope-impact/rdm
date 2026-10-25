(ns steps.compliance-gap
  "Step definitions for dhf/spec/compliance/gap.feature: `rdm-spec check` in
  a regulated project set up by rdm-spec (steps.compliance-support). The
  When, `it exits N` and `it exits 2 and names X` are the kernel's."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.compliance-support :as cs]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- dir [ctx] (or (:dir ctx) (s/fail! "no project was made")))

(defn- plan! [ctx text]
  (s/write! (dir ctx) "dhf/documents/plan.md" text)
  ctx)

(defn- finding-lines
  "The error lines of the last run under `rule`."
  [ctx rule]
  (filter #(str/includes? % (str ": " rule ": ")) (s/err-lines (kc/the-run ctx))))

(defn- gaps
  "The keys the last check reported as gaps: its clause-unreferenced findings."
  [ctx]
  (vec (keep #(second (re-find #"clause-unreferenced: (\S+)" %)) (finding-lines ctx "clause-unreferenced"))))

;; ---------------------------------------------------------------------------
;; Without :regulated

(defstep #"a file dhf/documents/plan\.md that names no clause"
  (fn [ctx] (plan! ctx (cs/document {} "We plan the work."))))

(defstep #"it exits 0 and no finding or warning names a clause, a clause list or a document"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not-any? #(re-find #"(?i)clause|dhf/documents|dhf/clauses|document" %) (s/err-lines r))
                (str "a line names the profile\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Clause lists

(defstep #"a regulated project"
  (fn [ctx] (cs/regulated! ctx)))

(defstep #"(dhf/clauses/\S+) holds:"
  (fn [ctx file doc]
    (s/write! (dir ctx) file (str doc "\n"))
    ctx))

(defstep #"(dhf/clauses/\S+) holds \"([^\"]*)\""
  (fn [ctx file line]
    (s/write! (dir ctx) file (str line "\n"))
    ctx))

(defstep #"no document names any clause"
  (fn [ctx] (plan! ctx (cs/document {} "We plan the work."))))

(defstep #"it exits 1 with one clause-unreferenced finding for each of (\S+), (\S+) and (\S+), and none for any other key"
  (fn [ctx & ks]
    (let [r (kc/exits! ctx 1)
          got (gaps ctx)]
      (s/check! (= (sort ks) (sort got))
                (str "clause-unreferenced findings for " (pr-str got) "\n" (s/show r))))))

(defstep #"dhf/clauses/std\.txt holds only comments"
  (fn [ctx]
    (s/write! (dir ctx) "dhf/clauses/std.txt" "# The standard's clauses\n# none yet\n")
    ctx))

(defstep #"it exits 2 and says the clause list (\S+) holds no clause"
  (fn [ctx file] (kc/refused-naming! ctx file "holds no clause") nil))

;; ---------------------------------------------------------------------------
;; What names a clause

(defstep #"dhf/clauses/std\.txt holds the clauses (\S+) and (\S+)"
  (fn [ctx a b]
    (s/write! (dir ctx) "dhf/clauses/std.txt" (str a " Development plan\n" b " Requirements analysis\n"))
    (assoc ctx :clause-lines {a 1 b 2} :descriptions {a "Development plan" b "Requirements analysis"})))

(defstep #"dhf/documents/plan\.md names (\S+) in an audit tag and nothing else"
  (fn [ctx k] (plan! ctx (cs/document {} (str "We plan the work [[" k "]].")))))

(defstep #"it prints a clause-unreferenced finding naming (\S+), the line of (\S+), the key and its description"
  (fn [ctx file k]
    (let [line (get-in ctx [:clause-lines k])
          d (get-in ctx [:descriptions k])
          r (kc/the-run ctx)]
      (s/check! (some #(and (str/starts-with? % (str file ":" line ": clause-unreferenced: "))
                            (str/includes? % k) (str/includes? % d))
                      (s/err-lines r))
                (str "no clause-unreferenced finding at " file ":" line " naming " k " and " d "\n" (s/show r))))))

(defstep #"dhf/clauses/std\.txt holds the one clause (\S+)"
  (fn [ctx k]
    (s/write! (dir ctx) "dhf/clauses/std.txt" (str k " Development plan\n"))
    ctx))

(defstep #"dhf/documents/plan\.md holds the line (.+)"
  (fn [ctx line] (plan! ctx (cs/document {} line))))

(defstep #"the clause (\S+) is (named|not named)"
  (fn [ctx k named]
    (let [r (kc/the-run ctx)
          gap? (some #{k} (gaps ctx))
          result-gaps (get-in (kc/stdout-edn r) [:gap :gaps])]
      (s/check! (not= 2 (:exit r)) (str "the check could not run\n" (s/show r)))
      (if (= "named" named)
        (s/check! (and (not gap?) (= 0 (:exit r)) (not (some #{k} result-gaps)))
                  (str k " is not named\n" (s/show r)))
        (s/check! (and gap? (= 1 (:exit r)) (some #{k} result-gaps))
                  (str k " is named\n" (s/show r)))))))

(defstep #"dhf/documents/plan\.md holds \"An open \[\[ tag\", a blank line, and \"STD:5\.1 \]\]\""
  (fn [ctx] (plan! ctx (cs/document {} "An open [[ tag\n\nSTD:5.1 ]]"))))

(defstep #"dhf/documents/plan\.md names (\S+) and (\S+) in audit tags"
  (fn [ctx a b]
    ;; frontmatter is lines 1-5, a blank line 6: the tags stand on lines 7 and 9
    (plan! (assoc ctx :tag-lines {a 7 b 9})
           (cs/document {} (str "We plan the work [[" a "]].\n\nWe also meet [[" b "]].")))))

(defstep #"it prints a clause-unknown warning naming (\S+), the line of the tag and (\S+)"
  (fn [ctx file k]
    (let [line (get-in ctx [:tag-lines k])
          r (kc/the-run ctx)]
      (s/check! (some #(and (str/starts-with? % (str file ":" line ": warning: clause-unknown: "))
                            (str/includes? % k))
                      (s/err-lines r))
                (str "no clause-unknown warning at " file ":" line " naming " k "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Frontmatter, placeholders, identity

(defstep #"dhf/documents/plan\.md has the title \"([^\"]*)\" and a body that names no clause"
  (fn [ctx title] (plan! ctx (cs/document {:title title} "We plan the work."))))

(defstep #"dhf/documents/plan\.md names (\S+) in an audit tag and holds a line \"([^\"]*)\""
  (fn [ctx k line]
    ;; the tag on line 7, the placeholder on line 9
    (plan! (assoc ctx :todo-line 9)
           (cs/document {} (str "We plan the work [[" k "]].\n\n" line)))))

(defstep #"it prints a document-placeholder warning naming (\S+) and the line holding TODO"
  (fn [ctx file]
    (let [r (kc/the-run ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" (:todo-line ctx) ": warning: document-placeholder: "))
                      (s/err-lines r))
                (str "no document-placeholder warning at " file ":" (:todo-line ctx) "\n" (s/show r))))))

(defstep #"dhf/documents/plan\.md has frontmatter with an id and a title but no revision"
  (fn [ctx] (plan! ctx (cs/document {:revision nil} "We plan the work [[STD:5.1]]."))))

(defstep #"it prints a document-unidentified finding naming (\S+) and the missing revision"
  (fn [ctx file]
    (let [r (kc/the-run ctx)]
      (s/check! (some #(and (str/starts-with? % (str file ": document-unidentified: "))
                            (str/includes? % "revision"))
                      (s/err-lines r))
                (str "no document-unidentified finding for " file " naming the revision\n" (s/show r))))))

(defstep #"dhf/documents/plan\.md and dhf/documents/old-plan\.md both have the id (\S+)"
  (fn [ctx id]
    (s/write! (dir ctx) "dhf/documents/old-plan.md" (cs/document {:id id :title "Old plan"} "An older plan."))
    (plan! ctx (cs/document {:id id} "We plan the work [[STD:5.1]]."))))

(defstep #"it exits 1 and names the id (\S+) and both files"
  (fn [ctx id]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(every? (fn [w] (str/includes? % w)) [id "dhf/documents/plan.md" "dhf/documents/old-plan.md"])
                      (s/err-lines r))
                (str "no line names " id " and both files\n" (s/show r))))))
