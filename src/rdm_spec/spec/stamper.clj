(ns rdm-spec.spec.stamper
  "Component Stamper (spec context): gives every unstamped scenario the next
  id from the ledger, and advances the ledger, so an id is never issued twice,
  whatever the scenario's wording or place."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]))

(defn stamp-text
  "TEXT with each [line id] of STAMPS put on the scenario at that line: first
  on its tag line, or on a line of its own above it."
  [text stamps]
  (let [lines (vec (str/split text #"\n" -1))]
    (str/join "\n"
              (reduce (fn [ls [line id]]
                        (let [i (dec line)
                              above (dec i)
                              tag-line? (and (>= above 0) (str/starts-with? (str/trim (nth ls above)) "@"))]
                          (if tag-line?
                            (let [start (loop [j above] (if (and (pos? j) (str/starts-with? (str/trim (nth ls (dec j))) "@")) (recur (dec j)) j))
                                  l (nth ls start)
                                  ind (re-find #"^\s*" l)]
                              (assoc ls start (str ind "@" id " " (subs l (count ind)))))
                            (let [ind (re-find #"^\s*" (nth ls i))]
                              (vec (concat (subvec ls 0 i) [(str ind "@" id)] (subvec ls i)))))))
                      lines
                      (sort-by first > stamps)))))

(defn ledger-text [text n]
  (if (re-find #":issued\s+\d+" text)
    (str/replace-first text #":issued\s+\d+" (str ":issued " n))
    (str "{:issued " n "}\n")))

(defn read-ledger
  "The highest id the ledger of SPEC has issued, or a finding."
  [tree spec]
  (let [r (project/read-edn-file tree (:ledger spec))
        v (:value r)]
    (cond
      (:missing r) {:finding (project/finding (:ledger spec) "ledger-unreadable" "the id ledger is missing")}
      (:error r) {:finding (project/finding (:ledger spec) (get-in r [:error :line]) "ledger-unreadable"
                                            (str "the id ledger is not readable EDN: " (get-in r [:error :message])))}
      (not (and (map? v) (integer? (:issued v)) (>= (:issued v) 0)))
      {:finding (project/finding (:ledger spec) "ledger-unreadable" "the id ledger is not {:issued N}")}
      :else {:issued (:issued v)})))

(defn stamp-spec! [tree cfg spec]
  (let [{:keys [issued finding]} (read-ledger tree spec)]
    (if finding
      {:findings [finding]}
      (let [read (parser/read-spec tree cfg spec)
            unstamped (filter #(empty? (:ids %)) (:scenarios read))
            numbered (map-indexed (fn [i sc] [sc (format "%s-%04d" (:id-prefix spec) (+ issued 1 i))]) unstamped)
            last-n (+ issued (count unstamped))]
        (doseq [[file group] (group-by (comp :file first) numbered)]
          (let [f (project/abs tree file)]
            (spit f (stamp-text (slurp f) (map (fn [[sc id]] [(:line sc) id]) group)))))
        (when (seq unstamped)
          (spit (project/abs tree (:ledger spec)) (ledger-text (slurp (project/abs tree (:ledger spec))) last-n)))
        {:stamped (vec (for [[sc id] numbered] {:id id :file (:file sc) :title (:title sc)}))
         :issued last-n
         :findings []}))))

(defn stamp-task [{:keys [cfg]}]
  (project/validate-specs! (project/root) cfg)
  (let [rs (for [spec (project/specs cfg)] (assoc (stamp-spec! (project/root) cfg spec) :spec spec))
        multi? (seq (:specs cfg))]
    (project/report {:stamp {:stamped (vec (mapcat :stamped rs))
                             :issued (if multi? (into {} (for [r rs] [(get-in r [:spec :name]) (:issued r)])) (:issued (first rs)))}}
                    (mapcat :findings rs))))
