package dev.astra.benchmark;

import com.google.gson.*;
import dev.astra.cli.Json;
import dev.astra.metrics.ProcessMetrics;
import dev.astra.client.SimpleClient;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BenchmarkRunner {
    public record Trial(String design, String counters, int workers, int connections, int pipeline, int repetition) {}
    public record Summary(String run, Trial trial, boolean complete, double serverMiBs, double clientMiBs,
                          double requestsPerSecond, double p50Ms, double p95Ms, double p99Ms,
                          double serverCpuPercent, double clientCpuPercent, double serverSeconds,
                          long failedRequests, long rejected, long serverGcMillis, long serverBytes,
                          int peakProcessing, String error) {}
    private final BenchmarkConfig config;
    private final Path output;

    public BenchmarkRunner(BenchmarkConfig config, Path output) { config.validate(); this.config = config; this.output = output; }
    public static List<Trial> matrix(BenchmarkConfig c) {
        List<Trial> trials = new ArrayList<>();
        for (int repetition = 1; repetition <= c.repetitions; repetition++)
            for (String counter : c.counters) for (int pipeline : c.pipelineDepths) {
                trials.add(new Trial("baseline", counter, 1, 1, pipeline, repetition));
                for (int workers : new TreeSet<>(c.workers)) {
                    trials.add(new Trial("pool", counter, workers, workers, pipeline, repetition));
                    trials.add(new Trial("thread-per-connection", counter, workers, workers, pipeline, repetition));
                }
            }
        Collections.shuffle(trials, new Random(c.seed));
        return trials;
    }
    public List<Summary> run() throws Exception {
        if (Files.exists(output)) {
            try (var files = Files.list(output)) {
                if (files.findAny().isPresent()) throw new IOException("Output directory must be empty: " + output);
            }
        }
        Files.createDirectories(output);
        Json.write(output.resolve("config.json"), config);
        Json.write(output.resolve("environment.json"), ProcessMetrics.environment());
        List<Trial> matrix = matrix(config);
        Json.write(output.resolve("plan.json"), matrix);
        List<Summary> summaries = new ArrayList<>();
        for (int i = 0; i < matrix.size(); i++) {
            String id = String.format(Locale.ROOT, "run-%03d", i + 1);
            Trial trial = matrix.get(i);
            Json.event("TRIAL", Map.of("index", i + 1, "total", matrix.size(), "trial", trial));
            try {
                summaries.add(trial(id, trial));
                Report.write(output, summaries);
            } catch (Exception e) {
                Json.write(output.resolve("failure.json"), Map.of("run", id, "trial", trial, "error", e.toString()));
                Report.write(output, summaries);
                throw e;
            }
        }
        if (config.overload) for (String counter : config.counters) overload(counter);
        Report.write(output, summaries);
        return List.copyOf(summaries);
    }
    private Summary trial(String id, Trial trial) throws Exception {
        Path directory = output.resolve(id);
        Files.createDirectories(directory);
        Json.write(directory.resolve("trial.json"), trial);
        long deadline = System.nanoTime() + (long) ((config.warmupSeconds + config.durationSeconds + config.drainSeconds + 30) * 1e9);
        List<String> serverArgs = serverArgs(trial.design(), trial.counters(), trial.workers(), directory);
        try (var server = new ChildJvm(config.serverJvmArgs, serverArgs, directory, "server", config)) {
            int port = server.await("READY", deadline).get("port").getAsInt();
            var clientArgs = new ArrayList<>(List.of("client", "--port", Integer.toString(port), "--connections", Integer.toString(trial.connections()),
                    "--pipeline-depth", Integer.toString(trial.pipeline()), "--payload-bytes", Long.toString(config.payloadBytes),
                    "--chunk-bytes", Integer.toString(config.chunkBytes), "--warmup-seconds", Double.toString(config.warmupSeconds),
                    "--duration-seconds", Double.toString(config.durationSeconds), "--drain-seconds", Double.toString(config.drainSeconds),
                    "--checksum", Boolean.toString(config.checksum), "--metrics-interval-ms", Integer.toString(config.metricsIntervalMillis),
                    "--output", directory.toString()));
            try (var client = new ChildJvm(config.clientJvmArgs, clientArgs, directory, "client", config)) {
                JsonObject clientStart = client.await("WINDOW_START", deadline);
                server.command("sample START"); JsonObject start = server.await("START", deadline);
                JsonObject clientEnd = client.await("WINDOW_END", deadline);
                server.command("sample END"); JsonObject end = server.await("END", deadline);
                JsonObject result = client.await("RESULT", deadline);
                int clientExit = client.exit(deadline);
                server.command("stop"); JsonObject stopped = server.await("STOPPED", deadline);
                int serverExit = server.exit(deadline);
                Json.write(directory.resolve("boundaries.json"), Map.of("serverStart", start, "serverEnd", end,
                        "clientStart", clientStart, "clientEnd", clientEnd));
                double seconds = (number(end, "nano_time") - number(start, "nano_time")) / 1e9;
                double clientSeconds = (number(clientEnd, "nano_time") - number(clientStart, "nano_time")) / 1e9;
                if (seconds <= 0 || clientSeconds <= 0) throw new IOException("Invalid measurement interval");
                long bytes = integer(end, "bytes") - integer(start, "bytes");
                boolean complete = result.get("complete").getAsBoolean() && stopped.get("complete").getAsBoolean()
                        && clientExit == 0 && serverExit == 0;
                var summary = new Summary(id, trial, complete, bytes / 1048576.0 / seconds, number(result, "mibPerSecond"),
                        (integer(end, "requests") - integer(start, "requests")) / seconds,
                        number(result, "p50Millis"), number(result, "p95Millis"), number(result, "p99Millis"),
                        (number(end, "cpu_nanos") - number(start, "cpu_nanos")) / (seconds * 1e9) * 100,
                        (number(clientEnd, "cpu_nanos") - number(clientStart, "cpu_nanos")) / (clientSeconds * 1e9) * 100, seconds,
                        integer(result, "failedRequests"), integer(end, "rejected"), integer(end, "gc_millis") - integer(start, "gc_millis"),
                        bytes, end.get("peak_processing").getAsInt(), complete ? "" : "Client failure or incomplete drain");
                Json.write(directory.resolve("summary.json"), summary);
                if (!complete) throw new IOException("Incomplete trial " + id + "; inspect result and summary files");
                return summary;
            }
        }
    }
    private List<String> serverArgs(String design, String counters, int workers, Path directory) {
        var args = new ArrayList<>(List.of("server", "--port", "0", "--design", design, "--counters", counters,
                "--checksum", Boolean.toString(config.checksum), "--max-payload-bytes", Long.toString(Math.max(1L << 30, config.payloadBytes)),
                "--drain-seconds", Double.toString(config.drainSeconds), "--metrics-interval-ms", Integer.toString(config.metricsIntervalMillis),
                "--output", directory.toString()));
        if (design.equals("pool")) args.addAll(List.of("--workers", Integer.toString(workers), "--queue-capacity", Integer.toString(config.queueCapacity)));
        return args;
    }
    private void overload(String counters) throws Exception {
        Path directory = output.resolve("overload-" + counters);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        List<String> args = serverArgs("pool", counters, 1, directory);
        int queueIndex = args.indexOf("--queue-capacity") + 1; args.set(queueIndex, "1");
        try (var server = new ChildJvm(config.serverJvmArgs, args, directory, "server", config)) {
            int port = server.await("READY", deadline).get("port").getAsInt();
            JsonObject saturated;
            try (var first = new Socket("127.0.0.1", port); var second = new Socket("127.0.0.1", port)) {
                do {
                    server.command("sample SATURATED"); saturated = server.await("SATURATED", deadline);
                } while (integer(saturated, "processing") != 1 || integer(saturated, "queued") != 1);
                try (var excess = new Socket("127.0.0.1", port)) {
                    excess.setSoTimeout(3_000);
                    if (excess.getInputStream().read() != -1) throw new IOException("Overload did not close excess socket");
                }
                server.command("connections");
                Json.write(directory.resolve("saturated-connections.json"), server.await("CONNECTIONS", deadline));
            }
            JsonObject recovered;
            do { server.command("sample RECOVERY"); recovered = server.await("RECOVERY", deadline); }
            while (integer(recovered, "active") != 0);
            if (SimpleClient.upload("127.0.0.1", port, 1024, 1, config.checksum) != 1024) throw new IOException("Recovery upload failed");
            server.command("stop"); JsonObject stopped = server.await("STOPPED", deadline);
            if (server.exit(deadline) != 0) throw new IOException("Overload server did not drain");
            Json.write(directory.resolve("evidence.json"), Map.of("saturated", saturated, "recovered", recovered, "final", stopped));
        }
    }
    private static double number(JsonObject object, String key) { return object.get(key).getAsDouble(); }
    private static long integer(JsonObject object, String key) { return object.get(key).getAsLong(); }
}
