# 04 — Same conversation, three ways to assign threads

Read the `switch` in `TcpServer.accept`, then `SchedulingTest`. BASELINE runs the handler immediately. THREAD_PER_CONNECTION starts a platform thread. POOL submits the same handler to a fixed `ThreadPoolExecutor`.

In the pool, a task is an entire connection, not a request. W workers and Q queue slots mean at most W processing connections and Q waiting connections. A full pool rejects the next socket and closes it. This is backpressure by refusal. The thread-per-connection design intentionally has no such application limit.

Run the server with `server 9000 pool`, then the reference client. Run the tests to observe W=1, Q=1, and three connections: one works, one waits, one is refused. When the first leaves, the second can begin.

Question: why can a persistent idle connection reduce pool capacity? Its task owns a worker until that connection closes. Later lessons add timeout and history tracking so this is visible and recoverable.
