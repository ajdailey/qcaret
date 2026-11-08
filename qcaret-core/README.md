# qcaret-core

Model-side code with no SWT and no logging dependency: thread ownership, the
memory budget, paths and safe file writes, encodings and line endings, text
storage, the document model, editing commands, grammars and the tokenizer,
search and file replace, the line diff, external change detection and the
workspace folder's listings and index. Tests run without a display.

A package uses only lower layers (`ArchitectureTest.coreLayers` in
`qcaret-launcher`); the layer column below is that order, lowest first.

---

## Map

| Package | Layer | Contents | Detail |
|---|---|---|---|
| `core.util` | 1 | `Checks`, `Streams`; depends on no other QCaret package | [Files and encodings](docs/files-and-encodings.md#utilities) |
| `core.concurrent` | 2 | `Workers`, `CancellationToken`, `WorkerThread`, `MemoryBudget` | [Threads and memory](docs/threads-and-memory.md) |
| `core.io` | 3 | `SafePaths`, `AtomicFiles`, `SafeSave`, `ProcessLock`, `FileStamp`, `FileBaseline`, `Digests`, `BinaryDetector`, `HiddenFiles` | [Files and encodings](docs/files-and-encodings.md) |
| `core.text` | 4 | `TextEncoding`, `EncodingDetector`, `LineEnding`, `LineEndingStats`, `LogText`, `BoundedChars`; `EditorConfig`, `EditorConfigFile`, `EditorConfigGlob`, `Modelines`, `FileSettings` | [Files and encodings](docs/files-and-encodings.md#encodings) |
| `core.buffer` | 5 | `RopeStorage`, `TextSnapshot`, `TextCursor`, `LineView` | [Documents](docs/documents.md#text-storage) |
| `core.document` | 6 | `Document`, `EditBatch`, `DocumentChange`, undo, selections, anchors, `DocumentFiles` | [Documents](docs/documents.md#document-model) |
| `core.edit` | 7 | `LineEdits`, `LineTransforms`, `BlockEdits`, `CommentEdits`, `AutoClose`, `Brackets`, `Moves`, `Words`, `WordIndex`, `Occurrences` | [Documents](docs/documents.md#editing-commands) |
| `core.syntax` | 7 | Grammar reader, compiler, `Tokenizer`, `SyntaxEngine`, `GrammarSet` | [Grammars](docs/grammars.md) |
| `core.search` | 7 | `TextMatcher`, `TextSearch`, `FileSearch`, `FileReplace`, `ReplaceBackups` | [Search](docs/search.md) |
| `core.diff` | 7 | `LineDiff` | [Search](docs/search.md#line-diff) |
| `core.watch` | above `io` | `FileCheck`, `FileMonitor` | [External changes](docs/external-changes.md) |
| `core.workspace` | above `io` | `WalkRules`, `DirectoryListing`, `FileIndex` | [Search](docs/search.md#workspace-folder) |

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
- A load, a search read, a replace, a result set and the file index reserve
  their heap from `MemoryBudget` before they hold it, and release it when they
  drop it.
- A value that did not originate in QCaret's code reaches a logger through
  `LogText.oneLine`.

## Testing

The test jar carries `testkit` for the other modules' tests and `qcaret-bench`.
The harness and coverage by class are in [TESTING.md](TESTING.md).
