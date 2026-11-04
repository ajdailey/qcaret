# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership,
paths and atomic writes, log-safe text, text storage and small utilities. The
other document engine packages (`document`, `edit`, `syntax`, `search`,
`diff`, `watch`, `workspace`) exist as empty packages with their layer rules
in place. Tests run without a display.

The test jar carries `testkit` for the other modules' tests and
`qcaret-bench`: `Poll.until`, a bounded poll for state another thread
produces, and `TempTrees.delete`, which deletes a tree and retries until a
bound, because Windows keeps a file a terminated child process had open
delete-pending for a moment.

## Packages

| Package | Layer | Contents |
|---|---|---|
| `core.util` | 1 | `Checks`, `Streams`; depends on no other QCaret package |
| `core.concurrent` | 2 | `Workers`, `CancellationToken`, `OperationGate`, `OperationHandle`, `OperationOutcome`, `WorkerThread` |
| `core.io` | 3 | `SafePaths`, `AtomicFiles` |
| `core.text` | 4 | `LogText` |
| `core.buffer` | 5 | `TextStorage`, `TextSnapshot` and three storage candidates |
| `core.document` | 6 | The mutable document |
| `core.edit`, `core.syntax`, `core.search`, `core.diff` | 7 | Editing operations, grammars, search, line diff; none uses another |
| `core.watch`, `core.workspace` | above `io` | External change detection, folder index |

A package uses only lower layers (`ArchitectureTest.coreLayers` in
`qcaret-launcher`).

## Threads (`core.concurrent`)

`Workers` owns every thread QCaret starts, and nothing else creates a thread,
an executor, a `Timer` or a `ThreadFactory` (enforced by `ArchitectureTest`).
The launcher creates one instance and closes it at shutdown.

| Work | Runs on | Names |
|---|---|---|
| CPU-bound (`executeCpu`) | Fixed pool of platform threads, bounded queue of 4,096 | `qcaret-cpu-N` |
| Blocking (`start(Kind, Runnable)`) | A virtual thread per task | `qcaret-io-`, `qcaret-search-`, `qcaret-watch-`, `qcaret-ipc-`, `qcaret-lifecycle-`, `qcaret-watchdog-` |

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

A background operation returns an `OperationHandle` (`cancel`, `isDone`,
`await`) and ends `COMPLETED`, `CANCELED` or `FAILED`. Its listener's final
call comes before `isDone()` turns true. `CancellationToken.check()` throws
`CancellationException` once canceled.

`OperationGate` counts the operations using a resource: `enter()` admits one
until `close()`, `exit()` ends it, and `awaitIdle` returns once the gate is
closed and the count is zero. `enter` and `exit` are lock-free, so the UI
thread may call them.

`@WorkerThread` marks a method that runs only on a worker. In `app.controller`
a blocking file or settings call, or a call to a `@WorkerThread` method, must
come from such a method or from a worker task's `run`.

## Paths (`core.io`)

Every path from outside the process goes through `SafePaths`.

| Method | Rule |
|---|---|
| `userFile` | Rejects empty, NUL and, on Windows, device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. A missing path needs an existing parent directory |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |

An editor opens whatever file its user names, so a user file path is
canonicalized and type-checked, not contained.

`AtomicFiles.write` puts the content in a temporary file in the target's
directory (POSIX `rw-------`), then moves it over the target with
`ATOMIC_MOVE`; on failure the target is unchanged and the temporary file
deleted. Nothing goes to `java.io.tmpdir`. `write(Path, InputStream)` streams
the content through a 64 KiB buffer. `writeOwnerOnly`, for a secret, also
replaces the temporary file's ACL with one entry for its owner where there are
ACLs but no POSIX permissions (Windows).

## Text storage (`core.buffer`)

`TextStorage` is mutable text with a line index, edited through
`replace(offset, removeLength, text)` on one thread. `snapshot()` returns an
immutable `TextSnapshot` that later edits do not change, for reading on a
worker. Both read through `TextContent`: `CharSequence`, `lineCount`,
`lineAt`, `offsetOfLine`, `lineLength`, `line` and `readChunks`, which passes
the text one internal chunk at a time.

Line breaks are LF, CR LF and CR; lines are zero-based. A CR LF pair split
across two chunks or leaves is one break: each node counts a trailing CR as a
break, and joining a node that ends with CR to one that starts with LF
subtracts one (`LineBreaks`).

| Candidate | Structure | Snapshot |
|---|---|---|
| `PieceTreeStorage` | AVL tree of pieces over immutable 64 Ki-char `String` chunks with line-break offsets and append-only add chunks; edits copy the path | O(1) |
| `RopeStorage` | AVL tree of immutable `String` leaves of at most 2 Ki chars; an edit rebuilds the leaves it touches and copies the path | O(1) |
| `GapBufferStorage` | One `char[]` with a gap; line starts in a gapped `int[]`, stored from the end of the text past the gap | O(1); the next edit copies both arrays, O(n) |

The storage benchmark in `qcaret-bench` decides between them; the others are
then deleted.

## Log text (`core.text`)

`LogText.oneLine` reduces a value that did not originate in QCaret's code (a
path, a pattern, an exception message) to one line before it reaches a logger:
C0 controls, DEL, NEL, U+2028 and U+2029 become `_`, and the result is cut at
1,024 chars. `config/findsecbugs-taint.txt` registers it with find-sec-bugs as
removing CR and LF.

## Utilities (`core.util`)

| Class | Contract |
|---|---|
| `Checks` | `atLeast` and `notNegative` throw `IllegalArgumentException` naming the argument |
| `Streams` | `readLimited` reads a stream to its end and throws `IOException` once it passes the limit, so an oversized file is refused rather than truncated |

## Tests

`WorkersTest` covers thread names, failure routing, rejection after close,
`tryStart`, and that no task runs after `close` returns. A pool can report
terminated just before its last thread ends, and the live set lags a removal,
so the lifecycle checks poll.
`TextStorageTest` applies 2,000 seeded random inserts, deletes and
replacements to each candidate (400 on documents above 128 Ki chars, with a
CR LF across a chunk boundary) and compares content, every line, every offset's
line, chunk reads and earlier snapshots with a `StringBuilder`. A case is
named by candidate and seed; `-Dqcaret.storage.seed=N` runs one seed.
`OperationGateTest`, `SafePathsTest` (including the Windows device forms on
every OS), `AtomicFilesTest`, `LogTextTest`, `UtilTest` and `TestkitTest`
cover the other classes.
