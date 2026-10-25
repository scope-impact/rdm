(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  A fence fixture is a throwaway repository (steps.regeneration-support/
  cut-project!) whose commit tagged spec-3 holds a copy of the room's
  allow-list, files outside it and the room's implementation; an earlier
  commit held a previous build the cut deleted, and a later commit holds work
  after the cut. The context keeps :dir (the repository), :cut, :commit (the
  cut's commit) and :room, a path in a fresh directory that does not exist
  yet, for ROOM.

  What a scope must remove is worked out here from the configuration's
  :code-layout and the run's --context and --component arguments, so the
  Thens check the whole room, step definitions included."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- new-room [] (str (fs/file (s/tmp-dir) "room")))

(defn- fixture!
  "ctx with a cut project (`opts` as for cut-project!) and a room path."
  [ctx & [opts]]
  (needs-fence!)
  (let [p (rs/cut-project! opts)]
    (merge ctx p {:room (new-room)})))

(defn- cut-files [ctx]
  (or (:cut-files ctx) (rs/cut-files (:dir ctx) (:cut ctx))))

(defn- passed! [ctx]
  (let [r (run ctx)]
    (s/check! (zero? (:exit r)) (str "the fence did not pass\n" (s/show r)))
    (kc/stdout-edn r)
    r))

(defn- room-files [ctx] (rs/files-under (:room ctx)))

(defn- no-room! [ctx]
  (let [room (:room ctx)]
    (s/check! (or (not (fs/exists? room)) (empty? (fs/list-dir room)))
              (str "a refused fence left a room at " room))))

(defn- refused! [ctx code]
  (let [r (run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a fence that cannot run printed a result\n" (s/show r)))
    (no-room! ctx)
    r))

(defn- allowed [] (get-in (s/room-config) [:clean-room :allow]))

(defn- implementation [] (:implementation (s/room-config)))

(defn- implementation-file? [rel] (some #(rs/under? rel %) (implementation)))

;; ---------------------------------------------------------------------------
;; The scope a run asked for, and what the code layout says it removes

(defn- option-values
  "Every comma-separated value the run's args give the option `flag`."
  [args flag]
  (->> (partition 2 1 args)
       (keep (fn [[a b]] (when (= a flag) b)))
       (mapcat #(str/split % #","))
       (remove str/blank?)
       vec))

(defn- layout-prefixes
  "The path prefixes the room's :code-layout gives the contexts and
  components the run named."
  [args]
  (let [layout (:code-layout (s/room-config))
        u #(str/replace % "-" "_")]
    (vec (concat
          (for [c (option-values args "--context") e (:context layout)]
            (str/replace e "{context}" (u c)))
          (for [c (option-values args "--component")
                :let [[ctx comp] (str/split c #"/")]
                e (:component layout)]
            (-> e (str/replace "{context}" (u ctx)) (str/replace "{component}" (u comp))))))))

(defn- removed? [prefixes rel] (some #(str/starts-with? rel %) prefixes))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the commit (\S+) carries no spec-N tag"
  (fn [ctx name]
    (let [ctx (fixture! ctx)
          dir (:dir ctx)
          head (rs/git! dir "rev-parse" "HEAD")]
      ;; The work after the cut is a real commit with no tag; the name the
      ;; scenario uses is made to name it.
      (rs/git! dir "branch" name head)
      (s/check! (= head (rs/git! dir "rev-parse" (str name "^{commit}"))) (str name " does not name the commit"))
      (s/check! (not-any? #(re-matches #"spec-\d+" %) (rs/tags-at dir name)) (str name " carries a spec-N tag"))
      ctx)))

(defstep #"the configuration allows (.+)"
  (fn [ctx listed]
    (let [paths (set (str/split listed #",\s*|\s+and\s+"))]
      (if (str/includes? listed "the cut holds no ")
        (let [[_ path] (re-matches #"(\S+) and the cut holds no (\S+)" listed)
              ctx (fixture! ctx {:drop [path]})]
          (s/check! (some #{path} (allowed)) (str "the configuration does not allow " path))
          (s/check! (not-any? #(rs/under? % path) (keys (cut-files ctx))) (str "the cut holds " path))
          (assoc ctx :missing path))
        (do (s/check! (= paths (set (allowed)))
                      (str "the configuration allows " (allowed) ", not " paths))
            (fixture! ctx))))))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          files (cut-files ctx)]
      (s/check! (some #(rs/under? % "src") (keys files)) "the cut holds no implementation")
      (assoc ctx :cut-files files))))

(defstep #"the code layout places (?:context|component) code at (\S+) and its unit tests at (\S+)"
  (fn [ctx code tests]
    (let [as-layout #(-> % (str/replace "<context>" "{context}") (str/replace "<component>" "{component}"))
          layout (:code-layout (s/room-config))
          entries (set (concat (:context layout) (:component layout)))]
      (doseq [e [(as-layout code) (as-layout tests)]]
        (s/check! (contains? entries e) (str "the code layout holds no " e ": " (pr-str layout))))
      (let [ctx (fixture! ctx)]
        (assoc ctx :cut-files (cut-files ctx))))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (let [r (refused! ctx 2)]
      (s/check! (re-find #"cannot-run: .*regeneration reads only a cut" (:err r))
                (str "the fence does not say regeneration reads only a cut\n" (s/show r))))))

(defstep #"it exits 2 and names (dhf/\S+)"
  (fn [ctx what]
    (let [r (refused! ctx 2)]
      (s/check! (re-find (re-pattern (str "cannot-run: .*" (java.util.regex.Pattern/quote what))) (:err r))
                (str "the fence does not name " what "\n" (s/show r))))))

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx what]
    (let [r (refused! ctx 2)
          model (edn/read-string (slurp (str (fs/file s/room-root (:model (s/room-config))))))
          contexts (for [c (:containers model) x (:contexts c)] (name (:id x)))
          line (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
      (s/check! line (str "the fence says no cannot-run line\n" (s/show r)))
      (doseq [w (concat [what "kernel"] contexts)]
        (s/check! (re-find (re-pattern (str "\\b" w "\\b")) line)
                  (str "the refusal does not name " w "\n" (s/show r)))))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)
          commits (rs/lines (rs/git! room "rev-list" "--all"))]
      (s/check! (= 1 (count commits)) (str "the room holds " (count commits) " commits"))
      (s/check! (= commits [(rs/git! room "rev-parse" "HEAD")]) "the room's one commit is not HEAD")
      (s/check! (= [(first commits)] (str/split (rs/git! room "rev-list" "--parents" "-n" "1" "HEAD") #" "))
                "the room's commit has a parent")
      (s/check! (not (fs/exists? (fs/file room ".git/objects/info/alternates")))
                "the room borrows objects from another store")
      (s/check! (str/blank? (rs/git! room "status" "--porcelain" "--ignored")) "the room's working tree is not clean")
      ctx)))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (let [expected (into (sorted-map) (filter (fn [[k]] (some #(rs/under? k %) (allowed)))) (cut-files ctx))
          actual (room-files ctx)]
      (doseq [a (allowed)]
        (s/check! (some #(rs/under? % a) (keys actual)) (str "the room holds nothing of " a)))
      (s/check! (= (set (keys expected)) (set (keys actual)))
                (str "the room's files differ from the allowed paths at the cut: extra "
                     (vec (take 10 (remove (set (keys expected)) (keys actual))))
                     ", missing " (vec (take 10 (remove (set (keys actual)) (keys expected))))))
      (doseq [[k v] expected]
        (s/check! (= v (get actual k)) (str k " changed in the room")))
      ctx)))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (let [room (:room ctx)
          paths (remove str/blank? (keep #(second (str/split % #" " 2)) (rs/lines (rs/git! room "rev-list" "--objects" "--all"))))
          allowed-or-parent? (fn [p] (some #(or (rs/under? p %) (str/starts-with? % (str p "/"))) (allowed)))
          reachable (count (rs/lines (rs/git! room "rev-list" "--objects" "--all")))
          stored (count (rs/lines (rs/git! room "cat-file" "--batch-all-objects" "--batch-check")))]
      (doseq [p paths]
        (s/check! (allowed-or-parent? p) (str "the room holds an object of the path " p)))
      (s/check! (= reachable stored) (str "the room stores " stored " objects but reaches " reachable))
      (s/check! (str/blank? (:out (rs/git room "grep" "-l" "-F" "-e" "the previous build: never in a clean room" "HEAD")))
                "the previous build reached the room"))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (passed! ctx)
    (doseq [p (implementation)]
      (s/check! (not (fs/exists? (fs/file (:room ctx) p))) (str "the room holds " p)))
    (s/check! (not-any? implementation-file? (keys (room-files ctx))) "the room holds implementation files")
    (s/check! (= 1 (count (rs/lines (rs/git! (:room ctx) "rev-list" "--all")))) "the room is not one commit")))

(defn- none-under! [ctx prefixes]
  (passed! ctx)
  (let [cut (keys (cut-files ctx))
        room (keys (room-files ctx))]
    (doseq [p prefixes]
      (s/check! (some #(str/starts-with? % p) cut) (str "the cut holds nothing at " p ": the check would prove nothing"))
      (s/check! (not-any? #(str/starts-with? % p) room) (str "the room holds a file at " p)))))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b] (none-under! ctx [a b]) nil))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b] (none-under! ctx [a b]) nil))

(defstep #"ROOM holds no file of the bounded contexts (\S+) and (\S+) or of the component (\S+)"
  (fn [ctx a b comp]
    (let [layout (:code-layout (s/room-config))
          prefixes (layout-prefixes ["--context" (str a "," b) "--component" comp])]
      (s/check! (= (count prefixes) (+ (* 2 (count (:context layout))) (count (:component layout))))
                "the layout gives no prefixes")
      (passed! ctx)
      (let [cut (keys (cut-files ctx)) room (keys (room-files ctx))]
        (doseq [x [(str "src/rdm_spec/" a "/") (str "src/rdm_spec/" b "/")
                   (str "src/rdm_spec/" comp ".clj")]]
          (s/check! (some #(str/starts-with? % x) cut) (str "the cut holds nothing at " x)))
        (doseq [p prefixes]
          (s/check! (not-any? #(str/starts-with? % p) room) (str "the room holds a file at " p))))
      nil)))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [prefixes (layout-prefixes (:args (run ctx)))
          cut (cut-files ctx)
          expected (into (sorted-map) (filter (fn [[k]] (and (implementation-file? k) (not (removed? prefixes k))))) cut)
          actual (into (sorted-map) (filter (fn [[k]] (implementation-file? k))) (room-files ctx))]
      (s/check! (seq prefixes) "the run named no scope")
      (s/check! (seq expected) "the cut keeps no implementation: the check would prove nothing")
      (s/check! (= (set (keys expected)) (set (keys actual)))
                (str "the room's implementation differs from the cut's, less the scope: extra "
                     (vec (remove (set (keys expected)) (keys actual)))
                     ", missing " (vec (take 10 (remove (set (keys actual)) (keys expected))))))
      (doseq [[k v] expected]
        (s/check! (= v (get actual k)) (str k " changed in the room")))
      (s/check! (= (set (filter (fn [k] (some #(rs/under? k %) (allowed))) (keys (room-files ctx))))
                   (set (filter (fn [k] (some #(rs/under? k %) (allowed))) (keys cut))))
                "the room does not hold the allowed paths")
      (s/check! (= (count (room-files ctx))
                   (+ (count actual) (count (filter (fn [k] (some #(rs/under? k %) (allowed))) (keys cut)))))
                "the room holds a file outside the allow-list and the implementation")
      nil)))
