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
  command each; undo restores every caret.
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
  as the new primary; a result for another revision is dropped.
- Esc cancels a running line transform, save and comparison through one
  `Runnable` and the `lineWork` token, and drops the secondary selections and
  the block; with neither, it closes the find bar.

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
  `brackets` or `markers` grammar's source is the document's
  `SyntaxHighlighter`, whose new regions run the step again; other grammars and
  plain text use `IndentFoldSource` at the document's tab width. A source that
  has no regions for the snapshot's revision returns null, and the installed
  regions stay.

## Line transforms

Sort, Remove Duplicate Lines and Trim Trailing Whitespace compute their
`LineTransforms` edit on a `qcaret-cpu-` worker and apply it on the UI thread
as one undo step, with the view's busy cursor from the command to the apply; a
result for an older revision is dropped with a notice.

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
