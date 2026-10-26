package dev.astra.protocol;

import java.io.IOException;

public final class ProtocolException extends IOException {
    public static final int LENGTH = 1, VERSION = 2, SEQUENCE = 3, PAYLOAD = 4, CHECKSUM = 5;
    private final int code;

    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() { return code; }
}
