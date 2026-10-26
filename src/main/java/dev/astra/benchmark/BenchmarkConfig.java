package dev.astra.benchmark;

import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import com.google.gson.*;
import dev.astra.cli.Json;

public final class BenchmarkConfig {
    public List<Integer> workers = defaultWorkers();
    public List<Integer> pipelineDepths = List.of(16);
    public List<String> counters = List.of("synchronized", "long-adder");
    public int repetitions = 3, maxWorkers = 64, maxThreadsPerProcess = 512, maxRssMiBPerProcess = 1024;
    public double warmupSeconds = 10, durationSeconds = 30, drainSeconds = 30;
    public long payloadBytes = 1L << 20, seed = 20261026;
    public int chunkBytes = 65_536, queueCapacity = 64, metricsIntervalMillis = 1000;
    public boolean checksum, overload = true;
    public List<String> serverJvmArgs = List.of("-Xms128m", "-Xmx256m");
    public List<String> clientJvmArgs = List.of("-Xms128m", "-Xmx256m");

    public static BenchmarkConfig load(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Benchmark configuration must be a JSON object");
        Set<String> fields = new HashSet<>();
        for (var field : BenchmarkConfig.class.getFields()) fields.add(field.getName());
        for (String key : parsed.getAsJsonObject().keySet())
            if (!fields.contains(key)) throw new IllegalArgumentException("Unknown benchmark setting: " + key);
        BenchmarkConfig config = Json.COMPACT.fromJson(parsed, BenchmarkConfig.class);
        config.validate();
        return config;
    }

    private static List<Integer> defaultWorkers() {
        int p = Runtime.getRuntime().availableProcessors();
        return new TreeSet<>(List.of(1, 2, 4, 8, p, 2 * p, 4 * p)).stream().toList();
    }
    public void validate() {
        if (workers == null || workers.isEmpty() || workers.stream().anyMatch(w -> w == null || w < 1 || w > maxWorkers)
                || pipelineDepths == null || pipelineDepths.isEmpty() || pipelineDepths.stream().anyMatch(p -> p == null || p < 1)
                || counters == null || counters.isEmpty() || counters.stream().anyMatch(c -> !List.of("synchronized", "long-adder").contains(c))
                || repetitions < 1 || maxWorkers < 1 || maxThreadsPerProcess < 8 || maxRssMiBPerProcess < 64
                || !Double.isFinite(warmupSeconds) || warmupSeconds < 0 || !Double.isFinite(durationSeconds) || durationSeconds <= 0
                || !Double.isFinite(drainSeconds) || drainSeconds <= 0 || warmupSeconds + durationSeconds + drainSeconds > 86_400
                || payloadBytes < 0 || chunkBytes < 1 || chunkBytes > 65_536 || queueCapacity < 1 || metricsIntervalMillis < 1
                || serverJvmArgs == null || clientJvmArgs == null)
            throw new IllegalArgumentException("Invalid benchmark configuration or worker/resource budget exceeded; see docs/CLI.md");
    }
}
