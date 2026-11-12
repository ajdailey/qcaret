# Editor controller

`EditorController`, the overview strip and the Bookmarks list, in
`app.controller` of [qcaret-app](../README.md). The display projection and the
other `app.editor` models are in [Display projection](display-projection.md).

## Edits

`EditorController` runs one editor's `EditorInput` against its document: every
edit is one `Document.apply` with the view's `SelectionModel` as origin, and
undo and redo restore that model. A read-only document ignores edits.

- Typing, Enter (the document's line ending), Backspace and Delete apply at
  every selection and coalesce by kind; Backspace and Delete take a CR LF or a
  surrogate pair whole.
- Copy joins the selections with the line ending; Paste converts line breaks to
  it and, with as many lines as selections, puts one line at each.
- An edit the widget made on its own is mapped from display offsets and applied
  as a command.
- `type` holds a high surrogate until its low one arrives: views send one char
  per key event, and a block's column moves between two lone halves.
- Line, case and indent commands are `core.edit` functions applied as one
  command each; undo restores every caret. Delete Word Back and Forward
  (`WordDeletes`), Title Case and Swap Case (`CaseEdits`) and Transpose
  (`Transpose`) are such functions too.
- The controller runs the projection's rewraps on `qcaret-cpu-` workers, a
  newer request canceling the running one.

## Carets and block selection

- Caret moves apply to every selection; a move without Shift collapses a range
  to its side first. Up, Down and the page keys follow the projection's rows
  (`CaretNavigation`) at each caret's preferred column; a page is the view's
  `ViewLayout.pageRows`.
- Block selection: Alt+Shift with an arrow key, a mouse drag with Alt (the view
  reads rows and columns through `ViewLayout`), or Shift with an arrow key in
  column selection mode, moves the block's caret corner; columns past a line's
  end are virtual. In block mode typing, Backspace, Delete, cut, copy and paste
  follow `BlockEdits`, and the block stays a block.
- Add Next Occurrence selects the word at a primary caret, else searches a
  snapshot for the primary's text on a `qcaret-cpu-` worker and adds the match
  as the new primary, apart from a selection it touches
  (`SelectionSet.apart`); a result for another revision is dropped.
- Esc cancels a running save and comparison through one `Runnable`, a line
  transform through the `lineWork` token and a selection search through
  `SelectionCommands`, and drops the secondary selections and the block; with
  neither, it closes the find bar.

## Selection commands

`SelectionCommands` holds one view's selection commands; `EditorController`
passes them the command.

- Select All Occurrences selects every occurrence of the primary selection's
  text (`Occurrences.all`), or every whole-word occurrence of the word at a
  primary caret (`Occurrences.words`); the occurrence at the old primary stays
  primary. Occurrences that touch stay separate selections, so typing over
  `ab` twice in `abab` gives `xx`. Past `SelectionCommands.MAX_OCCURRENCES`
  (10,000) it selects nothing and shows a notice.
- Skip Occurrence drops the primary, which Add Next Occurrence added last, and
  selects the next occurrence of its text, wrapping, as the new primary. When
  that occurrence is already selected, it becomes the primary. At a caret it
  runs Add Next Occurrence.
- Expand Selection widens each selection to the smallest longer range of
  `SelectionExpansion.expand` that holds it: the word, the inside of the
  quotes around it on its line (`"`, `'`, `` ` ``; a backslash escapes), the
  quotes too, the inside of the innermost bracket pair, the brackets too, its
  lines without the last break, the whole text. Brackets pair and skip strings
  and comments as bracket matching does, within 16 Ki chars on the UI thread.
  A line wrapped inside a bracket pair is smaller than the pair, so the line
  comes first there.
- Select Line selects the touched lines with their breaks; a selection that
  already holds them takes the next line too.

A text longer than `SYNC_CHARS` (64 Ki chars) is searched on a `qcaret-cpu-`
worker, as is a bracket pair further than 16 Ki chars, with the view busy. The
result applies only at the same revision and selections; one for an older
revision is dropped with a notice.

`SelectionHistory` keeps Undo Selection and Redo Selection: before each
selection change, the selections and block, at most 100 in each direction. A
change that keeps every anchor of a non-empty selection, such as a drag or
Shift with an arrow key, extends the entry of the change before it. Any text
change, from any view, clears both directions, since the entries are offsets in
the old text.

## Input method composition

Composition text from the input method goes to `Document.preedit`, outside
undo, at the primary selection's start; the selection stays. The view ends the
composition and sends a commit as typed chars, which replace the selection and
coalesce into one undo step. Every edit removes a composition first. A disposed
`EditorController` drops `preeditFromView` and `type`: GTK takes the focus from
a closing tab's widget after the close, and the input method ends its
composition then.

## Grammar-driven commands

Each grammar switch passes the grammar to every view's
`EditorController.setGrammar`: comment delimiters, fold source, indent rules
and pairs.

- Toggle Line Comment and Toggle Block Comment apply `CommentEdits` with the
  document grammar's delimiters at every selection, as one undo step; a grammar
  without a line comment toggles a block comment around each block of lines.
- Enter and a typed closer go through `SmartIndent` with the view's
  `IndentRules`: `PatternIndentRules` over the grammar's `indent` regexes,
  `IndentRules.BRACKETS` for plain text and a grammar without them. A typed
  char that `AutoClose` handles does not outdent.
- Typing and Backspace follow `AutoClose` with the pairs `pairs()` returns: the
  grammar's `autoClose` pairs, `Pairs.DEFAULT` for plain text and a grammar
  without any; a block types plainly.

## Folding

- Fold collapses the region the primary caret's line starts, else the innermost
  expanded region holding it; Unfold expands the collapsed region the line
  starts; Toggle Fold does either; Fold All and Unfold All take every region of
  the view.
- Fold All measures its spans on a `qcaret-cpu-` worker against a snapshot and
  a copy of the rows, and collapses them only if the text, rows and offered
  regions are unchanged, else asks again; a fold command meanwhile drops it.
- Folding moves each caret and anchor in hidden lines to the end of the line
  before them. A primary caret that any other command puts in hidden lines
  (find, Go to Line, undo) expands the regions hiding it. Left, Right, the word
  moves and Mod+End step over hidden lines.
- Fold regions come from a `FoldRegionSource` on a `qcaret-cpu-` worker, 300 ms
  after the last change and at once on a tab width change, one run at a time; a
  result for an older revision is dropped. Each view computes its own. A
  `brackets`, `markers` or `sections` grammar's source is the document's
  `SyntaxHighlighter`, whose new regions run the step again. Other grammars use
  a `MergedFoldSource` of the highlighter's regions (folding spans and region
  markers) and `IndentFoldSource` at the document's tab width; plain text uses
  `IndentFoldSource` alone. A source that has no regions for the snapshot's
  revision returns null, and the installed regions stay, so an indentation
  grammar's regions wait for the highlighter's pass.

## Line transforms

Sort, Remove Duplicate Lines, Trim Trailing Whitespace and Wrap Paragraph
compute their edit (`LineTransforms`, `ParagraphWrap`) on a `qcaret-cpu-`
worker and apply it on the UI thread as one undo step, with the view's busy
cursor from the command to the apply; a result for an older revision is dropped
with a notice. Lines a transform, Delete Line or Move Lines leaves as they were
to keep a CR and an LF apart (`Edit.unchanged`) are counted in a notice through
`EditorController.notice`; a command that skips nothing shows none.

### Wrap Paragraph

Edit > Line > Wrap Paragraph hard-wraps at `editor.rightMargin`: with a
selection, every paragraph in the lines it touches; with carets only, the
paragraph at each caret. `ParagraphWrap` defines the rest:

- A paragraph is a run of lines that are not blank and that all start, after
  their indent, with the grammar's line comment, or none of which does. A line
  of blanks alone, or of its indent, the comment and blanks, is blank.
- Every wrapped line starts with the first line's indent, plus the comment and
  the blanks after it on a comment paragraph.
- Words are runs of chars other than space and tab, joined by one space. A line
  takes the words that keep it within the column, a tab counted to the next tab
  stop and a char per code point; a longer word is a line of its own.
- Wrapped lines end in the paragraph's first line break, or the document's line
  ending for a one-line paragraph, so a CR LF paragraph stays CR LF.
- A selection then spans its wrapped lines; a caret moves to the end of its
  paragraph.

## Bookmarks

Toggle works at every caret's line, Next and Previous from the primary caret's
line with wrap-around, and Clear; Next and Previous onto a hidden line expand
the regions hiding it. The `Bookmarks` are the document's, shared by its views.

Edit Bookmark Comment asks for the primary caret's line's comment. The answer
bookmarks the line and sets the comment, an empty one keeps the bookmark
without a comment, and a refusal (too long, too many) shows a notice and
changes nothing. An anchor follows the line while the prompt shows.

## Brackets and occurrences

- Bracket matching pairs the grammar's `brackets` (`Pairs.DEFAULT` for plain
  text) and skips a bracket whose token is a comment, string or escape
  (`TokenBracketFilter`). A line without tokens yet counts every bracket. A
  worker search reads a copy of the token cache taken when it starts.
- Go to Matching Bracket moves each caret beside a bracket past its partner;
  Select to Matching Bracket selects from the bracket to the partner. Each
  partner is sought on the UI thread within 16 Ki chars of its bracket; one
  further away sends every search to a worker, applied only at the same
  revision and selections. A scroll does not end that search.
- One UI turn after a caret move, an edit or a scroll, `EditorHighlights` gives
  the view the bracket beside the primary caret with its partner (sought on the
  UI thread within the visible range and 16 Ki chars on each side, else on a
  worker) and the whole-word occurrences of the word at the primary caret in
  the visible range (on a worker, at most 2,000, shown when there are two or
  more). An edit shifts both at once; a result for an older revision is
  dropped. A moved tab's new view shows both at once and computes them again.

## Word completion

Complete Word looks up the word chars before the primary caret in every open
document (`WordCompletion`); one match is inserted at once, more show the
view's list. With `editor.completionChars` above 0 the list also opens after
that many word chars are typed. While it shows, Up, Down, Page Up and Page Down
move in it, Enter and Tab replace the typed prefix at every caret that ends
with it, Esc closes it, typing narrows it, and any other command or a caret
moving off the word closes it.

## Insert Date and Time

`DateTimeInsert` runs Edit > Insert Date and Time. The chooser lists
`editor.dateFormat` first, then six common `DateTimeFormatter` patterns
(`DateTimeInsert.COMMON`), each with the current time, and selects
`ui.lastDateFormat`. The rows are formatted on a `qcaret-cpu-` worker: the first
format of a zoned time loads the time-zone rules (13 ms measured in QLogg).
The chosen format, in the default locale at the time of the choice, goes to
`EditorController.insertText`, which replaces every selection as one undo step
(a block takes it on every row, as typed text) and does nothing on a read-only
document. `Options.validDateFormat` checks a pattern by formatting a fixed UTC
time; the Preferences dialog refuses an invalid one, and an invalid one in
`qcaret.conf` falls back to the default.

## Document statistics

`StatisticsController` runs Tools > Document Statistics in a modeless dialog.
It counts the selected document's text snapshot and its non-empty selections
on a `qcaret-cpu-` worker, posting each new whole percent read; Refresh counts
again and closing the dialog cancels the count through its token. A result
for an older revision is dropped and the dialog says the document changed.

`TextStatistics` defines the counts:

| Count | Definition |
|---|---|
| Lines | Line breaks (LF, CR LF, CR) plus one, per counted range |
| Characters | Code points, line breaks included: a surrogate pair is one, CR LF two |
| Characters without spaces | Code points that are not `Character.isWhitespace`; a no-break space counts |
| Words | Maximal runs of such code points |
| Bytes | The text encoded in the document's charset, plus its BOM for the document; an unencodable char or a lone surrogate counts as the charset's replacement bytes. `UNKNOWN_BYTES` (shown as unknown) in a charset with no encoder |

Selections are counted one by one and summed, so a word cut by two selections
counts twice. `TextStatistics.Counter` reads `TextContent.readChunks` and
encodes through an 8,192-char buffer, carrying a surrogate pair split between
chunks or buffer fills.

## Overview strip

Each editor view has an `OverviewUpdater` that computes its strip's
`OverviewMarks` on a `qcaret-cpu-` worker: the find bar's matches over the
whole document while the bar is open on the selected document (at most
1,000,000), the matches of the set last navigated or else the newest set, and
the bookmarks.

- A request waits 150 ms, so an edit burst computes once; edits, bookmark
  changes, the find query, the results and the strip's height request it.
- A result shows only while the document is at its revision and the strip at
  its height. A hidden strip (View > Overview Strip, window-wide) computes
  nothing.
- The strip scales by model lines, marks and click alike, so folds change
  neither: a mark on a hidden line stays, and a click there scrolls to the row
  of the fold's first line (`DisplayProjection.rowOfLine`).

## Modified-line marks

Each editor view has a `LineChangesUpdater` that computes its gutter marks
(`LineChanges`) while the view shows them (View > Modified Line Marks,
`ui.lineChanges`); the view reports the toggle through
`EditorInput.lineChangesShown`.

- The baseline is `Document.savedText`: the text of the last
  `Document.markSaved`, which a load, a save and a reload all end in, or the
  initial text. `markUnsaved` keeps it, so a deleted file keeps its last
  baseline. A document restored from a recovery file takes the file on disk
  (`RecoveredBaseline`, read on a `qcaret-io-` worker through
  `Document.setSavedText`); a missing or unreadable file leaves the recovered
  text, so no line is marked. `DocumentListener.textSaved` reports each
  `markSaved` and `setSavedText`.
- `LineChanges.compute` diffs the saved and current texts by line with
  `LineDiff` on a `qcaret-cpu-` worker. In a run of removed lines followed by
  added lines, the added lines pair with the removed ones as changed and the
  rest are added; removed lines alone mark the line after them, or the last
  line.
- A text change waits 250 ms; a save computes at once. A current text that is
  the saved snapshot itself clears the marks without a worker. A newer request
  cancels the running one, and a result shows only while the document still
  has its revision and saved text. Undo back to the saved text computes no
  marks.
- A side over 100,000 lines or 16,777,216 chars is not compared: the view
  shows no marks and its gutter tooltip says why. Past `LineDiff.MAX_EDITS` the changed
  middle of the text is marked as one change.
- A split view computes its own marks.

## Bookmarks list

`BookmarkList` is the Bookmarks list's model (Search > Bookmarks > Bookmarks
List). While shown it follows the selected document through
`DocumentController.addActiveListener`, its edits and its `Bookmarks` changes;
hidden, it reads nothing.

- A read waits 150 ms, then builds the rows on a `qcaret-cpu-` worker from a
  text snapshot and the bookmarks' offsets: the line, the comment and the
  line's first 160 chars, control chars as spaces, at most 10,000 rows.
- Rows show only at the snapshot's revision; an older result is dropped and
  read again.
- Each row reserves 96 bytes plus 2 per char from the `MemoryBudget` while
  listed; rows past a refusal are left out, and the summary reads "first N of
  M".
- Go To, Edit Comment and Remove act on the row's bookmark at its current line
  (`Bookmarks.line(key)`), so an edit since the read does not move them to
  another line; Go To into a collapsed region expands it.
