(ns rdm-spec.verification.verify
  "Verify (dhf/spec/verification/verify.feature): `rdm-spec verify ROOM
  CYCLE`, a cycle's gate. The room's implementation, in place of the
  implementation of a fresh export of the cut, passes every check the cut's
  configuration declares (:verify), in order, the first that fails stopping
  it; the rest are listed :not-run.

  In order: the cycle's cycle.edn (in the project's :cycles-dir; missing:
  cannot run), its :cut (a tag), the cut's configuration, its :verify and
  :allure (either unusable: cannot run, and nothing runs), the room; then
  CYCLE/build is the cut's files exported (no history) with every
  :implementation path removed and the room's copied in, and the checks run
  in it:

    :tests   the command, with RESULTS_DIR the absolute CYCLE/results, its
             output in CYCLE/test.log; it passes when it exits 0, wrote
             results, the cut's spec holds no unstamped scenario, every
             stamped scenario not tagged @wip is named by a result (each row
             of an outline, by its parameters) and every result naming it
             passed, and every such result follows the Allure mapping
             (rdm-spec.kernel.runner/mapping-differences)
    :serves  the app started on its port (held already: cannot run), polled
             every budget/polls seconds until it accepts a connection, then
             the probe run with {url}; the app's process tree is stopped
    :runs    the command, within its budget, with the exit and the start of
             standard output expected

  A command that cannot be started cannot run (exit 2). CYCLE/ratified.edn
  records {:cut :room-tree :outcome :checkout :checks} (:reason when
  refused), :room-tree the git tree of the room's implementation paths.

  Result {:verify {:cycle :cut :outcome :passed|:refused :checks [..]}}; a
  check {:name :kind :outcome :seconds ..}: :tests adds :scenarios (the
  stamped scenarios its results name), :runs :budget and :margin (seconds),
  :serves :budget :polls :answered-on-poll and :margin (polls); a failed one
  :reason."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.verification.results :as results]))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn- git [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" (map str args)))

;; ---------------------------------------------------------------------------
;; The declared checks

(def kinds #{:tests :serves :runs})

(defn check-name [c] (or (:name c) (some-> (:kind c) name)))

(defn- in-implementation? [word impl]
  (let [w (str/replace (str word) #"^\./" "")]
    (some #(or (= w %) (str/starts-with? w (str (str/replace % #"/+$" "") "/"))) impl)))

(defn checks-problem
  "Why the declared checks `checks` cannot be trusted, or nil. `impl` the
  implementation paths."
  [checks impl]
  (cond
    (nil? checks) "the configuration declares no :verify: a project says what verifying it proves"
    (not (and (sequential? checks) (every? map? checks))) "the configuration's :verify is not a list of checks"
    (some #(not (kinds (:kind %))) checks)
    (str (:kind (first (remove #(kinds (:kind %)) checks))) " is not a kind of check: the kinds are :tests, :serves and :runs")
    (not= :tests (:kind (first checks)))
    "a project is verified by its tests first: :verify declares the :tests check first"
    (not (and (sequential? (:command (first checks))) (seq (:command (first checks)))))
    "the tests check names no command"
    :else
    (some (fn [c]
            (case (:kind c)
              :tests nil
              :runs (cond (not (seq (:command c))) (str "the check " (check-name c) " names no command")
                          (not (number? (get-in c [:budget :seconds]))) (str "the check " (check-name c) " states no budget in seconds")
                          :else nil)
              :serves (cond (not (seq (:start c))) (str "the check " (check-name c) " names no start command")
                            (not (integer? (:port c))) (str "the check " (check-name c) " names no port")
                            (not (and (number? (get-in c [:budget :seconds])) (pos-int? (get-in c [:budget :polls]))))
                            (str "the check " (check-name c) " states no budget of seconds and polls")
                            (not (seq (:probe c))) (str "the check " (check-name c) " names no probe")
                            (some #(in-implementation? % impl) (:probe c))
                            (str "the check " (check-name c) " cannot be trusted: the probe is in the implementation the builder writes ("
                                 (first (filter #(in-implementation? % impl) (:probe c))) "); a probe lives in a kept path")
                            :else nil)
              nil))
          checks)))

;; ---------------------------------------------------------------------------
;; Running a command

(defn- run-command
  "Runs `cmd` in `dir`: {:exit :out :err :seconds :timed-out?}; cannot run
  when it cannot be started."
  [check cmd dir env timeout-ms]
  (let [t0 (System/currentTimeMillis)
        proc (try (p/process (mapv str cmd) {:dir (str dir) :out :string :err :string :in "" :extra-env env})
                  (catch Exception e
                    (command/cannot-run! (one-line (str "the check " (check-name check) " cannot start its command "
                                                        (first cmd) ": " (ex-message e))))))
        r (if timeout-ms (deref proc timeout-ms ::timeout) @proc)]
    (if (= ::timeout r)
      (do (p/destroy-tree proc)
          (try (deref proc 5000 nil) (catch Exception _ nil))
          {:timed-out? true :seconds (round2 (/ (- (System/currentTimeMillis) t0) 1000.0))})
      {:exit (:exit r) :out (:out r) :err (:err r)
       :seconds (round2 (/ (- (System/currentTimeMillis) t0) 1000.0))})))

(defn runs-difference
  "How a :runs check's answer `r` ({:exit :out :timed-out?}) differs from
  what it expects, or nil."
  [check r]
  (let [{:keys [exit stdout-starts]} (:expect check)
        budget (get-in check [:budget :seconds])]
    (cond
      (:timed-out? r) (str "no answer within the budget of " budget " seconds")
      (and (some? exit) (not= exit (:exit r))) (str "the exit " (:exit r) " where " exit " was expected")
      (and stdout-starts (not (str/starts-with? (str (:out r)) stdout-starts)))
      (str "standard output does not start with " (pr-str stdout-starts) ": the first line it printed is "
           (pr-str (first (str/split-lines (str (:out r))))))
      :else nil)))

;; ---------------------------------------------------------------------------
;; The tests check's results

(defn scenario-findings
  "What the tests check finds in `results` ([{:file :spec :status
  :parameters :result}]) for the stamped scenarios of `pickles`, held to
  the mapping `m`."
  [pickles results m]
  (let [by-spec (group-by :spec (filter :spec results))]
    (vec
     (for [[id pks] (group-by :id (filter #(and (:id %) (not (:wip? %))) pickles))
           :let [rs (get by-spec id)
                 pk0 (first pks)
                 at (fn [rule msg] (assoc (command/finding (:file pk0) (:line pk0) rule (one-line msg)) :id id))]
           f (cond
               (empty? rs)
               [(at "scenario-untested" (str id " is named by no result: every stamped scenario not tagged @wip is tested before it counts as built"))]

               :else
               (let [pairs (if (:outline? pk0)
                             (for [pk pks] [pk (filter #(= (:values (:row pk)) (:parameters %)) rs)])
                             [[pk0 rs]])]
                 (concat
                  (for [[pk prs] pairs :when (empty? prs)]
                    (at "scenario-untested" (str id " has no result for its row " (pr-str (:values (:row pk)))
                                                 ": every row of an outline is tested")))
                  (for [[_ prs] pairs r prs :when (not= "passed" (:status r))]
                    (at "scenario-not-passed" (str id ": the result " (:file r) " is " (:status r))))
                  (for [[pk prs] pairs r prs :when (:result r)
                        d (runner/mapping-differences pk (:result r) m)]
                    (at (:rule d) (str (when (:element d) (str (:element d) ": ")) (:message d)
                                       " (" (:file r) ")"))))))]
       f))))

(defn- unstamped-findings [pickles]
  (vec (for [pk (distinct (map #(select-keys % [:file :line :ids :title]) pickles))
             :when (empty? (:ids pk))]
         (command/finding (:file pk) (:line pk) "scenario-unstamped"
                          (str "the scenario \"" (:title pk) "\" has no id: a cut holding an unstamped scenario is not verified")))))

(defn- tests-check [check {:keys [root build cycle-rel cut-config mapping]}]
  (let [results-dir (fs/file root cycle-rel "results")
        log-rel (str cycle-rel "/test.log")
        _ (fs/delete-tree results-dir)
        _ (fs/create-dirs results-dir)
        r (run-command check (:command check) build {"RESULTS_DIR" (str (fs/absolutize results-dir))}
                       (some-> (get-in check [:budget :seconds]) (* 1000) long))
        _ (spit (str (fs/file root log-rel)) (str (:out r) (:err r)))
        base {:name (check-name check) :kind :tests}
        fail (fn [findings]
               {:entry (assoc base :outcome :failed :seconds (:seconds r)
                              :reason (command/format-finding (first findings)))
                :findings findings})]
    (cond
      (:timed-out? r)
      (fail [(command/finding log-rel "tests-failed" (str "the test command did not finish within "
                                                          (get-in check [:budget :seconds]) " seconds: its output is in " log-rel))])
      (not (zero? (:exit r)))
      (fail [(command/finding log-rel "tests-failed" (str "the test command failed with exit status " (:exit r)
                                                          ": its output is in " log-rel))])
      (empty? (fs/glob results-dir "*-result.json"))
      (fail [(command/finding (str cycle-rel "/results") "tests-no-results"
                              (str "the test command exited 0 and wrote no Allure result into " cycle-rel
                                   "/results: a green exit with no results is not a run"))])
      :else
      (let [read (results/read-results {:root root} (str cycle-rel "/results") {:keep? true})
            {:keys [pickles refusals]} (runner/plan build {:spec-dir (command/config-value {:config cut-config} :spec-dir)
                                                           :id-prefix (command/config-value {:config cut-config} :id-prefix)
                                                           :model (command/config-value {:config cut-config} :model)})
            named (set (filter (set (keep :id pickles)) (keep :spec (:results read))))
            findings (vec (concat (map #(command/finding (:file %) (:line %) (:rule %) (one-line (:message %))) refusals)
                                  (unstamped-findings pickles)
                                  (results/unreadable-findings read)
                                  (map #(dissoc % :id) (scenario-findings pickles (:results read) mapping))))]
        (if (seq findings)
          (assoc-in (fail findings) [:entry :scenarios] (count named))
          {:entry (assoc base :outcome :passed :seconds (:seconds r) :scenarios (count named)) :findings []})))))

(defn- runs-check [check {:keys [build]}]
  (let [budget (get-in check [:budget :seconds])
        r (run-command check (:command check) build {} (long (* 1000 budget)))
        diff (runs-difference check r)
        base {:name (check-name check) :kind :runs :seconds (:seconds r) :budget budget}]
    (if diff
      {:entry (assoc base :outcome :failed :reason diff)
       :findings [(command/finding "check-failed" (str "the check " (check-name check) " failed: " diff))]}
      {:entry (assoc base :outcome :passed :margin (round2 (- budget (:seconds r)))) :findings []})))

(defn- port-held? [port]
  (or (try (with-open [s (java.net.Socket.)]
             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 200) true)
           (catch Exception _ false))
      (try (with-open [_ (java.net.ServerSocket. (int port))] false)
           (catch Exception _ true))))

(defn- answers? [port timeout-ms]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int (max 50 timeout-ms))) true)
       (catch Exception _ false)))

(defn- fill [words k v] (mapv #(str/replace (str %) k (str v)) words))

(defn- serves-check [check {:keys [build]}]
  (let [port (:port check)
        {:keys [seconds polls]} (:budget check)
        interval (long (/ (* 1000 seconds) polls))
        nm (check-name check)
        _ (when (port-held? port)
            (command/cannot-run! (str "the port " port " of the check " nm
                                      " is already held by another process: verify measures only the app it starts")))
        t0 (System/currentTimeMillis)
        proc (try (p/process (fill (:start check) "{port}" port) {:dir (str build) :out :string :err :string :in ""})
                  (catch Exception e
                    (command/cannot-run! (one-line (str "the check " nm " cannot start " (first (:start check)) ": " (ex-message e))))))]
    (try
      (let [answered (loop [i 1]
                       (cond
                         (> i polls) nil
                         (answers? port (min 500 interval)) i
                         :else (let [next-at (+ t0 (* i interval))]
                                 (Thread/sleep (max 0 (- next-at (System/currentTimeMillis))))
                                 (recur (inc i)))))
            secs (round2 (/ (- (System/currentTimeMillis) t0) 1000.0))
            base {:name nm :kind :serves :seconds secs :budget seconds :polls polls}]
        (if-not answered
          {:entry (assoc base :outcome :failed :reason (str "no answer came within " seconds " seconds (" polls " polls)"))
           :findings [(command/finding "check-failed" (str "the check " nm " failed: no answer came within " seconds
                                                           " seconds (" polls " polls) on the port " port))]}
          (let [url (str "http://127.0.0.1:" port)
                pr (run-command check (fill (:probe check) "{url}" url) build {} (* 1000 seconds))]
            (if (and (not (:timed-out? pr)) (zero? (:exit pr)))
              {:entry (assoc base :outcome :passed :answered-on-poll answered :margin (- polls answered)) :findings []}
              (let [why (str "the app answered on poll " answered ", and its probe "
                             (if (:timed-out? pr) "did not finish" (str "exited " (:exit pr)))
                             ": " (one-line (str (:out pr) " " (:err pr))))]
                {:entry (assoc base :outcome :failed :answered-on-poll answered :reason why)
                 :findings [(command/finding "check-failed" (str "the check " nm " failed: " why))]})))))
      (finally
        (p/destroy-tree proc)
        (try (deref proc 5000 nil) (catch Exception _ nil))))))

;; ---------------------------------------------------------------------------
;; The checkout

(defn- export!
  "Exports the cut's files into `build`, no history."
  [root cut build]
  (let [tar (str (fs/file (fs/create-temp-dir {:prefix "verify-"}) "cut.tar"))]
    (try
      (let [r (git root "archive" "--format=tar" "-o" tar cut)]
        (when-not (zero? (:exit r)) (command/cannot-run! (one-line (str "the cut " cut " cannot be exported: " (:err r))))))
      (fs/delete-tree build)
      (fs/create-dirs build)
      (let [r (p/shell {:out :string :err :string :continue true :in ""} "tar" "-xf" tar "-C" (str build))]
        (when-not (zero? (:exit r)) (command/cannot-run! (one-line (str "the cut " cut " cannot be unpacked: " (:err r))))))
      (finally (fs/delete-tree (fs/parent tar))))))

(defn- copy-path! [src dst]
  (if (fs/directory? src)
    (fs/copy-tree src dst {:replace-existing true})
    (do (fs/create-dirs (fs/parent dst)) (fs/copy src dst {:replace-existing true}))))

(defn room-tree
  "The git tree of the implementation paths `impl` of `room`."
  [room impl]
  (let [g (str (fs/create-temp-dir {:prefix "verify-tree-"}))]
    (try
      (git g "init" "-q")
      (doseq [pth impl :let [src (fs/file room pth)] :when (fs/exists? src)]
        (copy-path! src (fs/file g pth)))
      (git g "add" "-A" "-f" "--" ".")
      (str/trim (:out (git g "write-tree")))
    (finally (fs/delete-tree g)))))

;; ---------------------------------------------------------------------------
;; The stage

(defn verify
  "The verify stage."
  [req]
  (let [[room-arg cycle] (:args req)
        root (str (:root req))
        cycle-rel (str (str/replace (command/config-value req :cycles-dir) #"/+$" "") "/" cycle)
        cycle-file (command/path req (str cycle-rel "/cycle.edn"))
        _ (when-not (fs/regular-file? cycle-file)
            (command/cannot-run! (str "there is no " cycle-rel "/cycle.edn: a cycle is declared before it is verified")))
        c (try (edn/read-string (slurp (str cycle-file)))
               (catch Exception e (command/cannot-run! (one-line (str cycle-rel "/cycle.edn is not readable EDN: " (ex-message e))))))
        cut (:cut c)
        _ (when-not (string? cut) (command/cannot-run! (str cycle-rel "/cycle.edn names no :cut")))
        _ (when-not (zero? (:exit (git root "rev-parse" "-q" "--verify" (str "refs/tags/" cut))))
            (command/cannot-run! (str "the cut " cut " that " cycle-rel "/cycle.edn names is not a tag of this repository")))
        cfg-r (git root "show" (str cut ":" command/config-file))
        _ (when-not (zero? (:exit cfg-r))
            (command/cannot-run! (str "the cut " cut " holds no " command/config-file)))
        cut-config (try (edn/read-string (:out cfg-r))
                        (catch Exception e (command/cannot-run! (one-line (str "the cut's " command/config-file " is not readable EDN: " (ex-message e))))))
        impl (vec (:implementation cut-config))
        checks (:verify cut-config)
        _ (when-let [why (checks-problem checks impl)] (command/cannot-run! why))
        mapping (runner/mapping-of cut-config)
        _ (when-let [why (runner/mapping-problem mapping)]
            (command/cannot-run! (str "the configuration's :allure cannot be used: " why)))
        room (command/path req room-arg)
        _ (when-not (fs/directory? room) (command/cannot-run! (str "there is no room " room-arg)))
        build-rel (str cycle-rel "/build")
        build (fs/file root build-rel)
        _ (export! root cut build)
        _ (doseq [pth impl] (fs/delete-tree (fs/file build pth)))
        _ (doseq [pth impl :let [src (fs/file room pth)] :when (fs/exists? src)] (copy-path! src (fs/file build pth)))
        tree (room-tree room impl)
        env {:root root :build (str build) :cycle-rel cycle-rel :cut-config cut-config :mapping mapping}
        {:keys [entries findings]}
        (reduce (fn [{:keys [entries findings stopped?] :as acc} check]
                  (if stopped?
                    (update acc :entries conj {:name (check-name check) :kind (:kind check) :outcome :not-run})
                    (let [{:keys [entry] :as out} (case (:kind check)
                                                    :tests (tests-check check env)
                                                    :runs (runs-check check env)
                                                    :serves (serves-check check env))]
                      {:entries (conj entries entry)
                       :findings (into findings (:findings out))
                       :stopped? (not= :passed (:outcome entry))})))
                {:entries [] :findings [] :stopped? false}
                checks)
        passed? (every? #(= :passed (:outcome %)) entries)
        rat (cond-> {:cut cut :room-tree tree :outcome (if passed? :passed :refused)
                     :checkout build-rel :checks entries}
              (not passed?) (assoc :reason (:reason (first (remove #(= :passed (:outcome %)) entries)))))]
    (binding [*print-namespace-maps* false]
      (spit (str (fs/file root cycle-rel "ratified.edn")) (str (pr-str rat) "\n")))
    {:result {:verify {:cycle cycle :cut cut :outcome (:outcome rat) :checks entries}}
     :findings findings}))
