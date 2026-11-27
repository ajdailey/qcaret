# Main window

`MainWindow` and the controls around the editors: layout, tabs and tab moves,
menus, the toolbar, the status bar, fonts, placement, Always on Top, file drop,
the macOS application menu and the platform traits, in `ui.swt` of
[qcaret-ui-swt](../README.md).

## Layout

`MainWindow` holds the menu bar, a horizontal `SashForm` with the workspace
browser left of a second horizontal `SashForm`, which holds a vertical
`SashForm` left of the [Outline dock](dialogs-and-docks.md#outline-dock). The
vertical sash holds a `CTabFolder` (one tab per document) over a horizontal
`SashForm` holding the HyperSearch results dock and the Bookmarks list dock side
by side. Below them are the find bar, the `ProgressRow` rows and a status bar.

- A hidden results or Bookmarks dock is an invisible control the sash leaves
  out; with both hidden the folder is maximized. With the browser hidden the
  sash right of it is maximized, and with the Outline dock hidden or collapsed
  the vertical sash is.
- A tab's control is a horizontal `SashForm`, the frame, holding the editors'
  `SashForm` and, for a Markdown tab out of Source, the preview pane
  ([Markdown preview](markdown-preview.md#tab-frame)). The editors' sash holds
  one `EditorPane`'s `control()`, or two after a split (`SWT.HORIZONTAL` side by
  side, `SWT.VERTICAL` one above the other).
- The pane that took the focus last is the tab's focused view; a pane's
  `FocusIn` reports it to the controller, and a view the controller focuses
  becomes it without waiting for the event.
- `attach` connects the controller: menu items run
  `ApplicationController.execute`, and a window-system close request runs
  `closeWindow`.
- `MainWindow` asks the controller to check the open files whenever the shell
  is activated.

## Tabs

`TabStrip` owns the `CTabFolder` and its listeners; an `EditorTab` per
document is the item's data and the controller's `TabView`.

`TabMenu` is the one context menu of every tab: each right click disposes its
items and adds `MenuTable.TAB_MENU` for that tab, enabled from
`CommandStates.tabItems`. Its items act on the tab clicked; File > Close
Multiple Tabs runs the same closes on the selected tab. A tab's close button
and a middle click ask the `DocumentController` to close it.

Dragging a tab within the strip moves it: an item cannot change position, so
the move disposes and recreates it, and the `Selection` that `CTabFolder` sends
for a neighbor while the selected item is disposed is ignored, so the moved tab
stays shown. A tab close ignores that `Selection` too, and the controller
selects the tab shown next (see
[Windows and tabs](../../qcaret-app/docs/windows-and-tabs.md#tab-close)).

A closed tab's panes stop following their models at the close
(`EditorPane.release`). `TabStrip` hands the tab's control to
`DeferredDisposal`, which hides it at once and disposes it in later UI tasks of
at most 20 ms each, so a bulk close's widget teardown, most of its cost on GTK,
stays out of the command's UI task.

### Tab moves between windows

A tab drag uses the strip's `MouseDown` and `MouseUp`, no `DragSource`. A
release on another tab reorders. A release off the tabs asks
`Display.getCursorControl` (through a `PointerProbe` tests replace) for the
control under the point:

- another main window's control moves the tab there, before the tab under the
  point or last;
- no QCaret control and a point outside the source shell opens a new window
  with its corner at the point, which GTK 4 and Wayland place themselves.
  `WindowGeometry.place` moves a window that does not fit there into the work
  area, so a drop near the bottom-right corner opens it flush with that corner
  (416,104 for a 1024x768 window on a 1440x900 Mac).

The shell's data names its `TabStrip`. A moved tab is a new `EditorTab` with new
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

The mouse wheel over the strip or the buttons selects the previous tab (wheel
up) or the next tab (wheel down). `TabRowWheel` adds each event's `count` to a
total and calls `TabList.step` each time the total reaches 3 either way,
subtracting 3. A notch on GTK and win32 sends one event of about 3; a cocoa
trackpad gesture sends about 200 events of 1 or 2. A reversal restarts the
total at the event's count, and a step past the first or last tab clears it
and leaves the selection. `TabList.step` selects as a click does, so the
editor, title and status bar follow, and `CTabFolder` shows the selected tab.

Win32 sends the wheel to the focused control, not the one under the pointer.
`TabRowWheel` is a `Display` filter: it takes a wheel event whose position
lies over the row from any control in the folder's shell and cancels it for
that control, so the row acts on hover and the editor under focus does not
scroll.

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
  plus keypad +), when enabled, whichever control of the shell has focus;
  `KeyBehavior.windowCommand` names the command. On GTK 4 (`Gtk4KeyBehavior`) it runs
  the accelerators too and consumes the key: SWT runs a GTK 4 accelerator from
  the window's shortcut controller after the focused control, and the editor's
  `StyledText` inserted the CR that Ctrl+Shift+M carries. On win32
  (`Win32KeyBehavior`) it runs the
  accelerators too for a key in a `Browser`: SWT's Edge sends a key with Ctrl
  or Alt, or one that types no character, to the `Browser` from WebView2's
  `AcceleratorKeyPressed`, and the window's native accelerators never see it.
  The consumed key is marked handled, so WebView2 does not act on it as well
  (Ctrl+F opened WebView2's own find box).
- Alt and a letter or digit in the Markdown preview's `Browser` reaches no
  menu bar mnemonic on win32 either: SWT's Edge never sends it to the shell as
  `WM_SYSCHAR`. `BrowserBehavior.menuMnemonics` (`Win32BrowserBehavior`)
  consumes such a key that the window filter left and posts
  `WM_SYSCOMMAND` with `SC_KEYMENU` and the key to the shell, through SWT's
  `Control.handle` and `internal.win32.OS.SendMessage` by reflection; Windows
  then opens the menu as it does for Alt and the key. A member that does not
  resolve logs one WARN, and the key opens nothing.
- A `MenuTable.RADIO` command is a radio item. SWT sends `Selection` to the
  item it deselects too, so the listener runs the command only for the item
  selected.
- The Encoding menu holds Reopen with Encoding and Save with Encoding cascades
  (`EncodingCascade`): the common charsets from the start, and an Other
  cascade disabled until `EncodingChoices` arrives from a worker, filled again
  when a keymap change rebuilds the menus. Reopen with Encoding starts with
  Automatic Detection, which calls `ApplicationController.reopenWithDetection`.
- The Window menu lists the windows, when there are several, and the open
  documents, numbered in that order, the first nine as mnemonics. On cocoa it
  starts with Minimize (`CommandId.MINIMIZE`, `Cmd+M`) and Zoom
  (`CommandId.ZOOM_WINDOW`, no key), then a separator; SWT adds neither to an
  application-defined Window menu. Elsewhere `MenuBehavior.systemCommands`
  returns both (`MenuTable.TITLE_BAR_COMMANDS`), so the menu leaves them to the
  title bar; the command palette and the Keys tab list both on every platform.
  `MINIMIZE` runs `MainView.minimize` and is enabled while
  `MainView.minimizeSupported` holds; `ZOOM_WINDOW` runs `MainView.zoom`
  (toggles `Shell.setMaximized`) and is always enabled. `MainWindow` applies
  both to the active shell when it owns it at any depth
  (`ActiveWindow.ownedBy`), else to its own shell.
  `RecentMenus` lists in File > Recent Files the recent files above Open All,
  Clean Up and Clear Recent Files, and in File > Recently Closed the closed
  files above Clear Recently Closed, each rebuilt when its menu shows and
  numbered as the Window menu is; a path's `&` is doubled.
- The View menu's Syntax cascade and the status bar's language popup show the
  app's `SyntaxMenu`: the recently chosen grammars and a separator, Plain Text,
  then one cascade per category. `MainMenuBar` rebuilds the cascade's entries
  above Reload Grammars on each show, and the popup on each click.

### Editor context menu

`EditorMenu` is the context menu of the text views, `MenuTable.EDITOR_MENU`
with the Edit menu's Format, Encode/Decode and Convert Case cascades:
one popup per window, built on its first show (building one per view stalled a
file open under GTK 4 in QLogg) and again after a key change. `SWT.MenuDetect`
on each view's `StyledText` makes that view the tab's focused view; a pointer
request calls `EditorInput.menuFromView`, and a keyboard request opens below
the caret and moves nothing. An item runs through that view's
`EditorInput.execute`, so in a split it acts on the view clicked; a command
the view does not run (the Format commands) goes to
`ApplicationController.execute`, whose active view is the one clicked. The
popup holds the view weakly, so it does not keep a closed document.

### macOS application menu

`AppMenu.hook` passes the window set's actions to `MenuBehavior.hookAppMenu`,
which `CocoaMenuBehavior` wires to the system's application menu; elsewhere it
hooks nothing. `MenuBehavior.systemCommands` names the commands the menu bar
leaves out: About, Preferences and Quit on cocoa, Minimize and Zoom elsewhere.

- About, Quit and Preferences run `ABOUT`, `QUIT` and `PREFERENCES` in the
  window activated last and are left out of the menu bar; Hide, Hide Others and
  Show All stay native. Quit with no target window runs `requestQuit`.
- A system quit request (Cmd-Q, logout) arrives as a Display `SWT.Close`, which
  is vetoed and runs `WindowSet.requestQuit`, so the unsaved-changes prompts of
  every window and the ordered quit always run.
- A file opened with QCaret (Open With, a Dock drop) arrives as
  `SWT.OpenDocument` and goes to `WindowSet.openFile`. A null or empty name
  opens nothing, and a name that does not parse logs a warning, since an
  exception there reaches the event loop. A cold start gets the files as
  events in its first event-loop passes, after it took the session:
  a start that names nothing runs `LaunchDocuments.collect` (`CocoaLaunchDocuments`,
  `DocumentEventLoop`) right after the
  Display, before `AppMenu.hook`, and starts with any files it collects as file
  arguments, keeping the session as a start with file arguments does.
  A launch that hands off to a running instance takes them with
  `LaunchDocuments.await`, which waits up to 1 s for the first event and 200 ms
  after the last ([qcaret-launcher](../../qcaret-launcher/README.md)).
- The Workspace Browser deletes on Backspace too (`BrowserKeys`): the key
  labeled delete on a Mac keyboard sends Backspace; Delete is fn-Delete.
- A tool window has the main menus. The menu bar shows the key window's menus,
  and a window without a menu bar shows only the application menu, so
  `Dialogs.shell`, for each modeless shell, and `DiffWindow` call
  `MenuBehavior.toolWindowMenus` with `ToolMenuBars.of(owner)`, the main
  window's `ToolMenuBars` found through the owner chain. On cocoa that builds a
  `MainMenuBar` over the main window's controller; other platforms build
  none. Its items act on the main window, except Close (`Cmd+W`,
  `MainMenuBar.closesToolWindow`), which closes the tool window and stays
  enabled. Minimize and Zoom act on the tool window, the active shell its main
  window owns (Minimize through
  [`WindowStacking.minimize`](#always-on-top)). The bars sync
  enablement with the main window's and are rebuilt on a keymap change. A
  modal dialog gets none, so no command runs in a main window it blocks.

Measured on macOS 27 arm64: the tool window menus, Minimize and Zoom, and
`open -a` to a running and a stopped instance. Unverified: the Finder Open With
menu, Hide Others and Show All.

## Toolbar

`MainToolBar` is a fixed row under the menu bar, shown unless `ui.toolbar` is
false: New, Open, Save, Save All, Print, Undo, Redo, Cut, Copy, Paste, Find,
Replace, HyperSearch, Zoom In, Zoom Out. The bar has `SWT.RIGHT`, so a text
item's label sits on the icon row; without it GTK 3 puts the label under an
empty icon slot and the row grows 15 px for a Markdown tab.

- Each button runs its command through `ApplicationController.execute`, shows
  the command and its keys as the tool tip, and carries the command as data
  for its accessible name ([Accessible names](#accessible-names)).
- Enablement syncs from `CommandStates` with the menu bar, and `EDIT_STATE` on
  each status read, coalesced with the status bar.
- `MarkdownSwitch` adds Source, Split and Preview at the end while the selected
  tab is a Markdown tab, and `MarkdownToolItems` the Markdown actions button
  after them ([Markdown preview](markdown-preview.md#tab-frame)). The button is
  an `SWT.PUSH` item whose icon draws its down arrow in the toolbar's ink, on
  every platform: win32 draws an `SWT.DROP_DOWN` item's arrow black whatever
  the toolbar's colors (1.36:1 on the dark toolbar, measured at 150 %).
- A click that left the focus on the bar returns it to the pane that had it
  last, the preview or the editor (`PaneFocus`), so Zoom In from the toolbar
  zooms the same pane again.
- `ToolbarOverflow` holds the bar in a clipping composite at no less than its
  preferred width, and shows a More button at the row's end while an item lies
  past the clip. While More shows, the clip ends at the right edge of the last
  item that fits whole beside it, so no item shows cut; `hidden()` lists the
  items past that edge. Its menu lists the hidden items in toolbar order, built when
  it opens: each with its command's title, `MenuIcons` icon, checked and
  enabled state and a disabled item's reason; a `ToolMenu` drop-down as a
  cascade of its own menu. Choosing an item runs the item's `Selection` as a
  click does, a check item flipped first. A GTK 3 toolbar allocated less than
  its preferred width shows its own overflow arrow, whose entries run no
  command, so the bar is never narrowed. Neither GTK 3 nor GTK 4 wraps a
  `SWT.WRAP` toolbar into a second row (measured, Xvfb).
- The row is as high as a hidden probe toolbar holding an icon item and a text
  item, and centers the bar in it, so it keeps one height across tabs. A win32
  toolbar with the Markdown switch's text items is 26 points high against 20
  without them (150 %, measured on Windows 11); on GTK 3 and GTK 4 the two are
  equal (31 and 36 px, measured, Xvfb).

## Status bar and title

The status bar shows a notice for 8 s, the Warnings link to Show Log while a
WARN or ERROR line is unseen, and the selected document's read-only marker,
line and column, selection, encoding, line endings, language and, while a
Markdown preview shows, its renderer (`StatusFields`). A caret move or state change schedules one read on the next
UI turn; further changes before it share that read. While the `BusyCounter`
counts an active operation, `WindowBusyCursor` shows `PlatformTraits.busyCursor` over
the shell, each editor (`EditorPane.setWindowBusy`) and each shell the window
owns, with their `StyledText` controls; a Show filter, added only while busy,
sets it on an owned shell that opens meanwhile
([busy cursor](../../qcaret-app/docs/views-and-commands.md#busy-cursor)).

A left click on the encoding field opens Reopen with Encoding and Save with
Encoding, the Encoding menu's `EncodingCascade`s, and one on the line-ending
field opens LF, CRLF and CR as radio items running the Line Endings commands;
both open below the field as the language popup does, and each field's tool
tip names its action (`StatusMenus`). The encoding popup is built on the first
click and kept, since its Other cascades list every charset; the line-ending
popup is built on each click and replaces the previous one. A click on the
preview field, while it names a renderer, opens Basic and Browser the same way
as radio items running View > Markdown View's commands; Browser is disabled
where it cannot run, with the reason in its tool tip, or in its label where
GTK 4, which drops menu tool tips (`MenuBehavior.label`).

`MemoryGauge` ends the bar while `ui.memoryIndicator` is on and is excluded
from the layout while it is off. It paints `MemoryFields.text` over a bar
filled to `MemoryFields.fill`, mixing the canvas's foreground into its
background at each paint, so it follows the native theme; the tip is its tool
tip. The ink moves toward white or black until it has 4.5 contrast with the
background (`SwtThemeService.readable`): after a live change on cocoa SWT can
report a foreground close to the background. A left click calls `ApplicationController.collectGarbage`. Its width only
grows, so a refresh does not move the fields beside it.

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

- `place` sizes and positions the shell through `WindowGeometry` before `open`,
  as `WindowPlacement` runs it.
  On Wayland (`org.eclipse.swt.internal.gdk.backend` is `wayland`) the position
  is skipped and the size applies. On win32 `computeTrim` overstates the height
  once a menu bar is set, so the window is placed again with the trim measured
  from the sized shell, then fitted into its monitor's work area
  (`WindowGeometry.fit`): a placement without a location keeps the position
  Windows chose at creation, which can put a tall window under the taskbar.
- `open` reads `getMaximized`, opens the shell and passes both to
  `WindowPlacement.afterOpen`. GTK 4 drops a maximize requested before the
  shell maps, so `Gtk4WindowPlacement` maximizes the shell again; the other
  classes do nothing. The launcher's first window, every later window
  (`WindowSet.Factory`) and a theme rebuild's new window open through it.
- Every unmaximized move and resize outside full screen records the normal
  bounds, which `windowBounds` returns for the quit to save.
- `WindowDialogs.rememberSize` gives the resizable windows (`preferences`,
  `shortcuts`, `palette`, `quickOpen`, `hyperSearch`, `replace`, `report`,
  `compare`, `guide`, `text`, `log`) their last size after they open, inside
  the monitor's work area ([dialog placement](dialogs-and-docks.md#dialog-placement)),
  and keeps a size that changed when the window closes.
- The window icon is the `qcaret-*.png` set at 16 to 256 px
  (`Icons.Variant.DARK`).

## Always on Top

View > Always on Top runs `AlwaysOnTop` in app, which calls `MainView.setOnTop`
and reads `onTopSupported`; `MainWindow` passes both to
`WindowStacking.PLATFORM` (`ui.swt.platform`), which `PlatformTraits.stacking`
chooses on first use, after the Display exists. SWT has no public call, so each
implementation reaches SWT internals by reflection and the product compiles
against every platform's SWT jar.
`ArchitectureTest.reflectionOnlyInExceptionClasses` allows reflection only in
these classes, `Gtk4ToolItemSizing`, `CocoaPrintJobNaming`, `Gtk3NativeNames`
([Accessible names](#accessible-names)), all in `ui.swt.platform`, and
`core.io.Flock`; `noSwtInternals` is unchanged.

| Platform | Class | Call | Verified |
|---|---|---|---|
| win32 | `Win32Stacking` | `OS.SetWindowPos` on `Control.handle` with `HWND_TOPMOST` or `HWND_NOTOPMOST`, no move, size or activation | In CI: `WS_EX_TOPMOST` each way |
| cocoa | `CocoaStacking` | `NSWindow.setLevel` on `Shell.window`, floating (3) or normal (0), also on the window's dialogs | In CI: the window's level and a dialog's, each way |
| GTK 3 on X11 | `GtkX11Stacking` | `GTK3.gtk_window_set_keep_above` on `Shell.shellHandle` | Under Xvfb: `_NET_WM_STATE_ABOVE` set and cleared when the window maps |
| GTK 3 on Wayland, GTK 4 | `WindowManagerStacking` | None | Item disabled |

- A member that does not resolve, or a call that fails, logs one WARN and
  leaves the stacking unsupported (`PlatformStacking`); nothing throws to the
  caller. Always on Top, and Window > Minimize on cocoa, are disabled at the
  next menu sync.
- `WindowStackingTest` fails on its platform when an SWT upgrade removes a
  reflected member.
- On a mapped GTK window the toggle is a request to the window manager; Xvfb
  has none, so a live toggle is unverified here.
- Dialogs stay above the window: on GTK a dialog is transient for its parent
  (`WM_TRANSIENT_FOR` measured) and the find bar's history list, an
  `SWT.ON_TOP` shell, is an override-redirect popup; win32 makes a topmost window's owned windows topmost.
  On cocoa a Display `SWT.Show` filter in `CocoaStacking` raises each shell
  opened under a floating window, at any depth, to the floating level, and
  returns it to normal with the window; an `SWT.ON_TOP` shell keeps its level.
  Native file and message dialogs are sheets or modal panels, which AppKit
  stacks above. Only GTK on X11 is measured.
- When unsupported on GTK 3 the item's tool tip names the window manager's
  window menu (`WindowStacking.explainUnsupported`, a no-op on win32 and
  cocoa). SWT drops menu item tool tips on GTK 4.
- Window > Minimize (`MINIMIZE`) calls `WindowStacking.minimize` through
  `MainWindow.minimize`; elsewhere that is `Shell.setMinimized`. SWT makes
  a shell with a parent an AppKit child window of it (`Shell.updateParent`),
  and AppKit takes the parent off screen while a child window is miniaturized.
  `CocoaStacking.minimize` removes a tool window from its owner's children (`NSWindow.removeChildWindow`) before
  it miniaturizes and adds it back above the owner (`addChildWindow`) on
  `SWT.Deiconify`, unless the owner is hidden or minimized then. The title
  bar's button minimizes without `WindowStacking`: a Display
  `SWT.Iconify` filter in `CocoaStacking`, added on the Display current when
  the stacking is created, removes such a tool window from the children and
  brings the owner back (`NSWindow.orderFront`); it rejoins on restore the
  same way. `isVisible` stays true for the hidden owner; `occlusionState`
  shows it. Measured in CI on macOS 15 arm64 and Intel
  (`cocoaMinimizesAToolWindowWithoutItsOwner`, and
  `cocoaKeepsTheOwnerWhenTheTitleBarMinimizesAToolWindow` through
  `performMiniaturize:`).
- While the cocoa stacking is unsupported, Minimize is disabled
  (`CommandStates` reads `MainView.minimizeSupported`, which is
  `WindowStacking.minimizeSupported`: `supported` on cocoa, true elsewhere, so
  Minimize stays enabled on GTK 3 under Wayland and on GTK 4),
  `CocoaStacking.minimize` does nothing, also after
  a failed `removeChildWindow`, and so does the `SWT.Iconify` filter: the title
  bar's button then takes the owner off screen with the tool window, as AppKit
  does by default.

## Full Screen

View > Full Screen (F11; Control+Command+F on macOS) calls
`MainView.setFullScreen` with the opposite of `fullScreen`; `MainWindow` passes
both to `FullScreen` (`ui.swt.platform`), which `PlatformTraits.fullScreen`
creates per window. The state is not saved. While full screen, the bounds
tracking skips every event, so `windowBounds` and `windowMaximized` report the
window before full screen, which a quit saves. Esc does not leave full screen.

| | win32, GTK (`NativeFullScreen`) | cocoa (`CocoaFullScreen`) |
|---|---|---|
| State | The last request: set before `Shell.setFullScreen(true)` and cleared after `setFullScreen(false)`, so the size events win32 sends inside the call count as full screen | `Shell.getFullScreen`, which the title bar button also changes |
| Change | During the call on win32; a window manager request on GTK | An AppKit animation into a space of its own, after the call returns. AppKit ignores a change requested during the animation, so `CocoaFullScreen` holds a request made within `SETTLE_MS` (1 s) of the previous change until then, makes it again while the shell does not show it (at most `RETRIES`), and the check item shows the held request |
| Menu bar | Stays in the window | The system menu bar, shown when the pointer reaches the top edge |

SWT's `Shell.setBounds` and `setSize` leave full screen on cocoa and GTK
(measured in CI and under Xvfb), so nothing resizes the main shell after
`place`. `CaretCommandsSurfaceTest` checks the item, the saved bounds and
maximized state while full screen, and a second toggle during the transition,
on every CI platform; the macOS jobs measured the held request leaving full
screen about 1 s after it was made.

## Theme rebuild

win32 controls read the native theme only when created, so a switch of the
side `ui.theme` resolves to replaces each main window's controls.
`Win32ThemeFollow.showOnWindows` sets the native theme, flushes uxtheme's
cached menu theme (`Win32MenuThemes.flush`, see
[native controls](theme-and-icons.md#native-controls)) and calls
`WindowRebuild.rebuild`, which `WindowViews` implements; GTK and cocoa never
call it ([native controls](theme-and-icons.md#native-controls)).

Each window's controllers hold a `MainWindowSlot`, not the `MainWindow`. The
slot implements `MainView` by forwarding every call to the current window, so
`ApplicationController`, its sub-controllers, their running workers,
navigation history and search results stay across a rebuild. The slot keeps
the busy count and the last dock, editor display, memory, warning, progress
row and Always on Top calls, and `replace` pushes them to the new window.
`MainWindowSlotTest` calls every `MainView` method by reflection and fails on
one the slot does not forward.

`WindowViews` rebuilds one window at a time on later UI turns, and only a
window built under the other side. Each turn does one step, so the longest
block is the window's creation or one tab:

1. Creates a `MainWindow` through `Gui`'s factory, attached to the same
   controller, registered with the event loop and hidden at the old window's
   bounds, and starts `DocumentController.stageViews`.
2. Builds one tab's views in the hidden window per turn (`StagedTabs.step`).
   The old window stays shown, keeps every tab bound and takes all input.
3. Swaps: reads the old window's bounds, maximized, full screen and minimized
   state, the side, dock and results weights and the Workspace Browser's
   expanded folders, calls `MainWindowSlot.replace`, gives the new results
   dock the old one's expanded sets and groups and selected row
   (`ResultsDock.takeState`), then
   `DocumentController.rebindViews`: each tab closes in the old window and its
   editors attach to the staged views, with the split arrangement and weights,
   focused pane, top rows, Markdown preview content and an open completion
   list. An input method composition is first kept as typed text. It then
   opens the new shell, hides the old one and activates the new window when
   the old one was active.
4. Disposes the old shell on the next turn.

A staged pane follows its document and selections, since `DisplayProjection`
takes one listener per pane. Its `StagedInput` holds the width, viewport,
overview height and modified-line reports the hidden pane would send to the
editor, and forwards the last of each at the swap. Each step first closes the
staged views of a tab that closed, moved to another window, was split or
unsplit, or was moved out of the staged tabs' order, and stages it again later;
a tab opened during the build is staged at its place. The swap adds views for
a tab still without staged ones, so no tab is lost or shown twice.

The swap waits, checked every 250 ms, while `MainWindowSlot.holdsDialog`
reports a modal dialog or prompt (Quick Open and the command palette included)
or a native file, folder or print dialog (`NativeDialogs`): disposing the shell
would answer for the user or drop the answer. Staging continues meanwhile. The
create turn and the swap both wait while the window shows a menu bar menu,
cascade or context menu (`ShownMenus`, from `SWT.Show` and `SWT.Hide`) or owns a
visible window `WindowViews.holdWhile` marks as holding input. Preferences, the
recovery dialog and the external change prompt hold while open, since closing
them answers for the user (Later, Keep Mine, Cancel). Other windows rebuild
meanwhile. A switch back during a build disposes the hidden
window and leaves the old one; a later switch starts again. The modeless
windows the old shell owns close with it: Statistics and Checksums, whose
`closed` cancels a running count or checksum run, Generate, Tip of the Day,
Keyboard Shortcuts, Show Log, the user guide, replace reports and comparison
windows.

From `rebuild` until the swap, the old window's selected tab keeps its editor
colors (`WindowViews.replacing`, read by `EditorTab.setPalette`): each
`StyledText` color change resets the line cache over the whole document, and
the four colors of a 3 million line file took 2.3 s on the UI thread before the
first build step (measured, GTK 3 under Xvfb). A window that waits for a menu or
a hold takes the colors at once, so Preferences left open shows the new palette.

## Accessible names

`AccessibleName` (`ui.swt.platform`) sets the name a screen reader reads for a
control, and passes a description listener to `AccessibleLabels.describe`. On
cocoa (`CocoaAccessibleLabels`) the name is also the accessible description,
and `attach` adds `ACC.ROLE_GROUP`: VoiceOver reads a `Canvas` without a role as `SWTComposite`
with a blank description and does not read its name. `attachKeepingRole` leaves
the control's own role.

| Control | Name | Role on cocoa |
|---|---|---|
| `TabStripButtons` group | Tab buttons | group |
| New, tab list, Close | The tool tip, with the keys | push button |
| `OverviewStrip` | Overview | group |
| `MemoryGauge` | Memory and its text, such as `Memory 312/4096 MB` | group |
| Each view's `StyledText` (`EditorTab`) | The tab title, following each change | text |

SWT on GTK calls Java accessibility listeners only for the controls it draws,
not for native widgets such as a tool item or a `Tree`. `ToolBarNames.attach`
names each toolbar item that carries a `CommandId` by the command's title, and
`ToolBarNames.name` a `ToolMenu` drop-down added later by its title, through
the toolbar's `Accessible` and through `NativeNames` (`ui.swt.platform`);
`OutlineDock` names its `Tree` Outline through `AccessibleName` and
`NativeNames`. `NativeNames` is `Gtk3NativeNames` on GTK 3, which sets
`accessible-name` on the widget's ATK object through SWT internals (a tool
item's on the GtkButton inside it); its elsewhere `NoNativeNames` sets nothing.
Read through AT-SPI under Xvfb (GTK 3.24.41, GTK 4.14.5):

| Platform | Toolbar icon buttons | Outline `Tree` |
|---|---|---|
| GTK 3 | The command title, such as `Find`; the tool tip is the description | `Outline` |
| GTK 4 | The tool tip, which GTK 4 reads as the name: `Find (Ctrl+F)` | Absent from AT-SPI, items included, with or without a tool tip; SWT 3.135.0 binds no `gtk_accessible_*` call |
| win32 | The command title, from `ToolBarNames`, which SWT asks per item index (unverified) | `AccessibleName` (unverified) |
| cocoa | The item's tool tip | `AccessibleName` (unverified) |

The gutter is painted in the `StyledText` left margin and is no separate
control. A `ToolIcons.item` tool item reads its tool tip as its title on
cocoa (`ToolItem`), and the read-only `StyledText` views of Compare and Replace
in Results take the `Label` before them (`StyledText.getAssociatedLabel`).

## Platform traits

Every platform difference in the view layer goes through `PlatformTraits`
(`ui.swt.platform`), answered by `Win32Traits`, `CocoaTraits`, `Gtk3Traits` and
`Gtk4Traits`; the GTK pair share `GtkTraits`. A value a view class needs is a
`PlatformTraits` method; behavior that differs is a seam interface whose
implementation a `PlatformTraits` method returns. A seam QLogg also has carries
the same names in both apps. `PlatformTraits.PLATFORM` is chosen by `LoadedToolkit` when the
interface is first used: from `SWT.getPlatform()`, and on GTK from the version
SWT records in `org.eclipse.swt.internal.gtk.version`. `LoadedToolkit`
initializes `Display` first, whose static initializer loads SWT's GTK library
and records the version, so the choice holds before a `Display` exists. SWT
3.135.0 falls back in both directions (`OS` static initializer, verified with
`javap`):

| Requested | Fails to load | SWT loads | `LoadedToolkit` |
|---|---|---|---|
| GTK 4 (`SWT_GTK4=1`) | GTK 4 | GTK 3 | `Gtk3Traits` and a WARN |
| GTK 3 | GTK 3 | GTK 4 | `Gtk4Traits`, no WARN |
| Either | Both | Nothing | Logs the `LinkageError` or `SWTError` at DEBUG and picks `Gtk3Traits`; the launcher's `Gui` then ends the launch with exit status 3 |

`PlatformTraits.of` builds any platform's traits, so `PlatformTraitsTest`
checks every value and seam class of all four on Linux, and fails on a boolean
`PlatformTraits` method other than `macKeys` and `linuxKeys`.
`ArchitectureTest.platformOnlyInTraits` fails on a `SWT.getPlatform()` call or
an `SWT_GTK4` string outside that package, and on a `System.getenv` call in
`ui.swt` or the launcher outside it. `swtInternalNamesOnlyInPlatformClasses`
fails on a class outside that package and the reflection classes whose constant
pool holds a string starting `org.eclipse.swt.internal.`, such as a GTK property
name. `behaviorOnlyThroughSeams` fails on a call, outside `ui.swt.platform` and
`core.util.os`, of a boolean `PlatformTraits` or `OsTraits` method other than
the value traits `macKeys`, `linuxKeys`, `fileNamesIgnoreCase`, `hidden` and
`runsInsteadOfOpening`.

`platformQueriesOnlyInThePlatformPackage` fails on a call outside
`ui.swt.platform` of `Display.getSystemMenu`, `getSystemTray`,
`getSystemTaskBar`, `isSystemDarkTheme` or `Program.findProgram`;
`SwtThemeService` reads the system setting through `NativeTheme.systemDark`.
`valueTraitsOnlyInReviewedCallers` lists the callers of each boolean or enum
value trait (`VALUE_TRAIT_CALLERS`) and fails on any other. ArchUnit sees the
call, not the condition around it, so a new caller is reviewed before it is
added: one that passes the value on stays, and one that would choose platform
code calls a seam instead.

### Values

| Method | win32 | cocoa | GTK 3 | GTK 4 | Caller |
|---|---|---|---|---|---|
| `name` | `win32` | `cocoa` | `gtk` | `gtk` | `MainWindow.toolkitDescription` |
| `monospaceFamily`, `monospaceSize` | Consolas 10 | Menlo 12 | Monospace 10 | Monospace 10 | `Dialogs.monospaceName`, `monospaceSize` |
| `caretBlend` | XOR | difference | difference | over | `CaretInk.platform` |
| `toolkitVersion` | empty | empty | `GTK` and the GTK version | As GTK 3 | `MainWindow.toolkitDescription` |
| `shownBackground` | `getBackground` | `getBackground` | A drawn pixel of a visible control | As GTK 3 | `ToolIcons.reink` |
| `macKeys`, `linuxKeys` | -, - | yes, - | -, yes | -, yes | `Gui`, `PreferencesDialog` and `WorkspaceDock`: `Keymap.defaults`, key names, and Backspace deleting in the workspace browser on macOS (`BrowserKeys`) |
| `busyCursor` | `CURSOR_APPSTARTING` | `CURSOR_WAIT` | `CURSOR_APPSTARTING` | `CURSOR_APPSTARTING` | `WindowBusyCursor`, `EditorPane` (see [Status bar and title](#status-bar-and-title)) |

### Seams

A seam is an interface in `ui.swt.platform` with one class per platform where
the behavior differs, which a `PlatformTraits` method returns; a stateless seam
also has a `PLATFORM` constant. Callers call every method on every platform; a
class implements each method, with an empty body where nothing happens, and no
default method carries behavior.

| Seam | win32 | cocoa | GTK 3 | GTK 4 | Caller |
|---|---|---|---|---|---|
| `ChromeColors` (`chromeColors(display)`, per window) | `Win32ChromeColors`: the toolbar row, status bar, find bar, progress rows, docks, their fields, trees and tables, and preview notices in neutral grays under dark; checked tool item ink; a tree's selection on the other side | `NativeChromeColors`, no-op | `GtkChromeColors`: icon sets fade disabled tool items on the light ink | As GTK 3 | `MainWindow` (see [Chrome colors](theme-and-icons.md#chrome-colors)) |
| `DialogColors` | `Win32DialogColors`: dialog colors, field hints, light-theme check boxes, drawn grid lines and dark header dividers under a dark start | `NativeDialogColors`: native colors, plain check boxes | As cocoa | As cocoa | `Dialogs.shell`, `Dialogs.gridLines`, `HistoryField`, `DiffWindow`, `SwtThemeService.check` (see [Dialog colors](theme-and-icons.md#dialog-colors)) |
| `DialogTabs` (`dialogTabs(parent)`, per dialog) | `Win32DialogTabs` under the dark native setting: a `CTabFolder` in the dialog colors; else `NativeDialogTabs` | `CocoaDialogTabs`: the native `TabFolder`, keeping keyboard focus on a control across a tab change | `NativeDialogTabs`: the native `TabFolder` | As GTK 3 | `Dialogs.tabs`: Preferences (see [Dialog colors](theme-and-icons.md#dialog-colors)) |
| `ThemeFollow` (`themeFollow(display)`) | `Win32ThemeFollow`: sets the native theme before the first Shell (`NativeTheme.set`), System from the system setting; polls the setting; a change of side sets it again and rebuilds the main windows ([theme rebuild](#theme-rebuild)) | `CocoaThemeFollow`: pins the appearance at start and at each change; polls; `CocoaChrome` recolors shells from the pinned side | `GtkThemeFollow`: sets it for light and dark, switches it live, raises link contrast at creation and at each `SWT.Settings` | As GTK 3 | `SwtThemeService` |
| `MenuBehavior` | `Win32MenuBehavior`: menu icons in the ink of the side pop-up menus show (`Win32MenuThemes.shownDark`); reasons in tool tips | `CocoaMenuBehavior`: no menu icons; the application menu (`hookAppMenu`, `systemCommands`); the main menus on tool windows (`toolWindowMenus`) | `Gtk3MenuBehavior`: blank-padded menu icons; a disabled menu icon on dark shows the faded copy (`disabledOnDark`) | `Gtk4MenuBehavior`: no menu icons (SWT ignores `setImage`); a disabled item's reason in its label | `MainMenuBar`, `AppMenu`, `ToolMenuBars`, `MenuIcons`, `ToolIcons`, `StatusMenus`, `ToolbarOverflow`, `MarkdownToolItems` (see [Menus](#menus)) |
| `ToolItemSizing` | `NativeToolItemSizing` | `NativeToolItemSizing` | `NativeToolItemSizing` | `Gtk4ToolItemSizing`: a tool item's picture keeps the icon's size through SWT internals | `ToolIcons` (see [Icons](theme-and-icons.md#icons)) |
| `KeyBehavior` | `Win32KeyBehavior`: the window filter runs every binding for a key in a `Browser` | `CocoaKeyBehavior`: Alt (Option) and a key types the character | `Gtk3KeyBehavior` | `Gtk4KeyBehavior`: the window filter runs the accelerators | `EditorPane.route`, `MainWindow.dispatchWindowKeys` (see [Menus](#menus)) |
| `BrowserBehavior` | `Win32BrowserBehavior`: follows a top-frame link with no gesture; sends Alt and a letter or digit from the page to the shell as a menu mnemonic | `CocoaBrowserBehavior` | `Gtk3BrowserBehavior`: scrolls to anchors; checks for a stray `about:blank` page | `Gtk4BrowserBehavior`: as GTK 3, and no `Browser` may be created | `BrowserPane`, `LinkGestures`, `BrowserPreview` (see [Markdown preview](markdown-preview.md#browser-pane)) |
| `GuideDisplay` | `BrowserGuideDisplay` | `BrowserGuideDisplay` | `BrowserGuideDisplay` | `NoticeGuideDisplay`: a notice names the file | `MainWindow.showGuide` |
| `PrintSupport` | `Win32PrintSupport`: an empty printer list shows a notice; the dialog opens on its default scope | `CocoaPrintSupport`: the empty-list notice; names the job (`CocoaPrintJobNaming`), dialog on a selection | `Gtk3PrintSupport`: dialog on a selection | `Gtk4PrintSupport`: a PDF file instead of the dialog | `MainWindow.choosePrinter` (see [Printing](printing.md)) |
| `FullScreen` (`fullScreen(shell, ui)`, per window) | `NativeFullScreen` | `CocoaFullScreen` | `NativeFullScreen` | `NativeFullScreen` | `MainWindow` (see [Full Screen](#full-screen)) |
| `WindowStacking` (`stacking()`) | `Win32Stacking` | `CocoaStacking` | `GtkX11Stacking` on X11, else `WindowManagerStacking`; both explain a disabled item | `WindowManagerStacking` | `MainWindow` (Always on Top, Minimize), `MainMenuBar` (tool tip); see [Always on Top](#always-on-top) |
| `WindowPlacement` | `Win32WindowPlacement`: placed again with the trim measured from the sized shell, then fitted into the work area | `NativeWindowPlacement` | `GtkWindowPlacement`: no position under Wayland | `Gtk4WindowPlacement`: no position; maximized again after `open` | `MainWindow.place`, `MainWindow.open` (see [Placement](#placement)) |
| `NativeNames` | `NoNativeNames` | `NoNativeNames` | `Gtk3NativeNames`: names native widgets through ATK | `NoNativeNames` | `ToolBarNames`, `OutlineDock` (see [Accessible names](#accessible-names)) |
| `AccessibleLabels` | `NativeAccessibleLabels` | `CocoaAccessibleLabels`: the name is also the description; group role | `NativeAccessibleLabels` | `NativeAccessibleLabels` | `AccessibleName` (see [Accessible names](#accessible-names)) |
| `LaunchDocuments` | `NativeLaunchDocuments` | `CocoaLaunchDocuments`: collects open-document events at start and before a handoff (`DocumentEventLoop`) | `NativeLaunchDocuments` | `NativeLaunchDocuments` | `Gui`, `HandoffDocuments` (see [qcaret-launcher](../../qcaret-launcher/README.md#startup-and-exit)) |
| `ComboListSelection` | `Win32ComboListSelection`: paints a drop-down combo's open list selection under the dark native setting | `NativeComboListSelection` | `NativeComboListSelection` | `NativeComboListSelection` | every `Combo` at creation (see [win32](theme-and-icons.md#win32)) |

Operating-system checks outside the views go through `OsTraits`
(`core.util.os`). Its values: `fileNamesIgnoreCase`, `fileLock`,
`userSettings`, `launcherDirectory`, `fontDirectories`, `hidden` and
`runsInsteadOfOpening`. Its seams: `DeviceNames` (`deviceNames()`), which on
Windows (`WindowsDeviceNames`) reports a device path and refuses a new name
ending in a dot or space and a checksum entry on another UNC share, and nothing
on Linux and macOS (`FileNames`), for `SafePaths` and `ChecksumCheck`; and
`FileConfinement` (`fileConfinement()`), which on Linux in explicit and portable
mode moves `user.home` to the settings directory and opens the guide outside the
process, and on Windows places WebView2's data in the settings directory, for
`PlatformSetup` (launcher) and `SettingsLocation.embedsGuide` (app). `AboutInfo`
takes About's OS line from `OsTraits.description`. `MainWindow.system` returns
`OsTraits.current()`, which `WorkspaceController` asks whether Open in Default
Application would run a file and `HelpController` passes to
`SettingsLocation.embedsGuide`. `ArchitectureTest.osNameOnlyInTheOsSeam` confines
`os.name` to that package; `Target` (bootstrap) reads it before the launcher
loads.

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
