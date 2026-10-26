# 09 — Measure a curve instead of guessing a winner

Read `BenchmarkRunner.matrix`, `trial`, `ChildJvm`, then `Report`. Each trial launches fresh server and client JVMs. A seeded shuffle varies execution order. The client emits warm-up/measurement boundaries; the runner asks the server for snapshots and computes payload throughput from server byte and monotonic-time differences.

Run a short functional check:

```sh
java -jar target/astra.jar benchmark --config experiments/smoke.json --output results/smoke
```

Open `results/smoke/report.html`. These very short trials test the machinery, not a scientific performance claim. `study.json` repeats a wider six-processor sweep; `default.json` uses the runtime's processor count and longer windows. `pipelining.json` compares depths 1 and 16 with smaller requests. Check your machine and adjust explicit worker lists before using study presets elsewhere.

Inspect a trial's `boundaries.json`, `server-samples.csv`, `client-result.json`, and `summary.json`. Server and client boundary clocks are never subtracted across JVMs. Server commands follow client events, introducing a small marker delay that matters especially in short runs. Failed trials stop the experiment and keep their logs instead of silently entering the successful median.

Question: if adding workers stops improving throughput, what does that prove? Only that this configuration stopped scaling. CPU time, GC, client load, queue behavior, and controlled follow-up experiments help distinguish the cause.
