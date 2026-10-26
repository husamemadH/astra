package dev.astra.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Binary framing shared by every server design and client. No TCP packet assumptions. */
public final class Protocol {
    public static final int VERSION = 1, HEADER = 10, CHUNK = 65_536, MAX_FRAME = HEADER + CHUNK;
    public static final int BEGIN = 1, DATA = 2, END = 3, ACK = 4, ERROR = 5;

    private Protocol() {}

    /** DATA points into the Reader's reusable buffer: consume it before reading another frame. */
    public record Frame(int type, long id, long value, int flags, byte[] data, int offset, int size) {}

    public static final class Reader {
        private final DataInputStream in;
        private final byte[] buffer = new byte[MAX_FRAME];

        public Reader(InputStream in) { this.in = new DataInputStream(in); }

        /** null means clean EOF between frames; partial headers/bodies throw EOFException. */
        public Frame read() throws IOException {
            int first = in.read();
            if (first < 0) return null;
            int length = (first << 24) | (in.readUnsignedByte() << 16)
                    | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            if (length < HEADER || length > MAX_FRAME)
                throw new ProtocolException(ProtocolException.LENGTH, "Invalid frame length: " + length);
            in.readFully(buffer, 0, length);
            ByteBuffer b = ByteBuffer.wrap(buffer, 0, length);
            if (Byte.toUnsignedInt(b.get()) != VERSION)
                throw new ProtocolException(ProtocolException.VERSION, "Unsupported protocol version");
            int type = Byte.toUnsignedInt(b.get());
            long id = b.getLong();
            if (id <= 0 && !(type == ERROR && id == 0))
                throw new ProtocolException(ProtocolException.SEQUENCE, "Request ID must be positive");
            return switch (type) {
                case BEGIN -> {
                    exact(length, 19);
                    long bytes = b.getLong();
                    int flags = Byte.toUnsignedInt(b.get());
                    if (bytes < 0 || flags > 1)
                        throw new ProtocolException(ProtocolException.PAYLOAD, "Invalid payload size or flags");
                    yield new Frame(type, id, bytes, flags, buffer, 0, 0);
                }
                case DATA -> {
                    if (length == HEADER)
                        throw new ProtocolException(ProtocolException.LENGTH, "Empty DATA frame");
                    yield new Frame(type, id, 0, 0, buffer, HEADER, length - HEADER);
                }
                case END -> {
                    exact(length, 14);
                    yield new Frame(type, id, Integer.toUnsignedLong(b.getInt()), 0, buffer, 0, 0);
                }
                case ACK -> {
                    exact(length, 19);
                    int status = Byte.toUnsignedInt(b.get());
                    long accepted = b.getLong();
                    if (status != 0 || accepted < 0)
                        throw new ProtocolException(ProtocolException.PAYLOAD, "Invalid acknowledgement");
                    yield new Frame(type, id, accepted, status, buffer, 0, 0);
                }
                case ERROR -> {
                    if (length < 12 || length > 268)
                        throw new ProtocolException(ProtocolException.LENGTH, "Invalid error frame");
                    yield new Frame(type, id, Short.toUnsignedInt(b.getShort()), 0, buffer, 12, length - 12);
                }
                default -> throw new ProtocolException(ProtocolException.VERSION, "Unknown frame type");
            };
        }
    }

    private static void exact(int actual, int expected) throws ProtocolException {
        if (actual != expected)
            throw new ProtocolException(ProtocolException.LENGTH, "Expected frame length " + expected);
    }

    private static void header(DataOutputStream out, int type, long id, int bodySize) throws IOException {
        out.writeInt(HEADER + bodySize);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeLong(id);
    }

    public static void begin(DataOutputStream out, long id, long bytes, boolean crc) throws IOException {
        header(out, BEGIN, id, 9);
        out.writeLong(bytes);
        out.writeByte(crc ? 1 : 0);
    }

    public static void data(DataOutputStream out, long id, byte[] buffer, int count) throws IOException {
        if (count < 1 || count > CHUNK || count > buffer.length) throw new IllegalArgumentException("chunk size");
        header(out, DATA, id, count);
        out.write(buffer, 0, count);
    }

    public static void end(DataOutputStream out, long id, long crc) throws IOException {
        header(out, END, id, 4);
        out.writeInt((int) crc);
    }

    public static void ack(DataOutputStream out, long id, long bytes) throws IOException {
        header(out, ACK, id, 9);
        out.writeByte(0);
        out.writeLong(bytes);
        out.flush();
    }

    public static void error(DataOutputStream out, long id, ProtocolException error) throws IOException {
        byte[] message = error.getMessage().getBytes(StandardCharsets.UTF_8);
        int count = Math.min(256, message.length);
        header(out, ERROR, Math.max(0, id), 2 + count);
        out.writeShort(error.code());
        out.write(message, 0, count);
        out.flush();
    }

    /** Constant memory regardless of logical request size. The caller reuses its payload buffer. */
    public static void request(DataOutputStream out, long id, long bytes, byte[] chunk, boolean verify) throws IOException {
        if (id < 1 || bytes < 0 || chunk.length < 1 || chunk.length > CHUNK)
            throw new IllegalArgumentException("Invalid request ID, size, or chunk buffer");
        begin(out, id, bytes, verify);
        CRC32 crc = new CRC32();
        long remaining = bytes;
        while (remaining > 0) {
            int count = (int) Math.min(remaining, chunk.length);
            data(out, id, chunk, count);
            if (verify) crc.update(chunk, 0, count);
            remaining -= count;
        }
        end(out, id, verify ? crc.getValue() : 0);
        out.flush();
    }

    public static byte[] payloadBuffer(int size) {
        if (size < 1 || size > CHUNK) throw new IllegalArgumentException("chunk size must be 1..65536");
        byte[] buffer = new byte[size];
        for (int i = 0; i < size; i++) buffer[i] = (byte) (i * 31 + 7);
        return buffer;
    }
}
