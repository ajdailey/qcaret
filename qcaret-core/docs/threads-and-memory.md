# Threads and memory

`Workers`, `CancellationToken`, `WorkerThread` and `MemoryBudget` in
`core.concurrent` of [qcaret-core](../README.md).

## Workers

`Workers` owns every thread QCaret starts, and nothing else creates a thread,
an executor, a `Timer` or a `ThreadFactory` (enforced by `ArchitectureTest`).
The launcher creates one instance and closes it at shutdown.

| Work | Runs on | Names |
|---|---|---|
| CPU-bound (`executeCpu`) | Fixed pool of platform threads, bounded queue of 4,096 | `qcaret-cpu-N` |
| Blocking (`start(Kind, Runnable)`) | A virtual thread per task | `qcaret-io-`, `qcaret-search-`, `qcaret-watch-`, `qcaret-ipc-`, `qcaret-lifecycle-`, `qcaret-watchdog-` |
| Print job (`Kind.PRINT`) | A daemon platform thread per job: native print calls keep per-thread state, and a virtual thread can change carrier between them | `qcaret-print-` |

- Every task runs inside a wrapper that passes an exception it ends with to the
  failure handler given to the constructor, which is also each thread's
  uncaught exception handler. No task returns a `Future`.
- After `close`, `start` and `executeCpu` throw `RejectedExecutionException`;
  `tryStart` returns false instead.
- `close(bound)` rejects new tasks, waits up to the bound for running ones,
  then interrupts what is left (`shutdownNow` for the pool). A virtual thread
  that calls `close` does not wait for itself. `liveThreads()` counts what has
  not ended, and `liveThreadNames()` names it for a log line.
- `shutdownHook(task)` creates the JVM shutdown hook's platform thread,
  `qcaret-shutdown`, unstarted and outside the live set, so `close` neither
  waits for nor interrupts it.
- `start` adds the thread to the live set, then checks `closed` again before
  starting it, and `close` reads the set until it holds no other thread. A
  start racing `close` is therefore rejected or waited for; no task starts
  after `close` returns.
- Pool threads are daemon threads, so a missed `close` cannot keep the JVM
  alive.
- Code that catches `InterruptedException` restores the interrupt flag and
  returns.

`@WorkerThread` marks a method that runs only on a worker, such as
`DocumentFiles.load` and `DisplayProjection.compute`. Its callers are worker
methods or tasks submitted to `Workers` (`ArchitectureTest` in
`qcaret-launcher`).

## Cancellation

`CancellationToken.check()` throws `CancellationException` once canceled.

## Memory budget

`MemoryBudget` is the share of `Runtime.maxMemory()` that open documents hold,
`DOCUMENT_PERCENT` (60%). `reserve` is lock-free and refuses, reserving
nothing, what the share cannot hold; the holder calls `release` when the
document closes. Loads, search reads, replaces, result sets, the file index,
Go to Symbol scans and the Bookmarks list charge it.
