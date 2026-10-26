# Astra — Java TCP Scaling Benchmark

## 1. Purpose and source

Implement a Java client/server benchmark that demonstrates how TCP throughput changes as server worker count increases, identifies the observed throughput ceiling, and explains the bottleneck using measurements.

Source: `/home/husamemad/Downloads/astra.docx`. Java is an explicit user requirement. The source requires the server designs and capabilities listed below; wire format, defaults, module names, benchmark procedure, and retention policies in this specification are implementation decisions that make those requirements concrete.

The deliverable is an executable experiment, reproducible results, and an explanation of the measured scaling curve. Do not assume throughput will increase monotonically or claim a bottleneck without supporting evidence.

## 2. Required scope

- Use TCP exclusively for application communication; do not implement UDP.
- Implement three servers behind exactly the same application protocol:
  1. Single-threaded blocking baseline.
  2. Unbounded thread-per-connection server.
  3. Fixed thread pool using `ExecutorService` and a bounded work queue.
- Support length-prefixed frames, chunked payloads, and pipelined requests with ordered replies.
- Compare shared counters implemented using Java `synchronized` against counters implemented using `LongAdder`.
- Demonstrate bounded-queue backpressure in the fixed-pool server.
- Maintain a registry of current connections and a history of closed connections.
- Implement and demonstrate two server scenarios that each use both registries.
- Measure throughput across worker counts and explain the observed ceiling.

No GUI, database, authentication system, UDP transport, distributed coordinator, or production deployment is required. Results and connection history may be stored locally as files; history does not need to survive a restart.

## 3. Implementation platform and structure

- Language: Java, targeting Java 21.
- Build: Maven with Maven Wrapper.
- Networking: blocking `ServerSocket` and `Socket`, with buffered input/output streams.
- Concurrency: platform threads for all three designs. Use `ThreadPoolExecutor` through an `ExecutorService` abstraction for the fixed-pool design. Virtual threads and asynchronous networking are outside this comparison.
- Tests: JUnit 5, with integration tests using loopback TCP and ephemeral ports.
- Server, load generator, experiment runner, and result aggregation must be implemented in Java. The runner may export CSV/JSON and generate an HTML report containing SVG charts.

Suggested components:

| Component | Responsibility |
| --- | --- |
| `protocol` | Frame encoding/decoding, validation, request/response types |
| `server` | Shared connection handler and three admission/execution strategies |
| `metrics` | Counter implementations, sampling, run metadata |
| `connections` | Active registry, bounded history, snapshots, idle cleanup |
| `client` | Concurrent TCP connections, pipelining, load generation, latency collection |
| `benchmark` | Experiment matrix, run lifecycle, aggregation, charts |

Share the connection handler, protocol, payload-processing path, buffer sizes, and metrics interface across server designs. Only connection admission/execution and the selected counter implementation should change between comparable runs.

## 4. Workload and protocol

### 4.1 Workload semantics

A request uploads a logical payload to the server. The server reads every payload byte, updates byte counters, and sends a small acknowledgement after receiving the complete request. Do not echo the full payload: the primary measurement is client-to-server payload goodput.

Generate deterministic payload bytes in reusable client buffers. Stream and discard payload chunks at the server; never allocate a buffer equal to the complete logical payload. A 500 MiB request must work with bounded streaming memory.

The primary workload performs no artificial sleep or CPU work. An optional CRC32 verification mode may exercise CPU-bound processing, but it must be configured identically on both sides and reported separately from the primary workload.

### 4.2 Wire format

Use a binary, versioned protocol. All integer fields use network byte order (big-endian).

Every frame starts with a 4-byte signed Java `int`, `frameLength`, giving the number of bytes that follow, excluding the prefix. Reject negative or invalid lengths before allocating or reading the body.

Every body begins with:

| Field | Size | Meaning |
| --- | --- | --- |
| `version` | 1 byte | Protocol version, initially `1` |
| `type` | 1 byte | Frame type |
| `requestId` | 8 bytes | Positive, strictly increasing identifier within a connection |

Frame types and remaining fields:

| Type | Value | Fields after common header |
| --- | --- | --- |
| `BEGIN` | 1 | `payloadLength`: 8-byte nonnegative `long`; `flags`: 1 byte, bit 0 enables CRC32 |
| `DATA` | 2 | Between 1 and 65,536 payload bytes |
| `END` | 3 | `crc32`: 4 bytes, zero when verification is disabled |
| `ACK` | 4 | `status`: 1 byte, zero means success; `acceptedBytes`: 8-byte `long` |
| `ERROR` | 5 | `errorCode`: 2 bytes; remaining bytes are a UTF-8 diagnostic, at most 256 bytes |

The minimum body length is 10 bytes and the maximum is 65,546 bytes. Enforce exact lengths for fixed-format frames. Default maximum logical payload is 1 GiB, configurable within the nonnegative `long` range. Reject unknown versions, frame types, flags, and impossible state transitions.

`BEGIN`, zero or more `DATA` frames, and `END` constitute one request. All frames for that request use the same ID. The accumulated `DATA` length must equal the declared payload length; reject overflow or excess data. An empty payload uses `BEGIN` followed immediately by `END`.

### 4.3 Ordering and pipelining

- Requests on one connection cannot interleave their chunks.
- After writing `END` for request N, the client may immediately send request N+1 without waiting for N's `ACK`.
- The server processes requests sequentially within each connection and sends exactly one `ACK` per successful request in request order.
- The client has a configurable maximum number of outstanding requests per connection, default `16`. Setting it to `1` provides a stop-and-wait control experiment.
- Use a dedicated writer and reader per client connection, or an equivalent implementation that demonstrably drains replies while writing. A semaphore limits outstanding requests; an acknowledgement releases its request's permit.
- Publish request ID and start time in a thread-safe outstanding-request structure before sending it. A connection failure must release/fail all outstanding requests and stop both client loops.
- Flush client output at least after each `END`; flush acknowledgements promptly. Do not flush after every payload byte or chunk.

Handle short reads explicitly: TCP packet boundaries are unrelated to frame boundaries. Use exact-length reads for headers and chunk bodies. A truncated frame or EOF during a request is a failed request; EOF between requests is a normal disconnect.

On malformed input, send a bounded `ERROR` if possible and close the connection. Stable error codes must cover invalid length, unsupported protocol, invalid request sequence, payload mismatch, and checksum mismatch. An overload rejection occurs before parsing a request and is represented by connection closure, not a fabricated request acknowledgement. The client records it as an admission/connection failure.

## 5. Server designs

### 5.1 Single-threaded blocking baseline

One server thread accepts a connection and serves it synchronously until disconnect, timeout, or shutdown, then accepts the next connection. It never processes two connections concurrently. Monitoring and maintenance threads may exist but must not perform request processing.

Persistent clients can monopolize this design. Benchmark it with one active client connection for the primary comparison and document how additional connections wait in the OS backlog. Do not accidentally convert the baseline into a per-request thread pool.

### 5.2 Thread per connection

An acceptor creates one platform thread for every accepted connection. Each thread executes the shared handler until that connection closes. There is deliberately no application-imposed worker limit or bounded executor queue in this design.

Record live handler threads, active connections, failures to create threads, and resource exhaustion. The experiment runner must provide an external run deadline and abort criteria so an unsafe experiment can be stopped without adding a worker cap that changes this design.

### 5.3 Fixed pool with bounded queue

Construct a `ThreadPoolExecutor` with equal core and maximum pool sizes, W, an `ArrayBlockingQueue` with capacity Q, and no pool growth beyond W. One submitted task owns one connection for its entire lifetime. The queue therefore contains accepted connections waiting for a worker, not individual payload chunks or requests.

Use an explicit rejection policy: when the pool and queue are full, close the newly accepted socket, record an overload rejection, and move that connection to history. Never silently discard tasks or leave a rejected socket open. This satisfies the source's permitted “refuse new work” form of backpressure.

Expose queue capacity, current depth, peak depth, queue wait duration, processing connections, and rejected connections. The application can hold at most W processing connections plus Q queued connections, with at most one additional socket being admitted by the single acceptor. The OS listen backlog is separate and must be recorded in run configuration.

Queued sockets consume descriptors and kernel buffers even before a worker starts. Add a configurable queue-wait timeout; maintenance removes and closes expired queued tasks. Use atomic lifecycle transitions so a task cannot both start processing and expire.

Persistent connections occupy workers continuously. For worker-count sweeps, use exactly W client connections against the pool so that queued persistent connections do not distort normal scaling measurements. Exercise queueing and overload in separate experiments.

## 6. Counters and synchronization comparison

Provide a `MetricsCounters` interface with two interchangeable implementations:

1. `SynchronizedCounters`: updates and snapshots use one shared monitor and Java `synchronized` methods or blocks.
2. `LongAdderCounters`: one `LongAdder` per metric, with `add`, `increment`, and `decrement` as appropriate.

Both implementations must expose at least:

- Payload bytes received, incremented after each successfully read `DATA` chunk.
- Successfully completed requests, incremented after valid `END` processing.
- Active accepted connections, incremented on admission and decremented exactly once on closure, including rejected or expired connections.
- Accepted, closed, rejected, failed requests, and protocol-error totals.

Keep the update frequency identical: do not update synchronized counters per byte while updating adders per chunk. Do not hold the counter monitor across socket I/O, registry operations, or payload processing.

`LongAdder` live snapshots are approximate and are not an atomic snapshot across metrics. Never use these counters for admission limits or lifecycle correctness. Use executor bounds and atomic connection state for those decisions. Once all handlers have stopped, final counts must reconcile exactly for either implementation.

Use separate measurement-window counters/snapshots rather than resetting shared counters while connections are active. Measure both counter modes for all three designs, with identical workload and environment settings.

## 7. Connection registry and history

### 7.1 Data and lifecycle

Use a `ConcurrentHashMap<ConnectionId, ConnectionRecord>` for all accepted sockets that have not yet closed, including queued connections. Generate unique IDs per server run; do not identify a connection solely by remote address.

An active record contains ID, remote/local endpoints, accepted timestamp, monotonic acceptance time, state (`QUEUED` or `PROCESSING`), worker start time when applicable, last inbound activity time, bytes received, and completed requests. Snapshots derive connection age and idle duration using a monotonic clock. Per-connection fields must be thread-safe to read during processing.

On closure, create an immutable history entry containing the same identity, close timestamp, duration, queue wait, final connection totals, and close reason. Reasons include normal disconnect, peer reset/I/O error, protocol error, idle timeout, queue timeout, overload rejection, and server shutdown.

Centralize finalization with an atomic close-once guard. Socket closure, active-count decrement, registry removal, and history insertion must occur exactly once even when shutdown, maintenance, and a worker race. Use a short lifecycle lock for transitions and combined registry/history snapshots; do not hold it during network I/O or file export.

Keep history in a thread-safe bounded deque/ring, default 10,000 entries. Evict the oldest entry when full and expose the number evicted. Aggregate accepted/closed totals remain available after eviction. Retained history counts alone cannot reconcile lifetime totals once eviction occurs.

### 7.2 Required scenario A: lifecycle inspection

Implement a local console `connections` command that captures a consistent snapshot of both active registry and retained history, then prints or exports it. Include current connection ages and closed connection durations, final totals, and close reasons. This requires no additional network protocol or web UI.

Demonstration: connect two clients to a concurrent server, send traffic, disconnect one, and run the command. The remaining client appears as active with an increasing age; the disconnected client appears in history with a fixed duration and final counts. No ID appears in both sections of the same snapshot.

### 7.3 Required scenario B: idle cleanup and audit

Implement configurable idle cleanup using the active registry. A maintenance task identifies processing connections without inbound activity for the configured timeout, closes them through the shared finalization path, and records `IDLE_TIMEOUT` in history. Update activity while chunks are read so a long, progressing request is not treated as idle. Immediately before closing, recheck activity and state under the per-connection coordination mechanism.

Provide a local console `timeouts` command/report that combines currently idle active connections with retained historical timeout entries. Include active candidates, recent idle/queue timeout counts, and evicted-history context.

Demonstration: one client remains active while another becomes idle. After the timeout, the idle client disappears from the active registry and appears in history with the correct reason; the active client continues successfully. Queue-wait expiry is tracked separately from processing idle time.

## 8. Configuration and operation

Provide a runnable JAR with `server`, `client`, and `benchmark` subcommands. Reject invalid configuration with an actionable message and nonzero exit code.

Proposed defaults:

| Setting | Default |
| --- | --- |
| Bind address / port | `127.0.0.1:9000` |
| Server design | `pool` |
| Counter mode | `long-adder` |
| Pool workers | `Runtime.getRuntime().availableProcessors()` |
| Queue capacity | `64` |
| Listen backlog | `128` |
| Payload chunk size | `65,536` bytes |
| Logical request size | `1 MiB` |
| Pipeline depth | `16` per connection |
| Maximum logical request | `1 GiB` |
| Idle timeout / queue-wait timeout | `30 s` / `10 s` |
| History retention | `10,000` entries |
| Metrics interval | `1 s` |
| TCP_NODELAY | Enabled on client and server |
| Warm-up / measurement / repetitions | `10 s` / `30 s` / `3` |
| Drain deadline | `30 s` |

Allow configuration of connection count, request/chunk sizes, pipeline depth, workers, queue capacity, timeouts, checksum mode, output directory, run deadline, and client/server socket buffer sizes. Record both requested and effective socket settings where observable. Worker and queue settings apply only to the pool; reject incompatible options rather than suggesting they tune the baseline.

Examples of the intended CLI contract:

```sh
./mvnw clean verify
java -jar target/astra.jar server --design pool --workers 8 --queue-capacity 64 --counters long-adder
java -jar target/astra.jar client --host 127.0.0.1 --port 9000 --connections 8 --payload-bytes 1048576 --pipeline-depth 16 --duration-seconds 30
java -jar target/astra.jar benchmark --config experiments/default.json --output results/run-001
```

Startup output must identify the design, counter mode, protocol version, limits, and effective configuration. Avoid per-request logging during measurements.

Shutdown must stop admission, allow in-flight requests to drain to a deadline, close remaining queued and active sockets, finalize history, stop executors, and write a final metrics summary. A timed-out drain is reported as an incomplete run, not a successful benchmark. Client shutdown stops new requests, drains acknowledgements to a deadline, then closes connections and joins its reader/writer threads.

## 9. Benchmark design

### 9.1 Controlled experiment matrix

Let P be the recorded number of available logical processors. Sweep fixed-pool workers through the unique sorted values in `{1, 2, 4, 8, P, 2P, 4P}`; apply a declared resource budget before launching large runs. Extend the sweep if feasible and no ceiling has appeared.

For each counter mode:

1. Run the baseline with one connection as the throughput reference.
2. Run the fixed pool at each W with W client connections.
3. Run thread-per-connection at the corresponding connection counts, recording the actual handler count.
4. At selected worker counts, repeat with pipeline depth `1` and `16` to isolate pipelining's effect.
5. Run a separate overload experiment with more clients than W + Q, including deliberately persistent/slow connections, and measure rejections, queue bounds, and recovery.

Use a fixed 1 MiB payload and 64 KiB chunks for the primary matrix. Add smaller requests and at least one 500 MiB streaming correctness run; report these separately. Counter comparisons must have matching design, W, connection count, payload, pipeline depth, and verification mode.

Warm up the JVM before collecting measurements. Repeat each measured configuration at least three times and vary execution order to reduce systematic thermal or background-load bias. Restart the server between independent trials to isolate retained state. No fixed throughput target is required because results depend on hardware.

### 9.2 Measurement boundaries and outputs

The Java runner controls server/client processes and explicit warm-up, measurement, and drain phases through local process control. All workload traffic remains TCP. Use monotonic clocks for elapsed time. Do not reset active request state at a phase boundary.

Primary throughput is server payload bytes consumed during the measurement interval divided by its elapsed duration. Count only `DATA` payload bytes, excluding protocol headers and acknowledgements. Report it as MiB/s and Gbit/s with units defined. Bytes from a request spanning a boundary count in the interval in which they are consumed.

Also report successfully acknowledged payload goodput at the client, measured separately, so failed or partial requests cannot be hidden by high receive throughput. Request rate is completed requests per second. Latency runs from before sending `BEGIN` to receiving its `ACK`; report p50/p95/p99 for the declared cohort of requests started in the measurement window, including those acknowledged during drain. Record failures and requests missing at the drain deadline separately rather than dropping them from the results.

Collect:

- Payload throughput, completed requests/s, latency percentiles, errors, and rejection rate.
- Process CPU use, JVM heap usage, GC time/count, platform thread count, and process RSS when available.
- Active/processing/queued connections, queue peaks and waits, accepted/closed totals.
- Client CPU and throughput, network utilization where available, and relevant OS resource limits.

Normalize CPU reporting explicitly (one core = 100%, or total machine = 100%). Use Java management APIs where possible; clearly mark OS-specific or unavailable metrics. A profiling pass may be used to support a contention/GC diagnosis, but label it separately because instrumentation changes timing.

Write raw samples to CSV, effective configuration/environment metadata to JSON, per-run summaries, and an aggregate report. Include source revision when available, JDK/JVM options, OS, CPU/core count, RAM, host topology, NIC/link information when known, and timestamps.

### 9.3 Finding and explaining the ceiling

Plot throughput against W for the pool, with separate series for the two counter modes and variability across repetitions. Show the baseline as a reference. Plot thread-per-connection against connection/handler count and label the differing x-axis meaning explicitly. Add queue/rejection and CPU/GC plots where they explain a result.

Use an initial plateau heuristic of less than 5% median throughput improvement across two successive increases in worker count, provided differences are not overwhelmed by run variability. This is an analysis aid, not proof of a bottleneck. A sustained decline may indicate contention or oversubscription rather than a stable plateau.

Distinguish server saturation from a client, network, OS, or shared-host limit. Use evidence and a targeted follow-up experiment: for example, inspect CPU/GC/monitor contention, compare counter modes, increase load-generator capacity, or repeat across separate hosts if available. Record hypotheses that remain unverified. Do not label a flat graph “memory bandwidth limited” based only on heap usage.

If hardware or runtime limits prevent identifying the ceiling, report the highest observed throughput, tested range, and limiting evidence. Do not fabricate a ceiling. A run without a defensible ceiling explanation does not yet satisfy the project's main experimental objective.

## 10. Validation and acceptance criteria

| Area | Required evidence |
| --- | --- |
| Java implementation | Maven Wrapper builds the executable JAR and runs the test suite on Java 21 |
| Shared TCP protocol | The same client completes workloads against all three designs without protocol-specific changes |
| Framing | Tests cover fragmented headers/bodies, multiple frames in one write, invalid lengths/types, and truncated input |
| Chunking | A 500 MiB request succeeds with a JVM heap smaller than the payload, for example `-Xmx128m` in an isolated streaming test |
| Pipelining | A test sends multiple complete requests before reading any replies, then verifies ordered IDs and exact byte counts; sustained load drains replies concurrently |
| Baseline | Instrumentation confirms at most one connection handler executes at a time |
| Thread-per-connection | Concurrent accepted connections get distinct platform handler threads without an application worker cap |
| Pool | Handler count never exceeds W and queue depth never exceeds Q; excess connections are closed and counted |
| Backpressure recovery | An overloaded pool accepts new work after existing handlers/queued connections finish, with no socket/task leaks |
| Counters | Both implementations yield exact expected final byte/request/connection totals after concurrent activity stops |
| Connection lifecycle | Disconnect, rejection, timeout, protocol failure, and shutdown each finalize a connection exactly once |
| Registry scenarios | Lifecycle inspection and idle cleanup/audit demonstrate both active and historical records |
| Cleanup races | Tests cover queued-task start versus expiry and worker close versus maintenance/shutdown |
| Bounded history | Old entries are evicted at capacity and eviction totals are exposed |
| Failures | Client disconnects mid-request, resets, checksum errors, and stalled peers do not corrupt shared state or permanently occupy pool capacity |
| Shutdown | No accepted sockets, client loops, or executor threads remain after the shutdown deadline |
| Experiment | Raw data and repeatable configuration cover three designs, both counter modes, the worker sweep, and overload |
| Conclusion | Report identifies the observed ceiling with supporting measurements and describes experimental limitations |

Use deterministic concurrency coordination and condition-based waits in tests rather than fragile fixed sleeps. Do not assert hardware-specific throughput thresholds in automated tests.

## 11. Implementation milestones and deliverables

1. **Protocol and baseline:** Maven project, frame codec, streaming request handler, baseline server, and basic Java client; framing/streaming tests pass.
2. **Concurrency designs:** thread-per-connection and fixed-pool strategies, bounded admission, queue expiry, pipelining, and reliable shutdown; concurrency and overload tests pass.
3. **Shared state:** both counter modes, active registry, bounded history, console inspection, idle cleanup/audit; lifecycle and reconciliation tests pass.
4. **Experiment tooling:** Java runner, configuration files, metrics sampling, raw exports, report/chart generation, and environment capture.
5. **Measured results:** repeated worker sweep, counter and pipelining comparisons, overload demonstration, targeted bottleneck investigation, and final explanation.

Deliver source code, `pom.xml`, Maven Wrapper, tests, runnable JAR, example experiment configurations, README commands, representative raw results, generated charts, and a report explaining the scaling ceiling and tradeoffs between the three designs.
