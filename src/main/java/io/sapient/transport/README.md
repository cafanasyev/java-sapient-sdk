# io.sapient.transport

TCP transport layer shared by all SAPIENT roles.

Provides a publish/subscribe interface over raw byte buffers, with automatic reconnection and thread-safe publishing. Supports both plain TCP and mutual TLS (mTLS) connections.