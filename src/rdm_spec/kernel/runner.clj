(ns rdm-spec.kernel.runner
  "The kernel component Gherkin runner (dhf/spec/kernel/runner.feature): runs
  the scenarios of a project's spec against its step definitions and writes
  one Allure result per scenario, and per row of an outline.

  The step API, a public contract (spec-0168, spec-0169):

    (defstep #\"pattern\" (fn [ctx & captured] ctx'))

  A step matches a definition when the pattern matches its whole text; the
  function gets the context and each captured group, then the step's doc
  string or data table ([[cell]]) when it has one. What it returns is the
  next step's context; nil leaves the context as it was. Each scenario starts
  with {}. Evidence is attached with (attach! name text) or (attach! name
  text type), type text/plain unless given: an Allure attachment of the
  running step, its source a file beside the result.

  A step two definitions match, or that none matches, makes the result
  broken, naming them, and no step of the scenario runs. Failed is a Then
  (an And or But after one) whose assertion did not hold: a
  java.lang.AssertionError (assert), or an ex-info whose data holds a true
  keyword named failed or assertion (steps.support's fail!). Anything else
  that goes wrong is broken (spec-0323). The steps after it are skipped.
  A scenario tagged @wip is not run: its result is skipped and labelled
  planned.

  A result is filled from its scenario through the Allure mapping (glossary,
  \"Allure mapping\"): the configuration's :allure, else `default-mapping`.
  It always carries fullName, historyId, status, stage, start, stop,
  statusDetails when it did not pass, and the labels suite (the context's
  id), subSuite (the component's id without its colon), context, framework
  gherkin and planned for @wip.

  The run task (`run`): the scenarios of every feature file under the spec
  directory, found by looking, never listed; the step definitions every .clj
  file under :steps-dir; results into --results, else RESULTS_DIR, else the
  configured :results-dir. No feature file: cannot run, no result written.
  A scenario that did not pass is a finding (scenario-failed); a Gherkin
  refusal is a finding as the reader names it.

  API for test.clj and verify: defstep, attach!, step-files,
  load-step-files!, shadow-warnings, plan, pickles-of, step-name,
  run-pickles!, default-mapping, mapping-of, mapping-problem,
  mapping-differences, allure-result, run."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [babashka.classpath]))

;; ---------------------------------------------------------------------------
;; Step definitions

(defonce ^:private registry (atom []))

(defn register!
  "Registers a step definition; one of the same pattern from the same
  namespace (a file loaded again) replaces it."
  [pattern f ns-name line]
  (let [d {:pattern pattern :fn f :ns ns-name :line line}]
    (swap! registry (fn [ds] (conj (vec (remove #(and (= ns-name (:ns %)) (= (str pattern) (str (:pattern %)))) ds)) d)))
    d))

(defmacro defstep
  "Defines a step: (defstep #\"pattern\" (fn [ctx & captured] ctx'))."
  [pattern f]
  `(register! ~pattern ~f ~(str *ns*) ~(:line (meta &form))))

(defn definitions [] @registry)

(defn clear-definitions! [] (reset! registry []))

(defn- def-name [d] (str "#\"" (:pattern d) "\" (" (:ns d) (when (:line d) (str ":" (:line d))) ")"))

(defn shadow-warnings
  "A warning for each pattern defined by more than one namespace: every step
  it matches is broken."
  []
  (vec (for [[p ds] (group-by (comp str :pattern) @registry)
             :when (< 1 (count (distinct (map :ns ds))))]
         {:file nil :line nil :rule "step-defined-twice" :severity :warning
          :message (str "the pattern #\"" p "\" is defined by " (str/join " and " (map :ns ds))
                        ": every step it matches is broken")})))

(defn step-files
  "Every .clj file under the step directory `dir`, sorted."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "**.clj"))))
    []))

(defn load-step-files!
  "Loads each step definition file of `files`."
  [files]
  (doseq [f files] (load-file (str f))))

(defn matching
  "The definitions of `defs` whose pattern matches the whole `text`, each
  with :args its captured groups."
  [defs text]
  (vec (for [d defs
             :let [m (re-matches (:pattern d) text)]
             :when m]
         (assoc d :args (if (string? m) [] (vec (rest m)))))))

;; ---------------------------------------------------------------------------
;; Evidence

(def ^:dynamic *attachments* nil)
(def ^:dynamic *results-dir* nil)

(defn attach!
  "Attaches `text` as evidence named `nm` to the running step."
  ([nm text] (attach! nm text "text/plain"))
  ([nm text type]
   (when *attachments*
     (let [ext (case type "application/json" "json" "text/html" "html" "image/png" "png" "txt")
           src (str (random-uuid) "-attachment." ext)]
       (when *results-dir*
         (fs/create-dirs *results-dir*)
         (if (bytes? text)
           (fs/write-bytes (fs/file *results-dir* src) text)
           (spit (str (fs/file *results-dir* src)) (str text))))
       (swap! *attachments* conj {:name (str nm) :source src :type type})))
   nil))

;; ---------------------------------------------------------------------------
;; Pickles: what runs, one per scenario and one per outline row

(defn step-name "A step's name: its keyword and text." [st] (str (:keyword st) " " (:text st)))

(defn- primaries
  "`steps` each with :primary, the keyword And and But continue."
  [steps]
  (second (reduce (fn [[prev out] st]
                    (let [p (if (#{"And" "But"} (:keyword st)) (or prev "Given") (:keyword st))]
                      [p (conj out (assoc st :primary p))]))
                  [nil []] steps)))

(defn- subst-step [st values]
  (cond-> (update st :text gherkin/substitute values)
    (:doc-string st) (update :doc-string gherkin/substitute values)
    (:data-table st) (update :data-table (fn [t] (mapv (fn [r] (mapv #(gherkin/substitute % values) r)) t)))))

(defn pickles-of
  "The pickles of the parsed feature file `rel`: `opts` :prefix (the id
  prefix) and :owner ({:context :component :context-name :component-name})."
  [rel parsed {:keys [prefix owner]}]
  (let [feature (:feature parsed)
        id-re (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}"))]
    (vec
     (for [sc (:scenarios parsed)
           :let [own (vec (:tags sc))
                 all (vec (distinct (concat (:tags feature) (get-in sc [:rule :tags]) own)))
                 ids (vec (distinct (for [t own :when (re-matches id-re t)] (subs t 1))))
                 levels (vec (for [t own :when (#{"@e2e" "@contract"} t)] (subs t 1)))
                 base {:id (when (= 1 (count ids)) (first ids)) :ids ids
                       :title (:name sc) :file rel :line (:line sc)
                       :feature (:name feature) :rule (get-in sc [:rule :name])
                       :tags all :level (when (= 1 (count levels)) (first levels))
                       :needs (vec (distinct (for [t all :when (re-matches #"@UN-\d+" t)] (subs t 1))))
                       :wip? (boolean (some #{"@wip"} all))
                       :outline? (= "Scenario Outline" (:keyword sc))
                       :context (:context owner) :component (:component owner)
                       :context-name (:context-name owner) :component-name (:component-name owner)}
                 steps (vec (concat (:background sc) (:steps sc)))]
           row (if (= "Scenario Outline" (:keyword sc))
                 (for [ex (:examples sc) r (:rows ex)]
                   {:line (:line r) :header (:header ex) :values (zipmap (:header ex) (:cells r))})
                 [nil])]
       (if row
         (assoc base
                :row row
                :display-name (str (gherkin/substitute (:name sc) (:values row))
                                   " [" (str/join ", " (map #(get (:values row) %) (:header row))) "]")
                :steps (primaries (mapv #(subst-step % (:values row)) steps)))
         (assoc base :row nil :display-name (:name sc) :steps (primaries steps)))))))

(defn- read-model [f]
  (when (fs/regular-file? f)
    (try (let [m (edn/read-string (slurp (str f)))] (when (map? m) m)) (catch Exception _ nil))))

(defn owners
  "{spec-path owner} of the model `m`; a kernel component's context is :kernel."
  [m]
  (into {}
        (concat
         (for [c (:containers m) :when (map? c)
               x (:contexts c) :when (map? x)
               comp (:components x) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context (:id x) :component (:id comp)
                          :context-name (or (:name x) (some-> (:id x) name))
                          :component-name (or (:name comp) (some-> (:id comp) name))}])
         (for [comp (:kernel m) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context :kernel :component (:id comp) :context-name "kernel"
                          :component-name (or (:name comp) (some-> (:id comp) name))}]))))

(defn- rel-of [root f] (str/replace (str (fs/relativize (fs/absolutize root) (fs/absolutize f))) "\\" "/"))

(defn- under? [root f only]
  (or (empty? only)
      (let [af (str (fs/absolutize f))]
        (some (fn [o]
                (let [ao (str (fs/absolutize (if (fs/absolute? o) o (fs/file root o))))]
                  (or (= af ao) (str/starts-with? af (str (str/replace ao #"/+$" "") "/")))))
              only))))

(defn plan
  "What a run of the project `root` runs: {:pickles :refusals :features},
  :features the number of feature files found. `opts`: :spec-dir, :id-prefix,
  :model (paths relative to `root`) and :only (paths a feature file must lie
  under)."
  [root {:keys [spec-dir id-prefix model only]}]
  (let [spec-dir (str/replace (or spec-dir "dhf/spec") #"/+$" "")
        d (fs/file root spec-dir)
        files (if (fs/directory? d)
                (vec (sort (filter #(under? root % only) (map str (fs/glob d "**.feature")))))
                [])
        own (owners (read-model (fs/file root (or model "dhf/spec/model.edn"))))
        parsed (for [f files
                     :let [rel (rel-of root f)
                           sp (str/replace (subs rel (inc (count spec-dir))) #"\.feature$" "")]]
                 [rel (gherkin/parse-feature rel (slurp f)) (get own sp)])]
    {:pickles (vec (mapcat (fn [[rel p o]] (pickles-of rel p {:prefix (or id-prefix "spec") :owner o})) parsed))
     :refusals (vec (mapcat (fn [[_ p _]] (:refusals p)) parsed))
     :features (count files)}))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  "rdm-spec's default Allure mapping (spec-0333)."
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}}
   :evidence {}})

(def spec-elements
  "The elements of the spec an Allure element may carry."
  #{:context :component :feature :rule :scenario :scenario-id :gherkin-step :example-row :gherkin-tags :level
    :need :file})

(def ^:private label-elements #{:epic :feature :story :suite :subSuite :parentSuite})

(def allure-elements
  "The elements of an Allure result a mapping may fill."
  (into label-elements #{:name :step :parameter :labels :links :evidence}))

(defn mapping-of
  "The Allure mapping of the configuration `config`."
  [config]
  (or (:allure config) default-mapping))

(defn mapping-problem
  "Why the mapping `m` cannot be used, or nil."
  [m]
  (let [simple (for [[k v] m :when (not (#{:labels :links :evidence} k))] [k v])]
    (cond
      (not (map? m)) "the :allure mapping is not a map"
      (some #(not (allure-elements %)) (keys m))
      (let [k (first (remove allure-elements (keys m)))] (str k " is not an element of an Allure result"))
      (some (fn [[_ v]] (not (spec-elements v))) simple)
      (let [[k v] (first (filter (fn [[_ v]] (not (spec-elements v))) simple))]
        (str v " is not an element of the spec (mapped to " k ")"))
      (not (map? (:labels m))) "the :labels of the mapping is not a map of label names to elements"
      (some (fn [[_ v]] (not (spec-elements v))) (:labels m))
      (let [[k v] (first (filter (fn [[_ v]] (not (spec-elements v))) (:labels m)))]
        (str v " is not an element of the spec (mapped to the label " k ")"))
      (not= :scenario-id (get (:labels m) "spec")) "the label spec, from the scenario's id, is required"
      (and (contains? m :links) (not (and (map? (:links m)) (every? #{:need} (keys (:links m))))))
      "the :links of the mapping names only :need {:type :url}"
      (and (contains? m :evidence) (not (and (map? (:evidence m))
                                             (every? #{:e2e :contract} (keys (:evidence m)))
                                             (every? #(and (sequential? %) (every? string? %)) (vals (:evidence m))))))
      "the :evidence of the mapping is {level [attachment-name]}, the levels :e2e and :contract"
      :else nil)))

(defn- component-id [k] (when k (if (namespace k) (str (namespace k) "/" (name k)) (name k))))

(defn- element-value
  "The value of the spec element `e` for the pickle `pk`: a string, a vector
  of strings, or nil."
  [pk e]
  (case e
    :context (some-> (:context pk) name)
    :component (:component-name pk)
    :feature (:feature pk)
    :rule (:rule pk)
    :scenario (:title pk)
    :scenario-id (:id pk)
    :gherkin-tags (mapv #(subs % 1) (:tags pk))
    :gherkin-step (mapv step-name (:steps pk))
    :example-row (when-let [r (:row pk)] (mapv (fn [h] {:name h :value (get (:values r) h)}) (:header r)))
    :level (:level pk)
    :need (:needs pk)
    :file (:file pk)
    nil))

(defn- as-values [v] (cond (nil? v) [] (sequential? v) (mapv str v) :else [(str v)]))

(defn- expected-labels
  "[[name value]] the mapping puts on the result of `pk`."
  [pk m]
  (vec (concat
        (for [k [:feature :story :epic :parentSuite :suite :subSuite]
              :when (contains? m k)
              v (as-values (element-value pk (get m k)))]
          [(name k) v])
        (for [[n e] (:labels m) v (as-values (element-value pk e))] [n v]))))

(defn- need-links [pk m]
  (when-let [{:keys [type url]} (get-in m [:links :need])]
    (vec (for [n (:needs pk)] {:type (or type "issue") :name n :url (str/replace (str url) "{id}" n)}))))

(defn- history-id [pk]
  (let [base (or (:id pk) (str (:file pk) ":" (:title pk)))]
    (if-let [r (:row pk)]
      (str base "|" (str/join "|" (map #(get (:values r) %) (:header r))))
      base)))

(defn allure-result
  "The Allure result of the pickle `pk` that ran with `outcome` ({:status
  :start :stop :steps :statusDetails :attachments}), filled through the
  mapping `m`."
  [pk outcome m]
  (let [mapped (expected-labels pk m)
        fixed (concat
               (when-not (contains? m :suite) [["suite" (some-> (:context pk) name)]])
               (when-not (contains? m :subSuite) [["subSuite" (component-id (:component pk))]])
               [["framework" "gherkin"] ["context" (some-> (:context pk) name)]]
               (when (:wip? pk) [["planned" "true"]]))
        labels (vec (for [[n v] (concat mapped fixed)
                          :when (some? v)
                          :when (not (and (#{"context" "framework"} n) (some #(= n (first %)) mapped)))]
                      {:name n :value v}))
        params (when (contains? m :parameter) (element-value pk (:parameter m)))]
    (cond-> {:uuid (or (:uuid outcome) (str (random-uuid)))
             :historyId (history-id pk)
             :name (if (contains? m :name) (str (element-value pk (:name m))) (:title pk))
             :fullName (str (:file pk) ":" (:title pk))
             :status (:status outcome)
             :stage "finished"
             :start (:start outcome)
             :stop (:stop outcome)
             :labels labels
             :links (or (need-links pk m) [])
             :parameters (vec params)
             :steps (vec (:steps outcome))
             :attachments (vec (:attachments outcome))}
      (:statusDetails outcome) (assoc :statusDetails (:statusDetails outcome)))))

(defn- getk [x k] (if (contains? x k) (get x k) (get x (name k))))

(defn- attachment-names [r]
  (letfn [(walk [x] (concat (map #(getk % :name) (getk x :attachments)) (mapcat walk (getk x :steps))))]
    (set (walk r))))

(defn mapping-differences
  "How the result `r` (keys keywords or strings) differs from what the
  mapping `m` fills it with from the pickle `pk`: [{:rule :element :message}],
  rule steps-differ-from-scenario, mapping-differs or evidence-missing."
  [pk r m]
  (let [labels (getk r :labels)
        label-vals (fn [n] (vec (keep #(when (= n (getk % :name)) (str (getk % :value))) labels)))
        want (group-by first (expected-labels pk m))
        steps (mapv #(str (getk % :name)) (getk r :steps))
        want-steps (mapv step-name (:steps pk))
        id (or (:id pk) (:title pk))]
    (vec
     (concat
      (when (and (contains? m :step) (not= want-steps steps))
        [{:rule "steps-differ-from-scenario" :element nil
          :message (str "the result of " id " holds the steps " (pr-str steps) ", not the scenario's lines "
                        (pr-str want-steps))}])
      (for [k [:feature :story :epic :parentSuite :suite :subSuite]
            :when (contains? m k)
            :let [w (mapv second (get want (name k))) g (label-vals (name k))]
            :when (not= (sort w) (sort g))]
        {:rule "mapping-differs" :element (name k)
         :message (str "the result of " id " holds the label " (name k) " " (pr-str g) ", not " (pr-str w)
                       ", the scenario's " (name (get m k)))})
      (for [[n e] (:labels m)
            :when (not= "spec" n)
            :let [w (mapv second (get want n)) g (label-vals n)]
            :when (not= (sort w) (sort g))]
        {:rule "mapping-differs" :element n
         :message (str "the result of " id " holds the label " n " " (pr-str g) ", not " (pr-str w)
                       ", the scenario's " (name e))})
      (when (and (contains? m :name) (not= (str (element-value pk (:name m))) (str (getk r :name))))
        [{:rule "mapping-differs" :element "name"
          :message (str "the result of " id " is named " (pr-str (getk r :name)) ", not "
                        (pr-str (element-value pk (:name m))))}])
      (when-let [links (need-links pk m)]
        (let [have (set (for [l (getk r :links)] [(getk l :type) (getk l :url)]))
              missing (remove #(have [(:type %) (:url %)]) links)]
          (when (seq missing)
            [{:rule "mapping-differs" :element "links"
              :message (str "the result of " id " has no " (str/join ", " (map #(str (:type %) " link to " (:name %)) missing))
                            ", the need" (when (< 1 (count missing)) "s") " the scenario traces to")}])))
      (let [names (attachment-names r)]
        (for [a (get-in m [:evidence (some-> (:level pk) keyword)])
              :when (not (names a))]
          {:rule "evidence-missing" :element a
           :message (str "the result of " id " has no " a " attachment, which :evidence requires for @" (:level pk))}))))))

;; ---------------------------------------------------------------------------
;; Running

(defn- now [] (System/currentTimeMillis))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)] (.printStackTrace e (java.io.PrintWriter. sw)) (str sw)))

(defn assertion?
  "Whether `e` is an assertion that did not hold."
  [e]
  (or (instance? AssertionError e)
      (boolean (some (fn [[k v]] (and (keyword? k) (#{"failed" "assertion"} (name k)) v)) (ex-data e)))))

(defn status-of-error
  "The status of a result whose step of the primary keyword `primary` threw `e`."
  [primary e]
  (if (and (= "Then" primary) (assertion? e)) "failed" "broken"))

(defn- one-line [s] (str/replace (str s) #"\s*\r?\n\s*" " "))

(defn- step-args [st m]
  (cond-> (:args m)
    (contains? st :doc-string) (conj (:doc-string st))
    (contains? st :data-table) (conj (:data-table st))))

(defn- run-steps [pk defs]
  (let [matches (mapv #(matching defs (:text %)) (:steps pk))
        problems (keep-indexed (fn [i ms]
                                 (let [st (nth (:steps pk) i)]
                                   (cond (empty? ms) (str "no step definition matches \"" (step-name st) "\"")
                                         (< 1 (count ms)) (str "the step \"" (step-name st) "\" is matched by "
                                                               (count ms) " definitions: "
                                                               (str/join " and " (map def-name ms)))
                                         :else nil)))
                               matches)
        t0 (now)]
    (cond
      (:wip? pk)
      {:status "skipped" :start t0 :stop t0
       :steps (mapv (fn [st] {:name (step-name st) :status "skipped" :start t0 :stop t0}) (:steps pk))}

      (seq problems)
      {:status "broken" :start t0 :stop (now)
       :statusDetails {:message (str/join "; " problems)}
       :steps (mapv (fn [st ms] {:name (step-name st) :status (if (= 1 (count ms)) "skipped" "broken")
                                 :start t0 :stop t0})
                    (:steps pk) matches)}

      :else
      (loop [i 0 ctx {} out [] failure nil]
        (if (>= i (count (:steps pk)))
          (cond-> {:status (or (:status failure) "passed") :start t0 :stop (now) :steps out}
            failure (assoc :statusDetails (:details failure)))
          (let [st (nth (:steps pk) i)
                s0 (now)]
            (if failure
              (recur (inc i) ctx (conj out {:name (step-name st) :status "skipped" :start s0 :stop s0}) failure)
              (let [m (first (nth matches i))
                    atts (atom [])
                    r (binding [*attachments* atts]
                        (try {:ctx (apply (:fn m) ctx (step-args st m))}
                             (catch Throwable e {:error e})))
                    s1 (now)
                    base {:name (step-name st) :start s0 :stop s1 :attachments @atts}]
                (if-let [e (:error r)]
                  (let [status (status-of-error (:primary st) e)
                        details {:message (one-line (str "\"" (step-name st) "\" (line " (:line st) "): "
                                                         (or (ex-message e) (.getName (class e)))))
                                 :trace (trace-of e)}]
                    (recur (inc i) ctx (conj out (assoc base :status status :statusDetails details))
                           {:status status :details details}))
                  (let [v (:ctx r)]
                    (recur (inc i) (if (map? v) v ctx) (conj out (assoc base :status "passed")) nil)))))))))))

(defn- write-json! [dir r]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true})))

(defn run-pickles!
  "Runs `pickles` against the registered definitions, writing each result
  into `out` (when given) and calling (cb pickle result): [[pickle result]].
  `opts` :mapping (default-mapping when none)."
  ([pickles out cb] (run-pickles! pickles out cb {}))
  ([pickles out cb {:keys [mapping]}]
   (let [defs @registry
         m (or mapping default-mapping)]
     (vec (for [pk pickles]
            (let [outcome (binding [*results-dir* (some-> out str)] (run-steps pk defs))
                  r (allure-result pk outcome m)]
              (when out (write-json! (str out) r))
              (when cb (cb pk r))
              [pk r]))))))

;; ---------------------------------------------------------------------------
;; The run task

(defn- cannot-run! [msg] (throw (ex-info (str msg) {:rdm-spec.kernel.command/cannot-run true})))

(defn- cfg [req k default]
  (let [v (get (:config req) k)] (if (some? v) v default)))

(defn- under-root [root p] (str (if (fs/absolute? p) p (fs/file root p))))

(defn run
  "The run task's stage: runs the project's scenarios against its step
  definitions; see the namespace doc. Result {:run {:results DIR :scenarios
  N :statuses {status n}}}."
  [req]
  (let [root (str (:root req))
        config (:config req)
        m (mapping-of config)
        _ (when-let [why (mapping-problem m)] (cannot-run! (str "the configuration's :allure cannot be used: " why)))
        out-rel (or (not-empty (get-in req [:opts :results]))
                    (not-empty (get (:env req) "RESULTS_DIR"))
                    (cfg req :results-dir "dhf/allure-results"))
        out (under-root root out-rel)
        {:keys [pickles refusals features]} (plan root {:spec-dir (cfg req :spec-dir "dhf/spec")
                                                       :id-prefix (cfg req :id-prefix "spec")
                                                       :model (cfg req :model "dhf/spec/model.edn")})
        _ (when (zero? features)
            (cannot-run! (str "no scenario was found: " (cfg req :spec-dir "dhf/spec") " holds no feature file")))
        steps-dir (under-root root (cfg req :steps-dir "test/steps"))
        files (step-files steps-dir)]
    (clear-definitions!)
    (when (fs/directory? steps-dir)
      (babashka.classpath/add-classpath (str (fs/parent steps-dir))))
    (doseq [f files]
      (try (load-file f)
           (catch Throwable e
             (cannot-run! (one-line (str "the step definition file " (rel-of root f) " cannot be loaded: "
                                         (ex-message e)))))))
    (let [runs (run-pickles! pickles out nil {:mapping m})
          bad (filter #(#{"failed" "broken"} (:status (second %))) runs)]
      {:result {:run {:results out-rel :scenarios (count runs)
                      :statuses (into (sorted-map) (frequencies (map (comp :status second) runs)))}}
       :findings (vec (concat
                       (map #(select-keys % [:file :line :rule :message :severity]) refusals)
                       (for [[pk r] bad]
                         {:file (:file pk) :line (or (get-in pk [:row :line]) (:line pk)) :severity :error
                          :rule "scenario-failed"
                          :message (one-line (str (:display-name pk) " is " (:status r) ": "
                                                  (get-in r [:statusDetails :message])))})
                       (map #(assoc % :severity :warning) (shadow-warnings))))})))
