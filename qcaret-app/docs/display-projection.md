# Display projection

The editor models in `app.editor` of [qcaret-app](../README.md): the display
projection and its folds, highlight composition, the token cache, indentation,
column metrics and bookmarks.

## DisplayProjection

`DisplayProjection` maps a document's text to the display text an editor shows:
model lines split into rows at virtual breaks, one display char each, where
word wrap ends a row or a line passes `MAX_ROW_CHARS` (16,384). A line without
breaks maps one to one. A display offset on a virtual break maps to the model
offset of the break; a model offset at a break maps to the start of the next
row.

- The projection answers for the text it last reported: during
  `Listener.changing` for the text before a change, during `changed` for the
  text after it.
- A document change becomes one display change covering the changed lines (all
  of a batch's edits in one range). `StyledText` shifts its per-line caches
  once per change, so one change per edited line cost 10,000 spread carets
  about 5 times as much (measured).
- `RowBreaks` places a line's breaks: a row ends where the next char would pass
  the wrap column, after the row's last space or tab when it has one, and holds
  at most `MAX_ROW_CHARS` chars. Columns count from the row start (tab stops,
  wide chars 2), and a surrogate pair is never split.
- `RowIndex` keeps only the lines with breaks, sorted, with their breaks in one
  array; edits replace a line range and shift the tail. A batch with several
  line groups goes through `replaceAll`, one pass over all entries, since a
  replace per group shifts the tail once per group: on a wrapped document that
  shifting is most of a 10,000-caret keystroke (profiled).
- A document change recomputes the changed lines' rows on the UI thread. A
  change over 1 Mi chars with wrap on gets only the long-line splits there, and
  the projection asks its owner for a rewrap.

### Rewrap

Turning wrap on or off, a new wrap width and, with wrap on, a new tab width
need a rewrap of every line: `rewrapRequest` returns a `Rewrap`, `compute` runs
on a worker from the request's snapshot, and `install` applies it only while
the document is still at that revision. A stale or superseded result is dropped
and the owner asks again.

### Folds

`setFoldRegions` gives the regions a view offers, one per start line; `fold`,
`foldAt`, `unfold`, `foldAll`, `unfoldAll` and `reveal` change the collapsed
set, each as one display change over the lines it affects.

`FoldLayer` holds the collapsed regions, sorted by start line, and the hidden
spans they merge into: region `(a, b)` hides lines `a + 1` to `b`, and line `a`
is followed by line `b`'s delimiter. Each span keeps the chars and rows it
hides and where its hidden text starts in the unfolded display, so a display
lookup is a binary search over spans and reads no text. A model offset in
hidden text maps to the end of the span's first line (`visibleOffset` gives a
shown offset in either direction).

| Edit | Collapsed region |
|---|---|
| Touches its hidden text, from the end of its first line to the end of its last | Expands |
| Ends at the first line's end, or inserts there without a line break | Kept, unless a CR would join that line's LF; the region follows the edit |
| Leaves its hidden lines without their wrap, under a deferred rewrap (over 1 Mi chars) | Expands |
| `setFoldRegions` with no offered region starting on its first line | Expands; an offered region takes a changed end |

Spans after an edit move by the change's char and row deltas; spans inside it
are measured again. Offered regions follow edits approximately until the next
`setFoldRegions`.

## Highlight composition

`HighlightComposer` composes what one view paints on a row: the syntax tokens'
foreground (`TokenSource`) and one background per char, taken from the first
layer that covers it in the order secondary selection (which also sets the
foreground), bracket pair, find match, word occurrence. It returns runs of role
and background ordinals; the view maps them to its toolkit styles.

`TokenCache` holds the tokens of the last 2,000 painted lines, maps each token
type to the `TokenRole` of the same name and shifts entries through a change:

| Edit | Cached tokens |
|---|---|
| Without breaks | Moved within its line |
| Insert with breaks | The line's tokens split around it |
| A CR and LF joining or parting at an edit (line count off from the edits' sum) | Every line dropped |
| Any other edit | The lines it touches dropped |

`copy` gives a worker the entries held.

`FoldRegionSource` is the fold layer's input: `{start, end}` line pairs per
revision, null while that revision's are not computed. `TokenBracketFilter`
reads a `TokenSource` and leaves comment, string and escape brackets out of
`Brackets` matching.

## Indentation

`PatternIndentRules` finds a grammar's `indent` regexes in at most 1,000 chars
of the line through a `BoundedChars`; a regex that reaches the read bound or
overflows the stack does not match.

`IndentFoldSource` opens a region on a non-blank line when the next non-blank
line is indented deeper, and ends it on the last non-blank line before a line
indented no deeper; blank lines stay outside.

`SmartIndent`:

- Enter copies the caret line's leading whitespace up to the caret.
- After an opener (`IndentRules.increase`) it adds a tab or a tab width of
  spaces as the document indents, and with a closer after the caret
  (`IndentRules.decrease`) the closer moves to its own line at the old
  indentation.
- After a block-ending statement (`IndentRules.dedentNext`, Python's `return`
  and `pass`) the new line takes one level less.
- A closer typed after only whitespace outdents the line to the previous
  non-blank line's indentation, within 1,000 lines, one level less unless that
  line opens one, and never indents it further.

## Column metrics

`ColumnMetrics` holds a monospace font's column geometry: the advance is the
measured width of 1,000 digits over 1,000, kept fractional, and column `c`
starts at `round(c * advance)`. The gutter width, the wrap width in columns and
the right margin line use it; text outside printable ASCII, and the indent
guides, are placed at the widget's measured glyph positions.

## Bookmarks

`Bookmarks` holds one document's bookmarks as `Anchor`s at their lines' starts
with `Bias.AFTER`, so a break inserted at that start moves a bookmark down with
its line's text. Each bookmark has an optional comment: one line (`clean` turns
breaks and other control chars into spaces), at most 1,000 chars, at most 1,000
per document; removing the bookmark removes it.

`Bookmarks` listens to the document and, for each edit that removes text, works
in the coordinates before the edit:

| Edit | Bookmark |
|---|---|
| Replaces the line, and the inserted text holds a line with the same text (Move Line, Sort) | Moves to that line |
| Removes the line's text and its break, or the last line's text and the break before it | Removed |
| Joins its line to a bookmarked line above | Removed; the upper one stays |
| Anything else | Follows its anchor |

A removed bookmark is kept with the removed text's length and hash and two
anchors around the inserted text, for the newest 1,000 such edits. An undo or
redo that puts the same text back between those anchors (at the start or the
end of the span, so coalesced deletes match too) restores the bookmark with its
comment. Toggle, Clear and Remove are not undoable, and Clear drops the kept
ones.

`LineEnds` names the break after a line for the line-ending marks.
