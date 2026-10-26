package dev.astra.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTest {
    @TempDir Path directory;
    @Test void matrixIsReproducibleAndUsesMatchedConcurrency() {
        var c = new BenchmarkConfig(); c.workers = List.of(1, 2); c.repetitions = 2;
        var trials = BenchmarkRunner.matrix(c);
        assertEquals(20, trials.size()); assertEquals(trials, BenchmarkRunner.matrix(c));
        for (var trial : trials) assertEquals(trial.workers(), trial.connections());
        assertEquals(4, trials.stream().filter(t -> t.design().equals("baseline")).count());
    }
    @Test void reportUsesActualTrialDataAndMedianForEvenCounts() throws Exception {
        assertEquals(2.5, Report.median(new double[]{1,2,3,4}));
        var trial = new BenchmarkRunner.Trial("pool", "long-adder", 2, 2, 16, 1);
        var summary = new BenchmarkRunner.Summary("run-001", trial, true, 42, 40, 10, 1, 2, 3, 90, 80, 1, 0, 0, 0, 44040192, 2, "");
        Report.write(directory, List.of(summary));
        assertTrue(Files.readString(directory.resolve("summary.csv")).contains("42.000000"));
        assertTrue(Files.readString(directory.resolve("report.html")).contains("<svg"));
    }
    @Test void resourceBudgetRejectsAnUnsafeMatrix() {
        var c = new BenchmarkConfig(); c.workers = List.of(100); c.maxWorkers = 8;
        assertThrows(IllegalArgumentException.class, c::validate);
    }
}
