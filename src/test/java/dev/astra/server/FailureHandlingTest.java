package dev.astra.server;

import dev.astra.client.SimpleClient;
import dev.astra.protocol.Protocol;
import dev.astra.metrics.MetricsCounters.Counter;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FailureHandlingTest {
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
    @Test void truncatedRequestFreesWorkerAndCountsFailure() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 1;
        try (var server = new TcpServer(c)) {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                var out = new DataOutputStream(socket.getOutputStream());
                Protocol.begin(out, 1, 100, false); out.writeInt(110); out.writeByte(1); out.flush();
                socket.shutdownOutput();
            }
            await(() -> server.counters().get(Counter.CLOSED) == 1);
            assertEquals(1, server.counters().get(Counter.FAILED_REQUESTS));
            assertEquals(0, server.counters().get(Counter.ACTIVE));
            assertEquals(100, SimpleClient.upload("127.0.0.1", server.port(), 100, 1));
        }
    }
    @Test void checksumMismatchIsAProtocolFailureWithNoSuccessAck() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.checksum = true;
        try (var server = new TcpServer(c); var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = new DataOutputStream(socket.getOutputStream());
            Protocol.begin(out, 1, 0, true); Protocol.end(out, 1, 123); out.flush();
            var reply = new Protocol.Reader(socket.getInputStream()).read();
            assertEquals(Protocol.ERROR, reply.type()); assertEquals(5, reply.value());
            await(() -> server.counters().get(Counter.CLOSED) == 1);
            assertEquals(1, server.counters().get(Counter.PROTOCOL_ERRORS));
            assertEquals(0, server.requests());
            assertEquals("PROTOCOL_ERROR", server.registry().snapshot().history().getFirst().reason());
        }
    }
    @Test void interleavedIdsAndRepeatedIdsAreRejected() throws Exception {
        var c = new ServerConfig(); c.port = 0;
        try (var server = new TcpServer(c)) {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000); var out = new DataOutputStream(socket.getOutputStream());
                Protocol.begin(out, 1, 0, false); Protocol.end(out, 2, 0); out.flush();
                assertEquals(Protocol.ERROR, new Protocol.Reader(socket.getInputStream()).read().type());
            }
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000); var out = new DataOutputStream(socket.getOutputStream());
                Protocol.request(out, 1, 0, Protocol.payloadBuffer(1), false);
                var reader = new Protocol.Reader(socket.getInputStream()); assertEquals(Protocol.ACK, reader.read().type());
                Protocol.request(out, 1, 0, Protocol.payloadBuffer(1), false);
                assertEquals(Protocol.ERROR, reader.read().type());
            }
        }
    }
    @Test void forcedShutdownFinalizesActiveAndQueuedConnectionsAndJoinsThreads() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 1; c.queueCapacity = 1;
        try (var server = new TcpServer(c); var a = new Socket("127.0.0.1", server.port());
             var b = new Socket("127.0.0.1", server.port())) {
            await(() -> server.processing() == 1 && server.queued() == 1);
            assertFalse(server.shutdown(Duration.ZERO));
            assertEquals(0, server.registry().size()); assertEquals(0, server.processing());
            assertEquals(2, server.counters().get(Counter.CLOSED));
            assertTrue(server.registry().snapshot().history().stream().allMatch(h -> h.reason().equals("SERVER_SHUTDOWN")));
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() &&
                    (t.getName().startsWith("astra-worker-") || t.getName().equals("astra-acceptor") || t.getName().equals("astra-maintenance"))));
        }
    }
    @Test void peerResetDoesNotStopTheServer() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 1;
        try (var server = new TcpServer(c)) {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoLinger(true, 0);
                Protocol.begin(new DataOutputStream(socket.getOutputStream()), 1, 100, false);
            }
            await(() -> server.counters().get(Counter.CLOSED) == 1);
            assertEquals(100, SimpleClient.upload("127.0.0.1", server.port(), 100, 1));
        }
    }
}
