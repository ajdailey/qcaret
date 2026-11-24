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
| `WorkersTest` | Thread names, failure routing, rejection after close, `tryStart`; `noTaskRunsAfterCloseReturns` races `tryStart` against `close` 50 times; `rejectedStartIsNotCounted` reads `liveThreads()` after a true `close` while another thread keeps calling `tryStart`, 200 times (regression: a rejected `start` sat in the live set between two `closed` checks and was counted). `liveThreads()` is asserted 0 directly after a true `close`, and polled after a `close` with a short bound, whose interrupted threads end afterwards |
| `WorkerKindsTest` | A `CpuSteps` step from a virtual task runs on `qcaret-bulk-1`, inline elsewhere; `availableProcessors()` `IO` tasks with a spinning step leave a task of every kind runnable (inline on the virtual thread, a `LIFECYCLE` task waited over 5 s behind 8 spinners on 8 cores); a cancel or `close` while the step is queued ends the wait and the step never runs; a step's `IOException` reaches the caller |
| `BulkStepCancelTest` | With the only bulk thread held, a cancel while the first step waits ends a load, a save (target unchanged), a `searchFile`, `Checksums.ofFile` and `Digests.of` with a `CancellationException` |
| `MemoryBudgetTest` | The share and refusals |

### Text storage and documents

| Class | Covers |
|---|---|
| `TextStorageTest` | 2,000 seeded random inserts, deletes and replacements on the rope (400 on documents above 128 Ki chars, with a CR LF across a leaf boundary), on text with random, CR LF only and mixed line endings, compared with a `StringBuilder`: content, every line and line view, every offset's line, forward and backward cursor walks, chunk reads, earlier snapshots. A case is named by its endings and seed; `-Dqcaret.storage.seed=N` runs one seed |
| `DocumentRandomTest` | 1,500 random steps per seed over text with surrogate pairs (batches, typing runs, groups, undo, redo, selection moves, saves, new anchors, block selections, compositions), checking after each: the text against a replay of the change events, revisions one apart, each event's removed text and start line, anchors and `PositionMap` against a step-by-step mapping, an undo state always holding the same text, a clean document holding the saved text, the line ending counts, every selection set sorted, disjoint, in bounds and outside any CR LF or surrogate pair, and a block's lines and rows. A composition must leave the text, undo state and selections as it found them. It ends by undoing every step and redoing them. `-Dqcaret.document.seed=N` runs one seed |
| `DocumentTest` | Change events, coalescing rules, groups, redo discard, dirty state, the undo bounds, refused edits, anchors, the position map bound, selection mapping, block rows, `set` moving an offset inside a CR LF or a surrogate pair, an `apart` set keeping touching ranges through a change |
| `PreeditTest` | Composition text outside undo and the dirty state, undo removing it, an update replacing only the composition, a new composition after a clear, a selection surviving one, `applyInBlock`, the tab settings |

### Loading and saving

| Class | Covers |
|---|---|
| `DocumentFilesTest` | Every BOM and mixed line endings round-tripped byte for byte; the fallback, binary files, an unmappable save, a malformed forced decode, text that does not encode back, a canceled load, a file larger than one 1 MiB read block (regression: the read stopped after the first block), the load estimate, the digests of a load and a save |
| `DocumentSaveCancelTest` | A cancel in the third encoder block of a 4 MiB save: a hard-linked file ends with the whole new text in both links (regression: a partial file was left), and an atomic save and a cancel before an in-place save leave the file as it was |
| `StreamingLoadTest` | A load against a whole-array decode of the same bytes: every menu charset, BOM form and two ISO-2022 charsets with a block boundary at each offset of each sequence and CR LF, at blocks of 1 to 9 bytes and at the real first boundary; a detection corpus including a candidate that fails past the first block; a cancel in mid-file, a reservation raised by text behind an ASCII sample, a raise the budget refuses |
| `LoadHeapTest` | Loads at the budget's limit in a 128 MiB child JVM: ASCII and wide text load; wide text behind an ASCII head is refused instead of running out of memory; an edit and an added line at the limit reload as one undo step; a rewrite at the limit is refused |
| `ReloadTest` | `reload` against a comparison of two whole texts on 400 seeded edits at small blocks, repeated text, and edits in a file past every block; a BOM, a file written or made undecodable between the passes, a cancel, the charge, a change inside the headroom with the budget full, a span the budget refuses |
| `EncodingDetectorTest` | Every detection step, the menu charsets, an unknown name; a configured charset's place, a BOM over it, a modeline charset read only with the tail |
| `EncodersTest` | A decode-only charset not writable; the encoder reporting an unmappable char |
| `DeclaredCharsetLoadTest` | A modeline charset at the end of a file past three read blocks, a configured charset and a BOM over it through `load`, an empty file's `emptyCharset` under a configured and a forced charset, `TextModelines` on a 100,000-line text |
| `EditorConfigSuiteTest` | The 173 glob, parser and property cases of editorconfig-core-test, from `cases.tsv` beside its fixtures and BSD-2-Clause license |
| `EditorConfigTest`, `ModelinesTest`, `FileSettingsTest` | The directory walk (nearer and later wins, `unset`, `root`, an anchored section), malformed, oversized, non-UTF-8 and unreadable files, a directory named `.editorconfig`, a linked one; each modeline form, the first and last 5 lines, Emacs blocks; the width table and a modeline over EditorConfig |

### Files

| Class | Covers |
|---|---|
| `ProcessLockTest` | A second acquire in the JVM and in a child JVM, close, a killed holder, a read of the held file, a non-ASCII path, 16 racing threads, a link that fails on a missing, changed or refusing API |
| `SafeSaveTest` | Permissions, a new file, an external change, a cancel, a symbolic link, a hard link, the held lock file; on Windows only, a user-only ACL kept and the content written to a staged file that already has it, and an inherited ACL left inherited with no staging directory |
| `DigestsTest` | A file past the block size |
| `ChecksumsTest` | Known vectors for all four algorithms (empty, `abc`), the million-`a` SHA-256 through file and text buffers, a generated file past three buffers, a cancel mid-file, a surrogate pair split across pieces in UTF-16LE, a char the charset cannot encode |
| `ChecksumListTest` | Text and `*` lines, the algorithm from the length, bad and overlong lines, escaped names both ways, one-char pieces, each `ChecksumCheck` outcome |
| `ChecksumNamesTest` | `ChecksumCheck.checkName` under Windows and Linux traits on every OS: UNC names off the list's share and device names refused, local, relative and same-share names kept; an absolute local name verified; on Windows a UNC and device names unreadable with the reason; on POSIX a FIFO and `/dev/zero` unreadable within a 30 s bound, which a missing regular-file check fails instead of hanging |
| `SafePathsTest` | The path rules; device names refused on Windows only; each refusal of a path's text a `PathFormException` with its reason; a name ending in a dot or space refused on Windows only |
| `OsTraitsTest` | The system each `os.name` picks, every answer of each system, each system's file confinement, per-user settings, the launcher directory and font folders per system, Windows variable names matched ignoring case, the description, `EnvPaths.absolute`; `runsInsteadOfOpening` by name per system, and by the execute bit on macOS and Linux (POSIX hosts) |
| `WindowsDeviceNamesTest` | The Windows device forms, the UNC share prefix with either separator, and share names compared folding ASCII case only, on every OS |
| `RunnableTypesTest` | Every `WINDOWS` and `MAC` extension in either case, trailing dots and spaces on Windows, a `PATHEXT` entry added and malformed entries ignored |
| `AtomicFilesTest` | Write, replace and the move retry; on Windows only, a secret from either `writeOwnerOnly` with one ACL entry for the user, the user as owner in the elevated CI account, and no `.qcaret-` directory left, and a file created in `privateDirectory` with only the user's entry from creation |
| `LogTextTest`, `UtilTest`, `TestkitTest` | The other classes |

### Editing commands

| Class | Covers |
|---|---|
| `LineEditsTest`, `BlockEditsTest`, `MovesTest` | Each command at one and several selections with the selections it leaves, padding into virtual space, one undo step per block typing run, a canceled search, Delete Line and Move Lines refusing a change that joins a CR and an LF and counting the lines left; cluster moves over combining marks, an emoji with a skin tone and a CR LF |
| `LineTransformsTest` | Mixed breaks staying in place through a sort, the final break staying last, duplicates per range, a caret in trimmed blanks, one undo step each; a sort, duplicate removal and trim that would join a CR and an LF leaving that part and counting its lines, and none counted when nothing joins |
| `LineCleanupTest` | Remove Blank Lines (spaces and tabs count as blank, a blank last line, a text of blanks), Reverse, the numeric key and its stable sort, Trim Leading Whitespace and both indentation conversions with the caret each leaves; CR LF texts and surrogate pairs; a blank removal and a leading trim that would join a CR and an LF counted as unchanged; 20,000 lines, past the cancellation interval, through each; one undo step each; a canceled token, and a numeric sort canceled mid-run |
| `TextCodecTest` | Each Encode/Decode rule: Base64 and URL forms of UTF-8 with surrogate pairs and CR LF, blanks and missing padding in Base64, `+` kept and either hex case in URL decode, the five XML entities, HTML 4.01 names (253 with `apos`) and character references, JSON escapes; every refusal reason; what XML unescape leaves; a round trip of each pair over three cancellation intervals; a token canceled mid-run stopping each conversion |
| `CodecEditsTest` | Ranges converted and kept selected with carets mapped, a reversed range, one refused range leaving every range and naming its index, carets alone and unchanged ranges giving null, one undo step, a long range, a canceled token |
| `AutoCloseTest` | The insert, type-over and pair-delete rules per caret |
| `BracketsTest` | Nesting, unmatched brackets, the bounds |
| `WordIndexTest` | The word rules, prefix lookup, whole-word occurrences |
| `CommentEditsTest` | Line and block comment toggles |
| `CaseEditsTest` | Title case per word of a range and the word at each caret, combining marks and surrogate pairs kept in their word, swap case, one step for every selection, null when nothing changes |
| `WordDeletesTest` | Back and forward spans at each caret, a line break alone and a CR LF as one, a selection deleted whole, overlapping spans deleted once, clusters kept whole, one step, null when nothing to delete |
| `TransposeTest` | A caret swapping its neighbors, no swap across a line break, clusters moved whole, adjacent carets swapping once, two or more selections rotating, one selection alone changing nothing, a rotation that would join a CR and an LF refused |
| `SelectionExpansionTest` | Expansion from word to quotes, brackets, line and text, each selection on its own, escaped quotes and filtered brackets not enclosing, the scan bound returning null, Select Line, `Occurrences.all` without overlap up to the maximum, no occurrence starting or ending inside a CR LF |
| `ParagraphWrapTest` | Filling to the column, an overlong word on its own line, the first line's indent and comment prefix, comment and code lines as separate paragraphs, blank lines ending a paragraph, selections, unchanged text, one undo step, a cancel |
| `XmlFormatTest`, `JsonFormatTest` | Through `FormatFixture`, every formatted fixture keeps its token sequence and equals its input with whitespace removed. Layout per rule (mixed content, `xml:space`, prolog and DOCTYPE, comments, empty containers, edges, CR LF and CR breaks, a selection's base indent); selection endpoints after the edit; a failing selection failing the run; parser line and column mapped to an offset, a surrogate pair counting two columns; external DTD, entity and parameter entity files that would break the text if read, and a `192.0.2.1` DTD, never read; an expansion bomb refused; JSON numbers and escapes kept, each extension an error at its offset, 100,000-level nesting, comments lenient and strict, a `//` comment at the end without a break, comment marks inside a string; a generated input over 4 MiB formatting and minifying back; the output limit; a cancel before and during a run |
| `XmlFragmentTest` | An XML selection of several top-level items: siblings one per line at the base indent and minified back, a leading comment as an item, text between items left verbatim, Check reporting a fragment and a single element staying a document, an error in the second element mapped to the text on line 1 and later lines, an unclosed element failing at the selection end, an XML declaration or DOCTYPE refused at its position, the whole text still one document |
| `KeptContentTest` | `FormatResult.kept` after an XML run with no edit: the first kept element and its line for text, CDATA and `xml:space="preserve"`, a no-break space or zero-width space as the only text, text and CDATA only content not reported, the first selection with a reason, a fragment's top level, none with an edit, for Check or for JSON |
| `CaretPathTest` | XML paths in tags, attributes, text, comments, CDATA, PIs and end tags, indexes only among same-named siblings including later ones, prefixes, a fragment's top level; JSON paths on names, values, brackets, commas and gaps, quoted names with escapes, a scalar root; malformed text before the caret an error and after it not, an unterminated tag at the caret an error; strict JSON refusing a comment before the caret; a cancel |
| `NumberSequenceTest` | Steps up, down and zero; zero padding counting the sign; hex in both cases as sign and magnitude, `Long.MIN_VALUE`; overflow either way refused; field parsing of decimal and `0x` hex at both `long` limits, and refusals |
| `PathAtCaretTest` | Runs ending at stop chars and a trailing period or colon; quoted paths with spaces; `:line` and `:line:column`; Windows drives, `C:12` and UNC paths; URLs marked |
| `SecureXmlTest` | Per `SecureXml` method, external DTD, general entity and parameter entity files that would break the text if read, never read (the DOM parser refusing their DOCTYPE, the schema factory refusing the access); an entity-expansion bomb refused by each |

### Grammars

| Class | Covers |
|---|---|
| `BuiltInGrammarsTest` | One sample per built-in grammar with its tokens, HTML's embedded JavaScript and CSS, a CSV quoted line break, a diff hunk's `---` line, here documents' opening lines and bodies; every built-in grammar except plain text names a category |
| `SyntaxEngineTest` | Seeded random edits that open and close comments, strings and braces and join CR LF pairs, between passes stopped at a deadline or after a seeded line count, then every line's tokens and the fold regions against a new engine, over Java, Lua long brackets, Rust nested comments and raw strings, Ruby and shell here documents, and Markdown sections and Java region markers. Two cases pin defects random edits found: an edit before a stopped frontier converging before it, and an edit between a CR and an LF shifting the lines after it by one line too few. Also a converging edit retokenizing three lines, view lines before and after the frontier, the line limit, marker folding, brackets nested past 64 levels with an unmatched close, and sections with an underline on the first line and an over-long line |
| `FoldRegionsTest` | Each grammar given sections or region markers on a sample with its regions: Java Javadoc, text block and nested regions beside brackets; unpaired markers and markers in a comment or string; Markdown levels, setext underlines and headers in a fence or comment; a section at the end of the text; TOML, INI, diff, Dockerfile, C#, VB.NET, C, C++, JavaScript, TypeScript, Kotlin; a block comment edited in and out; `fold="false"` spans; `FoldRegions.merge` |
| `TokenizerTest` | States across lines, the line limit, a runaway regex and a stack overflow left plain, a cancel, the zero-width guard, refused backreferences, nested spans, ends and escapes using `\1`, shared derived contexts, the derive and depth limits, `bodyNextLine` spans (the opening line's code, two markers on one line, a pop on the marker's line) |
| `GrammarReaderTest` | Every element, a refused DOCTYPE, schema errors with their line, bad regexes, missing contexts, symbol rules' default and named groups, a missing name group, an unknown kind, a misplaced `symbols` element, sections, region markers and span `fold`, `sections` mode without a section |
| `SymbolScannerTest` | A definition in a comment or a string ignored, the first rule winning a column, CR LF and CR breaks, the count and line limits on a generated file, a line over the block size, a cancel |
| `SymbolRescanTest` | `rescan` equal to `scanAll` after each of 150 steps of random edits to a 4,000-line text, with and without the count reached: comments and strings opened across lines, breaks split and joined, long lines; a truncated scan returned unchanged for an edit past its stop; the charge |
| `SymbolRulesTest` | One sample per built-in grammar with symbol rules, with its definitions and none from a comment or a string; which grammars have rules |
| `GrammarSetTest` | The detection order, each language's extensions, file names and shebangs, every format grammar's file names and extensions, one grammar per extension, user grammars, a runaway first-line regex, `forLabel` by id, name, extension and file name |
| `BoundedCharsTest` | The read bound and cancellation |

### Markdown

| Class | Covers |
|---|---|
| `MarkdownRendererTest` | Headings, paragraphs and their separator lines; each inline style's range; `<br>`, `<kbd>`, `<sub>`, `<sup>` and other inline HTML as text; HTML blocks and `<details>`; hard breaks and their source lines; nested and loose lists, empty items, numbers and task states; nested quotes and a quote in a list; rules; GFM tables with alignment, inline styles and a short row; code lines and the info string's language; a supplementary char before a run; CR LF and CR input equal to LF; the leaf block list; images by target; a cancel before the parse and one between blocks (a block whose walk cancels, then the next block's check); 3,000 generated sections over 256 KiB |
| `GitLabMarkdownTest` | Heading ids against the examples of GitLab's Markdown documentation, a `-` run joined; the heading list (ATX, setext, in a quote, none from a fence); `[[_TOC_]]` and `[TOC]` linking the same anchors; `>>>` quotes; autolinks; footnote numbering, order and the unused definition; alerts with the default and a custom title; front matter; image width and height |
| `SourceMapTest` | Rendered to source and back at a heading, a wrapped paragraph, a link reference definition, a fence and a list |
| `LinkTargetTest` | Relative, parent, absolute and `file:` paths, an untitled document, URLs and `//`, anchors, a local file's fragment, percent decoding with malformed escapes |
| `HeadingPathTest` | `name#id` split at the name's last `#` and decoded once; an existing `name#id` file kept whole; a missing file before the `#` keeping the whole name; `headingSourceLine` with repeats, setext, a fence, punctuation, non-ASCII and a quote, against every rendered heading's anchor and source line |

### Search and replace

| Class | Covers |
|---|---|
| `TextMatcherTest` | Case folding; whole word, including a regex ending inside `\Q`, a `(?x)` comment and a `\Q` in such a comment (regression: the plain wrapper failed with "Unclosed group"); the line-break anchors; skipped empty matches; lookbehind before the range; cancellation; a stack overflow on 400,000 chars as a typed failure; replacement templates |
| `TextSearchTest` | Line, column and snippet over CR, LF and CR LF on a string and on a rope; a cut snippet; selection ranges; the limit; skipped long lines with the lines around them searched; the skipped-line count with ranges ending before, inside and after a long line, several ranges in one line, and ends on each side of a `SCAN_CHARS` chunk, on a string and a rope (regression: lines the long-line pass read past a range's end were counted); a stack overflow that names its line and keeps the hits before it (regression: a per-line retry read past its line into the overflowing one) |
| `FileSearchTest` | A generated tree: masks, excluded and hidden directories, the top level only, open documents left out, binary, too-large and refused-memory skips, the stamp and SHA-256 of a found file |
| `SearchWorkTest` | Bounds that do not depend on the runner: files accepted after a cancel or after the room ends, chars read after a cancel in each scan, in the matcher and in its literal search, the memory reads hold |
| `PrefilterTest` | `requiredPrefix` on alternation, a quantifier on the last char, escapes, `\Q`, classes, inline flags, anchors and a surrogate pair; which queries get a literal. Every matcher call and `TextSearch.search` with and without the literal on 40 seeded texts, as a string and a rope, over 20 queries that include each exclusion, and `searchFile` on a file past every block with a literal across each 4,096-char window. The offsets the regex runs from, through the package-private `reportingStarts`, against the literal's occurrences. A canceled token in the literal search of a string. `-Dqcaret.prefilter.seed=N` runs one seed |
| `FileReadCancelTest` | A cancel at the third block of each read stage and the digest, counted through the package-private `FileSearch.setProbe`, with at most one block after it, in the walk and in `searchFile`, and the reservation released; the block-wise read of files over 20 blocks, with sequences split across a block end, against a whole-file read, detect, decode and digest for each encoding and BOM case |
| `SearchStepsTest` | One `IO` task per core whose decode spins through `FileSearch.setProbe` leaves a task of every kind runnable; with the decode run on the `IO` thread, six kinds wait |
| `SearchDigestTest` | Digest blocks per file through `FileSearch.setProbe`: in the walk and in `searchFile`, a file without hits is not digested, and a file with a hit or a skipped line over 20 blocks gets the whole-file SHA-256 |
| `LineScanTest` | `TextSearch.search` on a `String` and on a `Prefix` of one against the char-by-char path of other text: 40 seeded texts with CR, LF, CR LF and mixed breaks, some past `SCAN_CHARS`, and each break form at each side of a 4,096-char window end, over six queries, five `maxLineChars` values, ranges and a limit. Without a limit, `skippedLines` equals a count of long lines in the ranges taken line by line in the test. A cancel at the third window of each scan, counted through the package-private `TextSearch.setProbe`, with at most one window after it. `-Dqcaret.linescan.seed=N` runs one seed |
| `FileReplaceTest` | A replacement line break taking a CR LF file's ending, UTF-16 LE with its BOM, an unmappable replacement, a file changed after the search, a read-only file, a dropped match, malformed and binary files, a 3 MiB file replaced at both ends, a cancel leaving no backup, a restore refusing a file changed since, a restore past every block, a damaged backup, listing and pruning runs; a decode-only charset encoding nothing |
| `FailedReplaceTest` | A replace and a restore in a JVM under `ulimit -f`, where `bash` and the `unix` view exist: a failed in-place replace keeps a backup that restores (regression: the backup was deleted with the file cut), a failed atomic replace drops its backup, a restore that fails partway can be retried. Direct calls to `writeFailed` cover the same outcomes on every platform |
| `LineDiffTest` | 400 seeded random edits against a dynamic-programming longest common subsequence: each script rebuilds both lists, keeps a longest common subsequence and lists removed lines first; hunks keep their context; the edit bound |
| `WorkspaceWalkTest` | A temp tree listed and indexed: the listing order, hidden and excluded entries, a link to an ancestor listed as a cycle and not indexed, the index's charge, its file limit, a refused reservation, a canceled build returning its charge, an unreadable root |
| `ListingOptionsTest` | `NameFilter` globs and case, case ignored on Windows and macOS, `HomeFolder`, the filter leaving directories, hidden entries only when asked, size and time on each entry and the sort by either |
| `FileOperationsTest` | Create and rename refusing a taken name, a case-only rename, a name taken in other case where case matters, a counted tree delete, a link to an outside directory deleted without its target, a failing delete reporting what remains, a missing entry, `FileIndex.withChange` |

### External change detection

| Class | Covers |
|---|---|
| `FileCheckTest` | An untouched file read by its stamp only, a touch with the same bytes, other bytes, a truncate and regrow to the same size and time, a racy baseline settling, a file renamed over the name with other and with the same bytes (the first skipped without a file key: NTFS tunneling), a delete and the return of the same and of other bytes, a file where none was, a directory at the path, a retargeted link, a 3 MiB file changed in its last byte |
| `FileMonitorTest` | The poll, requested checks, one registration per directory released with its last file, a throwing listener reported once while checks go on, a directory event naming its file (skipped on macOS, whose `WatchService` polls), `suspend` and `resume` around the rename of a watched folder's parent (on Windows the rename fails first without `suspend`), `close` |
