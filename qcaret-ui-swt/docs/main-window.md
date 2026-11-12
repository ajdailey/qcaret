# Main window

`MainWindow` and the controls around the editors: layout, tabs and tab moves,
menus, the toolbar, the status bar, fonts, placement, Always on Top, file drop
and the macOS application menu, in `ui.swt` of [qcaret-ui-swt](../README.md).

## Layout

`MainWindow` holds the menu bar, a horizontal `SashForm` with the workspace
browser left of a vertical `SashForm`, which holds a `CTabFolder` (one tab per
document) over a horizontal `SashForm` holding the HyperSearch results dock and
the Bookmarks list dock side by side; then the find bar, the `ProgressRow` rows
and a status bar.

- A hidden dock is an invisible control the sash leaves out; with both hidden
  the folder is maximized, and with the browser hidden the vertical sash is.
- A tab's control is a `SashForm` holding one `EditorPane`'s `control()`, or
  two after a split (`SWT.HORIZONTAL` side by side, `SWT.VERTICAL` one above
  the other).
- The pane that took the focus last is the tab's focused view; a pane's
  `FocusIn` reports it to the controller, and a view the controller focuses
  becomes it without waiting for the event.
- `attach` connects the controller: menu items run
  `ApplicationController.execute`, and a window-system close request runs
  `closeWindow`.
- `MainWindow` asks the controller to check the open files whenever the shell
  is activated.

## Tabs

One context menu serves every tab: each right click disposes its items and adds
Close, Close Others, Close to the Left, Close to the Right, Close All, Move to
New Window, Copy Path, Copy File Name and Open Containing Folder for that tab,
enabled from `CommandStates.tabItems`. A tab's close button and a middle click
ask the `DocumentController` to close it.

Dragging a tab within the strip moves it: an item cannot change position, so
the move disposes and recreates it, and the `Selection` that `CTabFolder` sends
for a neighbor while the selected item is disposed is ignored, so the moved tab
stays shown.

### Tab moves between windows

A tab drag uses the strip's `MouseDown` and `MouseUp`, no `DragSource`. A
release on another tab reorders. A release off the tabs asks
`Display.getCursorControl` (through a `PointerProbe` tests replace) for the
control under the point:

- another main window's control moves the tab there, before the tab under the
  point or last;
- no QCaret control and a point outside the source shell opens a new window
  with its corner at the point, which GTK 4 and Wayland place themselves.

The shell's data names its `MainWindow`. A moved tab is a new `Tab` with new
`EditorPane`s over the same projection and selections; widgets do not cross
shells.

### Tab strip buttons

`TabStripButtons` is the folder's top-right control: New, the tab list and
Close, always shown. Each is a `Canvas` painting a `ToolIcons` image
(`tab-new`, `tab-list-down` or `tab-list-right`, `tab-close`) on the palette's
`gutter`, in the ink of the palette (see
[Theme and icons](theme-and-icons.md#palette-application)). New and Close run `NEW` and `CLOSE` through
`ApplicationController.execute`, so Close asks about unsaved changes; their
tool tips name the keys. Close is faded and inert without a tab.

- The list button shows the count of hidden tabs (`CTabItem.isShowing`) beside
  its arrow, read on each strip paint; a count of another width resizes the
  group and the folder lays out again. Its tool tip and accessible name are
  Show All Tabs, with the hidden count when any. It has no key.
- `TabList` fills the list menu on each show: every tab in strip order,
  labeled as the tab, the selected one checked. Separators set the run shown
  in the strip apart from the hidden tabs on either side. Tabs sharing a name
  add their folder. A choice runs `CTabFolder.setSelection` and sends the
  folder `Selection`, the path a tab click takes.
- `ListGlyph` picks the arrow; the down arrow is the default.

SWT's chevron cannot be hidden through public API. `TabStripButtons` sets a
`CTabFolderRenderer` that draws nothing for it and holds its `ToolBar` at zero
width on each resize, so no second list control shows. The strip still reserves
the chevron's width, 34 px on GTK 3 and 43 px on GTK 4 (measured). A
`showList` request opens the list menu.

`CTabFolder` can leave a selected new last tab hidden: it appends the item to
the end of its show order, keeps that order when the tab fits beside the run
already shown, and measures no width for it. Measured on GTK 3 at most window
widths from 600 to 720 px after a close and New, and on the macOS x86_64
runner at 700 px. On each strip paint `TabList.showSelection` turns MRU mode
on and off once for a hidden selected tab; ending MRU mode rebuilds the order
from the first tab shown.

The mouse wheel over the strip or the buttons moves the run of shown tabs one
tab per notch without changing the selection. `CTabFolder.showItem` moves the
run only in MRU mode; otherwise the folder keeps the run that holds the
selection. `TabList.scroll` turns MRU mode on, shows the tabs from the last
down to the new first, lays the folder out through a `Resize` event and turns
MRU mode off, which starts the run at that tab. The selected tab never leaves the strip
(measured on GTK 3 and GTK 4): the run stops with the selection at either end.

## Menus

`MainMenuBar` builds one menu per `CommandGroup` from `MenuTable` and applies
`CommandStates`; it reads no controller getter for item state.

- Each item shows every key of its command from the `Keymap`; only the first
  window binding becomes an accelerator (`SwtKeys.accelerator`, `SWT.MOD1` for
  Ctrl or Command), so editor keys never fire from another control.
- A disabled item's accelerator does not fire on win32 or GTK, so enablement
  syncs on every `documentStateChanged` and on shell activation, not only on a
  menu's `Show`. Showing a menu or running one of its items syncs that menu's
  checks.
- A Display key filter runs window bindings that are not accelerators (Ctrl
  plus keypad +), when enabled.
- The Encoding menu holds Reopen with Encoding and Save with Encoding cascades:
  the common charsets from the start, and an Other cascade disabled until
  `EncodingChoices` arrives from a worker, filled again when a keymap change
  rebuilds the menus. Reopen with Encoding starts with Automatic Detection,
  which calls `ApplicationController.reopenWithDetection`.
- The Window menu lists the windows, when there are several, and the open
  documents. File > Recent Files lists the recent files above Clear Recent
  Files, and File > Recently Closed the closed files above Clear Recently
  Closed, each rebuilt when its menu shows; a path's `&` is doubled.
- The View menu's Syntax cascade and the status bar's language popup show the
  app's `SyntaxMenu`: the recently chosen grammars and a separator, Plain Text,
  then one cascade per category. `MainMenuBar` rebuilds the cascade's entries
  above Reload Grammars on each show, and the popup on each click.

### Editor context menu

`EditorMenu` is the context menu of the text views: one popup per window, built
on its first show (building one per view stalled a file open under GTK 4 in
QLogg) and again after a key change. `SWT.MenuDetect` on each view's
`StyledText` makes that view the tab's focused view; a pointer request calls
`EditorInput.menuFromView`, and a keyboard request opens below the caret and
moves nothing. An item runs through that view's `EditorInput.execute`, so in a
split it acts on the view clicked. The popup holds the view weakly, so it does
not keep a closed document.

### macOS application menu

`AppMenu.hook` wires the system's application menu on cocoa; elsewhere there is
no application menu and `hook` does nothing.

- About, Quit and Preferences run `ABOUT`, `QUIT` and `PREFERENCES` in the
  window activated last and are left out of the menu bar; Hide, Hide Others and
  Show All stay native. Quit with no target window runs `requestQuit`.
- A system quit request (Cmd-Q, logout) arrives as a Display `SWT.Close`, which
  is vetoed and runs `WindowSet.requestQuit`, so the unsaved-changes prompts of
  every window and the ordered quit always run.
- A file opened with QCaret (Open With, a Dock drop) arrives as
  `SWT.OpenDocument` and goes to `WindowSet.openFile`. A null or empty name
  opens nothing, and a name that does not parse logs a warning, since an
  exception there reaches the event loop.

All of it is unverified on cocoa.

## Toolbar

`MainToolBar` is a fixed row under the menu bar, shown unless `ui.toolbar` is
false: New, Open, Save, Save All, Undo, Redo, Cut, Copy, Paste, Find, Replace,
HyperSearch, Zoom In, Zoom Out.

- Each button runs its command through `ApplicationController.execute` and
  shows the command and its keys as the tool tip.
- Enablement syncs from `CommandStates` with the menu bar, and `EDIT_STATE` on
  each status read, coalesced with the status bar.
- A click that left the focus on the bar returns it to the editor.
- The GTK window's minimum width is the menu bar's (437 px measured); the
  toolbar prefers 441 px there, and below its width GTK moves the last buttons
  behind an overflow arrow. Every button's command is also in a menu.

## Status bar and title

The status bar shows a notice for 8 s, the Warnings link to Show Log while a
WARN or ERROR line is unseen, and the selected document's read-only marker,
line and column, selection, encoding, line endings and language
(`StatusFields`). A caret move or state change schedules one read on the next
UI turn; further changes before it share that read. `BusyCounter` shows
`CURSOR_APPSTARTING` while any load, save or conversion runs.

The window title is `CommandStates.windowTitle`, set with the status fields on
the UI turn after a state change. `Gui` calls `showStatusNow` after
`startSession`, so the untitled document's title does not wait for the startup
events to drain.

## Fonts

`setFontSize` creates the new editor font, calls `EditorPane.setFont` on every
editor and disposes the old font. The widgets, the menu bar and the focus stay;
nothing is rebuilt. `setFontFamily` does the same and also moves open
comparison windows, text windows and the replace preview to the family; text
windows and the preview keep the default size.

`EditorFonts.family` resolves the saved family on the UI thread: one that
`Display.getFontList` does not hold, or whose `i` and `W` measure differently,
gives way to `Dialogs.monospaceName` with one WARN per window, and the saved
value stays. The Appearance tab keeps the scanned families `EditorFonts.listed`
finds in one `Display.getFontList` pass, since a font file's family can be
missing from it (macOS: `.SF NS Mono`, `Courier`), and adds a saved family the
list lacks, so OK does not drop it.

## Placement

- `place` sizes and positions the shell through `WindowGeometry` before `open`.
  On Wayland (`org.eclipse.swt.internal.gdk.backend` is `wayland`) the position
  is skipped and the size applies. On win32 `computeTrim` overstates the height
  once a menu bar is set, so the window is placed again with the trim measured
  from the sized shell.
- Every unmaximized move and resize records the normal bounds, which
  `windowBounds` returns for the quit to save.
- `MainWindow.rememberSize` gives the resizable windows (`preferences`,
  `shortcuts`, `palette`, `quickOpen`, `replace`, `report`, `compare`, `guide`)
  their last size, within the monitor's client area, after they open, and keeps
  a size that changed when the window closes.
- The window icon is the `qcaret-*.png` set at 16 to 256 px
  (`Icons.Variant.DARK`).

## Always on Top

View > Always on Top runs `AlwaysOnTop` in app, which calls
`MainView.setOnTop` and reads `onTopSupported`; `MainWindow` passes both to
`WindowStacking.PLATFORM`, one implementation chosen on first use, after the
Display exists. SWT has no public call, so each implementation reaches SWT
internals by reflection and the product compiles against every platform's SWT
jar. `ArchitectureTest.reflectionOnlyInSanctionedViews` allows reflection in
`ui.swt` and the launcher only in these classes and `ToolIcons`;
`noSwtInternals` is unchanged.

| Platform | Class | Call | Verified |
|---|---|---|---|
| win32 | `Win32Stacking` | `OS.SetWindowPos` on `Control.handle` with `HWND_TOPMOST` or `HWND_NOTOPMOST`, no move, size or activation | In CI: `WS_EX_TOPMOST` each way |
| cocoa | `CocoaStacking` | `NSWindow.setLevel` on `Shell.window`, floating (3) or normal (0), also on the window's dialogs | In CI: the window's level and a dialog's, each way |
| GTK 3 on X11 | `GtkX11Stacking` | `GTK3.gtk_window_set_keep_above` on `Shell.shellHandle` | Under Xvfb: `_NET_WM_STATE_ABOVE` set and cleared when the window maps |
| GTK 3 on Wayland, GTK 4 | `WindowStacking.NONE` | None | Item disabled |

- A member that does not resolve, or a call that fails, logs one WARN and
  leaves the stacking unsupported (`PlatformStacking`); nothing throws to the
  caller, and the item is disabled at the next menu sync.
- `WindowStackingTest` fails on its platform when an SWT upgrade removes a
  reflected member.
- On a mapped GTK window the toggle is a request to the window manager; Xvfb
  has none, so a live toggle is unverified here.
- Dialogs stay above the window: on GTK a dialog is transient for its parent
  (`WM_TRANSIENT_FOR` measured) and the find bar's combo list is an
  override-redirect popup; win32 makes a topmost window's owned windows topmost.
  On cocoa a Display `SWT.Show` filter in `CocoaStacking` raises each shell
  opened under a floating window, at any depth, to the floating level, and
  returns it to normal with the window; an `SWT.ON_TOP` shell keeps its level.
  Native file and message dialogs are sheets or modal panels, which AppKit
  stacks above. Only GTK on X11 is measured.
- When unsupported on GTK 3 the item's tool tip names the window manager's
  window menu. SWT drops menu item tool tips on GTK 4.

## File drop and the load row

`MainWindow.acceptDroppedFiles` makes the shell a `DropTarget` for
`FileTransfer` only, so a drag offering no file is never accepted; the
platforms pass a drop over a child to the nearest target above it.

- Every drop is a copy, also after the user changes the operation mid-drag:
  accepting a move has the source delete the file.
- `dropped` parses the names and calls `ApplicationController.openDropped`; a
  name that does not parse shows the open failure and the rest open. A dropped
  folder is ignored ([workspace folder and browser](../../qcaret-app/docs/workspace-and-navigation.md#workspace-folder-and-browser)).
- The editor has no `DragSource`: a drag in the text selects.

| Platform | Drop |
|---|---|
| GTK 3 | Measured with a real XDND source in another process: a drop over an empty window and over the `StyledText` opens a tab with a copy, and a text drag is refused. A drag from the workspace browser opens over the `StyledText` and the tab strip (`WorkspaceDockLayoutTest`) |
| GTK 4 (`SWT_GTK4=1`) | A drop from another process reaches no target. A drag from the workspace browser opens over the `StyledText` and the tab strip (`WorkspaceDockLayoutTest`) |
| win32, cocoa | Unverified |

A `ProgressRow` shows what `DocumentsView.showLoadProgress` passes, above the
status bar, and takes no room while hidden. While it shows, a Display `KeyDown`
filter sends Esc from any control of the shell to the cancel and consumes it,
which covers a window with no tab and keeps Esc from also dropping carets.
