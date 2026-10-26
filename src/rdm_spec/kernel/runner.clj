(ns rdm-spec.kernel.runner
  "The Gherkin runner (dhf/spec/kernel/runner.feature): reads the declared
  Gherkin subset, runs each scenario's steps against the step definitions
  registered with `defstep`, and writes one Allure result per scenario (one per
  outline row). Kernel code: the spec context's parser reads features with
  `read-feature`, and `rdm-spec run` and test.clj run them with `run-features`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading Gherkin

(def ^:private step-keywords ["Given" "When" "Then" "And" "But"])

;; Keywords of Gherkin that the declared subset does not hold: refused, never
;; skipped.
(def ^:private unsupported-keywords
  #{"Scenario Template" "Example" "Scenarios" "Ability" "Business Need"})

(defn- refusal [file line rule message]
  {:file file :line line :rule rule :message message})

(defn- indent-of [s] (count (re-find #"^\s*" s)))

(defn- split-row
  "The cells of a table row `| a | b |`, `\\|` an escaped bar."
  [s]
  (let [inner (-> s str/trim (subs 1))
        inner (if (str/ends-with? inner "|") (subs inner 0 (dec (count inner))) inner)]
    (loop [cs (seq inner) cur (StringBuilder.) cells []]
      (if-let [c (first cs)]
        (cond
          (and (= c \\) (#{\| \\ \n} (second cs)))
          (do (.append cur (case (second cs) \| \| \\ \\ \n \newline)) (recur (nnext cs) cur cells))
          (= c \|) (recur (next cs) (StringBuilder.) (conj cells (str/trim (str cur))))
          :else (do (.append cur c) (recur (next cs) cur cells)))
        (conj cells (str/trim (str cur)))))))

(defn- parse-tags [file line-no s]
  (let [s (str/replace s #"\s+#.*$" "")
        toks (str/split (str/trim s) #"\s+")]
    (if (every? #(re-matches #"@\S+" %) toks)
      {:tags (mapv (fn [t] {:tag (subs t 1) :line line-no}) toks)}
      {:refusal (refusal file line-no "gherkin-tag-malformed"
                         (str "a tag line holds something that is not a tag: " (str/trim s)))})))

(defn read-feature
  "Reads one feature file's text. Returns {:file :feature {:name :line :tags
  :description} :background [step] :rules [rule] :scenarios [scenario]
  :refusals [{:file :line :rule :message}]}. A scenario is {:keyword :name
  :line :tags :rule :steps :examples :background}; a step is {:keyword :text
  :line :doc-string :table}."
  [file text]
  (let [lines (str/split-lines (str text))
        n (count lines)]
    (loop [i 0
           st {:file file :feature nil :background [] :rules [] :scenarios []
               :refusals [] :pending-tags [] :container nil :allow-desc false
               :last-step nil :examples nil}]
      (if (>= i n)
        (let [st (cond-> st
                   (and (nil? (:feature st)) (some #(not (str/blank? %)) lines)
                        (not (every? #(or (str/blank? %) (str/starts-with? (str/trim %) "#")) lines)))
                   (update :refusals conj (refusal file 1 "gherkin-no-feature" "the file holds no Feature line")))]
          (-> st
              (update :scenarios
                      (fn [ss] (mapv (fn [s] (assoc s :background
                                                    (vec (concat (:background st)
                                                                 (get-in s [:rule :background])))))
                                     ss)))
              (select-keys [:file :feature :background :rules :scenarios :refusals])))
        (let [raw (nth lines i)
              line-no (inc i)
              t (str/trim raw)
              kw-of (fn [k] (when (str/starts-with? t (str k ":")) (str/trim (subs t (inc (count k))))))
              refuse (fn [st rule msg] (update st :refusals conj (refusal file line-no rule msg)))
              ;; Attach a finished step (or block) to the current container.
              add-step (fn [st step]
                         (case (:container st)
                           :background (if-let [ri (:rule-idx st)]
                                         (update-in st [:rules ri :background] (fnil conj []) step)
                                         (update st :background conj step))
                           :scenario (update-in st [:scenarios (dec (count (:scenarios st))) :steps] conj step)
                           nil))
              update-last-step (fn [st f]
                                 (case (:container st)
                                   :background (if-let [ri (:rule-idx st)]
                                                 (update-in st [:rules ri :background (dec (count (get-in st [:rules ri :background])))] f)
                                                 (update-in st [:background (dec (count (:background st)))] f))
                                   :scenario (let [si (dec (count (:scenarios st)))]
                                               (update-in st [:scenarios si :steps (dec (count (get-in st [:scenarios si :steps])))] f))))]
          (cond
            (str/blank? t) (recur (inc i) st)

            ;; doc string: read to its closing delimiter
            (and (or (str/starts-with? t "\"\"\"") (str/starts-with? t "```"))
                 (:last-step st))
            (let [delim (subs t 0 3)
                  ind (indent-of raw)
                  end (loop [j (inc i)]
                        (cond (>= j n) nil
                              (= delim (str/trim (nth lines j))) j
                              :else (recur (inc j))))]
              (if-not end
                (recur n (refuse st "gherkin-doc-string-unclosed" "a doc string is never closed"))
                (let [content (->> (subvec (vec lines) (inc i) end)
                                   (map (fn [l] (if (>= (indent-of l) ind) (subs l (min ind (count l))) (str/triml l))))
                                   (str/join "\n"))]
                  (recur (inc end) (-> (update-last-step st #(assoc % :doc-string content))
                                       (assoc :last-step nil))))))

            (str/starts-with? t "#") (recur (inc i) st)

            (str/starts-with? t "@")
            (let [{:keys [tags refusal]} (parse-tags file line-no t)]
              (recur (inc i) (if refusal
                               (update st :refusals conj refusal)
                               (-> st (update :pending-tags into tags) (assoc :allow-desc false)))))

            (kw-of "Feature")
            (if (:feature st)
              (recur (inc i) (-> (refuse st "gherkin-feature-twice"
                                         (str "a second Feature in one file (the first is on line " (get-in st [:feature :line]) ")"))
                                 (assoc :pending-tags [])))
              (recur (inc i) (assoc st :feature {:name (kw-of "Feature") :line line-no :tags (:pending-tags st) :description ""}
                                    :pending-tags [] :container :feature :allow-desc true :last-step nil)))

            (kw-of "Background")
            (recur (inc i) (assoc st :container :background :allow-desc true :last-step nil :pending-tags []))

            (kw-of "Rule")
            (let [rule {:name (kw-of "Rule") :line line-no :tags (:pending-tags st)}]
              (recur (inc i) (-> st (update :rules conj rule)
                                 (assoc :rule-idx (count (:rules st)) :pending-tags [] :container :rule
                                        :allow-desc true :last-step nil :examples nil))))

            (or (kw-of "Scenario Outline") (kw-of "Scenario"))
            (let [outline? (some? (kw-of "Scenario Outline"))
                  sc {:keyword (if outline? "Scenario Outline" "Scenario")
                      :name (or (kw-of "Scenario Outline") (kw-of "Scenario"))
                      :line line-no :tags (:pending-tags st)
                      :rule (when-let [ri (:rule-idx st)] (get-in st [:rules ri]))
                      :rule-idx (:rule-idx st)
                      :steps [] :examples []}]
              (recur (inc i) (cond-> (-> st (update :scenarios conj sc)
                                         (assoc :pending-tags [] :container :scenario :allow-desc true
                                                :last-step nil :examples nil))
                               (nil? (:feature st)) (refuse "gherkin-no-feature" "a scenario before any Feature line"))))

            (kw-of "Examples")
            (let [si (dec (count (:scenarios st)))]
              (if (and (= :scenario (:container st)) (>= si 0)
                       (= "Scenario Outline" (get-in st [:scenarios si :keyword])))
                (recur (inc i) (-> st (update-in [:scenarios si :examples] conj
                                                 {:line line-no :tags (:pending-tags st) :headings nil :rows []})
                                   (assoc :pending-tags [] :examples si :allow-desc true :last-step nil)))
                (recur (inc i) (refuse st "gherkin-examples-misplaced" "Examples outside a Scenario Outline"))))

            (str/starts-with? t "|")
            (cond
              (:examples st)
              (let [si (:examples st)
                    ei (dec (count (get-in st [:scenarios si :examples])))
                    cells (split-row t)]
                (recur (inc i) (-> (if (nil? (get-in st [:scenarios si :examples ei :headings]))
                                     (assoc-in st [:scenarios si :examples ei :headings] cells)
                                     (update-in st [:scenarios si :examples ei :rows] conj {:line line-no :cells cells}))
                                   (assoc :allow-desc false))))
              (:last-step st)
              (recur (inc i) (update-last-step st (fn [s] (update s :table (fnil conj []) {:line line-no :cells (split-row t)}))))
              :else (recur (inc i) (refuse st "gherkin-table-misplaced" "a table row outside a step or Examples")))

            (some #(str/starts-with? t (str % " ")) step-keywords)
            (let [k (first (filter #(str/starts-with? t (str % " ")) step-keywords))
                  step {:keyword k :text (str/trim (subs t (count k))) :line line-no}]
              (cond
                (= :void (:container st)) (recur (inc i) st)
                (#{:background :scenario} (:container st))
                (if (:examples st)
                  (recur (inc i) (refuse st "gherkin-step-misplaced" "a step after the Examples of an outline"))
                  (recur (inc i) (-> (add-step st step) (assoc :last-step step :allow-desc false))))
                :else
                (recur (inc i) (refuse st "gherkin-step-misplaced" (str "a step outside a scenario or background: " t)))))

            :else
            (let [kw (second (re-find #"^([A-Z][A-Za-z ]*?)\s*:" t))]
              (cond
                (or (contains? unsupported-keywords kw) (str/starts-with? t "* "))
                (recur (inc i) (-> (refuse st "gherkin-keyword-unknown"
                                           (str "the keyword \"" (if (str/starts-with? t "* ") "*" (str kw ":"))
                                                "\" is not in the declared subset of Gherkin"))
                                   (assoc :container (if (str/starts-with? t "* ") (:container st) :void)
                                          :examples nil :allow-desc true)))
                (:allow-desc st)
                (recur (inc i) (if (= :feature (:container st))
                                 (update-in st [:feature :description] #(str/trim (str % "\n" t)))
                                 st))
                kw
                (recur (inc i) (refuse st "gherkin-keyword-unknown"
                                       (str "the keyword \"" kw ":\" is not in the declared subset of Gherkin")))
                :else
                (recur (inc i) (refuse st "gherkin-line-unexpected" (str "a line that is no step, table, tag or keyword: " t)))))))))))

(defn tags-of
  "Every tag name (without @) the scenario carries, its feature's and rule's first."
  [feature scenario]
  (distinct (map :tag (concat (get-in feature [:feature :tags]) (get-in scenario [:rule :tags]) (:tags scenario)))))

(defn- substitute [text row]
  (reduce-kv (fn [t k v] (str/replace t (str "<" k ">") v)) text row))

(defn pickles
  "The runs of a feature: one per scenario, one per example row of an outline.
  Each is {:scenario :steps :parameters :row}."
  [feature]
  (vec
   (mapcat
    (fn [sc]
      (let [bg (:background sc)]
        (if (= "Scenario Outline" (:keyword sc))
          (for [ex (:examples sc) r (:rows ex)]
            (let [row (zipmap (:headings ex) (:cells r))
                  sub (fn [s] (cond-> (update s :text substitute row)
                                (:doc-string s) (update :doc-string substitute row)
                                (:table s) (update :table (fn [tb] (mapv #(update % :cells (partial mapv (fn [c] (substitute c row)))) tb)))))]
              {:scenario sc :steps (vec (concat bg (map sub (:steps sc))))
               :row (:cells r) :row-line (:line r)
               :parameters (mapv (fn [h v] {"name" h "value" v}) (:headings ex) (:cells r))}))
          [{:scenario sc :steps (vec (concat bg (:steps sc))) :parameters []}])))
    (:scenarios feature))))

;; ---------------------------------------------------------------------------
;; The step API

(defonce ^:private registry (atom []))

(defn clear-steps! [] (reset! registry []))

(defn register-step! [pattern f file line]
  (swap! registry (fn [defs]
                    (let [k [(str pattern) file]]
                      (conj (vec (remove #(= k [(str (:pattern %)) (:file %)]) defs))
                            {:pattern pattern :fn f :file file :line line})))))

(defmacro defstep
  "Defines a step: a pattern matched against the whole step text (after its
  keyword) and a function of the context and the captured groups (then a doc
  string or a table, when the step has one). Its return is the next step's
  context when it is a map."
  [pattern f]
  `(register-step! ~pattern ~f ~(str *file*) ~(:line (meta &form))))

(def ^:dynamic *attachments* nil)
(def ^:dynamic *results-dir* nil)
(def ^:dynamic *deferred* nil)

(defn fail!
  "An assertion that did not hold."
  [message]
  (throw (ex-info (str message) {::assertion true})))

(defn attach!
  "Attaches evidence to the running step, as an Allure attachment."
  ([name content] (attach! name content "text/plain"))
  ([name content type]
   (when (and *attachments* *results-dir*)
     (let [ext (case type "text/plain" "txt" "application/json" "json" "text/html" "html" "image/svg+xml" "svg" "bin")
           source (str (random-uuid) "-attachment." ext)]
       (spit (str (fs/path *results-dir* source)) (str content))
       (swap! *attachments* conj {"name" name "source" source "type" type})))
   nil))

(defn defer!
  "Registers a function run when the scenario ends, however it ends: the
  removal of a fixture, the stop of a process."
  [f]
  (when *deferred* (swap! *deferred* conj f))
  nil)

;; ---------------------------------------------------------------------------
;; Running

(defn- matches [text]
  (keep (fn [d] (when-let [m (re-matches (:pattern d) text)]
                  (assoc d :groups (if (string? m) [] (vec (rest m))))))
        @registry))

(defn- now [] (System/currentTimeMillis))

(defn- assertion? [e]
  (or (instance? AssertionError e) (::assertion (ex-data e))))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (str sw)))

(defn- run-steps [steps]
  (loop [steps steps ctx {} out [] state nil effective "Given"]
    (if-let [step (first steps)]
      (let [eff (if (#{"And" "But"} (:keyword step)) effective (:keyword step))
            name (str (:keyword step) " " (:text step))]
        (if state
          (recur (rest steps) ctx (conj out {"name" name "status" "skipped" "stage" "finished"
                                             "start" (now) "stop" (now) "steps" [] "attachments" [] "parameters" []})
                 state eff)
          (let [start (now)
                atts (atom [])
                ms (matches (:text step))
                [status details ctx']
                (cond
                  (empty? ms)
                  ["broken" {"message" (str "no step definition matches: " name)} ctx]
                  (> (count ms) 1)
                  ["broken" {"message" (str "two step definitions match \"" name "\": "
                                            (str/join " and " (map #(str "#\"" (:pattern %) "\" (" (:file %) ":" (:line %) ")") ms)))}
                   ctx]
                  :else
                  (let [d (first ms)
                        extra (cond (:doc-string step) [(:doc-string step)]
                                    (:table step) [(mapv :cells (:table step))]
                                    :else [])]
                    (try
                      (let [r (binding [*attachments* atts]
                                (apply (:fn d) ctx (concat (:groups d) extra)))]
                        ["passed" nil (if (map? r) r ctx)])
                      (catch Throwable e
                        [(if (and (= "Then" eff) (assertion? e)) "failed" "broken")
                         {"message" (str (ex-message e)) "trace" (trace-of e)} ctx]))))
                rec (cond-> {"name" name "status" status "stage" "finished" "start" start "stop" (now)
                             "steps" [] "attachments" @atts "parameters" []}
                      details (assoc "statusDetails" details))]
            (recur (rest steps) ctx' (conj out rec)
                   (when (not= "passed" status) {:status status :details (update details "message" #(str name ": " %))})
                   eff))))
      {:steps out :state state :ctx ctx})))

(defn needs-of [feature scenario]
  (filter #(re-matches #"UN-\d+" %) (tags-of feature scenario)))

(defn ids-of [scenario prefix]
  (let [re (re-pattern (str (java.util.regex.Pattern/quote prefix) "-\\d{4,}"))]
    (filterv #(re-matches re %) (map :tag (:tags scenario)))))

(defn run-pickle
  "Runs one pickle and writes its Allure result. Returns the result."
  [feature pickle {:keys [results-dir owner needs-path id-prefix]
                   :or {needs-path "dhf/spec/needs.edn" id-prefix "spec"}}]
  (let [sc (:scenario pickle)
        file (:file feature)
        tags (tags-of feature sc)
        wip? (some #{"wip"} (map :tag (:tags sc)))
        ids (ids-of sc id-prefix)
        id (when (= 1 (count ids)) (first ids))
        own (owner file)
        start (now)
        deferred (atom ())
        {:keys [steps state]}
        (if wip?
          {:steps (mapv (fn [s] {"name" (str (:keyword s) " " (:text s)) "status" "skipped" "stage" "finished"
                                 "start" start "stop" start "steps" [] "attachments" [] "parameters" []})
                        (:steps pickle))
           :state {:status "skipped" :details {"message" "planned (@wip): not run"}}}
          (binding [*results-dir* results-dir *deferred* deferred]
            (try (run-steps (:steps pickle))
                 (finally
                   (doseq [f @deferred] (try (f) (catch Throwable _ nil)))))))
        stop (now)
        labels (concat [{"name" "feature" "value" (get-in feature [:feature :name])}]
                       (when-let [r (get-in sc [:rule :name])] [{"name" "story" "value" r}])
                       (when own [{"name" "epic" "value" (:context own)}
                                  {"name" "suite" "value" (:context own)}
                                  {"name" "subSuite" "value" (:sub-suite own)}])
                       (map (fn [t] {"name" "tag" "value" t}) tags)
                       [{"name" "framework" "value" "gherkin"}]
                       (when id [{"name" "spec" "value" id}])
                       (when own [{"name" "context" "value" (:context own)}
                                  {"name" "component" "value" (:component own)}])
                       (when wip? [{"name" "planned" "value" "true"}]))
        uuid (str (random-uuid))
        result (cond-> {"uuid" uuid
                        "historyId" (str/join "|" (cons (or id (str file ":" (:name sc))) (:row pickle)))
                        "name" (:name sc)
                        "fullName" (str file ":" (:name sc))
                        "status" (if state (:status state) "passed")
                        "stage" "finished"
                        "start" start "stop" stop
                        "steps" steps
                        "parameters" (:parameters pickle)
                        "labels" (vec labels)
                        "links" (mapv (fn [n] {"type" "issue" "name" n "url" (str needs-path "#" n)}) (needs-of feature sc))
                        "attachments" []}
                 state (assoc "statusDetails" (:details state)))]
    (when results-dir
      (spit (str (fs/path results-dir (str uuid "-result.json"))) (json/generate-string result {:pretty true})))
    (assoc result ::file file ::line (or (:row-line pickle) (:line sc)) ::id id)))

(defn run-features
  "Runs every pickle of the given feature files ({:file :text}), writing results
  into :results-dir. :parallel runs that many at once. Returns {:results
  :counts :refusals}."
  [{:keys [files parallel] :as opts}]
  (let [features (map (fn [{:keys [file text]}] (read-feature file text)) files)
        refusals (mapcat :refusals features)
        work (for [f features :when (empty? (:refusals f)) p (pickles f)] [f p])
        results (if (and parallel (> parallel 1))
                  (let [q (java.util.concurrent.LinkedBlockingQueue. ^java.util.Collection (vec (map-indexed vector work)))
                        out (atom [])
                        workers (doall (for [_ (range parallel)]
                                         (future (loop []
                                                   (when-let [[i [f p]] (.poll q)]
                                                     (let [r (run-pickle f p opts)]
                                                       (swap! out conj [i r])
                                                       (when-let [cb (:on-result opts)] (cb r)))
                                                     (recur))))))]
                    (run! deref workers)
                    (mapv second (sort-by first @out)))
                  (mapv (fn [[f p]] (let [r (run-pickle f p opts)]
                                      (when-let [cb (:on-result opts)] (cb r))
                                      r))
                        work))]
    {:results results
     :refusals (vec refusals)
     :counts (merge {:passed 0 :failed 0 :broken 0 :skipped 0}
                    (frequencies (map #(keyword (get % "status")) results)))}))

;; ---------------------------------------------------------------------------
;; The project: its configuration, its model, its features and its steps

(defn read-config [root]
  (let [f (fs/file root "dhf/harness/config.edn")]
    (when (fs/exists? f) (edn/read-string (slurp f)))))

(defn model-owners
  "A function of a feature file's path to {:context :component :sub-suite},
  read from the model: a component owns dhf/spec/<its :spec>.feature."
  [root config]
  (let [spec-dir (or (:spec-dir config) "dhf/spec")
        mf (fs/file root (or (:model config) "dhf/spec/model.edn"))
        model (try (edn/read-string (slurp mf)) (catch Exception _ nil))
        entry (fn [ctx-id c] [(str spec-dir "/" (:spec c) ".feature")
                              {:context ctx-id :component (:name c)
                               :sub-suite (str/replace (subs (str (:id c)) 1) ":" "")}])
        m (into {} (concat
                    (for [cn (:containers model) cx (:contexts cn) c (:components cx) :when (:spec c)]
                      (entry (name (:id cx)) c))
                    (for [c (:kernel model) :when (:spec c)]
                      (entry "kernel" c))))]
    (fn [file] (get m file))))

(defn feature-files
  "Every feature file under the spec directory, found by looking, sorted."
  [root spec-dir]
  (let [d (fs/file root spec-dir)]
    (if (fs/directory? d)
      (->> (fs/glob d "**.feature")
           (map #(str (fs/relativize (fs/absolutize root) (fs/absolutize %))))
           sort vec)
      [])))

(defn load-steps!
  "Loads every step definition file in the steps directory, in name order."
  [root steps-dir]
  (let [d (fs/file root steps-dir)]
    (when (fs/directory? d)
      (require 'babashka.classpath)
      ((resolve 'babashka.classpath/add-classpath) (str (fs/parent (fs/absolutize d))))
      (doseq [f (sort (map str (fs/glob d "*.clj")))]
        (load-file f)))))

(defn- in-paths? [file paths]
  (or (empty? paths)
      (some #(let [p (str/replace % #"/$" "")] (or (= file p) (str/starts-with? file (str p "/")))) paths)))

(defn run-suite!
  "test.clj's entry: loads test/steps, runs the scenarios of the spec (only
  under PATHS, when given) and prints a summary. True when every scenario
  that is not planned passed."
  [{:keys [root paths results-dir parallel]}]
  (let [config (read-config root)
        files (filter #(in-paths? % paths) (feature-files root (or (:spec-dir config) "dhf/spec")))]
    (if (empty? files)
      (do (binding [*out* *err*] (println "cannot-run: no scenario was found")) false)
      (do
        (load-steps! root (or (:steps-dir config) "test/steps"))
        (fs/create-dirs results-dir)
        (let [{:keys [results counts refusals]}
              (run-features {:files (map (fn [f] {:file f :text (slurp (fs/file root f))}) files)
                             :results-dir (str results-dir) :parallel parallel
                             :owner (model-owners root config)
                             :needs-path (or (:needs config) "dhf/spec/needs.edn")
                             :id-prefix (or (:id-prefix config) "spec")
                             :on-result (fn [r] (when-not (#{"passed" "skipped"} (get r "status"))
                                                  (locking *out*
                                                    (println (str (str/upper-case (get r "status")) " " (::file r) ":" (::line r) " "
                                                                  (::id r) " " (get r "name") ": "
                                                                  (first (str/split-lines (str (get-in r ["statusDetails" "message"])))))))))})]
          (doseq [r refusals] (println (str (:file r) ":" (:line r) ": " (:rule r) ": " (:message r))))
          (println (format "scenarios: %d run, %d passed, %d failed, %d broken, %d skipped (planned)"
                           (count results) (:passed counts) (:failed counts) (:broken counts) (:skipped counts)))
          (and (empty? refusals) (pos? (count results))
               (every? #(or (= "passed" (get % "status"))
                            (and (= "skipped" (get % "status")) (some #{{"name" "planned" "value" "true"}} (get % "labels"))))
                       results)))))))
