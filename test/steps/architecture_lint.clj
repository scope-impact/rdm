(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature: `rdm-spec check`
  on a copy of the room whose model a step edits, or on a small project with
  a model of its own (steps.architecture-support).

  Context keys set here: :dir the project; :named what the finding must
  name (strings); :view-count the elements a view shows; :limit the
  configured limit; :length a description's length."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- edited
  "ctx on a copy of the room whose model is (f model), naming `named`."
  [ctx f named]
  (let [dir (a/room-copy ctx)]
    (a/update-model! dir f)
    (assoc ctx :dir dir :named named)))

(defn- update-where [xs pred f] (mapv #(if (pred %) (f %) %) xs))

(defstep #"a view with no description"
  (fn [ctx]
    (edited ctx (fn [m] (update m :views update-where #(= "containers" (:key %)) #(dissoc % :description)))
            ["containers"])))

(defstep #"a container with no description"
  (fn [ctx]
    (edited ctx (fn [m] (update m :containers update-where #(= :repository (:id %)) #(dissoc % :description)))
            [":repository"])))

(defstep #"a relationship with no description"
  (fn [ctx]
    (edited ctx (fn [m] (update m :relationships update-where #(= [:reviewer :git-host] [(:from %) (:to %)])
                                #(dissoc % :description)))
            [":reviewer" ":git-host"])))

(defstep #"a context view that includes no person"
  (fn [ctx]
    (let [m (-> a/base-model
                (assoc :people [])
                (update :relationships (fn [rs] (vec (remove #(= :bather (:from %)) rs)))))]
      (assoc ctx :dir (a/small-project! ctx m) :named ["context"]))))

(defstep #"a container declared with kind library"
  (fn [ctx]
    (edited ctx (fn [m] (update m :containers conj {:id :helpers :name "Helpers" :kind :library
                                                    :technology "Any" :description "Shared helpers"}))
            [":helpers"])))

(defstep #"a relationship that publishes the event (\S+)"
  (fn [ctx event]
    (edited ctx (fn [m] (update m :relationships conj {:from :regeneration/cut :to :ratification/ratify
                                                       :description "Publishes" :event (keyword event)}))
            [":regeneration/cut" ":ratification/ratify" event])))

(defstep #"(\S+) is not in the event catalogue"
  (fn [ctx event]
    (let [dir (:dir ctx)
          rel (or (:events (kc/config-of dir)) "dhf/spec/events.edn")]
      (s/check! (not (str/includes? (slurp (str (fs/file dir rel))) (str ":" event)))
                (str "the catalogue declares " event))
      ctx)))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n]
    (let [dir (a/room-copy ctx)
          c (kc/config-of dir)]
      (s/write! dir "dhf/harness/config.edn" (pr-str (assoc-in c [:c4 :view-element-limit] (parse-long n))))
      (assoc ctx :dir dir :limit n))))

(defstep #"a view showing (\d+) elements"
  (fn [ctx n]
    ;; The context view shows the system, every person and every external
    ;; system: externals are added until it shows n.
    (let [dir (a/room-copy ctx)
          m (a/read-model dir)
          shown (+ 1 (count (:people m)) (count (:external m)))
          extra (- (parse-long n) shown)]
      (s/check! (pos? extra) "the context view already shows that many elements")
      (a/update-model! dir #(update % :external into
                                    (for [i (range extra)]
                                      {:id (keyword (str "outside-" i)) :name (str "Outside " i)
                                       :description "Another system"})))
      (assoc ctx :dir dir :named ["context" n] :view-count n))))

(defstep #"it warns under rule (\S+) and names the view and both counts"
  (fn [ctx rule]
    (let [r (kc/the-run ctx)
          ws (filterv kc/warning-line? (s/err-lines r))
          l (first (filter (fn [l] (every? #(str/includes? l %) [(str "warning: " rule ": ") "context"
                                                                  (:view-count ctx) (:limit ctx)]))
                           ws))]
      (s/check! l (str "no warning under " rule " names the view and both counts\n" (s/show r)))
      (s/check! (not (some #(str/includes? % (str ": " rule ": ")) (kc/finding-lines r)))
                (str "the view's size is a finding, not a warning\n" (s/show r)))
      nil)))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n]
    (let [dir (a/room-copy ctx)
          c (kc/config-of dir)]
      (s/write! dir "dhf/harness/config.edn" (pr-str (assoc-in c [:c4 :description-max-chars] (parse-long n))))
      (assoc ctx :dir dir :limit n))))

(defstep #"a person whose description is (\d+) characters"
  (fn [ctx n]
    (let [desc (subs (apply str (repeat 10 "Writes the spec, and more. ")) 0 (parse-long n))]
      (edited ctx (fn [m] (update m :people update-where #(= :engineer (:id %)) #(assoc % :description desc)))
              [":engineer" n (:limit ctx)]))))

(defstep #"it fails rule ((?:c4|a)-[a-z-]+) and names (.+)"
  (fn [ctx rule _what]
    (let [r (kc/exits! ctx 1)
          l (first (filter (fn [l] (and (str/includes? l (str ": " rule ": "))
                                        (every? #(str/includes? l (str %)) (:named ctx))))
                           (kc/finding-lines r)))]
      (s/check! l (str "no finding under " rule " names " (str/join ", " (:named ctx)) "\n" (s/show r)))
      nil)))

(defstep #"it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          out (kc/stdout-edn r)
          text (pr-str out)]
      (doseq [w ["audience" "coupling" "render"]]
        (s/check! (str/includes? text w) (str "the result lists nothing about " w "\n" (s/show r))))
      nil)))

;; ---------------------------------------------------------------------------
;; Bounded contexts are modules of one container

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (edited ctx (fn [m] (update m :views (fn [vs] (vec (remove #(= "knowledge-components" (:key %)) vs)))))
            [":knowledge"])))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (edited ctx (fn [m] (update m :containers update-where #(= :rdm-spec (:id %))
                                (fn [c] (update c :contexts update-where #(= :knowledge (:id %))
                                                (fn [x] (update x :components conj
                                                                {:id :knowledge/glossary :name "Glossary"
                                                                 :spec "knowledge/glossary"
                                                                 :description "The spec's vocabulary"}))))))
            [":knowledge/glossary"])))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [dir (a/room-copy ctx)]
      (s/write! dir "dhf/spec/knowledge/orphan.feature"
                "@UN-011\nFeature: Orphan\n  Owned by no component.\n")
      (assoc ctx :dir dir :named ["dhf/spec/knowledge/orphan.feature"]))))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (edited ctx (fn [m] (update m :relationships conj {:from :knowledge/wiki :to :publishing/site
                                                       :description "Feeds pages to"}))
            [":knowledge/wiki" ":publishing/site"])))
