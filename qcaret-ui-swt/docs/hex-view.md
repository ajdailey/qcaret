# Hex view

The SWT side of the hex views in [qcaret-ui-swt](../README.md): `HexPane`,
`HexCanvas`, `HexPainter`, `HexGestures`, `HexGrid` and `HexFindBar` in
`ui.swt.hex`; `HexSlot`, `HexWindow`, `WindowHexViews` and `BinaryDialog` in
`ui.swt`. The controller, modes, sync, copy and find are in the app's
[Documents](../../qcaret-app/docs/documents.md#hex-view).

## Pane and window

`HexPane` implements `HexView`: a notice bar, the rows (`HexCanvas`), the find
bar and a status line. `WindowHexViews` creates one per tab in the tab frame
and one per Hex Viewer window, gives each the window's palette, font and keymap
as they change, and adds the bars to `ChromeColors`. Disposing a pane calls
`HexInput.detach`, which ignores any pane but the attached one, so a window
replacement's late disposal of the old pane leaves the new one attached.

| Mode | `HexSlot` |
|---|---|
| Text | Pane hidden; the frame's other panes shown again; editors maximized if the pane was |
| Split | Other panes hidden; pane right of the editors, at a row's width while that leaves the editors a third of the frame, else half |
| Hex | Other panes hidden; pane maximized; editors hidden, since GTK 4 paints the children of a control `SashForm` moves out of view |

The controller sets the Markdown mode to Source before Split or Hex shows, and
Text after a preview shows, so `HexSlot` and `PreviewSlot` never show their
panes together. In Hex, `EditorTab` focuses the pane where it would focus an
editor.

`HexWindow` is a modeless `SHELL_TRIM` shell owned by the main window, opened as
wide as a row and closed with its owner. Its pane runs the window keys itself
(`HexGestures`); in a main window the menu runs them and
`HexCommands.runFocused` routes them to the focused pane. On cocoa the window
carries the main menus (`ToolMenuBars`), and a menu accelerator fires before
the key reaches the canvas, so `HexWindow` passes `Dialogs.shell` a
`ToolCommands` that runs `HexPane.WINDOW_COMMANDS` (Copy, Select All, Find,
Replace, Find Next and Previous, Go to Line, Close) on the pane; their items
stay enabled. Every other item acts on the main window. On win32 and GTK the
window has no menu bar and its own shell's accelerators only, so the keys reach
the canvas.

`BinaryDialog` answers Open in Hex Viewer (the default button), Open as Text or
Cancel, once; closing it answers Cancel. The too-large refusal is an
`ErrorDialog` with an Open in Hex Viewer button.

## Painting

`HexCanvas` measures the advance as the width of `ColumnMetrics.sample()` over
its length, kept fractional, and `HexGrid` puts column `c` at
`round(c * advance)`. `HexPainter` draws each run of cells of one color within
a group of 8 bytes as one `drawString` at its first cell's x. The glyphs of a
run advance by the font's own advance, the one the grid is measured from, so
each glyph lands within half a pixel of the cell the selection and the caret
are drawn at. One `drawString` per cell takes about four times the 8 ms a UI
turn may take for 60 full rows on GTK 3; `HexPaintTimeTest`
(`-Dqcaret.hex.paint=<file>`) times a paint. `HexCanvasSurfaceTest` checks
every column against the extent of a run of zeros, also at
`-Dswt.autoScale=150`.

- A row `HexInput.row` does not have yet paints its offset only; the read's
  end calls `redraw`.
- The `.` of a byte outside 0x20-0x7E takes `ControlBoxes.ink` of the text over
  its ground; a replacement byte for an unencodable char takes the
  `TokenRole.INVALID` color.
- The selection fills both columns. The caret outlines its cell in its column,
  2 px in the caret color while focused, and an empty selection marks the same
  byte in the other column with the occurrence background.
- While dimmed, text is drawn halfway to the background.
- Colors are created on a palette change; a paint creates no SWT resource.

## Input

`HexGestures` maps the keymap's editor bindings of the caret moves to `HexKey`
(line start and end to the row's, document start and end to the source's, the
select variants extending), so the macOS keys and the user's overrides apply
with no platform check. Tab switches the column (the `Traverse` is canceled so
the key arrives), and the editor bindings of Copy, Select All and Esc go to the
controller. A press, Shift+press and drag
hit-test through `HexGrid.offsetAt`; a drag held above or below the rows
scrolls a row every 50 ms on a `UiDispatcher` timer, stopped on release and
focus loss. The context menu is built on its first `MenuDetect`.

In a Hex Viewer, the find field's `KeyDown` runs the window keys of Find Next
and Previous, Go to Line and Close (`HexGestures.findFieldKey`) and cancels the
key, so it is not typed; Find and Replace stay with the field.

## Narrow widths

`HexBarLayout` lays out the find bar: its controls left to right, starting a
new row where the next does not fit, the field narrowed to the bar where it is
wider; then the find message on its own row, wrapped to the bar's width and
left out while empty. The status line is a wrapping `Label`, and
`HexPane.showStatus` lays the pane out again when its line count changes. A Hex
Viewer window's minimum width is at least `HexPane.minimumWidth`, the widest
find bar control (the field), since GTK sets no native minimum. A tab's pane
has no minimum of its own, so there the field narrows.

While the canvas is narrower than a full row, `HexCanvas` shows a horizontal
`Slider` under it, and `HexGrid` takes the scroll as `left`: every x it returns
or hit-tests is shifted by it, so painting and clicks need no other change. A
selection change scrolls the caret's cell in its column into view. Widening
the canvas past a row hides the slider and scrolls back to the left edge.
