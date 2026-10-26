(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` in a regeneration project (a copy of the room's
  kept paths, steps.regeneration-support) committed and tagged spec-3, a
  step's change committed and tagged spec-4. Its model and event catalogue
  are the room's, edited as a step says.

  The result: {:scopes {:from :to :scopes [{:component id}] :shared [path]}}.

  Context keys set here: :dir, :expected (the scopes, in order)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(def events-rel "dhf/spec/events.edn")

(defn- events [dir] (edn/read-string (slurp (str (fs/file dir events-rel)))))
(defn- write-events! [dir m] (s/write! dir events-rel (str ";; the fixture's catalogue\n" (pr-str m) "\n")))

(defn- cuts!
  "ctx with :dir: the room's copy, `before!` applied and cut spec-3,
  `change!` applied and cut spec-4."
  [ctx before! change!]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (before! dir)
    (rs/commit! dir "the cut spec-3")
    (rs/tag! dir "spec-3")
    (change! dir)
    (rs/commit! dir "the cut spec-4")
    (rs/tag! dir "spec-4")
    (assoc ctx :dir dir)))

(defn- append! [dir rel line]
  (spit (str (fs/file dir rel)) (str "\n" line "\n") :append true))

(defn- edit-events! [dir f] (write-events! dir (f (events dir))))

(defn- scopes-of [ctx]
  (let [r (kc/exits! ctx 0)
        out (:scopes (kc/stdout-edn r))]
    (s/check! (vector? (:scopes out)) (str "the result holds no {:scopes {:scopes [..]}}\n" (s/show r)))
    out))

(defn- lists! [ctx want]
  (let [got (:scopes (scopes-of ctx))]
    (s/check! (= want got) (str "the scopes are " (pr-str got) ", not " (pr-str want) "\n" (s/show (kc/the-run ctx))))
    nil))

(defn- comps [& ids] (mapv (fn [id] {:component id}) ids))

;; ---------------------------------------------------------------------------
;; A cycle's scopes come from the spec change

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx]
    (cuts! ctx (fn [_]) (fn [dir]
                          (append! dir "dhf/spec/architecture/views.feature" "# changed")
                          (append! dir "dhf/spec/kernel/bus.feature" "# changed")))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (lists! ctx (comps :kernel/bus :architecture/views))))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md, design prose that spec-3 did not have, was added"
  (fn [ctx]
    (cuts! ctx (fn [dir] (fs/delete-if-exists (fs/file dir "dhf/spec/architecture/views.md")))
           (fn [dir] (s/write! dir "dhf/spec/architecture/views.md" "# Views\n\nDesign prose.\n")))))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (lists! ctx (comps :architecture/views))))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (cuts! ctx (fn [_]) (fn [dir] (append! dir "dhf/spec/glossary.md" "**Term**: a fixture's term.")))))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (let [out (scopes-of ctx)
          r (kc/the-run ctx)]
      (s/check! (= [] (:scopes out)) (str "the scopes are " (pr-str (:scopes out))))
      (s/check! (= ["dhf/spec/glossary.md"] (:shared out)) (str "the shared files are " (pr-str (:shared out))))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "glossary.md") (str/includes? % "a person must judge"))
                      (s/err-lines r))
                (str "no warning names glossary.md for a person to judge\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A changed event key reaches its publisher and the subscribers that read it

(defn- shape! [dir event k f]
  (edit-events! dir #(update-in % [event :shape k] f)))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    ;; :read-by names the readers of SpecParsed's :needs against the
    ;; relationships' order, so the order listed is the relationships'
    (-> (cuts! ctx #(edit-events! % (fn [m] (assoc-in m [:SpecParsed :read-by :needs] [:compliance/audit :publishing/site])))
               #(shape! % :SpecParsed :needs (fn [x] (str x "; each need's sources in the order written"))))
        (assoc :expected (comps :spec/parser :publishing/site :compliance/audit)))))

(defstep #"it lists as scopes the event's publisher, then the subscribers its :read-by names for that key, in the order the model's relationships name them, and no other subscriber"
  (fn [ctx] (lists! ctx (:expected ctx))))

(defn- add-nested-key [shape]
  (str/replace-first shape ":component-name}]" ":component-name :tested-by}]"))

(defstep #"the cuts spec-3 and spec-4, between which one event's scenarios gained a key that :read-by names one subscriber as reading"
  (fn [ctx]
    (-> (cuts! ctx (fn [_])
               (fn [dir]
                 (edit-events! dir (fn [m]
                                     (-> m
                                         (update-in [:SpecParsed :shape :scenarios] add-nested-key)
                                         (assoc-in [:SpecParsed :read-by :scenarios :tested-by] [:measurement/checklist]))))))
        (assoc :expected (comps :spec/parser :measurement/checklist)))))

(defstep #"it lists as scopes the event's publisher, then that one subscriber, and no other subscriber"
  (fn [ctx] (lists! ctx (:expected ctx))))

(defstep #"the cuts spec-3 and spec-4, between which only (an event's :read-by changed|an event that had no :read-by gained one naming every key|an event that had no :read-by gained one naming a scenario key's readers)"
  (fn [ctx which]
    (case which
      "an event's :read-by changed"
      (cuts! ctx (fn [_]) #(edit-events! % (fn [m] (assoc-in m [:SpecGated :read-by :passed?] []))))
      "an event that had no :read-by gained one naming every key"
      (cuts! ctx #(edit-events! % (fn [m] (update m :SpecGated dissoc :read-by)))
             #(edit-events! % (fn [m] (assoc-in m [:SpecGated :read-by]
                                                {:revision [:publishing/site] :passed? [:publishing/site] :findings [:publishing/site]}))))
      "an event that had no :read-by gained one naming a scenario key's readers"
      (cuts! ctx #(edit-events! % (fn [m] (update m :SpecParsed dissoc :read-by)))
             #(edit-events! % (fn [m] (assoc-in m [:SpecParsed :read-by] {:scenarios {:id [:architecture/lint]}})))))))

(defstep #"it lists no scope"
  (fn [ctx] (lists! ctx [])))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of an event without :read-by changed"
  (fn [ctx]
    (-> (cuts! ctx #(edit-events! % (fn [m] (update m :SpecParsed dissoc :read-by)))
               #(shape! % :SpecParsed :needs (fn [x] (str x "; changed"))))
        (assoc :expected (comps :spec/parser :architecture/lint :verification/status :measurement/checklist
                                :publishing/site :compliance/audit)))))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event"
  (fn [ctx] (lists! ctx (:expected ctx))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key changed, whose :read-by entry is empty"
  (fn [ctx]
    (cuts! ctx #(edit-events! % (fn [m] (assoc-in m [:SpecGated :read-by :findings] [])))
           #(shape! % :SpecGated :findings (fn [x] (str x "; changed"))))))

(defstep #"it lists the event's publisher as the only scope"
  (fn [ctx] (lists! ctx (comps :spec/check))))

;; ---------------------------------------------------------------------------
;; Scopes are listed in the order a builder builds them

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (cuts! ctx (fn [_]) (fn [dir]
                          (doseq [f ["verification/status" "spec/parser" "kernel/command"]]
                            (append! dir (str "dhf/spec/" f ".feature") "# changed"))))))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (lists! ctx (comps :kernel/command :spec/parser :verification/status))))
