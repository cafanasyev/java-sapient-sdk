# java-sapient-sdk

[![CI](https://github.com/cafanasyev/java-sapient-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/cafanasyev/java-sapient-sdk/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/cafanasyev/java-sapient-sdk/graph/badge.svg)](https://codecov.io/gh/cafanasyev/java-sapient-sdk)

Java SDK for [BSI Flex 335 v2.0](https://www.bsigroup.com/en-US/insights-and-media/insights/brochures/bsi-flex-335-interface-of-the-sapient-sensor-management-specification/) SAPIENT — a protocol standard for autonomous sensor and effector interoperability. The SDK provides TCP client connectivity and node dispatching for communicating with SAPIENT fusion nodes using Protobuf-serialized messages.

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
| `io.sapient.fusion` | Fusion module — *not yet implemented*                                                               |

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
- [`IClient`](src/main/java/io/sapient/transport/IClient.java) — the transport: publish/subscribe typed SAPIENT messages plus connection-state monitoring.
- [`ISocketProvider`](src/main/java/io/sapient/transport/ISocketProvider.java) — supplies the socket (host/port and TLS vs. plain) to the client.

Add the SDK to your build.

Maven:

```xml
<dependency>
    <groupId>io.github.cafanasyev</groupId>
    <artifactId>java-sapient-sdk</artifactId>
    <version>0.3.1</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.cafanasyev:java-sapient-sdk:0.3.0")
```

1. Implement the [INode.java](src/main/java/io/sapient/transmission/INode.java) interface for each Node you want to connect.
   All methods are supplied with comments explaining their purpose.
2. Create an instance of the [NodeDispatcher.java](src/main/java/io/sapient/transmission/NodeDispatcher.java). In order to do so:
    * Implement the [ISocketProvider.java](src/main/java/io/sapient/transport/ISocketProvider.java) interface. For the default TLS based connection use the already implemented [SslContextFactory.java](src/main/java/io/sapient/transport/SslContextFactory.java). For a non-TLS connection you can use: [`SapientConfig.java:82`](https://github.com/cafanasyev/java-sapient-test-harness/blob/cd5dab8/src/main/java/io/sapient/SapientConfig.java#L82)
    * Instantiate the [NodeDispatcherConfig.java](src/main/java/io/sapient/transmission/NodeDispatcherConfig.java)
    * Instantiate the [SocketClient.java](src/main/java/io/sapient/transport/SocketClient.java)
    * Instantiate the [NodeDispatcher.java](src/main/java/io/sapient/transmission/NodeDispatcher.java)

   A code sample for all of the above is inside [`SapientConfig.java`](https://github.com/cafanasyev/java-sapient-test-harness/blob/master/src/main/java/io/sapient/SapientConfig.java) — the [java-sapient-test-harness](https://github.com/cafanasyev/java-sapient-test-harness) repository serves as a reference implementation that uses this SDK.
   Use the default provided values of the variables from the test-harness sample.
   The purpose of each variable is documented at the classes which use those variables.
   Also, the reasoning behind the `socketWatchdogInterval`, `socketProbeTimeout`, `socketInitialReconnectDelay`, and `connectionLossDetectionDelay` variables is described inside [CHANGELOG.md](CHANGELOG.md) (points 3 and 4).
3. Pass your INode implementations to the `NodeDispatcher.register(INode node)` method. Doing so will make the Dispatcher:
   * regularly check whether the Node is online;
   * when a node is online — obtain the Registration message from the Node implementation and send it to the fusion node (server);
   * maintain the keep-alive with automatic sending of Status Reports (based on the interval stated in its Registration);
   * if a node becomes offline — send a GOOD BYE Status Report to de-register the Node;
   * route server messages to the required Node (see the callback methods of the [INode.java](src/main/java/io/sapient/transmission/INode.java) interface);
   * keep the connection open as long as at least one online Node is provided;
   * close the connection if no online Nodes are present;
   * re-open the connection if at least one online Node appears;
   * automatically set StatusReport.Info to INFO_UNCHANGED if the last message doesn't have any change (you don't need to implement this yourself);
   * automatically populate StatusReport.ReportId if it's blank;
   * automatically populate DetectionReport.ReportId if it's blank;
4. Stop managing a Node with `NodeDispatcher.unregister(INode node)` when you no longer want the Dispatcher to report for it.
5. Close the Dispatcher with `NodeDispatcher.close()` (it is `AutoCloseable`, so try-with-resources works) to shut down the connection and its background threads when you are done.
6. OPTIONALLY:
   * Use the Node Dispatcher to send Detection Reports/Alerts/TaskAcks via the typed `NodeDispatcher.publish(...)` overloads (`publish(DetectionReport|Alert|TaskAck, UUID nodeId, Duration timeout)`);
   * You can invoke sending of Registrations/Status Reports outside of the Node Dispatcher automatic lifecycle — for example if you want to notify the Server about some changes immediately without waiting for the next interval — using the `publish(Registration|StatusReport, UUID nodeId, Duration timeout)` overloads;
   * Call [`IClient.addStateChangeListener(...)`](src/main/java/io/sapient/transport/IClient.java#L65) to subscribe to connection state changes (and [`removeStateChangeListener(...)`](src/main/java/io/sapient/transport/IClient.java#L73) to unsubscribe). You may want to log or run additional logic when, for example, the connection is lost for a prolonged period. You can also poll the connection directly with `getState()`, `isConnected()`, and `probeReachable(Duration)`.

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