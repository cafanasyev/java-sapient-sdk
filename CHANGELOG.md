## 1 — IClient redesign — typed SapientMessage transport

### Problem

`IClient` extended `Runnable` and operated on raw `ByteBuffer`. This created two issues:

1. **Lifecycle mismatch with gRPC.** `NodeDispatcher.run()` called `client.run()` as a blocking loop,
   which works for TCP reconnect cycles but is unnatural for a `ManagedChannel`. The goal is to support
   both raw TCP and gRPC as interchangeable `IClient` implementations.

2. **False abstraction.** `SocketClient` already embedded SAPIENT-specific framing (4-byte little-endian
   length prefix per BSI Flex 335 v2.0 §4.2), making the `ByteBuffer` interface a leaking abstraction.
   `NodeDispatcher` also serialized/deserialized `SapientMessage` manually on every publish and receive.

### Decision

- **Remove `Runnable` from `IClient`.** Replace with a non-blocking `start()` method. Each implementation
  manages its own connection lifecycle internally. `start()` may be called again after `close()` to
  reconnect — required by the SAPIENT protocol, which expects a Registration message shortly after
  connection and will close idle connections.

- **Type `IClient` with `SapientMessage`** instead of `ByteBuffer`. This is an honest representation:
  the SDK is SAPIENT-specific and will never need a generic TCP transport. `SocketClient` owns
  serialization and framing internally; `GrpcClient` will own native gRPC transport. `NodeDispatcher`
  works with typed messages throughout with no manual serialize/deserialize.

### Result

`IClient` becomes a 3-method interface: `start()`, `publish(SapientMessage, Duration)`,
`subscribe(Consumer<SapientMessage>)`, plus `close()` from `AutoCloseable`.

---

## 2 — Connection status monitoring

### Problem

There was no way to observe whether a client was connected or disconnected, and no way to check
whether a remote endpoint was reachable — short of attempting a publish and waiting for a timeout.
This made it impossible to build a meaningful health indicator or UI status display without
coupling to internal state.

Additionally, `ISocketProvider` was a `@FunctionalInterface` that hid the host and port inside a
lambda, making the client unable to perform a reachability probe independently of the managed
connection.

### Decision

- **Introduce `ConnectionState` enum** with four values: `DISCONNECTED`, `CONNECTING`, `CONNECTED`,
  `CLOSED`. This covers the full lifecycle and maps cleanly to gRPC's `ConnectivityState` for the
  future `GrpcClient` implementation.

- **Extend `IClient`** with: `getState()`, a `default isConnected()` convenience shorthand,
  `addStateChangeListener(BiConsumer<ConnectionState, Instant>)`, `removeStateChangeListener(...)`,
  and `probeReachable(Duration)`. Multiple listeners are supported; each implementation must protect
  its run loop from listener exceptions. The listener receives both the new state and the
  `Instant` at which the transition occurred, captured once before notifying all registered
  listeners so every listener sees a consistent timestamp for the same event.

- **Enrich `ISocketProvider`** with `host()` and `port()` methods. No longer a `@FunctionalInterface`.
  Carrying the address explicitly allows `SocketClient` to open a raw probe socket without touching
  the managed connection.

- **`SocketClient` state machine**: `runLoop` sets `CONNECTING` before each connection attempt,
  `CONNECTED` after a successful `connect()`, `DISCONNECTED` when returning to the retry loop, and
  `CLOSED` on `close()`. Listeners are held in an `ArrayList<BiConsumer<ConnectionState, Instant>>`
  protected by a `ReentrantReadWriteLock` — read lock during notification, write lock for
  registration changes. The transition timestamp is captured with `Instant.now()` once per
  `setState` call and passed uniformly to every listener.

- **`probeReachable(Duration)`** opens a plain `Socket` to `host:port` and closes it immediately —
  equivalent to `nc -z host port`. Returns `true` on success, `false` on any `IOException`.

### Result

`IClient` is now a fully observable state machine. Callers can poll `getState()` or register
`BiConsumer<ConnectionState, Instant>` listeners to react to transitions with a precise event
timestamp. `probeReachable(Duration)` provides a transport-agnostic TCP-level health check with
no ICMP dependency.

---

## 3 — re-registration after prolonged connection loss

### Problem

BSI Flex 335 v2.0 §4.9 requires re-sending a registration message if reconnection occurs more than
2 minutes after the node noticed loss of connection. The server's complementary behaviour is: close
the TCP connection after 3 consecutive missed status reports, then retain the registration state for
2 minutes from that point.

`NodeWrapper` never re-registered after a reconnect. The status loop caught `TimeoutException`
internally and kept retrying indefinitely, so even a multi-hour TCP outage would result in status
reporting silently resuming without re-registration once the connection came back.

Two distinct failure modes require re-registration:

1. **Process suspension** (laptop sleep, container pause). Threads freeze, no status reports are
   delivered, and the wall clock advances. On wake, the server has already closed the TCP
   connection; the OS buffers the server's FIN, so `readLoop` gets an EOF immediately. Measuring
   the `DISCONNECTED→CONNECTED` gap yields seconds regardless of how long the process was
   suspended — masking an outage of arbitrary length.

2. **Server crash with TCP reconnect within the grace period.** The server drops all registrations
   immediately on crash. If the server restarts quickly (e.g. 2 minutes 1 second), the TCP
   reconnect succeeds but the server has no memory of the node. The time-based check alone cannot
   detect this: a 2m1s gap with a 10-second status interval gives `serverRetention = 2m30s`, so
   the threshold is not yet crossed and status reports resume to a server that has no registration
   for the node.

### Decision

- **Add `reconnectGracePeriod`** to `NodeDispatcherConfig` (default `Duration.ofMinutes(2)`).
  Making it configurable keeps tests fast without hardcoding protocol constants.

- **Derive `serverRetention`** from the negotiated status interval:
  `3 × statusInterval + reconnectGracePeriod`. `3 × statusInterval` is when the server closes
  the TCP connection; `reconnectGracePeriod` is how long the server retains the registration after
  closing. Any gap past `serverRetention` from the last confirmed status report means the server
  has certainly expired the session — covers process-suspension scenarios where no TCP disconnect
  event fires.

- **Detect grace-expired reconnects at the dispatcher**, using a single `IClient` state listener
  shared across all nodes. On each `DISCONNECTED → CONNECTED` transition the gap is compared
  against `reconnectGracePeriod`; a qualifying reconnect increments a shared `reregistrationEpoch`
  counter in `NodeDispatcher`. Each node captures the epoch at registration time; if the epoch
  has advanced when the status loop checks, the server has certainly purged the registration and
  re-registration is triggered. Covers server crashes with fast restarts where the time-based
  check alone would not fire.

- **Apply a 95% tolerance to both period thresholds.** Scheduler jitter and GC pauses of a few
  milliseconds can otherwise hide a real outage right at the boundary. Firing slightly early
  costs a redundant registration; firing slightly late risks sending status reports to a server
  that has already forgotten the node.

- **Drive the node lifecycle from a single loop in `NodeWrapper`** with two phases: register, then
  run the status loop until it requests re-registration or termination. A rejected registration
  backs off and retries without taking the node offline; either re-registration signal
  (grace-expired reconnect or `serverRetention` exceeded) returns to the register phase.

### Result

Re-registration is triggered by two complementary signals. The TCP reconnect check catches server
crashes where the connection drops and comes back after the grace period expires — even if the
total outage is short enough that the time-based threshold would not fire. The time-based check
covers half-open connections and process suspensions where no disconnect event is raised. Both
checks share a single `registrationEpoch` anchor per registration cycle and require no per-node
listener or cross-cutting state beyond one `AtomicLong` in `NodeDispatcher`.

---

## 4 — Accounting for connection-loss detection delay

### Problem

Both re-registration checks from §3 assume the client notices loss of connection instantly. It
doesn't. On a half-open socket (server silently gone, no FIN), `readLoop` stays blocked inside
`InputStream.read()` until something wakes it up. Until it wakes, the dispatcher sees the
connection as `CONNECTED` and the status loop keeps "publishing" into a dead TCP send buffer —
the writes succeed locally but the server has already purged the registration.

The obvious wake-up mechanism is `SO_TIMEOUT`. In practice it is not reliable enough to anchor
the design on:

- On `SSLSocket`, `SO_TIMEOUT` applies to the underlying raw socket, not the TLS record stream.
  Partial TLS records can keep the read in native code long past the configured timeout.
- `InputStream.read()` honours `SO_TIMEOUT` per call, not per logical message. `readFully` loops
  `read()` until all bytes arrive, so a slow trickle of bytes resets the timer on every call and
  the effective timeout becomes unbounded.
- Empirically, with `SO_TIMEOUT = 10s` the loop sometimes only woke up after 30–40s, which is
  enough to push both re-registration checks past their respective windows and either drop a
  real outage signal or miscalibrate the server-retention deadline.

The two re-registration checks both under-counted this gap:

- **Dispatcher-side (`DISCONNECTED → CONNECTED` gap)**: the measured gap starts from when the
  client emitted `DISCONNECTED`, not from when the network actually failed. A real 2m1s outage
  with a detection delay of 5s looks like a 1m56s gap — below the 2-minute grace, so no epoch
  bump.

- **Node-side (`serverRetention` deadline)**: `3 × statusInterval + reconnectGracePeriod` is
  measured from the last *attempted* status report. Some of those attempts went to a dead socket
  that the client hadn't yet marked dead, so the effective deadline was later than the server's
  actual retention window.

### Decision

- **Detect liveness from a dedicated watchdog, not from `readLoop`.** `SocketClient` starts a
  virtual-thread watchdog for every successful connection. The watchdog wakes at a fixed
  `watchdogInterval`, runs `probeReachable(probeTimeout)`, and — on probe failure — closes the
  underlying socket. Closing the socket unblocks the pending `InputStream.read()` with an
  `IOException`, which surfaces to `runLoop` and drives the `DISCONNECTED` transition through the
  existing path. Detection is bounded by `watchdogInterval + probeTimeout` regardless of whether
  `SO_TIMEOUT` is set, whether the socket is plain or TLS, or whether bytes are trickling in. The
  watchdog is interrupted when `runLoop` exits its connection cycle.

- **Make `probeTimeout`, `initialReconnectDelay`, and `watchdogInterval` configurable** on
  `SocketClient` via a new constructor. Defaults (2s probe, 1s initial reconnect delay, 10s
  watchdog interval) preserve reasonable production behaviour. `initialReconnectDelay` is the
  base for linear backoff: attempt N waits `min(N, 10) × initialReconnectDelay`.

- **Add `connectionLossDetectionDelay`** to `NodeDispatcherConfig`, representing the worst-case
  time between actual network loss and the client emitting `DISCONNECTED`. Computed as
  `watchdogInterval + probeTimeout`. Applied in both places the gap is measured:

  - **Dispatcher-side**: added to the `DISCONNECTED → CONNECTED` gap before comparing against
    `reconnectGracePeriod`. Inflates the measured gap to match the real outage duration.

  - **Node-side**: subtracted from the `serverRetention` deadline in the status loop. Brings the
    client-side deadline back in line with the server's actual retention window.

### Result

Disconnect detection is decoupled from the read path and from `SO_TIMEOUT`'s quirks. A half-open
TCP connection on an otherwise-silent link is detected within `watchdogInterval + probeTimeout`
of the network failure — a bound set entirely by the client's own scheduling, not by kernel or
SSL-layer behaviour. Both re-registration checks are calibrated against that worst-case
detection delay rather than the instant the client happened to notice.

---

## 5 — Spread status reports and registrations across nodes (jitter)

### Problem

When several nodes are registered in one `NodeDispatcher`, each `NodeWrapper` runs its own
status-report loop: send a status report, sleep `statusInterval`, repeat. The sleep is exact, so
nodes that share the same `statusInterval` and were registered around the same time send their
reports at almost the same instant. Every cycle, forever — they don't drift apart on their own.
With many nodes this looks like a sharp burst of traffic to the server every interval. After a
network outage they all wake up and start over together, which re-synchronises them even harder.

The same problem hits **registration messages** in coordinated reconnect scenarios: when a
fusion server restarts or a regional outage clears, every connected client's TCP reconnects
within seconds of each other and every client immediately fires a registration message. A
registration carries the full node configuration, so the server gets a sharp spike of heavy
messages exactly when it just came back up.

We want to spread these sends across time so the server sees a steady stream instead of bursts.
The spread must stay well inside the SAPIENT protocol limits — the server closes the connection
after 3 consecutive missed status reports (BSI Flex 335 v2.0 §4.9) — and must not collapse back
into sync after days or weeks of running.

### Decision

Each node spreads its sends in three layers:

1. **One-time phase offset for status reports.** When a node registers, it picks a random offset
   between `0` and its `statusInterval` (e.g. anywhere from 0 to 10 seconds for a 10-second
   interval) and waits for that offset before the first status report. Two nodes with the same
   interval start at different points in the cycle.

2. **Small per-cycle jitter on status reports.** Every later sleep is `statusInterval ± 10%` (a
   fresh random value each cycle). For a 10-second interval, sleep is 9–11 seconds. This makes
   sure two nodes that happened to draw similar offsets slowly drift apart instead of staying
   glued.

3. **Registration jitter.** Before sending each registration message (initial or
   re-registration), the node sleeps a uniform random delay in `[0, 2 seconds)`. This spreads
   the registration storm that occurs when many clients reconnect simultaneously after a server
   restart or regional network recovery. The 2-second window is fixed (not a percentage of
   `statusInterval`) because registration latency budget is independent of how often status
   reports happen, and 2 seconds is small relative to `reconnectGracePeriod` (default 2 minutes)
   so it never risks the server's grace timer expiring before re-registration completes.

All three layers use independent random draws per node — no shared state, no coordination.

The 10% per-cycle jitter is well inside the 3-missed-report safety budget enforced by §3 and
§4. The mean sleep is still exactly `statusInterval`, so the long-term send rate the server
sees per node is unchanged.

After a reconnect or re-registration, every offset is re-randomised. Any outage that briefly
forces all nodes back into sync gets re-spread on the next registration cycle — self-healing.

### Result

- Many nodes that share a `statusInterval` no longer fire status reports at the same instant.
- Registration messages are spread over a 2-second window when many clients reconnect together
  (e.g. after a server restart) — preventing a registration storm.
- Mean send rate per node is unchanged.
- No timer, schedule anchor, or counter that can drift, accumulate error, or wrap. Every cycle
  is a fresh independent sleep — long-term stability comes from having no long-term state.
- Nodes with different `statusInterval` values are decorrelated for free; jitter mainly helps
  same-interval cohorts, which are the only ones that would synchronise in the first place.

---

## 6 — Deliver Registration Ack for fusion-node-triggered re-registration

### Problem

The fusion node can request a re-registration by sending the edge node a Task with
`command.request = "registration"`. The dispatcher resends the Registration and the fusion node
replies with a Registration Ack — but the ack never reaches `INode.onRegistrationAck`. The
ack-delivery path only fires inside `NodeWrapper.register()`, which is not active during the
status-report phase.

Symptoms:

- After the first Task-driven re-registration, `onRegistrationAck` is not invoked. The ack
  silently fills `NodeWrapper.ackQueue` (capacity 1).
- The next Task-driven re-registration finds the queue full; the dispatcher logs `ack queue
  full, dropping ack for node` and the ack is lost.
- A rejected re-registration is ignored — the node keeps sending status reports to a server
  that no longer accepts it.

### Decision

- **Make `NodeDispatcher` the single delivery point for `onRegistrationAck`.** Every incoming
  Registration Ack is dispatched to `node.onRegistrationAck(ack)` directly from
  `NodeDispatcher._onMessage`, regardless of whether the wrapper is in the registration or
  status-report phase. `NodeWrapper.register()` no longer invokes the callback itself.

- **Use `ackQueue` only as a wake-up signal for `register()`.** Offer to the queue only when
  `NodeWrapper.registered == false` (i.e. `register()` is waiting). When the wrapper is in the
  status-report phase, skip the queue entirely — nothing is polling it.

- **Exit the status loop on rejection.** When a Task-driven re-registration is rejected
  (`acceptance == false`), clear `registered` so the status loop returns and the wrapper
  re-enters `register()` for a fresh registration cycle.

- **Drain `ackQueue` at the top of `register()`.** Prevents a stale ack from a previous cycle
  being matched to a freshly published registration.

### Result

Every Registration Ack reaches `onRegistrationAck` exactly once, whether it follows the normal
registration flow or a Task-driven re-registration, and regardless of how many times it
happens during a single registration lifetime. A rejected re-registration sends the node back
to the registration phase instead of silently continuing to publish status reports.

## 7 — StatusReport.ReportId is ignored during StatusReport.Info overriding in the StatusReport publish

### Problem

When a node publishes a `StatusReport` with `info = INFO_NEW`, `NodeDispatcher` compares it against
the previously published report and, if nothing changed, downgrades it to `INFO_UNCHANGED` so the
server knows the state is identical to the last report.

The comparison cleared only the `info` field before comparing. But every `StatusReport` carries a
`report_id` — a mandatory ULID that is unique per message. Two consecutive reports therefore never
compared equal, so `INFO_UNCHANGED` was never applied: every report went out as `INFO_NEW` even when
the actual state was unchanged.

### Decision

- **Ignore `report_id` as well as `info` when comparing report content.** Both fields change on
  every report regardless of whether the underlying state changed, so neither should count toward
  "is this report the same as the last one".

### Result

Identical status reports are now correctly downgraded to `INFO_UNCHANGED` even though each one has a
distinct `report_id`. A report whose content actually changed still goes out as `INFO_NEW`.

## 8 — Auto-populate ReportId in StatusReport and DetectionReport when empty

### Problem

`report_id` is a mandatory ULID on both `StatusReport` and `DetectionReport` (BSI Flex 335 v2.0).
The SDK published whatever the node handed it, so a node that left `report_id` empty produced
messages that violate the protocol's mandatory-field requirement — and a server that rejects or
de-duplicates on `report_id` would behave unpredictably. Requiring every caller to generate a ULID
itself is easy to forget and duplicates the same boilerplate in every node implementation.

### Decision

- **Generate a ULID at publish time when `report_id` is empty.** `NodeDispatcher` checks the field
  on each `StatusReport` and `DetectionReport`; if blank, it fills in a freshly generated ULID
  before publishing. A `report_id` the node already set is left untouched.

- **Use the `ulid-creator` library** (`com.github.f4b6a3`) to generate monotonic ULIDs rather than
  hand-roll one. Monotonic generation keeps the ids time-ordered even within the same millisecond,
  which is the property the protocol's ULID requirement is after.

### Result

Every published `StatusReport` and `DetectionReport` carries a valid, time-ordered `report_id`
without the node having to supply one. Nodes that do set their own `report_id` keep full control.

## 9 — Log body of all incoming messages when in the DEBUG log level

### Problem

Outgoing messages were logged twice: a one-line INFO summary (`sending <type> …`) and, when DEBUG
was enabled, the full message body as JSON. Incoming messages only got the INFO summary
(`received <type> for node: …`) — there was no way to see the actual contents of what the fusion
node sent, even at DEBUG. That made diagnosing acks, tasks, and malformed incoming messages much
harder than diagnosing the outgoing side.

### Decision

- **Log the full JSON body of every incoming message at DEBUG**, mirroring the outgoing side. The
  body log only renders when DEBUG is enabled, so INFO-level logging is unchanged.

- **Share one helper between both directions.** The body-rendering block (guard on DEBUG, print the
  message as JSON, fall back to a placeholder if serialization fails) was extracted from the
  publish path into a single helper used by both incoming and outgoing logging, so the two stay in
  sync.

### Result

At DEBUG level the full contents of both sent and received messages are visible. At INFO and above,
logging is unchanged — only the one-line summaries appear.

## 10 — Deliver received Error messages to the node

### Problem

`Error` is one of the SAPIENT message types a server can send (the `error` field in the
`SapientMessage` oneof, BSI Flex 335 v2.0). It reports back the packet that caused a problem and one
or more error descriptions. `INode` had callbacks for the other server-to-node messages
(`onRegistrationAck`, `onAlertAck`, `onTask`), but none for `Error` — so when the dispatcher
received an `Error`, it hit the `default` branch of the message switch and was silently dropped. A
node had no way to learn that the server rejected one of its messages.

### Decision

- **Add `onError(Error error)` to `INode`**, mirroring the existing server-to-node callbacks.

- **Route the `ERROR` content case in `NodeDispatcher`** to `node.onError(...)`, exactly like the
  `ALERT_ACK` case: if no node is registered for the destination it is logged and dropped,
  otherwise the `Error` is delivered to the node.

### Result

A node is now notified whenever the server sends it an `Error`, with the full `Error` message
(offending packet and descriptions) available for it to inspect. The full body of every incoming
`Error` is always logged at DEBUG by the shared incoming-message logging added in §9 — independent
of message routing, so it is logged whether or not a node is registered for the destination. No
Error-specific logging is needed.

## 11 — Auto-populate StatusReport.Info when the node leaves it unset

### Problem

`info` is a mandatory field of `StatusReport` (BSI Flex 335 v2.0). The SDK only acted on it when the
node set `INFO_NEW`: such a report was downgraded to `INFO_UNCHANGED` when the content repeated the
previous one. A node that left `info` unset published `INFO_UNSPECIFIED`, and its reports never took 
part in de-duplication at all.

A goodbye report had the same gap. It is sent from `NodeWrapper.close()` on top of whatever
`node.getStatusReport()` returns, so it usually carried no `info` either.

### Decision

- **Fill `info` at publish time when the node leaves it unset.** `NodeDispatcher` compares the
  report against the previous one of the same node: `INFO_UNCHANGED` when the content repeats,
  `INFO_NEW` otherwise. For an unknown (not registered) node there is nothing to compare with, so
  the value is `INFO_NEW`.

- **Always set `INFO_NEW` on a goodbye** (`system = SYSTEM_GOODBYE`) and keep it out of
  de-duplication. A node leaving is new information even when the rest of the report did not change.

- **Keep an explicit `INFO_UNCHANGED` from the node as sent.** The node states that the content
  repeats, so the SDK does not second-guess it. An explicit `INFO_NEW` still gets the existing
  downgrade to `INFO_UNCHANGED` on repeated content (§7).

### Result

Every published `StatusReport` carries a valid `info`, so nodes no longer have to set the field
themselves to stay protocol-compliant. De-duplication now works for those nodes as well: identical
reports go out as `INFO_UNCHANGED`, and a goodbye always goes out as `INFO_NEW`.

## 12 — Update lastStatusReport only after successful send

### Problem

`NodeDispatcher.publish(StatusReport, ...)` stored the report in `NodeWrapper.lastStatusReport` with
`getAndSet` **before** giving the message to the transport. The store happened even when the send
then failed. So `lastStatusReport` could hold content the server never received.

That silently lost a state change:

1. The node state changes. The report goes out as `INFO_NEW`.
2. `IClient.publish` throws — publish deadline reached, or the client was stopped. Nothing reached
   the server. `NodeWrapper.runStatusLoop` logs the timeout and keeps running.
3. On the next tick the same content is published again. It equals `lastStatusReport`, so it goes out
   as `INFO_UNCHANGED`.

The server keeps the old state, and then gets "nothing changed" for a state it never received. The
change stays invisible until the node content changes again.

The python SDK (py-sapient-sdk) already has this check: a failed write keeps the previous report.

### Decision

- **Read `lastStatusReport`, send, then update it.** The update runs after `IClient.publish` returned
  without an error. When the send throws, the exception leaves the method and the old value stays on
  its own — no `try/catch` and no rollback needed.

- **`compareAndSet` instead of `set`.** The read and the write are two steps now, so a concurrent
  publish of the same node can land between them. The later writer loses instead of overwriting a
  newer value.

- **Every sent report is stored, with no exceptions.** A report the node pinned to `INFO_UNCHANGED`
  also reached the server, so it is stored too — otherwise a change could be lost across a pinned
  report. A goodbye is stored as well. It is harmless: `system` takes part in the content comparison,
  so a stored goodbye never matches a normal report, and a goodbye itself is always `INFO_NEW`
  without any comparison (§11).

- **A failed send is treated as "not delivered".** `SocketClient.publish` retries on `IOException`
  with a fresh connection. It throws only on the publish deadline or on a stopped client. So a clean
  return means the bytes were written and flushed, but it is no proof that the server processed them.
  When it is not clear, the SDK assumes the report did not arrive. One case is then one extra
  `INFO_NEW` with the same content: the server sees the same state twice and nothing breaks. The
  other case is worse — the SDK would send `INFO_UNCHANGED`, and the server would never learn the
  new state.

### Result

`lastStatusReport` holds only content that reached the transport without an error. A failed status
report is sent again as `INFO_NEW` on the next tick, so the server always learns about the change.
Two tests cover it: a failed publish keeps the next identical report `INFO_NEW`, and a report pinned
to `INFO_UNCHANGED` is stored for the next comparison.


## 13 — Cap the message frame size

### Problem

`SocketClient.readLoop` allocated `new byte[len]` with `len` taken straight from the 4-byte
length prefix on the wire. Nothing checked the value.

The prefix is unsigned on the wire and signed in Java. A prefix of `0xFFFFFFFF` reads back as
`-1`, so `new byte[-1]` threw `NegativeArraySizeException`. A large positive prefix threw
`OutOfMemoryError` instead.

Both are unchecked. `runLoop` catches only `EOFException` and `IOException`, so the exception
escaped the loop and killed the virtual thread. After that the client was dead for good:
`running` stayed `true` and `runLoopThread` stayed non-null, so `start()` refused to build a
replacement loop. Nothing in the log said why.

This is not only a hostile-input problem. `0xFFFFFFFF` is the pong sentinel of keepalive
solution D, so a peer using that mode would crash the client on its first pong.

### Decision

- **Cap the body at 16 MiB** (`DEFAULT_MAX_FRAME_SIZE`), the same value the SAPIENT server uses.
  The check widens the prefix with `Integer.toUnsignedLong` before comparing, so `0xFFFFFFFF` is
  seen as 4294967295 and not as a small negative number. A frame above the cap raises
  `IOException`, which `runLoop` already handles: the connection is dropped and retried.

- **Let the caller change the cap.** A new `SocketClient` constructor takes `maxFrameSize`, next
  to the existing timeouts. A deployment that knows its peer sends bigger or smaller messages
  can set its own value; everything else keeps the 16 MiB default. A non-positive value is
  rejected in the constructor, so the client cannot be built with a cap that blocks every frame.

- **Catch `RuntimeException` in `runLoop` as well.** The cap fixes the one trigger we know
  about, not the shape of the failure. Any unchecked exception from the read path is now
  treated like a connection failure — log it, drop the connection, reconnect — instead of
  killing the client silently.

### Result

A garbage or hostile length prefix costs one reconnect instead of a permanently dead client.
A zero-length prefix still produces an empty `SapientMessage`, so nothing changes for a
well-behaved peer.

## 14 — Four health check types and a configurable failure count

### Problem

The client had one way to check the connection and no tolerance for a single failure.

`SocketClient` ran a watchdog that opened a throwaway TCP connection to `host:port`. There was
no way to use ICMP where it is allowed, and no way to use the keepalive the SAPIENT server
implements ([dstl/SAPIENT-Proto-Files#12](https://github.com/dstl/SAPIENT-Proto-Files/issues/12)
solutions C and D).

The watchdog closed the socket on the **first** failed probe. One dropped packet or one busy
accept queue cost a full reconnect, and past the grace period a re-registration.

`NodeDispatcherConfig.connectionLossDetectionDelay` had to equal the client's `watchdogInterval
+ probeTimeout`, computed by the caller. Nothing checked it, and a wrong value breaks both
re-registration checks from §3 and §4.

### Decision

- **One health check type per client**, chosen with `HealthCheckConfig`: `NETCAT` (TCP connect,
  the old behaviour), `ICMP`, `ECHO` (solution C) and `PINGPONG` (solution D).
  `TRANSPORT_NATIVE` is reserved for a future gRPC client and rejected by `SocketClient`.

- **Fixed cadence, consecutive-failure count.** Checks start every `interval` whether the
  previous one passed or failed, and the next check is anchored on the previous check's start.
  Anchoring on the end would make failed checks retry back to back, so N failures would measure
  one outage of `N × timeout` instead of tolerating a blip. `timeout <= interval` is enforced,
  so two checks never overlap and the detection delay stays one line:
  `failureThreshold × interval + timeout`. This is what SSH, BGP, OSPF, BFD, Kubernetes and gRPC
  keepalive all do.

- **The transport reports its own detection delay.** `IClient.connectionLossDetectionDelay()`
  replaces the `NodeDispatcherConfig` field. A failure count cannot carry across transports —
  gRPC closes on the first missed PING ACK and has no counter — but a duration can. The
  dispatcher reads one number and never learns which mechanism produced it.

- **Any inbound frame proves liveness.** The read path tells the monitor about every frame, which
  pushes the next check back by a full interval and answers a pending in-band ping. A pong is
  only one kind of proof; a `RegistrationAck` works as well. This also makes a wrong-mode setup
  harmless: a server with keepalive off answers a zero-length frame with a validation `Error`,
  and that `Error` passes the check.

- **The in-band check queues for the write path like a publisher.** It never interrupts a write
  in progress. Failing to take the write slot within `timeout` counts as a failed check — a
  write path blocked that long is not a healthy connection, and it is the one case a priority
  queue could not fix anyway, since a publisher stuck inside `write()` blocks any single writer
  too. Known cost: a congested link can fail a check, which is what the failure threshold
  absorbs.

- **ICMP spawns the system `ping`.** `InetAddress.isReachable` falls back to a TCP connection on
  port 7 when it cannot get the ICMP privilege, so unprivileged on Linux it calls a healthy host
  dead. The `ping` binary carries `cap_net_raw` or the setuid bit and works without root, and the
  Python SDK can do exactly the same.

- **Clamp the retention window at zero.** `3 × statusInterval + grace − detectionDelay` can go
  negative with a large interval or threshold. A negative window means "re-register on every
  tick", so it is clamped and logged.

### Result

Four interchangeable liveness checks behind one config record. A single failed check no longer
costs a reconnect. The detection delay cannot drift out of sync with the transport, because the
transport is what reports it.

Defaults keep today's mechanism with the standard failure count: `NETCAT`, 10s interval, 2s
timeout, threshold 3. Detection moves from 12s to 32s — the price of tolerating two lost probes
instead of zero, absorbed by the retention budget. To get the old speed back, lower the interval
rather than the threshold: `4s / 2s / 3` detects in 14s and still tolerates two lost probes.

**Breaking:** `NodeDispatcherConfig` lost its `connectionLossDetectionDelay` component and
`defaults(destinationId, delay)` became `defaults(destinationId)`. The four-argument
`SocketClient(provider, probeTimeout, initialReconnectDelay, watchdogInterval)` constructor
became `SocketClient(provider, HealthCheckConfig, initialReconnectDelay)`. The one-argument
`SocketClient(provider)` is unchanged.
