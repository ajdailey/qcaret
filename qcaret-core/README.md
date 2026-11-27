# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership, the
memory budget, paths and safe file writes, encodings and line endings, text
storage, the document model, editing commands, XML and JSON formatting,
grammars and the tokenizer, the Markdown preview model,
search and file replace, the line diff, external change detection and the
workspace folder's listings and index. Tests run without a display. The one
third-party runtime library is commonmark-java with its extensions, in
`core.markdown` only.

A package uses only lower layers (`ArchitectureTest.coreLayers` in
`qcaret-launcher`); the layer column below is that order, lowest first.

---

## Map

| Package | Layer | Contents | Detail |
|---|---|---|---|
| `core.util` | 1 | `Checks`, `Streams`; depends on no other QCaret package | [Files and encodings](docs/files-and-encodings.md#utilities) |
| `core.util.os` | 1 | `OsTraits` and one class per operating system, `EnvPaths`, `RunnableTypes` | [Files and encodings](docs/files-and-encodings.md#operating-system) |
| `core.concurrent` | 2 | `Workers`, `CancellationToken`, `WorkerThread`, `MemoryBudget` | [Threads and memory](docs/threads-and-memory.md) |
| `core.io` | 3 | `SafePaths`, `AtomicFiles`, `SafeSave`, `ProcessLock`, `FileStamp`, `FileBaseline`, `Digests`, `BinaryDetector`, `HiddenFiles`; checksums: `ChecksumAlgorithm`, `Checksums`, `ChecksumList`, `ChecksumCheck` | [Files and encodings](docs/files-and-encodings.md) |
| `core.text` | 4 | `TextEncoding`, `EncodingDetector`, `LineEnding`, `LineEndingStats`, `LogText`, `BoundedChars`; `EditorConfig`, `EditorConfigFile`, `EditorConfigGlob`, `Modelines`, `Modeline`, `FileSettings`; `PathAtCaret` | [Files and encodings](docs/files-and-encodings.md#encodings), [Documents](docs/documents.md#path-at-the-caret) |
| `core.buffer` | 5 | `RopeStorage`, `TextSnapshot`, `TextCursor`, `LineView` | [Documents](docs/documents.md#text-storage) |
| `core.document` | 6 | `Document`, `EditBatch`, `DocumentChange`, undo, selections, anchors, `DocumentFiles`, `TextModelines` | [Documents](docs/documents.md#document-model) |
| `core.edit` | 7 | `LineEdits`, `LineTransforms`, `BlockEdits`, `CommentEdits`, `AutoClose`, `Brackets`, `Moves`, `Words`, `WordIndex`, `Occurrences`, `CaseEdits`, `WordDeletes`, `Transpose`, `SelectionExpansion`, `ParagraphWrap`, `CodecEdits`, `TextCodec`, `NumberSequence` | [Documents](docs/documents.md#editing-commands) |
| `core.syntax` | 7 | Grammar reader, compiler, `Tokenizer`, `SyntaxEngine`, `FoldRegionPass`, `GrammarSet`, `FoldRegions`, `SymbolScanner`, `SymbolScan`, `SymbolKind` | [Grammars](docs/grammars.md) |
| `core.search` | 7 | `TextMatcher`, `TextSearch`, `FileSearch`, `FileReplace`, `ReplaceBackups` | [Search](docs/search.md) |
| `core.diff` | 7 | `LineDiff` | [Search](docs/search.md#line-diff) |
| `core.format` | 7 | `Formats`, `FormatLayout`, `FormatResult`, `FormatError`, `KeptContent`: XML and JSON Pretty Print, Minify and Check; `CaretPath` | [Documents](docs/documents.md#xml-and-json-formatting) |
| `core.markdown` | 7, on `util`, `concurrent`, `text` and `document` | `MarkdownRenderer`, `RenderedMarkdown` and its records (`StyleRun`, `MarkdownBlock`, `CodeBlock`, `MarkdownTable`, `MarkdownImage`, `MarkdownLink`, `LinkTarget`, `HeadingPath`, `MarkdownHeading`, `MarkdownAlert`, `MarkdownFootnote`) | [Markdown](docs/markdown.md) |
| `core.xml` | uses no QCaret package | `SecureXml`: every JDK XML parser and schema factory, each locked down | [Grammars](docs/grammars.md#xml-parsers) |
| `core.watch` | above `io` | `FileCheck`, `FileMonitor` | [External changes](docs/external-changes.md) |
| `core.workspace` | above `io` | `WalkRules`, `DirectoryListing`, `ListingOptions`, `NameFilter`, `FileIndex`, `FileOperations`, `HomeFolder` | [Search](docs/search.md#workspace-folder) |
| `core.hex` | 7, on `concurrent`, `io`, `text` and `buffer` | `HexSource`, `FileHexSource`, `EncodedHexSource`, `ByteOffsets`, `HexBlocks`, `HexRows`, `HexRow`, `HexViewport`, `HexPattern`, `ByteSearch` | [Hex view model](#hex-view-model) |

The layer 7 packages do not use each other.

## Main contracts

- `Workers` creates every thread; nothing else creates a thread, an executor, a
  `Timer` or a `ThreadFactory`. `@WorkerThread` methods are called only from
  workers.
- One thread edits a `Document`, the first to mutate it (the UI thread).
  Workers read O(1) snapshots and check a `CancellationToken`; a result names
  the revision it read.
- Every path from outside the process goes through `SafePaths`, and every user
  file save through `SafeSave`.
- A load, a search read, a replace, a result set, the file index, a Go to
  Symbol scan and the Bookmarks list reserve their heap from `MemoryBudget` before they hold it, and
  release it when they drop it.
- A value that did not originate in QCaret's code reaches a logger through
  `LogText.oneLine`; a failure whose log line must not carry a path, through
  `LogText.reason`.

## Hex view model

`core.hex` is the read-only hex view's model. A view reads bytes through a
`HexSource`, whose size is fixed for its life:

| Source | Bytes | `charset()` |
|---|---|---|
| `FileHexSource` | The file on disk, any size. Each read opens, reads and closes the file, so no handle is held between reads; the `FileStamp` taken at `open` is compared before and after each read, and a mismatch throws `HexSourceChangedException` | ISO-8859-1 |
| `EncodedHexSource` | A `TextSnapshot` as Save writes it, BOM first, read through its `ByteOffsets`. A char the charset cannot encode, or a lone surrogate, reads as the encoder's replacement bytes, set in the read's `replaced` bits | The document's |

- `ByteOffsets` maps char offsets to byte offsets for one text version. Blocks
  are 64 Ki chars, one more where the boundary would split a surrogate pair.
  One-byte-per-char charsets and UTF-16 map arithmetically; there, each char
  of an unencodable pair gets its own replacement so the width stays fixed.
  Every other charset keeps each block's first byte offset (8 bytes per
  block), which `build` computes by encoding the whole text and `update`
  recomputes from the block before the first changed char. A lookup encodes at
  most one block.
- `HexBlocks` caches 16 blocks of 64 KiB per source, evicting the least
  recently used and reusing its arrays. `load` reads missing blocks; `copy` reads only
  the cache and never blocks. A `HexSourceChangedException` from `load` clears
  the cache, and a block read before a `clear` is dropped.
- `HexRows` lays a row out in character cells: offset (8 hex digits, 16 above
  4 GiB, widened to a longer column heading the caller passes), hex pairs in groups of 8, then one text cell per byte, ASCII 0x20 to
  0x7E and `.` otherwise. Bytes per row is 8, 16 or 32. `row` builds a `HexRow`
  from the cache, or returns null when a block is missing.
- `HexViewport` maps `long` rows onto an `int` scroll bar: one unit per row up
  to 2^30 rows past the top, scaled above.
- `HexPattern` parses hex pairs with `??` wildcards, or encodes text in the
  source's charset, 1 to 256 bytes; `HexPatternException` names the reason and
  the input index. `ByteSearch.find` reads the source in windows that overlap
  by the pattern length less one byte, forward or backward, with wrap, a
  `CancellationToken` and a progress count.
- `ByteOffsets.build` and `update`, `HexBlocks.load`, `ByteSearch.find`,
  `FileHexSource.open` and every `HexSource.read` are `@WorkerThread`.

## Testing

The test jar carries `testkit` for the other modules' tests and `qcaret-bench`.
The harness and coverage by class are in [TESTING.md](TESTING.md).
