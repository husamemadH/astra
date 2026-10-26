package dev.astra.benchmark;

import dev.astra.cli.Json;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Standalone HTML/SVG charts; no CDN, browser framework, or online service is needed. */
public final class Report {
    private Report() {}
    public static void write(Path directory, List<BenchmarkRunner.Summary> summaries) throws IOException {
        Json.write(directory.resolve("summaries.json"), summaries);
        StringBuilder csv = new StringBuilder("run,design,counters,workers,connections,pipeline,repetition,complete,server_mib_s,server_gbit_s,client_mib_s,requests_s,p50_ms,p95_ms,p99_ms,server_cpu_percent,client_cpu_percent,server_seconds,failed_requests,rejected,server_gc_ms\n");
        for (var s : summaries) {
            var t = s.trial();
            csv.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d%n",
                    s.run(), t.design(), t.counters(), t.workers(), t.connections(), t.pipeline(), t.repetition(), s.complete(),
                    s.serverMiBs(), s.serverMiBs() * 1048576 * 8 / 1e9, s.clientMiBs(), s.requestsPerSecond(),
                    s.p50Ms(), s.p95Ms(), s.p99Ms(), s.serverCpuPercent(), s.clientCpuPercent(), s.serverSeconds(),
                    s.failedRequests(), s.rejected(), s.serverGcMillis()));
        }
        Files.writeString(directory.resolve("summary.csv"), csv);
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>Astra scaling experiment</title><style>body{font:16px system-ui;max-width:1080px;margin:40px auto;padding:0 20px;color:#172337;background:#f7fafc}svg{width:100%;background:white;border:1px solid #cbd5e1}text{font:13px system-ui;fill:#172337}table{border-collapse:collapse;width:100%;background:white}th,td{padding:8px;border:1px solid #cbd5e1;text-align:right}h1,h2{color:#16324f}.note{max-width:85ch;line-height:1.6}</style>
                <h1>Astra TCP scaling experiment</h1><p class="note">Points show medians; vertical whiskers show the minimum and maximum across repetitions. Dashed lines show the single-threaded baseline. One MiB is 1,048,576 bytes. CPU percentages count one logical processor as 100%. Raw trials, interval boundaries, failure counts, and environment metadata are stored beside this report.</p>
                <p class="note">Server and client run in separate JVMs on the same host. Server window markers arrive over local process pipes after client phase events; use the recorded server elapsed time. Short windows amplify this boundary skew. Curves identify observed behavior, not by themselves the cause of a ceiling. Incomplete trials are excluded.</p>
                """);
        Set<Integer> pipelines = summaries.stream().map(s -> s.trial().pipeline()).collect(Collectors.toCollection(TreeSet::new));
        for (int pipeline : pipelines) for (String design : List.of("pool", "thread-per-connection")) {
            html.append("<h2>").append(design).append(" · pipeline ").append(pipeline).append("</h2>");
            html.append(chart(summaries, design, pipeline, false));
            html.append("<p>Blue: synchronized. Green: LongAdder. ").append(design.equals("pool") ? "X: fixed workers W." : "X: connections / handler threads.").append(" Y: received payload MiB/s.</p>");
            html.append(chart(summaries, design, pipeline, true));
            html.append("<p>CPU: solid = server; dashed = client. Colors identify the counter mode.</p>");
        }
        html.append("<h2>Repeated-trial summaries</h2><table><tr><th>Design</th><th>Counters</th><th>W/C</th><th>Pipeline</th><th>N</th><th>Median MiB/s</th><th>Min</th><th>Max</th></tr>");
        var groups = summaries.stream().filter(BenchmarkRunner.Summary::complete).collect(Collectors.groupingBy(
                s -> s.trial().design() + "/" + s.trial().counters() + "/" + s.trial().workers() + "/" + s.trial().pipeline(), TreeMap::new, Collectors.toList()));
        for (var group : groups.values()) {
            var t = group.getFirst().trial(); var values = group.stream().mapToDouble(BenchmarkRunner.Summary::serverMiBs).sorted().toArray();
            html.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%.1f</td><td>%.1f</td><td>%.1f</td></tr>",
                    t.design(), t.counters(), t.workers(), t.pipeline(), values.length, median(values), values[0], values[values.length - 1]));
        }
        html.append("</table><p>See <a href='summary.csv'>summary.csv</a>, <a href='config.json'>config.json</a>, and <a href='environment.json'>environment.json</a>.</p></html>");
        Files.writeString(directory.resolve("report.html"), html);
    }
    private static String chart(List<BenchmarkRunner.Summary> all, String design, int pipeline, boolean cpu) {
        var samples = all.stream().filter(s -> s.complete() && s.trial().pipeline() == pipeline).toList();
        int maxW = samples.stream().mapToInt(s -> s.trial().workers()).max().orElse(1);
        double maxY = samples.stream().mapToDouble(s -> cpu ? Math.max(s.serverCpuPercent(), s.clientCpuPercent()) : s.serverMiBs()).max().orElse(1) * 1.15;
        if (maxY <= 0) maxY = 1;
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 1000 340' role='img' aria-label='Scaling chart'><path d='M70 20V290H960' fill='none' stroke='#64748b'/>");
        for (int i = 0; i <= 4; i++) {
            double y = 290 - i * 65;
            svg.append(String.format(Locale.ROOT, "<path d='M70 %.1fH960' stroke='#e2e8f0'/><text x='5' y='%.1f'>%.0f</text>", y, y + 4, maxY * i / 4));
        }
        for (String mode : List.of("synchronized", "long-adder")) {
            String color = mode.equals("synchronized") ? "#2563eb" : "#059669";
            var points = samples.stream().filter(s -> s.trial().design().equals(design) && s.trial().counters().equals(mode))
                    .collect(Collectors.groupingBy(s -> s.trial().workers(), TreeMap::new, Collectors.toList()));
            for (int side = 0; side < (cpu ? 2 : 1); side++) {
                StringBuilder line = new StringBuilder();
                for (var point : points.entrySet()) {
                    final boolean client = side == 1;
                    double[] values = point.getValue().stream().mapToDouble(s -> cpu ? (client ? s.clientCpuPercent() : s.serverCpuPercent()) : s.serverMiBs()).sorted().toArray();
                    double x = 70 + (point.getKey() - 1) * 890.0 / Math.max(1, maxW - 1);
                    double y = 290 - median(values) / maxY * 260;
                    line.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
                    svg.append(String.format(Locale.ROOT, "<path d='M%.1f %.1fV%.1f' stroke='%s'/><circle cx='%.1f' cy='%.1f' r='4' fill='%s'/><text x='%.1f' y='315'>%d</text>",
                            x, 290 - values[0] / maxY * 260, 290 - values[values.length - 1] / maxY * 260, color, x, y, color, x - 4, point.getKey()));
                }
                svg.append("<polyline points='").append(line).append("' fill='none' stroke='").append(color).append("' stroke-width='2'")
                        .append(side == 1 ? " stroke-dasharray='5 4'" : "").append("/>");
            }
            if (!cpu) {
                double[] baseline = samples.stream().filter(s -> s.trial().design().equals("baseline") && s.trial().counters().equals(mode)).mapToDouble(BenchmarkRunner.Summary::serverMiBs).sorted().toArray();
                if (baseline.length > 0) svg.append(String.format(Locale.ROOT, "<path d='M70 %.1fH960' stroke='%s' stroke-dasharray='6 4'/>", 290 - median(baseline) / maxY * 260, color));
            }
        }
        return svg.append("</svg>").toString();
    }
    public static double median(double[] sorted) {
        if (sorted.length == 0) return 0;
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }
}
