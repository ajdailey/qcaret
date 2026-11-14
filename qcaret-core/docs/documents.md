# Documents

Text storage (`core.buffer`), the document model and its file loads and saves
(`core.document`), and editing commands (`core.edit`), of
[qcaret-core](../README.md).

## Text storage

`RopeStorage` holds the text: an AVL tree whose nodes each hold one immutable
`String` leaf of at most 2 Ki chars plus the length and line breaks of the
subtree. An edit rebuilds the leaves it touches and copies the path to them.
`snapshot()` returns the current root as an immutable `TextSnapshot` in O(1),
for reading on a worker. `TextStorage` (mutable) and `TextSnapshot` read
through `TextContent`: `CharSequence`, `lineCount`, `lineAt`, `offsetOfLine`,
`lineLength`, `line`, `readChunks`, `lineView` and `cursor`.

Line breaks are LF, CR LF and CR; lines are zero-based. A CR LF pair split
across two leaves is one break: each node counts a trailing CR as a break, and
joining a node that ends with CR to one that starts with LF subtracts one
(`LineBreaks`). Leaf scans use `String.indexOf`, also for CR LF text.

| Read | Use | Cost |
|---|---|---|
| `charAt` | Random access | One descent per leaf change; too slow for a per-char walk of a large text |
| `cursor(offset)` | Sequential per-char reads, forward or back (`TextCursor`) | Copies one leaf at a time into a `char[]`; one descent per leaf |
| `readChunks` | Bulk reads | Passes each leaf range to a `ChunkVisitor` |
| `lineView(line, view)` | One line without copying (`LineView`, reused) | One descent; reads the leaf in place |
| `line(i)` | One line as a `String` | Copies the line |

A `TextCursor` and a `LineView` belong to one thread and read the text version
they were positioned on.

## Document model

`Document` holds the text of one open file: a `RopeStorage`, its revision, undo
history, line ending counts, encoding and dirty state. One thread edits it, the
first to call a mutating method (the UI thread); a mutation from another
thread, or from a listener while it is being notified, throws
`IllegalStateException`. Workers read `snapshot()`, a `DocumentSnapshot`
(revision, undo state, text, encoding, line ending) taken in O(1).

`tabWidth()` (1 to 16, default 4) and `insertSpaces()` are per document; they
change no text.

### Edits and change events

`apply(EditBatch, origin, after)` applies non-overlapping `Replacement`s given
in the current text's coordinates, highest offset first. Each applied batch,
undo step and redo step increments `revision()` (a `long`, never reused) and
fires one `DocumentChange` to `DocumentListener`s: the revision, the cause
(`EDIT`, `UNDO`, `REDO`) and one `TextEdit` per replacement, by ascending
offset in the text before the change, with the removed and inserted text, the
start line and the line breaks in each. Applying the edits highest offset first
to the text before the change reproduces the text after it. A read-only
document refuses `apply`, `undo` and `redo`.

`applyInBlock` applies a batch and leaves the origin model in block mode with
the given block; a line past the new text's end becomes the last line. An undo
or edit that leaves the model's block lines gone leaves block mode.

### Input method composition

`preedit(offset, text)` replaces an input method's composition text outside
undo: the undo state, and so the dirty state, stay as they are, and the change
fires with cause `PREEDIT`, which recovery skips. It replaces only the current
composition; `offset` places a new one, and no call removes other text. Undo,
redo and `beginGroup` remove the composition first. `apply` refuses while one
shows, since the batch's offsets would include it, so callers run
`clearPreedit()` before computing an edit.

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
clear it. `Document.markUnsaved` makes a document dirty until the next
`markSaved`, for a buffer whose file was deleted; undo cannot make it clean.

### Positions and selections

| Class | Contract |
|---|---|
| `Anchor` | A live offset mapped through every change with its `Bias`: `BEFORE` stays before an insert at it, `AFTER` moves past it. A position inside a removed range goes to the start (`BEFORE`) or the end (`AFTER`) of the inserted text |
| `PositionMap` | `positions().map(revision, offset, bias)` maps an offset from an earlier revision through a log of the last 10,000 batches and at most 1,000,000 replacements; older revisions give `UNMAPPABLE` |
| `Selection` | Anchor, caret and preferred visual column; empty is a caret |
| `SelectionSet` | Immutable, sorted, with one primary; selections that overlap or touch merge when the set is built. A set from `apart` (the occurrence commands) keeps two non-empty selections that only touch apart, and so does every set mapped from it |
| `SelectionModel` | One view's selections, from `Document.newSelectionModel()`, mapped through every change: a caret moves past an insert at it, a range does not grow at either end. `set` is a user move and ends typing coalescing. An offset that `set` or a change leaves inside a CR LF or a surrogate pair moves to the pair's start, so no selection reaches the widget there |
| `BlockSelection` | Lines and visual columns (`Columns`: tabs to the next stop, East Asian wide chars 2). `rows` gives one `BlockRow` per line with the spaces typing must insert to reach the left column on a short line; a block as wide as nothing has empty rows, before a tab or wide char its column falls inside; in block mode the model maps the block's lines and rebuilds its selections |

### Line endings and encoding

The text keeps every CR, LF and CR LF as read, and a save writes them back
unchanged. `lineEndings()` counts each kind, updated per change from the
replaced range and one char on either side; `isMixed()` drives the status bar's
"Mixed". `lineEnding()`, what Enter inserts, is the most frequent kind at load,
the first seen on a tie, or `LoadOptions.empty` (LF in `LoadOptions.DEFAULTS`)
for a file without breaks. `setEncoding` changes what the next save writes and
makes the document dirty until then.

## Loading and saving

`DocumentFiles.load` and `save` run on a worker (`@WorkerThread`) and check
their `CancellationToken` per block.

### Load estimate and reservation

- `estimate` reads the size and the first 64 KiB and returns a `LoadCost`. A
  char costs 1 byte when the sample is ASCII and 2 otherwise; a UTF-16 or
  UTF-32 BOM divides the char count by 2 or 4. The peak is the text plus the
  load's buffers (at most 5 MiB); the retained cost is the text plus undo
  headroom of the same size, at most `UndoLimits.maxBytes` (64 MiB by default).
  `reserveBytes()` is the larger of the two.
- `LoadReservation.reserve` holds `reserveBytes()` from the `MemoryBudget`.
  `setProgress` passes the file bytes read at each `charge`, on the loading
  thread; a retry with another charset counts from the start.
- During the load, `charge` raises the hold by what the text read so far costs
  over the estimate at that point in the file, since an ASCII sample can hide 2
  bytes per char later. A raise the budget refuses ends the load with
  `LoadTooLargeException`, whose cost is projected from the text read.
- `keep` lowers the hold to `LoadedFile.retainedBytes`, measured from the
  leaves, and hands it to the caller until the document closes; `close`
  releases the rest and belongs in a `finally` block.

### Load

Load holds no copy of the file (at most `Integer.MAX_VALUE - 8` bytes). The
first 64 KiB give the forced charset or `EncodingDetector.candidates` and the
binary check; `BinaryFileException` for a binary file unless
`LoadOptions.asBinary()` (ISO-8859-1, which maps every byte). With
`LoadOptions.modelines` the last 4 KiB are read too, for a modeline charset, and
`LoadOptions.configured` is the EditorConfig charset; both reach detection only
without a forced charset. A file of 0 bytes with neither a forced nor a
configured charset takes `LoadOptions.emptyCharset` when set (reason `EMPTY`),
else detection.

Each candidate takes one `LoadPass`: 1 MiB blocks are digested, decoded
strictly into a `DocumentBuilder` (rope leaves and line-ending counts) and the
chars encoded back against the same bytes. A candidate that fails ends its pass
at the failing byte and the next starts over, so a file in its first candidate
is read once and a windows-1252 file twice. The last candidate's failure is
`TextCodingException` with the byte offset.

- One channel serves every pass, so a file replaced by a rename during the load
  stays the file opened. The digest and text come from the same pass, and a
  write in place between passes is not noticed by the load.
- `LoadedFile.roundTrips` false means the bytes would change on save; the
  caller opens the document read-only and names the encoding. A charset with no
  encoder never round-trips.
- `LoadedFile.baseline` holds the stamp read before the bytes and the SHA-256
  of the bytes read.
- `x-JISAutoDetect` reads the file as one block, charged to the reservation: it
  gives another result on split input. Of the 173 charsets of JDK 21.0.12,
  measured at blocks of 1 to 16 bytes on encoded text and random bytes, it is
  the only one.

### Reload

`reload` compares a file with an open document's text without holding the
file's text and returns a `ReloadedFile`. It reads as a load does, into a
`TextSink` instead of a `DocumentBuilder`.

1. The first pass (`TextDifference.Prefix`) finds the common prefix, the char
   count and the line endings; equal texts end there.
2. The second (`TextDifference.Span`) compares each later char with the current
   text's char at the same distance from the end. A run of equal chars is only
   counted and joins the span when a later char differs, so the run left at the
   end is the common suffix, and the rope builder holds only the span.

A second-pass digest or char count that differs from the first is an
`IOException`: the two passes must describe one file. `roundTrips` comes from
the first pass, as for a load.

`LoadReservation.reload` charges the buffers, the inserted text twice (a
`String` and the rope's leaves) and the removed text once (the undo step's
`String`), the heap `Document.apply` reaches, and the caller holds it until the
span is applied. Charges draw on the budget only past the document's undo
headroom, so a document at the budget limit reloads a span of up to about a
third of its headroom. The span runs from the first difference to the last, so
two edits far apart cost the text between them. A larger span fails with
`LoadTooLargeException`: two copies of a text at the limit do not fit the heap.
The undo step of a span past the headroom stays uncharged once applied.

### Save

Save encodes the snapshot once to find an unmappable char
(`TextCodingException` with the char offset, no file touched), then writes the
BOM and the text through `SafeSave` and returns a `FileBaseline`: the new stamp
and the SHA-256 of the bytes written. The caller passes the stamp to the next
save and the snapshot to `markSaved`.

## Editing commands

`core.edit` holds functions from a `TextContent` and a `SelectionSet` to an
`Edit` (the batch and the selections after it, or null to map them), so a
controller applies each as one `Document.apply`. `Edit.unchanged` counts the
lines a line command left as they were because changing them would put a CR
directly before an LF; when nothing else changes, the batch is empty. A line a
selection touches holds part of it; a range ending at a line's start leaves
that line out.

| Class | Commands |
|---|---|
| `LineEdits` | Duplicate (each range after itself, or each caret's lines below them), move lines up or down (touching blocks move together; nothing moves at the edge or when a CR and an LF would become neighbors, which joins two breaks), delete lines (the last line takes the break before it; nothing is deleted when a CR and an LF would become neighbors), join (break and the next line's indentation become one space, or none after an empty line or whitespace), upper and lower case through `CaseEdits`, indent and outdent (empty lines skipped; outdent removes a tab or up to a tab width of spaces), the Tab key (indents when a range spans lines, else a tab or spaces to the next stop), split a range into one selection per line |
| `BlockEdits` | Typing, Backspace, Delete and paste in a `BlockSelection` (Notepad++ column mode): a row that ends left of the block is padded with spaces; the block stays a block, as wide as nothing after typing; paste gives each row one line |
| `Moves`, `Words` | Caret steps by char (a CR LF and a surrogate pair are one), smart Home, End, and word moves that stop at runs of word chars, of other non-blank chars and at line breaks. `Words` walks clusters, a code point and the combining marks after it, which take its kind; caret word moves, double-click, Add Next Occurrence, the occurrence highlight and Delete Word share it |
| `CommentEdits` | Toggle line comments on the touched lines (removed when every non-blank line has one after its indentation, else added at the block's smallest indentation; a block comment per block of lines without a line comment) and block comments around each selection or a caret's trimmed line; a range keeps text inserted at its ends |
| `Occurrences` | The next exact occurrence of a text at or after an offset, wrapping, every occurrence without overlap, and the whole-word occurrences of a word in a range; a match starting or ending between a CR and its LF is skipped. Cancelable, for a worker |
| `CaseEdits` | Upper, lower, title and swap case, one code point at a time (the word at a caret). Title case starts each run of letters, digits and combining marks; an apostrophe between letters joins them |
| `WordDeletes` | Delete Word Back and Forward at each caret over the span a word move crosses; a line break goes alone, a CR LF as one |
| `Transpose` | Each caret swaps the grapheme clusters (`BreakIterator`) on its sides and moves past them, or the two before it at a line end; never across a line break. Two or more ranges rotate their texts instead; a rotation that would put a CR before an LF changes nothing and counts the touched lines unchanged |
| `SelectionExpansion` | Expand Selection (word, quotes, brackets through a `Brackets.Filter`, lines, text; `null` when the bracket scan bound ended the search) and Select Line |
| `LineTransforms` | Sort (code unit, descending, ignoring case), remove duplicate lines (keeps the first) and trim trailing spaces and tabs, over each range's touched lines or, when every selection is a caret, every line but an empty last one. A sort leaves each break in its place, so offsets and selections stay, and `SelectionModel` moves one that lands inside a pair; a removed last line takes the break before it. A sorted block, a removed run of duplicates or a trimmed line that would put a CR before an LF stays as it is. Cancelable, for a worker |
| `ParagraphWrap` | Wrap Paragraph at a column: every paragraph in the lines a selection touches, else the paragraph at each caret. A paragraph is a run of non-blank lines that all start with the grammar's line comment after their indent, or none of which does; each wrapped line takes the first line's indent and comment prefix. Words join with one space, tabs count to the next stop, and a word longer than the column takes a line of its own. Cancelable |
| `AutoClose`, `Pairs` | Typing with auto-closed pairs at each caret: an open char inserts its pair before a line end, blank, close bracket, `,` or `;` (a quote also needs no word char or same quote before it), a close char before the same char moves over it, Backspace between a pair deletes both. Returns null when no caret uses a pair, so plain typing applies. `Pairs.DEFAULT` is `() [] {} "" ''` |
| `Brackets` | The bracket beside a caret (the char before first) and its partner within bounds: same-pair brackets nest, other chars are ignored, and a `Filter` leaves out brackets (the app passes the ones in strings and comments); `NONE` when the text holds no partner, `BEYOND` when the bounds ended the search |
| `WordIndex` | The distinct words of a text for completion (letter or `_` first, 2 to 100 chars, at most 100,000), sorted by lower case; prefix lookup ignoring case. A word is copied only the first time it is seen |
