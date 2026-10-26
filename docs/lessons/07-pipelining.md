# 07 — Send work while replies are still arriving

Read `LoadClient.Connection.write` and `read`. There are two client threads for each TCP connection. The writer streams complete requests back-to-back; the reader continuously drains acknowledgements. A semaphore limits how many requests may be outstanding, preventing unbounded memory use.

The writer publishes the request ID and start time before sending BEGIN. The reader verifies response order and payload size, measures latency, and releases a permit. After the sending window ends, `shutdownOutput()` closes only the upload half of the connection: remaining replies still arrive before the socket closes.

Run `LoadClientTest`. One test writes 16 whole requests before reading any ACK. Another streams traffic with two connections, CRC32, and eight outstanding requests each, then checks every byte and reply. Latencies use fixed-size histogram buckets, so long runs do not keep millions of individual timings in memory.

Question: why not send a whole window and only then read replies? Both TCP directions have finite buffers. A blocked server reply can stop request reads and eventually block the client writer. Concurrent reply draining avoids that deadlock.
