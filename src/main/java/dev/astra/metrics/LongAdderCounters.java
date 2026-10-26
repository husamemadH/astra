package dev.astra.metrics;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Updates spread across cells under contention; a running snapshot is approximate. */
public final class LongAdderCounters implements MetricsCounters {
    private final LongAdder[] values = new LongAdder[Counter.values().length];
    public LongAdderCounters() { Arrays.setAll(values, i -> new LongAdder()); }
    public void add(Counter counter, long delta) { values[counter.ordinal()].add(delta); }
    public long get(Counter counter) { return values[counter.ordinal()].sum(); }
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) result.put(counter.name().toLowerCase(Locale.ROOT), get(counter));
        return Collections.unmodifiableMap(result);
    }
}
