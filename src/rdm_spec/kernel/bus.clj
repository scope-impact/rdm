(ns rdm-spec.kernel.bus
  "Event bus (dhf/spec/kernel/bus.feature): delivers each catalogued event to
  its subscribers, in process, within one run.

  Delivery is synchronous and in subscription order (ADR 0002, \"Events\").
  Every published event is recorded, in the order it was published (before
  its delivery, so an event a handler publishes comes after the one it
  handles), so a run can print its events with --events.

  The catalogue is the project's (the configured :events, read by the
  command): {EventName {:published-by publisher :carries [key ...]}}. With a
  catalogue that declares anything, the bus refuses an event it does not
  declare, one whose payload lacks a key it carries, and one from a
  publisher other than the declared one. No catalogue, or an empty one (as
  init writes), declares nothing and so checks nothing. A refusal is a task
  that cannot run (exit 2): an ex-info carrying
  :rdm-spec.kernel.command/cannot-run, naming the event and the catalogue; a
  refused event is neither recorded nor delivered.

  A bus: {:catalogue map-or-nil :catalogue-file path-or-nil
          :subscribers (atom {event [{:subscriber s :handler f} ...]})
          :events (atom [{:event :publisher :payload} ...])}")

(defn make-bus
  "A new bus for one run. `opts`: :catalogue (the event catalogue, or nil to
  check nothing) and :catalogue-file (how refusals name it)."
  ([] (make-bus {}))
  ([{:keys [catalogue catalogue-file]}]
   {:catalogue catalogue
    :catalogue-file catalogue-file
    :subscribers (atom {})
    :events (atom [])}))

(defn subscribe!
  "Subscribes `subscriber` (who, for the record) to `event`: `handler` is
  called with each payload of it, after every earlier subscription's."
  [bus event subscriber handler]
  (swap! (:subscribers bus) update event (fnil conj []) {:subscriber subscriber :handler handler})
  nil)

(defn- kw-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- refuse! [bus message]
  (throw (ex-info (str message " (the event catalogue " (or (:catalogue-file bus) "configured") ")")
                  {:rdm-spec.kernel.command/cannot-run true})))

(defn- check!
  "Refuses what the catalogue does not allow; checks nothing without one."
  [bus publisher event payload]
  (let [catalogue (:catalogue bus)]
    (when (seq catalogue)
      (let [decl (get catalogue event)]
        (when-not decl
          (refuse! bus (str "the event " (kw-name event) " published by " (kw-name publisher)
                            " is not declared in " (or (:catalogue-file bus) "the event catalogue"))))
        (when (and (contains? decl :published-by) (not= publisher (:published-by decl)))
          (refuse! bus (str "the event " (kw-name event) " is published by " (kw-name (:published-by decl))
                            ", not by " (kw-name publisher))))
        (let [missing (remove #(contains? payload %) (:carries decl))]
          (when (seq missing)
            (refuse! bus (str "the event " (kw-name event) " published by " (kw-name publisher)
                              " lacks " (apply str (interpose ", " (map str missing)))
                              ", which it carries"))))))))

(defn publish!
  "Publishes `event` with `payload` from `publisher`: checks it against the
  catalogue, records it, then delivers it to every subscriber in
  subscription order. Returns nil."
  [bus publisher event payload]
  (check! bus publisher event payload)
  (swap! (:events bus) conj {:event event :publisher publisher :payload payload})
  (doseq [{:keys [handler]} (get @(:subscribers bus) event)]
    (handler payload))
  nil)

(defn events
  "Every event published on `bus`, in order: [{:event :publisher :payload}]."
  [bus]
  @(:events bus))

(defn format-event
  "An event's line on the error stream: `event: NAME published-by PUBLISHER`."
  [{:keys [event publisher]}]
  (str "event: " (kw-name event) " published-by " (kw-name publisher)))
