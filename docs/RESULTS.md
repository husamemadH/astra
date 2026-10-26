# Measured results: throughput grows, then CPU capacity and scheduling dominate

Astra's repeated loopback experiment shows a clear improvement from one to two workers, then a broad, noisy saturation region. More workers do not give proportional throughput, and high concurrency increases tail latency. Restricting the same server/client workload to three processors lowers its observed ceiling to about 3,000 MiB/s. This supports CPU availability and scheduling as a major limit for the **combined server/client setup**; it is not a measurement of an isolated server's maximum capacity.

The full data is included so you can check the conclusions:

- [Primary scaling charts](../examples/results/study/report.html) and [90-trial CSV](../examples/results/study/summary.csv).
- [Three-processor control charts](../examples/results/cpu-control/report.html) and [27-trial CSV](../examples/results/cpu-control/summary.csv).
- [Pipelining charts](../examples/results/pipelining/report.html) and [60-trial CSV](../examples/results/pipelining/summary.csv).

Download or open the HTML files locally; GitHub may display their source instead of rendering them.

## Environment and method

The measurements ran on 2026-10-26, on an Intel Core i5-9400F with six logical processors and about 15.6 GiB RAM, Linux 7.2.11, and OpenJDK 21.0.12.1. Server and client were separate JVMs on the same workstation, communicating through TCP loopback. Both used `-Xms128m -Xmx256m`. TCP_NODELAY was enabled. The workstation was not isolated from background desktop activity.

The primary workload used 1 MiB requests, 64 KiB chunks, no checksum, and pipeline depth 16. Every configuration had 1.5 seconds of warm-up, 3 seconds of measurement, and three repetitions in seeded shuffled order. The pool used W connections for W workers. Thread-per-connection used the matching number of connections; baseline used one. Default presets support longer 10/30-second windows; these shorter study runs demonstrate behavior rather than provide a precision hardware characterization.

Reported throughput is the server's validated DATA-byte delta divided by its own monotonic elapsed time. Client ACK goodput is also saved. One MiB is 1,048,576 bytes; Gbit/s values in the CSV are payload-rate conversions, not physical NIC rates. Latency starts before sending BEGIN and ends at ACK; p99 includes measured requests acknowledged during drain. CPU is normalized so one fully used logical processor is 100%.

All three saved experiment suites use the identical frozen lesson-09 JAR. Each directory includes `artifact-provenance.json` with its source revision and SHA-256. The old environment field `revision` describes the launch checkout, which may be newer than the executable. Final code adds explicit artifact identity, admission-race hardening, and stronger monitoring; its correctness and final CLI are verified separately.

## Primary fixed-pool curve

Values below are medians of three repetitions. The range is the actual minimum and maximum, not a confidence interval. The p99 column is the median of the three per-run p99 values, not a pooled percentile.

| W | LongAdder MiB/s | LongAdder min–max | synchronized MiB/s | LongAdder p99 ms | LongAdder server + client CPU |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,966 | 1,821–2,191 | 1,861 | 1.18 | 179% |
| 2 | 3,915 | 3,522–4,707 | 4,089 | 2.75 | 332% |
| 4 | 5,306 | 2,149–5,719 | 5,535 | 9.96 | 487% |
| 6 | 3,651 | 3,487–5,623 | 4,787 | 17.83 | 424% |
| 8 | 5,547 | 3,989–7,266 | 4,367 | 23.07 | 523% |
| 12 | 4,289 | 2,843–6,727 | 6,458 | 36.70 | 470% |
| 24 | 4,796 | 4,647–7,372 | 4,620 | 48.23 | 513% |

With LongAdder, W=1 to W=2 approximately doubles median throughput from 1,966 to 3,915 MiB/s. Higher counts do not keep doubling: W=8 reaches a median 5,547 MiB/s, while W=24 gives 4,796 MiB/s and much higher latency. The synchronized series peaks at a median 6,458 MiB/s at W=12, but its repetitions vary widely. These runs do not justify declaring either counter implementation consistently faster.

The blocking baseline's median is 2,209 MiB/s with LongAdder and 1,875 MiB/s with synchronized counters. Thread-per-connection also scales beyond one connection and later becomes variable; for example its LongAdder W/C=4 median is 5,500 MiB/s and C=24 is 4,708 MiB/s. In healthy runs both concurrent designs execute the same handler, so large speed differences are not expected merely from changing how platform threads are created. The fixed pool's clear benefit here is bounded overload behavior.

The six-processor results support a **broad observed saturation region**, not a precise flat ceiling or a clean less-than-5% plateau. Repetition ranges overlap heavily, and background scheduling and short windows are material limitations. Preserve those ranges when explaining the graph.

## CPU-capacity control

The control used the same frozen JAR and primary workload, LongAdder counters, three repetitions, and the same warm-up/measurement durations. The launcher restricted the runner and both child JVMs to CPU IDs 0,1,2:

```sh
taskset -c 0,1,2 java -jar target/astra.jar benchmark --config experiments/cpu-control.json --output results/cpu-control
```

CPU IDs are machine-specific; inspect your allowed CPUs before reproducing this command. The affinity change also changes the JVM's reported processor count and therefore may affect its ergonomic thread choices.

| W | Six-processor pool MiB/s | Three-processor pool MiB/s | Three-processor combined CPU |
| --- | ---: | ---: | ---: |
| 1 | 1,966 | 2,116 | 185% |
| 2 | 3,915 | 3,149 | 263% |
| 4 | 5,306 | 2,625 | 272% |
| 8 | 5,547 | 3,011 | 272% |

The restricted pool stops improving beyond roughly 3,000 MiB/s while consuming about 263–272% combined CPU out of 300% available to the affinity mask. The restricted thread-per-connection medians are 2,980 MiB/s at C=4 and 2,987 MiB/s at C=8, another direct example of a ceiling despite more handlers. In the primary pool at W=8, the pair uses about 523% CPU and achieves 5,547 MiB/s. Reducing available CPU capacity lowers the high-concurrency throughput substantially while one-worker throughput remains similar.

This controlled change supports CPU availability and scheduling as a major bottleneck in this end-to-end workload. It does not identify the exact share spent in Java, TCP system calls, memory copies, or contention. Server GC pauses total at most 15 ms per primary measurement window, making long GC pauses an unlikely dominant explanation for these curves. Memory-bandwidth saturation was not measured and is not claimed. An isolated-server limit would require a separate load-generator host and further profiling; these results concern the local pair.

## Pipelining comparison

The separate small-request experiment uses 64 KiB logical requests, pipeline depths 1 and 16, W=1 and W=6, both counters, and three repetitions. Warm-up is 1 second and measurement is 3 seconds. All 60 trials completed without failed requests.

| Pool counter mode | W | Depth 1 MiB/s | Depth 16 MiB/s | Depth 1 p99 ms | Depth 16 p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| long-adder | 1 | 839 | 1,530 | 0.15 | 0.59 |
| long-adder | 6 | 2,325 | 3,217 | 0.42 | 4.59 |
| synchronized | 1 | 741 | 1,507 | 0.18 | 0.62 |
| synchronized | 6 | 2,337 | 3,270 | 0.46 | 4.98 |

Pipelining changes how much waiting can overlap. With one request outstanding, the writer must wait for each reply. A deeper pipeline allows more useful work to continue, but increases the amount of work waiting for service and can increase tail latency. Compare within one row: the smaller request size makes these a separate experiment from the 1 MiB primary curve. Run-to-run variation still applies; the full charts show min/max ranges.


## Backpressure and correctness evidence

In each counter mode, the overload experiment holds W=1 processing connection and Q=1 queued connection. The third socket is refused. After the held connections close, a fourth connection successfully uploads 1,024 bytes. Final counters report accepted=4, closed=4, rejected=1, active=0, peak processing=1, and peak queue=1. The raw active/history snapshot and final evidence are saved under the primary suite's `overload-*` directories.

All 38 automated Java tests pass on Java 21. They verify fragmented/coalesced frames, invalid input, ordered pipelining, both counter implementations, lifecycle and queue races, idle cleanup, history eviction, resets, and shutdown cleanup. A 500 MiB request succeeds in a JVM limited to a 128 MiB heap, demonstrating streaming memory use.

## What to study next

Use the curves to predict behavior before changing code. Try increasing the request size while keeping chunks fixed, changing pipeline depth with W held constant, and extending warm-up and measurement windows. Run one controlled change at a time. For a more precise ceiling, use a quiet machine and a separate load-generator host, preserve raw samples, and investigate the remaining CPU cost with Java Flight Recorder in a separately labeled profiling run.
