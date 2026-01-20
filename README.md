# java-sapient-sdk

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