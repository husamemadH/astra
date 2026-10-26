package dev.astra;

import dev.astra.cli.*;
import dev.astra.benchmark.*;
import dev.astra.client.*;
import dev.astra.server.*;
import dev.astra.metrics.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try {
            int code = run(args);
            if (code != 0) System.exit(code);
        } catch (Exception e) {
            System.err.println("Astra: " + e.getMessage()); System.exit(2);
        }
    }
    public static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println("""
                    Astra — Java TCP scaling laboratory
                    server [--design baseline|thread-per-connection|pool] [--port 9000] [--workers N]
                           [--queue-capacity 64] [--counters synchronized|long-adder] [--output DIR]
                    client [--host 127.0.0.1] [--port 9000] [--connections N] [--pipeline-depth 16]
                           [--payload-bytes 1048576] [--warmup-seconds 0] [--duration-seconds 30]
                    benchmark --config experiments/default.json --output results/run-001
                    Every option takes a value; booleans use true or false. See docs/CLI.md.
                    Server console commands: connections, timeouts, sample, stop.
                    """);
            return 0;
        }
        return switch (args[0]) {
            case "server" -> server(args);
            case "client" -> client(args);
            case "benchmark" -> benchmark(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0] + "; use --help");
        };
    }
    private static int benchmark(String[] args) throws Exception {
        var options = new Options(args, 1, "config", "output");
        var config = options.has("config") ? Json.COMPACT.fromJson(Files.readString(Path.of(options.text("config", ""))), BenchmarkConfig.class) : new BenchmarkConfig();
        if (config == null) throw new IllegalArgumentException("Benchmark configuration must be a JSON object");
        var summaries = new BenchmarkRunner(config, Path.of(options.text("output", "results/benchmark"))).run();
        Json.event("BENCHMARK_COMPLETE", Map.of("trials", summaries.size(), "output", options.text("output", "results/benchmark")));
        return 0;
    }
    private static int server(String[] args) throws Exception {
        var o = new Options(args, 1, "host", "port", "design", "workers", "queue-capacity", "counters", "backlog",
                "max-payload-bytes", "checksum", "idle-timeout-ms", "queue-timeout-ms", "history-capacity",
                "socket-buffer-bytes", "tcp-no-delay", "drain-seconds", "metrics-interval-ms", "output");
        var c = new ServerConfig();
        c.host = o.text("host", c.host); c.port = o.integer("port", c.port);
        c.design = ServerConfig.Design.valueOf(o.text("design", "pool").toUpperCase(Locale.ROOT).replace('-', '_'));
        if (c.design != ServerConfig.Design.POOL && (o.has("workers") || o.has("queue-capacity")))
            throw new IllegalArgumentException("workers and queue-capacity apply only to the pool design");
        c.workers = o.integer("workers", c.workers); c.queueCapacity = o.integer("queue-capacity", c.queueCapacity);
        c.counters = o.text("counters", c.counters); c.backlog = o.integer("backlog", c.backlog);
        c.maxPayload = o.longValue("max-payload-bytes", c.maxPayload); c.checksum = o.bool("checksum", c.checksum);
        c.idleTimeoutMillis = o.longValue("idle-timeout-ms", c.idleTimeoutMillis);
        c.queueTimeoutMillis = o.longValue("queue-timeout-ms", c.queueTimeoutMillis);
        c.historyCapacity = o.integer("history-capacity", c.historyCapacity);
        c.socketBufferBytes = o.integer("socket-buffer-bytes", 0); c.tcpNoDelay = o.bool("tcp-no-delay", true);
        double drain = o.decimal("drain-seconds", 30);
        if (!Double.isFinite(drain) || drain < 0 || drain > 86_400) throw new IllegalArgumentException("Invalid drain-seconds");
        Path output = o.has("output") ? Path.of(o.text("output", "")) : null;
        try (var server = new TcpServer(c)) {
            Thread hook = new Thread(() -> { try { server.close(); } catch (IOException ignored) { } }, "astra-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try (var sampler = output == null ? null : new MetricsSampler(output.resolve("server-samples.csv"),
                    o.longValue("metrics-interval-ms", 1000), () -> sample(server))) {
                var ready = Map.of("port", server.port(), "protocol", 1, "config", c, "environment", ProcessMetrics.environment());
                if (output != null) Json.write(output.resolve("server-config.json"), ready);
                Json.event("READY", ready);
                var console = new BufferedReader(new InputStreamReader(System.in));
                String command;
                while ((command = console.readLine()) != null) {
                    if (command.equals("stop")) break;
                    if (command.equals("connections")) Json.event("CONNECTIONS", server.registry().snapshot());
                    else if (command.equals("timeouts")) Json.event("TIMEOUTS", server.registry().timeoutReport(c.idleTimeoutMillis));
                    else if (command.equals("sample") || command.startsWith("sample "))
                        Json.event(command.equals("sample") ? "SAMPLE" : command.substring(7), sample(server));
                    else Json.event("COMMAND_ERROR", "Expected connections, timeouts, sample, or stop");
                }
                boolean complete = server.shutdown(Duration.ofNanos((long) (drain * 1e9)));
                var finalState = Map.of("complete", complete, "metrics", sample(server), "connections", server.registry().snapshot());
                if (output != null) Json.write(output.resolve("server-final.json"), finalState);
                Json.event("STOPPED", finalState);
                return complete ? 0 : 1;
            } finally { Runtime.getRuntime().removeShutdownHook(hook); }
        }
    }
    public static Map<String, Object> sample(TcpServer server) {
        var values = new LinkedHashMap<>(ProcessMetrics.snapshot()); values.putAll(server.snapshot()); return values;
    }
    private static int client(String[] args) throws Exception {
        var o = new Options(args, 1, "host", "port", "connections", "pipeline-depth", "chunk-bytes", "payload-bytes",
                "warmup-seconds", "duration-seconds", "drain-seconds", "checksum", "socket-buffer-bytes", "tcp-no-delay",
                "connect-timeout-ms", "metrics-interval-ms", "output");
        var c = new ClientConfig();
        c.host = o.text("host", c.host); c.port = o.integer("port", c.port); c.connections = o.integer("connections", c.connections);
        c.pipelineDepth = o.integer("pipeline-depth", c.pipelineDepth); c.chunkBytes = o.integer("chunk-bytes", c.chunkBytes);
        c.payloadBytes = o.longValue("payload-bytes", c.payloadBytes); c.warmupSeconds = o.decimal("warmup-seconds", c.warmupSeconds);
        c.durationSeconds = o.decimal("duration-seconds", c.durationSeconds); c.drainSeconds = o.decimal("drain-seconds", c.drainSeconds);
        c.checksum = o.bool("checksum", c.checksum); c.socketBufferBytes = o.integer("socket-buffer-bytes", 0);
        c.tcpNoDelay = o.bool("tcp-no-delay", true); c.connectTimeoutMillis = o.integer("connect-timeout-ms", c.connectTimeoutMillis);
        c.validate();
        Path output = o.has("output") ? Path.of(o.text("output", "")) : null;
        if (output != null) Json.write(output.resolve("client-config.json"), Map.of("config", c, "environment", ProcessMetrics.environment()));
        try (var sampler = output == null ? null : new MetricsSampler(output.resolve("client-samples.csv"),
                o.longValue("metrics-interval-ms", 1000), ProcessMetrics::snapshot)) {
            var result = new LoadClient(c).run(phase -> Json.event(phase, ProcessMetrics.snapshot()));
            if (output != null) Json.write(output.resolve("client-result.json"), result);
            Json.event("RESULT", result);
            return result.complete() ? 0 : 1;
        }
    }
}
