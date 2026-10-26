package dev.astra.metrics;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Periodic scalar CSV samples. Errors are retained and surfaced when the sampler closes. */
public final class MetricsSampler implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("astra-sampler").factory());
    private final BufferedWriter writer;
    private final Supplier<Map<String, Object>> source;
    private List<String> columns;
    private volatile IOException failure;
    public MetricsSampler(Path path, long intervalMillis, Supplier<Map<String, Object>> source) throws IOException {
        if (intervalMillis < 1) throw new IllegalArgumentException("metrics interval must be positive");
        Files.createDirectories(path.toAbsolutePath().getParent());
        writer = Files.newBufferedWriter(path); this.source = source;
        executor.scheduleWithFixedDelay(this::sample, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
    private synchronized void sample() {
        if (failure != null) return;
        try {
            Map<String, Object> values = source.get();
            if (columns == null) { columns = List.copyOf(values.keySet()); writer.write(String.join(",", columns)); writer.newLine(); }
            writer.write(String.join(",", columns.stream().map(k -> String.valueOf(values.get(k))).toList()));
            writer.newLine(); writer.flush();
        } catch (Exception e) { failure = e instanceof IOException io ? io : new IOException("Sampling failed", e); }
    }
    public void close() throws IOException {
        executor.shutdownNow();
        try { if (!executor.awaitTermination(2, TimeUnit.SECONDS)) throw new IOException("Sampler did not stop"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        sample(); writer.close();
        if (failure != null) throw failure;
    }
}
