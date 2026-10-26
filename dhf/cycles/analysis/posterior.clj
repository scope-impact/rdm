;; posterior.clj: what the graded cycles say about the spec, as beliefs with a
;; width rather than scores. An analysis of the cycles' record, not product
;; code: it reads dhf/cycles/NN/{checklist.json,verdicts.edn} and prints EDN
;; and Markdown. Run: bb dhf/cycles/analysis/posterior.clj [dhf/cycles] [extra.edn ...]
;;
;; The model (dhf/cycles/posterior.md explains it):
;;   theta_s  P(a build passes scenario s), scenario within component within
;;            context, partially pooled: a context's posterior is its
;;            components' prior, a component's posterior its scenarios' prior,
;;            each with a fixed prior strength KAPPA.
;;   each graded item is one Bernoulli draw of theta_s. Grader error is NOT
;;   modelled: no item has been graded twice, so it cannot be identified yet.
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str])

(def KAPPA 10.0)

;; ---- Beta distribution, numerically
(defn lgamma [x]                                   ; Lanczos
  (let [g 7 c [0.99999999999980993 676.5203681218851 -1259.1392167224028 771.32342877765313
               -176.61502916214059 12.507343278686905 -0.13857109526572012
               9.9843695780195716e-6 1.5056327351493116e-7]]
    (if (< x 0.5)
      (- (Math/log (/ Math/PI (Math/sin (* Math/PI x)))) (lgamma (- 1 x)))
      (let [x (dec x) t (+ x g 0.5)
            a (reduce + (first c) (map-indexed (fn [i ci] (/ ci (+ x i 1))) (rest c)))]
        (+ (* 0.5 (Math/log (* 2 Math/PI))) (* (+ x 0.5) (Math/log t)) (- t) (Math/log a))))))
(defn lbeta [a b] (- (+ (lgamma a) (lgamma b)) (lgamma (+ a b))))
(defn cdf [a b x]                                  ; Simpson on the pdf, in log space
  (if (<= x 0) 0.0
    (let [n 4000 h (/ x n)
          f (fn [t] (if (or (<= t 0) (>= t 1)) 0.0
                      (Math/exp (- (+ (* (dec a) (Math/log t)) (* (dec b) (Math/log (- 1 t)))) (lbeta a b)))))]
      (* (/ h 3) (reduce + (for [i (range (inc n))]
                             (* (f (* i h)) (cond (or (zero? i) (= i n)) 1 (odd? i) 4 :else 2))))))))
(defn quantile [a b p]
  (loop [lo 0.0 hi 1.0 k 0]
    (let [m (/ (+ lo hi) 2)]
      (if (> k 40) m (if (< (cdf a b m) p) (recur m hi (inc k)) (recur lo m (inc k)))))))
(defn mean [a b] (/ a (+ a b)))
(defn p-all [a b n] (Math/exp (- (lbeta (+ a n) b) (lbeta a b))))   ; E[theta^n]

;; ---- data
(defn cycle-data [dir]
  (for [c (sort (map str (fs/list-dir dir)))
        :let [cl (fs/file c "checklist.json") vd (fs/file c "verdicts.edn")]
        :when (and (fs/exists? cl) (fs/exists? vd))
        :let [items (into {} (for [i (get (json/parse-string (slurp (str cl))) "items")] [(get i "id") i]))
              vs (edn/read-string (slurp (str vd)))]]
    {:cycle (str (fs/file-name c))
     :obs (vec (for [v vs :let [i (items (:id v))] :when i]
                 {:id (:id v) :context (get i "context") :component (get i "component")
                  :pass? (= :pass (:verdict v)) :cycle (str (fs/file-name c))}))}))

;; ---- partial pooling: context -> component -> scenario
(defn posterior [a0 b0 obs]
  [(+ a0 (count (filter :pass? obs))) (+ b0 (count (remove :pass? obs)))])
(defn shrink [[a b]] (let [m (mean a b)] [(* m KAPPA) (* (- 1 m) KAPPA)]))

(defn analyse [cycles]
  (let [obs (mapcat :obs cycles)
        by-ctx (group-by :context obs)
        ctx-post (into {} (for [[c os] by-ctx] [c (posterior 1.0 1.0 os)]))
        comp-post (into {} (for [[[c k] os] (group-by (juxt :context :component) obs)]
                             [[c k] (apply posterior (concat (shrink (ctx-post c)) [os]))]))
        scen (for [[[c k id] os] (group-by (juxt :context :component :id) obs)
                   :let [[a b] (apply posterior (concat (shrink (comp-post [c k])) [os]))]]
               {:id id :context c :component k :graded (count os) :fails (count (remove :pass? os))
                :last (:pass? (last (sort-by :cycle os))) :mean (mean a b)
                :lo95 (quantile a b 0.05) :var (/ (* a b) (* (+ a b) (+ a b) (+ a b 1)))})]
    {:cycles (for [{:keys [cycle obs]} cycles
                   :let [p (count (filter :pass? obs)) f (- (count obs) p) a (+ 1 p) b (+ 1 f)]]
               {:cycle cycle :graded (count obs) :fails f :mean (mean a b)
                :lo95 (quantile a b 0.05) :p-slice-all-correct (p-all a b (count obs))})
     :contexts (sort-by first (for [[c [a b]] ctx-post] [c {:graded (+ a b -2) :fails (dec b) :mean (mean a b) :lo95 (quantile a b 0.05)}]))
     :components (sort-by (comp :mean second) (for [[[c k] [a b]] comp-post] [(str c "/" k) {:graded (count (filter #(and (= c (:context %)) (= k (:component %))) obs)) :mean (mean a b) :lo95 (quantile a b 0.05)}]))
     :scenarios scen}))

(defn pct [x] (format "%.1f%%" (* 100.0 x)))
(defn report [{:keys [cycles contexts components scenarios]}]
  (str/join "\n"
   (concat
    ["## Per cycle: P(a scenario of the slice passes), flat prior"
     "" "| Cycle | Graded | Fails | Posterior mean | 95% lower bound | P(every item of the slice right) |" "|---|---|---|---|---|---|"]
    (for [c cycles] (format "| %s | %d | %d | %s | %s | %s |" (:cycle c) (:graded c) (:fails c) (pct (:mean c)) (pct (:lo95 c)) (pct (:p-slice-all-correct c))))
    ["" "## Per bounded context, all cycles pooled" "" "| Context | Graded | Fails | Posterior mean | 95% lower bound |" "|---|---|---|---|---|"]
    (for [[c v] contexts] (format "| %s | %d | %d | %s | %s |" c (long (:graded v)) (long (:fails v)) (pct (:mean v)) (pct (:lo95 v))))
    ["" "## The ten least certain components (shrunk to their context)" "" "| Component | Graded | Posterior mean | 95% lower bound |" "|---|---|---|---|"]
    (for [[k v] (take 10 components)] (format "| %s | %d | %s | %s |" k (:graded v) (pct (:mean v)) (pct (:lo95 v))))
    ["" "## Where the next grading buys most: highest posterior variance" "" "| Scenario | Component | Graded | Fails | Last verdict | Posterior mean | 95% lower bound |" "|---|---|---|---|---|---|---|"]
    (for [s (take 12 (sort-by (comp - :var) scenarios))]
      (format "| %s | %s/%s | %d | %d | %s | %s | %s |" (:id s) (:context s) (:component s) (:graded s) (:fails s) (if (:last s) "pass" "fail") (pct (:mean s)) (pct (:lo95 s)))))))

(let [[dir & extra] *command-line-args*
      cycles (vec (concat (cycle-data (or dir "dhf/cycles")) (mapcat #(edn/read-string (slurp %)) extra)))]
  (println (report (analyse cycles))))
