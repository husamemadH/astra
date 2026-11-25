# Running Astra

Use a JDK, version 21 or later. `./mvnw clean verify` creates `target/astra.jar`.

Start a server in one terminal:

```sh
java -jar target/astra.jar server --design pool --workers 4 --queue-capacity 64 --counters long-adder --output results/manual-server
```

Run traffic in another:

```sh
java -jar target/astra.jar client --connections 4 --pipeline-depth 16 --warmup-seconds 2 --duration-seconds 10 --output results/manual-client
```

Type `connections`, `timeouts`, or `sample` in the server terminal to inspect it. The server drains and emits `STOPPED` after `stop`, standard input EOF, or a JVM shutdown signal such as SIGTERM. Signal-based exit codes follow the JVM and OS conventions. Output is one JSON event per line. Without an output directory, no result files are written.

The `READY` event includes the actual bound port and server configuration. With `--output`, `server-config.json` also records the full environment details. The client ends the run early if all connections fail and reports the errors in `RESULT`.

All options take explicit values; for example `--checksum true`. Unknown or repeated options are errors. Exit code 0 means success, 1 means an incomplete workload/drain, and 2 means configuration or execution failure.

| Server option | Default / meaning |
| --- | --- |
| `--host`, `--port` | `127.0.0.1`, `9000`; port `0` requests an ephemeral port |
| `--design` | `pool`; also `baseline` or `thread-per-connection` |
| `--workers`, `--queue-capacity` | Available processors, `64`; accepted only for pool |
| `--counters` | `long-adder`; also `synchronized` |
| `--backlog` | `128`; OS listen backlog, distinct from the application queue |
| `--max-payload-bytes` | `1073741824` |
| `--idle-timeout-ms`, `--queue-timeout-ms` | `30000`, `10000` |
| `--history-capacity` | `10000` |
| `--drain-seconds` | `30` |

| Client option | Default / meaning |
| --- | --- |
| `--host`, `--port` | `127.0.0.1`, `9000` |
| `--connections` | `1` |
| `--payload-bytes`, `--chunk-bytes` | `1048576`, `65536` |
| `--pipeline-depth` | `16`; use `1` for stop-and-wait |
| `--warmup-seconds`, `--duration-seconds`, `--drain-seconds` | `0`, `30`, `30` |
| `--connect-timeout-ms` | `5000` per socket |

Both commands support `--checksum` (false; must match), `--tcp-no-delay` (true), `--socket-buffer-bytes` (0 = OS default), `--metrics-interval-ms` (1000), and `--output DIR`. Actual buffer sizes are recorded because OS values may differ from the request.

The client measures ACK goodput within its time window. Latencies cover requests started inside that window, including replies received during drain; histogram bucket upper bounds have at most about 3.125% relative quantization. Failed requests and missing replies are reported separately. The benchmark lesson adds server payload throughput using server-side boundary snapshots.

## Automated experiments

```sh
java -jar target/astra.jar benchmark --config experiments/default.json --output results/run-001
```

The output directory must be empty. Configurations are JSON with the fields documented in `BenchmarkConfig.java`. Omitted fields use its defaults. If `workers` is omitted, the runner selects the unique values from `{1,2,4,8,P,2P,4P}` that fit within `maxWorkers`, where P is the JVM's available processor count. Explicit worker counts must all fit within the budget. The runner tests all three designs and both counter modes unless `counters` is explicitly narrowed. Fixed-pool connections equal workers. The thread-per-connection design uses the matching connection count, and the baseline uses one.

A run has an external deadline of warm-up + measurement + drain + 30 seconds for startup/control. `maxWorkers` bounds the experiment matrix, while `maxThreadsPerProcess` and `maxRssMiBPerProcess` abort runaway child processes on Linux. These are runner safeguards, not a concurrency cap inside the unbounded server. OS resource checks are unavailable off Linux; deadlines still apply. `serverJvmArgs` and `clientJvmArgs` allow explicit heap/GC/profiling controls and are passed as argument lists, without a shell.

`summary.csv` records MiB/s and Gbit/s; `report.html` shows median throughput and min/max whiskers, baseline references, and server/client CPU curves. Each trial has raw output, configuration, periodic samples, boundary snapshots, and final counters/history. Overload runs use W=1/Q=1, hold two connections open, prove the third is closed, and verify recovery. Server history includes queue waits and rejection reasons.

## Two registry demonstrations

These Java integration tests perform and assert both scenarios without needing an extra TCP client tool:

```sh
./mvnw -Dtest=RegistryTest#lifecycleInspectionContainsActiveAndClosedClients test
./mvnw -Dtest=RegistryTest#idleCleanupUsesRegistryAndAuditsHistoryWhileOtherClientWorks test
```

To observe them manually, run the server and type `connections` while a load client runs, then again after it finishes. For an idle client, Java's `jshell` can open a socket and keep it alive without sending data:

```java
var idle = new java.net.Socket("127.0.0.1", 9000);
```

With `--idle-timeout-ms 2000` on the server, `timeouts` will show its closed history entry after about two seconds. Run a second, active load client concurrently to see it continue processing. The retained timeout list is bounded by history capacity and is not a lifetime total.

Later builds also record whole-host CPU busy/total ticks on Linux. Their delta ratio describes host utilization, not just Astra and not just a restricted affinity mask. This helps expose background competition on a shared workstation; the older checked-in experiment schema may omit these additional columns.
