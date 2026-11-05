<!-- to-the-living:start -->
Presentation.{ «Flow: messaging between flows» }

# Flow: messaging between flows

## Crystallization

### Proposal 1: Message sends through Flow

2026-10-11, `flows/875960/vision/flow.md`, STT: «we need the flow
nexus to be the component that delivers the payload because it owns
the lock. It can't really give a lock because then, even if it has a
timeout, there would be a problematic window when we can't rely on
two components sharing a lock.» Landed, vision-flow L22.

A metaflow: one address, { Aspect Topic Layer }, continued by one flow
after another. A request: one of the closed set of typed things sent
to a metaflow (an order, a question, the living's words, a result, a
notice). The messenger: messenger-clj, the `hm-*` commands, which
stand in for Message. Orchestrate: the Nexus whose Lock the messenger
reserves before it types into a pane.

Sources: `flows/875960/vision/flow.md`, 2026-10-11 and 2026-10-12
(landed, vision-flow L22); `flows/f5a6e9/vision/flow.md`, «Message
asks Flow for a time-bound lock» (the record the 2026-10-11 words
changed); `flows/8475a9/vision/flow.md`, 2026-10-06;
`flows/d68c82/vision/flow.md`, «The flow tool queues messages for a
flow in transition»; `flows/d68c82/vision/messaging.md`, 2026-10-12;
`flows/7c89aa/vision/flow-ids.md`, 2026-10-13.

File: `psyche-context/skills/vision-flow.md` (the landed line as
context; the figure proposed under it)

```text
  A request reaching a metaflow is one of a closed set of types, each with its waking effect: an order, a question, the living's words, a result, a notice. Message asks Flow to send a request to a metaflow; Flow owns the lock and the delivery, answers sent or queued, and Message subscribes to Flow's events, learning when a request has entered the flow's context. A metaflow idle is woken by a waking request and receives its whole queue, oldest first, in the first prompt; a metaflow under refresh queues what arrives until its successor has started.
+
+ +----------------------+
+ | Message              |
+ +----------------------+
+    | asks: Send.{ Address Request }
+    v
+ +----------------------+
+ | Flow, owning the lock|
+ +----------------------+
+    | answers: Sent, or Queued under refresh
+    | delivers, or queues until the successor
+    v
+ +----------------------+
+ | the metaflow's flow  |
+ +----------------------+
+    | enters the context: Delivered.Address
+    v
+ +----------------------+
+ | Message, subscribed  |
+ +----------------------+
```

File: `primary/config/main-flow/shared.edn` (inside its map; the
command name is the Nexus's own, the one bootstrap literal)

```clojure
+ :flow {:cli "flow"
+        :timeout-ms 15000}
```

File: `messenger-clj/src/messenger_clj/core.clj` (uncommitted: the
target is a metaflow only in the worktree; `config!`, `sender-route!`
and the `layer-route!` call build on «book 08» Proposal 4; the query
and reply names are the ethos's own, shown below; a refusal is the
type's name carrying its text, the datom form)

```clojure
- (defn send-request!
-   ([flow request wait-presented pane] (send-request! flow request wait-presented pane 10))
-   ([target request wait-presented pane hold-seconds]
-    (let [flow (target-flow! target) request (validate-request! request)]
-    (let [sender (sender-origin!)]
-      (with-reservation flow
-        (fn []
-          (assert-not-retired! flow)
-          (assert-target! target flow)
-          (let [raw-stored (try (load-route (registry) flow) (catch Exception _ nil))
-                stored (try (when raw-stored (route-binding! raw-stored)) (catch Exception _ nil))]
-            (when (in-transition? raw-stored) (held! flow :InTransition request stored))
-            (when (:route_hold raw-stored) (held! flow :RouteHold request stored))
-            (when (or (nil? stored) (needs-binding? stored))
-              (hold-repair-required! flow raw-stored pane request))
-            (let [route (try (refresh-route-snapshot stored (exact-live-agent stored))
-                             (catch Exception _ (hold-repair-required! flow raw-stored pane request)))
-                  live (try (verify-target! route)
-                            (catch Exception error
-                              (let [reason (held-reason error)]
-                                (if (contains? #{:Blocked :Uncertain} reason)
-                                  (held! flow reason request route)
-                                  (hold-repair-required! flow raw-stored pane request)))))
-                  route (refresh-route-snapshot route live)
-                  envelope (message-envelope sender request)
-                  _ (try (prompt-request! route envelope wait-presented)
-                         (catch Exception error
-                           (if (str/starts-with? (.getMessage error) "RelayOverflow:")
-                             (held! flow :RelayOverflow request route)
-                             (throw error))))
-                  submission (try (append-attempt! flow :Submitting :Uncertain route request envelope)
-                                  (catch Exception error
-                                    (if (:hm/invalid (ex-data error))
-                                      (held! flow :InvalidBinding request route)
-                                      (throw error))))
-                  grade (if wait-presented :Presented :Transported)]
-              (try
-                (let [waited? wait-presented
-                      reply (prompt!* (transport) route envelope waited?)]
-                  (when waited? (presented! route reply)))
-                (verify-target! route)
-                (record-sent! flow grade route submission live request envelope)
-                (catch Exception error
-                  (if (:hm/post-ledger (ex-data error))
-                    (throw error)
-                    (do (record-uncertain! flow route request envelope)
-                        (fail (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12)
-                                   " } prompt failed or is uncertain: " (.getMessage error)))))))))))))))
+ ;; Flow owns the lock and the delivery; the
+ ;; messenger neither reserves nor holds.
+ (defn flow! [query]
+   (let [cfg (:flow (config!))
+         opts {:out :string :err :string
+               :continue true
+               :timeout (:timeout-ms cfg)}
+         reply (*shell* opts (:cli cfg) query)]
+     (when-not (zero? (:exit reply))
+       (fail (str "FlowRefused.«"
+                  (str/trim (:err reply)) "»")))
+     (str/trim (:out reply))))
+
+ ;; The request's own datom: the variant is the
+ ;; type's name, the body its value.
+ (defn request-datom [request]
+   (let [{:keys [variant context body]} request]
+     (if context
+       (str (name variant) ".{ «" context "» «"
+            body "» }")
+       (str (name variant) ".«" body "»"))))
+
+ (defn send-request! [target request]
+   (let [request (validate-request! request)
+         from (sender-route!)
+         raw-stored
+         (try (load-route (registry) target)
+              (catch Exception _ nil))
+         _ (when raw-stored
+             (layer-route!
+              (:layer-order (config!))
+              from raw-stored))
+         query (str "Send.{ " target " "
+                    (request-datom request) " }")
+         reply (flow! query)]
+     (cond
+       (str/starts-with? reply "Sent")
+       (do (append-attempt! target :sent :Sent
+                            nil request nil)
+           reply)
+       (str/starts-with? reply "Queued")
+       (do (append-attempt! target :queued :Queued
+                            nil request nil)
+           reply)
+       :else
+       (fail (str "SendUnanswered.«" reply "»")))))
+
+ ;; One Delivered frame, when the request has
+ ;; entered the metaflow's context.
+ (defn await-delivered! [target]
+   (let [reply (flow! (str "Subscribe." target))]
+     (when-not (str/starts-with? reply "Delivered")
+       (fail (str "NotDelivered.«" reply "»")))
+     reply))
```

```clojure
- (defn send!
-   ([flow body wait-presented pane] (send-request! flow {:variant :msg :body body} wait-presented pane))
-   ([flow body wait-presented pane hold-seconds]
-    (send-request! flow {:variant :msg :body body} wait-presented pane hold-seconds)))
- (defn send-psyche!
-   ([flow context verbatim wait-presented pane]
-    (send-psyche! flow context verbatim wait-presented pane 10))
-   ([flow context verbatim wait-presented pane hold-seconds]
-    (send-request! flow {:variant :psyche :context context :body verbatim}
-                   wait-presented pane hold-seconds)))
- (defn send-psyches!
-   ([flow input wait-presented pane]
-    (send-psyches! flow input wait-presented pane 10))
-   ([flow input wait-presented pane hold-seconds]
-    (send-request! flow {:variant :psyches :body input}
-                   wait-presented pane hold-seconds)))
+ (defn send! [target body]
+   (send-request! target {:variant :msg
+                          :body body}))
+ (defn send-psyche! [target context verbatim]
+   (send-request! target {:variant :psyche
+                          :context context
+                          :body verbatim}))
+ (defn send-psyches! [target input]
+   (send-request! target {:variant :psyches
+                          :body input}))
```

File: `messenger-clj/src/messenger_clj/core.clj` (`delivery-attempt!`
refuses a reason or grade outside these sets)

```clojure
- (def failure-reasons #{:NotRegistered :NeedsBinding :InTransition :RouteHold :IdentityChanged :PaneMissing :Blocked :ProcessMismatch :Stalled :Uncertain :RelayOverflow :Submitting :RepairCandidate :RepairRequired :RouteRepaired :InvalidBinding :sent})
- (def delivery-grades #{:Transported :Presented :Fallback-Presented :Repaired :Held :Uncertain})
+ (def failure-reasons
+   #{:NotRegistered :NeedsBinding :InTransition
+     :RouteHold :IdentityChanged :PaneMissing
+     :Blocked :ProcessMismatch :Stalled :Uncertain
+     :RelayOverflow :Submitting :RepairCandidate
+     :RepairRequired :RouteRepaired :InvalidBinding
+     :sent :queued})
+ (def delivery-grades
+   #{:Transported :Presented :Fallback-Presented
+     :Repaired :Held :Uncertain :Sent :Queued})
```

File: `messenger-clj/src/messenger_clj/core.clj` (under choice 5 and
choice 6)

```clojure
- (def Reservation [:map {:closed true} [:id :int] [:flow FlowId] [:root :string]])
```

```clojure
- (def ^:dynamic *reservation-wait-ms* 15000)
- (def ^:dynamic *reservation-retry-ms* 50)
- ;; A test seam around the Orchestrate boundary.  Production always uses
- ;; `with-reservation` below; tests supply a short-lived in-memory lease.
- (def ^:dynamic *with-reservation* nil)
```

```clojure
- (defn reserve! [flow]
-   (let [owner (flow-id! (or *flow-id* (System/getenv "FLOW_ID") flow))
-         operation (str "MessengerCljDelivery-"
-                        (str/replace (str (java.util.UUID/randomUUID)) "-" ""))
-         query (str "Lock.{ " operation " " owner " [ " (quote-datom (root))
-                    " ] «Register or submit through Herdr» }")
-         deadline (+ (System/nanoTime) (* 1000000 (long *reservation-wait-ms*)))]
-     (loop []
-       (let [reply (*shell* {:out :string :err :string :continue true :timeout 15000}
-                            "orchestrate" query)
-             match (re-find #"Locked\.\{\s+(\d+)\b" (:out reply))]
-         (cond
-           (and (zero? (:exit reply)) match)
-           (valid! Reservation {:id (parse-long (second match)) :flow flow :root (str (root))}
-                   "Reservation")
- 
-           (and (contention? reply) (< (System/nanoTime) deadline))
-           (do (Thread/sleep (long *reservation-retry-ms*)) (recur))
- 
-           (contention? reply)
-           (fail (str "Reservation timed out after " *reservation-wait-ms* "ms: "
-                      (str/trim (or (not-empty (:out reply)) (:err reply) ""))))
- 
-           :else
-           (fail (str "Reservation refused: "
-                      (str/trim (or (not-empty (:out reply)) (:err reply) "")))))))))
- (defn release! [reservation]
-   (let [reply (*shell* {:out :string :err :string :continue true :timeout 15000} "orchestrate" (str "Release." (:id reservation)))]
-     (when-not (and (zero? (:exit reply)) (str/starts-with? (:out reply) "Released."))
-       (fail (str "Reservation release failed: " (str/trim (or (not-empty (:out reply)) (:err reply) "")))))))
- (defn with-reservation [flow f]
-   (if *with-reservation*
-     (*with-reservation* flow f)
-     (let [reservation (reserve! flow)]
-       (try (f) (finally (release! reservation))))))
```

File: `messenger-clj/src/messenger_clj/core.clj`, `repair!` (under
choice 5 alone; its held intents are binding repairs, which Flow's
queue does not carry)

```clojure
-   (with-reservation flow
-     (fn []
+   (locking repair-lock
+     (do
```

```clojure
+ (def repair-lock (Object.))
```

File: `signal-flow/ethos/signal.ethos` (uncommitted; the queries)
Ethos, the Ethos metaflow's. Apart.

```ethos
-   Lock.{ Sender Recipient }
    Bind.{ Address Process }
    Identify.Process
-   Deliver.{ Lock Request }
-   Release.Lock
+   Send.{ Address Request }
+   Subscribe.Address
```

File: `signal-flow/ethos/signal.ethos` (uncommitted; the responses)
Ethos, the Ethos metaflow's. Apart.

```ethos
-   Locked.Lock
    Bound.FlowId
    Identified.Address
-   Delivered
+   Sent
+   Delivered.Address
    Queued
    Woken.FlowId
-   Released
```

File: `signal-flow/ethos/signal.ethos` (uncommitted; the refusals
of a lock on the wire)
Ethos, the Ethos metaflow's. Apart.

```ethos
    Refusal.[ Locked
-             Held.Lock
              Awake.FlowId
              Asleep
-             Lapsed
-             Unknown.[ Address.Address Lock.Lock FlowId.FlowId Key.Key ]
+             Unknown.[ Address.Address
+                       FlowId.FlowId
+                       Key.Key ]
```

File: `signal-flow/ethos/library.ethos` (uncommitted; the lock stays
Flow's, in its Memory, with no shared timeout)
Ethos, the Ethos metaflow's. Apart.

```ethos
-   Lock.{ Sender Address Until.Integer }
+   Lock.{ Sender Address }
```

File: `meta-signal-flow/ethos/signal.ethos` (the metaflow is
addressed, never a flow)
Ethos, the Ethos metaflow's. Apart.

```ethos
-   DeliveryRequest.{ DeliveryId FlowId Message }
+   DeliveryRequest.{ DeliveryId Address Request }
```

Ruling, one number from each group:

The figure and the code:
1. Land.
2. Land with changes the living names.
3. Drop.

The messenger's own lock and its held ledger. His words: 2026-10-11,
`flows/875960/vision/flow.md`, STT: «If message wants to send a
message, it communicates to flow and flow would take care of the
mechanics and then tell message the status.»; «Let's say the flow is
being refreshed, so the messages are queued for when the flow has
restarted.» The record those words changed, `flows/f5a6e9/vision/flow.md`:
«I don't think that it's Flow's job to hold messages.» None of his
words names the messenger's repair of a pane; the three choices are
the machine's:
4. The Orchestrate reservation kept for `repair!` alone, as the file
   stands, machine choice: a repair rebinds a pane and delivers
   nothing.
5. The reservation removed whole, `repair!` under a process lock, as
   shown, machine choice: the lock is Flow's, and the messenger has
   none of its own.
6. The held and repair ledger leaves the messenger with the
   reservation: Flow's Queued answer is the only holding, machine
   choice: a held send was a delivery the messenger could not make,
   and the messenger no longer delivers:

File: `messenger-clj/src/messenger_clj/core.clj` (each removed whole;
the first line of each shown; `repair-lock` above not added)

```clojure
- (defn hold-repair-required! [flow stored pane request]
```

```clojure
- (defn held! [flow reason request route]
```

```clojure
- (defn repair! [flow pending-id session pane-id terminal-id name agent-kind]
```

File: `messenger-clj/src/messenger_clj/core.clj`, the two sets above:

```clojure
  (def failure-reasons
-   #{:NotRegistered :NeedsBinding :InTransition
-     :RouteHold :IdentityChanged :PaneMissing
-     :Blocked :ProcessMismatch :Stalled :Uncertain
-     :RelayOverflow :Submitting :RepairCandidate
-     :RepairRequired :RouteRepaired :InvalidBinding
-     :sent :queued})
+   #{:NotRegistered :sent :queued})
  (def delivery-grades
-   #{:Transported :Presented :Fallback-Presented
-     :Repaired :Held :Uncertain :Sent :Queued})
+   #{:Sent :Queued})
```

The flow id in Flow's answers. His words: 2026-10-13,
`flows/7c89aa/vision/flow-ids.md`, STT: «I want flow IDs to almost
disappear from the chat.»; 2026-10-12, `flows/d68c82/vision/messaging.md`,
STT: «I've asked for the flow ID of the sender to be replaced by the
actual aspect (like how we name metaflows: aspect, topic, and then
layer). That's how I want flows to stop referring to each other by
flow IDs»; 2026-10-11, `flows/875960/vision/flow.md`, STT: «We've
been using the session ID hash so that we can find a transcript
easily from the flow ID.»
7. `Sent` bare and `Delivered.Address`, as shown: the answers name
   the metaflow and no flow, the machine reading of 2026-10-12 and
   2026-10-13.
8. `Delivered` carries the flow whose context the request entered,
   so its transcript is found, the machine reading of 2026-10-11:

File: `signal-flow/ethos/signal.ethos` (the responses above)
Ethos, the Ethos metaflow's. Apart.

```ethos
-   Delivered.Address
+   Delivered.{ Address FlowId }
```

### Proposal 2: two live flows of one metaflow

2026-09-24, `flows/d8df70/vision/flowLifecycle.md`, STT: «You don't
know what I said to him and he doesn't know what I told you so you're
splitting. That's really bad because now my psyche is going all over
the place.»

Sources: `flows/d8df70/vision/flowLifecycle.md`, 2026-09-24;
`flows/fe945a/vision/flowLifecycle.md`, 2026-09-29 (landed,
vision-flow L52–54); `flows/fd0f97/vision/flowLifecycle.md`, «The
psyche's messages are not being passed along the cluster» (landed,
L12); `flows/e1953c/vision/flowLifecycle.md`, «Messages pass to both
flows until the old flow logs out to the new one».

File: `psyche-context/skills/vision-flow.md` (builds on «book 06»
Proposal 6; after its line)

```text
  Two flows of one metaflow share everything with each other, and one cedes.
+ Two of them live at once split the psyche: what is said to one, the other does not know.
```

File: `psyche-context/skills/vision-flow.md` (the hooks sentence
stands twice, L56 and L58; the second removed)

```text
- A flow is refreshed when its context passes the layer's threshold: it is told, writes its handover, and is replaced as it goes idle; while the refresh runs the metaflow is locked, and the predecessor is reaped in the same event. Every harness event reaches Flow through its hooks calling the Flow CLI, which identifies the calling process.
+ A flow is refreshed when its context passes the layer's threshold: it is told, writes its handover, and is replaced as it goes idle; while the refresh runs the metaflow is locked, and the predecessor is reaped in the same event.
```

Ruling, one number from each group:

The line:
1. Land.
2. Land with changes the living names.
3. Drop.

The duplicate; no word of his on this fork:
4. L58's sentence removed, as shown, machine choice: L56 carries the
   hooks sentence whole, and «identified by the calling process»
   stands at L20.
5. L56's first sentence removed instead, machine choice: L58's is the
   shorter:

File: `psyche-context/skills/vision-flow.md` (under choice 5, L56)

```text
- Every harness event reaches Flow through the harness's hooks calling the Flow CLI, so Flow knows each flow's state without polling; a marked block landing in a transcript becomes an action, a book among them, with no tool call by the flow; a flow nearing its context limit is told, writes its handover, and is refreshed as it goes idle. Polling is forbidden; a poller that must exist is registered and reported.
+ Flow knows each flow's state without polling; a marked block landing in a transcript becomes an action, a book among them, with no tool call by the flow; a flow nearing its context limit is told, writes its handover, and is refreshed as it goes idle. Polling is forbidden; a poller that must exist is registered and reported.
```

What the replaced flow receives. His words: `flows/e1953c/vision/flowLifecycle.md`,
STT: «messages essentially get passed on to both the new and the old
flow until the old flow logs out to the new flow.»; 2026-09-24,
`flows/d8df70/vision/flowLifecycle.md`, STT: «A seat starts
receiving. The old seat stops receiving first. That's how we get a
lock.» The second stands at L52–54; the order of the choices is the
machine's reading:
6. The old flow stops receiving first: L52–54 stand, no line
   changes.
7. Both receive until the old logs out to the new:

File: `psyche-context/skills/vision-flow.md` (L52–54)

```text
- Reaping belongs to the refresh event, not to a later sweep. A refreshed flow
- takes the replaced end out of receiving messages, so a dead end is never
- left registered and addressable.
+ Reaping belongs to the refresh event, not to a later sweep. Until the replaced flow logs out to its successor, a request reaches both; then the replaced end is taken out of receiving, so a dead end is never left registered and addressable.
```

### Proposal 3: a flow is a session; a restart is a new flow; not working is idle

2026-10-03, `flows/9fb0ad/vision/flowLifecycle.md`, typed: «Yeah
Flow is obviously a session so when we restart the whole context,
that's a new Flow. It's a different context.»; «when the flow isn't
working then it's idle, isn't it?»

Sources: `flows/9fb0ad/vision/flowLifecycle.md`, 2026-10-03;
`flows/875960/vision/flow.md`, 2026-10-11.

File: `psyche-context/skills/vision-flow.md` (after the metaflow
paragraph, L16)

```text
  Flow manages metaflows. A metaflow is one address, { Aspect Topic Layer }, that continues through several flows one after another; the flow is its current run, and what is addressed is always the metaflow, never a flow. The default topic is core: the permanent topic, the thing that keeps the machine going, the last topic to stand.
+
+ A flow is a session: when the whole context is restarted, that is a new flow, a different context; the harness itself has the concept, and exposes it as the flow's id. A flow that is not working is idle.
```

File: `psyche-context/skills/vision-flow.md` (under choice 4, L12;
«book 09» Proposal 8 writes «one run of a metaflow» at this line)

```text
- Flow is the Nexus that manages flows. A flow is one run of a voice; the component is named for what it manages. A flow the living asks for is launched, properly and surely. Flow holds the lock on flows.
+ Flow is the Nexus that manages flows. A flow is one session of a metaflow; the component is named for what it manages. A flow the living asks for is launched, properly and surely. Flow holds the lock on flows.
```

Ruling, one number from each group:

The paragraph:
1. Land.
2. Land with changes the living names.
3. Drop.

«one run of a voice» at L12. His words: 2026-10-11,
`flows/875960/vision/flow.md`, STT: «No we've gotten rid of the voice
concept. Now it's just replaced by the core topic.»
4. «one session of a metaflow», as shown, the machine reading of the
   2026-10-03 and 2026-10-11 words together.
5. L12 stands.

### Proposal 4: speech across layers, guidance or hard-enforced

His words on each side. 2026-10-03, `flows/9fb0ad/vision/speech.md`,
typed: «It's not a hard rule; it's guidance. Of course there may be an
exception but it should be rare.» (the same words stand in
`flows/5578cc/vision/flow.md`, 2026-10-03); the same day, 15:18Z:
«it should be very rare for Field to talk to Psyche directly. It
should go through Mind, really, because Mind holds the knowledge.»

2026-10-07, `flows/d4ae97/vision/flow.md`, line 137, STT: «You're
going to have to talk to a secretary because you're secondary. I want
that hard-enforced. I guess this is basic intent, right, because it's
not spirit.»; «because the messaging program is imperfect and it
cannot actually enforce this.»; «he cannot message from tertiary to
primary or from tertiary to secondary of another aspect, right? They
have to either go horizontally, [one] level up, or any level down and
across».

Machine facts: no raw record of his words says «Flow refuses».
vision-flow L34 was landed by psyche-context commit b95e977
(2026-10-09) from `flows/f5a6e9/books/18-speech-across-aspects.md`,
Ruling 1, lines 28–29, which `flows/445410/log.md` line 137 records
as approved whole on 2026-10-09; that is a log claim. The intent line
is «book 08» Proposal 4's, unlanded.

Sources: `flows/9fb0ad/vision/speech.md`, 2026-10-03;
`flows/d4ae97/vision/flow.md`, 2026-10-07.

File: `psyche-context/skills/vision-flow.md` (L34; under choices 2
and 3)

```text
- Who speaks to whom, within an aspect and across aspects at the same layer in a shared topic, is the vision-aspects skill's. Flow refuses a request that leaves those routes.
+ Who speaks to whom, within an aspect and across aspects at the same layer in a shared topic, is the vision-aspects skill's. Those routes are guidance, not a hard rule: a request up carries a good reason, and an exception is rare.
```

File: `psyche-context/skills/intent-main-flow.md` (builds on «book
08» Proposal 4; under choices 2 and 3)

```text
- You speak across at your own layer, one layer up in your own aspect, or to any layer below, in any aspect. A Secondary is its Primary's secretary: it keeps track of what the Primary works on, and what climbs from below reaches the Primary through it. A Primary designs and implements through its own subflows. Below the Primary, you ask the layer above for authority and a mandate when you are not sure, and you say that you do not know: the layer above infers better than you.
+ You speak across at your own layer, one layer up in your own aspect, or to any layer below, in any aspect; a message up carries a good reason, and an exception to these routes is rare. A Secondary is its Primary's secretary: it keeps track of what the Primary works on, and what climbs from below reaches the Primary through it. A Primary designs and implements through its own subflows. Below the Primary, you ask the layer above for authority and a mandate when you are not sure, and you say that you do not know: the layer above infers better than you.
```

File: `messenger-clj/src/messenger_clj/core.clj` (builds on «book 08»
Proposal 4, `layer-route!`; under choice 2: the check is an evidence
line on the sender's error stream, named as the ethos names the
refusal)

```clojure
-       (fail (str "LayerRouteRefused.{ «"
-                  (metaflow! from) "» «"
-                  (metaflow! to) "» }")))))
+       (binding [*out* *err*]
+         (println (str "OffRoute.{ «"
+                       (metaflow! from) "» «"
+                       (metaflow! to) "» }"))))))
```

File: `messenger-clj/src/messenger_clj/core.clj` (builds on «book 08»
Proposal 4; under choice 3, the check and its call removed)

```clojure
- ;; Down and across is free; up is one layer, in
- ;; the sender's own aspect.
- (defn layer-route! [order from to]
-   (let [a (rank order (:layer from))
-         b (rank order (:layer to))]
-     (when-not (or (>= b a)
-                   (and (= b (dec a))
-                        (= (:aspect from)
-                           (:aspect to))))
-       (fail (str "LayerRouteRefused.{ «"
-                  (metaflow! from) "» «"
-                  (metaflow! to) "» }")))))
```

```clojure
-                    (catch Exception _ nil))
-               _ (when raw-stored
-                   (layer-route!
-                    (:layer-order (config!))
-                    from raw-stored))]
+                    (catch Exception _ nil))]
```

File: `signal-flow/ethos/signal.ethos` (uncommitted; under choice 3)
Ethos, the Ethos metaflow's. Apart.

```ethos
-             OffRoute
```

Ruling, one number; the order of the choices is the machine's
reading, the 2026-10-07 words taken as the later on the same route:

1. Hard-enforced, his 2026-10-07 words: L34 stands, the
   vision-model-roles lines of «book 08» Proposal 4 stand, its
   `layer-route!` check is kept; no line changes.
2. Guidance, his 2026-10-03 words: L34 and the intent line as shown,
   the messenger's check reduced to the `OffRoute` evidence line.
3. Guidance, the check removed: L34 and the intent line as under
   choice 2, `layer-route!` and its call removed, `OffRoute` out of
   the ethos.

### Proposal 5: deterministic work is done by code

2026-10-03, `flows/5578cc/vision/flow.md`, typed: «Let's make this so
we need something developed into intent: that we intend to do
anything that is deterministic into code, to save the context, cost,
and noise that making an LLM do it would incur.»; «there's no reason
for us to make the model check the flow ID. It should get it in its
prompt». The same words stand in
`flows/9fb0ad/vision/deterministicWork.md`.

Sources: `flows/5578cc/vision/flow.md`, 2026-10-03;
`flows/875960/vision/flow.md`, 2026-10-11 («no claimed [ID]», landed,
vision-flow L20).

File: `psyche-context/skills/intent-deterministic-work.md` (a new
file; the name is the machine's, choice 4; the main flow's line on
the flow id is «book 07» Proposal 2's)

```text
+ ---
+ description: A model is about to do, or is told to do, what a deterministic program could do.
+ dependencies: []
+ ---
+
+ Anything deterministic is done in code, to save the context, cost and noise that making a model do it would incur. A model does not check its flow id: it gets it in its prompt.
```

Ruling, one number from each group:

The intent module:
1. Land.
2. Land with changes the living names.
3. Drop.

The module's name and its sentence; no word of his names the module:
4. `intent-deterministic-work`, the machine's paraphrase as shown,
   machine choice: the topic noun of the record
   (`deterministicWork`).
5. His own sentence as the module's line, the name drawn from his
   «deterministic into code»:

File: `psyche-context/skills/intent-deterministic-into-code.md` (in
place of the file above)

```text
+ ---
+ description: A model is about to do, or is told to do, what a deterministic program could do.
+ dependencies: []
+ ---
+
+ We intend to do anything that is deterministic into code, to save the context, cost, and noise that making an LLM do it would incur. The model does not check the flow ID: it gets it in its prompt.
```

### Proposal 6: a flow knows what the flows concerned know

2026-08-19, `flows/e06e4c07/vision/flowKnowledge.md`, typed: «Id
rather the discussion drip into every flow which it concerns. I want
this flow to know what the other knows and vice versa.»; «It should
be a skill, or maybe even a specialized subagent role which looks at
recent sessions for psyche rulings and insights/information which
directly impact its parent flow.»

A session file: the harness's transcript of one flow, found from the
flow id.

Sources: `flows/e06e4c07/vision/flowKnowledge.md`, 2026-08-19, three
records; `flows/d68c82/vision/messaging.md`, 2026-10-12.

File: `psyche-context/skills/vision-remembering.md` (a new section
after «The remembering line says what was found most relevant»; under
choice 4)

```text
  The log's record of a remembering carries a short description of what
  from the remembered flow was found most relevant to the current one.
+
+ ## A flow knows what the flows concerned know
+
+ A discussion drips into every flow it concerns: a flow knows what the other knows, and vice versa. The sharing is a skill, or a specialized subagent role that reads recent sessions for psyche rulings and insights that directly impact its parent flow; session files are made accessible and searchable for agents.
+
+ +----------------------+
+ | parent flow          |
+ +----------------------+
+    | launches, naming its topic
+    v
+ +----------------------+
+ | session reader       |
+ +----------------------+
+    | reads, recent first
+    v
+ +----------------------+
+ | session files        |
+ +----------------------+
+    | yield what impacts the parent
+    v
+ +----------------------+
+ | parent flow          |
+ +----------------------+
```

File: `field-context/subagent/sessionReader.md` (the subagent role's
module: a machine module, in the subagent section of «book 09»
Proposal 3, Field's under its choice 7; its text is the machine's,
landed by its owner outside this book; the psyche line it rests on is
the section above)

File: `primary/config/main-flow/psyche.edn` (builds on «book 09»
Proposal 3; beside `scripter`, the layer as there)

```clojure
+            :session-reader
+            {:layer :Tertiary}
```

Ruling, one number from each group:

The section, the figure and the subagent's line:
1. Land.
2. Land with changes the living names.
3. Drop.

Where the section lands; no word of his on this fork:
4. vision-remembering, as shown, machine choice: reading another
   flow's transcript is remembering's (L11).
5. vision-flow, after L22, machine choice: the subagent role is a
   subflow Flow launches:

File: `psyche-context/skills/vision-flow.md` (under choice 5, after
L22)

```text
  A request reaching a metaflow is one of a closed set of types, each with its waking effect: an order, a question, the living's words, a result, a notice. Message asks Flow to send a request to a metaflow; Flow owns the lock and the delivery, answers sent or queued, and Message subscribes to Flow's events, learning when a request has entered the flow's context. A metaflow idle is woken by a waking request and receives its whole queue, oldest first, in the first prompt; a metaflow under refresh queues what arrives until its successor has started.
+
+ A discussion drips into every flow it concerns: a flow knows what the other knows, and vice versa. The sharing is a skill, or a specialized subagent role that reads recent sessions for psyche rulings and insights that directly impact its parent flow; session files are made accessible and searchable for agents.
```

The third record, 2026-08-19, `flows/e06e4c07/vision/flowKnowledge.md`,
typed: «obviously another nexus. But we might want a small clever
tool to help search those files more efficiently for now.»; «finding
the user typed prompts is an obvious first step. then we would need
the few preceding model responses, to give those prompts context. and
the result would have to contain line numbers, to allow a more
fine-grained search to proceed after the bulk of the gold has been
found.» Both choices the machine's:
6. It rides with this proposal, a paragraph after the section's
   first:

File: `psyche-context/skills/vision-remembering.md` (the section
above, under choice 4; under choice 5 the same after L22)

```text
+ The session files belong to another Nexus than Flow. Until it stands, a small search tool finds the typed prompts first, then the few model responses preceding each, and gives line numbers, so that a finer search follows the bulk.
```

7. It waits for the Nexus the session files belong to; no line.

Reading sessions beside messaging. His words: 2026-10-12,
`flows/d68c82/vision/messaging.md`, STT: «Let's work on some skills
and instructions also to minimize the amount of messaging between
flows and maximize their utility.» Both choices the machine's:
8. The session reader stands beside messaging as shown: a flow reads
   what concerns it, and no message is sent for it; no line.
9. The section says so:

File: `psyche-context/skills/vision-remembering.md` (the section
above; a sentence at the end of its first paragraph)

```text
- A discussion drips into every flow it concerns: a flow knows what the other knows, and vice versa. The sharing is a skill, or a specialized subagent role that reads recent sessions for psyche rulings and insights that directly impact its parent flow; session files are made accessible and searchable for agents.
+ A discussion drips into every flow it concerns: a flow knows what the other knows, and vice versa. The sharing is a skill, or a specialized subagent role that reads recent sessions for psyche rulings and insights that directly impact its parent flow; session files are made accessible and searchable for agents. What a flow reads from the sessions is not messaged to it: the reading takes the message's place.
```
<!-- to-the-living:end -->
