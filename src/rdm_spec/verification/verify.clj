(ns rdm-spec.verification.verify
  "A cycle's gate: the room's implementation, in place of the implementation of
  a fresh export of the cut, passes every check the project declares, in
  order, the first that fails stopping it; recorded in ratified.edn
  (dhf/spec/06-regenerate/verify.feature; 08-plugins, 11-project-in-a-folder,
  12-several-specs and 13-slice-development's verify.feature)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.plugins :as plugins]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

;; ------------------------------------------------------------------ the configuration

(def kinds [:tests :serves :runs :plugin])

(defn impl-roots [cfg] (vec (:implementation cfg)))

(defn- under-impl? [cfg arg]
  (some #(or (= arg %) (str/starts-with? (str arg) (str (str/replace % #"/$" "") "/"))) (impl-roots cfg)))

(defn verify-problem
  "Why a :verify cannot be trusted, or nil."
  [cfg checks]
  (cond
    (nil? checks) "the configuration declares no :verify"
    (not (and (sequential? checks) (seq checks) (every? map? checks))) "the configuration's :verify is not a vector of checks"
    :else
    (or (when-let [bad (first (remove #(some #{(:kind %)} kinds) checks))]
          (str (pr-str (:kind bad)) " is not a kind of check: the kinds are :tests, :serves, :runs and :plugin"))
        (when-not (= :tests (:kind (first checks)))
          "a project is verified by its tests first: :verify declares a :tests check, first")
        (first (for [c checks
                     :let [n (or (:name c) (name (:kind c)))
                           why (case (:kind c)
                                 :tests (when-not (and (vector? (:command c)) (seq (:command c))) "the tests check names no command")
                                 :serves (cond (not (and (seq (:start c)) (integer? (:port c)) (number? (get-in c [:budget :seconds])) (pos-int? (get-in c [:budget :polls]))))
                                               (str "the :serves check " n " declares :start, :port and a :budget of :seconds and :polls")
                                               (not (seq (:probe c))) (str "the :serves check " n " declares no :probe")
                                               (some #(under-impl? cfg %) (:probe c))
                                               (str "the :serves check " n " is refused: the probe is in the implementation the builder writes"))
                                 :runs (when-not (and (seq (:command c)) (number? (get-in c [:budget :seconds])))
                                         (str "the :runs check " n " declares a :command and a :budget of :seconds"))
                                 :plugin (when-not (some #(and (= (:name c) (:name %)) (= :Check (:answers %))) (:plugins cfg))
                                           (str "the :plugin check " (:name c) " is refused: the check names no plugin answering :Check")))]
                     :when why]
                 why)))))

;; ------------------------------------------------------------------ the Allure mapping

(def allure-elements #{:epic :feature :story :name :step :parameter :labels :links :evidence :suite :subSuite :fullName :historyId})
(def spec-elements #{:context :feature :rule :scenario :gherkin-step :example-row :scenario-id :component :component-id :gherkin-tags :need :level})

(defn default-mapping [needs]
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url (str needs "#{id}")}} :evidence {}})

(defn mapping-problem
  "Why an :allure mapping cannot be read, or nil."
  [m]
  (or (first (for [k (keys m) :when (not (allure-elements k))] (str (pr-str k) " is not an element of an Allure result")))
      (first (for [[k v] (dissoc m :labels :links :evidence) :when (not (spec-elements v))] (str (pr-str v) " is not an element of the spec")))
      (first (for [[_ v] (:labels m) :when (not (spec-elements v))] (str (pr-str v) " is not an element of the spec")))
      (when (and (contains? m :labels) (not (contains? (:labels m) "spec"))) "the label spec, from the scenario's id, is required")
      (when (and (:evidence m) (not (and (map? (:evidence m)) (every? sequential? (vals (:evidence m))))))
        ":evidence is {level [attachment-name ...]}")))

(defn- labels [r n] (vec (for [l (get r "labels") :when (= n (get l "name"))] (get l "value"))))

(defn- row-values [sc r]
  (into {} (for [p (get r "parameters")] [(get p "name") (str (get p "value"))])))

(defn- expected-steps [sc r]
  (let [vals (row-values sc r)]
    (vec (for [s (concat (:background sc) (:steps sc))]
           (str (:keyword s) " " (reduce (fn [t [k v]] (str/replace t (str "<" k ">") v)) (:text s) vals))))))

(defn- value-of [element sc r]
  (case element
    :context (some-> (:context sc) name)
    :feature (:feature sc)
    :rule (:rule sc)
    :scenario (:title sc)
    :scenario-id (:id sc)
    :component (:component-name sc)
    :component-id (some-> (:component sc) (#(str (namespace %) "/" (name %))))
    :level (some-> (:level sc) name)
    nil))

(defn mapping-findings
  "How a result breaks the mapping, for the stamped scenario it names."
  [mapping sc r file & [text]]
  (let [line (fn [needle] (or (when text (project/line-of text needle)) 1))
        f (fn [rule needle what] (project/finding file (line needle) rule (str (:id sc) ": " what)))
        lbl {:epic "epic" :feature "feature" :story "story" :suite "suite" :subSuite "subSuite"}]
    (if (:wip? sc)
      []
      (vec
       (concat
        (for [[k el] (dissoc mapping :labels :links :evidence :step :parameter)
              :let [want (value-of el sc r)
                    got (if (lbl k) (labels r (lbl k)) [(get r (name k))])]
              :when (and want (not= [want] got))]
          (f (str "mapping-differs: " (name k)) (str "\"" (if (lbl k) "value" (name k)) "\"")
             (str "its " (name k) " is " (pr-str (if (= 1 (count got)) (first got) got)) " where the scenario's " (name el) " is " (pr-str want))))
        (when (= :gherkin-step (:step mapping))
          (let [want (expected-steps sc r) got (mapv #(get % "name") (get r "steps"))]
            (when (not= want got)
              [(f "steps-differ-from-scenario" "\"steps\"" (str "its steps " (pr-str got) " are not the scenario's " (pr-str want)))])))
        (when (and (= :example-row (:parameter mapping)) (:outline? sc))
          (let [vals (row-values sc r)]
            (when-not (some #(= (:values %) vals) (:rows sc))
              [(f "mapping-differs: parameter" "\"parameters\"" (str "its parameters " (pr-str vals) " name no row of the outline"))])))
        (for [[n el] (:labels mapping)
              :let [want (if (= el :gherkin-tags) (vec (:tags sc)) (some-> (value-of el sc r) vector))
                    got (labels r n)]
              :when (and want (not= want got))]
          (f (str "mapping-differs: " n) "\"labels\"" (str "its " n " labels are " (pr-str got) " where the scenario's are " (pr-str want))))
        (when-let [{t :type u :url} (get-in mapping [:links :need])]
          (for [n (:needs sc)
                :let [want {"type" t "name" n "url" (str/replace u "{id}" n)}]
                :when (not (some #(= want (select-keys % ["type" "name" "url"])) (get r "links")))]
            (f "mapping-differs: links" "\"links\"" (str "it has no " t " link " (pr-str want) " to " n ", a need it traces to"))))
        (for [a (get-in mapping [:evidence (:level sc)])
              :let [names (set (map #(get % "name") (concat (get r "attachments") (mapcat #(get % "attachments") (get r "steps")))))]
              :when (not (names a))]
          (f (str "evidence-missing: " a) "\"attachments\"" (str "it carries no attachment named " a ", which a " (name (:level sc)) " scenario requires"))))))))

;; ------------------------------------------------------------------ scope and coverage

(defn in-scope
  "The scenarios a cycle's scope requires results for: a spec or slice scope narrows them; any other scope is the whole spec."
  [scenarios scope slice-reach]
  (let [entries (if (sequential? scope) scope [scope])
        specs (set (keep #(when (map? %) (:spec %)) entries))
        slices (keep #(when (map? %) (:slice %)) entries)
        comps (set (mapcat #(get slice-reach %) slices))]
    (if (or (seq specs) (seq slices))
      (filterv #(or (specs (:spec %)) (comps (:component %))) scenarios)
      (vec scenarios))))

(defn coverage [scenarios statuses]
  {:missing (vec (for [sc scenarios :when (#{:unverified nil} (statuses (:id sc)))] (:id sc)))
   :not-passed (vec (for [sc scenarios :when (= :failed (statuses (:id sc)))] (:id sc)))})

(defn record [cut tree outcome checkout checks reason]
  (cond-> {:cut cut :room-tree tree :outcome outcome}
    checkout (assoc :checkout checkout)
    true (assoc :checks checks)
    reason (assoc :reason reason)))

(defn poll-times [seconds polls] (vec (for [i (range 1 (inc polls))] (long (* 1000 i (/ seconds polls))))))

(defn margin [budget secs] (/ (Math/round (* 100.0 (- budget secs))) 100.0))

;; ------------------------------------------------------------------ the room's implementation

(defn room-index!
  "Indexes the room's implementation as git would commit it, into a temporary git dir. Returns {:git-dir :tree}."
  [room cfg]
  (let [g (project/temp-dir "room-index-")
        roots (filter #(fs/exists? (fs/path room %) {:nofollow-links true}) (impl-roots cfg))
        gitc (fn [& args] (apply project/sh room "git" (str "--git-dir=" g) (str "--work-tree=" room) args))]
    (project/git! g "init" "-q" "--bare")
    (when (seq roots)
      (let [r (apply gitc "add" "-A" "--" roots)]
        (when-not (zero? (:exit r)) (project/cannot-run! (str "git cannot read the room's implementation: " (project/one-line (:err r)))))))
    {:git-dir g :tree (str/trim (:out (gitc "write-tree")))}))

(defn place-implementation!
  "Replaces the checkout's implementation with the room's, as indexed."
  [build cfg {:keys [git-dir]}]
  (doseq [r (impl-roots cfg) :let [p (fs/path build r)]]
    (when (or (fs/exists? p {:nofollow-links true}) (fs/sym-link? p))
      (if (fs/directory? p {:nofollow-links true}) (fs/delete-tree p) (fs/delete p))))
  (let [r (project/sh build "git" (str "--git-dir=" git-dir) (str "--work-tree=" build) "checkout-index" "-a" "-f")]
    (when-not (zero? (:exit r)) (project/cannot-run! (str "the room's implementation cannot be placed in the checkout: " (project/one-line (:err r)))))))

;; ------------------------------------------------------------------ the checks

(defn- answered? [port timeout-ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int timeout-ms))
      (.setSoTimeout s (int timeout-ms))
      (let [out (.getOutputStream s)]
        (.write out (.getBytes "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"))
        (.flush out)
        (let [buf (byte-array 5) n (.read (.getInputStream s) buf)]
          (and (= 5 n) (= "HTTP/" (String. buf 0 5))))))
    (catch Exception _ false)))

(defn port-held? [port]
  (or (try (with-open [s (java.net.Socket.)]
             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300) true)
           (catch Exception _ false))
      (try (with-open [ss (java.net.ServerSocket.)]
             (.setReuseAddress ss true)
             (.bind ss (java.net.InetSocketAddress. (int port)))
             false)
           (catch Exception _ true))))

(defn- fill [argv k v] (mapv #(str/replace % k (str v)) argv))

(defn- secs [start] (/ (Math/round (* 100.0 (/ (- (System/nanoTime) start) 1e9))) 100.0))

(defn run-serves [c build]
  (let [n (or (:name c) "serves") port (:port c)
        {s :seconds k :polls} (:budget c)
        ps (or (:probe-seconds c) 30)
        start (System/nanoTime)]
    (if (port-held? port)
      {:name n :kind :serves :outcome :refused :reason (str "the port " port " is already held by another process: a :serves check measures only its own app")}
      (let [proc (try (p/process (fill (:start c) "{port}" port) {:dir build :out :string :err :string})
                      (catch Exception e nil))]
        (if-not proc
          {:name n :kind :serves :outcome :refused :reason (str "the :serves check " n " cannot start its app " (pr-str (:start c)))}
          (try
            (let [times (poll-times s k)
                  poll (loop [i 1 [t & more] times]
                         (if (nil? t)
                           nil
                           (let [wait (- (+ (quot start 1000000) t) (quot (System/nanoTime) 1000000))]
                             (when (pos? wait) (Thread/sleep wait))
                             (if (answered? port (max 100 (min 1000 (long (* 1000 (/ s k))))))
                               i
                               (if (and (not (.isAlive ^Process (:proc proc))) (> i 0))
                                 (recur (inc i) more)
                                 (recur (inc i) more))))))]
              (if-not poll
                {:name n :kind :serves :outcome :failed :seconds (secs start) :polls k :budget s
                 :reason (str "no answer came within " s " seconds (" k " polls on port " port ")")}
                (let [url (str "http://127.0.0.1:" port "/")
                      r (project/run-cmd (fill (:probe c) "{url}" url) {:dir build :seconds ps})
                      out (project/one-line (str (:out r) " " (:err r)))
                      base {:name n :kind :serves :seconds (secs start) :answered-on-poll poll :polls k :budget s :probe-seconds ps}]
                  (cond
                    (:not-found? r) (assoc base :outcome :refused :reason (str "the probe of " n " cannot be started: " (pr-str (:probe c))))
                    (:timed-out? r) (assoc base :outcome :failed :reason (str "the probe timed out after " ps " seconds and was stopped; it printed: " out))
                    (not (zero? (:exit r))) (assoc base :outcome :failed :reason (str "the probe exited " (:exit r) ": the app that answered is not this application; it printed: " out))
                    :else (assoc base :outcome :passed :margin (- k poll))))))
            (finally
              (p/destroy-tree proc)
              (deref (future (deref proc)) 3000 nil))))))))

(defn run-runs [c build]
  (let [n (or (:name c) "runs") budget (get-in c [:budget :seconds])
        r (project/run-cmd (:command c) {:dir build :seconds budget})
        sec (/ (Math/round (* 100.0 (:seconds r))) 100.0)
        base {:name n :kind :runs :seconds sec :budget budget}
        {:keys [exit stdout-starts]} (:expect c)
        first-line (first (str/split-lines (str (:out r))))]
    (cond
      (:not-found? r) (assoc base :outcome :refused :reason (str "the check " n " cannot find its command " (first (:command c))))
      (:timed-out? r) (assoc base :outcome :failed :reason (str "no answer within the budget of " budget " seconds: the command was stopped"))
      (and exit (not= exit (:exit r))) (assoc base :outcome :failed :reason (str "it answered with the exit " (:exit r) " where " exit " was expected"))
      (and stdout-starts (not (str/starts-with? (str (:out r)) stdout-starts)))
      (assoc base :outcome :failed :reason (str "its first line " (pr-str first-line) " does not start with " (pr-str stdout-starts)))
      :else (assoc base :outcome :passed :margin (margin budget sec)))))

(defn- hold-results
  "Holds the results in dir to the mapping and the scenarios in scope: [findings named-count]."
  [root dir mapping all-scs scope-scs]
  (let [{rs :results unreadable :unreadable} (results/read-dir (str (fs/path root dir)) dir)
        stamped (filter :id all-scs)
        by-id (into {} (map (juxt :id identity) stamped))
        {:keys [statuses]} (status/derive-statuses stamped rs)
        req (remove :wip? scope-scs)
        {:keys [missing not-passed]} (coverage req statuses)
        named (count (filter #(some (fn [r] (= (:id %) (:spec r))) rs) req))
        f (concat
           (for [u unreadable] (project/finding (:file u) "result-unreadable" (:why u)))
           (for [r rs :let [sc (by-id (:spec r))] :when sc
                 x (mapping-findings mapping sc (:raw r) (:file r) (slurp (str (:path r))))]
             x)
           (for [id missing :let [sc (by-id id)]] (project/finding (:file sc) (:line sc) "scenario-unverified" (str id " is named by no passing result")))
           (for [id not-passed :let [sc (by-id id)]] (project/finding (:file sc) (:line sc) "scenario-failed" (str id " is failed: a result naming it did not pass"))))]
    [(vec f) named (count rs)]))

(defn run-tests [c ctx]
  (let [{:keys [root build cycle-dir mapping scenarios scope-scs]} ctx
        rel-results (str cycle-dir "/results")
        abs-results (str (fs/absolutize (fs/path root rel-results)))
        log (str cycle-dir "/test.log")
        start (System/nanoTime)]
    (when (fs/exists? abs-results) (fs/delete-tree abs-results))
    (fs/create-dirs abs-results)
    (let [r (project/run-cmd (:command c) {:dir build :env {"RESULTS_DIR" abs-results}})
          sec (secs start)
          _ (spit (str (fs/path root log)) (str (:out r) (:err r)))
          base {:name (or (:name c) "tests") :kind :tests :seconds sec}]
      (cond
        (:not-found? r) [(assoc base :outcome :refused :reason (str "the test command " (pr-str (:command c)) " cannot be found")) []]
        (not (zero? (:exit r))) [(assoc base :outcome :failed :reason (str "the test command failed with exit " (:exit r) "; its output is in " log)) []]
        (empty? (fs/glob abs-results "*-result.json"))
        [(assoc base :outcome :failed :reason "the test command exited 0 and wrote no Allure result: a green exit with no results is not a run") []]
        :else
        (let [[fs* named] (hold-results root rel-results mapping scenarios scope-scs)]
          (if (seq fs*)
            [(assoc base :outcome :failed :reason (str (count fs*) " findings in the results: each is named")) fs*]
            [(assoc base :outcome :passed :scenarios named) []]))))))

(defn run-plugin-check [c ctx]
  (let [{:keys [root build cycle-dir cycle cut cfg mapping scenarios scope-scs]} ctx
        n (:name c)
        rel (str cycle-dir "/results/" n)
        start (System/nanoTime)]
    (fs/create-dirs (fs/path root rel))
    (let [request {:request :Check :cycle cycle :cut cut :checkout (str cycle-dir "/build") :results rel}
          a (plugins/ask-check (:plugins cfg) n request build)
          base {:name n :kind :plugin :seconds (secs start)}
          rdir (cond (seq (fs/glob (fs/path root rel) "*-result.json")) rel
                     (seq (fs/glob (fs/path build rel) "*-result.json")) (str cycle-dir "/build/" rel))]
      (cond
        (not= :passed (:outcome a)) [(merge base (select-keys a [:outcome :reason])) (vec (:findings a))]
        rdir (let [[fs* named] (hold-results root rdir mapping scenarios scope-scs)]
               (if (seq fs*)
                 [(assoc base :outcome :failed :reason (str "the plugin " n "'s results hold " (count fs*) " findings")) fs*]
                 [(assoc base :outcome :passed :scenarios named) []]))
        :else [(assoc base :outcome :passed) []]))))

;; ------------------------------------------------------------------ the task

(defn read-cycle [root cfg cycle]
  (let [rel (str (:cycles-dir cfg) "/" cycle "/cycle.edn")
        f (fs/path root rel)]
    (when-not (fs/exists? f) (project/cannot-run! (str "no " rel ": a cycle is declared by hand before it is verified")))
    (let [c (try (edn/read-string (slurp (str f))) (catch Exception e (project/cannot-run! (str rel " cannot be read: " (project/one-line (ex-message e))))))]
      (when-not (and (map? c) (string? (:cut c))) (project/cannot-run! (str rel " names no :cut")))
      (when (and (contains? c :slice) (contains? c :scope))
        (project/cannot-run! (str rel " holds both :slice and :scope: a cycle names the slice it builds, or its scope")))
      (cond-> c (:slice c) (assoc :scope [{:slice (:slice c)}])))))

(defn verify [run gate!]
  (let [root (:root run)
        wcfg (:cfg run)
        [room-arg cycle] (:args run)
        room (str (fs/absolutize (fs/path root room-arg)))
        cyc (read-cycle root wcfg cycle)
        cut-name (:cut cyc)
        g (gate! run {:request :Gate :task :verify :cut cut-name :cycle cycle})]
    (if (or (seq (remove :warning? (:findings g))) (:exit g))
      g
      (let [_ (when-not (cut/tag-commit root cut-name) (project/cannot-run! (str cut-name " is not a cut: verify trusts only a cut, a commit tagged spec-N")))
            _ (when-not (fs/directory? room) (project/cannot-run! (str "the room " room-arg " does not exist")))
            cycle-dir (str (:cycles-dir wcfg) "/" cycle)
            build-rel (str cycle-dir "/build")
            build (str (fs/absolutize (fs/path root build-rel)))
            ratified (fs/path root cycle-dir "ratified.edn")
            _ (when (fs/exists? build) (fs/delete-tree build))
            _ (when (fs/exists? ratified) (fs/delete ratified))
            _ (cut/export! root cut-name build)
            refuse! (fn [why] (fs/delete-tree build) (project/cannot-run! why))
            cfg (project/with-defaults (or (try (edn/read-string (slurp (str (fs/path build project/config-path)))) (catch Exception _ nil))
                                           (refuse! (str "the cut " cut-name " holds no readable " project/config-path))))
            _ (when-let [p (plugins/problem cfg (:plugins cfg []))] (refuse! p))
            _ (when-let [p (verify-problem cfg (:verify cfg))] (refuse! p))
            mapping (or (:allure cfg) (default-mapping (:needs cfg)))
            _ (when-let [p (mapping-problem mapping)] (refuse! p))
            _ (try (parser/validate-specs! build cfg) (catch clojure.lang.ExceptionInfo e (refuse! (ex-message e))))
            idx (room-index! room cfg)
            tree (:tree idx)
            reading (try (place-implementation! build cfg idx)
                         (parser/read-specs build cfg)
                         (finally (fs/delete-tree (:git-dir idx))))
            scenarios (vec (mapcat :scenarios (:specs reading)))
            unstamped (filter #(not= 1 (count (:ids %))) scenarios)]
        (if (seq unstamped)
          (let [fs* (vec (for [sc unstamped] (project/finding (:file sc) (:line sc) "scenario-unstamped"
                                                              (str "the scenario \"" (:title sc) "\" carries " (if (empty? (:ids sc)) "no id" "more than one id")
                                                                   ": a cut holding an unstamped scenario is not verified"))))]
            (spit (str ratified) (str (project/pprint-str (record cut-name tree :refused build-rel [] "the cut holds an unstamped scenario")) ""))
            {:findings fs* :result {:verify {:cycle cycle :cut cut-name :outcome :refused :checks []}}})
          (let [rel-files (for [f (project/files-under build (:spec-dir cfg))] (subs f (inc (count (:spec-dir cfg)))))
                model (or (parser/read-model-lenient (fs/path build (:model cfg))) {})
                scope (:scope cyc :project)
                slices (keep #(when (map? %) (:slice %)) (if (sequential? scope) scope [scope]))
                _ (doseq [s slices] (when-not (some #{s} (parser/slices-of rel-files)) (refuse! (str "the cut " cut-name " holds no slice " s))))
                reach (into {} (for [s slices] [s (parser/slice-reach model rel-files s)]))
                scope-scs (in-scope (filter :id scenarios) scope reach)
                ctx {:root root :build build :cycle-dir cycle-dir :cycle cycle :cut cut-name :cfg cfg :mapping mapping
                     :scenarios scenarios :scope-scs scope-scs}
                [checks findings]
                (loop [cs (:verify cfg) done [] fs* [] stopped false]
                  (if (empty? cs)
                    [done fs*]
                    (let [c (first cs)
                          n (or (:name c) (name (:kind c)))]
                      (if stopped
                        (recur (rest cs) (conj done {:name n :kind (:kind c) :outcome :not-run}) fs* true)
                        (let [[res more] (case (:kind c)
                                           :tests (run-tests c ctx)
                                           :serves [(run-serves c build) []]
                                           :runs [(run-runs c build) []]
                                           :plugin (run-plugin-check c ctx))]
                          (recur (rest cs) (conj done res) (into fs* more) (not= :passed (:outcome res))))))))
                failed (first (filter #(#{:failed :refused} (:outcome %)) checks))
                outcome (if failed :refused :passed)
                refused? (some #(= :refused (:outcome %)) checks)]
            (spit (str ratified) (project/pprint-str (record cut-name tree outcome build-rel checks (when failed (str (:name failed) ": " (:reason failed))))))
            (when-not failed
              (bus/publish! :CycleVerified :verification/verify {:cycle cycle :cut cut-name :checkout build-rel :checks checks}))
            (cond-> {:findings (vec (concat findings
                                            (when (and failed (not refused?))
                                              [(project/finding (str cycle-dir "/ratified.edn") "verify-refused" (str "the check " (:name failed) " " (name (:outcome failed)) ": " (:reason failed)))])))
                     :result {:verify {:cycle cycle :cut cut-name :outcome outcome :checks checks}}}
              refused? (assoc :exit 2 :why (str "the check " (:name failed) " cannot run: " (:reason failed))))))))))
