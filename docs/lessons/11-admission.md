# 11 — The gap between checking a condition and acting on it

Review found one subtle queue race: a connection could expire after acceptance but before its task was submitted. Maintenance would remove nothing from the queue and finalize the connection; admission could then enqueue a closed task that no longer appeared in the registry. It would occupy a queue slot until a worker eventually took it.

Read `Entry.enqueueIfOpen` and its use in the pool branch. The entry monitor covers both the liveness check and the nonblocking executor submission. Expiry now happens either before both steps, preventing submission, or after both steps, allowing maintenance to remove the task. The lock does not cover socket I/O.

Run `RegistryTest#expiryCannotRemoveATaskBeforeItIsEnqueuedAndLeaveAStaleQueueEntry`. Two threads race submission and expiry through a shared gate; the final queue must always be empty. This is a useful example of why a thread-safe map alone does not make a multi-step lifecycle correct.

This stage also makes executable provenance explicit: environment metadata distinguishes the launch checkout's Git revision from the SHA-256 of the actual JAR. Launching an older JAR from a newer checkout must not imply that the executable contains the newer changes.
