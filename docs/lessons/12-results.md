# 12 — Explain what the measurements do and do not establish

Read `docs/RESULTS.md` alongside `examples/results/study/report.html`. Start with the W=1 and W=2 points; then compare higher worker counts, repetition ranges, latency, and CPU. Open the raw CSV when you want to check a sentence in the report.

The CPU-control experiment changes one resource: both JVMs inherit an affinity mask with three logical processors instead of six. It uses the same frozen JAR, payload, pipeline depth, and durations. The pipelining experiment separately changes pipeline depth with smaller payloads. Neither control should be mixed into the primary curve without labeling the changed condition.

Every saved experiment includes an artifact digest and source revision in `artifact-provenance.json`. A checkout revision alone can be misleading if you launch a JAR built earlier; the final application therefore also records the actual executable's SHA-256 digest.

Question: why keep noisy results? Reproducibility includes variation and limitations. Deleting slow trials to make a smooth curve would conceal information rather than explain the system.
