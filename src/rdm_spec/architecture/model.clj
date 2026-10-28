(ns rdm-spec.architecture.model
  "Reads model.edn and the event catalogue, whole or not at all; finds each
  component's feature files from the model, never by guessing; and computes
  the model hash, so views go stale on content, never formatting
  (dhf/spec/04-model-the-architecture/model.feature, 13-slice-development/model.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

;; ------------------------------------------------------------------ reading

(defn- scan
  "Bracket structure of EDN text: nil when balanced, else {:line :message}."
  [text]
  (let [n (count text) closer {\( \) \[ \] \{ \}}]
    (loop [i 0 line 1 stack [] in-str false]
      (if (>= i n)
        (cond in-str {:line (:line (peek stack) line) :message "a string is never closed"}
              (seq stack) (let [[ch l] (peek stack)] {:line l :message (str "the \"" ch "\" opened on line " l " is never closed")})
              :else nil)
        (let [c (.charAt ^String text i)]
          (cond
            in-str (cond (= c \\) (recur (+ i 2) line stack true)
                         (= c \") (recur (inc i) line stack false)
                         (= c \newline) (recur (inc i) (inc line) stack true)
                         :else (recur (inc i) line stack true))
            (= c \newline) (recur (inc i) (inc line) stack false)
            (= c \") (recur (inc i) line stack true)
            (= c \;) (let [j (or (str/index-of text "\n" i) n)] (recur j line stack false))
            (= c \\) (recur (+ i 2) line stack false)
            (contains? closer c) (recur (inc i) line (conj stack [c line]) false)
            (#{\) \] \}} c) (if (and (seq stack) (= c (closer (first (peek stack)))))
                              (recur (inc i) line (pop stack) false)
                              {:line line :message (if (empty? stack)
                                                     (str "the \"" c "\" on line " line " closes nothing")
                                                     (str "the \"" c "\" on line " line " does not close the \"" (first (peek stack)) "\" opened on line " (second (peek stack))))})
            :else (recur (inc i) line stack false)))))))

(defn read-text
  "Reads a model's text: {:model m} or {:findings [finding]}."
  [file text]
  (if-let [{:keys [line message]} (scan text)]
    {:findings [(project/finding file line "model-unreadable" (str "the model is not EDN: " message))]}
    (try
      (let [rdr (java.io.PushbackReader. (java.io.StringReader. text))
            m (edn/read {:eof ::eof} rdr)
            more (edn/read {:eof ::eof} rdr)]
        (cond (= ::eof m) {:findings [(project/finding file 1 "model-unreadable" "the model is empty")]}
              (not= ::eof more) {:findings [(project/finding file (or (project/line-of text (pr-str more)) 1) "model-unreadable" "the model is more than one form")]}
              (not (map? m)) {:findings [(project/finding file 1 "model-unreadable" "the model is not a map")]}
              :else {:model m}))
      (catch Exception e
        {:findings [(project/finding file (or (some-> (re-find #"line (\d+)" (str (ex-message e))) second parse-long) 1)
                                     "model-unreadable" (str "the model is not EDN: " (ex-message e)))]}))))

(defn read-file [root file]
  (let [f (fs/path root file)]
    (if (fs/exists? f)
      (assoc (read-text file (slurp (str f))) :text (slurp (str f)))
      {:findings [(project/finding file "model-unreadable" "the model does not exist")] :text ""})))

;; ------------------------------------------------------------------ the hash

(defn canon
  "A form printed as EDN prints it, maps' entries and sets' elements sorted by their printed form."
  [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canon k) " " (canon v))) (sort-by (fn [[k _]] (canon k)) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map canon x))) "}")
        (vector? x) (str "[" (str/join " " (map canon x)) "]")
        (seq? x) (str "(" (str/join " " (map canon x)) ")")
        :else (project/edn-str x)))

(defn sha256 [^String s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest d (.getBytes s "UTF-8"))))))

(defn model-hash [m] (sha256 (canon m)))

;; ------------------------------------------------------------------ elements

(defn kernel-container [m]
  (or (some #(when (seq (:contexts %)) %) (:containers m)) (first (:containers m))))

(defn elements
  "Every element of the model: [{:id :kind :name :description ...}], in the model's order."
  [m]
  (vec (concat
        (when-let [s (:system m)] [(assoc s :el :system)])
        (map #(assoc % :el :person) (:people m))
        (map #(assoc % :el :external) (:external m))
        (mapcat (fn [c] (cons (assoc c :el :container)
                              (mapcat (fn [ctx] (cons (assoc ctx :el :context :container (:id c))
                                                      (map #(assoc % :el :component :context (:id ctx) :container (:id c)) (:components ctx))))
                                      (:contexts c))))
                (:containers m))
        (map #(assoc % :el :component :context :kernel :container (:id (kernel-container m))) (:kernel m)))))

(def kind-name {:system "the system" :person "the person" :external "the external system" :container "the container"
                :context "the bounded context" :component "the component" :relationship "the relationship" :view "the view"})

(defn describe [el] (str (kind-name (:el el)) " " (pr-str (:id el))))

;; ------------------------------------------------------------------ the shape

(def shape
  {:top #{:system :people :external :containers :kernel :relationships :views}
   :system #{:id :name :description}
   :person #{:id :name :description :actor}
   :external #{:id :name :description}
   :container #{:id :name :description :technology :kind :contexts}
   :context #{:id :name :description :components}
   :component #{:id :name :description :spec}
   :relationship #{:from :to :description :event}
   :view #{:key :level :description :context :steps}})

(defn- el-line [text id] (or (project/line-of text (str ":id " (pr-str id))) (project/line-of text (pr-str id)) 1))

(defn shape-findings
  "Keys the shape does not declare, ids used twice, and relationships or views naming nothing."
  [file text m]
  (let [els (elements m)
        f (fn [line rule msg] (project/finding file line rule msg))
        raw (fn [el] (apply dissoc el [:el :context :container]))
        key-findings
        (concat
         (for [k (keys m) :when (not ((:top shape) k))]
           (f (or (project/line-of text (str k)) 1) "model-key-unknown" (str "the model carries " k ", which the model's shape does not declare")))
         (for [el els
               :let [allowed (shape (:el el))
                     el-keys (keys (if (= :component (:el el)) (dissoc el :el :context :container) (raw el)))]
               k el-keys :when (not (allowed k))]
           (f (or (project/line-of text (str k) (el-line text (:id el))) 1) "model-key-unknown"
              (str (describe el) " carries " k ", which the model's shape does not declare")))
         (for [r (:relationships m) k (keys r) :when (not ((:relationship shape) k))]
           (f (or (project/line-of text (str k)) 1) "model-key-unknown" (str "a relationship carries " k ", which the model's shape does not declare")))
         (for [v (:views m) k (keys v) :when (not ((:view shape) k))]
           (f (or (project/line-of text (str k)) 1) "model-key-unknown" (str "the view " (:key v) " carries " k ", which the model's shape does not declare"))))
        dup-findings
        (for [[id group] (group-by :id els) :when (and id (> (count group) 1))]
          (f (el-line text id) "model-id-duplicate" (str (pr-str id) " is the id of " (str/join " and of " (map #(kind-name (:el %)) group))
                                                         ": an id is used once anywhere in the model")))
        ids (set (map :id els))
        rel-findings
        (for [r (:relationships m) end [:from :to] :let [id (get r end)] :when (not (ids id))]
          (f (or (project/line-of text (str ":to " (pr-str (:to r)))) (project/line-of text (pr-str id)) 1) "model-element-unknown"
             (str "the relationship " (pr-str (:from r)) " -> " (pr-str (:to r)) " names " (pr-str id) ", which is no element of the model")))
        view-findings
        (concat
         (for [v (:views m) :when (and (= :component (:level v)) (not (some #(and (= :context (:el %)) (= (:context v) (:id %))) els)))]
           (f (or (project/line-of text (pr-str (:key v))) 1) "model-element-unknown" (str "the view " (:key v) " names the bounded context " (pr-str (:context v)) ", which the model does not hold")))
         (for [v (:views m) :when (= :dynamic (:level v)) [from to] (:steps v) id [from to] :when (not (ids id))]
           (f (or (project/line-of text (pr-str (:key v))) 1) "model-element-unknown" (str "the view " (:key v) " names " (pr-str id) ", which is no element of the model"))))]
    (vec (concat key-findings dup-findings rel-findings view-findings))))

;; ------------------------------------------------------------------ views' elements

(defn- lift [els-by-id id to-level]
  (let [el (els-by-id id)]
    (case to-level
      :system (if (#{:person :external} (:el el)) id (or (:id (:system* el)) ::system))
      :container (case (:el el)
                   (:person :external :container) id
                   :component (:container el)
                   :context (:container el)
                   :system ::system
                   id))))

(defn view-elements
  "What a view holds: {:elements [el] :rels [{:from :to :description}] :inside #{id}}."
  [m view]
  (let [els (elements m)
        by-id (into {} (map (juxt :id identity) els))
        sys (:id (:system m))
        people-ext (filter #(#{:person :external} (:el %)) els)
        rels (:relationships m)
        dedupe-rels (fn [rs] (vec (distinct (remove #(= (:from %) (:to %)) rs))))]
    (case (:level view)
      :context
      (let [up (fn [id] (let [el (by-id id)] (if (#{:person :external} (:el el)) id sys)))]
        {:elements (vec (concat (when (:system m) [(by-id sys)]) people-ext))
         :rels (dedupe-rels (for [r rels] (assoc r :from (up (:from r)) :to (up (:to r)))))})
      :container
      (let [up (fn [id] (let [el (by-id id)] (case (:el el) (:component :context) (:container el) :system nil id)))]
        {:elements (vec (concat (filter #(= :container (:el %)) els) people-ext))
         :rels (dedupe-rels (for [r rels :let [a (up (:from r)) b (up (:to r))] :when (and a b)] (assoc r :from a :to b)))})
      :component
      (let [inside (set (map :id (filter #(and (= :component (:el %)) (= (:context view) (:context %))) els)))
            touching (filter #(or (inside (:from %)) (inside (:to %))) rels)
            outside (distinct (remove inside (mapcat (juxt :from :to) touching)))]
        {:elements (vec (concat (filter #(inside (:id %)) els) (keep by-id outside)))
         :inside inside
         :rels (vec touching)})
      :dynamic
      (let [ids (distinct (mapcat (fn [[a b]] [a b]) (:steps view)))]
        {:elements (vec (keep by-id ids))
         :rels (vec (for [[a b label] (:steps view)] {:from a :to b :description label}))})
      {:elements [] :rels []})))

;; ------------------------------------------------------------------ ownership

(defn owned-files
  "The feature files a component owns under the contexts layout: its spec path's."
  [decl comp _files]
  (when (:spec comp) [(str (:dir decl) "/" (:spec comp) ".feature")]))

(defn slice-findings
  "Under the slices layout: feature files outside every slice or named for no
  component, components sharing a name, and components no slice specifies."
  [decl m feature-files]
  (let [dir (:dir decl)
        comps (filter #(= :component (:el %)) (elements m))
        by-name (group-by #(name (:id %)) comps)
        rels (for [f feature-files] [f (subs f (inc (count dir)))])
        in-slice (for [[f r] rels :let [parts (str/split r #"/")]
                       :when (and (= 2 (count parts)) (re-matches #"\d+-[A-Za-z0-9._-]+" (first parts)))]
                   [f (str/replace (second parts) #"\.feature$" "")])
        named (set (map second in-slice))]
    (vec (concat
          (for [[f r] rels :let [parts (str/split r #"/")]
                :when (not (and (= 2 (count parts)) (re-matches #"\d+-[A-Za-z0-9._-]+" (first parts))))]
            (project/finding f "slice-missing" "a feature file outside every slice: the spec is grouped by slice, then by component"))
          (for [[f n] in-slice :when (not (by-name n))]
            (project/finding f "slice-component-unknown" (str "no component of the model is named " n)))
          (for [[n group] by-name :when (> (count group) 1)]
            (project/finding (:model decl) "component-name-shared"
                             (str "the components " (str/join " and " (map #(pr-str (:id %)) group)) " share the name " n
                                  ": a feature file is found by its component's name")))
          (for [c comps :when (not (named (name (:id c))))]
            (project/finding (:model decl) "component-unspecified"
                             (str "the component " (pr-str (:id c)) " has no feature file named " (name (:id c)) ".feature in any slice")))))))
