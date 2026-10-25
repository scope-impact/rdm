(ns rdm-spec.kernel.bus
  "Event bus (dhf/spec/kernel/bus.feature): delivers each catalogued event to
  its subscribers, in process, within one run of the command.

  Delivery is synchronous and in subscription order (ADR 0002, \"Events\").
  Every event published is recorded, in the order it was published (an event
  published by a handler comes after the one being delivered), so a run can
  print them (`--events`) and the same inputs always print the same ones.

  The bus carries only what the project's catalogue (the configured :events)
  declares: it refuses, as a task that cannot run (exit 2), an event the
  catalogue does not declare, one from a publisher other than the declared
  one, and one whose payload lacks a key the event carries. With no
  catalogue, or an empty one (as init writes), it checks nothing.

  A bus: {:catalogue {event {:published-by kw :carries [k ..]}} or nil
          :catalogue-file \"path\" or nil
          :subscribers (atom [{:event :subscriber :handler}])
          :log (atom [{:event :publisher :payload}])}"
  (:require [clojure.string :as str]))

(defn make-bus
  "A new bus checking events against `catalogue` (nil or empty: no check),
  which was read from `catalogue-file` (named in refusals)."
  ([] (make-bus {}))
  ([{:keys [catalogue catalogue-file]}]
   {:catalogue catalogue
    :catalogue-file catalogue-file
    :subscribers (atom [])
    :log (atom [])}))

(defn subscribe!
  "Subscribes `subscriber` (a component id, for the record) to `event`:
  `handler` is called with the payload of each such event, after the
  handlers subscribed before it."
  [bus event subscriber handler]
  (swap! (:subscribers bus) conj {:event event :subscriber subscriber :handler handler})
  nil)

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- refuse! [bus message data]
  (throw (ex-info (str message " (the catalogue " (or (:catalogue-file bus) "given to the bus") ")")
                  (merge data {:rdm-spec.kernel.command/cannot-run true
                               :catalogue (:catalogue-file bus)}))))

(defn- check! [bus publisher event payload]
  (let [catalogue (:catalogue bus)]
    (when (seq catalogue)
      (let [decl (get catalogue event)
            data {:event event :publisher publisher}]
        (when-not decl
          (refuse! bus (str (kw-name publisher) " published the event " (kw-name event)
                            ", which the catalogue does not declare")
                   data))
        (when (not= publisher (:published-by decl))
          (refuse! bus (str "the event " (kw-name event) " was published by " (kw-name publisher)
                            ", but its declared publisher is " (kw-name (:published-by decl)))
                   data))
        (let [missing (vec (remove #(contains? payload %) (:carries decl)))]
          (when (seq missing)
            (refuse! bus (str "the event " (kw-name event) " published by " (kw-name publisher)
                              " lacks " (str/join ", " (map str missing)) ", which it carries")
                     (assoc data :missing missing))))))))

(defn publish!
  "Publishes `event` with `payload` from `publisher`: checks it against the
  catalogue, records it, then calls each of its subscribers' handlers in
  subscription order. Refuses (cannot run) what the catalogue does not allow."
  [bus publisher event payload]
  (check! bus publisher event payload)
  (swap! (:log bus) conj {:event event :publisher publisher :payload payload})
  (doseq [{:keys [handler] :as s} @(:subscribers bus)
          :when (= event (:event s))]
    (handler payload))
  nil)

(defn events
  "Every event published on the bus, in the order it was published:
  [{:event :publisher :payload}]."
  [bus]
  @(:log bus))

(defn format-event
  "The event's line on the error stream: `event: NAME published-by PUBLISHER`."
  [{:keys [event publisher]}]
  (str "event: " (kw-name event) " published-by " (kw-name publisher)))
