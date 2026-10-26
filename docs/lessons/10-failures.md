# 10 — Prove the failure paths, not only successful uploads

Read `FailureHandlingTest` and the watchdog in `ChildJvm`. A useful server keeps working after a truncated request, wrong checksum, repeated request ID, reset peer, or timeout. A useful benchmark stops and preserves evidence when a child exceeds its resource budget.

The watchdog checks both child JVMs independently while the runner is waiting for phase events. This matters because waiting for a client message must not suspend monitoring of the server. Resource limits belong to the experiment runner; the thread-per-connection server still has no application worker cap.

Run `./mvnw clean verify`. Follow the tests from the input fault through the handler, socket closure, registry finalization, and final counters. The shutdown test also asserts that accepted sockets and server threads are gone.

Question: why are “no exception thrown” tests insufficient here? A task can appear to finish while leaking a socket, retaining an active registry entry, losing counters, or leaving a blocked worker. The tests inspect those outcomes explicitly.
