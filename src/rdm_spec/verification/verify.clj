(ns rdm-spec.verification.verify
  "Verify (dhf/spec/verification/verify.feature): the gate. Reads the cycle
  first, then the cut it names; puts the room's implementation, in place of
  the implementation, into a fresh export of the cut (dhf/cycles/NN/build);
  and runs the checks the cut's configuration declares (:verify), in order,
  the first that fails stopping it and the rest named as not run. Records
  what it verified in dhf/cycles/NN/ratified.edn, unless it cannot run.

  Checks: :tests (the test command passes, its Allure results name and pass
  every stamped scenario not planned, each held to the :allure mapping),
  :serves (the app answers within its budget of polls and passes its probe),
  :runs (a command answers as expected within its budget) and :plugin (the
  project's plugin answering :Check)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; Numbers

(defn round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn margin
  "The seconds of a budget left unused, to 0.01, never below 0."
  [budget seconds]
  (round2 (max 0 (- budget seconds))))

(defn- now [] (System/currentTimeMillis))
(defn- took [t0] (round2 (/ (- (now) t0) 1000.0)))

;; ---------------------------------------------------------------------------
;; The declaration

(def kinds [:tests :serves :runs :plugin])

(def default-probe-seconds 30)
(def default-runs-seconds 60)

(defn check-name [c] (or (:name c) (some-> (:kind c) name)))

(defn- under? [path entry]
  (let [e (str/replace (str entry) #"/+$" "")
        p (str/replace (str path) #"^(\./)+" "")]
    (and (seq e) (or (= p e) (str/starts-with? p (str e "/"))))))

(defn- impl-path? [config w] (some #(under? w %) (:implementation config)))

(defn- words? [v] (and (vector? v) (seq v) (every? string? v)))

(defn- check-problem [config c]
  (let [n (check-name c)
        k (:kind c)]
    (case k
      :tests (when-not (words? (:command c)) "the tests check names no command")
      :runs (cond (not (words? (:command c))) (str "the :runs check " n " names no command")
                  (not (map? (:expect c))) (str "the :runs check " n " declares no :expect"))
      :serves (cond (not (words? (:start c))) (str "the :serves check " n " names no :start command")
                    (not (words? (:probe c))) (str "the :serves check " n " names no :probe")
                    (some #(impl-path? config %) (:probe c))
                    (str "the :serves check " n " probes with " (pr-str (:probe c))
                         ": the probe is in the implementation the builder writes")
                    (not (integer? (:port c))) (str "the :serves check " n " names no :port")
                    (not (and (pos? (get-in c [:budget :seconds] 0)) (pos-int? (get-in c [:budget :polls] 0))))
                    (str "the :serves check " n " declares no :budget {:seconds S :polls K}"))
      :plugin (when-not (some #(and (= (:name c) (:name %)) (= :Check (:answers %))) (:plugins config))
                (str "the :plugin check " (pr-str (:name c)) ": the check names no plugin answering :Check"
                     " (the configuration's :plugins declares no " (pr-str (:name c)) " answering :Check)"))
      nil)))

(defn verify-problem
  "Why the configuration's :verify cannot be trusted, or nil."
  [config]
  (let [v (:verify config)]
    (cond
      (nil? v) "the configuration declares no :verify"
      (not (and (sequential? v) (every? map? v))) "the configuration's :verify is not a vector of checks"
      :else
      (or (first (for [c v :when (not (some #{(:kind c)} kinds))]
                   (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves, :runs and :plugin")))
          (when-not (= :tests (:kind (first v)))
            "a project is verified by its tests first: :verify declares its :tests check, first")
          (first (keep #(check-problem config %) v))
          (first (for [[n c] (frequencies (map check-name v)) :when (> c 1)]
                   (str "the check " n " is declared twice: a check's name is declared once")))))))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(def result-elements #{:epic :feature :story :name :step :parameter :labels :links :evidence})
(def spec-elements #{:context :component :feature :rule :scenario :scenario-id :gherkin-step :gherkin-tags :example-row :level})

(defn allure-problem
  "Why an :allure mapping cannot be read, or nil."
  [m]
  (cond
    (nil? m) nil
    (not (map? m)) "the configuration's :allure is not a map"
    :else
    (or (first (for [k (keys m) :when (not (result-elements k))]
                 (str (pr-str k) " is not an element of an Allure result: the elements are "
                      (str/join ", " (map str (sort result-elements))))))
        (first (for [k [:epic :feature :story :name :step :parameter]
                     :let [v (get m k)]
                     :when (and (contains? m k) (not (spec-elements v)))]
                 (str (pr-str v) " is not an element of the spec: the elements are "
                      (str/join ", " (map str (sort spec-elements))))))
        (when-not (map? (:labels m)) "the label spec, from the scenario's id, is required: :allure maps no :labels")
        (first (for [[l v] (:labels m) :when (not (spec-elements v))]
                 (str (pr-str v) " is not an element of the spec: the elements are "
                      (str/join ", " (map str (sort spec-elements))))))
        (when-not (= :scenario-id (get (:labels m) "spec"))
          "the label spec, from the scenario's id, is required: :allure maps no label \"spec\" to :scenario-id")
        (when-not (or (nil? (:links m)) (map? (:links m))) "the configuration's :allure :links is not a map")
        (when-not (or (nil? (:evidence m)) (map? (:evidence m))) "the configuration's :allure :evidence is not a map"))))

(defn- spec-value [sc el]
  (case el
    :context (some-> (:context sc) name)
    :component (:component-name sc)
    :feature (:feature sc)
    :rule (:rule sc)
    :scenario (:title sc)
    :scenario-id (:id sc)
    :level (some-> (:level sc) name)
    :gherkin-tags (set (concat (:feature-tags sc) (:rule-tags sc) (:tags sc)))
    nil))

(defn- params-map [j]
  (into {} (for [p (get j "parameters") :when (map? p)] [(str (get p "name")) (str (get p "value"))])))

(defn- rows [sc]
  (vec (for [ex (:examples sc) r (:rows ex)]
         (zipmap (map str (:headings ex)) (map str (or (:cells r) r))))))

(defn- fill-row [text row]
  (reduce (fn [t [h v]] (str/replace t (str "<" h ">") v)) text row))

(defn- labels-of [j n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get j "labels"))))

(defn- attachment-names [x]
  (cond (map? x) (concat (keep #(get % "name") (get x "attachments")) (mapcat attachment-names (get x "steps")))
        :else []))

(defn- line-in [text k]
  (or (first (keep-indexed (fn [i l] (when (str/includes? l (str "\"" k "\"")) (inc i))) (str/split-lines (str text)))) 1))

(defn mapping-findings
  "The findings of one result (its JSON j, in file, whose text is text) held
  to the mapping m and its scenario sc."
  [m sc j {:keys [file text]}]
  (let [id (:id sc)
        row (params-map j)
        f (fn [k rule msg] (cmd/finding file (line-in text k) rule msg))
        differs (fn [k what msg] (f k "mapping-differs" (str what ": " id "'s result " msg)))]
    (vec
     (concat
      (when (= :gherkin-step (:step m))
        (let [want (mapv #(str (:keyword %) " " (fill-row (:text %) row)) (concat (:background sc) (:steps sc)))
              got (mapv #(str (get % "name")) (get j "steps"))]
          (when-not (= want got)
            [(f "steps" "steps-differ-from-scenario"
                (str id ": the result's steps " (pr-str got) " differ from the scenario's lines " (pr-str want)))])))
      (for [[el label] [[:epic "epic"] [:feature "feature"] [:story "story"]]
            :when (contains? m el)
            :let [want (spec-value sc (get m el)) got (labels-of j label)]
            :when (not (if want (= [want] got) (empty? got)))]
        (differs label (name el) (str "has the " label " " (pr-str got) " where the scenario's " (name (get m el))
                                      " is " (pr-str want))))
      (when (contains? m :name)
        (let [want (spec-value sc (:name m))]
          (when (and want (not= want (get j "name")))
            [(differs "name" "name" (str "is named " (pr-str (get j "name")) " where the scenario's "
                                         (name (:name m)) " is " (pr-str want)))])))
      (for [[l el] (:labels m)
            :let [want (spec-value sc el) got (labels-of j l)]
            :when (not (if (set? want) (= want (set got)) (if want (= [want] got) true)))]
        (differs "labels" "labels" (str "has the label " l " " (pr-str got) " where the scenario's " (name el)
                                        " is " (pr-str (if (set? want) (sort want) want)))))
      (when (and (= :example-row (:parameter m)) (:outline? sc) (not (some #{row} (rows sc))))
        [(differs "parameters" "parameter" (str "has the parameters " (pr-str row) ", no row of the outline's examples"))])
      (when-let [{:keys [type url]} (get-in m [:links :need])]
        (for [n (:needs sc)
              :let [u (str/replace (str url) "{id}" n)]
              :when (not (some #(and (= type (get % "type")) (= u (get % "url"))) (get j "links")))]
          (differs "links" "links" (str "has no " type " link to " n " (" u "), the need the scenario traces to"))))
      (for [nm (get-in m [:evidence (:level sc)])
            :when (not (some #{nm} (attachment-names j)))]
        (f "attachments" "evidence-missing"
           (str nm ": " id "'s result carries no " nm " attachment, which :evidence requires for @" (name (:level sc)))))))))

;; ---------------------------------------------------------------------------
;; Running commands

(defn fill
  "The words with each {key} replaced by its value."
  [words m]
  (mapv (fn [w] (reduce (fn [w [k v]] (str/replace w (str "{" k "}") (str v))) w m)) words))

(defn- run
  "Runs words in dir, stopped with everything it started past seconds (nil:
  no limit): {:exit :out :err :seconds} with :timed-out or :not-found."
  [words {:keys [dir env seconds]}]
  (let [t0 (now)]
    (try
      (let [pr (p/process words (cond-> {:dir (str dir) :out :string :err :string :in ""}
                                  env (assoc :extra-env env)))
            r (if seconds (deref pr (long (* 1000 seconds)) ::timeout) @pr)]
        (if (= ::timeout r)
          (do (p/destroy-tree pr)
              (let [d (deref pr 5000 nil)]
                {:timed-out true :out (str (:out d)) :err (str (:err d)) :seconds (took t0)}))
          {:exit (:exit r) :out (str (:out r)) :err (str (:err r)) :seconds (took t0)}))
      (catch java.io.IOException e
        {:not-found true :out "" :err (ex-message e) :seconds (took t0)}))))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn- not-found! [c words]
  (cmd/cannot-run! (str "the check " (check-name c) " cannot run: its command " (first words)
                        " was not found (" (pr-str words) ")")))

(defn runs-difference
  "How a :runs check's command answered otherwise than expected, or nil."
  [expect {:keys [exit out]}]
  (let [first-line (first (str/split-lines (str out)))]
    (cond
      (and (contains? expect :exit) (not= (:exit expect) exit))
      (str "it answered with the exit " exit " where " (:exit expect) " was expected")
      (and (:stdout-starts expect) (not (str/starts-with? (str out) (:stdout-starts expect))))
      (str "its standard output does not start " (pr-str (:stdout-starts expect)) ": the first line it printed was "
           (pr-str (str first-line))))))

;; ---------------------------------------------------------------------------
;; Serving

(defn port-held?
  "Another process holds the port: it cannot be bound. Connections closing
  (TIME_WAIT) do not hold it."
  [port]
  (try (with-open [ss (java.net.ServerSocket.)]
         (.setReuseAddress ss true)
         (.bind ss (java.net.InetSocketAddress. "127.0.0.1" (int port)))
         false)
       (catch java.io.IOException _ true)))

(defn answers?
  "GET / on the port returns an HTTP response, whatever its status, within ms."
  [port ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int ms))
      (.setSoTimeout s (int ms))
      (doto (.getOutputStream s)
        (.write (.getBytes "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"))
        (.flush))
      (let [buf (byte-array 5)
            in (.getInputStream s)
            n (loop [n 0]
                (if (< n 5)
                  (let [r (.read in buf n (- 5 n))] (if (neg? r) n (recur (+ n r))))
                  n))]
        (and (= 5 n) (= "HTTP/" (String. buf "US-ASCII")))))
    (catch Exception _ false)))

(defn- stop! [pr]
  (when pr
    (p/destroy-tree pr)
    (when (= ::alive (deref pr 5000 ::alive))
      (doseq [h (cons (.toHandle ^Process (:proc pr)) (iterator-seq (.iterator (.descendants (.toHandle ^Process (:proc pr))))))]
        (.destroyForcibly h))
      (deref pr 5000 nil))))

(defn- run-serves [c {:keys [build cycle-abs]}]
  (let [n (check-name c)
        port (:port c)
        {:keys [seconds polls]} (:budget c)
        probe-seconds (or (:probe-seconds c) default-probe-seconds)
        url (str "http://127.0.0.1:" port "/")
        start (fill (:start c) {"port" port})
        t0 (now)
        report (fn [m] (merge {:name n :kind :serves :seconds (took t0) :budget seconds :polls polls
                               :probe-seconds probe-seconds} m))]
    (when (port-held? port)
      (cmd/cannot-run! (str "the :serves check " n " cannot start its app: the port " port
                            " is held by another process, which it would measure instead")))
    (let [app (try (p/process start {:dir (str build) :in ""
                                     :out :write :out-file (fs/file cycle-abs (str n ".out.log"))
                                     :err :write :err-file (fs/file cycle-abs (str n ".err.log"))})
                   (catch java.io.IOException _ (not-found! c start)))]
      (try
        (let [interval (/ (* 1000.0 seconds) polls)
              answered (loop [i 1]
                         (when (<= i polls)
                           (let [wait (- (+ t0 (* i interval)) (now))]
                             (when (pos? wait) (Thread/sleep (long wait)))
                             (if (answers? port (long (max 100 (min 1000 interval))))
                               i
                               (recur (inc i))))))]
          (if-not answered
            (let [why (str "no answer came within " seconds " seconds (" polls " polls of GET " url ")")]
              {:report (report {:outcome :failed :reason why})
               :findings [(cmd/finding nil nil "serves-no-answer" (str "the :serves check " n ": " why))]})
            (let [probe (fill (:probe c) {"url" url "port" port})
                  r (run probe {:dir build :seconds probe-seconds})
                  printed (one-line (str (:out r) " " (:err r)))
                  base {:answered-on-poll answered :margin (- polls answered)}]
              (cond
                (:not-found r) (not-found! c probe)
                (:timed-out r)
                (let [why (str "the probe timed out after " probe-seconds " seconds and was stopped"
                               (when (seq printed) (str "; it printed: " printed)))]
                  {:report (report (assoc base :outcome :failed :reason why))
                   :findings [(cmd/finding nil nil "probe-timed-out" (str "the :serves check " n ": " why))]})
                (not= 0 (:exit r))
                (let [why (str "the app answered and its probe exited " (:exit r) ": it is not this application"
                               (when (seq printed) (str "; the probe printed: " printed)))]
                  {:report (report (assoc base :outcome :failed :reason why))
                   :findings [(cmd/finding nil nil "probe-failed" (str "the :serves check " n ": " why))]})
                :else {:report (report (assoc base :outcome :passed))}))))
        (finally (stop! app))))))

;; ---------------------------------------------------------------------------
;; The checks

(defn unstamped-findings
  "A finding for each scenario of the cut that carries no id."
  [scenarios]
  (vec (for [s scenarios :when (and (nil? (:id s)) (empty? (:ids s)))]
         (cmd/finding (:file s) (:line s) "scenario-unstamped"
                      (str "the scenario " (pr-str (:title s)) " has no id: a cut holding an unstamped scenario is not verified")))))

(defn- run-tests [c {:keys [root build cycle-rel cycle-abs config scenarios]}]
  (let [res-rel (str cycle-rel "/results")
        res-abs (fs/path cycle-abs "results")
        log-rel (str cycle-rel "/test.log")
        _ (do (fs/delete-tree res-abs) (fs/create-dirs res-abs))
        r (run (:command c) {:dir build :env {"RESULTS_DIR" (str res-abs)} :seconds (get-in c [:budget :seconds])})
        report (fn [m] (merge {:name (check-name c) :kind :tests :seconds (:seconds r)} m))
        failed (fn [rule why findings]
                 {:report (report {:outcome :failed :reason why})
                  :findings (into [(cmd/finding log-rel nil rule why)] findings)})]
    (spit (fs/file cycle-abs "test.log") (str (:out r) (:err r)))
    (cond
      (:not-found r) (not-found! c (:command c))
      (:timed-out r) (failed "tests-failed" (str "the test command failed: it was still running at the end of its budget of "
                                                 (get-in c [:budget :seconds]) " seconds; its output is in " log-rel) [])
      (not= 0 (:exit r)) (failed "tests-failed" (str "the test command failed with exit " (:exit r)
                                                     ": its output is in " log-rel) [])
      (empty? (fs/glob res-abs "*-result.json"))
      (failed "tests-no-results" (str "the test command exited 0 and wrote no Allure result into " res-rel
                                      ": a green exit with no results is not a run") [])
      :else
      (let [{rs :results unreadable :unreadable} (results/read-dir root res-rel)
            mapping (or (:allure config) default-mapping)
            due (filter #(and (:id %) (not (:wip? %))) scenarios)
            by-id (group-by :spec rs)
            text-of (memoize #(slurp (fs/file root %)))
            findings (vec
                      (concat
                       (results/unreadable-findings unreadable)
                       (for [s due :when (empty? (by-id (:id s)))]
                         (cmd/finding (:file s) (:line s) "scenario-untested"
                                      (str (:id s) " is named by no result in " res-rel)))
                       (for [s due r' (by-id (:id s)) :when (not= "passed" (:status r'))]
                         (cmd/finding (:file r') nil "scenario-not-passed"
                                      (str (:id s) "'s result is " (:status r') ": every result naming a scenario passes")))
                       (for [s due r' (by-id (:id s))
                             f (mapping-findings mapping s (:json r') {:file (:file r') :text (text-of (:file r'))})]
                         f)))
            named (count (filter #(seq (by-id (:id %))) due))]
        (if (seq findings)
          {:report (report {:outcome :failed :scenarios named
                            :reason (str (count findings) " finding" (when (> (count findings) 1) "s")
                                         " in the test results: " (cmd/finding-line (first findings)))})
           :findings findings}
          {:report (report {:outcome :passed :scenarios named})})))))

(defn- run-runs [c {:keys [build]}]
  (let [budget (or (get-in c [:budget :seconds]) default-runs-seconds)
        r (run (:command c) {:dir build :seconds budget})
        n (check-name c)
        report (fn [m] (merge {:name n :kind :runs :seconds (:seconds r) :budget budget} m))
        failed (fn [why] {:report (report {:outcome :failed :reason why})
                          :findings [(cmd/finding nil nil "runs-differs" (str "the :runs check " n ": " why))]})]
    (cond
      (:not-found r) (not-found! c (:command c))
      (:timed-out r) (failed (str "it was still running at the end of the budget of " budget " seconds, and was stopped"))
      :else (if-let [d (runs-difference (:expect c) r)]
              (failed d)
              {:report (report {:outcome :passed :margin (margin budget (:seconds r))})}))))

(defn- run-plugin-check [c {:keys [build cycle cut cycle-rel config]}]
  (let [n (check-name c)
        pl (first (filter #(and (= (:name c) (:name %)) (= :Check (:answers %))) (:plugins config)))
        run! (requiring-resolve 'rdm-spec.kernel.plugins/run!)
        relay (requiring-resolve 'rdm-spec.kernel.plugins/relay)
        r (run! pl {:request :Check :cycle cycle :cut cut :checkout (str cycle-rel "/build")} {:dir build})
        a (try (relay pl r)
               (catch clojure.lang.ExceptionInfo e
                 (if-let [why (:cannot-run (ex-data e))]
                   (cmd/cannot-run! (str "the check " n " is :refused: " why))
                   (throw e))))
        report {:name n :kind :plugin :seconds (round2 (:seconds r))}]
    (if (seq (:findings a))
      {:report (assoc report :outcome :failed :reason (str "the plugin " (:name pl) " found " (count (:findings a))))
       :findings (:findings a) :warnings (:warnings a)}
      {:report (assoc report :outcome :passed) :warnings (:warnings a)})))

(defn- run-check [c info]
  (case (:kind c)
    :tests (run-tests c info)
    :serves (run-serves c info)
    :runs (run-runs c info)
    :plugin (run-plugin-check c info)))

;; ---------------------------------------------------------------------------
;; The room and the checkout

(defn room-tree
  "The room's implementation as git would commit it: {:tree hash :files [path]}."
  [room impl]
  (let [t (cmd/temp-dir "rdm-spec-tree-")]
    (try
      (let [gd (str (fs/path t "git"))
            env {"GIT_DIR" gd "GIT_WORK_TREE" (str room) "GIT_INDEX_FILE" (str (fs/path t "index"))}
            g (fn [& args]
                (let [r (apply cmd/sh {:dir room :env env} "git" args)]
                  (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "git " (first args) " failed in the room: " (one-line (:err r)))))
                  (:out r)))
            existing (filter #(fs/exists? (fs/path room %)) impl)]
        (let [r (cmd/sh {} "git" "init" "-q" "--bare" gd)]
          (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "git init failed: " (one-line (:err r))))))
        (when (seq existing) (apply g "add" "-A" "-f" "--" existing))
        {:tree (str/trim (g "write-tree"))
         :files (vec (remove str/blank? (str/split-lines (g "ls-files"))))})
      (finally (fs/delete-tree t)))))

(defn- export! [root cut build]
  (fs/delete-tree build)
  (fs/create-dirs build)
  (let [tar (cmd/temp-file ".tar")]
    (try
      (let [a (cmd/sh {:dir root} "git" "archive" "--format=tar" "-o" (str tar) cut)]
        (when-not (= 0 (:exit a)) (cmd/cannot-run! (str "git archive of " cut " failed: " (one-line (:err a))))))
      (let [x (cmd/sh {:dir (str build)} "tar" "-x" "-f" (str tar))]
        (when-not (= 0 (:exit x)) (cmd/cannot-run! (str "tar failed: " (one-line (:err x))))))
      (finally (fs/delete-if-exists tar)))))

(defn- implant! [room build impl files]
  (doseq [p impl] (fs/delete-tree (fs/path build p)))
  (doseq [f files]
    (let [to (fs/path build f)]
      (fs/create-dirs (fs/parent to))
      (fs/copy (fs/path room f) to {:replace-existing true :copy-attributes true}))))

;; ---------------------------------------------------------------------------
;; The stages

(defn- commands-of [c]
  (case (:kind c)
    (:tests :runs) [(:command c)]
    :serves [(:start c) (:probe c)]
    []))

(defn prepare-stage
  "Reads the cycle, then the cut it names and the cut's configuration, and
  refuses, before anything runs, what verify cannot trust."
  [{:keys [root config args]}]
  (let [[room-arg cycle] args
        cycles (:cycles-dir config "dhf/cycles")
        cycle-rel (str cycles "/" cycle)
        decl-rel (str cycle-rel "/cycle.edn")
        text (or (cmd/read-at root nil decl-rel)
                 (cmd/cannot-run! (str "there is no " decl-rel ": verify reads the cycle before anything else")))
        decl (try (cmd/read-edn text) (catch Exception e (cmd/cannot-run! (str decl-rel " cannot be read: " (one-line (ex-message e))))))
        cut (:cut decl)
        _ (when-not (and (map? decl) (string? cut)) (cmd/cannot-run! (str decl-rel " names no :cut")))
        _ (when-not (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" (str "refs/tags/" cut))))
            (cmd/cannot-run! (str decl-rel " names the cut " cut ", which is not a cut: no tag " cut " exists")))
        room (fs/absolutize (fs/path root room-arg))
        _ (when-not (fs/directory? room) (cmd/cannot-run! (str "the room " room-arg " does not exist")))
        cut-config (let [t (cmd/read-at root cut "dhf/harness/config.edn")
                         c (try (cmd/read-edn t) (catch Exception _ nil))]
                     (if (map? c) c (cmd/cannot-run! (str "the cut " cut " holds no readable dhf/harness/config.edn"))))]
    (when-let [why (verify-problem cut-config)] (cmd/cannot-run! why))
    (when-let [why (allure-problem (:allure cut-config))] (cmd/cannot-run! why))
    (when (:plugins cut-config)
      (when-let [why ((requiring-resolve 'rdm-spec.kernel.plugins/problem) cut-config)] (cmd/cannot-run! why)))
    (doseq [c (:verify cut-config) words (commands-of c)
            :let [w (first words)]
            :when (and (not (str/includes? w "/")) (nil? (fs/which w)))]
      (not-found! c words))
    {:ctx {:cut cut :cycle cycle :revision cut :config cut-config
           ::info {:room room :cycle-rel cycle-rel :cycle-abs (fs/absolutize (fs/path root cycle-rel))}}}))

(defn- write-record! [cycle-abs record]
  (fs/create-dirs cycle-abs)
  (spit (fs/file cycle-abs "ratified.edn")
        (binding [*print-namespace-maps* false] (with-out-str (pp/pprint record)))))

(defn stage
  "Builds the checkout and runs every declared check, in order."
  [{:keys [root config cut cycle] :as ctx}]
  (let [{:keys [room cycle-rel cycle-abs]} (::info ctx)
        {:keys [scenarios refusals]} (bus/latest :SpecParsed)
        impl (:implementation config ["src" "test"])
        {:keys [tree files]} (room-tree room impl)
        build-rel (str cycle-rel "/build")
        build (fs/path cycle-abs "build")
        unstamped (into (vec refusals) (unstamped-findings scenarios))]
    (fs/create-dirs cycle-abs)
    (if (seq unstamped)
      (let [why (str "the cut " cut " holds " (count unstamped) " scenario" (when (> (count unstamped) 1) "s")
                     " that cannot be verified: " (cmd/finding-line (first unstamped)))]
        (write-record! cycle-abs {:cut cut :room-tree tree :outcome :refused :reason why :checks []})
        {:findings unstamped
         :result {:verify {:cycle cycle :cut cut :outcome :refused :checks []}}})
      (do
        (export! root cut build)
        (implant! room build impl files)
        (let [info {:root root :build (str build) :cycle cycle :cut cut :cycle-rel cycle-rel :cycle-abs cycle-abs
                    :config config :scenarios scenarios}
              {:keys [reports findings warnings]}
              (loop [cs (:verify config) acc {:reports [] :findings [] :warnings []}]
                (if-let [c (first cs)]
                  (let [{:keys [report] :as r} (run-check c info)
                        acc (-> acc (update :reports conj report)
                                (update :findings into (:findings r))
                                (update :warnings into (:warnings r)))]
                    (if (= :passed (:outcome report))
                      (recur (rest cs) acc)
                      (update acc :reports into (for [c' (rest cs)]
                                                  {:name (check-name c') :kind (:kind c') :outcome :not-run}))))
                  acc))
              passed? (every? #(= :passed (:outcome %)) reports)
              outcome (if passed? :passed :refused)
              failed (first (remove #(#{:passed :not-run} (:outcome %)) reports))]
          (write-record! cycle-abs (cond-> {:cut cut :room-tree tree :outcome outcome :checkout build-rel :checks reports}
                                     (not passed?) (assoc :reason (str "the check " (:name failed) " failed: " (:reason failed)))))
          {:findings (if (and (not passed?) (empty? findings))
                       [(cmd/finding nil nil "check-failed" (str "the check " (:name failed) " failed: " (:reason failed)))]
                       findings)
           :warnings warnings
           :result {:verify {:cycle cycle :cut cut :outcome outcome :checks reports}}})))))
