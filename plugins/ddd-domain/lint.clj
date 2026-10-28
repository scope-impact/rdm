#!/usr/bin/env bb
;; The ddd-domain plugin's lint (README.md, domain.feature): holds a project's
;; domain model to its shape, to its sources and to the rules of domain-driven
;; design. A subscriber to :SpecParsed (rdm-spec ADR 0003): the event on its
;; standard input gives the stamped scenarios an invariant must name.
;;
;; Usage: bb lint.clj [DOMAIN]   (DOMAIN defaults to dhf/spec/domain.edn)
;; Reads the configuration's :model, :events and :raw-dir. Findings on the error
;; stream as FILE:LINE: RULE: what; exit 0 none, 1 findings, 2 cannot run.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(defn cannot-run [msg]
  (binding [*out* *err*] (println (str "cannot run: " msg)))
  (System/exit 2))

(defn read-edn [path what]
  (try (edn/read-string (slurp path))
       (catch Exception e (cannot-run (str "cannot read " what " " path ": " (ex-message e))))))

(def domain-path (or (first *command-line-args*) "dhf/spec/domain.edn"))
(def event (let [in (slurp *in*)] (if (str/blank? in) {} (edn/read-string in))))
(def config (read-edn "dhf/harness/config.edn" "the configuration"))

(when-not (fs/exists? domain-path)
  ;; The domain model is optional.
  (System/exit 0))

(def domain-text (slurp domain-path))
(def domain (read-edn domain-path "the domain model"))
(def model (read-edn (:model config "dhf/spec/model.edn") "the model"))
(def catalogue (read-edn (:events config "dhf/spec/events.edn") "the event catalogue"))
(def sources (read-edn (str (:raw-dir config "dhf/raw") "/sources.edn") "the sources index"))
(def stamped
  (set (mapcat #(or (seq (:ids %)) (some-> (:id %) vector)) (:scenarios event))))

(def findings (atom []))

(defn line-of [k]
  (let [needle (str k)
        lines (str/split-lines domain-text)]
    (or (some (fn [[i l]] (when (str/includes? l needle) (inc i))) (map-indexed vector lines)) 1)))

(defn finding! [k rule what]
  (swap! findings conj (str domain-path ":" (line-of k) ": " rule ": " what)))

;; --- shape -------------------------------------------------------------------
(def keys-of
  {:top #{:contexts :events}
   :context #{:aggregates :read-models :policies :repositories}
   :aggregate #{:commands :invariants :reads :sources}
   :command #{:by :emits :refuses :sources}
   :invariant #{:text :verified-by :sources}
   :read-model #{:fed-by :sources}
   :policy #{:when :then :sources}
   :repository #{:stores}
   :event #{:sources}})

(defn shape! [kind k m]
  (if-not (map? m)
    (finding! k "domain-key-undeclared" (str (name kind) " " k " is not a map"))
    (doseq [extra (remove (keys-of kind) (keys m))]
      (finding! extra "domain-key-undeclared" (str (name kind) " " k " holds " extra ", which " (if (#{\a \e \i \o \u} (first (name kind))) "an " "a ") (name kind) " does not declare")))))

(shape! :top :domain domain)

(def contexts (:contexts domain))
(def model-contexts
  (set (for [c (:containers model) ctx (:contexts c)] (:id ctx))))
(def people (set (map :id (concat (:people model) (:external model)))))

(def aggregates ;; [ctx agg-id agg]
  (for [[ctx c] contexts [a agg] (:aggregates c)] [ctx a agg]))
(def commands ;; [ctx agg cmd-id cmd]
  (for [[ctx a agg] aggregates [k cmd] (:commands agg)] [ctx a k cmd]))

;; --- sources -----------------------------------------------------------------
(defn citation-known? [c]
  (when-let [[_ src _path commit] (re-matches #"([^:]+):(.+)@([0-9a-f]+)" (str c))]
    (let [pins (get-in sources [(keyword src) :pins])]
      (some #(or (str/starts-with? % commit) (str/starts-with? commit %)) pins))))

(defn sourced! [k what m]
  (let [cs (:sources m)]
    (if (empty? cs)
      (finding! k "domain-unsourced" (str what " " k " cites no source"))
      (doseq [c cs :when (not (citation-known? c))]
        (finding! k "domain-source-unknown" (str what " " k " cites " c ", a source or pin dhf/raw/sources.edn does not record"))))))

;; --- the rules ---------------------------------------------------------------
(doseq [[ctx c] contexts]
  (shape! :context ctx c)
  (when-not (model-contexts ctx)
    (finding! ctx "domain-context-unknown" (str "the context " ctx " is not a bounded context of the model")))
  (doseq [[rm m] (:read-models c)]
    (shape! :read-model rm m) (sourced! rm "the read model" m)
    (let [command-names (set (map #(nth % 2) commands))]
      (doseq [f (:fed-by m) :when (or (command-names f) (not (contains? (:events domain) f)))]
        (finding! rm "domain-read-model-fed" (str "the read model " rm " is fed by " f ", which is not an event of the domain")))))
  (doseq [[p m] (:policies c)] (shape! :policy p m) (sourced! p "the policy" m))
  (doseq [[r m] (:repositories c)] (shape! :repository r m)))

(doseq [[ctx a agg] aggregates]
  (shape! :aggregate a agg)
  (sourced! a "the aggregate" agg)
  (doseq [r (:reads agg)
          [other-ctx other] (keep (fn [[c2 a2 _]] (when (= a2 r) [c2 a2])) aggregates)
          :when (not= other-ctx ctx)]
    (finding! a "domain-contexts-coupled" (str "the aggregate " a " of " ctx " reads " other " of " other-ctx ": contexts meet through events")))
  (doseq [[i inv] (:invariants agg)]
    (shape! :invariant i inv)
    (sourced! i "the invariant" inv)
    (let [ids (:verified-by inv)]
      (if (empty? ids)
        (finding! i "domain-invariant-unverified" (str "the invariant " i " names no scenario that verifies it"))
        (doseq [id ids :when (not (stamped id))]
          (finding! i "domain-invariant-unverified" (str "the invariant " i " is verified by " id ", which no stamped scenario carries")))))))

(doseq [[[_ k] group] (group-by (fn [[_ _ k _]] [nil k]) commands) :when (> (count group) 1)]
  (finding! k "domain-command-handlers" (str "the command " k " is handled by " (str/join " and " (map second group)) ": one aggregate handles a command")))

(doseq [[_ a k cmd] commands]
  (shape! :command k cmd)
  (sourced! k "the command" cmd)
  (doseq [by (if (coll? (:by cmd)) (:by cmd) [(:by cmd)]) :when (not (people by))]
    (finding! k "domain-actor-unknown" (str "the command " k " of " a " is issued by " by ", which is no person or external system of the model")))
  (when (and (empty? (:emits cmd)) (empty? (:refuses cmd)))
    (finding! k "domain-command-silent" (str "the command " k " emits no event and names no refusal")))
  (doseq [e (:emits cmd) :when (not (contains? catalogue e))]
    (finding! e "domain-event-uncatalogued" (str "the command " k " emits " e ", which the event catalogue does not declare"))))

(let [emitted (set (mapcat (fn [[_ _ _ cmd]] (:emits cmd)) commands))]
  (doseq [[e m] (:events domain)]
    (shape! :event e m)
    (sourced! e "the event" m)
    (when-not (emitted e)
      (finding! e "domain-event-orphan" (str "the event " e " is emitted by no command")))))

(let [fs @findings]
  (binding [*out* *err*] (doseq [f (distinct fs)] (println f)))
  (System/exit (if (seq fs) 1 0)))
