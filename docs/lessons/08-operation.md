# 08 — Turn the experiment into a usable command-line program

Read `Main`, `Options`, `ProcessMetrics`, and `MetricsSampler`. Main translates user options into the same configuration objects used in tests. It does not contain protocol or thread scheduling logic.

Follow `docs/CLI.md` to run a server and client in two terminals. Inspect `connections` during traffic, then again after the client exits. Try a short idle timeout with an idle TCP client and inspect `timeouts`. JSON snapshots and CSV samples make the same observations available to the next lesson's runner.

CPU time is cumulative nanoseconds. To compute utilization over an interval, divide the CPU-time difference by the elapsed wall-time difference and multiply by 100. Here 100% means one logical processor, so a multithreaded process can exceed 100%.

Question: why record both the requested socket buffer and the effective buffer? The OS may clamp, enlarge, or otherwise adjust the request. Reproducible experiments describe what actually ran.
