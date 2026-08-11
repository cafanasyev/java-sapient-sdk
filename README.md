# java-sapient-sdk

[![CI](https://github.com/cafanasyev/java-sapient-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/cafanasyev/java-sapient-sdk/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/cafanasyev/java-sapient-sdk/graph/badge.svg)](https://codecov.io/gh/cafanasyev/java-sapient-sdk)

Java SDK for [BSI Flex 335 v2.0](https://www.bsigroup.com/en-US/insights-and-media/insights/brochures/bsi-flex-335-interface-of-the-sapient-sensor-management-specification/) SAPIENT — a protocol standard for autonomous sensor and effector interoperability. The SDK provides TCP client connectivity and node dispatching for communicating with SAPIENT fusion nodes using Protobuf-serialized messages.

Python counterpart:
[py-sapient-sdk](https://github.com/cafanasyev/py-sapient-sdk).

## Requirements

- Java 21+
- No local Maven installation required (Maven Wrapper included)

## Build

```bash
./mvnw compile
```

## Test

```bash
# Unit tests
./mvnw test

# Unit + integration tests
./mvnw verify
```

## Structure

| Package | Description                                                                                         |
|---|-----------------------------------------------------------------------------------------------------|
| `io.sapient.transport` | TCP transport layer — publish/subscribe over raw byte buffers, automatic reconnection, mTLS support |
| `io.sapient.transmission` | Transmission module — node registration, status reporting, ack handling                             |
## Code Quality

The project uses three static analysis tools that run automatically during the build:

| Tool | What it does | Runs during |
|---|---|---|
| [Spotless](https://github.com/diffplug/spotless) | Code formatting via [google-java-format](https://github.com/google/google-java-format) (AOSP style, 4-space indentation) | `validate` phase |
| [Error Prone](https://errorprone.info/) | Compile-time bug detection by Google | `compile` phase |
| [SpotBugs](https://spotbugs.github.io/) | Bytecode-level bug detection | `verify` phase |

### Standalone commands

```bash
# Check formatting
./mvnw spotless:check

# Auto-fix formatting
./mvnw spotless:apply

# Run SpotBugs analysis
./mvnw spotbugs:check

# Open SpotBugs GUI report
./mvnw spotbugs:gui
```

### IDE setup

Install the [google-java-format](https://plugins.jetbrains.com/plugin/8527-google-java-format) IntelliJ IDEA plugin and select **AOSP** style in its settings. This makes the IDE formatter produce identical output to Spotless.

### How to use SDK

The SDK is built around four interfaces. To familiarize yourself with the internal logic without a deep dive into the implementation, read these four — they are commented and cover the whole flow:

- [`INode`](src/main/java/io/sapient/transmission/INode.java) — a SAPIENT edge/fusion node you implement (identity, registration, and server-to-node callbacks).
- [`INodeDispatcher`](src/main/java/io/sapient/transmission/INodeDispatcher.java) — manages node registration, the keep-alive/status-report lifecycle, and message routing.
- [`IClient`](src/main/java/io/sapient/transport/IClient.java) — the transport: publish/subscribe typed SAPIENT messages plus connection-state monitoring. `SocketClient` serializes concurrent publishes through a single fair (FIFO) permit, so competing writer threads are served in arrival order and none is starved.
- [`ISocketProvider`](src/main/java/io/sapient/transport/ISocketProvider.java) — supplies the socket (host/port and TLS vs. plain) to the client.

Add the SDK to your build.

Maven:

```xml
<dependency>
    <groupId>io.github.cafanasyev</groupId>
    <artifactId>java-sapient-sdk</artifactId>
    <version>0.3.2</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.cafanasyev:java-sapient-sdk:0.3.2")
```

1. Implement the [INode.java](src/main/java/io/sapient/transmission/INode.java) interface for each Node you want to connect.
   All methods are supplied with comments explaining their purpose.
2. Create an instance of the [NodeDispatcher.java](src/main/java/io/sapient/transmission/NodeDispatcher.java). In order to do so:
    * Implement the [ISocketProvider.java](src/main/java/io/sapient/transport/ISocketProvider.java) interface. For the default TLS based connection use the already implemented [SslContextFactory.java](src/main/java/io/sapient/transport/SslContextFactory.java), whose `create(clientKey, clientCert, caCert)` builds the `SSLContext`. All three arguments are required, and each is the certificate/key content itself as a `byte[]` — the factory takes no file paths, so load the bytes from wherever you keep them. Both PEM and DER encodings are accepted (keys: PKCS#8, PKCS#1, SEC1/EC). Using this factory is optional: implement `ISocketProvider` yourself to supply any `SSLContext` (or `Socket`) you like. For a non-TLS connection you can use: [`SapientConfig.java:82`](https://github.com/cafanasyev/java-sapient-test-harness/blob/cd5dab8/src/main/java/io/sapient/SapientConfig.java#L82)
    * Instantiate the [NodeDispatcherConfig.java](src/main/java/io/sapient/transmission/NodeDispatcherConfig.java)
    * Instantiate the [SocketClient.java](src/main/java/io/sapient/transport/SocketClient.java)
    * Instantiate the [NodeDispatcher.java](src/main/java/io/sapient/transmission/NodeDispatcher.java)

   A code sample for all of the above is inside [`SapientConfig.java`](https://github.com/cafanasyev/java-sapient-test-harness/blob/master/src/main/java/io/sapient/SapientConfig.java) — the [java-sapient-test-harness](https://github.com/cafanasyev/java-sapient-test-harness) repository serves as a reference implementation that uses this SDK.
   Use the default provided values of the variables from the test-harness sample.
   The purpose of each variable is documented at the classes which use those variables.
   The health check settings are explained in [Health checks](#health-checks) below, and the reasoning behind them, `socketInitialReconnectDelay` and the connection-loss detection delay is inside [CHANGELOG.md](CHANGELOG.md) (points 3, 4 and 14).
3. Pass your INode implementations to the `NodeDispatcher.register(INode node)` method. This is what makes the Dispatcher automate node lifecycle management for you — see [Automated Node Lifecycle Behavior](#automated-node-lifecycle-behavior) below for the full list of what that gets you for free. (The reasoning behind the jitter design is described in [CHANGELOG.md](CHANGELOG.md), point 5.)
4. Stop managing a Node with `NodeDispatcher.unregister(INode node)` when you no longer want the Dispatcher to report for it.
5. Close the Dispatcher with `NodeDispatcher.close()` (it is `AutoCloseable`, so try-with-resources works) to shut down the connection and its background threads when you are done.
6. OPTIONALLY:
   * Use the Node Dispatcher to send Detection Reports/Alerts/TaskAcks via the typed `NodeDispatcher.publish(...)` overloads (`publish(DetectionReport|Alert|TaskAck, UUID nodeId, Duration timeout)`);
   * You can invoke sending of Registrations/Status Reports outside of the Node Dispatcher automatic lifecycle — for example if you want to notify the Server about some changes immediately without waiting for the next interval — using the `publish(Registration|StatusReport, UUID nodeId, Duration timeout)` overloads;
   * Call [`IClient.addStateChangeListener(...)`](src/main/java/io/sapient/transport/IClient.java#L65) to subscribe to connection state changes (and [`removeStateChangeListener(...)`](src/main/java/io/sapient/transport/IClient.java#L73) to unsubscribe). You may want to log or run additional logic when, for example, the connection is lost for a prolonged period. You can also poll the connection directly with `getState()`, `isConnected()`, and `probeReachable(Duration)`.

### Health checks

The client watches the connection with one health check. Pick the type that matches your network and the server's keepalive setting.

```java
var health = new HealthCheckConfig(
        HealthCheckType.NETCAT,   // NETCAT | ICMP | ECHO | PINGPONG
        Duration.ofSeconds(10),   // how often a check starts
        Duration.ofSeconds(2),    // how long one check may take, never above the interval
        3);                       // failures in a row before the connection is dropped

// the last argument is initialReconnectDelay: the pause before the first reconnect
// attempt. It grows linearly with each failed attempt (1s, 2s, 3s ...) up to 10x, and
// resets after a successful connect
var client = new SocketClient(provider, health, Duration.ofSeconds(1));
```

| Type | How it works | When to use it                          |
|---|---|-----------------------------------------|
| `NETCAT` | Opens and closes a throwaway TCP connection | Default. Works everywhere               |
| `ICMP` | Runs the system `ping` | ICMP is allowed between node and server |
| `ECHO` | Sends a zero-length frame, the server echoes it | Server runs in `echo` mode              |
| `PINGPONG` | Sends a zero-length frame, the server answers `0xFFFFFFFF` | Server runs in `pingpong` mode          |

`ECHO` and `PINGPONG` are not compatible with each other. Both send the same ping and answer differently, so the client and the server must use the same one.

Worst case time to notice a dead link is `failureThreshold × interval + timeout`, 32 seconds with the defaults. `NodeDispatcher` reads it from the client with `IClient.connectionLossDetectionDelay()`, so you never set it twice.

### Automated Node Lifecycle Behavior

Everything `NodeDispatcher.register(node)` automates for you — you don't need to implement any of this yourself — compared across both SDKs:

| Behavior | Details | Java | Python |
|---|---|---|---|
| Regularly poll `INode.isOnline()`/`is_online()` to detect online/offline transitions | Drives every other behavior below. | ✅ | ✅ |
| Auto-send Registration when a node comes online | Obtains the Registration message from the node implementation and sends it to the fusion node (server). | ✅ | ✅ |
| Auto Status Report keep-alive | Sends automatic Status Reports on the interval stated in the node's Registration. | ✅ | ✅ |
| Jitter — one-time phase offset before first status report | A one-time random phase offset in `[0, statusInterval)` before the first status report of each (re-)registered loop, so nodes that share a `statusInterval` don't start in sync. | ✅ | ✅ |
| Jitter — ±10% per-cycle on subsequent status reports | A fresh per-cycle jitter of `statusInterval ± 10%` on every subsequent Status Report, so nodes drift apart instead of re-synchronising — the mean send rate stays exactly `statusInterval`, and the ±10% stays well inside the protocol's 3-missed-report budget. | ✅ | ✅ |
| Jitter — random delay before registration/re-registration | A random delay in `[0, registrationJitterWindow)` (default 2 seconds) before every registration/re-registration, to spread the registration storm when many clients reconnect at once (e.g. after a fusion-server restart). Tunable; set to `0`/`Duration.ZERO` to disable, e.g. in tests. | ✅ | ✅ |
| Auto GOOD BYE Status Report on going offline | Sends a GOOD BYE Status Report to de-register the node when it becomes offline. | ✅ | ✅ |
| Route server messages to the node's callbacks | Routes server messages (RegistrationAck, AlertAck, Error, Task) to the required node's callback methods. | ✅ | ✅ |
| Open connection when a node comes online | Keeps the connection open as long as at least one online node is registered. | ✅ | ✅ |
| Close connection when no nodes are online | Closes the connection once no online nodes are left. | ✅ | ✅ |
| Re-open connection when a node comes online again | Re-opens the connection if at least one online node reappears. | ✅ | ✅ |
| Auto `StatusReport.Info = INFO_UNCHANGED` when unchanged | Set automatically if the last message has no changes, so unchanged reports aren't treated as new events. | ✅ | ✅ |
| Auto-populate `StatusReport.Info` if unset | Fills the mandatory field before sending: `INFO_UNCHANGED` when the content repeats the previous report, `INFO_NEW` otherwise. A GOOD BYE report always gets `INFO_NEW`. An explicit `INFO_UNCHANGED` from the node is kept as sent. | ✅ | ❌ |
| Auto-populate `StatusReport.ReportId` if blank | Fills in a fresh ULID before sending, so callers don't need to mint one themselves. | ✅ | ✅ |
| Auto-populate `DetectionReport.ReportId` if blank | Fills in a fresh ULID before sending, so callers don't need to mint one themselves. | ✅ | ✅ |

### Logging

The SDK logs through the [SLF4J](https://www.slf4j.org/) API and does **not** bundle a binding. To see any output, add an SLF4J binding of your choice to your application — for example [Logback](https://logback.qos.ch/), `slf4j-simple`, or `log4j-slf4j2-impl`. Without a binding, SLF4J stays silent. Log level is configured through whichever binding you pick (there is nothing SDK-specific to set).

What each level shows:

| Level | What is logged |
|---|---|
| `INFO` | One-line summaries: node registration/unregistration, GOOD BYE on going offline, and one line per message with its type and destination node (`sending <type> …`, `received <type> for node: …`). |
| `DEBUG` | Everything from `INFO`, **plus the full JSON body of every message** sent and received. Use this to inspect the exact contents of Registrations, Status Reports, Acks, Tasks, Errors, etc. |
| `WARN` / `ERROR` | Recoverable and error conditions (e.g. no node registered for a destination, publish timeouts, failed connection close). |

`DEBUG` is verbose — it prints the entire body of every message on the wire — so it is best reserved for diagnosing protocol issues rather than for normal operation.


## License

This project is released into the public domain under the [Unlicense](https://unlicense.org).