# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership,
paths, atomic and safe file writes, encodings and line endings, text storage,
the document model and small utilities. The packages `edit`, `syntax`,
`search`, `diff`, `watch` and `workspace` are empty, with their layer rules in
place. Tests run without a display.

The test jar carries `testkit` for the other modules' tests and
`qcaret-bench`: `Poll.until`, a bounded poll for state another thread
produces, and `TempTrees.delete`, which deletes a tree and retries until a
bound, because Windows keeps a file a terminated child process had open
delete-pending for a moment.

## Packages

| Package | Layer | Contents |
|---|---|---|
| `core.util` | 1 | `Checks`, `Streams`; depends on no other QCaret package |
| `core.concurrent` | 2 | `Workers`, `CancellationToken`, `OperationGate`, `OperationHandle`, `OperationOutcome`, `WorkerThread`, `MemoryBudget` |
| `core.io` | 3 | `SafePaths`, `AtomicFiles`, `SafeSave`, `FileStamp`, `BinaryDetector` |
| `core.text` | 4 | `TextEncoding`, `EncodingDetector`, `LineEnding`, `LineEndingStats`, `LogText` |
| `core.buffer` | 5 | `RopeStorage`, `TextSnapshot`, `TextCursor`, `LineView` |
| `core.document` | 6 | `Document`, `EditBatch`, `DocumentChange`, undo, selections, anchors, `DocumentFiles` |
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

`MemoryBudget` is the share of `Runtime.maxMemory()` that open documents
hold, `DOCUMENT_PERCENT` (60%). `reserve` is lock-free and refuses, reserving
nothing, what the share cannot hold; the holder calls `release` when the
document closes.

`@WorkerThread` marks a method that runs only on a worker. In `app.controller`
a blocking file or settings call, or a call to a `@WorkerThread` method, must
come from such a method or from a worker task's `run`.

## Paths and file writes (`core.io`)

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

`SafeSave.save(target, baseline, content, token)` saves a user file. It runs
on a worker.

1. A target that exists with a `FileStamp` (size, modified time, file key)
   other than `baseline` fails with `ExternalModificationException`; nothing
   is written. A missing target is written.
2. A symbolic link is resolved, so the link stays a link.
3. The content goes to `.<name>.<random>.qcaret-tmp` in the target's
   directory, which receives the target's POSIX permissions or DOS hidden and
   system flags and is forced to disk, then moves over the target with
   `ATOMIC_MOVE`. A cancel before the move leaves the target unchanged and
   deletes the temporary file.
4. The target is written in place (truncate, write, force) when it has more
   than one hard link, its owner differs from the temporary file's, its
   directory is not writable, or the move fails five times 100 ms apart
   (Windows sharing violations). A failure partway through an in-place write
   leaves the target partly written.

`BinaryDetector` calls a file binary when its first 64 KiB hold a NUL byte
(unless the encoding is UTF-16 or UTF-32) or more than 10% C0 controls other
than tab, LF, FF and CR.

## Encodings and line endings (`core.text`)

`TextEncoding` is a charset plus whether the file has its byte order mark; the
BOM is not text, and a save writes it back. BOM-sensing `UTF-16` and `UTF-32`
are taken as their big-endian forms. `TextEncoding.forName` returns null for a
name this Java lacks, so a remembered encoding the runtime does not have reads
as none. `TextEncoding.MENU` lists the Encoding menu's charsets; the
distribution smoke test checks that every bundle runtime has them.

`EncodingDetector.detect` examines the whole file, in this order:

1. BOM: UTF-8, UTF-32 LE/BE, UTF-16 LE/BE. `FF FE 00 00` is UTF-32LE, so a
   UTF-16LE file whose first char is U+0000 reads as UTF-32LE.
2. UTF-16 without BOM: at least 30% zero bytes at one parity and under 5% at
   the other in the first 64 KiB.
3. Declared: an XML declaration, an HTML `meta charset` or a Python or Emacs
   coding line in the first two lines, within the first 1 KiB; used only when
   the whole file decodes strictly with it. A name Java lacks is retried
   without hyphens (`latin-1`); a declared UTF-16 or UTF-32 is ignored.
4. The whole file is valid UTF-8 (an empty file is).
5. The fallback (`encoding.fallback`, windows-1252 by default), or ISO-8859-1
   when the fallback does not decode the file strictly.

`forced` applies a user's charset, keeping a matching BOM as the file's BOM.

`LineEndingStats` counts LF, CR LF and CR breaks and reports the dominant and
mixed state; `LineEnding` names the three kinds.

## Text storage (`core.buffer`)

`RopeStorage` holds the text: an AVL tree whose nodes each hold one immutable
`String` leaf of at most 2 Ki chars plus the length and line breaks of the
subtree. An edit rebuilds the leaves it touches and copies the path to them.
`snapshot()` returns the current root as an immutable `TextSnapshot` in O(1),
for reading on a worker. `TextStorage` (mutable) and `TextSnapshot` read through
`TextContent`: `CharSequence`, `lineCount`, `lineAt`, `offsetOfLine`,
`lineLength`, `line`, `readChunks`, `lineView` and `cursor`.

Line breaks are LF, CR LF and CR; lines are zero-based. A CR LF pair split
across two leaves is one break: each node counts a trailing CR as a break, and
joining a node that ends with CR to one that starts with LF subtracts one
(`LineBreaks`). Leaf scans use `String.indexOf`, also for CR LF text.

| Read | Use | Cost |
|---|---|---|
| `charAt` | Random access | One descent per leaf change; a per-char walk of 100 MiB misses the W7 limit |
| `cursor(offset)` | Sequential per-char reads, forward or back (`TextCursor`) | Copies one leaf at a time into a `char[]`; one descent per leaf |
| `readChunks` | Bulk reads | Passes each leaf range to a `ChunkVisitor` |
| `lineView(line, view)` | One line without copying (`LineView`, reused) | One descent; reads the leaf in place |
| `line(i)` | One line as a `String` | Copies the line |

A `TextCursor` and a `LineView` belong to one thread and read the text version
they were positioned on.

## Document model (`core.document`)

`Document` holds the text of one open file: a `RopeStorage`, its
revision, undo history, line ending counts, encoding and dirty state. One
thread edits it, the first to call a mutating method (the UI thread); a
mutation from another thread, or from a listener while it is being notified,
throws `IllegalStateException`. Workers read `snapshot()`, a
`DocumentSnapshot` (revision, undo state, text, encoding, line ending) taken in
O(1).

### Edits and change events

`apply(EditBatch, origin, after)` applies non-overlapping `Replacement`s given
in the current text's coordinates, highest offset first. Each applied batch,
undo step and redo step increments `revision()` (a `long`, never reused) and
fires one `DocumentChange` to `DocumentListener`s: the revision, the cause
(`EDIT`, `UNDO`, `REDO`) and one `TextEdit` per replacement, by ascending
offset in the text before the change, with the removed and inserted text, the
start line and the line breaks in each. Applying the edits highest offset
first to the text before the change reproduces the text after it. A read-only
document refuses `apply`, `undo` and `redo`.

### Undo

| Rule | Behavior |
|---|---|
| Step | One batch, or every batch between `beginGroup` and the outermost `endGroup` |
| Coalescing | A `TYPING`, `BACKSPACE` or `DELETE` batch merges into the previous step of its kind when it continues it at every caret, within 1 s, with no caret move (`SelectionModel.set`), command, undo, redo or save in between. Typed whitespace after a non-whitespace char starts a new step |
| Multi-caret | A batch is one step whatever its replacement count |
| Redo | Discarded by any new step |
| Bounds | `UndoLimits`: 10,000 steps and 64 MiB of removed and inserted text by default; the oldest steps drop first, the newest is always kept |
| Selections | A step records its origin `SelectionModel`'s selections before and after; `undo(target)` and `redo(target)` restore them into `target` |

`isDirty()` is the undo state differing from the one `markSaved(snapshot)`
recorded, or the encoding differing from the saved one. Undoing back to the
saved state clears it; once the saved state's step is dropped, undo cannot
clear it.

### Positions and selections

| Class | Contract |
|---|---|
| `Anchor` | A live offset mapped through every change with its `Bias`: `BEFORE` stays before an insert at it, `AFTER` moves past it. A position inside a removed range goes to the start (`BEFORE`) or the end (`AFTER`) of the inserted text |
| `PositionMap` | `positions().map(revision, offset, bias)` maps an offset from an earlier revision through a log of the last 10,000 batches and at most 1,000,000 replacements; older revisions give `UNMAPPABLE` |
| `Selection` | Anchor, caret and preferred visual column; empty is a caret |
| `SelectionSet` | Immutable, sorted, with one primary; selections that overlap or touch merge when the set is built |
| `SelectionModel` | One view's selections, from `Document.newSelectionModel()`, mapped through every change: a caret moves past an insert at it, a range does not grow at either end. `set` is a user move and ends typing coalescing |
| `BlockSelection` | Lines and visual columns (`Columns`: tabs to the next stop, East Asian wide chars 2). `rows` gives one `BlockRow` per line with the spaces typing must insert to reach the left column on a short line; in block mode the model maps the block's lines and rebuilds its selections |

### Line endings and encoding

The text keeps every CR, LF and CR LF as read, and a save writes them back
unchanged. `lineEndings()` counts each kind, updated per change from the
replaced range and one char on either side; `isMixed()` drives the status
bar's "Mixed". `lineEnding()`, what Enter inserts, is the most frequent kind at
load, the first seen on a tie, or `newFile.lineEnding` for a file without
breaks. `setEncoding` changes what the next save writes and makes the document
dirty until then.

### Files

`DocumentFiles.load` and `save` run on a worker (`@WorkerThread`) and check
their `CancellationToken` per block.

- `estimate` reads the size and the first 64 KiB and returns a `LoadCost`. A
  char costs 1 byte when the sample is ASCII and 2 otherwise; a UTF-16 or
  UTF-32 BOM divides the char count by 2 or 4. The peak is the bytes read plus
  the decoded text plus the document's text; the retained cost is the
  document's text plus undo headroom of the same size, at most
  `UndoLimits.maxBytes`. A caller reserves `reserveBytes()`, the larger of the
  two, before `load`, and keeps the retained cost while the document is open.
  An ASCII-sampled file whose later text is not Latin-1 is underestimated by
  up to half its text.
- Load reads the file whole (at most `Integer.MAX_VALUE - 8` bytes), takes the
  forced charset or `EncodingDetector`'s choice, throws `BinaryFileException`
  for a binary file unless `LoadOptions.asBinary()` (ISO-8859-1, which maps
  every byte), decodes strictly (`TextCodingException` with the byte offset)
  and encodes the text back. `LoadedFile.roundTrips` false means the bytes
  would change on save; the caller opens the document read-only and names the
  encoding.
- Save encodes the snapshot once to find an unmappable char
  (`TextCodingException` with the char offset, no file touched), then writes
  the BOM and the text through `SafeSave` and returns the new `FileStamp`; the
  caller passes it to the next save and the snapshot to `markSaved`.

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
replacements to the rope (400 on documents above 128 Ki chars, with a CR LF
across a leaf boundary), on text with random, CR LF only and mixed line
endings, and compares content, every line and line view, every offset's line,
forward and backward cursor walks, chunk reads and earlier snapshots with a
`StringBuilder`. A case is named by its endings and seed;
`-Dqcaret.storage.seed=N` runs one seed.

`DocumentRandomTest` runs 1,500 random steps per seed (batches, typing runs,
groups, undo, redo, selection moves, saves, new anchors) and checks after each:
the text against a replay of the change events, revisions one apart, each
event's removed text and start line, anchors and `PositionMap` against a
step-by-step mapping, an undo state always holding the same text, a clean
document holding the saved text, the line ending counts, and every selection
set sorted, disjoint and in bounds. It ends by undoing every step and redoing
them. `-Dqcaret.document.seed=N` runs one seed.

`MemoryBudgetTest` covers the share and refusals.

`DocumentTest` covers change events, coalescing rules, groups, redo
discard, dirty state, the undo bounds, refused edits, anchors, the position
map bound, selection mapping and block rows. `DocumentFilesTest` round-trips
every BOM and mixed line endings byte for byte, and covers the fallback,
binary files, an unmappable save, a malformed forced decode, text that does
not encode back, a canceled load, a file larger than one 1 MiB read block
(regression: the read stopped after the first block), and the load estimate. `SafeSaveTest` covers permissions, a new
file, an external change, a cancel, a symbolic link and a hard link.
`EncodingDetectorTest` covers every detection step, the menu charsets and an
unknown name.
`OperationGateTest`, `SafePathsTest` (including the Windows device forms on
every OS), `AtomicFilesTest`, `LogTextTest`, `UtilTest` and `TestkitTest`
cover the other classes.
