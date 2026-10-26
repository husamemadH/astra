# 05 — Correct counting when threads run together

Read `MetricsCounters`, `SynchronizedCounters`, and `LongAdderCounters`. An expression like `count++` reads, changes, and writes a value; concurrent threads can overwrite each other's work. `synchronized` protects the entire update with a monitor. `LongAdder` distributes updates across internal cells and sums them when asked.

Both implementations update once per received chunk and once per completed request. Comparing different update frequencies would make an unfair experiment. Live LongAdder totals can represent slightly different instants, so admission decisions use the executor's real bounds instead.

Run `CountersTest`. Eight workers perform known amounts of work, then stop. At that point both counter strategies must agree exactly. Real TCP tests also reconcile bytes, requests, and active connections after shutdown.

Question: which implementation must be faster? Neither is guaranteed. Low contention, snapshot frequency, and other bottlenecks can change the outcome; later benchmarks will measure this rather than assume it.
