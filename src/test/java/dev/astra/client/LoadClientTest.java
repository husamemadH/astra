package dev.astra.client;

import dev.astra.server.*;
import dev.astra.protocol.Protocol;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

class LoadClientTest {
    @Test void writesSeveralCompleteRequestsBeforeReadingReplies() throws Exception {
        var c = new ServerConfig(); c.port = 0;
        try (var server = new TcpServer(c); var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = new DataOutputStream(socket.getOutputStream());
            for (int i = 1; i <= 16; i++) Protocol.request(out, i, 1024, Protocol.payloadBuffer(128), false);
            var reader = new Protocol.Reader(socket.getInputStream());
            for (int i = 1; i <= 16; i++) {
                var ack = reader.read(); assertEquals(i, ack.id()); assertEquals(1024, ack.value());
            }
        }
    }
    @Test void concurrentReadersDrainAllPipelinedRepliesAndReconcile() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.workers = 2; c.checksum = true;
        try (var server = new TcpServer(c)) {
            var client = new ClientConfig(); client.port = server.port(); client.connections = 2;
            client.durationSeconds = .25; client.payloadBytes = 100_000; client.pipelineDepth = 8; client.checksum = true;
            var result = new LoadClient(client).run(phase -> {});
            assertTrue(result.complete(), result.errors().toString());
            assertTrue(result.acknowledged() > 2);
            assertEquals(result.started(), result.acknowledged());
            assertEquals(result.acknowledged() * client.payloadBytes, server.bytes());
            assertEquals(result.measuredAcknowledged(), result.latencySamples());
            assertTrue(result.peakOutstandingPerConnection() <= client.pipelineDepth);
            assertTrue(result.p99Millis() >= result.p50Millis());
        }
    }
    @Test void disconnectFailsOutstandingWorkAndJoinsClientThreads() throws Exception {
        var c = new ServerConfig(); c.port = 0; c.maxPayload = 1;
        try (var server = new TcpServer(c)) {
            var client = new ClientConfig(); client.port = server.port(); client.durationSeconds = .1;
            var result = new LoadClient(client).run(phase -> {});
            assertFalse(result.complete()); assertTrue(result.failedRequests() > 0);
            assertEquals(1, result.connectionFailures());
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("astra-client-")));
        }
    }
    @Test void histogramUsesBoundedBucketsAndPreservesCountWhenMerged() {
        var a = new LatencyHistogram(); var b = new LatencyHistogram();
        for (int i = 1; i <= 100; i++) (i <= 50 ? a : b).record(i * 1_000_000L);
        a.merge(b); assertEquals(100, a.count());
        assertTrue(a.percentileMillis(50) >= 50 && a.percentileMillis(50) < 52);
        assertTrue(a.percentileMillis(99) >= 99 && a.percentileMillis(99) < 103);
    }
}
