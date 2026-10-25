(ns steps.architecture-support
  "Fixtures for the architecture context's step definitions
  (architecture_model, architecture_lint, architecture_views): copies of the
  room's spec whose model a scenario changes, a small model of its own for
  the views, and the model hash computed here, independently of the
  implementation, as the glossary defines it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.support :as s]))

(def model-rel "dhf/spec/model.edn")
(def events-rel "dhf/spec/events.edn")

(def architecture-ns
  ['rdm-spec.architecture.model 'rdm-spec.architecture.lint 'rdm-spec.architecture.views])

(def check-ns
  "Every namespace `rdm-spec check` runs."
  ['rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
   'rdm-spec.architecture.lint 'rdm-spec.architecture.views])

(defn needs-architecture! [] (apply s/needs-built! architecture-ns))
(defn needs-check! [] (apply s/needs-built! (concat architecture-ns check-ns)))

;; ---------------------------------------------------------------------------
;; The model hash (glossary: Model hash), computed independently

(defn- printed [x]
  (binding [*print-namespace-maps* false *print-meta* false *print-length* nil *print-level* nil]
    (pr-str x)))

(defn- sorted-form [x]
  (cond
    (map? x) (let [es (sort-by (comp printed first) (map (fn [[k v]] [(sorted-form k) (sorted-form v)]) x))]
               (str "{" (str/join ", " (map (fn [[k v]] (str k " " v)) es)) "}"))
    (set? x) (str "#{" (str/join " " (sort (map sorted-form x))) "}")
    (vector? x) (str "[" (str/join " " (map sorted-form x)) "]")
    (seq? x) (str "(" (str/join " " (map sorted-form x)) ")")
    :else (printed x)))

(defn hash-of
  "The SHA-256, lowercase hex, of the model value printed back with every
  map's keys and every set's elements sorted by their printed form."
  [m]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes ^String (sorted-form m) "UTF-8")))))

;; ---------------------------------------------------------------------------
;; Projects

(defn read-model [dir] (edn/read-string (slurp (str (fs/file dir model-rel)))))

(defn write-model! [dir m] (s/write! dir model-rel (pr-str m)))

(defn update-model! [dir f] (write-model! dir (f (read-model dir))) dir)

(defn read-events [dir] (edn/read-string (slurp (str (fs/file dir events-rel)))))

(defn update-config! [dir f]
  (let [file (str (fs/file dir "dhf/harness/config.edn"))]
    (spit file (pr-str (f (edn/read-string (slurp file)))))))

(defn views! [dir]
  (let [r (s/rdm-spec! dir "views")]
    (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))
    r))

(defn room!
  "A copy of the room's allow-list, its views generated from its model."
  []
  (let [dir (s/copy-of-room!)]
    (views! dir)
    dir))

(defn ensure-room!
  "ctx with :dir, a copy of the room with fresh views when it has none."
  [ctx]
  (needs-check!)
  (if (:dir ctx) ctx (assoc ctx :dir (room!))))

(defn change-model!
  "ctx's project (a room copy when it has none), its model rewritten as (f model)."
  [ctx f]
  (let [ctx (ensure-room! ctx)]
    (update-model! (:dir ctx) f)
    ctx))

(defn update-in-seq
  "Updates the entries of the vector at `ks` in `m` for which `pred` holds."
  [m ks pred f]
  (update-in m ks (fn [xs] (mapv #(if (pred %) (f %) %) xs))))

(defn update-element
  "The model with the element `id` (anywhere) rewritten as (f element)."
  [m id f]
  (let [hit #(= id (:id %))]
    (-> m
        (update-in-seq [:people] hit f)
        (update-in-seq [:external] hit f)
        (update-in-seq [:kernel] hit f)
        (update :containers
                (fn [cs]
                  (mapv (fn [c]
                          (let [c (if (hit c) (f c) c)]
                            (if (:contexts c)
                              (update c :contexts
                                      (fn [ctxs]
                                        (mapv (fn [ctx]
                                                (let [ctx (if (hit ctx) (f ctx) ctx)]
                                                  (if (:components ctx)
                                                    (update-in-seq ctx [:components] hit f)
                                                    ctx)))
                                              ctxs)))
                              c)))
                        cs))))))

(defn add-component
  "The model with `comp` added to the bounded context `ctx-id`."
  [m ctx-id comp]
  (update-element m ctx-id #(update % :components (fnil conj []) comp)))

;; ---------------------------------------------------------------------------
;; What the run said

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn finding-lines [run]
  (vec (remove #(str/starts-with? % "event: ") (s/err-lines run))))

(defn line-with [run & parts]
  (first (filter (fn [l] (every? #(str/includes? l %) parts)) (finding-lines run))))

(defn fails-rule!
  "The run exited 1 and one finding line is under `rule` and names every one of `parts`."
  [ctx rule & parts]
  (let [r (the-run ctx)]
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (apply line-with r (str ": " rule ": ") parts)
              (str "no finding under " rule " names all of " (pr-str parts) "\n" (s/show r)))))

(defn warns-rule!
  "One warning line is under `rule` and names every one of `parts`."
  [ctx rule & parts]
  (let [r (the-run ctx)]
    (s/check! (apply line-with r (str "warning: " rule ": ") parts)
              (str "no warning under " rule " names all of " (pr-str parts) "\n" (s/show r)))))

(defn result [run] (try (edn/read-string (:out run)) (catch Exception _ nil)))
