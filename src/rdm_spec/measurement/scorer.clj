(ns rdm-spec.measurement.scorer
  "Component Scorer (measurement context): files the graders' verdicts on a
  verified cycle against its checklist into score.edn. It records nothing it
  did not measure: no verdicts, no score; an item nobody graded is a fail;
  a verdict for an item not on the checklist is refused; the builder never
  grades or reads its own score; only a verified build is scored."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def role-keys [:builder :grader :spec-owner])

(defn read-verdicts
  "Every verdict of FILE, each with its :file; or findings."
  [file]
  (let [text (project/read-text (project/root) file)]
    (when (nil? text) (project/cannot-run! (str "the verdicts file " file " does not exist")))
    (let [r (project/read-edn-text text)
          f (fn [msg] {:findings [(project/finding file "verdicts-malformed" msg)]})]
      (cond
        (:error r) {:findings [(project/finding file (get-in r [:error :line]) "verdicts-unreadable"
                                                (str file " is not readable EDN: " (get-in r [:error :message])))]}
        (not (vector? (:value r))) (f (str file " holds " (if (map? (:value r)) "a map" "no vector") ": a verdicts file is a vector of {:id :verdict :evidence}"))
        :else
        (let [vs (:value r)
              bad (for [v vs
                        msg (cond
                              (not (map? v)) [(str "a verdict is not a map: " (pr-str v))]
                              :else (concat
                                     (when-not (and (string? (:id v)) (not (str/blank? (:id v)))) [(str "a verdict names no :id: " (pr-str v))])
                                     (when-not (#{:pass :fail} (:verdict v)) [(str (:id v) " is given the verdict " (pr-str (:verdict v)) ": a verdict is :pass or :fail")])
                                     (when-not (and (string? (:evidence v)) (not (str/blank? (:evidence v))))
                                       [(str (:id v) " is given no evidence: every verdict carries its :evidence")])))]
                    msg)]
          (if (seq bad)
            {:findings (mapv #(project/finding file "verdicts-malformed" %) bad)}
            {:verdicts (mapv #(assoc % :file file) vs)}))))))

(defn edn-line [k v] (str " " (pr-str k) " " (pr-str v)))

(defn score-text
  "score.edn, its keys in their order, one item per line."
  [m]
  (str "{" (str/triml (str/join "\n" (for [k [:cycle :cut :scope :room-tree :builder :grader :spec-owner]] (edn-line k (get m k)))))
       "\n :items [" (str/join "\n         " (map pr-str (:items m))) "]"
       "\n" (edn-line :passed (:passed m))
       "\n" (edn-line :scored (:scored m)) "}\n"))

(defn score-task [{:keys [cfg args opts]}]
  (let [cycle (first args)
        files (get opts "--verdicts")
        cdir (str (:cycles-dir cfg) "/" cycle)
        cycle-file (str cdir "/cycle.edn")]
    (when (empty? files) (project/cannot-run! "score needs --verdicts FILE: no verdicts, no score"))
    (let [r (project/read-edn-file (project/root) cycle-file)
          _ (when (:missing r) (project/cannot-run! (str "no " cycle-file ": a cycle is declared before it is scored")))
          _ (when (:error r) (project/cannot-run! (str cycle-file " is not readable EDN: " (get-in r [:error :message]))))
          cyc (:value r)
          read (map read-verdicts files)
          read-findings (mapcat :findings read)]
      (if (seq read-findings)
        (project/report {} read-findings)
        (let [verdicts (vec (mapcat :verdicts read))]
          (when (empty? verdicts)
            (project/cannot-run! (str "the verdicts hold no verdict: the grading did not happen, so nothing is scored")))
          (let [cl-file (str cdir "/checklist.json")
                cl-text (project/read-text (project/root) cl-file)
                _ (when-not cl-text (project/cannot-run! (str "no " cl-file ": run rdm-spec checklist " cycle " first")))
                cl (json/parse-string cl-text true)
                item-ids (mapv :id (:items cl))
                on-list (set item-ids)
                rat (:value (project/read-edn-file (project/root) (str cdir "/ratified.edn")))
                dup (for [[id vs] (group-by :id verdicts) :when (> (count vs) 1)]
                      (project/finding (:file (first vs)) "verdicts-graded-twice"
                                       (str id " is graded twice, in " (str/join " and " (distinct (map :file vs))))))
                roles (for [k role-keys :when (not (and (map? (get cyc k)) (string? (get-in cyc [k :name]))))]
                        (project/finding cycle-file "cycle-role-missing" (str "the cycle names no " (name k) ": every role is named, as human or agent")))
                own (when (and (map? (:builder cyc))
                               (some #(= (get-in cyc [:builder :name]) (get-in cyc [% :name])) [:grader :spec-owner]))
                      [(project/finding cycle-file "cycle-builder-grades"
                                        (str (get-in cyc [:builder :name]) " is the builder: the builder may not grade or read its own score"))])
                unverified (when-not (= :passed (:outcome rat))
                             [(project/finding (str cdir "/ratified.edn") "cycle-unverified"
                                               (str "the cycle has no passing verification: only a verified build is scored"))])
                unknown (for [v verdicts :when (not (on-list (:id v)))]
                          (project/finding (:file v) "verdict-unknown" (str (:id v) " is not on cycle " cycle "'s checklist")))
                findings (concat dup roles own unverified unknown)]
            (if (seq findings)
              (project/report {} findings)
              (let [by-id (into {} (map (juxt :id identity) verdicts))
                    items (vec (for [id item-ids]
                                 (if-let [v (by-id id)]
                                   {:id id :verdict (:verdict v) :evidence (:evidence v)}
                                   {:id id :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true})))
                    m {:cycle cycle :cut (:cut cyc) :scope (get cyc :scope :project) :room-tree (:room-tree rat)
                       :builder (:builder cyc) :grader (:grader cyc) :spec-owner (:spec-owner cyc)
                       :items items :passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)}
                    out (str cdir "/score.edn")]
                (spit (project/abs out) (score-text m))
                (project/report {:score {:cycle cycle :passed (:passed m) :scored (:scored m)
                                         :ungraded (count (filter :ungraded items)) :file out}})))))))))
