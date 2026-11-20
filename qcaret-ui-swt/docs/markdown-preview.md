# Markdown preview

The Basic preview pane and its place in a tab, in `ui.swt.markdown` and
`ui.swt` of [qcaret-ui-swt](../README.md). The controller side (modes, renders,
scroll mapping, links, images) is in
[Documents](../../qcaret-app/docs/documents.md#markdown-preview).

## Tab frame

A tab's control is a horizontal `SashForm`, the frame, holding the editors'
`SashForm` and, from the first mode other than Source, a `MarkdownPane` on
their right. `PreviewSlot` is the tab's `MarkdownPreviewView`: Source maximizes
the editors, Split shows both at equal weights the first time, Preview
maximizes the pane. A mode change moves the focus to the control that shows.
An editor split stays inside the editors' sash, so the preview stands right of
both views.

`MarkdownSwitch` adds a separator and three radio items, Source, Split and
Preview, at the toolbar's end while the selected tab is a Markdown tab, and
removes them otherwise. `BrowserPreview.unavailable` is the one check of the
Browser renderer; it gives a reason in this version, so View > Markdown
Preview > Browser is disabled with that reason as its tool tip.

## Pane

`MarkdownPane` is a read-only, wrapping `StyledText` with a notice row above it
for the size notice and its Render button. Nothing is styled ahead of
painting:

| Listener | Class | Per painted line |
|---|---|---|
| `LineStyleListener` | `MarkdownStyler` | Fonts and colors from `MarkdownSpans`, code lines in the monospace family with token colors, the bold header row, glyph metrics for each image char, quote and list indent, bullet, number or checkbox, a measured table's indent and tab stops |
| `LineBackgroundListener` | `MarkdownStyler` | The code shade on code and front matter lines |
| `PaintObjectListener` | `MarkdownPainter` | An image, or a box with the file name for one that did not decode |
| `Paint` | `MarkdownPainter` | Quote bars (an alert's in its kind's color), rules, the line under level 1 and 2 headings, table borders |

UI-thread work per new render is `setText` of the render's text plus the
scroll restore; the styling follows the lines in view. Table columns are the
only work that grows with the document: `TableColumns` measures cells with a
`GC` through `MarkdownTableLayout` in UI turns of at most 200 cells and 8 ms,
checked every 16 cells, tables in view first, and restyles a table's lines when
it is measured. A table row's spaces show as no-break spaces so a cell does not
wrap; Copy, from the keys bound to it and from the context menu, puts the
render's own text on the clipboard.

The pane reports a new top line from `Paint`; `scrollToLine` records the line
it set, so the controller's own scroll is not reported back. The text has no
top margin: `getTopIndex` returns the first fully visible line, and a line
scrolled under a top margin would read as the next one.

Fonts (`MarkdownFonts`: body, bold, italic, six heading sizes, a smaller size
for subscript and superscript, and the editor font's family for code) belong
to the pane and are disposed with it. `setPalette` recolors in place on a theme
switch.

## Images

`SwtImageDecoder` decodes on the controller's worker with `ImageLoader`, first
frame only, and scales with `ImageData.scaledTo` to the requested size, no wider
than the pane's text area when the decoder was made. `PaneImages` creates each
`Image` on the UI thread when it arrives and disposes all of them on new
content and with the pane; a result for a request of earlier content is
dropped. Until an image arrives its char reserves the requested size, or 32 by
32 pixels without one; a failed decode reserves 160 by 40 for its placeholder.
