package dev.astra.server;

import dev.astra.protocol.Protocol;
import java.io.*;
import java.net.*;

/** Lesson 03: one accept loop also handles the entire accepted connection. */
public final class TcpServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Thread acceptor;
    private volatile boolean running = true;
    private volatile Socket active;
    private volatile long bytes, requests;

    public TcpServer(String host, int port) throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(host, port), 128);
        acceptor = Thread.ofPlatform().name("astra-baseline").start(this::accept);
    }

    private void accept() {
        while (running) {
            try (Socket socket = listener.accept()) {
                active = socket;
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                RequestHandler.serve(new BufferedInputStream(socket.getInputStream(), Protocol.CHUNK),
                        socket.getOutputStream(), 1L << 30, false, new RequestHandler.Events() {
                            public void bytes(int count) { bytes += count; }
                            public void completed() { requests++; }
                        });
            } catch (IOException e) {
                if (running) System.err.println("Connection ended: " + e.getMessage());
            } finally { active = null; }
        }
    }

    public int port() { return listener.getLocalPort(); }
    public long bytes() { return bytes; }
    public long requests() { return requests; }

    @Override public void close() throws IOException {
        running = false;
        listener.close();
        Socket socket = active;
        if (socket != null) socket.close();
        try { acceptor.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
