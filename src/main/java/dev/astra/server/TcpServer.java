package dev.astra.server;

import dev.astra.protocol.Protocol;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** All designs share the handler; only the connection scheduling branch differs. */
public final class TcpServer implements AutoCloseable {
    private final ServerConfig config;
    private final ServerSocket listener;
    private final Thread acceptor;
    private final ThreadPoolExecutor pool;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Thread> handlers = ConcurrentHashMap.newKeySet();
    private final LongAdder bytes = new LongAdder(), requests = new LongAdder(), rejected = new LongAdder();
    private final AtomicInteger processing = new AtomicInteger(), peakProcessing = new AtomicInteger();
    private volatile boolean running = true;

    public TcpServer(String host, int port) throws IOException {
        this(baseline(host, port));
    }

    private static ServerConfig baseline(String host, int port) {
        var config = new ServerConfig();
        config.host = host; config.port = port; config.design = ServerConfig.Design.BASELINE;
        return config;
    }

    public TcpServer(ServerConfig config) throws IOException {
        config.validate();
        this.config = config;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(config.host, config.port), config.backlog);
        pool = config.design == ServerConfig.Design.POOL
                ? new ThreadPoolExecutor(config.workers, config.workers, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(config.queueCapacity), Thread.ofPlatform().name("astra-worker-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy()) : null;
        acceptor = Thread.ofPlatform().name("astra-acceptor").start(this::accept);
    }

    private void accept() {
        while (running) {
            try {
                Socket socket = listener.accept();
                sockets.add(socket);
                if (!running) { discard(socket); break; }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, config.idleTimeoutMillis));
                Runnable task = () -> serve(socket);
                switch (config.design) {
                    case BASELINE -> task.run();
                    case THREAD_PER_CONNECTION -> {
                        Thread thread = Thread.ofPlatform().name("astra-connection").unstarted(task);
                        handlers.add(thread);
                        thread.start();
                    }
                    case POOL -> {
                        try { pool.execute(task); }
                        catch (RejectedExecutionException e) { rejected.increment(); discard(socket); }
                    }
                }
            } catch (IOException e) {
                if (running) System.err.println("Accept failed: " + e.getMessage());
            }
        }
    }

    private void serve(Socket socket) {
        int active = processing.incrementAndGet();
        peakProcessing.accumulateAndGet(active, Math::max);
        try {
            RequestHandler.serve(new BufferedInputStream(socket.getInputStream(), Protocol.CHUNK),
                    socket.getOutputStream(), config.maxPayload, config.checksum, new RequestHandler.Events() {
                        public void bytes(int count) { bytes.add(count); }
                        public void completed() { requests.increment(); }
                    });
        } catch (IOException ignored) { /* A failed peer must not stop the acceptor. */ }
        finally {
            processing.decrementAndGet();
            discard(socket);
            handlers.remove(Thread.currentThread());
        }
    }

    private void discard(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    public int port() { return listener.getLocalPort(); }
    public long bytes() { return bytes.sum(); }
    public long requests() { return requests.sum(); }
    public long rejected() { return rejected.sum(); }
    public int processing() { return processing.get(); }
    public int peakProcessing() { return peakProcessing.get(); }
    public int queued() { return pool == null ? 0 : pool.getQueue().size(); }

    @Override public void close() throws IOException {
        running = false;
        listener.close();
        if (pool != null) pool.shutdownNow();
        sockets.forEach(this::discard);
        try {
            acceptor.join(2_000);
            for (Thread thread : handlers) thread.join(2_000);
            if (pool != null && !pool.awaitTermination(2, TimeUnit.SECONDS))
                throw new IOException("Workers did not stop");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
