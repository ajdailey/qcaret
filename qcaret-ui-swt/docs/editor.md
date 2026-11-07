# Editor pane

`EditorPane` and its parts in `ui.swt.editor` of [qcaret-ui-swt](../README.md):
the content adapter, key and mouse input, the gutter, carets, line styles, the
overview strip and the scroll bar.

`EditorPane` is one editor: a `StyledText` over a `ContentAdapter`, the
`StyledTextContent` that serves the display rows of the document's
`DisplayProjection`, then the `OverviewStrip` and a `Slider` as the vertical
scroll bar, in one composite (`control()`). The model is the only authority.

## Content adapter

The adapter turns each projection change into one `textChanging` and
`textChanged` pair, so the widget never re-reads the whole text after an edit.
It sends no `textSet`; a loaded document gets a new adapter, read in
`setContent`. `setText` goes to the controller as an edit.

### Input method composition

A `replaceTextRange` from the widget, which it calls only for input method
composition text, goes to `EditorInput.preeditFromView`, outside undo. SWT
sends `ImeComposition` on the `IME` widget, and its `StyledText` listener
removes the composition at a commit; the committed chars then arrive as key
events, one per UTF-16 unit (measured on GTK 3 under IBus; read in SWT 3.135
for win32 and cocoa).

- That listener also collapses the widget's selection, so `EditorPane` ignores
  widget caret reports until its own `IME` listener runs, and puts the model's
  selection back once no composition shows.
- A display change reports whole rows, and `StyledText.handleTextChanged` reads
  a change that starts before the composition as text that moved it, so after
  each `replaceTextRange` the pane sets `IME.setCompositionOffset` from the
  model. Without that, the offset of a composition past column 0 moves off it,
  and at the document's end `showCaret` throws.

The events IBus sends are in [TESTING.md](../TESTING.md#ibus-test).

## Keys

A `VerifyKey` listener converts each key with `SwtKeys.stroke`, runs the
`Keymap`'s editor binding through `EditorInput.execute`, types printable chars
and Tab, and consumes both. Ctrl+Alt (AltGr) and, on macOS, Option produce
typed chars. Insert alone is consumed, so the widget never enters overwrite
mode. Any edit the widget still makes (word delete, middle-click paste, drag
and drop) arrives as `Verify`, is canceled, and goes to
`EditorInput.replaceFromView`; the controller applies it inside the listener,
before `modifyContent` returns.

## Carets and selections

Caret moves are editor bindings, run by the controller at every caret. A plain
click and drag move the widget's caret natively, and each move goes to the
model. The widget holds only the model's primary selection, set again after
every model change and every display change. Caret moves the widget makes while
it applies a change are ignored, and so is a widget report of that primary,
such as after a resize, unless it follows a plain click.

### Mouse gestures

`EditorGestures`, a Display filter, takes the editor's mouse gestures before
the widget sees them (the widget's own Alt+click adds a native selection):

- Alt+drag, and Ctrl+Alt+drag where the window manager takes Alt+drag, select a
  block; Alt+Shift+click and drag extend it; in column selection mode a plain
  drag selects a block.
- Mod+click adds a caret or removes the one clicked, and a drag from that press
  adds a selection to the ones from before it (`addSelectionFromView`), once
  the pointer leaves the pressed offset.
- The press fixes the block's anchor as a line and column, and each drag step
  extends from it, so a horizontal scroll during the drag keeps the anchor.
- A block or Mod+drag held above, below, left or right of the text area extends
  toward the pointer every 50 ms from a `UiDispatcher.timerExec` timer, and the
  controller scrolls to the caret. Release, focus loss and dispose cancel the
  timer (`cancelTimer`).
- A click in the fold marker column sends `toggleFoldFromView`, so the widget's
  caret stays.

### Column layout

`EditorLayout` is the pane's `ViewLayout`. A printable ASCII row without tabs
maps x to a column at the font's fractional advance from the row's left edge,
while the row's measured width stays within half a column of that advance; any
other row uses the widget's measured glyph positions. A family's bold or italic
face can be wider than its regular face, which is the advance `ColumnMetrics`
measures. Past a row's end columns continue at the fractional advance.

A double click at or past a wrapped row's right edge selects the row's last
word, where the widget alone would take the trailing space and the virtual
break.

## Gutter

The gutter is painted in the widget's left margin by a paint listener, with the
line number on each line's first row; `ColumnMetrics` places the digits and
sizes the margin.

- `MainWindow.setLineNumbersShown` reaches every pane and the constructor of
  later ones, since a margin change on a loaded document costs O(rows); hidden
  numbers take their width with them, and the bookmark and fold columns stay.
- A two-column fold marker column right of the numbers shows a box with a minus
  on each line that starts a fold region, a plus while it is collapsed. A
  collapsed region's first line ends in a box with an ellipsis.
- Bookmarked lines get a mark on their first row in the bookmark column
  (`EditorPane.BOOKMARK_COLUMN`, 14 px left of the line numbers), so it never
  meets the digits or the fold marker column: a bar in the caret color for a
  bookmark, a flag (the bar and a square at its top right) in
  `Palette.bookmarkComment` (teal, mint on dark) for one with a comment.
- With the pointer over the gutter left of the fold markers, a commented
  bookmark's line shows its comment as the `StyledText` tooltip. The pane
  follows the document's `Bookmarks` and repaints on each change they report; a
  palette change recolors the marks.

## Painting

- The current line's rows get the palette's current-line background
  (`LineBackgroundListener`).
- Secondary selections are line style backgrounds and secondary carets are 2 px
  bars at `getLocationAtOffset` in the palette's caret color, so both follow
  the widget's measured glyphs on any script. Secondary carets in hidden lines
  are not painted. A block's part past a short line's end, and each row's caret
  there, are painted from the line end's measured position at the fractional
  advance.
- Line styles come from the pane's `HighlightComposer`: the syntax tokens
  (`setSyntax`), the find matches (`showMatches`), the bracket pair
  (`showBrackets`), the word occurrences (`showOccurrences`) and the secondary
  selections. A run takes its `TokenRole`'s palette color, bold, italic and
  underline; on the same chars the background of a secondary selection wins
  over a bracket, a bracket over a find match, and a find match over an
  occurrence. `redrawHighlights` repaints after new tokens arrive.
- Show Whitespace paints a dot in each space and an arrow across each tab, and
  Show Line Endings the break's kind (`CRLF`, `LF`, `CR`) after each line's
  last row, in the palette's whitespace color, which a theme switch recolors.
  Positions come from `getLocationAtOffset` at each run's ends.
- `setBusy` shows `SWT.CURSOR_APPSTARTING` over the text and gutter while a
  whole-text operation computes and applies.
- Each paint that shows another display range reports it through
  `EditorInput.viewportChanged`.

### Primary caret

The primary caret is an SWT `Caret` with a 2 px image one line high, rebuilt on
a font change, a zoom change and a palette change and disposed with the widget.
The platform composites the image over the text, so `CaretInk` picks the image
color that shows the caret color over the current line:

| Platform | Operator |
|---|---|
| GTK 3, cocoa | Difference |
| win32 | XOR (assumed, not yet checked on Windows) |
| GTK 4 | Over |

Under difference, a channel the background cannot reach shows the nearest
value: the dark palette's amber shows red 0xdd for 0xf3.

## Fonts and widget caches

Every resize and font change sends the columns that fit the text area for word
wrap. `setFont` replaces the font in place and measures the advance again.

Font, tabs, margins and the text and background colors each make the widget
reset its per-line cache, O(rows) through a `TreeSet` (about 1 s at 1M rows):
the pane sets them before `setContent`, and a palette change sets only colors
that differ. A theme switch therefore costs about 1 s per view of a 1M-line
document.

## Completion list

The completion list (`CompletionPopup`) is a shell that takes no focus, below
the primary caret, created on show and disposed on hide; the editor keeps the
keys, which the controller routes to the list. A double click chooses a word.
When the editor loses focus to another control, the list closes on the next UI
turn.

## Overview strip and scroll bar

The `StyledText` has no native vertical bar, so the strip sits between the text
and a `Slider`. The slider counts rows (one widget line each): maximum the row
count, thumb the rows that fit, selection the top row. It follows the widget on
each paint and resize and after the strip or the wheel scroll, and its own
moves set the top row and hand the focus back to the text. The wheel scrolls
the text by its event count in `MouseVerticalWheel` and consumes the event.
Page and caret moves scroll the widget, folding and word wrap change its rows,
and the next paint sets the slider.

`OverviewStrip` (14 px) paints the last `OverviewMarks`: a HyperSearch mark per
pixel row holding a match, a find mark over it, and a bookmark tick at the left
edge, then a line down its left edge and lines at the first and last visible
line, in the palette's gutter, line-number, `overviewFind`, `overviewSearch`
and caret colors; a palette change recolors it. A commented bookmark is marked
like any other. A click or drag scrolls the line under the pointer to the
middle of the view. Each resize reports the height, 0 while View > Overview
Strip hides it. Split views have one strip each.
