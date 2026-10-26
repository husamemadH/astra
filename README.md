# Astra

A Java 21 laboratory for understanding TCP servers, threads, shared counters, and throughput ceilings. Start with [the learning guide](docs/STUDY_GUIDE.md), then follow the commits in chronological order.

Build with Java 21 or later:

```sh
./mvnw clean verify
java -jar target/astra.jar
```

The original implementation contract is in [SPEC.md](SPEC.md). The repository grows through small, explained lessons; later commits add the server, client, experiments, and measured results.
