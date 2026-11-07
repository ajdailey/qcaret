# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership,
paths, atomic and safe file writes, encodings and line endings, text storage,
the document model, editing commands, grammars and the tokenizer, the find
matcher, the HyperSearch text and file search, the line diff, external change
detection, the workspace folder's listings and file index, and small
utilities. Tests run without a display.

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
| `core.io` | 3 | `SafePaths`, `AtomicFiles`, `SafeSave`, `ProcessLock`, `FileStamp`, `FileBaseline`, `Digests`, `BinaryDetector`, `HiddenFiles` |
| `core.text` | 4 | `TextEncoding`, `EncodingDetector`, `LineEnding`, `LineEndingStats`, `LogText`, `BoundedChars` |
| `core.buffer` | 5 | `RopeStorage`, `TextSnapshot`, `TextCursor`, `LineView` |
| `core.document` | 6 | `Document`, `EditBatch`, `DocumentChange`, undo, selections, anchors, `DocumentFiles` |
| `core.edit`, `core.syntax`, `core.search`, `core.diff` | 7 | Editing commands (`LineEdits`, `LineTransforms`, `BlockEdits`, `CommentEdits`, `AutoClose`, `Brackets`, `Moves`, `Words`, `WordIndex`, `Occurrences`), grammars and highlighting, search and file replace (`TextMatcher`, `TextSearch`, `FileSearch`, `FileReplace`, `ReplaceBackups`), line diff (`LineDiff`); none uses another |
| `core.watch`, `core.workspace` | above `io` | External change detection (`FileCheck`, `FileMonitor`); `WalkRules`, `DirectoryListing`, `FileIndex` |

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

`@WorkerThread` marks a method that runs only on a worker, such as
`DocumentFiles.load` and `DisplayProjection.compute`. Its callers are worker
methods or tasks submitted to `Workers` (`ArchitectureTest` in
`qcaret-launcher`).

## Paths and file writes (`core.io`)

Every path from outside the process goes through `SafePaths`.

| Method | Rule |
|---|---|
| `userFile` | Rejects empty, NUL and, on Windows, device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. A missing path needs an existing parent directory |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |
| `holdLock`, `releaseLock` | Name and forget a lock file the process holds, any number at once, compared by real path and file key, so a hard link matches. `userFile` and `SafeSave.save` then reject it under any name: a save replaces the file, the lock stays on the old one, and the next process to try the lock takes the new one |

An editor opens whatever file its user names, so a user file path is
canonicalized and type-checked, not contained.

`ProcessLock.tryAcquire` takes an exclusive lock on a file, which the OS
releases at process death. It returns null when the lock is held, by another
process or this JVM. Reading the file or closing another channel on it leaves
the lock held.

| System | Call | Detail |
|---|---|---|
| Linux, macOS | `flock(2)` through `Flock` | `open` with `O_RDWR`, `O_CREAT`, `O_CLOEXEC`, mode 0600. `java.lang.foreign` by reflection, linked once per process; the API is a preview in Java 21 |
| Windows | `FileChannel.tryLock` | A lock belongs to its handle; no `java.lang.foreign` class loads |

The launchers pass `--enable-native-access=ALL-UNNAMED`. Plain `java -jar` on
Linux and macOS prints the JDK's three `WARNING:` lines on stderr; an
`Enable-Native-Access` manifest entry does not remove them on Java 21.

A JDK that lacks a name, has a different signature or denies native access
fails the link, and `tryAcquire` throws `IOException`. Do not fall back to
`FileChannel.tryLock` on Linux or macOS: it takes a POSIX record lock, which any
closing channel drops and which does not conflict with `flock` on local Linux,
so two launches could both hold the file. The bundles run Java 21; the universal
jar on JDK 22 or later has not been run, and a difference there takes this path.

`AtomicFiles.write` puts the content in a temporary file in the target's
directory (POSIX `rw-------`), then moves it over the target with
`ATOMIC_MOVE`; on failure the target is unchanged and the temporary file
deleted. Nothing goes to `java.io.tmpdir`. `write(Path, InputStream)` streams
the content through a 64 KiB buffer. `writeOwnerOnly`, for a secret, also
replaces the temporary file's ACL with one entry for the current user where
there are ACLs but no POSIX permissions (Windows): `USERDOMAIN\user.name`, then
the bare name, then the file's owner when no name resolves. An elevated
process's new file is owned by `BUILTIN\Administrators`, not the user.
`writeOwnerOnly(Path, ContentWriter)`
takes content too large for one array and forces it to disk before the move.
On Windows a refused move is tried for up to 250 ms: a target open elsewhere
without delete sharing refuses it for that moment.

`SafeSave.save(target, baseline, content, token)` saves a user file. It runs
on a worker.

1. A target that exists with a `FileStamp` (size, modified time, file key)
   other than `baseline` fails with `ExternalModificationException`; nothing
   is written. A missing target is written.
2. A symbolic link is resolved, so the link stays a link. A dangling link
   resolves to the missing file it names, relative to the link's directory.
3. A target that exists and fails `Files.isWritable` fails with
   `AccessDeniedException`; nothing is written. On Windows that is the DOS
   read-only attribute or an ACL.
4. The content goes to `.<name>.<random>.qcaret-tmp` in the target's
   directory and is forced to disk, then the file moves over the target with
   `ATOMIC_MOVE`. Replacing a file, the temporary file is created owner-only,
   takes the target's group before the write, and takes the target's POSIX
   permissions or DOS hidden and system flags before the move. A new file
   takes the default mode. `SafeSave` checks the token on each write to the
   temporary file, so a `ContentWriter` need not; a cancel before the move
   leaves the target unchanged and deletes the temporary file.
5. The target is written in place (truncate, write, force) when it has more
   than one hard link, its owner differs from the temporary file's, the user
   cannot give the temporary file its group, its directory is not writable,
   or the move fails five times 100 ms apart (Windows sharing violations).
   The token is checked before the truncate and never after it: an in-place
   write runs to its end, so Esc does not stop one on a large file. A failure
   partway leaves the target partly written.

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

`EncodingDetector.detect` examines the whole file, in this order;
`candidates` lists the same choices from the file's first 64 KiB alone, for a
caller that tries each over the file (`DocumentFiles.load`):

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
mixed state; `LineEndingStats.Counter` counts a text read in pieces, a CR at a
piece's end waiting for the next char. `LineEnding` names the three kinds.

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
document refuses `apply`, `undo` and `redo`. `applyInBlock` applies a batch
and leaves the origin model in block mode with the given block; a line past
the new text's end becomes the last line. An undo or edit that leaves the
model's block lines gone leaves block mode.

`preedit(offset, text)` replaces an input method's composition text outside
undo: the undo state, and so the dirty state, stay as they are, and the change
fires with cause `PREEDIT`, which recovery skips. It replaces only the current
composition; `offset` places a new one, and no call removes other text. Undo, redo
and `beginGroup` remove the composition first; `apply` refuses while one
shows, since the batch's offsets would include it, so callers run
`clearPreedit()` before computing an edit.

`tabWidth()` (1 to 16, default 4) and `insertSpaces()` are per document;
they change no text.

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
| `BlockSelection` | Lines and visual columns (`Columns`: tabs to the next stop, East Asian wide chars 2). `rows` gives one `BlockRow` per line with the spaces typing must insert to reach the left column on a short line; a block as wide as nothing has empty rows, before a tab or wide char its column falls inside; in block mode the model maps the block's lines and rebuilds its selections |

## Editing commands (`core.edit`)

Functions from a `TextContent` and a `SelectionSet` to an `Edit` (the batch and
the selections after it, or null to map them), so a controller applies each as
one `Document.apply`. A line a selection touches holds part of it; a range
ending at a line's start leaves that line out.

| Class | Commands |
|---|---|
| `LineEdits` | Duplicate (each range after itself, or each caret's lines below them), move lines up or down (touching blocks move together; nothing moves at the edge or when a CR and an LF would become neighbors, which joins two breaks), delete lines (the last line takes the break before it), join (break and the next line's indentation become one space, or none after an empty line or whitespace), upper and lower case one code point at a time (the word at a caret), indent and outdent (empty lines skipped; outdent removes a tab or up to a tab width of spaces), the Tab key (indents when a range spans lines, else a tab or spaces to the next stop), split a range into one selection per line |
| `BlockEdits` | Typing, Backspace, Delete and paste in a `BlockSelection` (Notepad++ column mode): a row that ends left of the block is padded with spaces; the block stays a block, as wide as nothing after typing; paste gives each row one line |
| `Moves`, `Words` | Caret steps by char (a CR LF and a surrogate pair are one), smart Home, End, and word moves that stop at runs of word chars, of other non-blank chars and at line breaks |
| `CommentEdits` | Toggle line comments on the touched lines (removed when every non-blank line has one after its indentation, else added at the block's smallest indentation; a block comment per block of lines without a line comment) and block comments around each selection or a caret's trimmed line; a range keeps text inserted at its ends |
| `Occurrences` | The next exact occurrence of a text at or after an offset, wrapping, and the whole-word occurrences of a word in a range; cancelable, for a worker |
| `LineTransforms` | Sort (code unit, descending, ignoring case), remove duplicate lines (keeps the first) and trim trailing spaces and tabs, over each range's touched lines or, when every selection is a caret, every line but an empty last one. A sort leaves each break in its place, so offsets and selections stay, and `SelectionModel` moves one that lands inside a pair; a removed last line takes the break before it. Cancelable, for a worker |
| `AutoClose`, `Pairs` | Typing with auto-closed pairs at each caret: an open char inserts its pair before a line end, blank, close bracket, `,` or `;` (a quote also needs no word char or same quote before it), a close char before the same char moves over it, Backspace between a pair deletes both. Returns null when no caret uses a pair, so plain typing applies. `Pairs.DEFAULT` is `() [] {} "" ''` |
| `Brackets` | The bracket beside a caret (the char before first) and its partner within bounds: same-pair brackets nest, other chars are ignored, and a `Filter` leaves out brackets (the app passes the ones in strings and comments); `NONE` when the text holds no partner, `BEYOND` when the bounds ended the search |
| `WordIndex` | The distinct words of a text for completion (letter or `_` first, 2 to 100 chars, at most 100,000), sorted by lower case; prefix lookup ignoring case. A word is copied only the first time it is seen |

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
  UTF-32 BOM divides the char count by 2 or 4. The peak is the text plus the
  load's buffers (at most 5 MiB); the retained cost is the text plus undo
  headroom of the same size, at most `UndoLimits.maxBytes` (64 MiB by default).
  `reserveBytes()` is the larger of the two.
- `LoadReservation.reserve` holds `reserveBytes()` from the `MemoryBudget`.
  `setProgress` passes the file bytes read at each `charge`, on the loading
  thread; a retry with another charset counts from the start.
  During the load, `charge` raises it by what the text read so far costs over
  the estimate at that point in the file, since an ASCII sample can hide 2
  bytes per char later. A raise the budget refuses ends the load with
  `LoadTooLargeException`, whose cost is projected from the text read. `keep`
  lowers the hold to `LoadedFile.retainedBytes`, measured from the leaves, and
  hands it to the caller until the document closes; `close` releases the rest
  and belongs in a `finally` block.
- Load holds no copy of the file (at most `Integer.MAX_VALUE - 8` bytes). The
  first 64 KiB give the forced charset or `EncodingDetector.candidates` and the
  binary check; `BinaryFileException` for a binary file unless
  `LoadOptions.asBinary()` (ISO-8859-1, which maps every byte). Each candidate
  takes one `LoadPass`: 1 MiB blocks are digested, decoded strictly into a
  `DocumentBuilder` (rope leaves and line-ending counts) and the chars encoded
  back against the same bytes. A candidate that fails ends its pass at the
  failing byte and the next starts over, so a file in its first candidate is
  read once and a windows-1252 file twice. The last candidate's failure is
  `TextCodingException` with the byte offset. One channel serves every pass, so
  a file replaced by a rename during the load stays the file opened; the
  digest and text come from the same pass, and a write in place between passes
  is not noticed by the load. `LoadedFile.roundTrips` false means the bytes
  would change on save; the caller opens the document read-only and names the
  encoding. A charset with no encoder never round-trips. `LoadedFile.baseline`
  holds the stamp read before the bytes and the SHA-256 of the bytes read.
- `x-JISAutoDetect` reads the file as one block, charged to the reservation:
  it gives another result on split input. Measured over the 173 charsets of
  JDK 21.0.12 at blocks of 1 to 16 bytes, on encoded text and random bytes, it
  is the only one.
- `reload` compares a file with an open document's text without holding the
  file's text and returns a `ReloadedFile`. It reads as a load does, into a
  `TextSink` instead of a `DocumentBuilder`. The first pass (`TextDifference.Prefix`)
  finds the common prefix, the char count and the line endings; equal texts end
  there. The second (`TextDifference.Span`) compares each later char with the
  current text's char at the same distance from the end. A run of equal chars
  is only counted and joins the span when a later char differs, so the run left
  at the end is the common suffix, and the rope builder holds only the span.
  A second-pass digest or char count that differs from the first is an
  `IOException`: the two passes must describe one file. `roundTrips` comes from
  the first pass, as for a load.
- `LoadReservation.reload` charges the buffers, the inserted text twice (a
  `String` and the rope's leaves) and the removed text once (the undo step's
  `String`), the heap `Document.apply` reaches, and the caller holds it until
  the span is applied. Charges draw on the budget only past the document's
  undo headroom, so a document at the budget limit reloads a span of up to about
  a third of its headroom. The span runs from the first difference to the last,
  so two edits far apart cost the text between them. A larger span fails with
  `LoadTooLargeException`: two copies of a text at the limit do not fit the heap.
  The undo step of a span past the headroom stays uncharged once applied.
- Save encodes the snapshot once to find an unmappable char
  (`TextCodingException` with the char offset, no file touched), then writes
  the BOM and the text through `SafeSave` and returns a `FileBaseline`: the
  new stamp and the SHA-256 of the bytes written. The caller passes the stamp
  to the next save and the snapshot to `markSaved`.

`Document.markUnsaved` makes a document dirty until the next `markSaved`, for
a buffer whose file was deleted; undo cannot make it clean.

## Grammars and highlighting (`core.syntax`)

A grammar is an XML file in QCaret's own format, validated against
`grammar.xsd` (beside the classes) while the JDK parser reads it, with DOCTYPE
declarations refused and external DTD and schema access off
(`GrammarReader`). Each regex compiles at read time and each context a rule
names must exist; a failure is an `InvalidGrammarException` naming the file
and, for a schema error, the line. `GrammarSet.BUILT_IN` lists the 47
built-in grammars (`grammars/<id>.xml`), written from the language or
format specifications each file names.

Languages: plain text, Java, XML, HTML, JSON, YAML, SQL, Python, JavaScript,
TypeScript, C, C++, shell, PowerShell, batch, properties, INI, CSS, Markdown,
Go, Rust, C#, Kotlin, Ruby, PHP, Perl, Lua, Groovy (with `Jenkinsfile` and
`*.gradle`), Scala, Swift, Dart, R, Haskell, Objective-C and VB.NET. Formats: Dockerfile,
Makefile, CMake, TOML, diff, CSV, TSV, log files, HCL, Protobuf, GraphQL and
nginx configuration. Each extension belongs to one built-in grammar (`GrammarSetTest`): `.h` is C, `.m`
and `.mm` are Objective-C, `.kts` (so `build.gradle.kts`) is Kotlin. PHP text
outside `<?php ... ?>` is HTML.

| Element | Content |
|---|---|
| `grammar` | `id` (a user grammar with the same id replaces the built-in), `name`, `version`, optional `category` (`programming`, `markup` or `build`: the Syntax menu submenu; without it a user grammar goes in User) |
| `detect` | `extension`s, `fileName`s, one `firstLine` regex |
| `comments` | `line`, `blockStart`, `blockEnd` |
| `brackets`, `autoClose` | `pair open close` |
| `indent` | `increase`, `decrease` and `dedentNext` regexes |
| `folding` | `mode`: `none`, `brackets` (`open` and `close` chars), `markers` (`begin` and `end` regexes) or `indent` |
| `context` | Rules in order: `match` (regex, token, optional `push` and `pop`), `keywords` (words, `ignoreCase`, `wordChars`), `span` (`begin`, optional `end`, `escape`, inner `context`, `multiLine`, `nested`, `bodyNextLine`), `include` (a context's rules in place), `embed` (another grammar between `begin` and `end`) |

Tokens are `TokenType`: comment, string, escape, keyword, keyword2, type,
number, constant, operator, delimiter, function, preprocessor, tag,
attribute, heading, emphasis, link, invalid; text no rule covers is plain.

`Syntax.compile` turns each context into a regex alternating its rules; the
token at a position is the first rule matching there. A backreference or
named group is refused: the alternation renumbers groups. Each rule's
possible first chars come from matching its regex, without a leading
lookbehind or `\b`, against each ASCII char (a match or a hit of the input's
end means it may start); a rule whose leading negative lookbehind matches
every ASCII letter, digit and `_` cannot start after one. Each context keeps
one alternation per distinct set of rules a position may start, so the
tokenizer tries only those, none inside most identifiers, and every rule at a
non-ASCII char.
A span becomes a context of its own (escape, end, inner rules); a span without
`end`, or not `multiLine`, closes at the line's end. Inside an embedded
grammar the embed's `end` is tried first in every context, so HTML's
`</script>` ends JavaScript even inside a string. A zero-width rule that only
switches contexts may repeat 16 times at one position before a char is
skipped.

In a `nested` span the `begin` opens one more level, closed by its own `end`
(Rust and Haskell block comments). A `\1` in a span's `end` or `escape` is the
text the `begin`'s first group matched: Lua's `\[(=*)\[` closes only at `]`
with as many `=` and `]`, a Ruby here document only at its own identifier. Such
a span compiles to a template, and `Syntax.derive` adds one context per
captured text on first use: at most 512 per grammar, each for a text of at
most 64 chars without a backslash. Past those limits the `begin` colors itself
and opens nothing. A `nested` span cannot also be `bodyNextLine`.

A `bodyNextLine` span's body starts at the next line, so a here document's
opening line keeps tokenizing as code after the marker. The `begin` pushes a
pending context (`Syntax.pending`); until the line's end the rules of the
context below it apply, and a `pop` or span end removes that context and
keeps the pending one. At the line's end `Tokenizer` turns each pending
context into its span's body, the first marker's body on top, so the bodies
of `cat <<A <<B` follow in order; single-line contexts below a pending one
close there too. Ruby, Perl, PHP, shell, Dockerfile and HCL here documents
use it with a `\1` end.

`Tokenizer` tokenizes one line from a start state to an end state; a state is
an interned context stack of at most 256 contexts. A deeper push is skipped,
so unclosed nested comments do not grow the states without bound. A line over
the limit (10,000 chars by default) gets no tokens and keeps its state. Each
search reads the line through a
`BoundedChars` that checks the `CancellationToken` and ends the search after
`4 * (rules + 2) * remaining chars + 65,536` reads; that, or a
`StackOverflowError`, leaves the rest of the line plain.

`SyntaxEngine` highlights one document on one thread at a time:

- It stores each line's start state and fold counts, 4 bytes each. `edit`
  shifts both by each edit's line range widened by one line on each side (a
  CR and an LF that join or part stay inside it) and moves the frontier back.
- It copies up to 256 lines, at most 256 Ki chars, in one read: a
  `LineView` locates its line from the start of its leaf, so line by line
  reads scan each leaf once per line.
- `run` returns the `request`ed lines first, then tokenizes from the frontier
  until a deadline, the end, or the frontier passing the last view line it
  had not reached. After the last edited line, and after a stopped frontier
  that an edit moved back, a line ending in the state its successor already
  holds ends the work: the edit has converged.
- The frontier returns the lines of each view range (`setViewport`) as it
  passes them; a requested line beyond it is tokenized from a stored state, or
  from the initial state, and returned again when the frontier passes.
- At the end it pairs the fold counts into regions: per line the closing and
  opening marks left after pairing within the line, outside comments and
  strings; one region per start line, the largest, spanning two lines or
  more. It also counts the lines over the limit.

`GrammarSet` holds the compiled grammars: user grammars replace built-in ones
by id, and a user grammar that does not compile is left out with a message.
`detect` takes a grammar naming the file name, then the extension (any
case), then a first-line regex over the first 1,000 chars, read through
`BoundedChars`; user grammars come first, then plain text.

The format grammars use the token set as follows:

| Grammar | Tokens |
|---|---|
| `csv`, `tsv` | Columns cycle through `keyword2`, `type`, `number`, `preprocessor`, `string`: a separator match pops the column context and pushes the next, and a zero-width `$` match pops it at the line's end, so a CSV quoted field keeps its column across a line break |
| `log` | Levels: FATAL and ERROR `invalid`, WARN `escape`, INFO `type`, DEBUG `keyword2`, TRACE `comment`, as upper-case words, logfmt `level=` and JSON `"level":` values, and one-letter forms (`[E]`, Android `E/Tag`, glog `E0102`). Timestamps `number`, bracketed thread names `delimiter`, `package.Class` logger names `function`, exception names and `Caused by:` `keyword`, `at` and `... N more` lines `comment`, URLs `link`, keys of `key=value` `attribute` |
| `diff` | File headers `heading`, hunk headers `keyword`, added lines `type`, removed lines `escape`, context-format changed lines `constant`; inside a unified hunk a `---` line is a removed line, and a line starting with any other char ends the hunk |

## Search (`core.search`)

`SearchQuery` (text, regex, case, whole word) compiles to a `TextMatcher`; an
invalid regex throws `PatternSyntaxException` and empty text
`IllegalArgumentException`. A regex compiles with `MULTILINE`, so `^` and `$`
match at CR, LF and CR LF. Case folding is Unicode-aware; whole word
(`SearchQuery.wholeWord`) rejects a letter, digit or `_` touching either end.
A regex that ends inside `\Q`, a `(?x)` comment, or a `\Q` inside such a
comment (`Pattern` reads it as a quote) gets `\E`, a line feed or both before
the wrapper's closing parenthesis. Lookbehind and lookahead see text
outside a searched range.

- `next`, `previous` and `all` return non-empty `Match`es; empty matches are
  skipped. `replacementAt` returns the replacement when the query matches
  exactly a range; `replaceAll` returns one `Replacement` per match.
- Every call reads through a char sequence that checks the
  `CancellationToken` every 4,096 chars, so a catastrophic regex stops on
  cancel. A `StackOverflowError` in the regex engine becomes
  `PatternTooComplexException`.
- `ReplaceTemplate` parses a regex replacement once: `$n`, `${name}`, `\n`,
  `\t`, and a backslash before any other char inserts that char. A missing
  group or a dangling `\` or `$` throws `IllegalArgumentException`. A literal
  query's replacement is inserted as is.

The calls are `@WorkerThread`: the find bar runs them on `qcaret-cpu-` workers
from a snapshot, and HyperSearch reuses them.

`TextSearch.search` lists every match of a `TextMatcher` in one text (a
snapshot or a decoded file) as `Hit`s: offsets, line, column, the hash of the
matched text and a snippet of the first line, control chars as spaces,
trimmed, and cut to 500 chars around the match on a longer line. Optional
ranges limit it to the selections; a match then lies inside one.

- A line over `maxLineChars` is not searched and is counted in `skippedLines`. One chunked pass finds those lines; each searched stretch
  ends before a skipped line, so no match runs into one.
- A `PatternTooComplexException` ends the search: the stretch is searched
  again line by line, each line read through a view that ends at the line,
  and `tooComplexLine` names the line that overflowed. The hits before it are
  kept.
- `limit` bounds the hits; `limited` reports that more existed.

`FileSearch.run` walks a directory on the calling `qcaret-search-` worker
without following symbolic links. `FileFilter` holds the masks: `;`-separated
globs on the file name (empty include takes every file), excluded directory
names (default `.git;.svn;.hg;target;node_modules;build`) and hidden files, a leading `.` or the
platform's hidden attribute. Paths in `searchedElsewhere` (open documents) are
not read. Each file is read and decoded on a `qcaret-io-` thread, at most four
at a time, and matched on the CPU pool.

| File | Result |
|---|---|
| Over `maxFileBytes` | `Skip.TOO_LARGE` |
| `BinaryDetector` says binary | `Skip.BINARY` |
| Read fails, or its directory cannot be listed | `Skip.UNREADABLE` |
| `MemoryBudget` refuses five bytes per file byte for the read | `Skip.NO_MEMORY` |
| Otherwise | Decoded with `EncodingDetector` (malformed input as U+FFFD), matched; `FileHits` with encoding, `FileStamp` and SHA-256 when it has hits, skipped lines or a regex failure |

The `Sink` receives results on worker threads in no fixed order; its `room()`
bounds the hits, and 0 ends the walk. Cancel stops the walk; `run` returns
once the reads in flight end.

`FileSearch.searchFile` reads, decodes and matches one file on the calling
thread, as the walk does each file, for a per-file rerun.

### File replace

`FileReplace.replace` replaces a directory replace's matches in one file on
disk; any skip leaves the file untouched.

1. Read the stamp, then the bytes. Skip a missing, unreadable or read-only
   (`Files.isWritable`) file, one over the size limit, one whose SHA-256
   differs from the bytes searched, a binary file, one that does not decode
   with `REPORT` in the encoding searched with, and one whose text does not
   encode back to the same bytes (a stateful encoding would rewrite unchanged
   text).
2. Match each checked span again on exactly its range
   (`TextMatcher.replacementAt`); a span that does not match is dropped.
   Line breaks in a replacement become the file's dominant line ending; the
   other breaks stay as read.
3. Encode with `REPORT`; an unmappable char skips the file. The BOM is kept.
4. `ReplaceBackups.record` writes the original bytes, then `SafeSave` writes
   the file with the stamp from step 1 as its baseline. A change between the
   read and that stamp check skips the file; a change after the check is
   overwritten (the write time of one file).

Six bytes per file byte are reserved from the `MemoryBudget` while a file is
replaced. `ReplaceBackups` keeps one directory per run under
`<settings>/replace-backups/`, named by its UTC start time. Each replaced file
gets `<n>.bak`: a header line with its path and the SHA-256 of its bytes
before and after, then the original bytes, forced to disk before the file is
replaced; a write that does not happen deletes it. After a failed write,
`writeFailed` deletes the backup only when the file still holds its original
bytes. Otherwise the backup stays, and the digest of the bytes the write left
replaces the header's after digest. `restore` writes a backup back through
`SafeSave` only while the file holds the bytes the run left; a file changed
since is refused, one already holding its original reports unchanged. A
restore that fails partway records the bytes it left the same way, so a retry
works. Its digests and the copy stream 1 MiB blocks through the backup's body.
The digest of the body is checked before the write, since `SafeSave` can write
in place. `prune` deletes runs older than the days kept.

## Line diff (`core.diff`)

`LineDiff.diff` returns the shortest edit script between two line lists
(Myers' greedy algorithm after matching the common prefix and suffix), each
run of changes listing its removed lines before its added ones. Past 1,000
edits the rest is reported as all lines removed, then all added: correct, not
shortest, and bounded in time and memory. `hunks` groups a script into
changes with a given number of context lines.

## Workspace folder (`core.workspace`)

`WalkRules` leaves out hidden entries (`HiddenFiles`: a name starting with
`.`, or the DOS hidden attribute on Windows) and directories with an excluded
name, with their trees. `FileSearch` applies the same hidden rule.

`DirectoryListing.read` lists one directory for the browser: directories
first, then files, each by name ignoring case. It reads at most 50,000
entries, in directory order, and reports the cut. A link to a directory lists
as a directory unless its target contains the link (`CYCLE`, never listed
further); a link to a file and a broken link list as files.

`FileIndex.build` walks a folder for Quick Open into paths relative to the
root, joined with `/`. It follows no link to a directory, so a link cycle is
never entered, and lists links to files. Each path reserves 64 bytes plus 2
per char from the `MemoryBudget`; the walk stops at its file limit
(`MAX_FILES`, 200,000) or at a refused reservation and reports which
(`Cut`). A cancel throws `CancellationException` with nothing left charged;
the owner returns `charged()` when it drops the index. An unreadable directory
is skipped.

## External change detection (`core.watch`)

`FileCheck.check(path, baseline, token)` compares the file at the path the
user opened with a `FileBaseline` (stamp, SHA-256, time taken) on a worker.
The path is read through symbolic links at each check, so a retargeted link
compares its new target.

| File at the path | Result |
|---|---|
| No regular file | `MISSING`; `UNCHANGED` when the baseline is null (no file existed) |
| Stamp equals the baseline's, baseline not racy | `UNCHANGED`, nothing read |
| Same SHA-256 | `UNCHANGED` with the new stamp: a touch, or the same bytes renamed over the file |
| Other SHA-256, or a file where the baseline is null | `CHANGED` with the new baseline |

A baseline is racy (`FileBaseline.racy`) while its modification time lies
within 2 s of the time it was taken. A file system with a coarse clock, a copy
that keeps times, or NTFS tunneling can then record a second write of the same
size with the same stamp; the digest decides instead. The next check after the
2 s returns the baseline again with a new time, which settles it. Without a
file key (Windows), a file replaced with the same size and modification time
under a settled baseline reads `UNCHANGED`. `read` digests in 1 MiB blocks and
repeats while the stamp changes during the read, at most three times.

`FileMonitor` runs one `qcaret-watch-` thread while any file is watched. It
calls its listener with every watched file every `pollMillis`, on `checkAll`
(window activation), and with the files `check` names or a directory event
names. `pause` holds that thread for a listener waiting on a changing file. Each directory holding a watched file is registered once with a
`WatchService` and released with its last file; a directory that cannot be
registered is polled only. The poll stays on: a `WatchService` misses network
mounts, polls on macOS, and never sees a link's target change in another
directory. An exception from the listener goes to the thread's
uncaught-exception handler once per run of failures, and checking goes on. The thread ends within 100 ms of the last `unwatch` or of
`close`. `setPollMillis` takes effect at once: the next check is one new interval away.

## Bounded reads (`core.text`)

`BoundedChars` is a `CharSequence` over a line that throws `LimitReached` once
`charAt` passes a read count, and checks a `CancellationToken` every 4,096
reads, so a backtracking regex over it ends. `lineLimit` is `8 * length +
65,536`.

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

`DocumentRandomTest` runs 1,500 random steps per seed over text with surrogate
pairs (batches, typing runs, groups, undo, redo, selection moves, saves, new
anchors, block selections, compositions) and checks after each:
the text against a replay of the change events, revisions one apart, each
event's removed text and start line, anchors and `PositionMap` against a
step-by-step mapping, an undo state always holding the same text, a clean
document holding the saved text, the line ending counts, every selection set
sorted, disjoint, in bounds and outside any CR LF or surrogate pair, and a
block's lines and rows. A composition must leave the text, undo state and
selections as it found them. It ends by undoing every step and redoing
them. `-Dqcaret.document.seed=N` runs one seed.

`MemoryBudgetTest` covers the share and refusals.

`LineEditsTest`, `BlockEditsTest` and `MovesTest` cover each command at one
and several selections with the selections it leaves, padding into virtual
space, one undo step per block typing run, and a canceled search.
`LineTransformsTest` covers mixed breaks staying in place through a sort, the
final break staying last, duplicates per range, a caret in trimmed blanks and
one undo step each; `AutoCloseTest` the insert, type-over and pair-delete
rules per caret; `BracketsTest` nesting, unmatched brackets and the bounds;
`WordIndexTest` the word rules, prefix lookup and whole-word occurrences.
`PreeditTest` covers composition text outside undo and the dirty state, undo
removing it, an update replacing only the composition, a new composition after
a clear, a selection surviving one, `applyInBlock` and the tab settings.

`DocumentTest` covers change events, coalescing rules, groups, redo
discard, dirty state, the undo bounds, refused edits, anchors, the position
map bound, selection mapping and block rows. `DocumentFilesTest` round-trips
every BOM and mixed line endings byte for byte, and covers the fallback,
binary files, an unmappable save, a malformed forced decode, text that does
not encode back, a canceled load, a file larger than one 1 MiB read block
(regression: the read stopped after the first block), the load estimate, and
the digests of a load and a save. `DocumentSaveCancelTest` cancels in the third
encoder block of a 4 MiB save: a hard-linked file ends with the whole new text
in both links (regression: 196,608 bytes were left), and an atomic save and a
cancel before an in-place save leave the file as it was. `StreamingLoadTest` compares a load with a
whole-array decode of the same bytes: every menu charset, BOM form and two
ISO-2022 charsets with a block boundary at each offset of each sequence and CR
LF, at blocks of 1 to 9 bytes and at the real first boundary; a detection corpus
including a candidate that fails past the first block. It also covers a cancel
in mid-file, a reservation raised by text behind an ASCII sample and a raise the
budget refuses. `LoadHeapTest` loads at the budget's limit in a 128 MiB child
JVM: ASCII and wide text load, wide text behind an ASCII head is refused
instead of running out of memory, an edit and an added line at the limit reload as one undo
step, and a rewrite at the limit is refused. `ReloadTest` compares `reload` with a
comparison of two whole texts on 400 seeded edits at small blocks, repeated
text, and edits in a file past every block; it also covers a BOM, a file
written or made undecodable between the passes, a cancel, the charge, a change
inside the headroom with the budget full and a span the budget refuses.
`ProcessLockTest` covers a second acquire in the JVM and in a child JVM, close,
a killed holder, a read of the held file, a non-ASCII path, 16 racing threads
and a link that fails on a missing, changed or refusing API.
`DigestsTest` digests a file past the block size. `SafeSaveTest` covers permissions, a new
file, an external change, a cancel, a symbolic link, a hard link and the held
lock file.
`EncodingDetectorTest` covers every detection step, the menu charsets and an
unknown name.
`TextMatcherTest` covers case folding, whole word (including a regex ending
inside `\Q`, a `(?x)` comment and a `\Q` in such a comment, which the plain
wrapper failed with "Unclosed group"), the line-break anchors,
skipped empty matches, lookbehind before the range, cancellation, a stack
overflow on 400,000 chars as a typed failure, and replacement templates.
`TextSearchTest` covers line, column and snippet over CR, LF and CR LF on a
string and on a rope, a cut snippet, selection ranges, the limit, skipped
long lines with the lines around them searched, and a stack overflow that
names its line and keeps the hits before it (regression: a per-line retry
read past its line into the overflowing one). `FileSearchTest` walks a
generated tree: masks, excluded and hidden directories, the top level only,
open documents left out, binary, too-large and refused-memory skips, and the
stamp and SHA-256 of a found file.
`FileReplaceTest` covers a replacement line break taking a CR LF file's
ending, UTF-16 LE with its BOM, an unmappable replacement, a file changed
after the search, a read-only file (skipped where the user writes read-only
files), a dropped match, malformed and binary files, a 3 MiB file replaced at
both ends, a cancel leaving no backup, a restore refusing a file changed
since, a restore past every block, a damaged backup, and listing and pruning runs.
`FailedReplaceTest` runs a replace and a restore in a JVM under `ulimit -f`
where `bash` and the `unix` view exist: a failed in-place replace keeps a
backup that restores (regression: the backup was deleted with the file cut), a
failed atomic replace drops its backup, and a restore that fails partway can be
retried. Direct calls to `writeFailed` cover the same outcomes on every
platform. `LineDiffTest` compares 400 seeded random
edits with a dynamic-programming longest common subsequence: each script
rebuilds both lists, keeps a longest common subsequence and lists removed
lines first; hunks keep their context. It also covers the edit bound.
`WorkspaceWalkTest` lists and indexes a temp tree: the listing order, hidden
and excluded entries, a link to an ancestor listed as a cycle and not
indexed (skipped where links are refused), the index's charge, its file
limit, a refused reservation, a canceled build returning its charge, and an
unreadable root.

`FileCheckTest` covers an untouched file read by its stamp only, a touch with
the same bytes, other bytes, a truncate and regrow to the same size and time, a racy baseline settling, a file renamed over the name with
other and with the same bytes (the first skipped without a file key: NTFS
tunneling), a delete and the return of the same and of other
bytes, a file where none was, a directory at the path, a retargeted link and a 3 MiB file changed in its last byte. `FileMonitorTest`
covers the poll, requested checks, one registration per directory released
with its last file, a throwing listener reported once while checks go on, a
directory event naming its file (skipped on macOS, whose `WatchService` polls), and `close`.
`BuiltInGrammarsTest` tokenizes one sample per built-in grammar and checks
its tokens, HTML's embedded JavaScript and CSS, a CSV quoted line break, a
diff hunk's `---` line and here documents' opening lines and bodies included,
and that every built-in grammar except plain text names a category. `SyntaxEngineTest`
applies seeded random edits that open and close comments, strings and
braces and join CR LF pairs, between passes stopped at a deadline or after a
seeded line count, then checks every line's tokens and the fold regions
against a new engine, over Java and over Lua long brackets and Rust nested
comments and raw strings, and over Ruby and shell here documents. Two cases pin defects that random edits found: an
edit before a stopped frontier converging before it, and an edit between a
CR and an LF shifting the lines after it by one line too few. It also checks
that a converging edit retokenizes three lines, view lines before and after
the frontier, the line limit and marker folding. `TokenizerTest` covers states across lines, the
line limit, a runaway regex and a stack overflow left plain, a cancel, the
zero-width guard, refused backreferences, nested spans, ends and escapes
using `\1`, shared derived contexts, the derive and depth limits, and
`bodyNextLine` spans: the opening line's code, two markers on one line, and a
pop on the marker's line.
`GrammarReaderTest` covers every
element, a refused DOCTYPE, schema errors with their line, bad regexes and
missing contexts. `GrammarSetTest` covers the detection order, each
language's extensions, file names and shebangs, every format grammar's file
names and extensions, one grammar per extension, user grammars and a runaway
first-line regex. `CommentEditsTest` and `BoundedCharsTest`
cover the rest of the area.
`OperationGateTest`, `SafePathsTest` (including the Windows device forms on
every OS), `AtomicFilesTest`, `LogTextTest`, `UtilTest` and `TestkitTest`
cover the other classes.
