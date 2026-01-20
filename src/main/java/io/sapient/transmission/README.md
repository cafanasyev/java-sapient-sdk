# io.sapient.transmission

Transmission module of the SAPIENT protocol.

Implements the node lifecycle. Registration with a fusion node, waiting for `RegistrationAck`, periodic `StatusReport` publishing, and `Goodbye` on disconnect. Also supports publishing `Alert` and `DetectionReport` messages.

Each registered `INode` is managed by its own virtual thread with synchronous-looking control flow via `NodeWrapper`.
