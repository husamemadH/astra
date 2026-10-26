# Requirement-to-code map

| Original requirement | Implementation | Evidence |
| --- | --- | --- |
| TCP only | `Socket`, `ServerSocket`, `Protocol` | Same real TCP client works against all three designs |
| Single-threaded blocking | `TcpServer` BASELINE branch | `SchedulingTest.baselineProcessesOnlyOnePersistentConnection` |
| Unbounded thread per connection | THREAD_PER_CONNECTION branch with platform threads | Independent-handler test and matching-concurrency benchmark series |
| Fixed ExecutorService pool / bounded queue | `ThreadPoolExecutor` with equal min/max workers and `ArrayBlockingQueue` | Saturation, rejection, and recovery tests; saved overload runs |
| Length prefix | `Protocol.Reader` | One-byte fragmentation, coalescing, malformed/truncated frames |
| Chunked large payloads | Reused protocol and client buffers | 500 MiB transfer under `-Xmx128m` |
| No per-request wait / pipelining | `LoadClient` writer, reply reader, semaphore | Sixteen requests sent before reading replies; sustained pipeline test |
| synchronized versus LongAdder | `MetricsCounters` implementations | Concurrent reconciliation tests; counter-mode benchmark pairs |
| Backpressure | Rejected pool submissions close accepted socket | `SchedulingTest.boundedPoolRejectsExcessAndRecovers` |
| Active registry with connection age | `ConnectionRegistry` active map and `connections` command | Live/closed lifecycle inspection test |
| Closed connection history | Immutable bounded history with reasons and final totals | Retention, eviction, and exactly-once tests |
| Two scenarios using both registries | Lifecycle inspection; idle cleanup and timeout audit | `RegistryTest` and documented console demonstrations |
| Find/explain throughput ceiling | Java runner, raw metrics, reports, CPU-capacity control | Measured-results report, with environment and limitations |

## Implementation details that qualify the specification

- The default pool task is one entire connection. Queued persistent connections cannot make progress until a worker is released; normal sweeps use W connections for W workers.
- Default warm-up/measurement durations are 10/30 seconds. The checked-in study preset uses shorter repeated windows to keep the example manageable. Its results must be interpreted with that limitation.
- Primary throughput counts complete, valid DATA chunks. A final partial/truncated chunk contributes no validated payload bytes; the incomplete request is reported as failed.
- `LongAdder` snapshots are approximate while workers run; final counters reconcile after all handlers stop.
- Server window commands follow client phase events over process pipes. The server uses its own measured elapsed interval, and both endpoints' boundaries are preserved. No cross-process nanoTime subtraction is used.
- The timeout audit lists retained closed entries, not all historical timeouts after eviction. Eviction totals are exposed.
- Linux process RSS and resource limits are recorded where available. Physical NIC utilization is not sampled for the loopback workload; it is explicitly marked unavailable/not applicable.
- The runner aborts a failed trial and keeps its raw directory and failure record. Failed trials do not become successful aggregate points.
- The TCP application remains entirely Java. Maven downloads build dependencies; GitHub Actions runs the same Java build and smoke experiment.
