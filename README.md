# Astra — learn TCP scaling in Java

Astra implements the same streaming TCP protocol with three Java 21 server designs: a blocking baseline, one platform thread per connection, and a fixed pool with a bounded queue. A pipelined load client and experiment runner show how throughput, latency, shared counters, and backpressure change as concurrency increases.

**Start with [the study guide](docs/STUDY_GUIDE.md).** The Git history is deliberately ordered as a course. Each numbered commit adds one concept, working code, tests, and a short lesson. You can run earlier commits in a separate worktree.

## Build and try it

Install Java 21 or later. The Maven Wrapper supplies Maven; the first build downloads dependencies.

```sh
./mvnw clean verify
java -jar target/astra.jar server --design pool --workers 4 --counters long-adder
```

In another terminal:

```sh
java -jar target/astra.jar client --connections 4 --pipeline-depth 16 --duration-seconds 10
```

Type `connections`, `timeouts`, or `sample` in the server terminal to inspect state. Type `stop` after the client finishes. To compare other designs, use `--design baseline` or `--design thread-per-connection` and omit `--workers`.

## Run an experiment

```sh
java -jar target/astra.jar benchmark --config experiments/smoke.json --output results/smoke
```

Open `results/smoke/report.html`. The smoke run checks functionality; its short measurements are not performance conclusions. Use `experiments/default.json` for longer repeated trials. The output includes raw CSV samples, JSON configuration and summaries, per-process logs, and standalone SVG charts in an HTML report.

- [Study guide](docs/STUDY_GUIDE.md): the reading order and exercises.
- [Architecture](docs/ARCHITECTURE.md): how one request moves through the system.
- [CLI reference](docs/CLI.md): every option, result format, and experiment controls.
- [Measured results](docs/RESULTS.md): 177 saved trials, CPU-capacity control, pipelining comparison, and limitations.
- [Requirement map](docs/REQUIREMENTS.md): where each source requirement is implemented and tested.
- [Implementation specification](SPEC.md): requirements and acceptance criteria.

All application traffic uses TCP. The runner controls local JVMs through process pipes. No account, database, web service, or GUI is needed to build or run Astra.

The final Java 21 build passes 38 tests, including a 500 MiB transfer in a 128 MiB test JVM. GitHub Actions runs the tests and a separate-JVM smoke benchmark. Annotated tags `lesson-01` through `lesson-12` identify the learning checkpoints.
