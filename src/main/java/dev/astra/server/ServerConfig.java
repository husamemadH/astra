package dev.astra.server;

/** Configure before constructing TcpServer; do not mutate while it runs. */
public final class ServerConfig {
    public enum Design { BASELINE, THREAD_PER_CONNECTION, POOL }
    public String host = "127.0.0.1";
    public int port = 9000;
    public Design design = Design.POOL;
    public int workers = Runtime.getRuntime().availableProcessors();
    public int queueCapacity = 64;
    public int backlog = 128;
    public long maxPayload = 1L << 30;
    public boolean checksum;
    public String counters = "long-adder";
    public long idleTimeoutMillis = 30_000;

    public void validate() {
        dev.astra.metrics.MetricsCounters.create(counters);
        if (host == null || host.isBlank() || port < 0 || port > 65535 || design == null
                || workers < 1 || queueCapacity < 1 || backlog < 1 || maxPayload < 0 || idleTimeoutMillis < 1)
            throw new IllegalArgumentException("Invalid server configuration");
    }
}
