package dev.astra.server;

import dev.astra.protocol.Protocol;
import dev.astra.protocol.ProtocolException;
import dev.astra.metrics.MetricsCounters;
import dev.astra.connections.ConnectionRegistry;
import dev.astra.connections.ConnectionRegistry.Entry;
import dev.astra.connections.ConnectionRegistry.Reason;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.astra.metrics.MetricsCounters.Counter.*;

/** One shared handler, three scheduling strategies, and a single close/finalization path. */
public final class TcpServer implements AutoCloseable {
    private final ServerConfig config;
    private final ServerSocket listener;
    private final Thread acceptor;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService maintenance;
    private final Set<Thread> handlers = ConcurrentHashMap.newKeySet();
    private final MetricsCounters counters;
    private final ConnectionRegistry registry;
    private final AtomicInteger processing = new AtomicInteger(), peakProcessing = new AtomicInteger(), peakQueue = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile int effectiveSendBuffer, effectiveReceiveBuffer;

    public TcpServer(String host, int port) throws IOException { this(baseline(host, port)); }
    private static ServerConfig baseline(String host, int port) {
        var c = new ServerConfig(); c.host = host; c.port = port; c.design = ServerConfig.Design.BASELINE; return c;
    }
    public TcpServer(ServerConfig config) throws IOException {
        config.validate();
        this.config = config;
        counters = MetricsCounters.create(config.counters);
        registry = new ConnectionRegistry(counters, config.historyCapacity);
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        try { listener.bind(new InetSocketAddress(config.host, config.port), config.backlog); }
        catch (IOException e) { listener.close(); throw e; }
        pool = config.design == ServerConfig.Design.POOL
                ? new ThreadPoolExecutor(config.workers, config.workers, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(config.queueCapacity), Thread.ofPlatform().name("astra-worker-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy()) : null;
        maintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("astra-maintenance").factory());
        long interval = Math.max(5, Math.min(100, Math.min(config.idleTimeoutMillis, config.queueTimeoutMillis) / 4));
        maintenance.scheduleWithFixedDelay(this::expire, interval, interval, TimeUnit.MILLISECONDS);
        acceptor = Thread.ofPlatform().name("astra-acceptor").start(this::accept);
    }

    private void accept() {
        while (running) {
            Entry entry = null;
            try {
                Socket socket = listener.accept();
                entry = registry.accept(socket);
                if (!running) { closeEntry(entry, Reason.SERVER_SHUTDOWN); break; }
                socket.setTcpNoDelay(config.tcpNoDelay);
                if (config.socketBufferBytes > 0) {
                    socket.setSendBufferSize(config.socketBufferBytes);
                    socket.setReceiveBufferSize(config.socketBufferBytes);
                }
                effectiveSendBuffer = socket.getSendBufferSize();
                effectiveReceiveBuffer = socket.getReceiveBufferSize();
                var task = new ConnectionTask(entry);
                switch (config.design) {
                    case BASELINE -> task.run();
                    case THREAD_PER_CONNECTION -> {
                        Thread thread = Thread.ofPlatform().name("astra-connection-" + entry.id).unstarted(task);
                        handlers.add(thread);
                        try { thread.start(); }
                        catch (OutOfMemoryError e) {
                            handlers.remove(thread);
                            counters.increment(THREAD_START_FAILURES);
                            closeEntry(entry, Reason.THREAD_START_FAILURE);
                        }
                    }
                    case POOL -> {
                        try { entry.enqueueIfOpen(() -> pool.execute(task)); peakQueue.accumulateAndGet(queued(), Math::max); }
                        catch (RejectedExecutionException e) {
                            if (running) counters.increment(REJECTED);
                            closeEntry(entry, running ? Reason.OVERLOAD_REJECTION : Reason.SERVER_SHUTDOWN);
                        }
                    }
                }
            } catch (IOException e) {
                if (entry != null) closeEntry(entry, Reason.IO_ERROR);
                if (running) counters.increment(IO_ERRORS);
            }
        }
    }

    private final class ConnectionTask implements Runnable {
        private final Entry entry;
        private ConnectionTask(Entry entry) { this.entry = entry; }
        public void run() {
            try {
                if (!entry.start()) return;
                peakProcessing.accumulateAndGet(processing.incrementAndGet(), Math::max);
                Reason reason = Reason.NORMAL;
                try {
                    // Observe raw socket progress, even when a large frame arrives in many reads.
                    InputStream activity = new FilterInputStream(entry.socket.getInputStream()) {
                        public int read() throws IOException {
                            int value = in.read(); if (value >= 0) entry.touch(); return value;
                        }
                        public int read(byte[] b, int off, int len) throws IOException {
                            int count = in.read(b, off, len); if (count > 0) entry.touch(); return count;
                        }
                    };
                    RequestHandler.serve(new BufferedInputStream(activity, Protocol.CHUNK), entry.socket.getOutputStream(),
                            config.maxPayload, config.checksum, new RequestHandler.Events() {
                                public void bytes(int count) { entry.bytes(count); counters.add(BYTES, count); }
                                public void completed() { entry.completed(); counters.increment(REQUESTS); }
                                public void failed() { counters.increment(FAILED_REQUESTS); }
                            });
                } catch (ProtocolException e) { reason = Reason.PROTOCOL_ERROR; counters.increment(PROTOCOL_ERRORS); }
                catch (IOException e) { reason = Reason.IO_ERROR; if (running) counters.increment(IO_ERRORS); }
                finally {
                    closeSocket(entry);
                    registry.finish(entry, reason);
                    processing.decrementAndGet();
                }
            } finally { handlers.remove(Thread.currentThread()); }
        }
    }

    private static void closeSocket(Entry entry) {
        try { entry.socket.close(); } catch (IOException ignored) { }
    }
    private void closeEntry(Entry entry, Reason reason) {
        entry.requestClose(reason);
        closeSocket(entry);
        if (!entry.started()) registry.finish(entry, reason);
    }
    private void expire() {
        long now = System.nanoTime();
        for (Entry entry : registry.entries()) {
            Reason reason = entry.expire(now, TimeUnit.MILLISECONDS.toNanos(config.idleTimeoutMillis),
                    TimeUnit.MILLISECONDS.toNanos(config.queueTimeoutMillis));
            if (reason != null) {
                if (pool != null && !entry.started())
                    pool.getQueue().removeIf(task -> ((ConnectionTask) task).entry == entry);
                closeEntry(entry, reason);
            }
        }
    }

    public MetricsCounters counters() { return counters; }
    public ConnectionRegistry registry() { return registry; }
    public int port() { return listener.getLocalPort(); }
    public long bytes() { return counters.get(BYTES); }
    public long requests() { return counters.get(REQUESTS); }
    public long rejected() { return counters.get(REJECTED); }
    public int processing() { return processing.get(); }
    public int peakProcessing() { return peakProcessing.get(); }
    public int queued() { return pool == null ? 0 : pool.getQueue().size(); }
    public int peakQueue() { return peakQueue.get(); }
    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>(counters.snapshot());
        result.put("processing", processing()); result.put("peak_processing", peakProcessing());
        result.put("queued", queued()); result.put("peak_queue", peakQueue());
        result.put("send_buffer_bytes", effectiveSendBuffer); result.put("receive_buffer_bytes", effectiveReceiveBuffer);
        return result;
    }

    /** Return false when a deadline forced socket closure rather than a clean client drain. */
    public boolean shutdown(Duration drain) throws IOException {
        if (!stopped.compareAndSet(false, true)) return registry.size() == 0;
        running = false;
        listener.close();
        if (pool != null) pool.shutdown();
        for (Entry entry : registry.entries()) if (!entry.started()) closeEntry(entry, Reason.SERVER_SHUTDOWN);
        long deadline = System.nanoTime() + drain.toNanos();
        boolean complete;
        try {
            while (registry.size() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
            complete = registry.size() == 0;
            for (Entry entry : registry.entries()) closeEntry(entry, Reason.SERVER_SHUTDOWN);
            acceptor.join(2_000);
            // Admission may have been between accept() and registry insertion when shutdown began.
            for (Entry entry : registry.entries()) closeEntry(entry, Reason.SERVER_SHUTDOWN);
            if (pool != null) pool.shutdownNow();
            for (Thread thread : handlers) thread.join(2_000);
            if (pool != null && !pool.awaitTermination(2, TimeUnit.SECONDS)) throw new IOException("Workers did not stop");
            maintenance.shutdownNow();
            if (!maintenance.awaitTermination(2, TimeUnit.SECONDS) || acceptor.isAlive() || !handlers.isEmpty())
                throw new IOException("Server threads did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during shutdown", e);
        } finally { maintenance.shutdownNow(); }
        return complete;
    }
    @Override public void close() throws IOException { shutdown(Duration.ZERO); }
}
