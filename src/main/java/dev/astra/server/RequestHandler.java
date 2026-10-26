package dev.astra.server;

import dev.astra.protocol.*;
import java.io.*;
import java.util.zip.CRC32;

/** Sequential request state machine, deliberately independent of how its thread is assigned. */
public final class RequestHandler {
    public interface Events {
        default void bytes(int count) {}
        default void completed() {}
        default void failed() {}
    }

    private RequestHandler() {}

    public static void serve(InputStream input, OutputStream output, long maxPayload,
                             boolean verify, Events events) throws IOException {
        var reader = new Protocol.Reader(input);
        var out = new DataOutputStream(new BufferedOutputStream(output, Protocol.CHUNK));
        long lastId = 0, id = 0, expected = 0, received = 0;
        boolean pending = false;
        CRC32 crc = new CRC32();
        try {
            while (true) {
                Protocol.Frame frame = reader.read();
                if (frame == null) {
                    if (pending) throw new EOFException("EOF during request " + id);
                    return;
                }
                if (!pending) {
                    if (frame.type() != Protocol.BEGIN || frame.id() <= lastId)
                        throw new ProtocolException(ProtocolException.SEQUENCE, "Expected BEGIN with increasing ID");
                    id = frame.id();
                    pending = true;
                    if (frame.value() > maxPayload || frame.flags() != (verify ? 1 : 0))
                        throw new ProtocolException(ProtocolException.PAYLOAD, "Payload limit or checksum mode mismatch");
                    expected = frame.value();
                    received = 0;
                    crc.reset();
                } else {
                    if (frame.id() != id)
                        throw new ProtocolException(ProtocolException.SEQUENCE, "Interleaved request IDs");
                    if (frame.type() == Protocol.DATA) {
                        if (frame.size() > expected - received)
                            throw new ProtocolException(ProtocolException.PAYLOAD, "Payload exceeds declared size");
                        received += frame.size();
                        if (verify) crc.update(frame.data(), frame.offset(), frame.size());
                        events.bytes(frame.size());
                    } else if (frame.type() == Protocol.END) {
                        if (received != expected)
                            throw new ProtocolException(ProtocolException.PAYLOAD, "Payload is shorter than declared size");
                        if (frame.value() != (verify ? crc.getValue() : 0))
                            throw new ProtocolException(ProtocolException.CHECKSUM, "Checksum mismatch");
                        events.completed();
                        pending = false;
                        lastId = id;
                        Protocol.ack(out, id, received);
                    } else {
                        throw new ProtocolException(ProtocolException.SEQUENCE, "Expected DATA or END");
                    }
                }
            }
        } catch (ProtocolException e) {
            try { Protocol.error(out, id, e); } catch (IOException ignored) { /* original error wins */ }
            throw e;
        } finally {
            if (pending) events.failed();
        }
    }
}
