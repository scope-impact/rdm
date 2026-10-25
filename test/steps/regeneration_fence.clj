(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  A fence fixture is a repository (steps.regeneration-support/cut-project!)
  whose commit tagged spec-3 holds a copy of the room's allow-list, files
  outside it, and the room's own implementation; an earlier commit held a
  previous build the cut deleted, and a later commit holds work after the
  cut. ROOM is a path in a fresh temporary directory that does not exist yet.

  What a scope removes is computed here from the configuration's
  :code-layout, independently of the fence: every implementation file
  starting with one of the scope's entries."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- room [ctx] (:room ctx))

(defn- fixture
  "The context with a fence fixture and a fresh ROOM."
  [ctx & [opts]]
  (needs-fence!)
  (let [p (rs/cut-project! opts)]
    (merge ctx p {:room (str (fs/file (s/tmp-dir) "room"))
                  :at-cut (rs/cut-files (:dir p) (:cut p))})))

(defn- passed! [ctx]
  (let [r (run ctx)]
    (s/check! (zero? (:exit r)) (str "the fence did not pass\n" (s/show r)))
    r))

(defn- room-files [ctx] (rs/files-under (room ctx)))

(defn- at-cut [ctx] (or (:at-cut ctx) (rs/cut-files (:dir ctx) (:cut ctx))))

(defn- allowed [] (get-in (s/room-config) [:clean-room :allow]))

(defn- allowed? [rel] (some #(rs/under? rel %) (allowed)))

(defn- implementation? [rel] (some #(rs/under? rel %) (:implementation (s/room-config))))

;; --- what a scope removes, from the code layout -----------------------------

(defn- fill [template ctx-name comp-name]
  (-> template
      (str/replace "{context}" (str/replace ctx-name "-" "_"))
      (str/replace "{component}" (str/replace (or comp-name "") "-" "_"))))

(defn- scope-prefixes
  "The path prefixes the run's --context and --component remove."
  [args]
  (let [layout (:code-layout (s/room-config))
        opt (fn [k] (some (fn [[a b]] (when (= a k) b)) (partition 2 1 args)))
        split #(if % (str/split % #",") [])]
    (vec (concat
          (for [c (split (opt "--context")) t (:context layout)] (fill t c nil))
          (for [cn (split (opt "--component"))
                :let [[c n] (str/split cn #"/")]
                t (:component layout)]
            (fill t c n))))))

(defn- removed? [prefixes rel] (some #(str/starts-with? rel %) prefixes))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the commit (\S+) carries no spec-N tag"
  (fn [ctx name]
    (let [ctx (fixture ctx)
          dir (:dir ctx)]
      ;; The commit after the cut, which no tag names, is reachable as `name`
      ;; (a branch), so the command line's literal stands for it.
      (rs/git! dir "branch" name "HEAD")
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (rs/tags-at dir name)))
                (str name " carries a spec-N tag"))
      ctx)))

(defstep #"the configuration allows (.+)"
  (fn [ctx paths]
    (let [want (set (map str/trim (str/split (str/replace paths #",? and " ", ") #",\s*")))
          cut-without (when-let [[_ p] (re-find #"^(\S+) and the cut holds no (\S+)$" paths)] p)]
      (if cut-without
        (do (s/check! (some #{cut-without} (allowed)) (str "the configuration does not allow " cut-without))
            (assoc (fixture ctx {:drop [cut-without]}) :missing cut-without))
        (do (s/check! (= want (set (allowed)))
                      (str "the configuration allows " (allowed) ", not " want))
            (fixture ctx))))))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx]
    (let [ctx (fixture ctx)]
      (s/check! (some implementation? (keys (at-cut ctx))) "the cut holds no implementation")
      ctx)))

(defstep #"the code layout places (?:context|component) code at (\S+) and its unit tests at (\S+)"
  (fn [ctx code tests]
    (let [layout (:code-layout (s/room-config))
          as-template #(-> % (str/replace "<context>" "{context}") (str/replace "<component>" "{component}"))
          entries (set (concat (:context layout) (:component layout)))]
      (doseq [t [code tests]]
        (s/check! (contains? entries (as-template t)) (str "the code layout holds no " (as-template t))))
      (fixture ctx))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (let [r (run ctx)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (re-find #"regeneration reads only a cut" (:err r)) (str "it does not say regeneration reads only a cut\n" (s/show r)))
      (s/check! (not (fs/exists? (room ctx))) "a refused fence made ROOM"))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (passed! ctx)
    (let [commits (rs/lines (rs/git! (room ctx) "rev-list" "--all"))
          parents (rs/git! (room ctx) "rev-list" "--parents" "-n" "1" "HEAD")]
      (s/check! (= 1 (count commits)) (str "ROOM holds " (count commits) " commits"))
      (s/check! (= [(first commits)] (str/split parents #" ")) "ROOM's commit has a parent")
      (s/check! (= "" (rs/git! (room ctx) "status" "--porcelain" "--untracked-files=all"))
                "ROOM's working tree differs from its commit"))))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (let [cut (at-cut ctx)
          want (into (sorted-map) (filter (comp allowed? key)) cut)
          got (room-files ctx)]
      (s/check! (seq want) "the cut holds no allowed file")
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "ROOM's files differ from the allowed paths at the cut: missing "
                     (vec (remove (set (keys got)) (keys want))) ", extra " (vec (remove (set (keys want)) (keys got)))))
      (doseq [[f t] want]
        (s/check! (= t (get got f)) (str f " changed in ROOM"))))))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (let [dir (room ctx)
          ;; every object in the store, reachable or not
          objects (rs/lines (rs/git! dir "cat-file" "--batch-all-objects" "--batch-check=%(objectname) %(objecttype)"))
          blobs (set (keep #(let [[o t] (str/split % #" ")] (when (= "blob" t) o)) objects))
          paths (rs/lines (rs/git! dir "rev-list" "--objects" "--all"))
          allowed-blobs (set (for [l (rs/lines (rs/git! (:dir ctx) "ls-tree" "-r" (:cut ctx)))
                                   :let [[meta rel] (str/split l #"\t" 2)]
                                   :when (allowed? rel)]
                               (nth (str/split meta #" ") 2)))
          secret (rs/git! (:dir ctx) "rev-parse" (str (:cut ctx) "~1:" rs/secret-path))]
      (doseq [l paths
              :let [[_ rel] (str/split l #" " 2)]
              :when (not (str/blank? rel))]
        (s/check! (or (allowed? rel) (some #(str/starts-with? % (str rel "/")) (allowed)))
                  (str "an object reachable in ROOM belongs to " rel)))
      (s/check! (every? allowed-blobs blobs)
                (str "ROOM holds blobs no allowed path holds at the cut: " (vec (remove allowed-blobs blobs))))
      (s/check! (not (contains? blobs secret)) "ROOM holds the previous build"))))

(defstep #"it exits 2 and names (dhf/\S+)"
  (fn [ctx p]
    (let [r (run ctx)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/includes? (:err r) p) (str "the error stream does not name " p "\n" (s/show r)))
      (s/check! (not (fs/exists? (room ctx))) "a refused fence made ROOM"))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (passed! ctx)
    (doseq [p (:implementation (s/room-config))]
      (s/check! (not (fs/exists? (fs/file (room ctx) p))) (str "ROOM holds " p)))
    (s/check! (not-any? implementation? (keys (room-files ctx))) "ROOM holds an implementation file")
    (s/check! (seq (room-files ctx)) "ROOM holds nothing at all")))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b]
    (passed! ctx)
    (let [files (keys (room-files ctx))]
      (doseq [p [a b]]
        (s/check! (not-any? #(str/starts-with? % p) files) (str "ROOM holds a file under " p))
        (s/check! (some #(str/starts-with? % p) (keys (at-cut ctx))) (str "the cut holds no file under " p))))))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (passed! ctx)
    (doseq [p [a b]]
      (s/check! (contains? (at-cut ctx) p) (str "the cut holds no " p))
      (s/check! (not (fs/exists? (fs/file (room ctx) p))) (str "ROOM holds " p)))))

(defstep #"ROOM holds no file of the bounded contexts (\S+) and (\S+) or of the component (\S+)"
  (fn [ctx c1 c2 cn]
    (passed! ctx)
    (let [files (keys (room-files ctx))
          [c n] (str/split cn #"/")
          own (fn [rel] (or (some #(str/starts-with? rel %)
                                  (for [x [c1 c2]] (str "src/rdm_spec/" x "/")))
                            (some #(str/starts-with? rel %)
                                  (for [x [c1 c2]] (str "test/rdm_spec/" x "/")))
                            (some #(str/starts-with? rel %) (for [x [c1 c2]] (str "test/steps/" x "_")))
                            (#{(str "src/rdm_spec/" c "/" n ".clj") (str "test/rdm_spec/" c "/" n "_test.clj")
                               (str "test/steps/" c "_" n ".clj")} rel)))]
      (s/check! (some own (keys (at-cut ctx))) "the cut holds no file of those scopes")
      (doseq [f files]
        (s/check! (not (own f)) (str "ROOM holds " f))))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [prefixes (scope-prefixes (:args (run ctx)))
          cut (at-cut ctx)
          want (into (sorted-map) (filter (fn [[f _]] (or (allowed? f)
                                                          (and (implementation? f) (not (removed? prefixes f))))))
                     cut)
          got (room-files ctx)]
      (s/check! (seq prefixes) "the run names no scope")
      (s/check! (some #(and (implementation? %) (not (removed? prefixes %))) (keys got)) "ROOM holds no implementation")
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "ROOM's files differ from what the scope keeps: missing "
                     (vec (remove (set (keys got)) (keys want))) ", extra " (vec (remove (set (keys want)) (keys got)))))
      (doseq [[f t] want]
        (s/check! (= t (get got f)) (str f " changed in ROOM"))))))

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx scope]
    (let [r (run ctx)
          model (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/model.edn"))))
          contexts (for [c (:containers model) x (:contexts c)] (name (:id x)))]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (doseq [w (concat [scope "kernel"] contexts)]
        (s/check! (str/includes? (:err r) w) (str "the error stream does not name " w "\n" (s/show r))))
      (s/check! (not (fs/exists? (room ctx))) "a refused fence made ROOM"))))
