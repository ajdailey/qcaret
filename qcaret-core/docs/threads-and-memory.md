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
| CPU steps of blocking tasks (`CpuSteps`) | A second fixed pool of the same size and queue bound | `qcaret-bulk-N` |
| Blocking (`start(Kind, Runnable)`) | A virtual thread per task | `qcaret-io-`, `qcaret-search-`, `qcaret-watch-`, `qcaret-ipc-`, `qcaret-lifecycle-`, `qcaret-watchdog-` |
| Print job (`Kind.PRINT`) | A daemon platform thread per job: native print calls keep per-thread state, and a virtual thread can change carrier between them | `qcaret-print-` |

- Every task runs inside a wrapper that passes an exception it ends with to the
  failure handler given to the constructor, which is also each thread's
  uncaught exception handler. No task returns a `Future`.
- After `close`, `start` and `executeCpu` throw `RejectedExecutionException`;
  `tryStart` returns false instead.
- `close(bound)` rejects new tasks, waits up to the bound for running ones,
  then interrupts what is left (`shutdownNow` for a pool). A virtual thread
  that calls `close` does not wait for itself. `liveThreads()` counts what has
  not ended, and `liveThreadNames()` names it for a log line, each pool as one
  `qcaret-cpu-*` or `qcaret-bulk-*` entry with its count.
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

### CPU steps of blocking tasks

`CpuSteps.run(token, step)` runs a step on the `qcaret-bulk-` pool when called
on a virtual thread `Workers.start` started, and waits for it; on any other
thread it runs the step inline. A blocking task must run every CPU-bound part
of its work this way: a virtual thread that computes holds its carrier, and the
carrier pool has `availableProcessors()` threads, so that many computing tasks
stall every virtual task, including quit and the settings saves.

- The calling thread does the reads and writes; a step does not block on I/O.
  File loops hand over one block at a time: `LoadPass` (load and reload),
  `DocumentFiles.save`, `Digests`, `Checksums.ofFile` and `RecoveryFile` read
  or write a block of 1 MiB (1 Mi chars when encoding) and run its digest,
  decode or encode as a step. Whole-text work (the load's text build and
  layout, a search's decode, match and digest, `FileReplace`'s checks and
  replacement, the replace preview, save cleanup, grammar parsing) is one step.
- A cancel while the step is queued ends the wait with a
  `CancellationException` and the step never runs; a running step is waited
  for, since it may use the caller's buffers and reservation, and checks the
  token itself. An interrupt does the same. `close` keeps the bulk pool
  accepting steps until the blocking threads end or are interrupted, so a save
  running at quit finishes its writes; a step the pool then drops ends its
  waiter with a `CancellationException`.
- The bulk pool is separate from `executeCpu` because its steps include
  whole-file work that does not split into blocks (a layout, a regex replace
  over a document): in one FIFO queue those would hold highlighting, folding
  and find tasks for their full length. Bulk steps queue only behind each
  other.
- The end of a quit runs on `LIFECYCLE` and drains the queued recovery writes;
  their encoding runs as bulk steps like any other.

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
