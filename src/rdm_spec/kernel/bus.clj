(ns rdm-spec.kernel.bus
  "The event bus (dhf/spec/kernel/bus.feature): bounded contexts meet only on
  it, within one run of the command. Delivery is synchronous, in
  subscription order; every event published is kept, in order, so a run can
  show its events.

  The bus carries only what the project's event catalogue (the configured
  :events, dhf/spec/events.edn) declares: an event it does not declare, one
  from another publisher than the declared one, or one missing a key it
  carries is refused, and the task cannot run (exit 2), naming the event and
  the catalogue. An empty catalogue, as init writes, declares nothing and so
  checks nothing.

    (make-bus {:catalogue {:Event {:published-by :ctx/comp :carries [:k]}}
               :source \"dhf/spec/events.edn\"})
    (subscribe! bus :Event :ctx/subscriber (fn [payload] ...))
    (publish! bus :ctx/comp :Event payload)
    (events bus)  ; [{:event :publisher :payload}], in order"
  (:require [clojure.string :as str]))

(defn make-bus
  "A new bus. `opts`: :catalogue (the declared events; empty or absent checks
  nothing) and :source (the catalogue's path, named in a refusal)."
  ([] (make-bus {}))
  ([{:keys [catalogue source]}]
   {:catalogue (if (map? catalogue) catalogue {})
    :source source
    :subscribers (atom [])
    :events (atom [])}))

(defn subscribe!
  "Subscribes `handler` (a function of the payload) to `event`, on behalf
  of `subscriber`. Returns the bus."
  [bus event subscriber handler]
  (swap! (:subscribers bus) conj {:event event :subscriber subscriber :handler handler})
  bus)

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn refusal
  "Why the bus refuses `publisher` publishing `event` with `payload`, or nil."
  [{:keys [catalogue source]} publisher event payload]
  (when (seq catalogue)
    (let [where (str "the configured catalogue" (when source (str " " source)))
          decl (get catalogue event)]
      (cond
        (nil? decl)
        (str "the event " (kw-name event) " is not declared by " where ", so the bus does not carry it"
             " (published by " (kw-name publisher) ")")

        (and (:published-by decl) (not= (:published-by decl) publisher))
        (str "the event " (kw-name event) " is published by " (kw-name publisher) ", but " where
             " declares its publisher " (kw-name (:published-by decl)))

        :else
        (when-let [missing (seq (remove #(contains? payload %) (:carries decl)))]
          (str "the event " (kw-name event) " carries no " (str/join ", " (map pr-str missing))
               ", which " where " says it carries"))))))

(defn publish!
  "Publishes `event` with `payload` from `publisher`: kept, then delivered
  to each subscriber in subscription order. A task cannot run when the
  catalogue refuses it."
  [bus publisher event payload]
  (when bus
    (when-let [why (refusal bus publisher event payload)]
      (throw (ex-info why {:rdm-spec.kernel.command/cannot-run true :event event})))
    (swap! (:events bus) conj {:event event :publisher publisher :payload payload})
    (doseq [{e :event h :handler} @(:subscribers bus)
            :when (= e event)]
      (h payload)))
  nil)

(defn events
  "Every event published on the bus, in order: [{:event :publisher :payload}]."
  [bus]
  (if bus @(:events bus) []))
