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

Additionally, `SocketProvider` was a `@FunctionalInterface` that hid the host and port inside a
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

- **Enrich `SocketProvider`** with `host()` and `port()` methods. No longer a `@FunctionalInterface`.
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

## 3 — §4.9 re-registration after prolonged connection loss

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