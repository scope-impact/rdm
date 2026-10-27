(ns rdm-spec.verification.verify
  "Verify (dhf/spec/verification/verify.feature): a cycle's gate. It reads
  the cycle, then puts the room's implementation, as git would commit it,
  into a fresh export of the cut (dhf/cycles/NN/build), and runs every check
  the cut's configuration declares, in order, the first that fails stopping
  it: :tests (the test command passes, writes results, and its results name
  and pass every scenario, each held to the Allure mapping), :serves (the app
  answers within its budget and passes its probe), :runs (a command answers
  as expected within its budget) and :plugin (the project's judge answering
  :Check). It records ratified.edn and, when every check passed, publishes
  CycleVerified. It gates, and never scores."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "N#{id}"}}
   :evidence {}})

(def allure-elements #{:epic :feature :story :suite :subSuite :parentSuite :name :fullName :step :parameter
                       :labels :links :evidence})
(def label-elements #{:epic :feature :story :suite :subSuite :parentSuite})
(def spec-elements #{:context :feature :rule :scenario :scenario-id :component :component-id :gherkin-tags
                     :gherkin-step :example-row :level :needs})

(defn mapping-problem
  "Why verify cannot read a mapping, or nil."
  [m]
  (or (some (fn [k] (when-not (allure-elements k) (str k " is not an element of an Allure result"))) (keys m))
      (some (fn [[k v]] (when (and (not (#{:labels :links :evidence} k)) (not (spec-elements v)))
                          (str v " is not an element of the spec, which " k " is mapped to")))
            m)
      (some (fn [[l v]] (when-not (spec-elements v) (str v " is not an element of the spec, which the label " l " is mapped to")))
            (:labels m))
      (when-not (= :scenario-id (get-in m [:labels "spec"]))
        "the label spec, from the scenario's id, is required: :labels maps \"spec\" to :scenario-id")
      (when-not (and (map? (:links m)) (every? #(and (map? %) (string? (:url %))) (vals (:links m))))
        ":links maps each linked element to {:type :url}")
      (when-not (and (map? (:evidence m)) (every? #(and (sequential? %) (every? string? %)) (vals (:evidence m))))
        ":evidence maps a level to the names of the attachments it requires")))

(defn mapping-of [config] (merge default-mapping (:allure config)))

(defn link-url
  "A link's url from its template: N is the needs file, {id} the need."
  [template needs id]
  (-> (str template)
      (str/replace #"^N(?=#)" needs)
      (str/replace "{needs}" needs)
      (str/replace "{id}" id)))

(defn- labels-named [r n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get r "labels"))))

(defn step-names
  "The names a scenario's (or an outline row's) steps carry."
  [s row]
  (let [values (when row (zipmap (:headings row) (:row row)))]
    (vec (for [st (concat (:background s) (:steps s))]
           (str (:keyword st) " " (reduce (fn [t [h v]] (str/replace t (str "<" h ">") v)) (:text st) values))))))

(defn- spec-value [s row elem]
  (case elem
    :context (some-> (:context s) name)
    :feature (:feature s)
    :rule (:rule s)
    :scenario (:title s)
    :scenario-id (:id s)
    :component (:component-name s)
    :component-id (some-> (:component s) str (subs 1))
    :gherkin-tags (:all-tags s)
    :gherkin-step (step-names s row)
    :example-row (when row (mapv (fn [h v] {"name" h "value" v}) (:headings row) (:row row)))
    :level (some-> (:level s) name)
    :needs (:needs s)
    nil))

(defn- attachments [x]
  (concat (get x "attachments") (mapcat attachments (get x "steps"))))

(defn mapping-findings
  "The findings about one result r of the scenario s (row: an outline's row)."
  [m s row r file text needs]
  (let [line (fn [needle] (or (when needle (parser/line-in text needle)) 1))
        f (fn [needle rule msg] (cmd/finding file (line needle) rule (str msg " (" (:id s) ")")))
        differs (fn [k msg & [needle]] (f needle "mapping-differs" (str (name k) ": " msg)))]
    (vec
     (concat
      (for [[k elem] m
            :when (label-elements k)
            :let [want (spec-value s row elem)
                  got (first (labels-named r (name k)))]
            :when (not= want got)
            :when (not (and (nil? want) (nil? got)))]
        (differs k (str "the label " (name k) " is " (pr-str got) " where the scenario's " (name elem) " is " (pr-str want))
                 (str "\"" (name k) "\"")))
      (when-let [elem (:name m)]
        (let [want (spec-value s row elem)]
          (when (not= want (get r "name"))
            [(differs :name (str "the name is " (pr-str (get r "name")) " where the scenario's " (name elem) " is " (pr-str want)) "\"name\"")])))
      (when-let [elem (:fullName m)]
        (let [want (spec-value s row elem)]
          (when (not= want (get r "fullName"))
            [(differs :fullName (str "the fullName is " (pr-str (get r "fullName")) " where it is " (pr-str want)) "\"fullName\"")])))
      (when (:step m)
        (let [want (step-names s row)
              got (mapv #(get % "name") (get r "steps"))]
          (when (not= want got)
            (let [i (or (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector want got))) (min (count want) (count got)))]
              [(f (get got i "\"steps\"") "steps-differ-from-scenario"
                  (str "the result has " (count got) " steps where the scenario has " (count want)
                       (when (< i (min (count want) (count got)))
                         (str ", and step " (inc i) " is " (pr-str (get got i)) " where the scenario's line is " (pr-str (get want i))))))]))))
      (when (and (:parameter m) row)
        (let [want (spec-value s row (:parameter m))]
          (when (not= want (vec (get r "parameters")))
            [(differs :parameter (str "the parameters are " (pr-str (get r "parameters")) " where the row is " (pr-str want)) "\"parameters\"")])))
      (for [[l elem] (:labels m)
            :let [want (spec-value s row elem)
                  got (labels-named r l)]
            :when (if (= :gherkin-tags elem)
                    (not (every? (set got) (:tags s)))
                    (not= [want] (if (seq got) [(first got)] [nil])))]
        (differs :labels (str "the label " l " is " (pr-str got) " where the scenario's " (name elem) " is " (pr-str want))
                 (str "\"" l "\"")))
      (for [[_ {:keys [type url]}] (:links m)
            n (:needs s)
            :let [u (link-url url needs n)]
            :when (not (some #(and (= type (get % "type" type)) (= u (get % "url"))) (get r "links")))]
        (differs :links (str "no " type " link to " n " at " u) "\"links\""))
      (for [a (get-in m [:evidence (:level s)])
            :when (not (some #(= a (get % "name")) (attachments r)))]
        (f "\"attachments\"" "evidence-missing" (str a ": the result carries no attachment " a ", which :evidence requires of a " (name (:level s)) " scenario")))))))

;; ---------------------------------------------------------------------------
;; Judging a run's results

(defn- row-of [s params]
  (let [pm (into {} (map (juxt #(get % "name") #(get % "value")) params))]
    (first (for [ex (:examples s) row (:rows ex)
                 :when (= (zipmap (:headings ex) row) (select-keys pm (:headings ex)))]
             {:headings (:headings ex) :row row}))))

(defn judge
  "Holds a run's results to the scenarios required of it and the mapping:
  {:findings :named} (:named, the required scenarios results name)."
  [root dir scenarios required m needs]
  (let [{rs :results bad :unreadable} (results/read-dir root dir)
        by-id (group-by :id (filter :id scenarios))
        req (filter required scenarios)
        texts (memoize (fn [file] (slurp (fs/file root file))))
        findings
        (concat
         (results/unreadable-findings bad)
         (for [r rs
               :let [s (first (by-id (:spec r)))]
               :when (and s (required s))
               :let [row (when (:outline? s) (row-of s (:parameters r)))]
               :when (or (not (:outline? s)) row)
               x (mapping-findings m s row (:json r) (:file r) (texts (:file r)) (needs (:spec s)))]
           x)
         (for [r rs
               :let [s (first (by-id (:spec r)))]
               :when (and s (required s) (not= "passed" (:status r)))]
           (cmd/finding (:file r) nil "result-not-passed" (str (:spec r) " is " (:status r) ": every result naming a scenario passes")))
         (for [s req
               :let [mine (filter #(= (:id s) (:spec %)) rs)]
               missing (if (:outline? s)
                         (for [ex (:examples s) row (:rows ex)
                               :when (not-any? #(= {:headings (:headings ex) :row row} (row-of s (:parameters %))) mine)]
                           (str "its row " (str/join " | " row) " is named by no result"))
                         (when (empty? mine) ["it is named by no result"]))]
           (cmd/finding (:file s) (:line s) "scenario-without-result" (str (:id s) ": " missing))))]
    {:findings (vec (distinct findings))
     :named (count (filter (fn [s] (some #(= (:id s) (:spec %)) rs)) req))}))

;; ---------------------------------------------------------------------------
;; The checks

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn- one-line [s] (cmd/one-line (str/trim (str s))))

(defn- under-impl? [config s]
  (let [s (str/replace (str s) #"^\./" "")]
    (some #(let [p (str/replace (str %) #"/+$" "")] (or (= s p) (str/starts-with? s (str p "/")))) (:implementation config))))

(defn check-name [c] (or (:name c) (name (:kind c))))

(defn validate-checks!
  "Refuses, before anything runs, a :verify verify cannot trust."
  [config]
  (let [checks (:verify config)]
    (when (nil? checks) (cmd/cannot-run! "the configuration declares no :verify: a project is verified by its tests first"))
    (when-not (and (sequential? checks) (every? map? checks)) (cmd/cannot-run! ":verify is a vector of checks"))
    (doseq [c checks :when (not (#{:tests :serves :runs :plugin} (:kind c)))]
      (cmd/cannot-run! (str (:kind c) " is not a kind of check: the kinds are :tests, :serves, :runs and :plugin")))
    (when-not (= :tests (:kind (first checks)))
      (cmd/cannot-run! "the configuration's :verify declares no :tests check first: a project is verified by its tests first"))
    (doseq [c checks]
      (case (:kind c)
        :tests (when-not (and (sequential? (:command c)) (seq (:command c)))
                 (cmd/cannot-run! "the tests check names no command"))
        :runs (when-not (and (sequential? (:command c)) (seq (:command c)) (number? (get-in c [:budget :seconds])))
                (cmd/cannot-run! (str "the :runs check " (check-name c) " names no command or no budget {:seconds S}")))
        :serves (do (when-not (and (seq (:start c)) (integer? (:port c)) (number? (get-in c [:budget :seconds]))
                                   (pos-int? (get-in c [:budget :polls])) (seq (:probe c)))
                      (cmd/cannot-run! (str "the :serves check " (check-name c) " needs :start, :port, :budget {:seconds S :polls K} and :probe")))
                    (when (some #(under-impl? config %) (:probe c))
                      (cmd/cannot-run! (str "the :serves check " (check-name c) " cannot be trusted: the probe is in the implementation the builder writes ("
                                            (pr-str (:probe c)) "): a probe lives in a kept path"))))
        :plugin (when-not (some #(and (= (:name c) (:name %)) (= :Check (:answers %))) (:plugins config))
                  (cmd/cannot-run! (str "the :plugin check " (:name c) " names no plugin answering :Check in :plugins")))))))

(defn- not-found! [c command]
  (cmd/cannot-run! (str "the check " (check-name c) " cannot run: its command " (first command) " was not found")))

(defn- tests-check [{:keys [root checkout results-rel scenarios required m needs]} c]
  (let [abs (fs/path root results-rel)
        log (str (fs/parent results-rel) "/test.log")
        _ (do (fs/delete-tree abs) (fs/create-dirs abs))
        r (cmd/run-process (:command c) {:dir checkout :env {"RESULTS_DIR" (str abs)}})
        base {:name (check-name c) :kind :tests}]
    (when (:not-found r) (not-found! c (:command c)))
    (spit (fs/file root log) (str (:out r) (:err r)))
    (let [secs (round2 (:seconds r))]
      (cond
        (not= 0 (:exit r))
        (let [why (str "the test command failed with the exit " (:exit r) "; its output is in " log)]
          {:check (assoc base :outcome :failed :seconds secs :reason why)
           :findings [(cmd/finding log nil "tests-failed" why)]})
        (empty? (fs/glob abs "*-result.json"))
        (let [why (str "the test command exited 0 and wrote no result into " results-rel ": a green exit with no results is not a run")]
          {:check (assoc base :outcome :failed :seconds secs :reason why)
           :findings [(cmd/finding results-rel nil "tests-no-results" why)]})
        :else
        (let [{:keys [findings named]} (judge root results-rel scenarios required m needs)]
          (if (seq findings)
            {:check (assoc base :outcome :failed :seconds secs
                           :reason (str (count findings) " findings about the results in " results-rel))
             :findings findings}
            {:check (assoc base :outcome :passed :seconds secs :scenarios named)}))))))

(defn- runs-check [{:keys [checkout]} c]
  (let [budget (get-in c [:budget :seconds])
        r (cmd/run-process (:command c) {:dir checkout :seconds budget})
        base {:name (check-name c) :kind :runs}
        n (check-name c)]
    (when (:not-found r) (not-found! c (:command c)))
    (let [secs (round2 (:seconds r))
          {:keys [exit stdout-starts]} (:expect c)
          first-line (first (str/split-lines (str (:out r))))
          why (cond
                (:timed-out r) (str n ": it gave no answer within its budget of " budget " seconds, and was stopped")
                (and (some? exit) (not= exit (:exit r))) (str n ": it answered with the exit " (:exit r) " where " exit " was expected")
                (and stdout-starts (not (str/starts-with? (str (:out r)) stdout-starts)))
                (str n ": its standard output starts " (pr-str (or first-line "")) " where " (pr-str stdout-starts) " was expected"))]
      (if why
        {:check (assoc base :outcome :failed :seconds secs :budget budget :reason why)
         :findings [(cmd/finding nil nil "check-failed" why)]}
        {:check (assoc base :outcome :passed :seconds secs :budget budget :margin (round2 (max 0 (- budget secs))))}))))

(defn- held? [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
         true)
       (catch Exception _ false)))

(defn- answers?
  "Whether GET / on the port gets any HTTP response within ms."
  [port ms]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int (max 50 ms)))
         (.setSoTimeout s (int (max 50 ms)))
         (let [out (.getOutputStream s)]
           (.write out (.getBytes (str "GET / HTTP/1.0\r\nHost: 127.0.0.1:" port "\r\n\r\n")))
           (.flush out)
           (let [buf (byte-array 5) n (.read (.getInputStream s) buf)]
             (and (= 5 n) (= "HTTP/" (String. buf "US-ASCII"))))))
       (catch Exception _ false)))

(defn- fill [words k v] (mapv #(str/replace (str %) k v) words))

(defn- serves-check [{:keys [checkout]} c]
  (let [n (check-name c)
        port (:port c)
        {:keys [seconds polls]} (:budget c)
        probe-seconds (:probe-seconds c 30)
        base {:name n :kind :serves :budget seconds :polls polls :probe-seconds probe-seconds}
        start (System/nanoTime)
        log (cmd/temp-file ".log")
        app (try (p/process (fill (:start c) "{port}" (str port))
                            {:dir (str checkout) :out (fs/file log) :err (fs/file log) :in ""})
                 (catch java.io.IOException _ (not-found! c (:start c))))
        interval (/ (* 1000.0 seconds) polls)
        elapsed #(/ (- (System/nanoTime) start) 1e9)]
    (try
      (let [answered (loop [i 1]
                       (when (<= i polls)
                         (let [due (+ (/ (- (System/nanoTime) start) 1e6) 0)
                               wait (- (* i interval) due)]
                           (when (pos? wait) (Thread/sleep (long wait)))
                           (if (answers? port (* 0.8 interval)) i (recur (inc i))))))]
        (if-not answered
          (let [why (str n ": no answer came within " seconds " seconds (" polls " polls of GET / on port " port ")")]
            {:check (assoc base :outcome :failed :seconds (round2 (elapsed)) :reason why)
             :findings [(cmd/finding nil nil "check-failed" why)]})
          (let [url (str "http://127.0.0.1:" port "/")
                pr (cmd/run-process (fill (:probe c) "{url}" url) {:dir checkout :seconds probe-seconds})
                said (one-line (str (:out pr) " " (:err pr)))
                common (assoc base :answered-on-poll answered :margin (- polls answered))]
            (when (:not-found pr) (not-found! c (:probe c)))
            (cond
              (:timed-out pr)
              (let [why (str n ": the probe timed out after " probe-seconds " seconds and was stopped" (when (seq said) (str "; it printed: " said)))]
                {:check (assoc common :outcome :failed :seconds (round2 (elapsed)) :reason why)
                 :findings [(cmd/finding nil nil "check-failed" why)]})
              (not= 0 (:exit pr))
              (let [why (str n ": the app answered, and its probe exited " (:exit pr) ", so it is not this application" (when (seq said) (str ": " said)))]
                {:check (assoc common :outcome :failed :seconds (round2 (elapsed)) :reason why)
                 :findings [(cmd/finding nil nil "check-failed" why)]})
              :else
              {:check (assoc common :outcome :passed :seconds (round2 (elapsed)))}))))
      (finally
        (cmd/kill-tree! (:proc app))
        (fs/delete-if-exists log)))))

(defn- plugin-check [{:keys [root checkout config cycle-id cut-tag checkout-rel results-rel scenarios required m needs]} c]
  (let [plugin (first (filter #(and (= (:name c) (:name %)) (= :Check (:answers %)))
                              ((requiring-resolve 'rdm-spec.kernel.plugins/declared) config)))
        rel (str results-rel "/" (:name c))
        abs (fs/path root rel)
        _ (do (fs/delete-tree abs) (fs/create-dirs abs))
        start (System/nanoTime)
        r ((requiring-resolve 'rdm-spec.kernel.plugins/ask) plugin (str checkout)
           {:request :Check :cycle cycle-id :cut cut-tag :checkout checkout-rel :results rel})
        secs (round2 (/ (- (System/nanoTime) start) 1e9))
        base {:name (:name c) :kind :plugin}]
    (case (:outcome r)
      :refused {:check (assoc base :outcome :refused :seconds secs :reason (:why r)) :refused (:why r)}
      :failed {:check (assoc base :outcome :failed :seconds secs :reason (str "the plugin " (:name c) " found what it judges"))
               :findings (if (seq (:findings r)) (:findings r)
                             [(cmd/finding nil nil "check-failed" (str (:name c) ": the plugin exited 1"))])
               :warnings (:warnings r)}
      (if (seq (fs/glob abs "*-result.json"))
        (let [{:keys [findings]} (judge root rel scenarios required m needs)]
          (if (seq findings)
            {:check (assoc base :outcome :failed :seconds secs :reason (str (count findings) " findings about the results in " rel))
             :findings findings :warnings (:warnings r)}
            {:check (assoc base :outcome :passed :seconds secs) :warnings (:warnings r)}))
        {:check (assoc base :outcome :passed :seconds secs) :warnings (:warnings r)}))))

;; ---------------------------------------------------------------------------
;; The room and the checkout

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn room-files
  "The room's implementation as git would commit it: {:files [path] :tree hash}."
  [room impl]
  (let [g (cmd/temp-dir "rdm-spec-room-")]
    (try
      (cmd/git! (str g) "init" "-q")
      (let [git (fn [& args] (apply cmd/git! (str room) "--git-dir" (str (fs/path g ".git")) "--work-tree" (str room) args))
            existing (filter #(fs/exists? (fs/path room %) {:nofollow-links true}) impl)
            files (if (seq existing)
                    (vec (sort (remove str/blank? (str/split-lines (apply git "ls-files" "-o" "--exclude-standard" "--" existing)))))
                    [])]
        (when (seq files) (apply git "add" "-f" "--" files))
        {:files files :tree (if (seq files) (git "write-tree") empty-tree)})
      (finally (fs/delete-tree g)))))

(defn- build-checkout!
  "A fresh export of the cut at checkout, its implementation replaced by the room's."
  [root cut-tag checkout config room files]
  (fs/delete-tree checkout)
  (fs/create-dirs checkout)
  (let [tar (cmd/temp-file ".tar")]
    (try
      (cmd/git! root "archive" "--format=tar" "-o" (str tar) cut-tag)
      (let [r (cmd/sh {:dir (str checkout)} "tar" "-xf" (str tar))]
        (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "the checkout could not be written: " (:err r)))))
      (finally (fs/delete-if-exists tar))))
  (doseq [p (:implementation config)] (fs/delete-tree (fs/path checkout p)))
  (doseq [f files]
    (let [src (fs/path room f) dst (fs/path checkout f)]
      (fs/create-dirs (fs/parent dst))
      (if (fs/sym-link? src)
        (fs/create-sym-link dst (fs/read-link src))
        (fs/copy src dst {:copy-attributes true :replace-existing true})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- in-scope-spec? [scope spec-name]
  (let [specs (keep :spec (if (sequential? scope) scope [scope]))]
    (or (empty? specs) (some #{spec-name} specs))))

(defn- write-edn! [f x]
  (fs/create-dirs (fs/parent f))
  (spit (fs/file f) (binding [*print-namespace-maps* false] (with-out-str (pp/pprint x)))))

(defn stage [{:keys [root config args] :as ctx}]
  (let [[room-arg cycle-id] args
        cycles (:cycles-dir config "dhf/cycles")
        cycle-dir (str cycles "/" cycle-id)
        cycle-file (str cycle-dir "/cycle.edn")
        cycle (or (try (cmd/read-edn (cmd/read-at root nil cycle-file)) (catch Exception _ nil))
                  (cmd/cannot-run! (str "there is no readable " cycle-file ": a cycle is declared before it is verified")))
        cut-tag (or (:cut cycle) (cmd/cannot-run! (str cycle-file " names no :cut")))
        _ (when-not (cut/commit-of root (str "refs/tags/" cut-tag))
            (cmd/cannot-run! (str "the cut " cut-tag " that " cycle-file " names is no tag of this repository")))
        cfg (or (try (cmd/read-edn (cmd/read-at root cut-tag "dhf/harness/config.edn")) (catch Exception _ nil))
                (cmd/cannot-run! (str "the cut " cut-tag " holds no readable dhf/harness/config.edn")))
        _ (validate-checks! cfg)
        m (mapping-of cfg)
        _ (when-let [why (mapping-problem m)] (cmd/cannot-run! (str "the configuration's :allure cannot be read: " why)))
        room (fs/absolutize (fs/path root room-arg))
        _ (when-not (fs/directory? room) (cmd/cannot-run! (str "the room " room-arg " does not exist")))
        gates ((requiring-resolve 'rdm-spec.kernel.plugins/gates) (assoc ctx :config cfg)
               {:task :verify :cut cut-tag :cycle cycle-id})]
    (if (seq (:findings gates))
      gates
      (let [checkout-rel (str cycle-dir "/build")
            checkout (fs/path root checkout-rel)
            results-rel (str cycle-dir "/results")
            ratified (fs/path root cycle-dir "ratified.edn")
            {:keys [files tree]} (room-files room (:implementation cfg))
            specs (filter #(in-scope-spec? (:scope cycle) (:name %)) (cmd/spec-configs cfg))
            spec (for [sc specs] (parser/read-spec root cut-tag sc))
            scenarios (vec (mapcat :scenarios spec))
            needs (into {} (for [sc (cmd/spec-configs cfg)] [(:name sc) (:needs sc "dhf/spec/needs.edn")]))
            checks (:verify cfg)
            not-run (fn [cs] (mapv (fn [c] {:name (check-name c) :kind (:kind c) :outcome :not-run}) cs))
            unstamped (for [s scenarios :when (empty? (:ids s))]
                        (cmd/finding (:file s) (:line s) "scenario-unstamped"
                                     (str "the scenario \"" (:title s) "\" at the cut " cut-tag " has no id: a cut holding an unstamped scenario is not verified")))]
        (if (seq unstamped)
          (do (write-edn! ratified {:cut cut-tag :room-tree tree :outcome :refused
                                    :reason "the cut holds an unstamped scenario" :checks (not-run checks)})
              {:findings (vec unstamped)
               :result {:verify {:cycle cycle-id :cut cut-tag :outcome :refused :checks (not-run checks)}}})
          (let [_ (build-checkout! root cut-tag checkout cfg room files)
                env {:root root :checkout checkout :checkout-rel checkout-rel :config cfg :cycle-id cycle-id :cut-tag cut-tag
                     :results-rel results-rel :scenarios scenarios
                     :required #(and (:id %) (not (:wip? %))) :m m :needs needs}
                run (loop [cs checks done [] findings [] warnings []]
                      (if-let [c (first cs)]
                        (let [r (case (:kind c)
                                  :tests (tests-check env c)
                                  :runs (runs-check env c)
                                  :serves (do (when (held? (:port c))
                                                (cmd/cannot-run! (str "the port " (:port c) " of the :serves check " (check-name c)
                                                                      " is already held by another process: no app was started")))
                                              (serves-check env c))
                                  :plugin (plugin-check env c))
                              done (conj done (:check r))
                              findings (into findings (:findings r))
                              warnings (into warnings (:warnings r))]
                          (cond
                            (:refused r) {:checks (into done (not-run (rest cs))) :findings findings :warnings warnings :refused (:refused r)}
                            (not= :passed (:outcome (:check r))) {:checks (into done (not-run (rest cs))) :findings findings :warnings warnings}
                            :else (recur (rest cs) done findings warnings)))
                        {:checks done :findings findings :warnings warnings}))
                passed? (and (not (:refused run)) (every? #(= :passed (:outcome %)) (:checks run)))
                failed (first (remove #(= :passed (:outcome %)) (:checks run)))]
            (write-edn! ratified (if passed?
                                   {:cut cut-tag :room-tree tree :outcome :passed :checkout checkout-rel :checks (:checks run)}
                                   {:cut cut-tag :room-tree tree :outcome :refused :reason (:reason failed)
                                    :checkout checkout-rel :checks (:checks run)}))
            (when (:refused run) (cmd/cannot-run! (:refused run)))
            (when passed?
              (bus/publish! :verification/verify :CycleVerified
                            {:cycle cycle-id :cut cut-tag :checkout checkout-rel :checks (:checks run)}))
            {:result {:verify {:cycle cycle-id :cut cut-tag :outcome (if passed? :passed :refused) :checks (:checks run)}}
             :findings (:findings run)
             :warnings (:warnings run)}))))))
