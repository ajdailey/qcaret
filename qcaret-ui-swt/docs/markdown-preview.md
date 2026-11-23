# Markdown preview

The Basic and Browser preview panes and their place in a tab, in
`ui.swt.markdown` and `ui.swt` of [qcaret-ui-swt](../README.md). The controller side (modes, renders,
scroll mapping, links, images) is in
[Documents](../../qcaret-app/docs/documents.md#markdown-preview).

## Tab frame

A tab's control is a horizontal `SashForm`, the frame, holding the editors'
`SashForm` and, from the first mode other than Source, a preview pane on their
right: a `BrowserPane` while the content carries HTML, else a `MarkdownPane`.
`PreviewSlot` is the tab's `MarkdownPreviewView`: Source maximizes the
editors, Split shows both at equal weights the first time, Preview maximizes
the pane and hides the editors: `SashForm` moves a control it does not show to
a 0 by 0 box off the frame, and GTK 4 still paints that box's children over the
pane. Entering Preview moves the focus from the editors to the pane, and leaving
it moves the focus from the pane to the focused editor. In
Preview, focusing a view (`EditorView.focus`, as closing the find bar does) or
selecting the tab focuses the pane: the hidden editors would take keys unseen. An editor
split stays inside the editors' sash, so the preview stands right of both
views. A renderer change swaps the panes and keeps the sash position; the
`MarkdownPane` stays hidden in the frame, a `BrowserPane` is disposed.

`MarkdownSwitch` adds a separator and three radio items, Source, Split and
Preview, at the toolbar's end while the selected tab is a Markdown tab, and
removes them otherwise. A click sends the item it unchecks a `Selection` event
before it checks the clicked item and sends that one its own (SWT 3.135.0 on
GTK, win32 and cocoa), so only the checked item's event runs the command and
syncs the items; a sync on the first event unchecks the clicked item.

`MarkdownToolItems` follows the switch and comes and goes with it: one
`SWT.DROP_DOWN` item, Markdown actions, whose button and arrow both open a menu
of `MarkdownToolItems.COMMANDS`: Basic and Browser as radio items, Sync
Scrolling and Outline as check items, Export as HTML and Print Rendered
Markdown. The menu is built when it opens, so each item takes its checked and
enabled state from `CommandStates` then; a disabled item gives its reason as
the menu bar does, and a radio item runs its command only on the event that
checks it. Icons follow `MenuIcons.decorate`: Export as HTML and Print
Rendered Markdown show `export-html` and `print`, the radio and check items
none. The item carries itself as a `ToolMenu`, which names it for
`ToolBarNames` and lets `ToolbarOverflow` list it as a cascade.

## Browser availability

`BrowserPreview.unavailable` is the one check of the Browser renderer:

| Cause | Reason |
|---|---|
| GTK 4 (`PlatformTraits.opensBrowser` false) | `MARKDOWN_BROWSER_GTK4`: WebKitGTK does not start under GTK 4 |
| A preview's `Browser` failed to create, a call on it threw `SWTException` or `SWTError`, or its start or load bound expired (Browser pane) | `MARKDOWN_BROWSER_FAILED` with the error's message, or with `MARKDOWN_BROWSER_TIMEOUT` ("the page did not load within 60 s" or "15 s") for a bound, for the rest of the Display's life |

`PreviewSlot` catches the failure, shows the content in the `MarkdownPane` and
calls `browserFailed`; the controller posts "Browser preview unavailable;
showing Basic" and renders Basic. View > Markdown View > Browser is disabled
with the reason as its tool tip, or, where
`PlatformTraits.showsMenuToolTips` is false (GTK 4, where SWT drops menu tool
tips), in its label. For tests, `MainWindow.setPreviewBrowserFactory` replaces
the `GuideWindow.BrowserFactory` and `setPreviewBrowserDispatcher` the
dispatcher that runs the panes' timers and bounds.

## Browser pane

`BrowserPane` shows `MarkdownHtml.page` through `Browser.setText(page, false)`.
The controller builds the page, the fragment inside `MarkdownPage.page` with
the palette's stylesheet, on a worker for each render, theme switch and zoom
change, so the
pane's UI-thread work is the `setText` call.

- JavaScript is off, and the page's content security policy, `default-src
  'none'; style-src 'unsafe-inline'; img-src data:`, loads nothing else. Local
  images arrive embedded as `data:` URIs.
- `setText` must pass `trusted` false: with true, SWT on GTK and cocoa loads
  the page with a `file:///` base, which lets it read local files.
- `MarkdownPage.page` renames each `<meta` tag in the fragment to `<wbr`
  (`InertMeta`), so a raw-HTML meta refresh does not navigate; the content
  policy does not cover it, and Edge requests the target of a canceled
  navigation (measured on CI, windows-x86_64), so a clicked link on win32
  reaches its server once from Edge as well as from the system browser.
- Every navigation is canceled except the page load and the pane's own
  `about:blank#id` scroll, matched on the text before `#` and the
  percent-decoded fragment. `LinkGestures` sends a canceled navigation within
  1 s after a gesture (primary-button release, Enter or Space on the browser)
  to `linkFollowed`, once per gesture; any other one is dropped. Each followed
  navigation is logged at debug with its gesture's source and age, and each
  dropped one with the time since the last gesture ended, or `none`.
- Where `PlatformTraits.followsLinksWithoutGesture` holds (win32), every
  canceled top-frame navigation goes to `linkFollowed` with no gesture and is
  logged as followed `with no gesture`. SWT's Edge with JavaScript off reports
  no mouse or key event to the `Browser`, so on the gesture rule every link
  click and Enter on win32 would be dropped (measured on CI, windows-x86_64:
  `changing` with no `MouseUp` in 42 clicks, no `KeyDown` for Enter). The
  page starts no navigation by itself: JavaScript is off, `InertMeta` makes a
  meta refresh inert, and the content policy loads no frame.
- A link to a local file carries `qcaret-file:` in place of `file:`
  (`MarkdownLinks.previewFileUrl`), and `linkFollowed` maps it back: WebKitGTK
  cancels a `file:` navigation from the `about:blank` page before `changing`,
  and passes other schemes to it (measured, WebKitGTK 2.52.6). A link whose
  URL commonmark's sanitizer refuses, such as `javascript:` or `smb:`, has no
  `href`.
- WebKitGTK loads `about:blank` without a `changing` event, so a raw-HTML link
  to it replaces the page with an empty one. Where
  `PlatformTraits.blankLinksReplacePage` holds (GTK), a `changed` to
  `about:blank` that is not the pane's own load marks the page replaced; its
  `completed` loads the shown content's page again, once, and neither meets a
  bound nor scrolls. A `completed` that met the stray page's state would
  scroll and cancel the pane's next load.
- `scrollToLine` maps a rendered line to its source line and the last element
  starting at or above it (`MarkdownHtml.idAt`), and `scrollToAnchor` takes an
  id; both navigate to `about:blank#id` once the page loaded, and a new page
  returns to the last id. Where `PlatformTraits.scrollsBrowserToFragments` is
  false, neither scrolls:
  - win32: SWT's Edge shows `setText` content from a temporary file through
    `document.write`. An anchor link scrolls inside WebView2 without a
    navigation event.
  - cocoa: SWT's `setUrl` escapes `#` as `%23` (CI reported
    `about:blank%23qcaret-line-150`), so the URL has no fragment and WebKit
    loads it as another page. The pane cancels that form.
- The browser's own scroll is not reported: with JavaScript off the page
  position cannot be read, so Split follows the editor and not the reverse,
  and only while Sync Scrolling is on.
- Each `setText` arms a start bound of 60 s; the page's own navigation
  (`changing` on `about:blank`) replaces it with a load bound of 15 s, and
  `completed` meets either. Expiry takes the failure path of a throwing call.
  The start bound covers the WebView2 start of a new browser control on win32,
  which delays the navigation, not the load after it. A failure raised while
  the pane's own browser call is on the stack runs after that call returns,
  since on cocoa an SWT timer runs inside the nested run loop of a native call.

| Platform | Engine | `setText(page, false)` base | `file:` image from the page |
|---|---|---|---|
| GTK 3 | WebKitGTK 4.1 | `about:blank` | Not loaded with or without the policy (measured, WebKitGTK 2.52.6) |
| cocoa | WebKit | `about:blank`; SWT ignores `file:` navigations from untrusted text | Loaded without the policy, blocked by the policy (CI, `aa1db2c`) |
| win32 | WebView2 | A temporary `file:` page written with `document.write` | Loaded without the policy, blocked by the policy (CI, `aa1db2c`) |

## Pane

`MarkdownPane` is a read-only, wrapping `StyledText` with a notice row above it
for the size notice and its Render button. The `StyledText` holds the display
text of a `MarkdownDisplayMap` ([Tables](#tables)). The text's `GridData` has zero
width and height hints, so a layout gives it the space left and never calls
its `computeSize`, which measures every line of a wrapping `StyledText`. With
size hints unset, the flushed frame layout of a renderer switch held the UI
thread 3.2 s (GTK 3) and 4.6 s (Windows x64) at 1 MiB, and 71-89 s at 950K
chars on macOS. Nothing is styled ahead of painting:

| Listener | Class | Per painted line |
|---|---|---|
| `LineStyleListener` | `MarkdownStyler` | Fonts and colors from `MarkdownSpans`, code lines in the monospace family with token colors and a 12 px indent, the bold header row, glyph metrics for each image char, each row padding char and each padded separator line's char, quote and list indent, bullet, number or checkbox, a laid-out table row's indent and tab stops |
| `LineBackgroundListener` | `MarkdownStyler` | The code shade on code and front matter lines and on a laid-out table's header row |
| `PaintObjectListener` | `MarkdownPainter` | An image, or a box with the file name for one that did not decode |
| `Paint` | `MarkdownPainter` | Code block boxes, quote bars (an alert's in its kind's color), rules, the line under level 1 and 2 headings, table borders; the pane background over the header row's shade outside the table |

UI-thread work per new render is the separator spacing and display map
(`BlockSpacing.of`, `MarkdownDisplayMap`, each one pass over the text), the
table keys, the text change (`TextDelta`), the scroll restore and styling of
the lines in view. The wrapping `StyledText` measures a
line's height once, and both ways of changing its text measure lines:

| Call | Lines measured |
|---|---|
| `setText`, then the scroll restore | Every line above the top line |
| One `replaceTextRange` of the span between the common prefix and suffix | The lines between the top line and the far end of the span; the rest keep their height |

`TextDelta` takes the call that measures fewer. At 1 MiB with the top at the
end, a render after typing near the end took 1.9 s with `setText` and 6.5 ms
with the replace (median of 10, Xvfb, GTK 3); as one replace from the empty
text, the first show of 951 KB took 3.3 s, so a first show is a `setText`.
Table layout runs in its own turns ([Tables](#tables)).

After a replace the pane styles the lines in view again with `redrawRange`,
since a span change can restyle text the replace kept. After a `setText` it
does not: `setText` styles every line, and `redrawRange` would measure each
line in view a second time (3-12 ms, measured, Xvfb, GTK 3). A first show
from an empty pane is a `setText`, and the widget's measuring of the lines in
view takes 7-11 ms of it on a 1,194 by 900 px pane (README.md and 2,000-row
tables, measured as above). Do not split that change by showing the lines in
view first and appending the rest in a later turn: `replaceTextRange` measures
each line it adds below the top line, so the append took 35-440 ms.

The pane reports a new top line from `Paint` as a rendered line;
`scrollToLine` and `selectRendered` record the line they leave at the top, so
the controller's own scroll, and a table layout that keeps the top line, is
not reported back. View > Markdown View > Sync Scrolling
(`markdown.syncScroll`) is the controller's rule: while it is off, the
controller scrolls neither side of Split for the other, and the views are
unchanged ([Documents](../../qcaret-app/docs/documents.md#markdown-preview)). `selectRendered` (a find match in Preview) sets
the selection, which scrolls it into view, and leaves the focus where it is;
the `BrowserPane` has no selection and only scrolls. The text has 24 px side
margins, 2 px between lines, and no top margin: `getTopIndex` returns the first
fully visible line, and a line scrolled under a top margin would read as the
next one. Space between blocks is in the separator lines ([Block
spacing](#block-spacing)), so it adds no line and moves no line index.

Fonts (`MarkdownFonts`: body, bold, italic, six heading sizes, a smaller size
for subscript and superscript, and the editor font's family for code, all
scaled by the zoom) belong to the pane and are disposed with it. `setPalette`
recolors in place on a theme switch.

## Block spacing

`BlockSpacing` (`app.editor`) gives an empty separator line between two blocks
extra pixels; `MarkdownDisplayMap` shows each such line as one zero-width
`TableWrap.PAD`, and `PadGlyphs` sets that char's glyph metrics to the
body font's ascent and descent plus the extra pixels. A block with no separator
beside it, such as a code block on the first line or inside a tight list item,
gets no space on that side.

| Separator | Pixels added | Browser |
|---|---|---|
| Before a heading | 4 at the bottom | 24 px margin above |
| After a level 1 or 2 heading | 6 at the top; the heading's rule is drawn below them | 0.3 em padding above the border |
| Before and after a code block | 8 at the bottom and at the top, inside the box | 8 px padding |

Each separator is one body line (21 px with the line spacing) before its
extra pixels. A line never gets shorter than the widget font's height, so
spacing Browser sets below that, such as 16 px under a heading, stays one
body line.

`MarkdownPainter` draws a code block as a box from the line's text start to the
right margin: the shade over its padding in the separators, the pane
background over the code lines' shade outside the box and at its 4 px corners,
and a 1 px border in the line number color. Code lines are indented 12 px.
Do not use `StyledText.setLineSpacingProvider` for this: in SWT 3.135.0 each
line laid out with a spacing other than its cached layout's, in view or not,
resets that line's cache and redraws the whole widget.

## Zoom

`MarkdownPane.setZoom` takes `markdown.previewZoom` in percent; `PreviewSlot`
passes it to a pane created later. The pane:

1. Builds `MarkdownFonts` again at the size and gives the widget the body font
   (the system font at 100): a line is never shorter than the widget font's
   height, so a smaller body font alone would leave the old line height.
   `setFont` drops the widget's line heights and cached layouts.
2. Calls `TableColumns.fontsChanged`, which drops every table's cached cell
   and word widths, then `TableColumns.reset` with the content and map shown,
   so each table is measured in the new fonts and laid out again under the
   rules in [Tables](#tables): a table far from the view waits, and its rows
   keep the old layout until it nears the view.
3. Scrolls the top rendered line back to the top, then disposes the replaced
   fonts. `PadGlyphs` measures the body font again on its next style.

Images keep their size. Print builds its own fonts and does not zoom. The
Browser pane's page carries the size in its stylesheet: `MarkdownPage.stylesheet`
scales every pixel length (body font, margins, padding, borders at least 1 px,
the 980 px content width), and heading and code sizes follow the body font in
`em` and percent. The controller builds the page
([Documents](../../qcaret-app/docs/documents.md#markdown-preview)), and
JavaScript stays off. `PreviewWheel` sends a wheel turn with Ctrl (Command
on macOS) over the text or the browser control to
`ApplicationController.wheelZoom`; whether a `Browser` reports the wheel on
each platform is not yet checked.

## Tables

`MarkdownDisplayMap` (`app.editor`) is the text the pane shows: the render's
text with each laid-out table row replaced by its display lines and each padded
separator holding one `TableWrap.PAD`. It maps
offsets and lines both ways; a display char a row adds (a tab, a line break,
the padding char) maps to the rendered offset after the piece before it. The
controller sees rendered offsets and lines only, and every widget offset or
line crosses the map:

| Call site | Map |
|---|---|
| `MarkdownStyler.style`, `background` | Display line to rendered line; each display piece takes its rendered chars' styles, shifted to display offsets |
| `MarkdownPainter.paint` | Rendered lines of code blocks, quotes, rules, headings and table rows to display lines |
| `MarkdownPainter.paintObject`, click, link cursor | Display offset to rendered offset |
| Copy, from the keys bound to it and the context menu | The selection to the rendered range from its first to its last copied char |
| `selectRendered`, `showImage` | Rendered offsets to display offsets; a match inside one cell across its break selects its part before the break (`displayRange`) |
| `scrollToLine`; the top line `previewScrolled` reports | Rendered line to its first display line, and back |

`TableColumns` lays out each table in UI turns of at most 200 cells and 8 ms,
tables in view first; `TableTurns` schedules the turns and holds the waiting
tables. A turn takes another step while twice the last step's time fits 1 ms
before that limit, and a layout step starts a turn of its own:

1. `MarkdownTableLayout` measures each cell through `SpanWidths`, each span in
   the font `MarkdownStyler` draws it with (code and kbd monospace, bold,
   italic).
2. A table wider than the pane's text width less its quote and list indent has
   its words and the spaces between them measured (`TableWords`) and is fitted
   with `MarkdownTableLayout.fit(words, width, true)`: each column keeps its
   widest word and shares the rest by how far its widest cell exceeds that
   word. Widest words that do not fit narrow in proportion, so a table is never
   wider than the pane.
3. `TableWrap` breaks a cell wider than its column at its spaces, a word wider
   than its column at a char count in proportion to its width, and gives a row
   as many display lines as its tallest cell. Display line `j` holds each
   cell's `j`th segment, tab-separated, with the indent and tab stops that
   start each segment in its column (`TableRowStyle`). `RowSteps` replaces the
   table's lines in the map and the widget through `replaceTextRange`, 16 rows
   in the pane's first step at each position and then as many as the last
   step's rate fits in 4 ms, from 4 to 64. Steps starting above the top line,
   in view and below the view each keep their own rate, carried over between
   tables and renders.

A table starting more than one view height below the rendered lines in view, or
above them and never laid out, is measured, its words included, but waits for
the fit and step 3. A table
laid out before a width change is laid out again while it ends within one view
height above the view. Each paint passes the lines in view to
`TableTurns.setView`, which queues the waiting tables near them. Outline go-to, Sync Scrolling and Find move the view first, and
the layout that follows keeps the top line and the selection.

A width change refits each measured table from its cached widths and lays out
again the tables whose column edges change. A new render keeps the layout and
rows of a table whose text, spans and alignments equal one shown before, so an
edit in Split does not lay out an unchanged table again. Across a layout the
top line keeps its rendered line and its pixel offset, and the selection its
rendered text. The offset is restored after each replace: on GTK 4 a replace
below the top line moves the view a few pixels without changing the top index
(measured: 4 px after an Outline go-to).

Do not show the rows with `setText`. `replaceTextRange` lays out the new rows
and each line between the top line and the rows whose height the widget has
not measured; `setText` lays out nothing at once but drops every line height,
and the widget then measures the whole text again in its 50 ms idle steps
(README.md show to idle 644 ms against 138 ms, measured, Xvfb, GTK 3). A
replace lays out the unmeasured lines between the top line and its rows: a
table far below the view took single replaces of up to 15 ms on README.md, and
a table just above the view after a jump took 3-12 ms for every replace, one
row or several (measured, as above). A replace also has a cost that does not
grow with its rows, so 2 ms steps took 2,887 ms of turns for a 2,000-row table
and 4 ms steps 2,665 ms, with half the collector pauses (measured, as above).
A single replace can still take 6-33 ms now and again (widget work, the
collector included), so a turn can pass 8 ms.

`MarkdownDisplayMap.replace` edits the display text in place and copies only
the row index, so a step does not copy the whole display text.

Cells have 13 px of padding left and right. A row's first and last display
lines end with a zero-width space, `TableWrap.PAD`, whose glyph metrics
(`PadGlyphs`) add 6 px above the body font's ascent and 4 px below its
descent; with the 2 px line spacing that is 6 px above and below the row's text. At a line's start,
the char moves the tab after it off its stop (GTK 3), so it ends the line.
Borders follow the display lines' pixel positions.

## Images

`SwtImageDecoder` decodes on the controller's worker with `ImageLoader`, first
frame only, and scales with `ImageData.scaledTo` to the requested size, no wider
than the pane's text area when the decoder was made. `PaneImages` creates each
`Image` on the UI thread when it arrives and disposes all of them on new
content and with the pane; a result for a request of earlier content is
dropped. Until an image arrives its char reserves the requested size, or 32 by
32 pixels without one; a failed decode reserves 160 by 40 for its placeholder.

## Outline

A heading chosen in the [Outline dock](dialogs-and-docks.md#outline-dock)
scrolls the shown pane through `scrollToLine`; the go-to is in
[Documents](../../qcaret-app/docs/documents.md#outline).

## Printing

File > Print Rendered Markdown prints Basic's styles and table layout without
block spacing or code boxes. `MarkdownStyler.apply`
maps a span's bits to a `TextStyle` for both the pane's `StyleRange` and the
printer's `TextLayout`, so a style change reaches both. The printing classes
are in [Printing](printing.md#rendered-markdown).
