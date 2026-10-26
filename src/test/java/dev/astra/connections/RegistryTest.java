package dev.astra.connections;

import dev.astra.server.*;
import dev.astra.metrics.*;
import dev.astra.client.SimpleClient;
import dev.astra.protocol.Protocol;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
    @Test void lifecycleInspectionContainsActiveAndClosedClients() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 2;
        try (var server = new TcpServer(c); var active = new Socket("127.0.0.1", server.port())) {
            SimpleClient.upload("127.0.0.1", server.port(), 1234, 1);
            await(() -> server.registry().snapshot().history().size() == 1);
            var snapshot = server.registry().snapshot();
            assertEquals(1, snapshot.active().size());
            assertEquals(1234, snapshot.history().getFirst().bytes());
            assertEquals("NORMAL", snapshot.history().getFirst().reason());
            assertNotEquals(snapshot.active().getFirst().id(), snapshot.history().getFirst().id());
        }
    }
    @Test void idleCleanupUsesRegistryAndAuditsHistoryWhileOtherClientWorks() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 2; c.idleTimeoutMillis = 200;
        try (var server = new TcpServer(c); var idle = new Socket("127.0.0.1", server.port());
             var active = new Socket("127.0.0.1", server.port())) {
            idle.setSoTimeout(3000); active.setSoTimeout(3000);
            var out = new DataOutputStream(active.getOutputStream());
            var replies = new Protocol.Reader(active.getInputStream());
            long id = 1;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.registry().snapshot().history().isEmpty() && System.nanoTime() < deadline) {
                Protocol.request(out, id, 100, Protocol.payloadBuffer(100), false);
                assertEquals(id++, replies.read().id());
                Thread.sleep(10);
            }
            assertEquals(-1, idle.getInputStream().read());
            await(() -> !server.registry().snapshot().history().isEmpty());
            assertEquals("IDLE_TIMEOUT", server.registry().snapshot().history().getFirst().reason());
            assertEquals(1, server.registry().snapshot().active().size());
            assertEquals(1L, server.registry().timeoutReport(200).get("idleTimeouts"));
        }
    }
    @Test void queuedSocketExpiresWithoutEverStarting() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 1; c.queueCapacity = 1; c.queueTimeoutMillis = 100;
        try (var server = new TcpServer(c); var working = new Socket("127.0.0.1", server.port())) {
            await(() -> server.processing() == 1);
            try (var queued = new Socket("127.0.0.1", server.port())) {
                queued.setSoTimeout(3000);
                assertEquals(-1, queued.getInputStream().read());
                await(() -> server.registry().snapshot().history().size() == 1);
                assertEquals("QUEUE_TIMEOUT", server.registry().snapshot().history().getFirst().reason());
                assertEquals(1, server.peakProcessing());
                assertEquals(0, server.queued());
            }
        }
    }
    @Test void finalizationIsExactlyOnceAndHistoryIsBounded() throws Exception {
        var counters = new LongAdderCounters();
        var registry = new ConnectionRegistry(counters, 2);
        for (int i = 0; i < 3; i++) {
            try (var socket = new Socket(); var pool = Executors.newFixedThreadPool(4)) {
                var entry = registry.accept(socket);
                var gate = new CountDownLatch(1);
                var tasks = new java.util.ArrayList<Future<?>>();
                for (int j = 0; j < 4; j++) tasks.add(pool.submit(() -> {
                    try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    registry.finish(entry, ConnectionRegistry.Reason.SERVER_SHUTDOWN);
                }));
                gate.countDown(); for (var task : tasks) task.get();
            }
        }
        assertEquals(0, counters.get(MetricsCounters.Counter.ACTIVE));
        assertEquals(3, counters.get(MetricsCounters.Counter.CLOSED));
        assertEquals(2, registry.snapshot().history().size());
        assertEquals(1, registry.snapshot().evicted());
    }
    @Test void startAndQueueExpiryCannotBothWin() throws Exception {
        for (int i = 0; i < 100; i++) {
            var registry = new ConnectionRegistry(new LongAdderCounters(), 2);
            try (var socket = new Socket(); var pool = Executors.newFixedThreadPool(2)) {
                var entry = registry.accept(socket);
                var gate = new CountDownLatch(1);
                var start = pool.submit(() -> { gate.await(); return entry.start(); });
                var expire = pool.submit(() -> { gate.await(); return entry.expire(System.nanoTime(), Long.MAX_VALUE, 0); });
                gate.countDown();
                boolean started = start.get(); var reason = expire.get();
                assertFalse(started && reason == ConnectionRegistry.Reason.QUEUE_TIMEOUT);
                registry.finish(entry, ConnectionRegistry.Reason.NORMAL);
            }
        }
    }
}
