package dev.astra.server;

import dev.astra.client.SimpleClient;
import dev.astra.protocol.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

class BaselineTest {
    @Test void uploadsMultipleRequestsAndEmptyPayloads() throws Exception {
        try (var server = new TcpServer("127.0.0.1", 0)) {
            assertEquals(3L << 20, SimpleClient.upload("127.0.0.1", server.port(), 1L << 20, 3));
            assertEquals(0, SimpleClient.upload("127.0.0.1", server.port(), 0, 2));
            assertEquals(3L << 20, server.bytes());
            assertEquals(5, server.requests());
        }
    }

    @Test void streams500MiBWithOnly128MiBHeap() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 500L * 1024 * 1024);
        try (var server = new TcpServer("127.0.0.1", 0)) {
            assertEquals(500L << 20, SimpleClient.upload("127.0.0.1", server.port(), 500L << 20, 1));
        }
    }

    @Test void rejectsWrongPayloadLength() throws Exception {
        try (var server = new TcpServer("127.0.0.1", 0); var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(2_000);
            var out = new DataOutputStream(socket.getOutputStream());
            Protocol.begin(out, 1, 100, false);
            Protocol.end(out, 1, 0);
            out.flush();
            var error = new Protocol.Reader(socket.getInputStream()).read();
            assertEquals(Protocol.ERROR, error.type());
            assertEquals(ProtocolException.PAYLOAD, error.value());
        }
    }
}
