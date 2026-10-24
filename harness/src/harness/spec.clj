(ns harness.spec
  "Reading a slice's spec line by line, as Löyly's tools do: a scenario is a
  line whose first word is `Scenario:` or `Scenario Outline:`; its tags are the
  tag lines directly above it; its Rule is the last `Rule:` line above it, with
  that Rule's own tag lines. Lines inside a doc string are text, never
  headings. RDM's own reader parses the same file fully for the record (DI-90)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def plan-dir "plan")

(defn slice-number
  "The slice number: the slice directory's name up to its first \"-\", verbatim."
  [slice-dir]
  (first (str/split (fs/file-name slice-dir) #"-" 2)))

(defn status
  "The `status:` line of a slice's plan, or nil."
  [slice-dir]
  (let [plan (fs/path slice-dir "plan.md")]
    (when (fs/exists? plan)
      (some->> (re-find #"(?m)^status:\s*(\S+)\s*$" (slurp (str plan))) second))))

(defn slices
  "Every slice directory of the record that holds a spec, sorted."
  [dhf]
  (let [root (fs/path dhf plan-dir)]
    (if (fs/directory? root)
      (->> (fs/list-dir root) (filter fs/directory?) (filter #(fs/exists? (fs/path % "spec.feature")))
           (sort-by str))
      [])))

(defn spec-file [slice-dir] (fs/path slice-dir "spec.feature"))

(def ^:private heading #"^\s*(Scenario(?: Outline)?):\s*(.*?)\s*$")
(def ^:private rule-line #"^\s*Rule:\s*(.*?)\s*$")
(def ^:private item-tag #"^item-(\d+)-(\d+)$")

(defn- tags-of [line] (map #(subs % 1) (re-seq #"@\S+" line)))

(defn read-lines
  "The scenarios of a spec's lines, in order: each a map of :title, :line
  (1-based), :indent, :tags, :item, :rule and :rule-tags."
  [lines]
  (loop [[line & more] lines, n 1, pending [], rule nil, in-doc nil, found []]
    (if (nil? line)
      found
      (let [trimmed (str/trim line)
            fence (cond (str/starts-with? trimmed "\"\"\"") "\"\"\"" (str/starts-with? trimmed "```") "```")]
        (cond
          in-doc (recur more (inc n) pending rule (when-not (= fence in-doc) in-doc) found)
          fence (recur more (inc n) [] rule fence found)
          (or (str/blank? trimmed) (str/starts-with? trimmed "#")) (recur more (inc n) pending rule nil found)
          (str/starts-with? trimmed "@") (recur more (inc n) (into pending (tags-of trimmed)) rule nil found)
          :else
          (if-let [[_ title] (re-matches rule-line line)]
            (recur more (inc n) [] {:title title :tags pending :line n} nil found)
            (if-let [[_ _ title] (re-matches heading line)]
              (recur more (inc n) [] rule nil
                     (conj found {:title title :line n :tags pending
                                  :indent (subs line 0 (- (count line) (count (str/triml line))))
                                  :item (first (filter #(re-matches item-tag %) pending))
                                  :rule (:title rule) :rule-tags (:tags rule)}))
              (recur more (inc n) [] (if (str/starts-with? trimmed "Feature:") nil rule) nil found))))))))

(defn read-spec [file] (read-lines (str/split-lines (slurp (str file)))))

(defn item-number
  "The running number of an item id, or nil."
  [item]
  (some-> (re-matches item-tag (str item)) (nth 2) parse-long))

(defn design-input [tags] (first (filter #(re-matches #"DI-\d+" %) tags)))

(defn relative [root path] (str (fs/relativize (fs/absolutize root) (fs/absolutize path))))
