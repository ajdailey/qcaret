# qcaret-core testing

## Harness

Tests run without a display. The test jar carries `testkit` for the other
modules' tests and `qcaret-bench`:

| Class | Contract |
|---|---|
| `Poll.until` | A bounded poll for state another thread produces |
| `TempTrees.delete` | Deletes a tree and retries until a bound, because Windows keeps a file a terminated child process had open delete-pending for a moment |

Seeded random tests name each case by its seed and take a system property that
runs one seed. Tests that need a symbolic link skip where links are refused,
and tests of a read-only file skip where the user writes read-only files.

## Coverage

### Threads and memory

| Class | Covers |
|---|---|
| `WorkersTest` | Thread names, failure routing, rejection after close, `tryStart`, no task running after `close` returns. A pool can report terminated just before its last thread ends, and the live set lags a removal, so the lifecycle checks poll |
| `MemoryBudgetTest` | The share and refusals |

### Text storage and documents

| Class | Covers |
|---|---|
| `TextStorageTest` | 2,000 seeded random inserts, deletes and replacements on the rope (400 on documents above 128 Ki chars, with a CR LF across a leaf boundary), on text with random, CR LF only and mixed line endings, compared with a `StringBuilder`: content, every line and line view, every offset's line, forward and backward cursor walks, chunk reads, earlier snapshots. A case is named by its endings and seed; `-Dqcaret.storage.seed=N` runs one seed |
| `DocumentRandomTest` | 1,500 random steps per seed over text with surrogate pairs (batches, typing runs, groups, undo, redo, selection moves, saves, new anchors, block selections, compositions), checking after each: the text against a replay of the change events, revisions one apart, each event's removed text and start line, anchors and `PositionMap` against a step-by-step mapping, an undo state always holding the same text, a clean document holding the saved text, the line ending counts, every selection set sorted, disjoint, in bounds and outside any CR LF or surrogate pair, and a block's lines and rows. A composition must leave the text, undo state and selections as it found them. It ends by undoing every step and redoing them. `-Dqcaret.document.seed=N` runs one seed |
| `DocumentTest` | Change events, coalescing rules, groups, redo discard, dirty state, the undo bounds, refused edits, anchors, the position map bound, selection mapping, block rows |
| `PreeditTest` | Composition text outside undo and the dirty state, undo removing it, an update replacing only the composition, a new composition after a clear, a selection surviving one, `applyInBlock`, the tab settings |

### Loading and saving

| Class | Covers |
|---|---|
| `DocumentFilesTest` | Every BOM and mixed line endings round-tripped byte for byte; the fallback, binary files, an unmappable save, a malformed forced decode, text that does not encode back, a canceled load, a file larger than one 1 MiB read block (regression: the read stopped after the first block), the load estimate, the digests of a load and a save |
| `DocumentSaveCancelTest` | A cancel in the third encoder block of a 4 MiB save: a hard-linked file ends with the whole new text in both links (regression: a partial file was left), and an atomic save and a cancel before an in-place save leave the file as it was |
| `StreamingLoadTest` | A load against a whole-array decode of the same bytes: every menu charset, BOM form and two ISO-2022 charsets with a block boundary at each offset of each sequence and CR LF, at blocks of 1 to 9 bytes and at the real first boundary; a detection corpus including a candidate that fails past the first block; a cancel in mid-file, a reservation raised by text behind an ASCII sample, a raise the budget refuses |
| `LoadHeapTest` | Loads at the budget's limit in a 128 MiB child JVM: ASCII and wide text load; wide text behind an ASCII head is refused instead of running out of memory; an edit and an added line at the limit reload as one undo step; a rewrite at the limit is refused |
| `ReloadTest` | `reload` against a comparison of two whole texts on 400 seeded edits at small blocks, repeated text, and edits in a file past every block; a BOM, a file written or made undecodable between the passes, a cancel, the charge, a change inside the headroom with the budget full, a span the budget refuses |
| `EncodingDetectorTest` | Every detection step, the menu charsets, an unknown name |

### Files

| Class | Covers |
|---|---|
| `ProcessLockTest` | A second acquire in the JVM and in a child JVM, close, a killed holder, a read of the held file, a non-ASCII path, 16 racing threads, a link that fails on a missing, changed or refusing API |
| `SafeSaveTest` | Permissions, a new file, an external change, a cancel, a symbolic link, a hard link, the held lock file |
| `DigestsTest` | A file past the block size |
| `SafePathsTest` | The path rules, including the Windows device forms on every OS |
| `AtomicFilesTest`, `LogTextTest`, `UtilTest`, `TestkitTest` | The other classes |

### Editing commands

| Class | Covers |
|---|---|
| `LineEditsTest`, `BlockEditsTest`, `MovesTest` | Each command at one and several selections with the selections it leaves, padding into virtual space, one undo step per block typing run, a canceled search |
| `LineTransformsTest` | Mixed breaks staying in place through a sort, the final break staying last, duplicates per range, a caret in trimmed blanks, one undo step each |
| `AutoCloseTest` | The insert, type-over and pair-delete rules per caret |
| `BracketsTest` | Nesting, unmatched brackets, the bounds |
| `WordIndexTest` | The word rules, prefix lookup, whole-word occurrences |
| `CommentEditsTest` | Line and block comment toggles |

### Grammars

| Class | Covers |
|---|---|
| `BuiltInGrammarsTest` | One sample per built-in grammar with its tokens, HTML's embedded JavaScript and CSS, a CSV quoted line break, a diff hunk's `---` line, here documents' opening lines and bodies; every built-in grammar except plain text names a category |
| `SyntaxEngineTest` | Seeded random edits that open and close comments, strings and braces and join CR LF pairs, between passes stopped at a deadline or after a seeded line count, then every line's tokens and the fold regions against a new engine, over Java, Lua long brackets, Rust nested comments and raw strings, and Ruby and shell here documents. Two cases pin defects random edits found: an edit before a stopped frontier converging before it, and an edit between a CR and an LF shifting the lines after it by one line too few. Also a converging edit retokenizing three lines, view lines before and after the frontier, the line limit, marker folding |
| `TokenizerTest` | States across lines, the line limit, a runaway regex and a stack overflow left plain, a cancel, the zero-width guard, refused backreferences, nested spans, ends and escapes using `\1`, shared derived contexts, the derive and depth limits, `bodyNextLine` spans (the opening line's code, two markers on one line, a pop on the marker's line) |
| `GrammarReaderTest` | Every element, a refused DOCTYPE, schema errors with their line, bad regexes, missing contexts |
| `GrammarSetTest` | The detection order, each language's extensions, file names and shebangs, every format grammar's file names and extensions, one grammar per extension, user grammars, a runaway first-line regex |
| `BoundedCharsTest` | The read bound and cancellation |

### Search and replace

| Class | Covers |
|---|---|
| `TextMatcherTest` | Case folding; whole word, including a regex ending inside `\Q`, a `(?x)` comment and a `\Q` in such a comment (regression: the plain wrapper failed with "Unclosed group"); the line-break anchors; skipped empty matches; lookbehind before the range; cancellation; a stack overflow on 400,000 chars as a typed failure; replacement templates |
| `TextSearchTest` | Line, column and snippet over CR, LF and CR LF on a string and on a rope; a cut snippet; selection ranges; the limit; skipped long lines with the lines around them searched; a stack overflow that names its line and keeps the hits before it (regression: a per-line retry read past its line into the overflowing one) |
| `FileSearchTest` | A generated tree: masks, excluded and hidden directories, the top level only, open documents left out, binary, too-large and refused-memory skips, the stamp and SHA-256 of a found file |
| `SearchWorkTest` | Bounds that do not depend on the runner: files accepted after a cancel or after the room ends, chars read after a cancel in each scan and in the matcher, the memory reads hold |
| `FileReadCancelTest` | A cancel at the third block of each read stage, counted through the package-private `FileSearch.setProbe`, with at most one block after it, in the walk and in `searchFile`, and the reservation released; the block-wise read of files over 20 blocks, with sequences split across a block end, against a whole-file read, detect, decode and digest for each encoding and BOM case |
| `FileReplaceTest` | A replacement line break taking a CR LF file's ending, UTF-16 LE with its BOM, an unmappable replacement, a file changed after the search, a read-only file, a dropped match, malformed and binary files, a 3 MiB file replaced at both ends, a cancel leaving no backup, a restore refusing a file changed since, a restore past every block, a damaged backup, listing and pruning runs |
| `FailedReplaceTest` | A replace and a restore in a JVM under `ulimit -f`, where `bash` and the `unix` view exist: a failed in-place replace keeps a backup that restores (regression: the backup was deleted with the file cut), a failed atomic replace drops its backup, a restore that fails partway can be retried. Direct calls to `writeFailed` cover the same outcomes on every platform |
| `LineDiffTest` | 400 seeded random edits against a dynamic-programming longest common subsequence: each script rebuilds both lists, keeps a longest common subsequence and lists removed lines first; hunks keep their context; the edit bound |
| `WorkspaceWalkTest` | A temp tree listed and indexed: the listing order, hidden and excluded entries, a link to an ancestor listed as a cycle and not indexed, the index's charge, its file limit, a refused reservation, a canceled build returning its charge, an unreadable root |

### External change detection

| Class | Covers |
|---|---|
| `FileCheckTest` | An untouched file read by its stamp only, a touch with the same bytes, other bytes, a truncate and regrow to the same size and time, a racy baseline settling, a file renamed over the name with other and with the same bytes (the first skipped without a file key: NTFS tunneling), a delete and the return of the same and of other bytes, a file where none was, a directory at the path, a retargeted link, a 3 MiB file changed in its last byte |
| `FileMonitorTest` | The poll, requested checks, one registration per directory released with its last file, a throwing listener reported once while checks go on, a directory event naming its file (skipped on macOS, whose `WatchService` polls), `close` |
