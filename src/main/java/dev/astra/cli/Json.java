package dev.astra.cli;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

public final class Json {
    public static final Gson COMPACT = new GsonBuilder().serializeNulls().create();
    public static final Gson PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private Json() {}
    public static synchronized void event(String event, Object data) {
        System.out.println(COMPACT.toJson(Map.of("event", event, "data", data)));
        System.out.flush();
    }
    public static void write(Path path, Object value) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, PRETTY.toJson(value) + "\n");
    }
}
