package dev.astra.connections;

import dev.astra.metrics.MetricsCounters;
import java.net.Socket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static dev.astra.metrics.MetricsCounters.Counter.*;

/** The lifecycle lock makes active/history membership snapshots consistent. It never protects I/O. */
public final class ConnectionRegistry {
    public enum Reason { NORMAL, IO_ERROR, PROTOCOL_ERROR, IDLE_TIMEOUT, QUEUE_TIMEOUT, OVERLOAD_REJECTION, SERVER_SHUTDOWN, THREAD_START_FAILURE }
    public record View(long id, String remote, String local, String acceptedAt, String closedAt,
                       String state, double ageMillis, double idleMillis, double queueWaitMillis,
                       long bytes, long requests, String reason) {}
    public record Snapshot(List<View> active, List<View> history, long evicted) {}

    public static final class Entry {
        public final long id;
        public final Socket socket;
        private final long acceptedNanos = System.nanoTime();
        private final String acceptedAt = Instant.now().toString();
        private final String remote, local;
        private long lastActivity = acceptedNanos, startedNanos, bytes, requests;
        private boolean started, finished;
        private Reason reason;

        private Entry(long id, Socket socket) {
            this.id = id; this.socket = socket;
            remote = String.valueOf(socket.getRemoteSocketAddress());
            local = String.valueOf(socket.getLocalSocketAddress());
        }
        public synchronized boolean start() {
            if (started || finished || reason != null) return false;
            started = true;
            startedNanos = lastActivity = System.nanoTime();
            return true;
        }
        public synchronized boolean started() { return started; }
        public synchronized void touch() { if (!finished) lastActivity = System.nanoTime(); }
        public synchronized void bytes(int count) { bytes += count; }
        public synchronized void completed() { requests++; }
        public synchronized boolean requestClose(Reason why) {
            if (finished || reason != null) return false;
            reason = why;
            return true;
        }
        /** Recheck the clock and activity while holding the same lock used by touch/start. */
        public synchronized Reason expire(long now, long idleNanos, long queueNanos) {
            if (finished || reason != null) return null;
            if (!started && now - acceptedNanos >= queueNanos) return reason = Reason.QUEUE_TIMEOUT;
            if (started && now - lastActivity >= idleNanos) return reason = Reason.IDLE_TIMEOUT;
            return null;
        }
        private synchronized View view(long now, String closedAt) {
            long queuedUntil = started ? startedNanos : now;
            return new View(id, remote, local, acceptedAt, closedAt, closedAt == null ? (started ? "PROCESSING" : "QUEUED") : "CLOSED",
                    (now - acceptedNanos) / 1e6, (now - lastActivity) / 1e6,
                    (queuedUntil - acceptedNanos) / 1e6, bytes, requests, reason == null ? null : reason.name());
        }
    }

    private final ConcurrentHashMap<Long, Entry> active = new ConcurrentHashMap<>();
    private final ArrayDeque<View> history = new ArrayDeque<>();
    private final Object lifecycle = new Object();
    private final AtomicLong ids = new AtomicLong();
    private final MetricsCounters counters;
    private final int capacity;
    private long evicted;

    public ConnectionRegistry(MetricsCounters counters, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("history capacity must be positive");
        this.counters = counters; this.capacity = capacity;
    }
    public Entry accept(Socket socket) {
        synchronized (lifecycle) {
            Entry entry = new Entry(ids.incrementAndGet(), socket);
            active.put(entry.id, entry);
            counters.increment(ACCEPTED); counters.increment(ACTIVE);
            return entry;
        }
    }
    /** Called after processing has stopped, so the immutable history contains final totals. */
    public void finish(Entry entry, Reason fallback) {
        synchronized (lifecycle) {
            synchronized (entry) {
                if (entry.finished) return;
                entry.finished = true;
                if (entry.reason == null) entry.reason = fallback;
                active.remove(entry.id);
                if (history.size() == capacity) { history.removeFirst(); evicted++; }
                history.addLast(entry.view(System.nanoTime(), Instant.now().toString()));
                counters.add(ACTIVE, -1); counters.increment(CLOSED);
            }
        }
    }
    public List<Entry> entries() { return List.copyOf(active.values()); }
    public int size() { return active.size(); }
    public Snapshot snapshot() {
        synchronized (lifecycle) {
            long now = System.nanoTime();
            return new Snapshot(active.values().stream().map(e -> e.view(now, null)).sorted(Comparator.comparingLong(View::id)).toList(),
                    List.copyOf(history), evicted);
        }
    }
    public Map<String, Object> timeoutReport(long idleMillis) {
        Snapshot snapshot = snapshot();
        List<View> candidates = snapshot.active().stream().filter(e -> e.state().equals("PROCESSING") && e.idleMillis() >= idleMillis).toList();
        List<View> timedOut = snapshot.history().stream().filter(e -> e.reason().endsWith("TIMEOUT")).toList();
        return Map.of("candidates", candidates, "recentTimeouts", timedOut,
                "idleTimeouts", timedOut.stream().filter(e -> e.reason().equals("IDLE_TIMEOUT")).count(),
                "queueTimeouts", timedOut.stream().filter(e -> e.reason().equals("QUEUE_TIMEOUT")).count(), "evicted", snapshot.evicted());
    }
}
