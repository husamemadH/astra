package dev.astra.client;

import dev.astra.protocol.Protocol;

public final class ClientConfig {
    public String host = "127.0.0.1";
    public int port = 9000, connections = 1, pipelineDepth = 16, chunkBytes = Protocol.CHUNK;
    public long payloadBytes = 1L << 20;
    public double warmupSeconds = 0, durationSeconds = 30, drainSeconds = 30;
    public boolean checksum, tcpNoDelay = true;
    public int socketBufferBytes, connectTimeoutMillis = 5_000;

    public void validate() {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || connections < 1 || pipelineDepth < 1
                || chunkBytes < 1 || chunkBytes > Protocol.CHUNK || payloadBytes < 0 || socketBufferBytes < 0
                || !Double.isFinite(durationSeconds) || durationSeconds <= 0
                || !Double.isFinite(warmupSeconds) || warmupSeconds < 0
                || !Double.isFinite(drainSeconds) || drainSeconds <= 0 || connectTimeoutMillis < 1
                || warmupSeconds + durationSeconds + drainSeconds > 86_400)
            throw new IllegalArgumentException("Invalid client configuration (duration/drain > 0; chunk 1..65536; total runtime <= 1 day)");
    }
}
