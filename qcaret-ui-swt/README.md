# qcaret-ui-swt

The SWT views: the main window with its tabs and menus, the editor
(`ui.swt.editor`), dialogs, the theme service and the UI dispatcher. It
depends on `qcaret-app`, `qcaret-core` and the build host's SWT fragment, and
uses public SWT API only. The package `ui.swt.search` holds the HyperSearch
results dock and the replace windows.

## Main window

`MainWindow` holds the menu bar, a horizontal `SashForm` with the workspace
browser left of a vertical `SashForm`, which holds a `CTabFolder` (one
tab per document) over a horizontal `SashForm` holding the HyperSearch results
dock and the Bookmarks list dock side by side, the find bar, the `ProgressRow` rows
and a status bar.
A hidden dock is an invisible control the sash leaves out; with both hidden
the folder is maximized, and with the browser hidden the vertical sash is. A
tab's control is a
`SashForm` holding one `EditorPane`'s `control()`, or two after a split (`SWT.HORIZONTAL` side by side,
`SWT.VERTICAL` one above the other). The pane that took the focus last is the
tab's focused view; a pane's `FocusIn` reports it to the controller, and a
view the controller focuses becomes it without waiting for the event. `attach` connects the
controller: menu items run `ApplicationController.execute`, and a
window-system close request runs `closeWindow`.

Tab move: a tab drag uses the strip's `MouseDown` and `MouseUp`, no
`DragSource`. A release on another tab reorders. A release off the tabs asks
`Display.getCursorControl` (through a `PointerProbe` tests replace) for the
control under the point: another main window's control moves the tab there,
before the tab under the point or last; no QCaret control and a point outside
the source shell opens a new window with its corner at the point, which GTK 4
and Wayland place themselves. The shell's data names its `MainWindow`. A moved
tab is a new `Tab` with new `EditorPane`s over the same projection and
selections; widgets do not cross shells. Public SWT API only.

`MainMenuBar` builds one menu per `CommandGroup` from `MenuTable` and applies
`CommandStates`; it reads no controller getter for item state. Each item shows
every key of its command from the `Keymap`; only the first window binding
becomes an accelerator (`SwtKeys.accelerator`, `SWT.MOD1` for Ctrl or
Command), so editor keys never fire from another control. A disabled item's
accelerator does not fire on win32 or GTK, so enablement syncs on every
`documentStateChanged` and on shell activation, not only on a menu's `Show`;
showing a menu or running one of its items syncs that menu's checks. The
Encoding menu holds Reopen with Encoding and Save with Encoding cascades: the
common charsets from the start, and an Other cascade disabled until
`EncodingChoices` arrives from a worker, filled again when a keymap change
rebuilds the menus. The
Window menu lists the windows, when there are several, and the open documents,
File > Recent Files the recent
files above Clear Recent Files and File > Recently Closed the closed files above
Clear Recently Closed, each rebuilt when its menu shows; a path's
`&` is doubled. Reopen with Encoding starts with Automatic Detection, which
calls `ApplicationController.reopenWithDetection`. A Display key
filter runs window bindings that are not accelerators (Ctrl plus keypad +),
when enabled.

Help > Keyboard Shortcuts opens `ShortcutsWindow`, a modeless table of every
bound command by menu with a filter field, as QLogg's; Copy puts the filtered
rows on the clipboard as `Keymap.reference(filter)` writes them. A second
request brings the open window forward. Edit > Go to Line opens `GoToDialog`,
a modal prompt without a nested event loop.

Edit > Preferences (the application menu on macOS) opens `PreferencesDialog`,
a modeless window with one tab per `PreferencesForm.Group`, over
`PreferencesInput`. OK and Apply pass the fields to the controller; a refused
field shows its message above the buttons, selects its tab and keeps the
dialog open. Restore Defaults fills every tab and applies nothing until OK or
Apply. Clear History on the Search and Workspace tab asks through
`ClearHistoryDialog`, then clears the saved histories, HyperSearch's last
run and the session's retained searches at once; Cancel on the dialog does not restore them. The Keys tab lists every command; the key field records one stroke, Add
refuses a stroke that types or that another command holds and names that
command, Remove and Default Keys edit the selected command. `setKeymap`
rebuilds the menu bar and hands the keymap to every `EditorPane` and an open
`ShortcutsWindow`.

`RecoveryDialog` lists a dead instance's buffers over `RecoveryInput`: file, write
time and how the original stands, all rows selected. Restore and Discard act on
the selected rows; Later and closing the dialog keep the rest for the next
start. It is modeless and closes when no row is left. `EditorPane.scrollToRow`
waits for the first resize when the view has no size yet, which is every hidden
tab of a restored session.

`MainWindow.rememberSize` gives the resizable windows (`preferences`,
`shortcuts`, `palette`, `quickOpen`, `replace`, `report`, `compare`, `guide`) their last
size, within the monitor's client area, after they open, and keeps a size that
changed when the window closes.

View > Command Palette opens `CommandPaletteWindow`, a modal dialog with a
filter field over the rows `PaletteInput.filter` returns: command, menu and
keys, a disabled command in gray. Up, Down, Page Up and Page Down in the field
move the selection; Enter or a double click closes the dialog and runs the
command on the next UI turn, after the editor has its focus back.

File > Quick Open and Window > Switch Document open `QuickOpenWindow`, the
same kind of dialog over `QuickOpenInput`: name and path columns and a summary
line, refilled on each field change and on `quickOpenChanged`; an open
document's row is in bold. Enter passes
the row to the controller on the next UI turn; closing the dialog cancels a
running match.

`WorkspaceDock` is the workspace browser: Refresh, Reveal Active File and
Hide over the folder and a `Tree`. A directory row holds an empty placeholder
child until it first expands, which asks `WorkspaceInput.expand` to list it;
the listed rows replace the placeholder on `workspaceChanged`, and a refill
keeps the expansion of rows that remain. Enter or a double click opens a
file, shows more entries on a "more" row and toggles a directory. One context
menu, built on the first `MenuDetect`, serves every row. The tree is not virtual: GTK's virtual `Tree` is quadratic in a
node's child count (measured), and a directory shows 500 rows at a
time.

The status bar shows a notice for 8 s, the Warnings link to Show Log while a
WARN or ERROR line is unseen, and the selected document's read-only marker,
line and column, selection, encoding, line endings and language
(`StatusFields`). A caret move or state change schedules one read on the next
UI turn; further changes before it share that read. `BusyCounter` shows
`CURSOR_APPSTARTING` while any load, save or conversion runs.

`setFontSize` creates the new editor font, calls `EditorPane.setFont` on every
editor and disposes the old font. The widgets, the menu bar and the focus stay;
nothing is rebuilt. `setFontFamily` does the same and also moves open comparison
windows, text windows and the replace preview to the family; text windows and
the preview keep the default size. `EditorFonts.family` resolves the saved
family on the UI thread: one that `Display.getFontList` does not hold, or whose
`i` and `W` measure differently, gives way to `Dialogs.monospaceName` with one
WARN per window, and the saved value stays. The Appearance tab keeps the
scanned families `EditorFonts.listed` finds in one `Display.getFontList` pass,
since a font file's family can be missing from it (macOS: `.SF NS Mono`,
`Courier`), and adds a saved family the list lacks, so OK does not drop it.

- `MainToolBar` is a fixed row under the menu bar, shown unless `ui.toolbar`
  is false: New, Open, Save, Save All, Undo, Redo, Cut, Copy, Paste, Find,
  Replace, HyperSearch, Zoom In, Zoom Out. Each button runs its command through
  `ApplicationController.execute`, syncs enablement from `CommandStates` with
  the menu bar, `EDIT_STATE` on each status read, coalesced with the status
  bar, and shows the command and its keys as the tool tip. A click that
  left the focus on the bar returns it to the editor. The GTK window's minimum
  width is the menu bar's (437 px measured); the toolbar prefers 441 px there,
  and below its width GTK moves the last buttons behind an overflow arrow. Every
  button's command is also in a menu.
- `EditorMenu` is the context menu of the text views: one popup per window, built
  on its first show (building one per view stalled a file open under GTK 4 in
  QLogg) and again after a key change. `SWT.MenuDetect` on each view's
  `StyledText` makes that view the tab's focused view; a pointer request calls
  `EditorInput.menuFromView`, a keyboard request opens below the caret and moves
  nothing. An item runs through that view's `EditorInput.execute`, so in a split
  it acts on the view clicked. The popup holds the view weakly, so it does not
  keep a closed document.
- One context menu serves every tab: each right click disposes its items and
  adds Close, Close Others, Close to the Left, Close to the Right, Close All,
  Move to New Window, Copy Path, Copy File Name and Open Containing Folder for
  that tab, enabled from `CommandStates.tabItems`. A tab's close button and a
  middle click ask the `DocumentController` to close it. Dragging a tab within the strip moves it: an item cannot change
  position, so the move disposes and recreates it, and the `Selection` that
  `CTabFolder` sends for a neighbor while the selected item is disposed is
  ignored, so the moved tab stays shown.
- Open and Save As use the native `FileDialog`, which runs a nested event
  loop. The unsaved-changes prompt (`UnsavedDialog`) does not: Save, Don't
  Save and Cancel answer through a callback, and closing it answers Cancel.
  The binary-file prompt (`BinaryDialog`) opens the read-only view on Open
  Read-Only; Cancel is the default and closing it declines.

- `place` sizes and positions the shell through `WindowGeometry` before
  `open`. On Wayland (`org.eclipse.swt.internal.gdk.backend` is `wayland`) the
  position is skipped and the size applies. On win32 `computeTrim` overstates
  the height once a menu bar is set, so the window is placed again with the
  trim measured from the sized shell.
- Every unmaximized move and resize records the normal bounds, which
  `windowBounds` returns for the quit to save.
- The window icon is the `qcaret-*.png` set at 16 to 256 px
  (`Icons.Variant.DARK`).

`FindBar` sits above the status bar while `FindController` is open: the
search field, Match case, Whole word, Regex, Previous, Next, the notice and
Close, plus a replace row (field, Replace, Replace All) for Replace. Both
fields are editable `Combo`s listing the saved history from `FindState`, as
`SearchDialog` does. Typing or choosing an entry finds incrementally; Enter
finds the next match, Shift+Enter the previous one, Enter in the replace field
replaces, and Escape anywhere in the bar closes it and focuses the editor.
While a field's list is open, Enter and Escape go to the list. Mod+F with the
bar open focuses and selects the field.

`SearchDialog` (Search > HyperSearch) is modal: the text with its history,
Match case, Whole word, Regex, the scope, the workspace folder, the directory
with its history and Browse, the include masks with their history, the
exclude masks and Hidden files. The directory fields are enabled for the
directory scopes, the masks for the folder scopes. Search passes a
`SearchForm` to the controller and closes. File > Set Workspace Folder and
Browse use the native `DirectoryDialog`.

`ResultsDock` (`ui.swt.search`) shows the `ResultsInput` model as an indented
tree in a virtual `Table`: set rows, file group rows and match rows, a `+` or
`-` before a set or group. GTK's `Tree` took 3.4 s to expand 10,000 children
and 2 s to clear them (measured under Xvfb), and a virtual table changes only
its row count; `ResultRows` maps rows to paths. A click on the mark, Enter, a
double click, Right and Left expand and collapse; Left on a child selects its
parent; Enter and a double click on a match open it. A match row paints the
matched text over the palette's find-match color in the palette's text color;
a stale row shows in the disabled color. Stop, Rerun, Replace, Pin and Clear
act on the selected row's set, Rerun File on the selected row's group while
it is stale, Clear All on every set, Hide hides the dock. A cut of more
than 2,000 rows replaces the table, since GTK removes rows one at a time
(4.6-7.7 s for 100,000 in the dock; a new table took 81-105 ms).

`BookmarksDock` shows the `BookmarksInput` model in a virtual `Table` with
Line (from 1), Comment and Text columns under Go To, Edit Comment, Remove,
Hide and the model's summary. Enter and a double click go to the selected
row, Delete removes its bookmark; a cut of more than 2,000 rows replaces the
table, as in the results dock. `BookmarkCommentDialog` is the comment prompt:
a modal one-line field limited to the comment length, starting with the
current comment selected; OK answers its text, and Cancel, Esc and closing
answer null.

`ReplaceDialog` (`ui.swt.search`) is the replace preview: modal and
resizable, the replacement field, the files and the selected file's matches
as virtual check tables, the before and after view in a monospace
`StyledText` (removed lines over a red tint of the editor background, added
lines over a green one), the summary and Apply. `ReportWindow` is a run's
report, modeless: the summary, a virtual table of file, folder and result
(skips and failures in dark red), Stop, Restore Run and Close; closing it does
not stop the run. `RunChooser` lists the runs kept for Restore Replace Run.
`ReplaceOpenDialog` asks before Save As replaces a file another tab holds with
unsaved changes.

`ChangeDialog` asks about a file another program changed: Keep Mine, Reload
and Compare, or, for a save that found the change, Overwrite, Reload, Compare
and Cancel. It is modeless, so the comparison window it opens takes input;
Compare leaves it open and closing it answers Keep Mine, or Cancel for a
save. `DocumentChooser` asks for the document Compare Documents compares
with. `DiffWindow` shows a `Comparison`: a modeless window owned by the main
window, two read-only monospace `StyledText` controls in a `SashForm`, each
row's background from `Palette.diffRemoved`, `diffAdded` and `diffChanged` (a
filler row in the gutter color), recolored on a palette change. After a
scroll bar move, a caret move or a paint, each side copies its top line and
horizontal offset to the other when they differ from the last position either
side reached; the side that moved leads, so a stale paint of the other side
does not pull it back. It opens at the first
difference; Next and Previous Difference, F7 and Shift+F7, scroll both sides
to a run of changes three rows below the top, wrapping; Esc closes it.
`MainWindow` asks the controller to check the open files whenever the shell
is activated.

Dialogs (`Dialogs`, `ErrorDialog`, `AboutDialog`, `TextWindow`) open without a
nested event loop: `open` returns at once and buttons act through listeners.
`showText` and `showLog` open their window modal and owned by the innermost
visible modal dialog when one is open, because GTK gives input only to the newest modal
shell; Licenses opened from About therefore stays usable. Four calls do run a
nested event loop, so code around them must tolerate re-entrant events:
`FileDialog.open`, `ColorDialog.open`, `PrintDialog.open`, and
`Clipboard.getContents` on GTK, which `MainWindow.clipboardText` uses.

### Printing

`MainWindow.choosePrinter` opens `PrintDialog` and creates the `Printer` on the
UI thread; `PrintJob` does the rest on the job's worker. A `ProgressRow` shows the
progress and Cancel above the status bar. It takes no Esc, since the window
stays usable.

| Call | Thread | Reason |
|---|---|---|
| `PrintDialog.open`, `new Printer` | UI | GTK's constructor runs `gtk_enumerate_printers` with a main loop on the default context and uses static fields; macOS's creates an `NSWindow` |
| `startJob`, `GC`, fonts, drawing, `endJob`, `cancelJob` | One platform worker | SWT checks the thread on widgets, not on a `Device` or its `GC`. macOS sets the graphics context per thread in `startJob`, and Pango's font map is per thread, so a virtual thread would be wrong |
| `Printer.dispose` | UI, or the worker when the Display is gone | The object was created there |

On GTK, `endJob` queues the spool file; the event loop delivers it, so a quit
straight after a job can lose it, and a delivery error reaches no notice.

`PrintJob` draws from the `PrintTarget.Page` rows at 10 points in the editor
font's family, placing each span at its column times the measured advance, with
0.75 inch margins from the paper's edge (`computeTrim`). Colors are
`Palette.LIGHT` on white whatever the window's theme; plain text and the header
are black. A device depth of 1 prints all black. GTK reports depth 0 and macOS
the screen's depth, so there the print system converts color for a monochrome
printer.

Copies stay with the print system: the `PrinterData` passes unchanged to the
`Printer` (GTK's CUPS backend sends `cups-copies`; Windows has them in the
DEVMODE; macOS in the print info). QCaret does not loop, which would multiply
them. GTK's Print to File writes one copy; Microsoft Print to PDF writes every
copy into the file, collated. The page range is applied by `PrintController`;
GTK does not also pass it to CUPS for a `GtkPrintJob`.

### User guide

`GuideWindow` is a modeless window, one per main window, over an SWT `Browser`
with JavaScript off. A `LocationListener` refuses every location but the guide's
file, with or without an anchor, and `about:blank`.

| Platform | Behavior |
|---|---|
| GTK 3 | WebKitGTK; without it `Browser` creation fails and the fallback runs |
| GTK 4 (`SWT_GTK4=1`) | `MainWindow.showGuide` shows a notice naming the file and creates no `Browser`: WebKitGTK 6.0 aborts the JVM ("Failed to fully launch dbus-proxy"). DO NOT CREATE ONE THERE |
| cocoa | WebKit opens a modal "Page Load Failed" alert for a missing page, so the page is written before the load. An SWT timer runs inside any nested run loop a native call spins, so a bound that expires during this window's own `setUrl` or `Shell.open` closes the window after the call returns (`calls`, `deferredFailure`) |
| win32 | WebView2 creation can stall; its data folder is `<settings>/cache/webview2` (`PlatformSetup`), inside the settings directory in every mode |

An `SWTException` or `SWTError` from any browser call, or a page not loaded
within `LOAD_BOUND_MILLIS` (15 s, `LoadBound` on `UiDispatcher.timerExec`), closes
the window and calls `ApplicationController.showGuideElsewhere`; during `create`
it returns null and `showGuide` returns false. The location comes from the
`changed` event: `Browser.getUrl` can throw on win32.

## Editor (`ui.swt.editor`)

`EditorPane` is one editor: a `StyledText` over a `ContentAdapter`, the
`StyledTextContent` that serves the display rows of the document's
`DisplayProjection`, then the `OverviewStrip` and a `Slider` as the vertical
scroll bar, in one composite (`control()`). The model is the only authority:

- The adapter turns each projection change into one `textChanging` and
  `textChanged` pair, so the widget never re-reads the whole text after an
  edit. It sends no `textSet`; a loaded document gets a new adapter, read in
  `setContent`. A `replaceTextRange` from the widget, which it calls only for
  input method composition text, goes to `EditorInput.preeditFromView`,
  outside undo. SWT sends `ImeComposition` on the `IME` widget, and its
  `StyledText` listener removes the composition at a commit; the committed
  chars then arrive as key events on GTK, win32 and cocoa (read in SWT 3.135).
  That listener also collapses the widget's selection, so `EditorPane` ignores
  widget caret reports until its own `IME` listener runs, and puts the model's
  selection back once no composition shows. `setText` goes to the controller as
  an edit.
- A `VerifyKey` listener converts each key with `SwtKeys.stroke`, runs the
  `Keymap`'s editor binding through `EditorInput.execute`, types printable
  chars and Tab, and consumes both; Ctrl+Alt (AltGr) and, on macOS, Option
  produce typed chars. Insert alone is consumed, so the widget never enters
  overwrite mode. Any edit the widget still makes
  (word delete, middle-click paste, drag and drop) arrives as `Verify`, is
  canceled, and goes to `EditorInput.replaceFromView`; the controller applies
  it inside the listener, before `modifyContent` returns.
- Caret moves are editor bindings, run by the controller at every caret. A
  plain click and drag move the widget's caret natively, and each move goes to
  the model. The widget holds only the model's primary selection, set again
  after every model change and every display change; caret moves the widget
  makes while it applies a change are ignored, and so is a widget report of
  that primary, such as after a resize, unless it follows a plain click.
- `EditorGestures`, a Display filter, takes the editor's mouse gestures before
  the widget sees them (the widget's own Alt+click adds a native selection):
  Alt+drag, and Ctrl+Alt+drag where the window manager takes Alt+drag, select
  a block; Alt+Shift+click and drag extend it; in column selection mode a
  plain drag selects a block; Mod+click adds a caret or removes the one
  clicked, and a drag from that press adds a selection to the ones from before
  it (`addSelectionFromView`), once the pointer leaves the pressed offset. The
  press fixes the block's anchor as a line and column, and each
  drag step extends from it, so a horizontal scroll during the drag keeps the
  anchor. A block or Mod+drag held above, below, left or right of the text
  area extends toward the pointer every 50 ms from a
  `UiDispatcher.timerExec` timer, and the controller scrolls to the caret. Release, focus loss and dispose cancel the timer (`cancelTimer`).
- `EditorLayout` is the pane's `ViewLayout`. A printable ASCII row without
  tabs maps x to a column at the font's fractional advance from the row's
  left edge, while the row's measured width stays within half a column of
  that advance; any other row uses the widget's measured glyph positions. A
  family's bold or italic face can be wider than its regular face, which is
  the advance `ColumnMetrics` measures. Past a row's end columns continue at
  the fractional advance.
- The gutter is painted in the widget's left margin by a paint listener, with
  the line number on each line's first row; `ColumnMetrics` places the digits
  and sizes the margin. `MainWindow.setLineNumbersShown` reaches every pane and
  the constructor of later ones, since a margin change on a loaded document
  costs O(rows); hidden numbers take their width with them, and the bookmark and
  fold columns stay. A two-column fold marker column right of the numbers
  shows a box with a minus on each line that starts a fold region, a plus
  while it is collapsed; `EditorGestures` takes a click there from the
  Display filter and sends `toggleFoldFromView`, so the widget's caret stays.
  A collapsed region's first line ends in a box with an ellipsis. Secondary
  carets in hidden lines are not painted. The current line's rows get the palette's
  current-line background (`LineBackgroundListener`). Secondary selections are
  line style backgrounds and secondary carets are 2 px bars at
  `getLocationAtOffset`, so both follow the widget's measured glyphs on any
  script. A block's part past a short line's end, and each row's caret there,
  are painted from the line end's measured position at the fractional
  advance.
  Secondary carets take the palette's caret color.
- The primary caret is an SWT `Caret` with a 2 px image one line high, rebuilt
  on a font change, a zoom change and a palette change and disposed with the
  widget. The platform composites the image over the text, so `CaretInk`
  picks the image color that shows the caret color over the current line:
  difference on GTK 3 and cocoa, XOR on win32 (assumed, not yet checked on
  Windows), over on GTK 4. Under difference, a channel the background cannot
  reach shows the nearest value: the dark palette's amber shows red 0xdd for
  0xf3.
- Each paint that shows another display range reports it through
  `EditorInput.viewportChanged`.
- Line styles come from the pane's `HighlightComposer`: the syntax tokens
  (`setSyntax`), the find matches (`showMatches`), the bracket pair
  (`showBrackets`), the word occurrences (`showOccurrences`) and the
  secondary selections. A run takes its `TokenRole`'s palette color, bold,
  italic and underline; on the same chars the background of a secondary
  selection wins over a bracket, a bracket over a find match, and a find
  match over an occurrence. `redrawHighlights` repaints after new tokens
  arrive.
- The View menu's Syntax cascade and the status bar's language popup show
  the app's `SyntaxMenu`: the recently chosen grammars and a separator, Plain
  Text, then one cascade per category. `MainMenuBar` rebuilds the cascade's
  entries above Reload Grammars on each show and the popup on each click.
- Bookmarked lines get a mark on their first row in the gutter's bookmark
  column (`EditorPane.BOOKMARK_COLUMN`, 14 px left of the line numbers), so
  it never meets the digits or the fold marker column: a bar in the caret
  color for a bookmark, a flag (the bar and a square at its top right) in
  `Palette.bookmarkComment` (teal, mint on dark) for one with a comment.
  With the pointer over the gutter left of the fold markers, a commented
  bookmark's line shows its comment as the `StyledText` tooltip. The pane
  follows the document's `Bookmarks` and repaints on each change they
  report; a palette change recolors the marks. The overview strip marks a
  commented bookmark like any other.
- `setBusy` shows `SWT.CURSOR_APPSTARTING` over the text and gutter while a
  whole-text operation computes and applies.
- Show Whitespace paints a dot in each space and an arrow across each tab,
  and Show Line Endings the break's kind (`CRLF`, `LF`, `CR`) after each
  line's last row, in the palette's whitespace color, which a theme switch
  recolors. Positions come from `getLocationAtOffset` at each run's ends.
- The completion list (`CompletionPopup`) is a shell that takes no focus
  below the primary caret, created on show and disposed on hide; the editor
  keeps the keys, which the controller routes to the list. A double click
  chooses a word. When the editor loses focus to another control, the list
  closes on the next UI turn.
- Every resize and font change sends the columns that fit the text area for
  word wrap. `setFont` replaces the font in place and measures the advance
  again. A
  double click at or past a wrapped row's right edge selects the row's last
  word, where the widget alone would take the trailing space and the virtual
  break.
- Font, tabs, margins and the text and background colors each make the
  widget reset its per-line cache, O(rows) through a `TreeSet` (about 1 s at
  1M rows): the pane sets them before `setContent`, and a palette change sets
  only colors that differ. A theme switch therefore costs about 1 s per view
  of a 1M-line document (measured 970 ms for one view, 1,882 ms for two, load
  1-2).

### Overview strip and scroll bar

The `StyledText` has no native vertical bar, so the strip sits between the
text and a `Slider`. The slider counts rows (one widget line each): maximum
the row count, thumb the rows that fit, selection the top row; it follows the
widget on each paint and resize and after the strip or the wheel scroll, and
its own moves set the top row and hand the focus back to the text. The wheel
scrolls the text by its event count in `MouseVerticalWheel` and consumes the
event. Page and caret moves scroll the widget, folding and word wrap change
its rows, and the next paint sets the slider.

`OverviewStrip` (14 px) paints the last `OverviewMarks`: a HyperSearch mark
per pixel row holding a match, a find mark over it, and a bookmark tick at
the left edge, then a line down its left edge and lines at the first and last
visible line, in the palette's gutter, line-number, `overviewFind`,
`overviewSearch` and caret colors; a palette change recolors it. A click or
drag scrolls the line under the pointer to the middle of the view. Each
resize reports the height, 0 while View > Overview Strip hides it. Split views
have one strip each.

## Themes

`SwtThemeService` reports `Display.isSystemDarkTheme` with a change event from
`SWT.Settings`, polled every second on cocoa, and sets native controls' theme:

| Platform | Native controls | A switch at runtime |
|---|---|---|
| GTK | `setDarkThemePreferred` before the first Shell for light and dark, and at every switch | Applies at once. The system setting is read once at Display creation, so a desktop change applies at the next start |
| win32 | `setDarkThemePreferred` before the first Shell; controls read it only when created | Native controls change at the next start; `showsNow` is false and the controller shows a notice. Buttons, combo lists and checkboxes stay light |
| cocoa | Never set: always the system appearance | Only the text view and tab strip recolor; a system appearance change recolors the rest through `CocoaChrome` |

DO NOT PIN THE COCOA APPEARANCE, live or at startup. Cocoa SWT reads its
widget colors once, under the system appearance; an application appearance
that differs draws text in its own appearance over those backgrounds, white on
light gray or black on dark gray. Both a live `setDarkThemePreferred` and a
startup Display replacement failed this way.

GTK SWT refreshes its system colors, repaints every shell and sends
`SWT.Settings` on the first `Display.sleep` after a theme change, so a loop that
never sleeps keeps the startup colors: status bar 250 with light text and a
stale `getBackground` (measured under Xvfb, Yaru). `Gui`'s loop sleeps when
idle. A `Link` keeps the color of its creation through that refresh, so
`SwtThemeService` sets each link's color on `SWT.Settings`. `colorLink` also
moves that color toward white or black until it reaches 4.5 contrast on the
link's background: Adwaita's dark link color measures 3.25 on its own window
background. Every new `Link` calls it.

A GTK theme with no dark variant ignores `setDarkThemePreferred`. A named theme
that is not installed behaves the same way: GTK falls back to light Adwaita, as
on an Ubuntu system whose `/etc/gtk-3.0/settings.ini` names Yaru without Yaru
installed (a CI runner, a container). `SWT.Settings` still fires. Native
controls stay light under the dark palette, with dark ink and the light logo,
because both read the native background.

Tests take the theme from `XDG_CONFIG_HOME`, set in the module POM to
`src/test/resources/gtk-config/adwaita`, so the dark switch runs on every Linux
host. `LightOnlyThemeTest` runs in a second surefire execution against a missing
theme and skips in the first.

Under a dark startup setting win32 draws check box labels near-white while
dialog pages stay light, so every check box comes from `SwtThemeService.check`,
which creates it with the theme off. A `MenuItem` or `Table` with `SWT.CHECK`
has no such label and does not use it.

cocoa SWT sends no `SWT.Settings` for an appearance change and keeps its startup
colors (measured on macOS 15 in QLogg). While the appearance differs from the
startup one, `CocoaChrome` sets each shell's, table's, tree's, list's and
field's colors to the current appearance, at the change, at `SWT.Show` of a
shell and at `SWT.Skin` of a new control, since the docks replace their tables
while open. It follows the system appearance, not `ui.theme`. A control with
`EditorPane.PALETTE_COLORED` keeps its colors: the editor and compare
`StyledText` and the tab strip, which the palette owns and nothing would restore.
`ReplaceDialog` marks its computing note with a style range, because
`setForeground(null)` returns a field to the startup color.

`MainWindow` applies a palette to the `StyledText` colors and the tab strip, then
re-inks its `ToolIcons` on the next UI turn, after native controls have restyled.
`ToolIcons` picks dark or light ink from the owner control's background
luminance, not from the palette, and `reink` swaps every applied item's image
when that changes; it also listens to `SWT.Settings`, from the Display or from
`CocoaChrome` on the owner. On GTK `getBackground` is stale until that event,
and `MainWindow`'s `reink` runs on the turn before it, so `reink` reads a pixel of
the visible owner through `Control.print`; a hidden toolbar re-inks when it
shows. GTK's disabled effect brightens an icon on a dark
background (measured 212 to 225 on a dark bar), so there `showState` gives a
disabled item a copy at 40% opacity. About picks its logo the
same way from the dialog background (`MainWindow.logoVariant`): native chrome
can differ from the palette on cocoa, on win32 before a restart and on a GTK
theme with no dark variant.

`MainWindow.colorTabs` sets all four `CTabFolder` colors, because the folder
otherwise keeps the system list colors of its creation: a white selected tab in
the dark theme, and a white label on a Display created under a dark system
setting.

| Tab | Background | Label and close button |
|---|---|---|
| Selected, focus inside the folder | `selection` | `selectionText` |
| Selected, focus in a dock or another shell | `gutter` | `selectionText` |
| Unselected | `gutter` | `Palette.tabText` |

The 2 px bar on the selected tab is the `CTabFolder` default: the system's list
selection color, gray with focus outside the folder. SWT has no setter for its
color. A hovered close button is SWT's fixed red.

## macOS application menu

`AppMenu.hook` wires the system's application menu on cocoa: About, Quit
and Preferences run `ABOUT`, `QUIT` and `PREFERENCES` in the window activated
last and are left out of the menu bar; Hide, Hide Others and Show All stay native. A system quit request (Cmd-Q, logout) arrives as a
Display `SWT.Close`, which is vetoed and runs `WindowSet.requestQuit`, so the
unsaved-changes prompts of every window and the ordered quit always run. A file
opened with QCaret (Open With, a Dock drop) arrives as `SWT.OpenDocument` and
goes to `WindowSet.openFile`; a null or empty name opens nothing, and a name that does not parse logs a warning, since an exception there reaches the event loop. Quit with no target window runs `requestQuit`. All of it is unverified on cocoa. Elsewhere there is no application menu and `hook` does nothing.

## File drop and the load row

`MainWindow.acceptDroppedFiles` makes the shell a `DropTarget` for
`FileTransfer` only, so a drag offering no file is never accepted; the
platforms pass a drop over a child to the nearest target above it. Every drop
is a copy, also after the user changes the operation mid-drag: accepting a move
has the source delete the file. `dropped` parses the names and calls
`ApplicationController.openDropped`; a name that does not parse shows the open
failure and the rest open. The editor has no `DragSource`: a drag in the text
selects. Measured on GTK 3 with a real XDND source in another process: a drop
over an empty window and over the `StyledText` opens a tab with a copy, and a
text drag is refused. Under `SWT_GTK4=1` a drop reaches no target. Unverified on
win32 and cocoa.

A `ProgressRow` shows what `DocumentsView.showLoadProgress` passes, above the status
bar, and takes no room while hidden. While it shows, a Display `KeyDown` filter
sends Esc from any control of the shell to the cancel and consumes it, which
covers a window with no tab and keeps Esc from also dropping carets.

The window title is `CommandStates.windowTitle`, set with the status fields on
the UI turn after a state change. `Gui` calls `showStatusNow` after
`startSession`, so the untitled document's title does not wait for the startup
events to drain.

## Icons

The application icon PNGs under `src/main/resources/.../icons/` are rendered
from `qcaret-dist/src/icons/*.svg` by `tools/icons.sh` and committed. The tool
icons are SVG in `src/icons/` (`currentColor` strokes); `src/build/IconRenderer`
renders each at 16 and 32 px in dark and light ink at `generate-resources`,
with jsvg, which is a build-only dependency. Every icon carries the same root
element (16 px grid, 1.4 stroke, round caps and joins, no fill) and no child
overrides it; `MenuIconsTest` fails on a difference.

`MenuIcons.icon` maps a command to its icon, for the toolbar and the menus.
`MenuIcons.decorate` shows the icons on the push items of the menu bar and the
editor menu, through a `ToolIcons` of the window's own.

| Platform | Item image | Ink | Items without an icon |
|---|---|---|---|
| GTK 3 | `MenuItem.setImage` | Same reading as the toolbar, live switch included | Hold `ToolIcons.blank` in a menu with an icon, because GTK 3 SWT reserves no image column and the label would move 22 px |
| GTK 4 | SWT ignores `setImage` | - | - |
| win32 | `MenuItem.setImage` | Fixed from `SwtThemeService.nativeDark`: menus take the startup setting, and the toolbar stays light under a dark one | Bare; the system reserves the column |
| cocoa | None; the application menu stays native | - | - |

A disabled GTK menu item shows SWT's disabled copy of its image, which keeps the
alpha and raises ink 212 to 225. On a dark background `ToolIcons.showState` gives
it the faded image, so it draws at 107 on the menu's 29; `MainMenuBar` and
`EditorMenu` call it after changing an item. Check, radio, cascade and listing
items show no icon: GTK draws a check item's image beside the indicator, and on
Windows 11 SWT draws it in the indicator's place. The tab menu is rebuilt on each show and carries none.

## Tests

`DocumentBehaviorSurfaceTest` checks the drop target's one transfer, `dropped`
over an open editor and with a name that does not parse, the load row with Esc
and Cancel, the title, Mod+drag and the small move of a Mod+click, the docks'
weights, Recently Closed, Automatic Detection, the tab width and reload prompts
and `AppMenu.open`'s guards. A real drag is not simulated there.
`PrintOutputTest` prints through a real window and `Printer` to a PDF and reads
it back with PDFBox: page count, header, every line, wrap, a range, a selection,
copies, Cancel. It supplies the dialog's answer through
`MainWindow.setPrinterChooser` and uses only GTK's Print to File or a Windows
printer named PDF, never a physical printer; it skips when neither exists.

`TabMoveGuiTest` drives the menu items and tab drags over real shells, with a
`PointerProbe` naming the control under the release; `-Dqcaret.shots=<dir>`
writes its screenshots.

Every test class runs under `HangWatchdog`, registered through
`META-INF/services`: a test still running after 120 s
(`qcaret.test.hangSeconds`) prints every thread's stack and halts the test JVM
5 s later, so a native call that blocks the main thread ends the CI job with a
stack. `EventLoop.runUntil` dispatches events until a condition holds, with no
fixed sleep.

`GuideWindowTest` runs User Guide to its window or fallback, GTK 4's notice, the
locations `allowed` takes, a result arriving after the window closed, a throwing
browser factory and `LoadBound`. A real
browser's outcome is never raced against a short timer: Cocoa and GTK resolve
that race opposite ways. `GuideScreenshotsTest` runs only with
`-Dqcaret.guide.shots=<dir>` (`tools/screenshots.sh`) and writes the user guide's
images from generated mock files.

`RecoverySurfaceTest` drives the recovery dialog and a restored split on a real
window, with the top line of a tab shown later and a fold in one view. With
`-Dqcaret.screenshots=<dir>` it captures both.

`TabColorsGuiTest` reads the tab strip's screen pixels through `GC.copyArea` in
light, dark, light and dark, light, dark order, with focus outside and inside
the folder, and requires contrast of at least 4.5 and a strip of the theme's
lightness. On GTK the status bar and its Warnings link take the same check and
the About logo must match the theme. A probe child of a known color checks that a screen read shows the
window: the `windows-11-arm` runner's desktop lies under a first-run page. When
it does not, win32 reads through `Control.print` and other platforms fail. It
skips on GTK 4, where `GC.copyArea` from the Display reads white.

`PreferencesSurfaceTest` drives the dialog on a real window: a refused field
keeps it open on its tab, Restore Defaults applies nothing, the Keys tab refuses
a typing or taken key and a free one reaches the menu item's accelerator, Clear
History's prompt declines and clears with an open find bar following, a theme
switch leaves it working, and a new size is remembered. With
`-Dqcaret.screenshots=<dir>` it captures each group.

`FontFamilyTest` (tag `swt`) takes installed families from the scan: bold keyword
rows keep every column in two families, a family change measures the gutter again
at a size it searches for, where two families' number columns round apart, a
missing or proportional family falls back, the family and the zoom keep each
other, an open log window follows, a family the toolkit lacks is not offered, and
the Appearance tab lists, applies and restores a theme file and a family. Its two-family cases skip on a host with
fewer, and the gutter case on a host where no size separates them. Every family
measured on the Linux host has equal regular and bold advances, so
`EditorLayoutGridTest` forces a row off the grid to reach the measured path.

`MainWindowTest` (tag `swt`) checks the requested client size and position
under X11, clamping to the work area, close requests and the dispatcher after
disposal. `EditorSurfaceTest` (tag `swt`) drives a real window: typing, Enter,
undo and redo by key events, an edit the widget makes itself (`insert`), and
Save, all checked in the model and the file; word wrap on a generated
200,000-line file with no gap over 1 s between 10 ms timer ticks on the UI
thread; the unsaved prompt's Cancel and Don't Save, from Close and from Exit;
a dragged tab that keeps its document shown; the double click past a wrapped
row's edge; and a pixel check of a secondary caret after CJK text at its
measured position and of gutter ink. The pixel check skips when
`Control.print` returns false. `ThemeAndDialogsTest` (tag `swt`) switches the theme from the View
menu, re-inks tool icons on a dark background, checks `showsNow` per
platform, the About logo by background, a text window over a modal dialog,
Licenses over About, and the menu bar without an application menu. It also
drives `CocoaChrome` on any platform: control colors follow an appearance
change, palette-colored controls keep theirs, a control created during a change
is colored, and `refresh` reports one change once.
`ToolbarAndMenusTest` (tag `swt`) checks the toolbar's order, icons and tool
tips, its buttons against `CommandStates`, View > Toolbar with the choice in the
settings file, a live theme switch's ink and faded buttons, a narrow window;
posted Ctrl+X, Ctrl+V, Ctrl+C and Ctrl+Z in the find field with the items
disabled and Ctrl+C in a read-only view, on GTK 3; the editor menu's selection rule, keyboard request, commands, split-view target and
key change; the tab menu's side closes; and View > Line Numbers in existing and
later views, and the menu items' icons with a theme switch both ways. No test
opens a popup under a pump: a win32 popup would block. `MenuIconsTest` (tag `swt`)
checks the command-to-icon table, both inks at both sizes, the shared root
element, and `MenuIcons` per platform. `ShellWindowTest` (tag `swt`) checks that opening and closing documents
enable and disable Save, Paste and Next Tab with no menu shown; the Other
encoding cascades filled once; one tab menu reused across tabs and after a
drag, with the dragged document still shown; a font change that keeps the
`StyledText`, the menu bar and the focus; the status fields; the warning
marker and the log window; the busy count; the binary prompt, its marked tab
and status, Save disabled and a typed key refused; and column 150, where the
widget's x must match `ColumnMetrics` within 1 px and a secondary caret's
pixels must sit at the widget's position. Under GTK 4 the window cannot grow
past the screen, so the pixel half of the column 150 check skips.
`EditingSurfaceTest` (tag `swt`) adds carets with Add Caret Below, moves and
extends every caret by key, adds one with Mod+click and drops them with Esc;
drags with Alt from column 150 across long and short lines, scrolling the
widget between press and drag, and checks the block, its copy, and in a print of the widget the block's left edge on a short
line within 1 px of the measured column 150 on a long line, with the virtual
part painted after a resize and a horizontal scroll; sends
composition text through the content and checks it stays out of undo; sends
`IME` events for a composition over a selection, with the commit's key events,
and checks a cancel keeps the selection and a commit replaces it as one undo
step; runs Go
to Line through its dialog; filters and copies Keyboard Shortcuts; holds a
block drag below, right, left and above the view and checks that it scrolls
until release, focus loss and tab close; and checks the
primary caret's image color and size and, polled while the caret blinks, its
screen pixel in both themes. With `-Dqcaret.screenshots=<dir>` it saves the
multi-caret, block and dark-caret windows as PNG. Under GTK 4 the caret's
screen check skips (a Display GC copy never showed the caret under Xvfb) and
its pixel half skips: the window cannot grow past the screen,
and `Control.print` draws nothing there (SWT issue 2819).
`SplitAndFindTest` (tag `swt`) types in one of two views and checks the text
and both carets, switches the arrangement and unsplits; it opens the find bar,
types a query, and checks the selected match, the matches in both views, the
match background in pixels in the light and the dark theme (skipped when
`Control.print` returns false), Escape closing the bar and clearing the
matches, and Esc in the editor dropping an extra caret before it closes the
bar; the Focus Other View item shows F6 and switches views.
`SearchSurfaceTest` (tag `swt`) runs two tree searches from the dialog into
the dock, checks the rows newest first, expands a group, opens a match with
Enter and steps with F4 to another file; it checks the strip's find marks,
a click on the strip scrolling there, the slider following that, its own
moves and the wheel, hiding and showing the strip, its recolor on a theme
switch and a strip in each split view, and the slider following Page Down and
Fold All; it checks both find bar fields listing the saved history, the
field's width with a 1,000-char entry, and HyperSearch listing the same texts. `ResultRowsTest` covers row paths through expansion, expansion
kept by set id, and groups collapsed when a search ends.
`SyntaxPaneTest` (tag `swt`) opens a Java file and checks the keyword's
palette color and bold, a number keeping its color under a find match, Java
checked in the Programming Languages A-M cascade, Python chosen from N-Z
listed first on the next show, and a switch to Plain Text that clears the
styles and the status field.

`FoldingSurfaceTest` (tag `swt`) clicks fold markers and checks the widget's
lines against the projection's rows, moves the caret by key over a collapsed
region, folds one split view and types in it with the other unchanged, and
sends Enter after an opener; with `-Dqcaret.screenshots=<dir>` it saves
`folding.png`.
`ProductivitySurfaceTest` (tag `swt`) runs a command from the palette's
filter, completes a word from the editor's keys, checks the bracket and
occurrence backgrounds and a bookmark bar left of the fold marker column in
pixels (skipped when `Control.print` returns false) and toggles the marks;
with `-Dqcaret.screenshots=<dir>` it saves the marks with a bookmark and
occurrences, the open palette, and a Java file with a collapsed region,
occurrences and a matched bracket (`merge.png`), as PNG.
`BookmarksSurfaceTest` (tag `swt`) checks a plain bookmark's bar and a
commented bookmark's flag in pixels, by color and by x range, in both themes (skipped when `Control.print` returns
false), the tooltip over the gutter and its absence elsewhere, the comment
prompt, the list's cells, Go To, Edit Comment, Remove and Hide, and both
docks shown at once; with `-Dqcaret.screenshots=<dir>` it saves
`bookmark-tooltip.png` (the whole screen, so the tooltip shows) and
`bookmarks-list.png`.
`WorkspaceSurfaceTest` (tag `swt`) expands browser rows, opens a file with
Enter, copies a path, reveals the active file, chooses a Quick Open match at
`:2`, checks the Recent Files entry and an empty Quick Open (the open
document in bold, then a closed recent file); with
`-Dqcaret.screenshots=<dir>` it saves `workspace-browser.png`,
`quick-open-empty.png` and `quick-open.png`.

`CompareSurfaceTest` (tag `swt`) changes a dirty document's file, activates
the window until the prompt shows, opens Compare from it, checks both sides'
rows, Next, Previous and Shift+F7 with wrapping, the other side following a
scroll, and the dark palette's background after a theme switch; Reload from
the prompt loads the file. With `-Dqcaret.screenshots=<dir>` it saves
`change-prompt.png` and `diff-view.png` (the whole screen).
`ReplaceSurfaceTest` (tag `swt`) opens the preview from the dock over a tree
search, types a replacement, unchecks a match, applies, checks the report's
rows and the files, and restores the run from the report; with
`-Dqcaret.screenshots=<dir>` it saves `replace-preview.png` and
`replace-report.png`.
`SwtUiDispatcherTest` checks that a canceled timer does not run and that
cancel after dispose does nothing. `CaretInkTest` checks the caret image color
under each operator for both palettes. `ResourceLeakTest` (tag `swt`) runs
`DeviceData.tracking` over two
warm-up rounds (the second adds one cached `StyledText` line layout) and four
rounds of tab open and close, the tab menu, the editor menu, the toolbar and
the line numbers hidden and shown, both
themes, zoom, Preferences through a theme switch, split with Fold All and Unfold All in both views and the find
bar searching and closed, unsplit, the command palette, the completion list
with the whitespace and line-ending marks on, a HyperSearch from its dialog
into the dock with a group expanded, the replace preview opened over it and
closed, the overview strip hidden and shown, a bookmark comment through its
prompt with the gutter tooltip and the Bookmarks list opened and hidden, the
workspace browser with a directory expanded and its row menu, Quick Open
filtered and closed, a comparison with the file on disk opened, recolored by
both themes and closed, the change prompt and the document chooser opened and
closed, and every menu's `Show`; the live counts per type must return to the warm-ups',
and the theme and zoom steps must replace the primary caret image.
System cursors are left out: GTK SWT moves its cached cursors to a list it
disposes with the Display on each theme switch, so the next request allocates
another.
