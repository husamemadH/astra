package dev.astra.metrics;

import java.util.*;

/** One shared monitor: contending workers must take turns updating any metric. */
public final class SynchronizedCounters implements MetricsCounters {
    private final long[] values = new long[Counter.values().length];
    public synchronized void add(Counter counter, long delta) { values[counter.ordinal()] += delta; }
    public synchronized long get(Counter counter) { return values[counter.ordinal()]; }
    public synchronized Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) result.put(counter.name().toLowerCase(Locale.ROOT), values[counter.ordinal()]);
        return Collections.unmodifiableMap(result);
    }
}
