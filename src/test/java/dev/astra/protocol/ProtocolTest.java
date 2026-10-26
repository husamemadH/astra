package dev.astra.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void fragmentedAndCoalescedFramesAreEquivalent() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        Protocol.request(out, 1, 7, Protocol.payloadBuffer(3), false);
        Protocol.request(out, 2, 0, Protocol.payloadBuffer(3), false);
        InputStream fragments = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(1, len));
            }
        };
        for (InputStream in : new InputStream[]{fragments, new ByteArrayInputStream(bytes.toByteArray())}) {
            var reader = new Protocol.Reader(in);
            assertEquals(7, reader.read().value());
            assertEquals(3, reader.read().size());
            assertEquals(3, reader.read().size());
            assertEquals(1, reader.read().size());
            assertEquals(Protocol.END, reader.read().type());
            assertEquals(2, reader.read().id());
            assertEquals(Protocol.END, reader.read().type());
            assertNull(reader.read());
        }
    }

    @Test void rejectsLengthsBeforeReadingOrAllocatingBody() throws Exception {
        for (int length : new int[]{-1, 0, 9, Protocol.MAX_FRAME + 1, Integer.MAX_VALUE}) {
            var bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeInt(length);
            assertThrows(ProtocolException.class, () -> new Protocol.Reader(new ByteArrayInputStream(bytes.toByteArray())).read());
        }
    }

    @Test void distinguishesCleanEofFromTruncatedHeaderOrBody() throws Exception {
        assertNull(new Protocol.Reader(InputStream.nullInputStream()).read());
        assertThrows(EOFException.class, () -> new Protocol.Reader(new ByteArrayInputStream(new byte[]{0})).read());
        assertThrows(EOFException.class, () -> new Protocol.Reader(new ByteArrayInputStream(new byte[]{0,0,0,19,1})).read());
    }

    @Test void rejectsUnsupportedVersionTypeFlagsAndFixedLengths() throws Exception {
        var bytes = new ByteArrayOutputStream();
        Protocol.begin(new DataOutputStream(bytes), 1, 3, false);
        byte[] valid = bytes.toByteArray();
        for (int[] change : new int[][]{{4, 2}, {5, 99}, {22, 2}, {3, 18}}) {
            byte[] malformed = valid.clone();
            malformed[change[0]] = (byte) change[1];
            assertThrows(ProtocolException.class, () -> new Protocol.Reader(new ByteArrayInputStream(malformed)).read());
        }
    }
}
