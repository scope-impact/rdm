(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` in a regeneration project (a copy of the room's kept
  paths, a git repository, steps.regeneration-support) cut spec-3, changed
  as a step says, and cut spec-4.

  The scopes' result, as this file reads it: {:scopes {:from :to :scopes
  [{:component id}] :shared [path]}}.

  Context keys set here: :dir, :expected (the scopes a Then expects, as
  component ids), :event (the event a step changed)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- two-cuts!
  "ctx with :dir: a regeneration project cut spec-3, `change!` applied to
  it, and cut spec-4."
  [ctx change!]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (s/hooks-off! dir)
    (rs/commit! dir "spec-3")
    (rs/tag! dir "spec-3")
    (change! dir)
    (rs/commit! dir "spec-4")
    (rs/tag! dir "spec-4")
    (assoc ctx :dir dir)))

(defn- append! [dir rel text]
  (let [f (fs/file dir rel)]
    (s/check! (fs/exists? f) (str "the project has no " rel))
    (spit (str f) text :append true)))

(def ^:private events-rel "dhf/spec/events.edn")

(defn- update-events! [dir f]
  (let [m (edn/read-string (slurp (str (fs/file dir events-rel))))]
    (s/write! dir events-rel (str (pr-str (f m)) "\n"))))

(defn- scopes-of [r]
  (let [out (kc/stdout-edn r)]
    (s/check! (map? (:scopes out)) (str "no scopes in the result\n" (s/show r)))
    (mapv :component (get-in out [:scopes :scopes]))))

(defn- lists! [ctx want]
  (let [r (kc/exits! ctx 0)
        got (scopes-of r)]
    (s/check! (= want got) (str "it lists " (pr-str got) ", not " (pr-str want) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     (append! dir "dhf/spec/architecture/views.feature" "\n# changed between the cuts\n")
                     (append! dir "dhf/spec/kernel/bus.feature" "\n# changed between the cuts\n")))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (lists! ctx [:kernel/bus :architecture/views]) nil))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md, design prose that spec-3 did not have, was added"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     (s/check! (not (fs/exists? (fs/file dir "dhf/spec/architecture/views.md"))) "views.md exists at spec-3")
                     (s/write! dir "dhf/spec/architecture/views.md" "# Views\n\nHow the views are drawn.\n")))))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (lists! ctx [:architecture/views]) nil))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (two-cuts! ctx (fn [dir] (append! dir "dhf/spec/glossary.md" "\n**Sauna**: a fixture's word.\n")))))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (let [r (lists! ctx [])]
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "dhf/spec/glossary.md")) (s/err-lines r))
                (str "no warning names glossary.md\n" (s/show r)))
      (s/check! (= ["dhf/spec/glossary.md"] (get-in (kc/stdout-edn r) [:scopes :shared]))
                (str "the shared files are " (pr-str (get-in (kc/stdout-edn r) [:scopes :shared]))))
      nil)))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (let [ctx (two-cuts! ctx (fn [dir] (update-events! dir #(assoc-in % [:SpecParsed :shape :needs] "[{:id :text}], changed"))))
          m (rs/model-of (:dir ctx))
          ev (edn/read-string (slurp (str (fs/file (:dir ctx) events-rel))))
          subs (for [r (:relationships m) :when (= :SpecParsed (:event r))] (:to r))]
      (assoc ctx :event :SpecParsed
             :expected (vec (distinct (cons (get-in ev [:SpecParsed :published-by]) subs)))))))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the order they name them"
  (fn [ctx]
    (s/check! (< 2 (count (:expected ctx))) (str "the event has too few subscribers to tell an order: " (pr-str (:expected ctx))))
    (lists! ctx (:expected ctx))
    nil))

(defstep #"the cuts spec-3 and spec-4, between which only an event's :description changed"
  (fn [ctx] (two-cuts! ctx (fn [dir] (update-events! dir #(assoc-in % [:SpecParsed :description] "Reworded, nothing else"))))))

(defstep #"it lists no scope"
  (fn [ctx] (lists! ctx []) nil))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     ;; written in the reverse of the order the scopes are built in
                     (doseq [rel ["dhf/spec/verification/status.feature" "dhf/spec/spec/parser.feature"
                                  "dhf/spec/kernel/command.feature"]]
                       (append! dir rel "\n# changed between the cuts\n"))))))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (lists! ctx [:kernel/command :spec/parser :verification/status]) nil))
