(ns steps.verification-verify
  "Step definitions for dhf/spec/verification/verify.feature: `rdm-spec
  verify ROOM 03` on a verify project the scenario makes, and a room.

  The verify project: the room's configuration without :oracle, a model
  with the bounded context architecture (Lint), the need UN-001, and
  dhf/spec/architecture/lint.feature holding, under the rule \"Every view is
  described\", spec-0003 and spec-0012 (both @e2e), plus what a step adds;
  a git repository whose commit (dhf/cycles excluded) is tagged spec-3, and
  dhf/cycles/03/cycle.edn naming it. The room: src/rdm_spec/demo.clj and a
  test.clj that prints a line, writes the results its plan holds into
  RESULTS_DIR and exits as planned; by default a result for every stamped
  scenario not tagged @wip that holds to rdm-spec's default Allure mapping.

  Every Given changes the scenario's plan and rebuilds both (`build!`): the
  project is committed again and spec-3 moved to the new commit, so a
  Given can change the cut's configuration after another Given made it.

  Context keys set here: :dir, :room, :cfg (the configuration at the cut),
  :scenarios, :cut-files ({rel text} committed at the cut beyond the spec),
  :plan ({:exit :results :log}), :room-files ({rel text} beyond test.clj),
  :pid-file (where a served app writes its pid), :marker (a file a served
  app writes when it starts), :held (a socket this process holds),
  :port, :tree (the room's tree hash)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The project and the room

(def model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts [{:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}]}]
   :relationships [] :views []})

(def default-scenarios
  [{:id "spec-0003" :title "An unlabelled relationship fails the lint"
    :steps ["Given a relationship with no description" "When \"rdm-spec check\" is run" "Then it fails"]}
   {:id "spec-0012" :title "A view with no description fails the lint"
    :steps ["Given a view with no description" "Then it fails"]}])

(def rule-name "Every view is described")

(defn- scenario-text [{:keys [id title steps wip? level]}]
  (str "\n    " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n    Scenario: " title "\n"
       (str/join (map #(str "      " % "\n") steps))))

(defn feature-text [scenarios]
  (str "@UN-001\nFeature: Lint\n  A fixture.\n\n  Rule: " rule-name "\n" (str/join (map scenario-text scenarios))))

(def lint-rel "dhf/spec/architecture/lint.feature")

(defn good-result
  "A result of the scenario `sc` that holds to rdm-spec's default mapping."
  [{:keys [id title steps wip? level]}]
  (cond-> {:uuid (str "r-" id) :name title :status (if wip? "skipped" "passed") :stage "finished"
           :fullName (str lint-rel ":" title) :historyId id
           :steps (vec (for [st steps] {:name st :status (if wip? "skipped" "passed")}))
           :labels (vec (concat [{:name "feature" :value "Lint"} {:name "story" :value rule-name}
                                 {:name "epic" :value "architecture"} {:name "suite" :value "architecture"}
                                 {:name "subSuite" :value "architecture/lint"}]
                                (for [t (remove nil? [id (or level "e2e") "UN-001" (when wip? "wip")])] {:name "tag" :value t})
                                [{:name "framework" :value "gherkin"} {:name "spec" :value id}
                                 {:name "context" :value "architecture"} {:name "component" :value "Lint"}]
                                (when wip? [{:name "planned" :value "true"}])))
           :links [{:type "issue" :name "UN-001" :url "dhf/spec/needs.edn#UN-001"}]
           :parameters [] :attachments []}
    (not wip?) (assoc :attachments [{:name "browser-trace" :source (str id "-trace.zip") :type "application/zip"}])))

(def default-cfg-extra
  {:verify [{:kind :tests :command ["bb" "test.clj"]}]})

(defn- base-cfg [] (merge (dissoc (s/room-config) :oracle :allure) default-cfg-extra))

(defn- git! [dir & args] (apply kc/git! dir args))

(defn- room-test-clj [{:keys [exit results log env-file]}]
  (str "(require '[cheshire.core :as json])\n"
       "(def results-dir (System/getenv \"RESULTS_DIR\"))\n"
       "(println " (pr-str (or log "the room's tests ran")) ")\n"
       (when env-file (str "(spit " (pr-str env-file) " results-dir)\n"))
       "(doseq [[i r] (map-indexed vector " (pr-str (mapv #(json/generate-string % {:pretty true}) results)) ")]\n"
       "  (spit (str results-dir \"/r\" i \"-result.json\") r))\n"
       "(System/exit " (or exit 0) ")\n"))

(defn build!
  "Writes the scenario's project and room from ctx; ctx with both."
  [ctx]
  (let [dir (or (:dir ctx) (s/tmp-dir))
        room (or (:room ctx) (s/tmp-dir))
        scs (or (:scenarios ctx) default-scenarios)
        plan (merge {:exit 0 :results (mapv good-result (remove :wip? scs))} (:plan ctx))
        cfg (or (:cfg ctx) (base-cfg))]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str cfg) "\n"))
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "A need." :sources ["demo:README.md@abc1234"]}}))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (s/write! dir lint-rel (feature-text scs))
    (doseq [[rel text] (:cut-files ctx)] (s/write! dir rel text))
    (kc/cut! dir "spec-3")
    (when-not (:no-cycle? ctx) (kc/write-cycle! dir))
    (s/write! room "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! room "test.clj" (room-test-clj plan))
    (doseq [[rel text] (:room-files ctx)] (s/write! room rel text))
    (assoc ctx :dir dir :room room :scenarios scs :plan plan :cfg cfg)))

(defn- cfg [ctx] (or (:cfg ctx) (base-cfg)))

(defn- with-cfg [ctx f] (build! (assoc ctx :cfg (f (cfg ctx)))))

(defn- plan [ctx f] (build! (assoc ctx :plan (f (merge {:exit 0 :results (mapv good-result (remove :wip? (or (:scenarios ctx) default-scenarios)))}
                                                        (:plan ctx))))))

(defn- cycle-file [ctx rel] (fs/file (:dir ctx) "dhf/cycles/03" rel))

(defn- ratified [ctx]
  (let [f (cycle-file ctx "ratified.edn")]
    (when (fs/exists? f) (edn/read-string (slurp (str f))))))

(defn- out [ctx] (:verify (kc/stdout-edn (kc/the-run ctx))))

(defn- check-of [ctx nm] (first (filter #(= nm (:name %)) (:checks (out ctx)))))

(defn- names! [r & parts]
  (s/check! (some (fn [l] (every? #(str/includes? l %) parts)) (s/err-lines r))
            (str "no line names " (str/join ", " (map pr-str parts)) "\n" (s/show r)))
  r)

(defn- verify! [ctx] (kc/run-line ctx "verify ROOM 03"))

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          build (fs/file (:dir ctx) "dhf/cycles/03/build")
          at-cut (set (str/split-lines (git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))
          impl (:implementation (kc/config-of (:dir ctx)))
          in-build (set (for [f (fs/glob build "**" {:hidden true}) :when (fs/regular-file? f)]
                          (str (fs/relativize build f))))
          outside-impl (set (remove (fn [rel] (some #(or (= rel %) (str/starts-with? rel (str % "/"))) impl)) in-build))]
      (s/check! (not (fs/exists? (fs/file build ".git"))) "the checkout holds history")
      (s/check! (= at-cut outside-impl) (str "the checkout holds " (sort outside-impl) "\nthe cut holds " (sort at-cut) "\n" (s/show r)))
      nil)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx] (build! (assoc ctx :no-cycle? true))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (kc/refused-naming! ctx "dhf/cycles/03/cycle.edn") nil))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx] (build! (assoc ctx :cut-files {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"}))))

(defstep #"the checkout it verifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/build/src/rdm_spec/old.clj"))) "the checkout holds old.clj")
    nil))

(defn- files-under [d]
  (into (sorted-map) (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (slurp (str f))])))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [build (fs/file (:dir ctx) "dhf/cycles/03/build")]
      (doseq [p (:implementation (cfg ctx))
              :let [a (fs/file (:room ctx) p) b (fs/file build p)]]
        (cond
          (fs/directory? a) (s/check! (= (files-under a) (files-under b)) (str p " differs from the room's"))
          (fs/exists? a) (s/check! (= (slurp (str a)) (slurp (str b))) (str p " differs from the room's"))
          :else (s/check! (not (fs/exists? b)) (str p " is in the checkout but not in the room"))))
      nil)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (build! ctx)]
      (build! (assoc ctx :room-files {"dhf/harness/config.edn" (pr-str (assoc (cfg ctx) :verify [{:kind :tests :command ["bb" "-e" "(System/exit 1)"]}]))
                                      lint-rel "Feature: Edited\n  By the builder.\n"})))))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [rel ["dhf/harness/config.edn" lint-rel]]
      (s/check! (= (git! (:dir ctx) "show" (str "spec-3:" rel)) (str/trim (slurp (str (fs/file (:dir ctx) "dhf/cycles/03/build" rel)))))
                (str rel " in the checkout is not the cut's")))
    nil))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx]
    (let [f (str (fs/file (s/tmp-dir) "results-dir.txt"))
          ctx (plan ctx #(assoc % :env-file f))]
      (assoc (verify! ctx) :env-file f))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (str/trim (slurp (:env-file ctx)))
          want (str (fs/canonicalize (fs/file (:dir ctx) "dhf/cycles/03/results")))]
      (s/check! (fs/absolute? got) (str "RESULTS_DIR is " got ", not absolute"))
      (s/check! (= want (str (fs/canonicalize got))) (str "RESULTS_DIR is " got ", not " want))
      nil)))

(defstep #"the :tests check's command exits (\d)"
  (fn [ctx n] (plan ctx #(assoc % :exit (parse-long n)))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (s/check! (seq (get-in ctx [:plan :results])) "the room writes no result") ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx]
    (let [named (set (for [r (get-in ctx [:plan :results]) :when (= "passed" (:status r)) l (:labels r)
                           :when (= "spec" (:name l))] (:value l)))]
      (s/check! (every? named (keep #(when-not (:wip? %) (:id %)) (:scenarios ctx))) "a scenario is not named")
      ctx)))

(defstep #"the tests check passes"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= :passed (:outcome (check-of ctx "tests"))) (str "the tests check is " (pr-str (check-of ctx "tests"))))
    nil))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (names! (kc/exits! ctx 1) "test command" "failed" "exited 1")
    nil))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (s/check! (str/includes? (slurp (str (cycle-file ctx "test.log"))) "the room's tests ran") "test.log does not hold the output")
    (names! (kc/the-run ctx) "test command" "dhf/cycles/03/test.log")
    nil))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (plan ctx #(assoc % :exit 0 :results []))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (names! (kc/exits! ctx 1) "a green exit with no results is not a run") nil))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (build! ctx)]
      (s/check! (not (:wip? (first (filter #(= "spec-0012" (:id %)) (:scenarios ctx))))) "spec-0012 is planned")
      ctx)))

(defn- without [results id] (vec (remove #(some (fn [l] (= {:name "spec" :value id} l)) (:labels %)) results)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (plan ctx #(update % :results without "spec-0012"))))

(defstep #"it exits 1 and names (spec-\d+)"
  (fn [ctx id] (names! (kc/exits! ctx 1) id) nil))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (plan ctx (fn [p] (update p :results (fn [rs] (mapv #(if (= "r-spec-0012" (:uuid %)) (assoc % :status "failed") %) rs)))))))

(defstep #"it exits 1 and names (spec-\d+) and its status"
  (fn [ctx id] (names! (kc/exits! ctx 1) id "failed") nil))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(defn tree-of
  "The git tree hash of the room's implementation paths `paths`."
  [room paths]
  (let [g (s/tmp-dir)
        run (fn [& args] (let [r (apply p/shell {:out :string :err :string :continue true :in ""}
                                        "git" (str "--git-dir=" g) (str "--work-tree=" room) args)]
                           (s/check! (zero? (:exit r)) (str "git " (str/join " " args) ": " (:err r)))
                           (str/trim (:out r))))]
    (run "init" "-q")
    (let [present (filter #(fs/exists? (fs/file room %)) paths)]
      (when (seq present) (apply run "add" "-A" "-f" "--" present))
      (run "write-tree"))))

(def answers {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help []}\")"]
              :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (with-cfg ctx #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]} answers]))]
      (assoc ctx :tree (tree-of (:room ctx) (:implementation (cfg ctx)))))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (let [ctx (verify! ctx)] (kc/exits! ctx 0) ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [want {:cut "spec-3" :room-tree (:tree ctx) :outcome :passed :checkout "dhf/cycles/03/build"
                :checks (:checks (out ctx))}]
      (s/check! (= 40 (count (:tree ctx))) "H is not a full hash")
      (s/check! (= want (ratified ctx)) (str "ratified.edn holds " (pr-str (ratified ctx)) "\nnot " (pr-str want)))
      nil)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (s/check! (= (slurp (str (fs/file (:room ctx) "test.clj"))) (slurp (str (cycle-file ctx "build/test.clj"))))
              "the checkout does not hold the room's tests")
    nil))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx] (verify! (plan ctx #(assoc % :exit 1)))))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (ratified ctx)]
      (s/check! (= :refused (:outcome r)) (str "ratified.edn holds " (pr-str r)))
      (s/check! (and (string? (:reason r)) (str/includes? (:reason r) "exited 1")) (str "the reason is " (pr-str (:reason r))))
      nil)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (kc/finding-lines (kc/exits! ctx 1)))
                (str "no finding names " file ":" line "\n" (s/show (kc/the-run ctx))))
      nil)))

;; ---------------------------------------------------------------------------
;; A project declares its checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (with-cfg ctx #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]} answers]))))

(defstep #"both pass"
  (fn [ctx] (build! ctx)))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          v (out ctx)
          [t a] (:checks v)]
      (s/check! (= {:cycle "03" :cut "spec-3" :outcome :passed}
                   (select-keys v [:cycle :cut :outcome])) (str "the result is " (pr-str v)))
      (s/check! (= #{:verify} (set (keys (kc/stdout-edn r)))) "the result holds more than :verify")
      (s/check! (= #{:cycle :cut :outcome :checks} (set (keys v))) (str "the result holds " (keys v)))
      (s/check! (and (= {:name "tests" :kind :tests :outcome :passed :scenarios 2} (dissoc t :seconds))
                     (number? (:seconds t)))
                (str "the tests check is " (pr-str t)))
      (s/check! (and (= {:name "answers" :kind :runs :outcome :passed :budget 30} (dissoc a :seconds :margin))
                     (number? (:seconds a)) (number? (:margin a))
                     (< (Math/abs (double (- (:margin a) (- 30 (:seconds a))))) 0.02))
                (str "the runs check is " (pr-str a)))
      (s/check! (= 2 (count (:checks v))) "more checks than declared")
      nil)))

(defn- serves
  "A :serves check of the app src/app.clj on `port`, its probe the kept
  dhf/harness/probe.clj."
  [port seconds polls]
  {:kind :serves :name "app" :start ["bb" "src/app.clj" "{port}"] :port port
   :budget {:seconds seconds :polls polls} :probe ["bb" "dhf/harness/probe.clj" "{url}"]})

(defn free-port []
  (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss)))

(defn- app-text
  "A served app: writes its pid and a marker, then on its port answers
  connections as `mode` says: :second (closes the first connection unanswered,
  answers the rest), :never-listens, :silent (accepts, never answers),
  :answers."
  [ctx mode]
  (str "(spit " (pr-str (:pid-file ctx)) " (str (.pid (java.lang.ProcessHandle/current))))\n"
       "(spit " (pr-str (:marker ctx)) " \"started\")\n"
       "(def port (parse-long (first *command-line-args*)))\n"
       (case mode
         :never-listens "(Thread/sleep 60000)\n"
         (str "(def ss (doto (java.net.ServerSocket.) (.setReuseAddress true) (.bind (java.net.InetSocketAddress. \"127.0.0.1\" port))))\n"
              "(loop [n 1]\n"
              "  (let [c (.accept ss)]\n"
              (case mode
                :silent "    (future (Thread/sleep 60000) (.close c))\n"
                :second "    (if (= 1 n) (.close c) (future (with-open [c c] (Thread/sleep 50) (let [o (.getOutputStream c)] (.write o (.getBytes \"HTTP/1.0 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok\")) (.flush o)))))\n"
                :answers "    (future (with-open [c c] (Thread/sleep 50) (let [o (.getOutputStream c)] (.write o (.getBytes \"HTTP/1.0 404 Not Found\\r\\nContent-Length: 0\\r\\n\\r\\n\")) (.flush o))))\n")
              "    (recur (inc n))))\n"))))

(defn- probe-text [exit] (str "(println \"probed\" (first *command-line-args*) " (if (zero? exit) "\"ok\"" "\"not this app\"") ")\n(System/exit " exit ")\n"))

(defn- served
  "ctx with a :serves check of an app in `mode`, budget `seconds` in `polls`."
  [ctx mode seconds polls & {:keys [probe port] :or {probe 0}}]
  (let [t (s/tmp-dir)
        port (or port (free-port))
        ctx (assoc ctx :pid-file (str (fs/file t "pid")) :marker (str (fs/file t "marker")) :port port)]
    (build! (-> ctx
                (assoc :cfg (assoc (cfg ctx) :verify [{:kind :tests :command ["bb" "test.clj"]} (serves port seconds polls)]))
                (assoc :room-files {"src/app.clj" (app-text ctx mode)})
                (assoc :cut-files {"dhf/harness/probe.clj" (probe-text probe)})))))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (let [ctx (served ctx :answers 10 20)]
      (-> ctx
          (assoc :cfg (update (cfg ctx) :verify conj answers))
          (assoc-in [:plan :exit] 1)
          build!))))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= [[:tests :failed] [:serves :not-run] [:runs :not-run]] (mapv (juxt :kind :outcome) (:checks (out ctx))))
              (str "the checks are " (pr-str (:checks (out ctx)))))
    (s/check! (not (fs/exists? (:marker ctx))) "the app was started")
    nil))

(def ^:private untrusted
  {"declares no :tests check" #(assoc % :verify [answers])
   "declares a check of the kind :renders" #(update % :verify conj {:kind :renders :name "pictures"})
   "declares a :serves check whose probe is under src/" #(update % :verify conj (assoc (serves 8999 10 20) :probe ["bb" "src/probe.clj" "{url}"]))
   "is absent" #(dissoc % :verify)
   "declares a :tests check with no command" #(assoc % :verify [{:kind :tests}])})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is] (with-cfg ctx (get untrusted is))))

(defn- ran-no-check! [ctx]
  (s/check! (not (fs/exists? (cycle-file ctx "test.log"))) "a check ran: test.log was written")
  (s/check! (not (fs/exists? (cycle-file ctx "ratified.edn"))) "ratified.edn was written"))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (kc/refused-naming! ctx why)
    (ran-no-check! ctx)
    nil))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx] (with-cfg ctx #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]}
                                             (assoc answers :command ["rdm-spec-no-such-command" "help"])]))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (kc/refused-naming! ctx "answers" "rdm-spec-no-such-command") nil))

;; ---------------------------------------------------------------------------
;; A :serves check

(defn- stopped! [ctx]
  (let [f (:pid-file ctx)]
    (when (fs/exists? f)
      (let [pid (parse-long (str/trim (slurp f)))
            h (.orElse (java.lang.ProcessHandle/of pid) nil)]
        (when (and h (.isAlive h))
          (.destroyForcibly h)
          (s/fail! (str "the app " pid " is still running")))))))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx] (served ctx :second 10 20)))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          c (check-of ctx "app")]
      (s/check! (= {:outcome :passed :answered-on-poll 2 :polls 20 :margin 18} (select-keys c [:outcome :answered-on-poll :polls :margin]))
                (str "the check is " (pr-str c) "\n" (s/show r)))
      nil)))

(defstep #"no process the check started is still running"
  (fn [ctx] (stopped! ctx) nil))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (served ctx :never-listens 10 20)))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx n]
    (let [r (kc/exits! ctx 1)]
      (s/check! (= :failed (:outcome (check-of ctx "app"))) (str "the check is " (pr-str (check-of ctx "app"))))
      (names! r "app" (str "no answer came within " n " seconds"))
      (stopped! ctx)
      nil)))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          ctx (served ctx :answers 10 20 :port (.getLocalPort ss))]
      (assoc ctx :held ss))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (try
      (kc/refused-naming! ctx (str (:port ctx)))
      (s/check! (not (fs/exists? (:marker ctx))) "an app was started")
      (ran-no-check! ctx)
      nil
      (finally (.close ^java.net.ServerSocket (:held ctx))))))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [ctx]
    (let [port (free-port)
          ctx (served ctx :second 10 20 :port port)
          proc (p/process {:dir (str (:room ctx)) :out :string :err :string} "bb" "src/app.clj" (str port))]
      (try
        ;; the first connection is closed unanswered, the second answered: both leave closing connections
        (loop [i 0]
          (when (< i 50)
            (when-not (try (with-open [c (java.net.Socket. "127.0.0.1" (int port))] (.read (.getInputStream c)) true)
                           (catch Exception _ nil))
              (Thread/sleep 100) (recur (inc i)))))
        (with-open [c (java.net.Socket. "127.0.0.1" (int port))]
          (let [o (.getOutputStream c)] (.write o (.getBytes "GET / HTTP/1.0\r\n\r\n")) (.flush o))
          (.read (.getInputStream c)))
        (finally (.destroyForcibly ^java.lang.Process (:proc proc)) @proc))
      (fs/delete-if-exists (:marker ctx))
      ctx)))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (fs/exists? (:marker ctx)) "the app was not started")
      (s/check! (= :passed (:outcome (check-of ctx "app"))) (str "the check is " (pr-str (check-of ctx "app")) "\n" (s/show r)))
      (stopped! ctx)
      nil)))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx] (served ctx :answers 10 20 :probe 1)))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (= :failed (:outcome (check-of ctx "app"))) (str "the check is " (pr-str (check-of ctx "app"))))
      (names! r "app" "not this app")
      (stopped! ctx)
      nil)))

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [ctx] (served ctx :silent 2 4)))

;; ---------------------------------------------------------------------------
;; A :runs check

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [ctx expected]
    (let [check (case expected
                  "the exit 0" (assoc answers :expect {:exit 0})
                  "standard output starting \"{:help\"" (assoc answers :expect {:exit 0 :stdout-starts "{:help"})
                  "an answer within 10 seconds" (assoc answers :expect {:exit 0} :budget {:seconds 10}))]
      (with-cfg ctx #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]} check])))))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [cmd (case does
                "exits 3" ["bb" "-e" "(System/exit 3)"]
                "prints \"usage\" and exits 0" ["bb" "-e" "(println \"usage\")"]
                "is still running after 10 seconds" ["bb" "-e" "(Thread/sleep 15000)"])]
      (with-cfg ctx #(update % :verify (fn [vs] (mapv (fn [c] (if (= :runs (:kind c)) (assoc c :command cmd) c)) vs)))))))

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx difference]
    (let [r (kc/exits! ctx 1)]
      (s/check! (= :failed (:outcome (check-of ctx "answers"))) (str "the check is " (pr-str (check-of ctx "answers"))))
      (case difference
        "the exit 3 where 0 was expected" (names! r "answers" "exited 3 where 0 was expected")
        "the first line it printed" (names! r "answers" "\"usage\"")
        "the budget of 10 seconds" (names! r "answers" "10 seconds"))
      nil)))

;; ---------------------------------------------------------------------------
;; The tests check holds every result to the project's Allure mapping

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (with-cfg ctx #(assoc % :allure runner/default-mapping))))

(defn- spec-0003 [f] (fn [rs] (mapv #(if (= "r-spec-0003" (:uuid %)) (f %) %) rs)))

(defn- relabel [r nm v] (update r :labels (fn [ls] (mapv #(if (= nm (:name %)) (assoc % :value v) %) ls))))

(def ^:private wrongs
  {"a second step reworded from the scenario's line" #(assoc-in % [:steps 1 :name] "When \"rdm-spec check\" runs")
   "one step fewer than the scenario's lines" #(update % :steps pop)
   "the story \"Something else\", not the scenario's rule" #(relabel % "story" "Something else")
   "the epic of another bounded context" #(relabel % "epic" "measurement")
   "no issue link to UN-001, the need the scenario traces to" #(assoc % :links [])
   "no browser-trace attachment, where :evidence requires one for @e2e" #(assoc % :attachments [])})

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [f (or (get wrongs wrong) (s/fail! (str "no such change: " wrong)))
          ctx (if (str/includes? wrong ":evidence requires")
                (assoc ctx :cfg (assoc-in (cfg ctx) [:allure :evidence] {:e2e ["browser-trace"]}))
                ctx)]
      (plan ctx #(update % :results (spec-0003 f))))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (let [r (kc/exits! ctx 1)]
      (s/check! (= :failed (:outcome (check-of ctx "tests"))) (str "the tests check is " (pr-str (check-of ctx "tests"))))
      (s/check! (some #(re-find (re-pattern (str "^dhf/cycles/03/results/[^:]+-result\\.json:\\d+: "
                                                 (java.util.regex.Pattern/quote finding) ".*spec-0003")) %)
                      (kc/finding-lines r))
                (str "no finding FILE:LINE: " finding " ... spec-0003 at the result's file\n" (s/show r)))
      nil)))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [ctx]
    (build! (-> ctx
                (assoc :cfg (assoc (cfg ctx) :allure (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]})))
                (assoc :scenarios (conj default-scenarios {:id "spec-0020" :title "A planned one" :wip? true
                                                           :steps ["Given a plan"]}))))))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx]
    (let [planned (good-result (last (:scenarios ctx)))]
      (s/check! (empty? (:attachments planned)) "the planned result has an attachment")
      (plan ctx #(update % :results conj planned)))))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (= :passed (:outcome (check-of ctx "tests"))) (str "the tests check is " (pr-str (check-of ctx "tests"))))
      (s/check! (not-any? #(str/includes? % "spec-0020") (s/err-lines r)) (str "spec-0020 is named\n" (s/show r)))
      nil)))

(def ^:private unreadable
  {"maps :epic to :aggregate" #(assoc % :epic :aggregate)
   "maps :colour to :context" #(assoc % :colour :context)
   "maps no label \"spec\"" #(update % :labels dissoc "spec")})

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is] (with-cfg ctx #(assoc % :allure ((get unreadable is) runner/default-mapping)))))

(defstep #"a configuration that declares no :allure"
  (fn [ctx]
    (let [ctx (build! ctx)]
      (s/check! (not (contains? (cfg ctx) :allure)) "the configuration declares :allure")
      ctx)))

(defstep #"each result is held to \{.*\}"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [[what f finding] [["the label feature" #(relabel % "feature" "Other") "mapping-differs: feature"]
                              ["the name" #(assoc % :name "Another title") "mapping-differs: name"]
                              ["the label component" #(relabel % "component" "Views") "mapping-differs: labels"]
                              ["the tags" #(update % :labels (fn [ls] (vec (remove (fn [l] (= {:name "tag" :value "UN-001"} l)) ls))))
                               "mapping-differs: labels"]]]
      (let [c (verify! (plan ctx #(update % :results (spec-0003 f))))
            r (kc/the-run c)]
        (s/check! (= 1 (:exit r)) (str "a result with another " what " passes\n" (s/show r)))
        (s/check! (some #(and (str/includes? % finding) (str/includes? % "spec-0003")) (kc/finding-lines r))
                  (str "no finding " finding " for " what "\n" (s/show r)))))
    nil))
