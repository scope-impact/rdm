(ns rdm-spec.kernel.project
  "Kernel: the project a task runs in. Its root and environment, its
  configuration, the layout of its spec, its model read as plain data, the
  revisions it is read at, and how a task reports. Shared by every bounded
  context; belongs to none."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ the run

(def ^:dynamic *root*
  "The project's root, an absolute path: the directory the task runs in."
  nil)

(def ^:dynamic *env*
  "The environment of the run, passed whole to every process it starts."
  nil)

(defn root [] (or *root* (str (fs/cwd))))

(def ^:dynamic *cache*
  "What one run has already read, so its stages read each thing once."
  nil)

(defn cached
  "The value of F for KEY, computed once per run."
  [key f]
  (if *cache*
    (if (contains? @*cache* key)
      (get @*cache* key)
      (let [v (f)] (swap! *cache* assoc key v) v))
    (f)))

(defn env
  ([] (or *env* (into {} (System/getenv))))
  ([k] (get (env) k)))

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2)."
  [msg]
  (throw (ex-info (str msg) {::cannot-run true})))

(defn cannot-run? [e] (boolean (::cannot-run (ex-data e))))

(defn one-line [s] (str/trim (str/replace (str s) #"\s*[\r\n]+\s*" " ")))

(defn abs
  "An absolute, normalized path: relative paths are read from the project's root."
  ([p] (abs (root) p))
  ([base p]
   (let [p (str p)]
     (str (fs/normalize (if (fs/absolute? p) (fs/path p) (fs/path (str base) p)))))))

(defn rel
  "PATH relative to BASE (the project's root by default), with / separators."
  ([p] (rel (root) p))
  ([base p] (str/replace (str (fs/relativize (fs/normalize (fs/absolutize (str base)))
                                             (fs/normalize (fs/absolutize (str p)))))
                         "\\" "/")))

(defn tmp-base
  "The temporary directory: TMPDIR, as every tool the run starts reads it."
  []
  (let [t (env "TMPDIR")]
    (if (and t (not (str/blank? t))) t (System/getProperty "java.io.tmpdir"))))

(defn temp-dir! [prefix]
  (fs/create-dirs (tmp-base))
  (str (fs/create-temp-dir {:dir (tmp-base) :prefix prefix})))

(defmacro with-temp-dir [[sym prefix] & body]
  `(let [~sym (temp-dir! ~prefix)]
     (try ~@body (finally (fs/delete-tree ~sym)))))

;; ------------------------------------------------------------------ processes

(defn sh
  "Runs ARGV in DIR (the root by default) with the run's environment; returns
  {:exit :out :err}, or {:exit nil :error why} when it cannot be started."
  [argv & [{:keys [dir in env extra-env]}]]
  (try
    (let [r @(p/process (vec (map str argv))
                        {:dir (str (or dir (root)))
                         :env (merge (or env (rdm-spec.kernel.project/env)) extra-env)
                         :in (or in "")
                         :out :string :err :string})]
      {:exit (:exit r) :out (:out r) :err (:err r)})
    (catch java.io.IOException e
      {:exit nil :error (one-line (ex-message e))})))

(defn git
  "Runs git in DIR with hooks and signing off; returns {:exit :out :err}."
  [dir & args]
  (sh (concat ["git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"] args)
      {:dir dir}))

(defn git!
  "As git, stopping the task (cannot run) when git fails."
  [dir & args]
  (let [r (apply git dir args)]
    (if (= 0 (:exit r))
      (:out r)
      (cannot-run! (str "git " (str/join " " args) ": " (one-line (or (:error r) (:err r))))))))

(defn git-repo? [dir] (= 0 (:exit (git dir "rev-parse" "--git-dir"))))

(defn git-prefix
  "The project's folder within its repository (\"\" at the repository's root, else \"wallet/\")."
  []
  (str/trim (git! (root) "rev-parse" "--show-prefix")))

(defn toplevel
  "The root of the project's repository."
  []
  (str/trim (git! (root) "rev-parse" "--show-toplevel")))

(defn git-prefix-or-blank
  "The project's folder within its repository, or \"\" outside a repository."
  []
  (let [r (git (root) "rev-parse" "--show-prefix")]
    (if (= 0 (:exit r)) (str/trim (:out r)) "")))

(defn resolve-commit [rev]
  (let [r (git (root) "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (= 0 (:exit r)) (str/trim (:out r)))))

(defn tree-ish
  "The tree-ish naming the project's folder at REV."
  [rev]
  (let [prefix (str/replace (git-prefix) #"/$" "")]
    (str rev ":" prefix)))

(defn export!
  "Writes the project's folder as committed at REV into DEST: its files, and no history."
  [rev dest]
  (fs/create-dirs dest)
  (let [tar (str (fs/path (temp-dir! "rdm-export-") "export.tar"))]
    (try
      (git! (toplevel) "archive" "--format=tar" "-o" tar (tree-ish rev))
      (let [r (sh ["tar" "-x" "-f" tar "-C" (str dest)])]
        (when-not (= 0 (:exit r)) (cannot-run! (str "tar: " (one-line (or (:error r) (:err r)))))))
      (finally (fs/delete-tree (fs/parent tar)))))
  dest)

(defn with-revision
  "Calls F with the directory holding the project as it is at REV: the root
  itself for the working tree (REV nil), else an export of the folder at REV,
  removed afterwards."
  [rev f]
  (if (or (nil? rev) (= :working-tree rev))
    (f (root))
    (let [d (temp-dir! "rdm-rev-")]
      (try (export! rev d) (f d) (finally (fs/delete-tree d))))))

;; ------------------------------------------------------------------ files

(defn file-in [tree p] (abs tree p))

(defn read-text [tree p]
  (let [f (file-in tree p)] (when (fs/regular-file? f) (slurp f))))

(defn edn-error-line
  "Where an EDN text stops being EDN: {:line :message}, or nil when it reads."
  [text]
  (let [opener {\) \( \] \[ \} \{}
        n (count text)]
    (loop [i 0 line 1 stack []]
      (if (>= i n)
        (when (seq stack)
          (let [[c l] (peek stack)] {:line l :message (str "the " c " opened on line " l " is never closed")}))
        (let [c (.charAt ^String text i)]
          (cond
            (= c \newline) (recur (inc i) (inc line) stack)
            (= c \;) (let [j (or (str/index-of text "\n" i) n)] (recur j line stack))
            (= c \\) (recur (+ i 2) line stack)
            (= c \") (let [[j l2] (loop [j (inc i) l line]
                                     (cond (>= j n) [j l]
                                           (= (.charAt ^String text j) \\) (recur (+ j 2) l)
                                           (= (.charAt ^String text j) \") [(inc j) l]
                                           (= (.charAt ^String text j) \newline) (recur (inc j) (inc l))
                                           :else (recur (inc j) l)))]
                       (if (>= j n)
                         {:line line :message "a string opened here is never closed"}
                         (recur j l2 stack)))
            (#{\( \[ \{} c) (recur (inc i) line (conj stack [c line]))
            (#{\) \] \}} c) (cond
                              (empty? stack) {:line line :message (str "the " c " on line " line " closes nothing")}
                              (not= (first (peek stack)) (opener c))
                              (let [[o l] (peek stack)]
                                {:line l :message (str "the " o " opened on line " l " is closed by " c " on line " line)})
                              :else (recur (inc i) line (pop stack)))
            :else (recur (inc i) line stack)))))))

(defn read-edn-text
  "Reads TEXT as one EDN value: {:value v} or {:error {:line :message}}."
  [text]
  (if-let [e (edn-error-line text)]
    {:error e}
    (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))]
      (try
        (let [v (edn/read {:eof ::eof :default (fn [tag v] v)} r)]
          (if (= v ::eof) {:error {:line 1 :message "it holds no value"}} {:value v}))
        (catch Exception e
          {:error {:line (max 1 (.getLineNumber r))
                   :message (one-line (str/replace (str (ex-message e)) #"^java\.lang\.RuntimeException: " ""))}})))))

(defn read-edn-file
  "Reads the EDN file P of TREE: {:value v}, {:missing true} or {:error {:line :message}}."
  [tree p]
  (if-let [t (read-text tree p)] (read-edn-text t) {:missing true}))

;; ------------------------------------------------------------------ configuration

(def config-file "dhf/harness/config.edn")

(def defaults
  {:id-prefix "spec"
   :spec-dir "dhf/spec"
   :views-dir "dhf/views"
   :raw-dir "dhf/raw"
   :wiki-dir "dhf/wiki"
   :steps-dir "dhf/steps"
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :implementation []
   :stack-words []
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}})

(defn with-defaults [cfg]
  (let [cfg (merge defaults cfg)
        sd (:spec-dir cfg)]
    (merge {:ledger (str sd "/ids.edn") :model (str sd "/model.edn")
            :events (str sd "/events.edn") :needs (str sd "/needs.edn")}
           cfg
           {:c4 (merge {:view-element-limit 20 :description-max-chars 45 :contexts-relate-by [:events]}
                       (:c4 cfg))})))

(defn read-config
  "The configuration of the project at TREE, with defaults; nil when it has none."
  [tree]
  (let [r (read-edn-file tree config-file)]
    (cond (:missing r) nil
          (:error r) nil
          (map? (:value r)) (with-defaults (:value r)))))

(defn config
  "The configuration of the project at TREE (the root by default); a missing
  or unreadable configuration stops the task (exit 2)."
  ([] (config (root)))
  ([tree]
   (let [r (read-edn-file tree config-file)]
     (cond
       (:missing r) (cannot-run! (str "no " config-file " here: rdm-spec runs in a project's root, the folder that holds its configuration"))
       (:error r) (cannot-run! (str config-file ":" (get-in r [:error :line]) ": the configuration is not readable EDN: " (get-in r [:error :message])))
       (not (map? (:value r))) (cannot-run! (str config-file ": the configuration is not a map"))
       :else (with-defaults (:value r))))))

;; ------------------------------------------------------------------ specs

(defn specs
  "The specs a configuration declares: one, from :spec-dir and the paths it
  names, or one per entry of :specs, each a folder of its own."
  [cfg]
  (if-let [ss (seq (:specs cfg))]
    (vec (for [{:keys [name dir id-prefix]} ss]
           {:name name :dir dir :id-prefix (or id-prefix (:id-prefix cfg))
            :ledger (str dir "/ids.edn") :model (str dir "/model.edn") :events (str dir "/events.edn")
            :needs (str dir "/needs.edn") :glossary (str dir "/glossary.md") :domain (str dir "/domain.edn")}))
    [{:name nil :dir (:spec-dir cfg) :id-prefix (:id-prefix cfg)
      :ledger (:ledger cfg) :model (:model cfg) :events (:events cfg) :needs (:needs cfg)
      :glossary (str (:spec-dir cfg) "/glossary.md") :domain (str (:spec-dir cfg) "/domain.edn")}]))

(defn validate-specs!
  "Refuses (exit 2) a :specs that cannot be read apart."
  [tree cfg]
  (when-let [ss (seq (:specs cfg))]
    (when-not (and (sequential? (:specs cfg)) (every? map? ss))
      (cannot-run! ":specs is a vector of {:name :dir :id-prefix}"))
    (doseq [[k why] [[:name "a spec's name is declared once"]
                     [:id-prefix "an id prefix is declared once, so no two scenarios share an id"]
                     [:dir "a folder holds one spec"]]]
      (let [vs (map k ss)
            dup (first (for [[v n] (frequencies vs) :when (> n 1)] v))]
        (when dup (cannot-run! (str ":specs declares " (pr-str dup) " twice: " why)))))
    (doseq [{:keys [name dir]} ss]
      (when-not (and (string? dir) (fs/directory? (abs tree dir)))
        (cannot-run! (str "the spec " (pr-str name) " has no folder " dir))))))

;; ------------------------------------------------------------------ the model, as data

(defn id-name [kw] (name kw))

(defn slug [kw] (str/replace (name kw) "-" "_"))

(defn read-model
  "The model of SPEC at TREE as data, or nil when it cannot be read."
  [tree spec]
  (let [r (read-edn-file tree (:model spec))]
    (when (map? (:value r)) (:value r))))

(defn contexts
  "Every bounded context of the model, in its order: {:id :name :description :components}."
  [model]
  (vec (for [c (:containers model) :when (map? c) ctx (:contexts c) :when (map? ctx)] ctx)))

(defn components
  "Every component of the model, contexts first in the model's order, then the
  kernel's: each with :context (its context's id, :kernel for the kernel's)
  and :context-name."
  [model]
  (vec (concat
        (for [ctx (contexts model) c (:components ctx) :when (map? c)]
          (assoc c :context (:id ctx) :context-name (or (:name ctx) (some-> (:id ctx) name))))
        (for [c (:kernel model) :when (map? c)]
          (assoc c :context :kernel :context-name "kernel")))))

(defn component [model id] (first (filter #(= id (:id %)) (components model))))

(defn slices? [cfg] (= :slices (:spec-layout cfg)))

(def slice-re #"^\d+-[^/]+$")

(defn slice-of
  "The slice a spec path sits in, under the slices layout, or nil."
  [spec-dir rel-path]
  (let [prefix (str spec-dir "/")]
    (when (str/starts-with? rel-path prefix)
      (let [parts (str/split (subs rel-path (count prefix)) #"/")]
        (when (and (= 2 (count parts)) (re-matches slice-re (first parts)))
          (first parts))))))

(defn owner
  "The component owning the spec file REL (a .feature or its .md prose) of
  SPEC: by the model's :spec path, or under the slices layout by the file's
  name, the name of the component's id. Nil when none does."
  [cfg spec model rel-path]
  (let [sd (:dir spec)
        ext (re-find #"\.(feature|md)$" rel-path)]
    (when ext
      (let [base (subs rel-path 0 (- (count rel-path) (count (first ext))))]
        (if (slices? cfg)
          (when (slice-of sd rel-path)
            (let [n (fs/file-name base)
                  cs (filter #(and (keyword? (:id %)) (= n (id-name (:id %)))) (components model))]
              (when (= 1 (count cs)) (first cs))))
          (first (filter #(and (string? (:spec %)) (= base (str sd "/" (:spec %)))) (components model))))))))

;; ------------------------------------------------------------------ reporting

(defn finding
  ([rule message] {:rule rule :message message})
  ([file line rule message] {:file file :line line :rule rule :message message})
  ([file rule message] {:file file :rule rule :message message}))

(defn warning [& args] (assoc (apply finding args) :warning true))

(defn raw [line] {:raw line})

(defn finding-line [{:keys [file line rule message raw warning]}]
  (or raw
      (str (when file (str file ":" (when line (str line ":")) " "))
           (when warning "warning: ")
           (when rule (str rule ": "))
           (one-line message))))

(defn report
  "A task's report: {:result :findings :warnings}."
  [result & [findings warnings]]
  {:result result :findings (vec findings) :warnings (vec warnings)})

;; ------------------------------------------------------------------ stack words

(defn stack-word-findings
  "Every line of the files under DIR naming a configured stack word."
  [tree dir words]
  (let [d (abs tree dir)]
    (when (and (seq words) (fs/directory? d))
      (for [f (sort (map str (fs/glob d "**")))
            :when (and (fs/regular-file? f) (re-find #"\.(feature|md|edn|txt|yaml|yml)$" f))
            :let [rel (rel tree f)]
            [i line] (map-indexed vector (str/split-lines (slurp f)))
            w words
            :when (re-find (re-pattern (str "(?<![A-Za-z0-9])" (java.util.regex.Pattern/quote w) "(?![A-Za-z0-9])")) line)]
        (finding rel (inc i) "stack-word" (str "names the stack word " w ": the stack lives in dhf/harness/stack.md, never here"))))))


;; ------------------------------------------------------------------ results directories

(defn results-dir
  "The results directory a task reads: --results, else RESULTS_DIR, else the
  configured one. {:dir as given :abs :explicit? :configured?}."
  [cfg opts]
  (let [given (get opts "--results")
        e (env "RESULTS_DIR")]
    (cond
      given {:dir given :abs (abs given) :explicit? true}
      (and e (not (str/blank? e))) {:dir e :abs (abs e) :explicit? true}
      :else {:dir (:results-dir cfg) :abs (abs (:results-dir cfg)) :configured? true})))
