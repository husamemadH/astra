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
}
