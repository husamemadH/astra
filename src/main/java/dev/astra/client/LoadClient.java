package dev.astra.client;

import dev.astra.protocol.Protocol;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** One writer plus one reply reader per socket prevents pipeline buffer deadlocks. */
public final class LoadClient {
    public record Result(boolean complete, long started, long acknowledged, long failedRequests,
                         long measuredStarted, long measuredAcknowledged, long measuredFailed,
                         long acknowledgedBytesInWindow, double seconds, double mibPerSecond, double requestsPerSecond,
                         double p50Millis, double p95Millis, double p99Millis, long latencySamples,
                         int peakOutstandingPerConnection, int connectionFailures, int effectiveSendBuffer,
                         int effectiveReceiveBuffer, List<String> errors) {}
    private record Pending(long id, long started, boolean measured) {}
    private final ClientConfig config;
    private final List<Connection> connections = new ArrayList<>();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder started = new LongAdder(), acknowledged = new LongAdder(), measuredStarted = new LongAdder(),
            measuredAcknowledged = new LongAdder(), windowBytes = new LongAdder(), windowRequests = new LongAdder();
    private final AtomicInteger connectionFailures = new AtomicInteger();
    private volatile long windowStart, windowEnd;
    private final CountDownLatch go = new CountDownLatch(1);

    public LoadClient(ClientConfig config) { config.validate(); this.config = config; }

    /** Phase events let the external runner sample the separate server JVM at window boundaries. */
    public Result run(Consumer<String> phase) throws InterruptedException {
        try {
            for (int i = 0; i < config.connections; i++) {
                try { connections.add(new Connection(i)); }
                catch (IOException e) { recordFailure("connect: " + e.getMessage()); }
            }
            long now = System.nanoTime();
            windowStart = now + nanos(config.warmupSeconds);
            windowEnd = windowStart + nanos(config.durationSeconds);
            for (Connection c : connections) { c.reader.start(); c.writer.start(); }
            go.countDown();
            sleepUntil(windowStart); phase.accept("WINDOW_START");
            sleepUntil(windowEnd); phase.accept("WINDOW_END");
            long deadline = windowEnd + nanos(config.drainSeconds);
            for (Connection c : connections) { joinUntil(c.writer, deadline); joinUntil(c.reader, deadline); }
            for (Connection c : connections) {
                if (c.writer.isAlive() || c.reader.isAlive()) c.fail("drain deadline exceeded");
            }
        } finally {
            go.countDown();
            for (Connection c : connections) {
                try { c.socket.close(); } catch (IOException ignored) { }
                c.writer.interrupt(); c.reader.interrupt();
            }
            for (Connection c : connections) { c.writer.join(2_000); c.reader.join(2_000); }
        }
        LatencyHistogram latencies = new LatencyHistogram();
        int peak = 0, send = 0, receive = 0;
        for (Connection c : connections) {
            latencies.merge(c.latencies); peak = Math.max(peak, c.peak.get());
            send = c.sendBuffer; receive = c.receiveBuffer;
        }
        long failed = started.sum() - acknowledged.sum();
        return new Result(connectionFailures.get() == 0 && failed == 0, started.sum(), acknowledged.sum(), failed,
                measuredStarted.sum(), measuredAcknowledged.sum(), measuredStarted.sum() - measuredAcknowledged.sum(),
                windowBytes.sum(), config.durationSeconds, windowBytes.sum() / 1048576.0 / config.durationSeconds,
                windowRequests.sum() / config.durationSeconds, latencies.percentileMillis(50), latencies.percentileMillis(95),
                latencies.percentileMillis(99), latencies.count(), peak, connectionFailures.get(), send, receive, List.copyOf(errors));
    }

    private void recordFailure(String message) {
        connectionFailures.incrementAndGet();
        synchronized (errors) { if (errors.size() < 20) errors.add(message); }
    }
    private static long nanos(double seconds) { return (long) (seconds * 1e9); }
    private static void sleepUntil(long deadline) throws InterruptedException {
        long left; while ((left = deadline - System.nanoTime()) > 0) TimeUnit.NANOSECONDS.sleep(left);
    }
    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        long left = deadline - System.nanoTime();
        if (left > 0) thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
    }

    private final class Connection {
        private final Socket socket = new Socket();
        private final Semaphore slots = new Semaphore(config.pipelineDepth);
        private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicInteger outstanding = new AtomicInteger(), peak = new AtomicInteger();
        private final LatencyHistogram latencies = new LatencyHistogram();
        private final Thread writer, reader;
        private final int sendBuffer, receiveBuffer;
        private volatile boolean writingDone;

        Connection(int index) throws IOException {
            try {
                socket.setTcpNoDelay(config.tcpNoDelay);
                if (config.socketBufferBytes > 0) {
                    socket.setSendBufferSize(config.socketBufferBytes); socket.setReceiveBufferSize(config.socketBufferBytes);
                }
                socket.connect(new InetSocketAddress(config.host, config.port), config.connectTimeoutMillis);
                sendBuffer = socket.getSendBufferSize(); receiveBuffer = socket.getReceiveBufferSize();
                writer = Thread.ofPlatform().name("astra-client-writer-" + index).unstarted(this::write);
                reader = Thread.ofPlatform().name("astra-client-reader-" + index).unstarted(this::read);
            } catch (IOException e) { socket.close(); throw e; }
        }
        void fail(String message) {
            if (failed.compareAndSet(false, true)) {
                recordFailure(message);
                try { socket.close(); } catch (IOException ignored) { }
                writer.interrupt();
            }
        }
        void write() {
            try {
                go.await();
                var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), Protocol.CHUNK));
                byte[] chunk = Protocol.payloadBuffer(config.chunkBytes);
                for (long id = 1; !failed.get() && System.nanoTime() < windowEnd; id++) {
                    if (!slots.tryAcquire(20, TimeUnit.MILLISECONDS)) { id--; continue; }
                    long start = System.nanoTime();
                    if (failed.get() || start >= windowEnd) { slots.release(); break; }
                    boolean measured = start >= windowStart;
                    pending.add(new Pending(id, start, measured));
                    peak.accumulateAndGet(outstanding.incrementAndGet(), Math::max);
                    started.increment(); if (measured) measuredStarted.increment();
                    Protocol.request(out, id, config.payloadBytes, chunk, config.checksum);
                }
                writingDone = true;
                socket.shutdownOutput(); // All requests are sent; replies may still arrive on the other half.
            } catch (IOException | InterruptedException e) { if (!failed.get()) fail("writer: " + e.getMessage()); }
            finally { writingDone = true; }
        }
        void read() {
            try {
                go.await();
                var in = new Protocol.Reader(new BufferedInputStream(socket.getInputStream(), Protocol.CHUNK));
                while (!failed.get()) {
                    var ack = in.read();
                    if (ack == null) {
                        if (!writingDone || !pending.isEmpty()) fail("connection closed before all replies (possible admission refusal)");
                        return;
                    }
                    Pending request = pending.peek();
                    if (request == null || ack.type() != Protocol.ACK || ack.id() != request.id() || ack.value() != config.payloadBytes)
                        throw new IOException("Unexpected reply: type=" + ack.type() + ", id=" + ack.id());
                    pending.remove(); outstanding.decrementAndGet();
                    long now = System.nanoTime();
                    acknowledged.increment();
                    if (now >= windowStart && now < windowEnd) { windowBytes.add(ack.value()); windowRequests.increment(); }
                    if (request.measured()) { measuredAcknowledged.increment(); latencies.record(now - request.started()); }
                    slots.release();
                }
            } catch (IOException | InterruptedException e) { if (!failed.get()) fail("reader: " + e.getMessage()); }
        }
    }
}
