## IClient redesign — typed SapientMessage transport

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