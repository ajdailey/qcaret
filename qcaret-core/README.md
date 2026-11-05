# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership,
paths, atomic and safe file writes, encodings and line endings, text storage,
the document model, editing commands, grammars and the tokenizer, the find
matcher and small utilities. The packages `diff`, `watch` and `workspace` are
empty, with their layer rules in place. Tests run without a display.

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
| `core.text` | 4 | `TextEncoding`, `EncodingDetector`, `LineEnding`, `LineEndingStats`, `LogText`, `BoundedChars` |
| `core.buffer` | 5 | `RopeStorage`, `TextSnapshot`, `TextCursor`, `LineView` |
| `core.document` | 6 | `Document`, `EditBatch`, `DocumentChange`, undo, selections, anchors, `DocumentFiles` |
| `core.edit`, `core.syntax`, `core.search`, `core.diff` | 7 | Editing commands (`LineEdits`, `LineTransforms`, `BlockEdits`, `CommentEdits`, `AutoClose`, `Brackets`, `Moves`, `Words`, `WordIndex`, `Occurrences`), grammars and highlighting, search, line diff; none uses another |
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
document refuses `apply`, `undo` and `redo`. `applyInBlock` applies a batch
and leaves the origin model in block mode with the given block.

`preedit(offset, removeLength, text)` replaces an input method's composition
text outside undo: the undo state, and so the dirty state, stay as they are,
and the change fires with cause `PREEDIT`, which recovery skips. Undo, redo
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
| `BlockSelection` | Lines and visual columns (`Columns`: tabs to the next stop, East Asian wide chars 2). `rows` gives one `BlockRow` per line with the spaces typing must insert to reach the left column on a short line; in block mode the model maps the block's lines and rebuilds its selections |

## Editing commands (`core.edit`)

Functions from a `TextContent` and a `SelectionSet` to an `Edit` (the batch and
the selections after it, or null to map them), so a controller applies each as
one `Document.apply`. A line a selection touches holds part of it; a range
ending at a line's start leaves that line out.

| Class | Commands |
|---|---|
| `LineEdits` | Duplicate (each range after itself, or each caret's lines below them), move lines up or down (touching blocks move together; nothing moves at the edge), delete lines (the last line takes the break before it), join (break and the next line's indentation become one space, or none after an empty line or whitespace), upper and lower case one code point at a time (the word at a caret), indent and outdent (empty lines skipped; outdent removes a tab or up to a tab width of spaces), the Tab key (indents when a range spans lines, else a tab or spaces to the next stop), split a range into one selection per line |
| `BlockEdits` | Typing, Backspace, Delete and paste in a `BlockSelection` (Notepad++ column mode): a row that ends left of the block is padded with spaces; the block stays a block, as wide as nothing after typing; paste gives each row one line |
| `Moves`, `Words` | Caret steps by char (a CR LF and a surrogate pair are one), smart Home, End, and word moves that stop at runs of word chars, of other non-blank chars and at line breaks |
| `CommentEdits` | Toggle line comments on the touched lines (removed when every non-blank line has one after its indentation, else added at the block's smallest indentation; a block comment per block of lines without a line comment) and block comments around each selection or a caret's trimmed line; a range keeps text inserted at its ends |
| `Occurrences` | The next exact occurrence of a text at or after an offset, wrapping, and the whole-word occurrences of a word in a range; cancelable, for a worker |
| `LineTransforms` | Sort (code unit, descending, ignoring case), remove duplicate lines (keeps the first) and trim trailing spaces and tabs, over each range's touched lines or, when every selection is a caret, every line but an empty last one. A sort leaves each break in its place, so offsets and selections stay; a removed last line takes the break before it. Cancelable, for a worker |
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

## Grammars and highlighting (`core.syntax`)

A grammar is an XML file in QCaret's own format, validated against
`grammar.xsd` (beside the classes) while the JDK parser reads it, with DOCTYPE
declarations refused and external DTD and schema access off
(`GrammarReader`). Each regex compiles at read time and each context a rule
names must exist; a failure is an `InvalidGrammarException` naming the file
and, for a schema error, the line. `GrammarSet.BUILT_IN` lists the
built-in grammars (`grammars/<id>.xml`), written from the language
specifications each file names.

Languages: plain text, Java, XML, HTML, JSON, YAML, SQL, Python, JavaScript,
TypeScript, C, C++, shell, PowerShell, batch, properties, INI, CSS, Markdown,
Go, Rust, C#, Kotlin, Ruby, PHP, Perl, Lua, Groovy (with `Jenkinsfile` and
`*.gradle`), Scala, Swift, Dart, R, Haskell, Objective-C and VB.NET. Each
extension belongs to one built-in grammar (`GrammarSetTest`): `.h` is C, `.m`
and `.mm` are Objective-C, `.kts` (so `build.gradle.kts`) is Kotlin. PHP text
outside `<?php ... ?>` is HTML.

| Element | Content |
|---|---|
| `grammar` | `id` (a user grammar with the same id replaces the built-in), `name`, `version` |
| `detect` | `extension`s, `fileName`s, one `firstLine` regex |
| `comments` | `line`, `blockStart`, `blockEnd` |
| `brackets`, `autoClose` | `pair open close` |
| `indent` | `increase`, `decrease` and `dedentNext` regexes |
| `folding` | `mode`: `none`, `brackets` (`open` and `close` chars), `markers` (`begin` and `end` regexes) or `indent` |
| `context` | Rules in order: `match` (regex, token, optional `push` and `pop`), `keywords` (words, `ignoreCase`, `wordChars`), `span` (`begin`, optional `end`, `escape`, inner `context`, `multiLine`, `nested`), `include` (a context's rules in place), `embed` (another grammar between `begin` and `end`) |

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
and opens nothing.

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
from a snapshot, and HyperSearch (S5) reuses them.

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

`DocumentRandomTest` runs 1,500 random steps per seed (batches, typing runs,
groups, undo, redo, selection moves, saves, new anchors) and checks after each:
the text against a replay of the change events, revisions one apart, each
event's removed text and start line, anchors and `PositionMap` against a
step-by-step mapping, an undo state always holding the same text, a clean
document holding the saved text, the line ending counts, and every selection
set sorted, disjoint and in bounds. It ends by undoing every step and redoing
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
removing it, `applyInBlock` and the tab settings.

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
`TextMatcherTest` covers case folding, whole word (including a regex ending
inside `\Q`, a `(?x)` comment and a `\Q` in such a comment, which the plain
wrapper failed with "Unclosed group"), the line-break anchors,
skipped empty matches, lookbehind before the range, cancellation, a stack
overflow on 400,000 chars as a typed failure, and replacement templates.
`BuiltInGrammarsTest` tokenizes one sample per built-in grammar and checks
its tokens, HTML's embedded JavaScript and CSS included. `SyntaxEngineTest`
applies seeded random edits that open and close comments, strings and
braces and join CR LF pairs, between passes stopped at a deadline or after a
seeded line count, then checks every line's tokens and the fold regions
against a new engine, over Java and over Lua long brackets and Rust nested
comments and raw strings. Two cases pin defects that random edits found: an
edit before a stopped frontier converging before it, and an edit between a
CR and an LF shifting the lines after it by one line too few. It also checks
that a converging edit retokenizes three lines, view lines before and after
the frontier, the line limit and marker folding. `TokenizerTest` covers states across lines, the
line limit, a runaway regex and a stack overflow left plain, a cancel, the
zero-width guard, refused backreferences, nested spans, ends and escapes
using `\1`, shared derived contexts and the derive and depth limits.
`GrammarReaderTest` covers every
element, a refused DOCTYPE, schema errors with their line, bad regexes and
missing contexts. `GrammarSetTest` covers the detection order, each
language's extensions, file names and shebangs, one grammar per extension, user grammars and a runaway
first-line regex. `CommentEditsTest` and `BoundedCharsTest`
cover the rest of the area.
`OperationGateTest`, `SafePathsTest` (including the Windows device forms on
every OS), `AtomicFilesTest`, `LogTextTest`, `UtilTest` and `TestkitTest`
cover the other classes.
