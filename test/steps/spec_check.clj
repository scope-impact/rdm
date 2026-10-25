(ns steps.spec-check
  "Step definitions for dhf/spec/spec/check.feature. Each scenario runs the
  check on a copy of the room's spec whose spec/check feature file is replaced
  by a fixture (steps.spec-support); the fixtures take their ids from the
  check feature itself, so no other file of the copy is touched, unless the
  scenario names an id another file carries.

  Shared in wording with other features, and defined here only when no file
  loaded earlier defines them, so each is there once with one meaning:
  \"it passes\" (dhf/spec/project/init.feature, dhf/spec/knowledge/
  sources.feature) and \"the configured stack words include ...\"
  (dhf/spec/knowledge/wiki.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(def check-file "dhf/spec/spec/check.feature")

;; ---------------------------------------------------------------------------
;; Helpers

(defn- registered? [text]
  (some #(re-matches (:pattern %) text) @rdm-spec.kernel.runner/*registry*))

(defn- room
  "ctx with a copy of the room (kept when it has one already)."
  [ctx]
  (apply s/needs-built! f/check-stages)
  (f/ensure-room! ctx))

(defn- ids-of
  "The id numbers the tag lines of `text` carry (an id named in a step's
  words is not a tag)."
  [text]
  (for [l (str/split-lines text)
        :when (str/starts-with? (str/trim l) "@")
        id (f/ids-in l)]
    (parse-long (subs id 6))))

(defn- cover-moved-ids!
  "Raises the copy's ledger over every id the spec's other files carry, so an
  id moved off them by spec-support (to make the fixture's ids its own) is
  one the ledger issued: the fixture's scenario is then the only finding."
  [dir fixture-file]
  (let [others (for [g (fs/glob (fs/file dir "dhf/spec") "**.feature")
                     :when (not= (str (fs/absolutize g)) (str (fs/absolutize (fs/file dir fixture-file))))]
                 (slurp (str g)))
        top (reduce max 0 (mapcat ids-of others))
        issued (or (f/ledger dir) 0)]
    (when (> top issued)
      (f/write-ledger! dir top))))

(defn- fixture!
  "ctx with the copy's check feature file replaced by `text`; :file and
  :text name it, :line is the line of the scenario named :name."
  [ctx text & {:keys [move?] :or {move? true}}]
  (let [ctx (room ctx)
        dir (:dir ctx)]
    (if move?
      (do (f/feature! dir "spec/check" text)
          (cover-moved-ids! dir check-file))
      (spit (str (fs/file dir check-file)) text))
    (assoc ctx :file check-file :text text
           :line (f/scenario-line text (or (:name ctx) "A fixture scenario")))))

(defn- one-scenario!
  "A fixture holding one scenario tagged `tags` (options as fixture-feature)."
  [ctx tags & [opts]]
  (fixture! (assoc ctx :name "A fixture scenario")
            (f/fixture-feature (merge {:tags tags :feature-name "Spec check"} opts))))

(defn- lines [ctx] (f/finding-lines (:run ctx)))

(defn- at [ctx] (str (:file ctx) ":" (:line ctx) ":"))

(defn- fails-with!
  "The run exited 1 and a finding line satisfies `pred`; `what` says what
  was looked for."
  [ctx pred what]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (some pred (lines ctx)) (str "no finding " what "\n" (s/show r)))))

(defn- includes-all? [l parts] (every? #(str/includes? l %) parts))

;; ---------------------------------------------------------------------------
;; A scenario is the unit of requirement and carries exactly one id

(defstep #"a scenario tagged both @(spec-\d+) and @(spec-\d+)"
  (fn [ctx a b]
    (assoc (one-scenario! ctx (str "@" a " @" b " @e2e")) :ids [a b])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx]
    (fails-with! ctx #(and (str/starts-with? % (at ctx)) (includes-all? % (:ids ctx)))
                 (str "at " (at ctx) " names " (str/join " and " (:ids ctx))))))

(defstep #"two scenarios both tagged @(spec-\d+)"
  (fn [ctx id]
    (let [ctx (room ctx)
          dir (:dir ctx)
          ;; another feature file of the copy carrying the id, else a second
          ;; scenario of the fixture would not be in another file
          other (first (for [g (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
                             :let [rel (str (fs/relativize dir g))]
                             :when (and (not= rel check-file)
                                        (re-find (re-pattern (str "@" id "(?!\\d)")) (slurp g)))]
                         rel))
          ctx (if other
                ctx
                (do (s/write! dir "dhf/spec/spec/parser.feature"
                              (f/fixture-feature {:tags (str "@" id " @e2e") :name "The other scenario"}))
                    ctx))
          other (or other "dhf/spec/spec/parser.feature")
          ctx (fixture! (assoc ctx :name "A fixture scenario")
                        (f/fixture-feature {:tags (str "@" id " @e2e") :feature-name "Spec check"})
                        :move? false)]
      (assoc ctx :id id :files [check-file other]))))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (fails-with! ctx #(includes-all? % (conj (:files ctx) (:id ctx)))
                 (str "names " (str/join " and " (:files ctx)) " and " (:id ctx)))))

(defstep #"a scenario on line (\d+) of a feature file with no @spec- tag"
  (fn [ctx n]
    (let [n (parse-long n)
          bare (f/scenario-line (f/fixture-feature {:tags "@e2e"}) "A fixture scenario")
          leading (vec (repeat (- n bare) "# A comment that places the scenario."))
          ctx (one-scenario! ctx "@e2e" {:leading leading})]
      (s/check! (= n (:line ctx)) (str "the fixture's scenario is on line " (:line ctx) ", not " n))
      ctx)))

(defstep #"it fails, names the file and line (\d+), and says to run \"rdm-spec stamp\""
  (fn [ctx n]
    (let [where (str (:file ctx) ":" n ":")]
      (fails-with! ctx #(and (str/starts-with? % where) (str/includes? % "rdm-spec stamp"))
                   (str "at " where " says to run rdm-spec stamp")))))

(defstep #"a scenario tagged @(spec-(\d+)), an id above every id the ledger has issued"
  (fn [ctx id n]
    (let [ctx (one-scenario! ctx (str "@" id " @e2e"))
          issued (f/ledger (:dir ctx))]
      (s/check! (and issued (> (parse-long n) issued))
                (str id " is not above the ledger's " (pr-str issued)))
      (assoc ctx :id id))))

(defstep #"it fails, names (spec-\d+) and says the ledger never issued it"
  (fn [ctx id]
    (fails-with! ctx #(and (str/includes? % id) (str/includes? % "never issued"))
                 (str "names " id " and says the ledger never issued it"))))

;; ---------------------------------------------------------------------------
;; A scenario carries exactly one verification level

(def ^:private levels #{"@e2e" "@contract"})

(defstep #"a stamped scenario tagged (@[\w-]+)"
  (fn [ctx tag]
    (if (levels tag)
      (assoc (one-scenario! ctx (str "@spec-0044 " tag)) :id "spec-0044" :tag tag)
      (assoc (one-scenario! ctx (str "@spec-0150 @e2e " tag)) :id "spec-0150" :tag tag))))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (let [r (:run ctx)
          about (filter #(and (str/starts-with? % (at ctx)) (re-find #"(?i)level" %)) (lines ctx))]
      (s/check! r "no command was run")
      (s/check! (not= 2 (:exit r)) (str "the check could not run\n" (s/show r)))
      (s/check! (empty? about) (str "the level check names the scenario\n" (s/show r))))))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (assoc (one-scenario! ctx "@spec-0045") :id "spec-0045")))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx]
    (fails-with! ctx #(includes-all? % [(:id ctx) "@e2e" "@contract"])
                 (str "names " (:id ctx) ", @e2e and @contract"))))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (assoc (one-scenario! ctx "@spec-0046 @e2e @contract") :id "spec-0046")))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx]
    (fails-with! ctx #(includes-all? % [(:id ctx) "@e2e" "@contract"])
                 (str "names " (:id ctx) ", @e2e and @contract"))))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx]
    (fails-with! ctx #(and (includes-all? % [(:id ctx) "@persona" "@e2e" "@contract" "@wip" "@UN-"])
                           (re-find #"@spec-" (str/replace % (str "@" (:id ctx)) "")))
                 (str "names " (:id ctx) " and @persona and lists the id, level, need and @wip tags"))))

;; ---------------------------------------------------------------------------
;; Every scenario traces to a user need

(defstep #"a feature tagged @(UN-\d+)"
  (fn [ctx need]
    (assoc (one-scenario! ctx "@spec-0047 @e2e" {:feature-tags (str "@" need)})
           :id "spec-0047" :need need)))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (let [own (f/tags-of (:dir ctx) "A fixture scenario")]
      (s/check! (some? own) "the fixture's scenario is not in the spec")
      (s/check! (not-any? #(str/starts-with? % "@UN-") own) (str "the scenario carries " (pr-str own))))))

(defn- maps-in [x]
  (cond (map? x) (cons x (mapcat maps-in (vals x)))
        (coll? x) (mapcat maps-in x)
        :else nil))

(defstep #"it lists the scenario"
  (fn [ctx]
    (let [r (:run ctx)
          v (try (edn/read-string (:out r)) (catch Exception _ nil))]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some #(= (:id ctx) (:id %)) (maps-in v))
                (str "the result lists no scenario " (:id ctx) "\n" (s/show r))))))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (assoc (one-scenario! ctx "@spec-0048 @e2e" {:feature-tags ""}) :id "spec-0048")))

(defstep #"it fails and names the scenario's id"
  (fn [ctx]
    (fails-with! ctx #(and (str/starts-with? % (at ctx)) (str/includes? % (:id ctx)))
                 (str "at " (at ctx) " names " (:id ctx)))))

(defstep #"the declared needs are (UN-\d+) to (UN-\d+)"
  (fn [ctx from to]
    (let [ctx (room ctx)
          n #(parse-long (subs % 3))
          keep? (set (for [i (range (n from) (inc (n to)))] (format "UN-%03d" i)))]
      (f/needs! (:dir ctx) #(into {} (filter (fn [[k _]] (keep? (str k)))) %))
      (assoc ctx :declared keep?))))

(defstep #"a scenario tagged @(UN-\d+)"
  (fn [ctx need]
    (s/check! (not (contains? (:declared ctx) need)) (str need " is declared"))
    (assoc (one-scenario! ctx (str "@spec-0049 @e2e @" need)) :id "spec-0049" :need need)))

(defstep #"it fails and names (UN-\d+) and the scenario's id"
  (fn [ctx need]
    (fails-with! ctx #(includes-all? % [need (:id ctx)]) (str "names " need " and " (:id ctx)))))

;; ---------------------------------------------------------------------------
;; Every user need rests on a source

(defstep #"the need (UN-\d+) with no sources"
  (fn [ctx need]
    (let [ctx (room ctx)]
      (f/needs! (:dir ctx) #(assoc-in % [need :sources] []))
      (assoc ctx :need need))))

(defstep #"it fails and names (UN-\d+)"
  (fn [ctx need]
    (fails-with! ctx #(str/includes? % need) (str "names " need))))

;; ---------------------------------------------------------------------------
;; Planned work is a scenario tagged @wip

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx]
    (let [ctx (one-scenario! ctx "@spec-0050 @e2e @wip")]
      (f/generate-views! (:dir ctx))
      (assoc ctx :id "spec-0050"))))

(when-not (registered? "it passes")
  (defstep #"it passes"
    (fn [ctx]
      (let [r (:run ctx)]
        (s/check! r "no command was run")
        (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))))))

;; ---------------------------------------------------------------------------
;; The spec never names an implementation

(when-not (registered? "the configured stack words include \"Cobalt\"")
  (defstep #"the configured stack words include \"([^\"]+)\""
    (fn [ctx word]
      (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))]
        (f/config! (:dir ctx) #(update % :stack-words (fn [ws] (vec (distinct (conj (vec ws) word))))))
        (assoc ctx :stack-word word)))))

(defstep #"a feature file whose description says the parser is written in (\S+)"
  (fn [ctx word]
    (let [ctx (one-scenario! ctx "@spec-0209 @e2e"
                             {:description [(str "The parser is written in " word ".")]})
          line (some (fn [[i l]] (when (str/includes? l (str "written in " word)) (inc i)))
                     (map-indexed vector (str/split-lines (:text ctx))))]
      (assoc ctx :word word :word-line line))))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx]
    (let [where (str (:file ctx) ":" (:word-line ctx) ":")]
      (fails-with! ctx #(and (str/starts-with? % where) (str/includes? % (:word ctx)))
                   (str "at " where " names " (:word ctx))))))
