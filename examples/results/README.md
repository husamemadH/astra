# Saved experiment evidence

Start with [the interpreted results](../../docs/RESULTS.md). This directory contains 177 successful repeated trials plus overload evidence, all captured on the same six-processor workstation.

| Directory | Purpose | Trials |
| --- | --- | ---: |
| `study/` | Three server designs, two counter modes, seven concurrency settings plus baseline | 90 |
| `cpu-control/` | Same workload restricted to three CPU IDs, LongAdder only | 27 |
| `pipelining/` | 64 KiB requests, pipeline depths 1 and 16, both counter modes | 60 |

Each suite includes a standalone `report.html`, `summary.csv`, configuration, environment, randomized plan, and artifact provenance. Each `run-*` directory includes raw process logs, periodic CSV samples, boundary snapshots, summaries, and final lifecycle/counter records. The primary suite also includes `overload-synchronized/` and `overload-long-adder/`.

These files are generated evidence and are marked as such in `.gitattributes` so GitHub collapses their diffs. To study the final commit's explanation without thousands of raw-data lines:

```sh
git show lesson-12 -- docs/RESULTS.md docs/lessons/12-results.md
```

The result metadata records the original experiment environment and executable. Current source may include later hardening; read `artifact-provenance.json` for the exact lesson-09 source revision and executable digest used consistently across these suites.
