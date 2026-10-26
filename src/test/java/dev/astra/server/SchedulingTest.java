package dev.astra.server;

import dev.astra.client.SimpleClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingTest {
    static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(5);
        });
    }

    @ParameterizedTest @EnumSource(ServerConfig.Design.class)
    void identicalClientWorksForEveryDesign(ServerConfig.Design design) throws Exception {
        var config = new ServerConfig(); config.port = 0; config.design = design;
        try (var server = new TcpServer(config)) {
            assertEquals(131_072, SimpleClient.upload("127.0.0.1", server.port(), 65_536, 2));
            await(() -> server.processing() == 0);
        }
    }

    @Test void boundedPoolRejectsExcessAndRecovers() throws Exception {
        var config = new ServerConfig(); config.port = 0; config.workers = 1; config.queueCapacity = 1;
        try (var server = new TcpServer(config); var first = new Socket("127.0.0.1", server.port())) {
            await(() -> server.processing() == 1);
            try (var second = new Socket("127.0.0.1", server.port())) {
                await(() -> server.queued() == 1);
                try (var excess = new Socket("127.0.0.1", server.port())) {
                    excess.setSoTimeout(2_000);
                    assertEquals(-1, excess.getInputStream().read());
                }
                await(() -> server.rejected() == 1);
                assertEquals(1, server.peakProcessing());
                first.close();
                await(() -> server.queued() == 0);
            }
            await(() -> server.processing() == 0);
            assertEquals(1024, SimpleClient.upload("127.0.0.1", server.port(), 1024, 1));
        }
    }

    @Test void baselineProcessesOnlyOnePersistentConnection() throws Exception {
        var config = new ServerConfig(); config.port = 0; config.design = ServerConfig.Design.BASELINE;
        try (var server = new TcpServer(config); var a = new Socket("127.0.0.1", server.port());
             var b = new Socket("127.0.0.1", server.port())) {
            await(() -> server.processing() == 1);
            assertEquals(1, server.peakProcessing());
            assertEquals(0, server.queued());
        }
    }

    @Test void threadPerConnectionStartsIndependentHandlers() throws Exception {
        var config = new ServerConfig(); config.port = 0; config.design = ServerConfig.Design.THREAD_PER_CONNECTION;
        try (var server = new TcpServer(config); var a = new Socket("127.0.0.1", server.port());
             var b = new Socket("127.0.0.1", server.port())) {
            await(() -> server.processing() == 2);
            assertEquals(2, server.peakProcessing());
        }
    }
}
