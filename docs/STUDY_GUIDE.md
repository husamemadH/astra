# Study Astra from the beginning

A socket is one endpoint of a TCP conversation. A thread is one independently scheduled path through the program. A queue holds work until a thread is available. Start with those three ideas; you do not need to understand the final benchmark all at once.

The numbered commits form a runnable course. Each implementation stage passed its relevant tests before the next stage was saved. List them in their original order:

```sh
git log --reverse --oneline
```

Read one commit without changing your files:

```sh
git show <commit>
git show <commit>:src/main/java/dev/astra/Main.java
```

To run an earlier stage without disturbing the main checkout:

```sh
git worktree add --detach ../astra-lesson <commit>
cd ../astra-lesson
./mvnw clean verify
```

Use the lesson and commands **from that commit**: the CLI evolves from positional arguments into named options in lesson 08. Remove the temporary checkout later with `git worktree remove ../astra-lesson` from the main checkout.

| Stage | Read | What you should understand before continuing |
| --- | --- | --- |
| 01 | [Project](lessons/01-project.md) | Where Java source, tests, and build settings live |
| 02 | [Framing](lessons/02-framing.md) | Why TCP needs length prefixes and how chunks bound memory |
| 03 | [Baseline](lessons/03-baseline.md) | How one blocking conversation runs from BEGIN to ACK |
| 04 | [Scheduling](lessons/04-scheduling.md) | What changes when the same handler uses different threads |
| 05 | [Counters](lessons/05-counters.md) | Why shared increments need coordination |
| 06 | [Lifecycle](lessons/06-lifecycle.md) | How queued, active, and closed connections are kept consistent |
| 07 | [Pipelining](lessons/07-pipelining.md) | How independent writers/readers avoid waiting for every reply |
| 08 | [Operation](lessons/08-operation.md) | How to inspect the server and interpret its metrics |
| 09 | [Experiments](lessons/09-experiments.md) | How to compare designs fairly and preserve raw evidence |
| 10 | [Failures](lessons/10-failures.md) | Why cleanup and race tests matter as much as successful traffic |

## A manageable way to study each stage

1. Read the lesson's explanation and question.
2. Run its tests. Locate one assertion that proves the main idea.
3. Trace one request by hand through the named classes.
4. Change one parameter or introduce one fault, predict what will happen, then run it.
5. Restore your experiment before moving on; keep your notes in a separate file.

Useful focused commands:

```sh
./mvnw -Dtest=ProtocolTest test
./mvnw -Dtest=SchedulingTest test
./mvnw -Dtest=RegistryTest test
./mvnw -Dtest=LoadClientTest test
./mvnw -Dtest=FailureHandlingTest test
```

## Exercises with expected observations

- **Set pipeline depth to 1, then 16.** At depth 1 each connection waits for its reply. Larger depth can hide waiting, but may raise latency; it cannot exceed the machine's resource limits.
- **Set W=1 and Q=1; hold three connections open.** One runs, one waits, and the third is refused. Closing a running connection releases a worker.
- **Set a short idle timeout.** A quiet processing connection moves from registry to history with `IDLE_TIMEOUT`. A queued connection has its own queue-wait timeout.
- **Switch the counter mode.** Final totals should match. Throughput differences are measurements, not guarantees about which mechanism always wins.
- **Increase workers beyond processor count.** Throughput may flatten or decline while latency rises. Compare client and server CPU before assigning a cause.

Read [ARCHITECTURE.md](ARCHITECTURE.md) when you want the complete picture. Return to the small lesson files whenever the full code feels too dense.
