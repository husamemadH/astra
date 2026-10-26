package dev.astra.metrics;

import dev.astra.server.*;
import dev.astra.client.SimpleClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.astra.metrics.MetricsCounters.Counter.*;

class CountersTest {
    @ParameterizedTest @ValueSource(strings={"synchronized", "long-adder"})
    void concurrentUpdatesReconcileAfterWorkersJoin(String mode) throws Exception {
        var counters = MetricsCounters.create(mode);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                counters.increment(ACTIVE);
                for (int j = 0; j < 10_000; j++) { counters.add(BYTES, 64); counters.increment(REQUESTS); }
                counters.add(ACTIVE, -1);
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(80_000 * 64, counters.get(BYTES));
        assertEquals(80_000, counters.get(REQUESTS));
        assertEquals(0, counters.get(ACTIVE));
    }

    @ParameterizedTest @ValueSource(strings={"synchronized", "long-adder"})
    void bothModesCountIdenticalRealTraffic(String mode) throws Exception {
        var config = new ServerConfig(); config.port = 0; config.counters = mode;
        var server = new TcpServer(config);
        try (server; var clients = Executors.newFixedThreadPool(4)) {
            List<Future<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) tasks.add(clients.submit(() -> SimpleClient.upload("127.0.0.1", server.port(), 100_000, 3)));
            for (var task : tasks) assertEquals(300_000L, task.get());
        }
        assertEquals(1_200_000, server.bytes());
        assertEquals(12, server.requests());
        assertEquals(0, server.counters().get(ACTIVE));
        assertEquals(4, server.counters().get(CLOSED));
    }
}
