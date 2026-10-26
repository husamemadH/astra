package dev.astra.cli;

import java.util.*;

/** Small strict parser: every option takes a value, including boolean options. */
public final class Options {
    private final Map<String, String> values = new LinkedHashMap<>();
    public Options(String[] args, int start, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (int i = start; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !names.contains(args[i].substring(2)))
                throw new IllegalArgumentException("Unknown option: " + args[i]);
            if (i + 1 == args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Missing value for " + args[i]);
            if (values.putIfAbsent(args[i].substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException("Repeated option: " + args[i]);
        }
    }
    public boolean has(String key) { return values.containsKey(key); }
    public String text(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public int integer(String key, int fallback) { return Integer.parseInt(text(key, Integer.toString(fallback))); }
    public long longValue(String key, long fallback) { return Long.parseLong(text(key, Long.toString(fallback))); }
    public double decimal(String key, double fallback) { return Double.parseDouble(text(key, Double.toString(fallback))); }
    public boolean bool(String key, boolean fallback) {
        String value = text(key, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
}
