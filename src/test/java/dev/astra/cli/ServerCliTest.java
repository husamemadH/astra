package dev.astra.cli;

import com.google.gson.JsonParser;
import dev.astra.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ServerCliTest {
    @TempDir Path directory;

    @Test void terminationSignalWritesTheFinalServerState() throws Exception {
        assumeTrue(ProcessHandle.current().supportsNormalTermination());
        Path stdout = directory.resolve("stdout.jsonl");
        Path stderr = directory.resolve("stderr.log");
        Path output = directory.resolve("results");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process server = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "server", "--port", "0", "--output", output.toString())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (server.isAlive() && !Files.readString(stdout).contains("\"event\":\"READY\"")
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.readString(stdout).contains("\"event\":\"READY\""), Files.readString(stderr));
            assertTrue(server.toHandle().destroy());
            assertTrue(server.waitFor(5, TimeUnit.SECONDS), "Server did not exit after the signal");
            assertTrue(Files.readString(stdout).contains("\"event\":\"STOPPED\""), Files.readString(stderr));
            var result = JsonParser.parseString(Files.readString(output.resolve("server-final.json"))).getAsJsonObject();
            assertTrue(result.get("complete").getAsBoolean());
            assertEquals(0, result.getAsJsonObject("metrics").get("active").getAsLong());
        } finally {
            if (server.isAlive()) server.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void startupKeepsDetailedMetadataOnDiskAndConsoleAcceptsPaddedCommands() throws Exception {
        Path stdout = directory.resolve("stdout.jsonl");
        Path stderr = directory.resolve("stderr.log");
        Path output = directory.resolve("results");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process server = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "server", "--port", "0", "--output", output.toString())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            var commands = server.outputWriter();
            commands.write(" sample \n stop \n");
            commands.flush();
            assertTrue(server.waitFor(5, TimeUnit.SECONDS), "Server did not exit after the padded stop command");
            assertEquals(0, server.exitValue(), Files.readString(stderr));
            var events = Files.readAllLines(stdout);
            assertEquals(3, events.size());
            var ready = JsonParser.parseString(events.getFirst()).getAsJsonObject();
            assertEquals("READY", ready.get("event").getAsString());
            var data = ready.getAsJsonObject("data");
            assertTrue(data.get("port").getAsInt() > 0);
            assertFalse(data.getAsJsonObject("environment").has("cpu_info"));
            assertFalse(data.getAsJsonObject("environment").has("resource_limits"));
            var saved = JsonParser.parseString(Files.readString(output.resolve("server-config.json"))).getAsJsonObject();
            assertEquals(data.get("port"), saved.get("port"));
            assertTrue(saved.getAsJsonObject("environment").has("cpu_info")
                    || saved.getAsJsonObject("environment").has("/proc/cpuinfo"));
            assertTrue(events.get(1).contains("\"event\":\"SAMPLE\""));
            assertTrue(events.getLast().contains("\"event\":\"STOPPED\""));
        } finally {
            if (server.isAlive()) server.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
