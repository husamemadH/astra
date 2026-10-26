# 03 — The simplest working TCP conversation

Read `RequestHandler`, `TcpServer`, and `SimpleClient`, in that order. The handler is a state machine: it expects BEGIN, accepts chunks, checks END, and acknowledges. The server assigns a thread to that handler; the client sends requests.

Build, then use two terminals: `java -jar target/astra.jar server` and `java -jar target/astra.jar client`. The server's accept loop does all the work itself. Another connection waits until the first client leaves. The reference client also waits for each reply before starting its next request.

`BaselineTest` sends a 500 MiB request while the test JVM is limited to 128 MiB. This is possible because neither endpoint holds the whole payload in memory.

Question: why separate the handler from the accept loop? The next lesson can change thread assignment without changing what a valid request means.
