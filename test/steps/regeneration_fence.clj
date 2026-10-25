(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  Fixture projects come from steps.regeneration-support/cut-project!: a git
  repository whose commit tagged spec-3 holds the room's allow-list, files
  outside it and (by default) the room's implementation; an earlier commit
  held a previous build, and a commit after the cut holds work the cut does
  not. What a room must hold is worked out here from the cut and the
  configuration, apart from the fence's own code."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence))

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- project [ctx opts]
  (needs-fence!)
  (let [p (rs/cut-project! opts)]
    (assoc ctx :dir (:dir p) :cut-commit (:commit p))))

(defn- room [ctx] (or (:room ctx) (s/fail! "no room was named")))

(defn- room-files [ctx]
  (let [r (room ctx)]
    (s/check! (fs/directory? r) (str "no room was built at " r (when (:run ctx) (str "\n" (s/show (:run ctx))))))
    (rs/files-under r)))

(defn- cut-files [ctx] (rs/cut-files (:dir ctx) "spec-3"))

(defn- implementation? [cfg rel] (some #(rs/under? rel %) (:implementation cfg)))

(defn- allowed? [cfg rel] (some #(rs/under? rel %) (get-in cfg [:clean-room :allow])))

(defn- option-list [args opt]
  (let [v (second (drop-while #(not= opt %) args))]
    (if v (vec (remove str/blank? (str/split v #","))) [])))

(defn- removed-prefixes
  "The implementation prefixes the run's --context and --component options
  remove, from the configuration's :code-layout."
  [cfg args]
  (let [layout (:code-layout cfg)]
    (vec (concat
          (for [c (option-list args "--context") e (:context layout)] (str/replace e "{context}" c))
          (for [cc (option-list args "--component")
                :let [[c n] (str/split cc #"/")]
                e (:component layout)]
            (-> e (str/replace "{context}" c) (str/replace "{component}" (str/replace n "-" "_"))))))))

(defn- blob-hash
  "The git object id of a blob holding `text`."
  [text]
  (let [bytes (.getBytes (str text) "UTF-8")
        md (java.security.MessageDigest/getInstance "SHA-1")]
    (.update md (.getBytes (str "blob " (count bytes) "\u0000") "UTF-8"))
    (.update md bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx]
    (let [ctx (project ctx {})]
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (rs/tags-at (:dir ctx) "HEAD")))
                "HEAD carries a spec-N tag")
      ctx)))

(defstep #"the configuration allows (.+), and the cut also holds dhf/harness/oracle/"
  (fn [ctx listed]
    (needs-fence!)
    (let [allow (vec (map str/trim (str/split (str/replace listed #",? and " ", ") #",")))
          cfg (s/room-config)]
      (s/check! (= (set allow) (set (get-in cfg [:clean-room :allow])))
                (str "the configuration allows " (pr-str (get-in cfg [:clean-room :allow])) ", not " (pr-str allow)))
      ;; the oracle's goldens are part of the cut and never reach a room
      (let [p (rs/cut-project! {})
            dir (:dir p)]
        (s/write! dir "dhf/harness/oracle/RECORDED.md" "# Recorded goldens\n")
        (rs/git! dir "add" "-A" "-f" "dhf/harness/oracle")
        (rs/git! dir "commit" "-q" "-m" "the oracle")
        (rs/git! dir "tag" "-f" "spec-3")
        (assoc ctx :dir dir)))))

(defstep #"the configuration allows dhf/wiki and the cut holds no dhf/wiki"
  (fn [ctx]
    (s/check! (some #{"dhf/wiki"} (get-in (s/room-config) [:clean-room :allow])) "the configuration does not allow dhf/wiki")
    (let [ctx (project ctx {:drop ["dhf/wiki"]})]
      (s/check! (not-any? #(rs/under? % "dhf/wiki") (keys (cut-files ctx))) "the cut holds dhf/wiki")
      ctx)))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx]
    (let [ctx (project ctx {})
          cfg (config-of (:dir ctx))]
      (s/check! (some #(implementation? cfg %) (keys (cut-files ctx))) "the cut holds no implementation")
      ctx)))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx]
    (let [layout (:code-layout (s/room-config))]
      (s/check! (every? (set (:context layout)) ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/"])
                (str "the code layout places contexts at " (pr-str (:context layout))))
      (project ctx {}))))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>\.clj and its unit tests at test/rdm_spec/<context>/<component>_test\.clj"
  (fn [ctx]
    (let [layout (:code-layout (s/room-config))]
      (s/check! (every? (set (:component layout)) ["src/rdm_spec/{context}/{component}.clj"
                                                    "test/rdm_spec/{context}/{component}_test.clj"])
                (str "the code layout places components at " (pr-str (:component layout))))
      (project ctx {}))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (kc/refused-naming! ctx "regeneration reads only a cut")
    (s/check! (not (fs/exists? (room ctx))) "a room was built")
    nil))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [r (room ctx)
          commits (rs/lines (rs/git! r "rev-list" "--all"))
          roots (rs/lines (rs/git! r "rev-list" "--max-parents=0" "--all"))]
      (s/check! (= 1 (count commits)) (str "the room holds " (count commits) " commits"))
      (s/check! (= commits roots) "the room's commit is not a root commit")
      nil)))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (let [cfg (config-of (:dir ctx))
          want (into (sorted-map) (filter #(allowed? cfg (key %)) (cut-files ctx)))
          got (room-files ctx)]
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "the room holds other files than the allowed ones: extra "
                     (pr-str (take 10 (remove (set (keys want)) (keys got))))
                     ", missing " (pr-str (take 10 (remove (set (keys got)) (keys want))))))
      (s/check! (= want got) "an allowed file differs from the cut")
      (s/check! (not-any? #(rs/under? % "dhf/harness/oracle") (keys got)) "the room holds dhf/harness/oracle")
      nil)))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (let [cfg (config-of (:dir ctx))
          r (room ctx)
          allow (get-in cfg [:clean-room :allow])
          parent? (fn [p] (some #(str/starts-with? % (str p "/")) allow))
          paths (keep #(second (re-matches #"\S+ (.+)" %)) (rs/lines (rs/git! r "rev-list" "--objects" "--all")))
          stray (remove #(or (allowed? cfg %) (parent? %)) paths)
          objects (set (map #(first (str/split % #" "))
                            (rs/lines (rs/git! r "cat-file" "--batch-all-objects" "--batch-check"))))]
      (s/check! (empty? stray) (str "objects reachable in the room belong to " (pr-str (take 10 stray))))
      (s/check! (seq objects) "the room holds no object")
      (s/check! (not (contains? objects (blob-hash rs/secret-text)))
                "the room holds the previous build's object")
      nil)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [cfg (config-of (:dir ctx))
          got (keys (room-files ctx))]
      (s/check! (not-any? #(implementation? cfg %) got)
                (str "the room holds implementation files: " (pr-str (take 10 (filter #(implementation? cfg %) got)))))
      nil)))

(defn- none-under! [ctx prefixes]
  (kc/exits! ctx 0)
  (let [got (keys (room-files ctx))
        hits (filter (fn [f] (some #(str/starts-with? f %) prefixes)) got)]
    (s/check! (empty? hits) (str "the room holds " (pr-str (take 10 hits))))
    nil))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b] (none-under! ctx [a b])))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b] (none-under! ctx [a b])))

(defstep #"ROOM holds no file of the bounded contexts (\S+) and (\S+) or of the component (\S+)"
  (fn [ctx a b component]
    (let [cfg (config-of (:dir ctx))]
      (none-under! ctx (removed-prefixes cfg ["--context" (str a "," b) "--component" component])))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [cfg (config-of (:dir ctx))
          prefixes (removed-prefixes cfg (:args (kc/the-run ctx)))
          _ (s/check! (seq prefixes) "the run names no scope")
          want (into (sorted-map) (filter (fn [[f _]] (and (implementation? cfg f)
                                                           (not-any? #(str/starts-with? f %) prefixes)))
                                          (cut-files ctx)))
          got (into (sorted-map) (filter #(implementation? cfg (key %)) (room-files ctx)))]
      (s/check! (seq want) "the cut holds no other implementation file")
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "the room's implementation differs: extra " (pr-str (take 10 (remove (set (keys want)) (keys got))))
                     ", missing " (pr-str (take 10 (remove (set (keys got)) (keys want))))))
      (s/check! (= want got) "an implementation file differs from the cut")
      nil)))

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx scope]
    (let [model (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/model.edn"))))
          contexts (for [c (:containers model) ctx (:contexts c)] (name (:id ctx)))]
      (apply kc/refused-naming! ctx scope "kernel" contexts)
      (s/check! (not (fs/exists? (room ctx))) "a room was built")
      nil)))
