(ns rdm-spec.measurement.scorer
  "The scorer (dhf/spec/measurement/scorer.feature): files a grader's
  verdicts on a verified cycle into its score.edn. It records nothing when
  nothing was graded, scores an ungraded item as a fail, and refuses a cycle
  whose builder grades it or reads its score."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(defn verdict-problems
  "What is malformed in a verdicts file's value."
  [v]
  (if-not (vector? v)
    [(str "the verdicts file holds " (if (map? v) "a map" (pr-str (type v))) ", not a vector of {:id :verdict :evidence}")]
    (vec
     (concat
      (for [x v :when (not (map? x))] (str "the verdict " (pr-str x) " is not a map {:id :verdict :evidence}"))
      (for [x v :when (and (map? x) (not (string? (:id x))))] (str "the verdict " (pr-str x) " names no :id"))
      (for [x v :when (and (map? x) (not (#{:pass :fail} (:verdict x))))]
        (str "the verdict for " (:id x) " is " (pr-str (:verdict x)) ": a verdict is :pass or :fail"))
      (for [x v :when (and (map? x) (or (not (string? (:evidence x))) (str/blank? (:evidence x))))]
        (str "the verdict for " (:id x) " gives no evidence: every verdict carries its evidence"))
      (for [[id n] (frequencies (keep #(when (map? %) (:id %)) v)) :when (> n 1)]
        (str id " is graded " n " times: grade it once, not twice"))))))

(defn items
  "The score's items, in the checklist's order: each verdict, or a fail for an item nobody graded."
  [ids verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (vec (for [id ids]
           (if-let [v (by-id id)]
             (select-keys v [:id :verdict :evidence])
             {:id id :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true})))))

(defn stage [{:keys [root config args opts]}]
  (let [cycle (first args)
        dir (str (:cycles-dir config "dhf/cycles") "/" cycle)
        vfile (or (:verdicts opts) (cmd/cannot-run! "score needs --verdicts FILE"))
        vtext (cmd/read-at root nil vfile)
        _ (when (nil? vtext) (cmd/cannot-run! (str "the verdicts file " vfile " does not exist")))
        verdicts (try (cmd/read-edn vtext) (catch Exception e ::unreadable))
        decl (or (try (cmd/read-edn (cmd/read-at root nil (str dir "/cycle.edn"))) (catch Exception _ nil))
                 (cmd/cannot-run! (str "the cycle " cycle " has no readable " dir "/cycle.edn")))
        f (fn [file msg rule] (cmd/finding file nil rule msg))]
    (if (= ::unreadable verdicts)
      {:findings [(f vfile "the verdicts file is not EDN" "verdicts-malformed")]}
      (let [problems (verdict-problems verdicts)]
        (cond
          (seq problems) {:findings (mapv #(f vfile % "verdicts-malformed") problems)}
          (empty? verdicts) (cmd/cannot-run! (str "the verdicts file " vfile " holds no verdict: the grading did not happen, so nothing is recorded"))
          :else
          (let [checklist (or (try (json/parse-string (cmd/read-at root nil (str dir "/checklist.json"))) (catch Exception _ nil))
                              (cmd/cannot-run! (str "the cycle " cycle " has no checklist: run \"rdm-spec checklist " cycle "\" first")))
                ids (mapv #(get % "id") (get checklist "items"))
                ratified (try (cmd/read-edn (cmd/read-at root nil (str dir "/ratified.edn"))) (catch Exception _ nil))
                roles [:builder :grader :spec-owner]
                missing (remove #(get-in decl [% :name]) roles)
                builder (get-in decl [:builder :name])
                findings (vec
                          (concat
                           (for [r missing]
                             (f (str dir "/cycle.edn") (str "the cycle declares no " r ": every role is named, as human or agent") "role-unnamed"))
                           (when (and builder (some #{builder} [(get-in decl [:grader :name]) (get-in decl [:spec-owner :name])]))
                             [(f (str dir "/cycle.edn") (str builder " is the cycle's builder and its grader or spec owner: the builder may not grade or read its own score") "builder-not-independent")])
                           (when-not (= :passed (:outcome ratified))
                             [(f (str dir "/ratified.edn")
                                 (str "the cycle has no passing verification" (when ratified (str ": its outcome is " (:outcome ratified))) ": only a verified build is scored")
                                 "unverified-cycle")])
                           (for [v verdicts :when (not (some #{(:id v)} ids))]
                             (f vfile (str (:id v) " is not on cycle " cycle "'s checklist") "verdict-unknown-item"))))]
            (if (seq findings)
              {:findings findings}
              (let [its (items ids verdicts)
                    score {:cycle cycle :cut (:cut decl) :scope (get decl :scope :project)
                           :room-tree (:room-tree ratified)
                           :builder (:builder decl) :grader (:grader decl) :spec-owner (:spec-owner decl)
                           :items its
                           :passed (count (filter #(= :pass (:verdict %)) its))
                           :scored (count its)}]
                (spit (fs/file root dir "score.edn") (with-out-str (pp/pprint score)))
                {:result {:score {:cycle cycle :passed (:passed score) :scored (:scored score) :file (str dir "/score.edn")}}}))))))))
