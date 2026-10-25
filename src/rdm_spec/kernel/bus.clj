(ns rdm-spec.kernel.bus
  "Event bus (dhf/spec/kernel/bus.feature): the in-process bus the bounded
  contexts meet on, within one run of the rdm-spec command.

  Delivery is synchronous and in subscription order. The bus remembers every
  event it delivered, in the order published, so a run can print them
  (`rdm-spec <task> --events`).

  The catalogue is the project's own (the configured :events file), handed to
  `make-bus` by the command. When the bus has a catalogue that declares at
  least one event, it refuses (cannot run, exit 2) an event the catalogue does
  not declare, one from a publisher other than the declared one, and one whose
  payload lacks a key the event carries. A bus made with no catalogue, or an
  empty one (a project that declares no event yet), checks nothing.

  A refusal is an ex-info whose data holds :rdm-spec.kernel.command/cannot-run
  (the command's way of saying a task cannot run), :event and :publisher.")

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn make-bus
  "A new bus. `opts` may hold :catalogue (the event catalogue, a map of event
  keyword to {:published-by :carries ...}) and :catalogue-file (how refusals
  name it)."
  ([] (make-bus {}))
  ([{:keys [catalogue catalogue-file]}]
   (atom {:catalogue (when (and (map? catalogue) (seq catalogue)) catalogue)
          :catalogue-file catalogue-file
          :subscribers []
          :events []})))

(defn subscribe!
  "Subscribes `handler` (a function of the payload) to `event` on behalf of
  `subscriber` (a component keyword). Handlers run in subscription order."
  [bus event subscriber handler]
  (swap! bus update :subscribers conj {:event event :subscriber subscriber :handler handler})
  nil)

(defn- refuse! [bus publisher event why]
  (let [{:keys [catalogue-file]} @bus]
    (throw (ex-info (str "the event " (kw-name event) " published by " (kw-name publisher) " " why
                         " the configured catalogue" (when catalogue-file (str " " catalogue-file)))
                    {:rdm-spec.kernel.command/cannot-run true
                     :event event :publisher publisher :catalogue catalogue-file}))))

(defn check-event
  "Refuses an event the bus's catalogue does not allow; nil when it does."
  [bus publisher event payload]
  (when-let [cat (:catalogue @bus)]
    (let [decl (get cat event)]
      (cond
        (nil? decl)
        (refuse! bus publisher event "is not declared in")

        (and (:published-by decl) (not= (:published-by decl) publisher))
        (refuse! bus publisher event (str "comes from the wrong publisher: " (kw-name (:published-by decl))
                                          " publishes it, according to"))

        :else
        (let [missing (remove #(contains? payload %) (:carries decl))]
          (when (seq missing)
            (refuse! bus publisher event (str "lacks the keys " (pr-str (vec missing)) " it carries according to")))))))
  nil)

(defn publish!
  "Publishes `event` from `publisher` with `payload`: checks it against the
  catalogue, records it, then delivers it to every subscriber of the event,
  in subscription order, before returning."
  [bus publisher event payload]
  (check-event bus publisher event payload)
  (swap! bus update :events conj {:event event :publisher publisher})
  (doseq [{:keys [handler] e :event} (:subscribers @bus)
          :when (= e event)]
    (handler payload))
  nil)

(defn events
  "Every event published on the bus, in order: [{:event :publisher}]."
  [bus]
  (vec (:events @bus)))

(defn format-event
  "How a run prints one event on the error stream."
  [{:keys [event publisher]}]
  (str "event: " (kw-name event) " published-by " (kw-name publisher)))
