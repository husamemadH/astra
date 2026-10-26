package dev.astra.benchmark;

import com.google.gson.*;
import dev.astra.cli.Json;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A separate JVM with bounded lifetime, captured logs, and optional Linux resource checks. */
final class ChildJvm implements AutoCloseable {
    private final Process process;
    private final BufferedWriter commands;
    private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    private final Thread reader;
    private final BenchmarkConfig config;
    private volatile IOException readFailure;

    ChildJvm(List<String> vmArgs, List<String> args, Path directory, String name, BenchmarkConfig config) throws IOException {
        this.config = config;
        Files.createDirectories(directory);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(vmArgs);
        command.add("-cp"); command.add(System.getProperty("java.class.path")); command.add("dev.astra.Main"); command.addAll(args);
        process = new ProcessBuilder(command).redirectError(directory.resolve(name + "-stderr.log").toFile()).start();
        commands = process.outputWriter();
        reader = Thread.ofPlatform().name("astra-child-output-" + name).start(() -> {
            try (var input = process.inputReader(); var log = Files.newBufferedWriter(directory.resolve(name + "-stdout.jsonl"))) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.write(line); log.newLine(); log.flush();
                    try {
                        var value = JsonParser.parseString(line);
                        if (value.isJsonObject() && value.getAsJsonObject().has("event")) events.add(value.getAsJsonObject());
                    } catch (JsonParseException ignored) { /* JVM diagnostics remain in raw stdout. */ }
                }
            } catch (IOException e) { readFailure = e; }
        });
    }
    void command(String command) throws IOException { commands.write(command); commands.newLine(); commands.flush(); }
    JsonObject await(String name, long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            budget();
            JsonObject event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.get("event").getAsString().equals(name)) return event.getAsJsonObject("data");
            if (readFailure != null) throw readFailure;
            if (!process.isAlive() && !reader.isAlive() && events.isEmpty())
                throw new IOException("Child exited " + process.exitValue() + " before " + name + "; inspect its logs");
        }
        throw new IOException("External run deadline exceeded waiting for " + name);
    }
    private void budget() throws IOException {
        Path status = Path.of("/proc", Long.toString(process.pid()), "status");
        if (!Files.exists(status)) return;
        List<String> lines;
        try { lines = Files.readAllLines(status); } catch (NoSuchFileException e) { return; }
        for (String line : lines) {
            if (line.startsWith("VmRSS:") && Long.parseLong(line.trim().split("\\s+")[1]) > config.maxRssMiBPerProcess * 1024L)
                throw new IOException("External RSS budget exceeded for child " + process.pid());
            if (line.startsWith("Threads:") && Integer.parseInt(line.trim().split("\\s+")[1]) > config.maxThreadsPerProcess)
                throw new IOException("External thread budget exceeded for child " + process.pid());
        }
    }
    int exit(long deadline) throws Exception {
        long left = deadline - System.nanoTime();
        if (left <= 0 || !process.waitFor(left, TimeUnit.NANOSECONDS)) throw new IOException("Child did not exit by deadline");
        reader.join(2_000);
        return process.exitValue();
    }
    public void close() {
        try { commands.close(); } catch (IOException ignored) { }
        if (process.isAlive()) process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
            reader.join(2_000);
        } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
