# Astra — learn TCP scaling in Java

Astra implements the same streaming TCP protocol with three Java 21 server designs: a blocking baseline, one platform thread per connection, and a fixed pool with a bounded queue. A pipelined load client and experiment runner show how throughput, latency, shared counters, and backpressure change as concurrency increases.

The quickstart runs a complete upload and benchmark. Then follow the [study guide](docs/STUDY_GUIDE.md) to learn how the project builds up from TCP framing to the scaling experiments. Each lesson has a tagged Git checkpoint you can run.

## Build and try it

Use a JDK, version 21 or later. Check `java -version` and `javac -version` in your terminal. If you have multiple Java installations, point `JAVA_HOME` at the JDK and put its `bin` directory first on `PATH`.

From the repository root, build the JAR with the Maven Wrapper. The first build downloads Maven and its dependencies.

```sh
./mvnw clean verify
```

If you use mise, this command selects the Temurin 21 JDK for the build.

```sh
mise exec java@temurin-21 -- ./mvnw clean verify
```

Start the server in one terminal.

```sh
java -jar target/astra.jar server --design pool --workers 4 --counters long-adder
```

Wait for the server's `READY` event. Then open another terminal in the repository root and run the client. If port 9000 is occupied, pass the same alternate `--port` to both commands.

```sh
java -jar target/astra.jar client --connections 4 --pipeline-depth 16 --duration-seconds 10
```

The client prints a `RESULT` event. Look for `complete: true` and matching values for `started` and `acknowledged`. In the server terminal, `connections` shows active clients and closed history, `sample` shows live counters, and `timeouts` shows the timeout audit. When the client finishes, type `stop` in the server terminal and look for the `STOPPED` event.

For `--design baseline`, omit `--workers` and run the client with `--connections 1`. For `--design thread-per-connection`, omit `--workers` and keep all four connections.

## Run an experiment

```sh
mkdir -p results
smoke_output=$(mktemp -d results/smoke.XXXXXX)
java -jar target/astra.jar benchmark --config experiments/smoke.json --output "$smoke_output" &&
  printf 'Open %s/report.html\n' "$smoke_output"
```

Open the printed `report.html` path in a browser. Each run gets its own directory, so you can repeat the command after a rehearsal. Use the [saved measurements](docs/RESULTS.md) to present the scaling results and the smoke run to demonstrate the workflow. Use `experiments/default.json` for longer repeated trials. The output includes raw CSV samples, JSON configuration and summaries, per-process logs, and standalone SVG charts in an HTML report.

- [Study guide](docs/STUDY_GUIDE.md): the reading order and exercises.
- [Architecture](docs/ARCHITECTURE.md): how one request moves through the system.
- [CLI reference](docs/CLI.md): every option, result format, and experiment controls.
- [Measured results](docs/RESULTS.md): 177 saved trials, CPU-capacity control, pipelining comparison, and limitations.
- [Requirement map](docs/REQUIREMENTS.md): where each source requirement is implemented and tested.
- [Implementation specification](SPEC.md): requirements and acceptance criteria.

All application traffic uses TCP. The runner controls local JVMs through process pipes. No account, database, web service, or GUI is needed to build or run Astra.

The Java 21 test suite includes a 500 MiB transfer in a 128 MiB test JVM. [GitHub Actions](https://github.com/husamemadH/astra/actions/workflows/build.yml) runs the tests and the separate-JVM smoke benchmark.
