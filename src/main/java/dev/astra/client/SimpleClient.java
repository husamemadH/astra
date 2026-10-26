package dev.astra.client;

import dev.astra.protocol.Protocol;
import java.io.*;
import java.net.*;

/** Stop-and-wait reference client: the later load generator adds concurrent reply draining. */
public final class SimpleClient {
    private SimpleClient() {}

    public static long upload(String host, int port, long payloadBytes, int requests) throws IOException {
        return upload(host, port, payloadBytes, requests, false);
    }

    public static long upload(String host, int port, long payloadBytes, int requests, boolean checksum) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), Protocol.CHUNK));
            var reader = new Protocol.Reader(new BufferedInputStream(socket.getInputStream()));
            byte[] chunk = Protocol.payloadBuffer(Protocol.CHUNK);
            long acknowledged = 0;
            for (long id = 1; id <= requests; id++) {
                Protocol.request(out, id, payloadBytes, chunk, checksum);
                var ack = reader.read();
                if (ack == null || ack.type() != Protocol.ACK || ack.id() != id || ack.value() != payloadBytes)
                    throw new IOException("Unexpected acknowledgement");
                acknowledged += ack.value();
            }
            return acknowledged;
        }
    }
}
