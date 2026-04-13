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
