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
the pane. A mode change moves the focus to the control that shows. In
Preview, focusing a view (`EditorView.focus`, as closing the find bar does) or
selecting the tab focuses the pane: the hidden editors would take keys unseen. An editor
split stays inside the editors' sash, so the preview stands right of both
views. A renderer change swaps the panes and keeps the sash position; the
`MarkdownPane` stays hidden in the frame, a `BrowserPane` is disposed.

`MarkdownSwitch` adds a separator and three radio items, Source, Split and
Preview, at the toolbar's end while the selected tab is a Markdown tab, and
removes them otherwise.

## Browser availability

`BrowserPreview.unavailable` is the one check of the Browser renderer:

| Cause | Reason |
|---|---|
| GTK 4 (`PlatformTraits.opensBrowser` false) | WebKitGTK does not start under GTK 4 |
| A preview's `Browser` failed to create, a call on it threw `SWTException` or `SWTError`, or its start or load bound expired (Browser pane) | The error's message, or `MARKDOWN_BROWSER_TIMEOUT` ("the page did not load within 60 s" or "15 s") for a bound, for the rest of the Display's life |

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
the palette's stylesheet, on a worker for each render and theme switch, so the
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
  percent-decoded fragment. A canceled navigation within 1 s of a
  primary-button release, Enter or Space on the browser goes to
  `linkFollowed`; any other one is dropped and logged at debug.
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
  position cannot be read, so Split follows the editor and not the reverse.
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

The pane reports a new top line from `Paint`; `scrollToLine` and
`selectRendered` record the line they leave at the top, so the controller's own
scroll is not reported back. `selectRendered` (a find match in Preview) sets
the selection, which scrolls it into view, and leaves the focus where it is;
the `BrowserPane` has no selection and only scrolls. The text has no
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

## Outline

A heading chosen in the [Outline dock](dialogs-and-docks.md#outline-dock)
scrolls the shown pane through `scrollToLine`; the go-to is in
[Documents](../../qcaret-app/docs/documents.md#outline).

## Printing

File > Print Rendered Markdown prints Basic's rendering. `MarkdownStyler.apply`
maps a span's bits to a `TextStyle` for both the pane's `StyleRange` and the
printer's `TextLayout`, so a style change reaches both. The printing classes
are in [Printing](printing.md#rendered-markdown).
