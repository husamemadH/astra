package dev.astra.metrics;

import java.util.*;

/** Observations only: never use approximate counters to enforce resource limits. */
public interface MetricsCounters {
    enum Counter { BYTES, REQUESTS, ACTIVE, ACCEPTED, CLOSED, REJECTED, FAILED_REQUESTS, PROTOCOL_ERRORS, IO_ERRORS, THREAD_START_FAILURES }
    void add(Counter counter, long delta);
    long get(Counter counter);
    Map<String, Long> snapshot();
    default void increment(Counter counter) { add(counter, 1); }

    static MetricsCounters create(String mode) {
        return switch (mode) {
            case "synchronized" -> new SynchronizedCounters();
            case "long-adder" -> new LongAdderCounters();
            default -> throw new IllegalArgumentException("counters must be synchronized or long-adder");
        };
    }
}
