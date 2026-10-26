package dev.astra.metrics;

import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

/** CPU is cumulative nanoseconds, enabling an explicit one-core=100% calculation over any interval. */
public final class ProcessMetrics {
    private ProcessMetrics() {}
    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nano_time", System.nanoTime());
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("cpu_nanos", os.getProcessCpuTime());
        result.put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        long gcTime = 0, gcCount = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcTime += Math.max(0, gc.getCollectionTime()); gcCount += Math.max(0, gc.getCollectionCount());
        }
        result.put("gc_millis", gcTime); result.put("gc_count", gcCount);
        result.put("rss_bytes", rss());
        return result;
    }
    private static long rss() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
            }
        } catch (Exception ignored) { }
        return -1;
    }
    public static Map<String, Object> environment() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", java.time.Instant.now().toString());
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("vm", System.getProperty("java.vm.name"));
        result.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("physical_memory_bytes", ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize());
        result.put("topology", "server and client are separate JVMs on the same host; TCP loopback");
        result.put("network_utilization", "not sampled; loopback has no physical NIC link rate");
        result.put("cpu_normalization", "100% equals one fully utilized logical processor");
        for (String name : List.of("/proc/cpuinfo", "/proc/self/limits")) {
            try { result.put(name.endsWith("limits") ? "resource_limits" : "cpu_info", Files.readString(Path.of(name))); }
            catch (Exception e) { result.put(name, "unavailable"); }
        }
        try {
            Process git = new ProcessBuilder("git", "rev-parse", "HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            result.put("revision", new String(git.getInputStream().readAllBytes()).trim()); git.waitFor();
        } catch (Exception e) { result.put("revision", "unavailable"); }
        return result;
    }
}
