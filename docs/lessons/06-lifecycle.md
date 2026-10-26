# 06 — A connection has a life, not just a socket

Read `ConnectionRegistry`, especially `Entry.start`, `expire`, and `finish`, then the corresponding calls in `TcpServer`. A connection starts QUEUED, may become PROCESSING, and finally moves into immutable history. IDs identify connections even when a client reconnects from the same address.

Timeout and worker threads can race. The entry lock decides whether processing starts or queue expiry wins. Closing a socket wakes blocked I/O. A processing connection is finalized only after its handler exits, so its history includes final counts. The lifecycle lock keeps a connection from appearing in both active and historical lists in one snapshot; no network operation holds that lock.

Run `RegistryTest`. It demonstrates two required scenarios: inspect one active and one closed client; then expire an idle client while another continues working. It also races queue expiry against worker start and races multiple attempts to finalize one connection.

Question: why bound history too? Even a bounded worker pool can leak memory if it stores every closed connection forever. Eviction counts explain why retained history may be smaller than lifetime totals.
