(ns rdm-spec.verification.verify
  "The Verify component of the verification context
  (dhf/spec/verification/verify.feature; glossary, \"Verify\", \"Check\"):
  `rdm-spec verify ROOM CYCLE`, a cycle's gate. The room's implementation,
  put in place of the implementation of a fresh export of the cycle's cut,
  passes every check the cut's configuration declares (:verify), in order,
  the first that fails stopping it; the rest are listed :not-run.

  In order, before any check runs:
    the cycle's cycle.edn and its :cut (cannot run without them);
    the cut's configuration: its :verify (a :tests check first, with a
    command; every kind :tests, :serves or :runs; a :serves probe outside
    the implementation) and its :allure (rdm-spec's default when none),
    else cannot run;
    the spec at the cut: a scenario with no single id stops it (exit 1);
    the room exists, and every :serves port is free (a port only closing
    connections hold is free), else cannot run.
  Then dhf/cycles/CYCLE/build is the export of the cut with the
  implementation paths (:implementation) replaced by the room's.

  Checks, each reporting its outcome and the seconds it took:
    :tests   its command, run in the checkout with RESULTS_DIR the absolute
             dhf/cycles/CYCLE/results of this repository, its output in
             dhf/cycles/CYCLE/test.log; it passes when the command exits 0,
             wrote results, every stamped scenario not tagged @wip is named by
             a result and every result naming it passed, and every result of
             a stamped scenario holds to the mapping (a planned scenario's to
             its spec label only). :scenarios is how many such scenarios
             results name.
    :serves  starts the app ({port}), polls GET / on the port every
             seconds/polls seconds; any HTTP response answers, an accepted
             connection with none does not; then its probe ({url}) must exit
             0. :answered-on-poll, :polls, :margin (polls left unused). Every
             process it started is stopped.
    :runs    its command answers within :budget with :expect's :exit and
             :stdout-starts; :budget and :margin in seconds.
  A command that cannot be started cannot run (exit 2), naming the check.

  dhf/cycles/CYCLE/ratified.edn: {:cut :room-tree (the git tree of the
  room's implementation paths) :outcome :passed | :refused :checkout
  :checks}, with :reason when refused. Result: {:verify {:cycle :cut
  :outcome :checks}}.

  Mapping findings name the result's file and the line of the element in
  it: steps-differ-from-scenario, mapping-differs (ELEMENT: what),
  evidence-missing (NAME: what)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as g]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; What the configuration declares

(def kinds #{:tests :serves :runs})

(defn- under? [w p]
  (let [p (str/replace (str p) #"/+$" "")
        w (str/replace (str w) #"^\./" "")]
    (or (= w p) (str/starts-with? w (str p "/")))))

(defn config-problem
  "Why the declared checks `checks` cannot be trusted, or nil."
  [checks implementation]
  (cond
    (nil? checks) "the configuration declares no :verify: a project says what verifies it"
    (not (and (sequential? checks) (every? map? checks))) ":verify is not a list of checks"
    (not= :tests (:kind (first checks))) "the first check is not a :tests check: a project is verified by its tests first"
    :else
    (or (some (fn [c]
                (cond
                  (not (kinds (:kind c))) (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves and :runs")
                  (and (= :tests (:kind c)) (empty? (:command c))) "the tests check names no command"
                  (and (= :runs (:kind c)) (empty? (:command c))) (str "the check " (:name c) " names no command")
                  (and (= :serves (:kind c)) (or (empty? (:start c)) (not (integer? (:port c)))))
                  (str "the check " (or (:name c) "serves") " names no :start or no :port")
                  (and (= :serves (:kind c)) (some (fn [w] (some #(under? w %) implementation)) (:probe c)))
                  (str "the probe of the check " (or (:name c) "serves")
                       " is under the implementation: the probe is in the implementation the builder writes")))
              checks)
        (when-not (some #(= :tests (:kind %)) checks) "a project is verified by its tests first"))))

(def allure-elements #{:epic :feature :story :suite :subSuite :name :fullName :step :parameter :labels :links :evidence})
(def label-elements #{:epic :feature :story :suite :subSuite})
(def spec-elements #{:context :feature :rule :scenario :scenario-id :component :component-id :gherkin-tags
                     :gherkin-step :example-row})

(defn mapping-problem
  "Why the mapping `m` cannot be read, or nil."
  [m]
  (cond
    (not (map? m)) ":allure is not a map"
    :else
    (or (some (fn [k] (when-not (allure-elements k) (str (pr-str k) " is not an element of an Allure result"))) (keys m))
        (some (fn [[k v]] (when (and (or (label-elements k) (#{:name :fullName :step :parameter} k)) (not (spec-elements v)))
                            (str (pr-str v) " is not an element of the spec")))
              m)
        (some (fn [[_ v]] (when-not (spec-elements v) (str (pr-str v) " is not an element of the spec"))) (:labels m))
        (when-not (= :scenario-id (get (:labels m) "spec"))
          "the label spec, from the scenario's id, is required: :labels {\"spec\" :scenario-id}"))))

;; ---------------------------------------------------------------------------
;; A result held to the mapping

(defn- expected [p el]
  (case el
    :context (:context p)
    :feature (:feature p)
    :rule (:rule p)
    :scenario (:name p)
    :scenario-id (:id p)
    :component (:component p)
    :component-id (:component-id p)
    :gherkin-tags (set (map #(subs % 1) (:tags p)))
    :gherkin-step (mapv runner/step-name (:steps p))
    :example-row (mapv (fn [[n v]] {:name n :value v}) (:parameters p))
    nil))

(defn- label-values [r nm] (vec (keep #(when (= nm (:name %)) (:value %)) (:labels r))))

(defn- line-of [text token]
  (or (some (fn [[i l]] (when (str/includes? l token) (inc i))) (map-indexed vector (str/split-lines (str text)))) 1))

(defn mapping-findings
  "The findings of the result `r` (read with keyword keys) of the pickle
  `p` held to the `mapping`; `at` {:file :text} is the result's file."
  [mapping p r {:keys [file text]}]
  (let [id (:id p)
        f (fn [token rule msg] (command/finding file (line-of text token) rule msg))
        label-name {:epic "epic" :feature "feature" :story "story" :suite "suite" :subSuite "subSuite"}]
    (vec
     (concat
      (for [[k el] mapping :when (label-elements k)
            :let [want (expected p el) got (label-values r (label-name k))]
            :when (not= (if want [want] []) got)]
        (f (str "\"" (label-name k) "\"") "mapping-differs"
           (str (name k) ": the result of " id " carries the label " (label-name k) " " (pr-str got)
                " where the scenario's " (name el) " is " (pr-str want))))
      (when-let [el (:name mapping)]
        (when (not= (expected p el) (:name r))
          [(f "\"name\"" "mapping-differs"
              (str "name: the result of " id " is named " (pr-str (:name r)) " where the scenario's " (name el)
                   " is " (pr-str (expected p el))))]))
      (when (:step mapping)
        (let [want (expected p (:step mapping)) got (mapv :name (:steps r))]
          (when (not= want got)
            [(f "\"steps\"" "steps-differ-from-scenario"
                (str id ": the result's steps are " (pr-str got) " where the scenario's lines are " (pr-str want)))])))
      (when (and (:parameter mapping) (seq (:parameters p)))
        (let [want (expected p (:parameter mapping)) got (mapv #(select-keys % [:name :value]) (:parameters r))]
          (when (not= want got)
            [(f "\"parameters\"" "mapping-differs"
                (str "parameter: the result of " id " holds the parameters " (pr-str got) " where the row's are " (pr-str want)))])))
      (for [[lname el] (:labels mapping)
            :let [want (expected p el) got (label-values r lname)]
            :when (if (set? want) (not= want (set got)) (not= (if want [want] []) got))]
        (f (str "\"" lname "\"") "mapping-differs"
           (str "labels: the result of " id " carries the label " lname " " (pr-str got)
                " where the scenario's " (name el) " is " (pr-str (if (set? want) (sort want) want)))))
      (when-let [{:keys [type url]} (get-in mapping [:links :need])]
        (for [n (:needs p)
              :let [u (str/replace (str url) "{id}" n)]
              :when (not-any? #(and (= type (:type %)) (= u (:url %))) (:links r))]
          (f "\"links\"" "mapping-differs"
             (str "links: the result of " id " holds no " type " link to " n " (" u "), the need the scenario traces to"))))
      (let [level (some #(when (#{"@e2e" "@contract"} %) (keyword (subs % 1))) (:tags p))
            names (set (map :name (concat (:attachments r) (mapcat :attachments (:steps r)))))]
        (for [a (get-in mapping [:evidence level]) :when (not (names a))]
          (f "\"attachments\"" "evidence-missing"
             (str a ": the result of " id " carries no attachment named " a ", which :evidence requires of a "
                  (name level) " scenario"))))))))

;; ---------------------------------------------------------------------------
;; A :runs check's answer

(defn runs-problem
  "Why the answer `a` ({:exit :out :timed-out?}) is not what `expect`
  holds, within `budget` seconds, or nil."
  [expect a budget]
  (cond
    (:timed-out? a) (str "it did not answer within the budget of " budget " seconds")
    (and (contains? expect :exit) (not= (:exit expect) (:exit a)))
    (str "it exited " (:exit a) " where " (:exit expect) " was expected")
    (and (:stdout-starts expect) (not (str/starts-with? (str (:out a)) (:stdout-starts expect))))
    (str "its standard output starts " (pr-str (first (str/split-lines (str (:out a)))))
         " where " (pr-str (:stdout-starts expect)) " was expected")))

;; ---------------------------------------------------------------------------
;; Processes

(defn- seconds-since [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e7)) 100.0))

(defn- stop! [proc]
  (when proc
    (let [^Process pr (:proc proc)]
      (doseq [h (iterator-seq (.iterator (.descendants (.toHandle pr))))] (.destroyForcibly ^java.lang.ProcessHandle h))
      (.destroyForcibly pr)
      (.waitFor pr 5 java.util.concurrent.TimeUnit/SECONDS))))

(defn- start! [check cmd opts]
  (try (apply p/process (merge {:in ""} opts) (map str cmd))
       (catch java.io.IOException e
         (command/cannot-run! (str "the check " (:name check) " cannot run: the command " (first cmd)
                                   " is not found (" (ex-message e) ")")))))

(defn- fill [words k v] (mapv #(str/replace (str %) k (str v)) words))

(defn port-held?
  "Whether a process holds `port`: one accepts connections on it, or it
  cannot be bound. A port only closing connections hold is free."
  [port]
  (or (try (with-open [s (java.net.Socket.)]
             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
             true)
           (catch Exception _ false))
      (try (with-open [ss (java.net.ServerSocket.)]
             (.setReuseAddress ss true)
             (.bind ss (java.net.InetSocketAddress. (int port)))
             false)
           (catch Exception _ true))))

(defn- answers?
  "Whether GET / on `port` gets any HTTP response within `ms`."
  [port ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int (max 50 ms)))
      (.setSoTimeout s (int (max 50 ms)))
      (let [o (.getOutputStream s)]
        (.write o (.getBytes (str "GET / HTTP/1.0\r\nHost: 127.0.0.1:" port "\r\n\r\n")))
        (.flush o))
      (let [buf (byte-array 5) n (.read (.getInputStream s) buf)]
        (and (pos? n) (str/starts-with? (String. buf 0 (int n)) "HTTP"))))
    (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; The checks

(defn- tests-check [req c {:keys [checkout results-rel log-rel cut-req commit cfg mapping]}]
  (let [t0 (System/nanoTime)
        results-abs (str (fs/absolutize (command/path req results-rel)))
        _ (fs/delete-tree results-abs)
        _ (fs/create-dirs results-abs)
        proc (start! (assoc c :name "tests") (:command c)
                     {:dir (str checkout) :out :string :err :string
                      :extra-env {"RESULTS_DIR" results-abs}})
        r @proc
        _ (spit (str (command/path req log-rel)) (str (:out r) (:err r)))
        cmd (str/join " " (:command c))
        fail (fn [why fs*] {:outcome :failed :seconds (seconds-since t0) :reason why :findings fs*})]
    (cond
      (not (zero? (:exit r)))
      (let [why (str "the test command " cmd " failed: it exited " (:exit r) "; its output is in " log-rel)]
        (fail why [(command/finding log-rel "tests-failed" why)]))
      (empty? (fs/glob results-abs "*-result.json"))
      (let [why (str "the test command " cmd " exited 0 and wrote no Allure result into " results-rel
                     ": a green exit with no results is not a run")]
        (fail why [(command/finding results-rel "tests-no-results" why)]))
      :else
      (let [read (results/read-results req results-rel {:keep? true})
            spec (parser/read-spec (:root req) cfg {:revision commit})
            owners (runner/owners-of (or (cut/edn-at cut-req commit (command/config-value {:config cfg} :model)) {}))
            pickles (vec (mapcat (fn [{:keys [file text]}]
                                   (when (str/ends-with? file ".feature")
                                     (runner/pickles-of file (g/parse-feature file text)
                                                        {:spec-dir (command/config-value {:config cfg} :spec-dir)
                                                         :id-prefix (command/config-value {:config cfg} :id-prefix)
                                                         :owners owners})))
                                 (:files spec)))
            by-id (group-by :id (filter :id pickles))
            scs (filter :id (:scenarios spec))
            rs-of (group-by :spec (:results read))
            fs* (vec (concat
                      (results/unreadable-findings read)
                      (for [sc scs :when (not (:wip? sc))
                            :let [rs (get rs-of (:id sc))]
                            :when (empty? rs)]
                        (command/finding (:file sc) (:line sc) "scenario-unnamed"
                                         (str (:id sc) " \"" (:title sc) "\" is named by no result: every scenario not"
                                              " tagged @wip is tested before it counts as built")))
                      (for [sc scs :when (not (:wip? sc))
                            r (get rs-of (:id sc))
                            :when (not= "passed" (:status r))]
                        (command/finding (:file r) "result-not-passed"
                                         (str (:id sc) " \"" (:title sc) "\": a result labelled with its id is " (:status r))))
                      (for [sc scs :when (not (:wip? sc))
                            r (get rs-of (:id sc))
                            :let [ps (get by-id (:id sc))
                                  params (vec (sort (map (juxt :name :value) (get-in r [:result :parameters]))))
                                  p (or (first (filter #(= params (vec (sort (:parameters %)))) ps)) (first ps))]
                            :when p
                            f (mapping-findings mapping p (:result r)
                                                {:file (:file r) :text (slurp (str (command/path req (:file r))))})]
                        f)))
            named (count (filter #(and (not (:wip? %)) (seq (get rs-of (:id %)))) scs))]
        (if (seq (remove command/warning? fs*))
          (assoc (fail (str "the tests check found " (count (remove command/warning? fs*)) " problems in the results") fs*)
                 :scenarios named)
          {:outcome :passed :seconds (seconds-since t0) :scenarios named :findings fs*})))))

(defn- serves-check [req c {:keys [checkout cycle-rel]}]
  (let [t0 (System/nanoTime)
        nm (or (:name c) "serves")
        port (:port c)
        {:keys [seconds polls]} (:budget c)
        interval-ms (/ (* 1000.0 seconds) polls)
        log (str (command/path req (str cycle-rel "/" nm ".log")))
        err-log (str (command/path req (str cycle-rel "/" nm ".err.log")))
        proc (start! (assoc c :name nm) (fill (:start c) "{port}" port)
                     {:dir (str checkout) :out (fs/file log) :err (fs/file err-log)})]
    (try
      (let [start (System/currentTimeMillis)
            answered (loop [i 1]
                       (when (<= i polls)
                         (let [due (+ start (* i interval-ms))
                               wait (- due (System/currentTimeMillis))]
                           (when (pos? wait) (Thread/sleep (long wait)))
                           (if (answers? port (long (* 0.8 interval-ms)))
                             i
                             (recur (inc i))))))
            base {:seconds nil :budget seconds :polls polls}]
        (if-not answered
          (let [why (str "the check " nm ": no answer came within " seconds " seconds on port " port
                         " (" polls " polls)")]
            (merge base {:outcome :failed :seconds (seconds-since t0) :reason why
                         :findings [(command/finding "serves-no-answer" why)]}))
          (let [url (str "http://127.0.0.1:" port "/")
                pr @(start! (assoc c :name nm) (fill (:probe c) "{url}" url)
                            {:dir (str checkout) :out :string :err :string})
                margin (- polls answered)]
            (if (zero? (:exit pr))
              (merge base {:outcome :passed :seconds (seconds-since t0) :answered-on-poll answered :margin margin})
              (let [why (str "the check " nm ": the app answered, but its probe exited " (:exit pr)
                             ", so it is not this application: " (str/trim (str (:out pr) " " (:err pr))))]
                (merge base {:outcome :failed :seconds (seconds-since t0) :answered-on-poll answered :margin margin
                             :reason why :findings [(command/finding "serves-probe-failed" why)]}))))))
      (finally (stop! proc)))))

(defn- runs-check [_req c {:keys [checkout]}]
  (let [t0 (System/nanoTime)
        budget (get-in c [:budget :seconds] 30)
        proc (start! c (:command c) {:dir (str checkout) :out :string :err :string})
        r (deref proc (long (* 1000 budget)) ::timeout)
        a (if (= ::timeout r) (do (stop! proc) {:timed-out? true}) r)
        secs (seconds-since t0)
        margin (/ (Math/round (* 100 (- budget secs))) 100.0)
        why (runs-problem (:expect c) a budget)]
    (if why
      {:outcome :failed :seconds secs :budget budget :margin margin :reason (str "the check " (:name c) ": " why)
       :findings [(command/finding "runs-failed" (str "the check " (:name c) ": " why))]}
      {:outcome :passed :seconds secs :budget budget :margin margin})))

;; ---------------------------------------------------------------------------
;; The stage

(defn- strip [s] (str/replace (str s) #"/+$" ""))

(defn- tree-of [req room paths]
  (let [g (str (fs/create-temp-dir {:prefix "rdm-spec-tree-"}))]
    (try
      (let [run (fn [& args]
                  (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                                 "git" (str "--git-dir=" g) (str "--work-tree=" room) args)]
                    (when-not (zero? (:exit r)) (command/cannot-run! (str "the room's tree cannot be read: " (str/trim (:err r)))))
                    (str/trim (:out r))))]
        (run "init" "-q")
        (let [present (filter #(fs/exists? (fs/file room %)) paths)]
          (when (seq present) (apply run "add" "-A" "-f" "--" present))
          (run "write-tree")))
      (finally (fs/delete-tree g)))))

(defn- copy-path! [from to]
  (when (fs/exists? from)
    (if (fs/directory? from)
      (fs/copy-tree from to {:replace-existing true})
      (do (fs/create-dirs (fs/parent to)) (fs/copy from to {:replace-existing true})))))

(defn verify
  "The verify stage: `rdm-spec verify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        cycle-rel (str (strip (command/config-value req :cycles-dir)) "/" cycle)
        cycle-file (str cycle-rel "/cycle.edn")
        cf (command/path req cycle-file)
        _ (when-not (fs/regular-file? cf)
            (command/cannot-run! (str "there is no " cycle-file ": a cycle is declared in its cycle.edn before it is verified")))
        c (try (clojure.edn/read-string (slurp (str cf)))
               (catch Exception e (command/cannot-run! (str cycle-file " is not readable EDN: " (ex-message e)))))
        tag (or (when (map? c) (:cut c)) (command/cannot-run! (str cycle-file " names no :cut")))
        _ (cut/repository! req)
        commit (or (cut/commit-of req tag)
                   (command/cannot-run! (str "the cut " tag " that " cycle-file " names is not in this repository")))
        cfg (or (cut/config-at req commit) (command/cannot-run! (str "the cut " tag " holds no " command/config-file)))
        checks (:verify cfg)
        impl (vec (:implementation cfg))
        _ (when-let [why (config-problem checks impl)] (command/cannot-run! why))
        mapping (if (contains? cfg :allure) (:allure cfg) runner/default-mapping)
        _ (when-let [why (mapping-problem mapping)] (command/cannot-run! (str ":allure: " why)))
        spec (parser/read-spec (:root req) cfg {:revision commit})
        stops (vec (concat
                    (for [{:keys [file line rule message]} (:refusals spec)] (command/finding file line rule message))
                    (for [sc (:scenarios spec) :when (not= 1 (count (:ids sc)))]
                      (command/finding (:file sc) (:line sc) "verify-unstamped"
                                       (str "the scenario \"" (:title sc) "\" carries " (count (:ids sc))
                                            " ids at the cut " tag ": a cut holding an unstamped scenario is not verified")))))
        room (command/path req room-arg)
        _ (when-not (fs/directory? room) (command/cannot-run! (str "the room " room-arg " is not a directory")))
        write-ratified! (fn [m] (spit (str (command/path req (str cycle-rel "/ratified.edn")))
                                      (str (pr-str m) "\n")))
        tree (tree-of req (str room) impl)
        checkout-rel (str cycle-rel "/build")
        record {:cut tag :room-tree tree :checkout checkout-rel}]
    (fs/create-dirs (command/path req cycle-rel))
    (if (seq stops)
      (do (write-ratified! (assoc record :outcome :refused :checks []
                                  :reason (str "the spec at " tag " holds " (count stops) " scenarios that are not stamped or not read")))
          {:result {:verify {:cycle cycle :cut tag :outcome :refused :checks []}} :findings stops})
      (do
        (doseq [s checks :when (and (= :serves (:kind s)) (port-held? (:port s)))]
          (command/cannot-run! (str "the port " (:port s) " of the check " (or (:name s) "serves")
                                    " is held by another process: verify would measure it, not the app")))
        (let [checkout (command/path req checkout-rel)]
          (fs/delete-tree checkout)
          (cut/export! req commit checkout)
          (doseq [p impl] (fs/delete-tree (fs/file checkout p)))
          (doseq [p impl] (copy-path! (fs/file room p) (fs/file checkout p)))
          (let [opts {:checkout checkout :cycle-rel cycle-rel :results-rel (str cycle-rel "/results")
                      :log-rel (str cycle-rel "/test.log") :cut-req req :commit commit :cfg cfg :mapping mapping}
                outs (loop [[s & more] checks out [] failed? false]
                       (if (nil? s)
                         out
                         (let [nm (or (:name s) (name (:kind s)))
                               head {:name nm :kind (:kind s)}]
                           (if failed?
                             (recur more (conj out (assoc head :outcome :not-run)) true)
                             (let [r (case (:kind s)
                                       :tests (tests-check req s opts)
                                       :serves (serves-check req s opts)
                                       :runs (runs-check req s opts))]
                               (recur more (conj out (merge head r)) (not= :passed (:outcome r))))))))
                shown (fn [o] (let [base (dissoc o :findings :reason)]
                                (case (:kind o)
                                  :tests (select-keys base [:name :kind :outcome :seconds :scenarios])
                                  :serves (select-keys base [:name :kind :outcome :seconds :budget :polls :answered-on-poll :margin])
                                  :runs (select-keys base [:name :kind :outcome :seconds :budget :margin])
                                  base)))
                checks-out (mapv shown outs)
                failed (first (filter #(= :failed (:outcome %)) outs))
                outcome (if failed :refused :passed)]
            (write-ratified! (cond-> (assoc record :outcome outcome :checks checks-out)
                               failed (assoc :reason (:reason failed))))
            {:result {:verify {:cycle cycle :cut tag :outcome outcome :checks checks-out}}
             :findings (vec (mapcat :findings outs))}))))))
