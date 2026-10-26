# 02 — TCP is a stream, so we invent message boundaries

Read `protocol/Protocol.java` and `ProtocolTest`. TCP delivers ordered bytes, not Java messages. A single `read` may return half a header; two writes may arrive in one read. We prefix each frame with its size and read exactly that many bytes.

A request is `BEGIN → DATA → DATA → … → END`. Its response is `ACK`. A 500 MiB logical request still uses one 64 KiB buffer: chunking limits memory, while framing tells the receiver where each piece ends. The `Reader` reuses its buffer, so a DATA frame must be consumed before reading the next frame.

Run `./mvnw test`. Find the test that deliberately delivers one byte at a time. Try changing the prefix to a negative value: validation should reject it before allocating memory.

Question: does calling `flush()` create a TCP message boundary? No. It only pushes buffered bytes toward the socket; the receiver still needs framing.
