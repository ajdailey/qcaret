# Dialogs and docks

The dialogs, windows and docks around the main window, in `ui.swt` and
`ui.swt.search` of [qcaret-ui-swt](../README.md).

## Nested event loops

Dialogs (`Dialogs`, `ErrorDialog`, `AboutDialog`, `TextWindow`) open without a
nested event loop: `open` returns at once and buttons act through listeners.
`showText` and `showLog` open their window modal and owned by the innermost
visible modal dialog when one is open, because GTK gives input only to the
newest modal shell; Licenses opened from About therefore stays usable.
`ErrorDialog` escapes `&` in its message, so a file name such as `R&D.log`
shows whole; a caller passes the text unescaped.

Four calls do run a nested event loop, so code around them must tolerate
re-entrant events: `FileDialog.open`, `DirectoryDialog.open`,
`PrintDialog.open`, and `Clipboard.getContents` on GTK, which
`MainWindow.clipboardText` uses. Open a native dialog through `NativeDialogs`,
which counts it on its top-level shell so a window rebuild waits for it;
`ArchitectureTest` fails any other `Dialog.open` call. `PrintSupport.choose`
takes `NativeDialogs::open` as its opener.

## Dialog placement

`Dialogs.open` centers a dialog over its owner, and `Dialogs.center` places a
size the same way. Both clamp the size to the work area of the owner's monitor
(`Monitor.getClientArea`), then move the window inside it
(`WindowGeometry.fit`), so a preferred height past the taskbar at 150 % keeps
the buttons on screen. `WindowDialogs.rememberSize` places a window after it
opens through `Dialogs.center`, at its remembered size or its own, which covers
the `ui.swt.search` windows that open themselves (`ReplaceDialog`,
`ReportWindow`). `DiffWindow` and the replace run chooser go through
`Dialogs.center` too.

## Window icons

Every dialog and tool window shows the main window's icon set.
`Dialogs.inheritIcons(shell, owner)` copies `owner`'s images when it has any,
so a dialog opened from a dialog takes them from the owning dialog.
`Dialogs.shell` calls it, and `DiffWindow` calls it for the shell it builds
itself. `ReportWindow`,
`RunChooser` and `ReplaceDialog` sit in `ui.swt.search`, which cannot reach
`Dialogs` without a package cycle, so `WindowDialogs` builds their shells with
`Dialogs.shell` and passes them in. The images stay owned by `MainWindow`, which
disposes them; a dialog never disposes them. `ArchitectureTest` fails a code
unit that creates an owned `Shell` without calling `inheritIcons`; the main
window's top-level shell and the borderless `CompletionPopup` are exempt.

GTK 3 also copies a transient parent's icon to a shell that has none; GTK 4
does not, so there a shell built without `inheritIcons` has no `_NET_WM_ICON`.

Native dialogs (`FileDialog`, `DirectoryDialog`, `PrintDialog`) take no images
through SWT's API, so their icon is the platform's. Measured under Xvfb on GTK
3: the file, folder and print dialogs carry the owner's icon through the
transient parent.

## Prompts

| Class | Behavior |
|---|---|
| `FileDialog` (native) | Open, Save As, Save a Copy As, the checksum file choices and Print to PDF (GTK 4); runs a nested event loop |
| `DirectoryDialog` (native) | File > Set Workspace Folder and the HyperSearch dialog's Browse; runs a nested event loop |
| `UnsavedDialog` | Save, Don't Save and Cancel answer through a callback; closing it answers Cancel |
| `BinaryDialog` | Opens the read-only view on Open Read-Only; Cancel is the default and closing it declines |
| `GoToDialog` | Search > Go to Line; modal, without a nested event loop; OK is enabled while the controller's validator accepts the text |
| `TabWidthDialog` | Edit > Tab Width > Other: a modal width field; OK answers it, Cancel and Esc close without an answer |
| `ReloadDialog` | Asks before Reload from Disk replaces unsaved changes; modal, Cancel is the default |
| `OpenLinkDialog` | Asks before a Markdown preview link opens a web address (`MainView.askOpenUrl`): the full address in a read-only field, Open and Cancel; modal, without a nested event loop, Cancel is the default and has the focus |
| `ReplaceOpenDialog` | Asks before Save As replaces a file another tab holds with unsaved changes |
| `ClearHistoryDialog` | Asks before Clear History |
| `DocumentChooser` | Asks for the document Compare Documents compares with |
| `DateFormatChooser` | Edit > Insert Date and Time: a modal list of formats with the current time; Insert, Enter or a double click inserts the selected one |
| `NumberSequenceDialog` | Edit > Insert Number Sequence: modal start, step, width and base fields returned as a `SequenceForm`; OK is enabled while the controller's validator accepts them |
| `StatisticsDialog` | Tools > Document Statistics: modeless; the document and selection counts, the percent read or a message, Refresh and Close; Esc closes it and closing cancels the count |
| `HashTextDialog` | Tools > *algorithm* > Generate: modeless; a single-line field (the same text on every platform, unlike a multi-line `Text`), its hash in a read-only monospace field, Copy and Close; Esc closes it |
| `ChecksumDialog` | Tools > *algorithm* > Generate from Files and Verify Checksums: modeless; the source, the lines in a read-only monospace field, a `ProgressRow` with Cancel, a status line, Choose, Copy, Save As and Close; Esc cancels a run while the row shows and closes the window otherwise |
| `TipDialog` | Help > Tip of the Day: modeless; the tip in a read-only text area, the Show tips on startup check box, Next Tip and Close; Esc closes it and closing calls `TipInput.closed` |
| `BookmarkCommentDialog` | A modal one-line field limited to the comment length, starting with the current comment selected; OK answers its text, and Cancel, Esc and closing answer null |

## Shortcuts and Preferences

Help > Keyboard Shortcuts opens `ShortcutsWindow`, a modeless table of every
bound command by menu with a filter field; Copy puts the filtered rows on the
clipboard as `Keymap.reference(filter)` writes them. A second request brings
the open window forward. It opens at least 560 px wide and as wide as its
packed columns need, within the screen (`TableFit.dialogWidth`); a remembered
size overrides that. A cell cut off shows its full text as the table's tool tip
(`TableFit.cellTips`). Its table and the Preferences Keys table take their grid
lines from `Dialogs.gridLines`, dark in a dark win32 dialog ([Dialog
colors](theme-and-icons.md#dialog-colors)).

Tools > Preferences (the application menu on macOS) opens `PreferencesDialog`, a
modeless window with one tab per `PreferencesForm.Group`, over
`PreferencesInput`. The tab folder comes from `Dialogs.tabs`: a dark
`CTabFolder` after a dark win32 start, else the native `TabFolder` ([Dialog
tabs](theme-and-icons.md#dialog-tabs)). After a dark start the selected tab
shows its own color only while focus is in the folder.

- The Editor tab's Editor colors list (`EditorColorsChoice`) holds the bundled
  schemes, then the theme files a worker lists; a saved theme file missing from
  the list is added so it stays chosen.
- The View tab (`PreferencesViewTab`) holds the six boxes of what a new window
  starts with under one heading and the five boxes every window follows under
  another.
- The Search and Workspace tab's `WorkspaceDefaultRow` shows
  `workspace.default` in a read-only field; Choose opens a `DirectoryDialog`
  and Clear empties the field.
- OK and Apply pass the fields to the controller; a refused field shows its
  message above the buttons, selects its tab and keeps the dialog open.
- Restore Defaults fills every tab and applies nothing until OK or Apply.
- Clear History on the Search and Workspace tab asks through
  `ClearHistoryDialog`, then clears the saved histories, HyperSearch's last run
  and the session's retained searches at once; Cancel on the dialog does not
  restore them.
- The Keys tab lists every command; the key field records one stroke and Clear
  empties it. Add refuses a stroke that types or that another command holds
  and names that command; Remove and Default Keys edit the selected command,
  and Restore All Defaults drops every override. The tab's mnemonics differ
  from each other and from the dialog buttons'.
- `setKeymap` rebuilds the menu bar and hands the keymap to every `EditorPane`
  and an open `ShortcutsWindow`.

## Recovery dialog

`RecoveryDialog` lists a dead instance's buffers over `RecoveryInput`: file,
write time and how the original stands, all rows selected. Restore and Discard
act on the selected rows; Later and closing the dialog keep the rest for the
next start. It is modeless and closes when no row is left.
`EditorPane.scrollToRow` waits for the first resize when the view has no size
yet, which is every hidden tab of a restored session.

## Command palette and Quick Open

View > Command Palette opens `CommandPaletteWindow`, a modal dialog with a
filter field over the rows `PaletteInput.filter` returns: command, menu and
keys, a disabled command in gray. Up, Down, Page Up and Page Down in the field
move the selection; Enter or a double click closes the dialog and runs the
command on the next UI turn, after the editor has its focus back. It is fitted
to its columns as Keyboard Shortcuts is, at least 520 px wide.

File > Quick Open and Window > Switch Document open `QuickOpenWindow`, the same
kind of dialog over `QuickOpenInput`: name and path columns and a summary line,
refilled on each field change and on `quickOpenChanged`; an open document's row
is in bold. Enter passes the row to the controller on the next UI turn; closing
the dialog cancels a running match.

## Workspace browser

`WorkspaceDock` is the workspace browser: a row of icon tool bars, the
`BrowseBar` fields and a `Tree` with Name, Size and Modified columns.

- The icons are Parent, Home, Favorites, Workspace, Refresh, Reveal Active
  File and Hide, each a push item alone on a flat `ToolBar` from
  `ToolIcons.item`, with its name as the tool tip and accessible name; a push
  button draws a dark face under the win32 dark theme
  ([theme and icons](theme-and-icons.md#win32)). Each tool bar is a tab stop
  (`ToolIconsTest`); Space runs its item, measured on GTK 3 and win32 x86_64,
  and Enter also does on win32. The row is a wrapping `RowLayout`, so a dock too
  narrow for one row puts the rest on a second. `BrowserSideWidth` sets the
  dock's default width: a quarter of the window, widened to the row's one-row
  width, up to half. The default follows each resize until session weights are
  restored or a sash drag changes them (`WorkspaceSurfaceTest`). The row is
  the icons' `ToolIcons` owner; `MainWindow` re-inks it after a theme change
  and when the dock shows.
- `BrowseBar` holds the first four icons, the path field, the filter field
  and the Hidden check box (`SwtThemeService.check`). The path field shows the
  shown folder; its tool tip says whether that is the workspace folder, and
  Workspace is enabled only when it is not. Both fields ask for no width of
  their own, so a long path never widens the dock or pushes Hidden out. Enter
  in a field passes its text to `WorkspaceBrowseInput`; each
  `workspaceChanged(null)` sets the fields, the check box and Workspace's
  enablement from it. Favorites builds its pop-up menu at each opening from
  `favorites()`.
- On win32 the window's `ChromeColors` color the tree, its header and the two
  fields, which draw light under the dark theme: while `ThemeController.dark`
  holds they take `Palette.DARK`'s background and text, the header its gutter,
  and after a switch to light they return to the system colors; under the dark
  native setting a field holding text stays dark until its window is rebuilt,
  and an empty one takes the system colors, colored again when it takes text or empties. A dark
  field's empty, unfocused hint is drawn by `FieldHint` in `Palette.DARK`'s
  unselected tab label color (5.9:1), since Windows draws the native hint in
  the theme's gray (1.9:1). The Hidden check box keeps the native theme
  ([theme and icons](theme-and-icons.md#win32)).
- `BrowserColumns`: Size and Modified start as wide as their headers and widen
  to the widest text a fill adds; Name takes the rest of the tree's width at
  each resize, down to its header's width. Modified shows the date.
- `BrowserRowTip` sets the tree's tool tip to the row under the pointer: the
  full path, then `Size:` for a file and `Modified:` with the date and time, one
  per line, from the row's `WorkspaceNode`. It sets the tip only when the
  pointer reaches another row or leaves the rows, so the tip does not flicker
  within a row.
- A column header click calls `sort` with the column, descending when it
  already sorts ascending; the tree shows the sort column and direction.
- A directory row holds an empty placeholder child until it first expands,
  which asks `WorkspaceInput.expand` to list it; the listed rows replace the
  placeholder on `workspaceChanged`, and a refill keeps the expansion of rows
  that remain. `reveal` expands the chain's directories but not its last row: a
  renamed or new folder it selects is not listed, and a refill would leave the
  row no child and so no expand arrow.
- The tree is `SWT.MULTI`: Ctrl-click (Cmd-click on macOS) toggles a row and
  Shift-click selects a range. `BrowserSelection` passes the selected rows and
  the focused row to `WorkspaceSelectionInput`, which enables and runs the row
  menu and keys; the focused row is the one the last selection event named, or
  the selected row a right click opened the menu on, else the first selected.
- Enter or a double click acts on the focused row: it opens a file, shows more
  entries on a "more" row and toggles a directory.
- `BrowserDrag` makes the tree a `FileTransfer` `DragSource` offering Copy
  only, with the selected file rows' paths; folders are left out and a
  selection without a file starts no drag. A drop on a QCaret window reaches the
  shell's [file drop](main-window.md#file-drop-and-the-load-row) target. The
  source handles no `dragFinished`, so the file stays when a target reports
  Move, which a GTK 3 target can (measured). A press on one of several
  selected rows drags them all on GTK 3; GTK 4 SWT selects the pressed row
  alone first, so one file goes (measured).
- One context menu, built on the first `MenuDetect`, serves every row; a
  right click over empty space clears the selection and the menu acts on the
  shown folder. `WorkspaceSelectionInput.enabled` sets each item from the
  selection before the menu shows.
- Plain Delete and F2 delete and rename the selected rows from a `KeyDown`
  listener on the tree. No default window binding uses either key
  (`KeymapTest`), so the `MainWindow` key filter does not take them, and the
  editor runs its own F2 binding only with the focus in the editor.
- `FileNameDialog` asks a name for New File, New Folder and Rename, the old
  name selected up to its extension. `DeleteDialog` asks before a delete, and
  `OpenManyDialog` before Open in Default Application on more than 10 files,
  each with Cancel as the default button and the focus.
- The tree is not virtual: GTK's virtual `Tree` is quadratic in a node's child
  count (measured), and a directory shows 500 rows at a time.

## Find bar and HyperSearch dialog

`FindBar` sits above the status bar while `FindController` is open: the search
field, Match case, Whole word, Regex, Previous, Next, the notice and Close,
plus a replace row (field, Replace, Replace All) for Replace. Both fields are
`HistoryField`s listing the saved history from `FindState`: a `Text`, an arrow
button, and a list in an `SWT.ON_TOP | SWT.NO_FOCUS` shell, as `CompletionPopup`
uses, so the field keeps the keyboard. The `Text` sits inside the field's
composite, not beside its label, so `HistoryField` names it for screen readers
from the `Label` created just before it in the same parent, and names the arrow
`FIND_HISTORY`. Do not replace it with a drop-down
`Combo`: a cocoa `NSComboBox` list runs a tracking loop on the UI thread that
outlives its window, which keeps the process running after a quit with the list
open; that list also takes no Enter and searches on each highlight.

- Typing or choosing an entry finds incrementally; moving the list's highlight
  with Up and Down does not.
- Enter finds the next match, Shift+Enter the previous one, and Enter in the
  replace field replaces. Escape anywhere in the bar closes it and focuses the
  editor. While a field's list is open, Enter puts the highlighted entry in the
  field and Escape closes the list; each consumes its key.
- A click outside the list, a focus change, and a move, resize or deactivation
  of the window close the list. The list's shell is a child of the window, so
  it closes with it.
- Mod+F with the bar open focuses and selects the field.

`SearchDialog` (Search > HyperSearch) is modal and resizable: the text with its history,
Match case, Whole word, Regex, the scope, the workspace folder, the directory
with its history and Browse, the include masks with their history, the exclude
masks and Hidden files. The text, directory and include fields are
`HistoryField`s; while a list is open, Enter and Escape go to it and do not
traverse, so they neither press Search nor close the dialog. The scope is a
read-only `Combo`. The directory fields are enabled for the directory scopes,
the masks for the folder scopes. Search passes a `SearchForm` to the controller
and closes.

## Results dock

`ResultsDock` (`ui.swt.search`) shows the `ResultsInput` model as an indented
tree in a virtual `Table`: set rows, file group rows and match rows, a `+` or
`-` before a set or group. A virtual table changes only its row count, where
GTK's `Tree` took seconds to expand and clear 10,000 children (measured under
Xvfb); `ResultRows` maps rows to paths.

- A click on the mark, Enter, a double click, Right and Left expand and
  collapse; Left on a child selects its parent; Enter and a double click on a
  match open it.
- A match row paints the matched text over the palette's find-match color in
  the palette's text color; a stale row shows in the disabled color.
- Stop, Rerun, Replace, Pin and Clear act on the selected row's set, Rerun File
  on the selected row's group while it is stale, Clear All on every set, and
  Hide hides the dock.
- A cut of more than 2,000 rows replaces the table, since GTK removes rows one
  at a time (seconds for 100,000, against about 0.1 s for a new table).
- `ResultsMenu` is the table's context menu (`ResultsInput.MENU`), built on
  the first `MenuDetect` and disposed with its table. A pointer request first
  selects the row under the pointer, or clears the selection over empty space.
  Open opens a match,
  or a group's first match; Copy copies the selected row's matches; Copy All
  Results every set; Rerun, Pin (a check item) and Remove Set (Clear) act as
  the buttons, on the selected row's set or the newest set. Expand All and
  Collapse All change the selected row's set and every group in it, and do not
  check the groups' files, which expanding one group does.

## Bookmarks dock

`BookmarksDock` shows the `BookmarksInput` model in a virtual `Table` with Line
(from 1), Comment and Text columns under Go To, Edit Comment, Remove, Hide and
the model's summary. The buttons and summary sit in a `DockBarLayout`, which
starts a new row for a control that does not fit after the others and wraps the
summary's text where it is wider than the dock. `TableFit.spread` keeps Line at
its width and gives Comment and Text the rest of the table's width at each
resize, in proportion to their starting widths (260 and 480), less a hidden
vertical scroll bar's width, so the table shows no horizontal scroll bar. It
sets the columns that narrow before those that widen: widening across the
switch to header minima with Comment first took the columns past the table for
a moment, and win32 then kept a horizontal and a vertical scroll bar under
columns that fit (Windows 11 at 150 %). At a fractional zoom it reads the zoom
from a numeric `swt.autoScale` value before the monitor's. While
the rest holds both header texts plus `TableFit.HEADER_PADDING`, neither column
is narrower than its header text: under a dark theme on win32 the header colors
make SWT 3.135.0 draw the header itself, which clips the text with no ellipsis
and aligns it by the column's style. Line is left-aligned, because win32 always
shows a table's first column left-aligned while that custom draw would follow
an `SWT.RIGHT` style. Enter and a double click go to the selected row, and
Delete removes its bookmark; a cut of more than 2,000 rows replaces the table,
as in the results dock. `BookmarksMenu` is the context menu
(`BookmarksInput.MENU`: Go To, Edit Comment, Copy Line, Remove, Clear All),
built and selecting its row as `ResultsMenu` does; Clear All is enabled while
the list has rows. On win32 the window's `ChromeColors` color this table and the
Search Results table under the theme, as the Outline tree.

## Outline dock

`OutlineDock` shows the `OutlineInput` model, the selected Markdown tab's
headings, as a `Tree` under Hide and the model's summary (a `DockBarLayout`, as
in the Bookmarks dock), right of the editors and the bottom docks. View >
Markdown View > Outline and the same item in the toolbar's Markdown actions
menu show and hide it; the window's session keeps it shown. While shown it
collapses on a tab that is not Markdown, or with no tab, and the editors and
bottom docks take its width; `MarkdownOutline` decides, and the check item stays
on.

- A heading's parent is the closest earlier heading of a higher level, so an
  H3 under an H1 is one step in. Every level shows expanded.
- A click on a heading's text calls `goTo` and keeps the focus in the tree;
  Enter and a double click call it and focus the tab's view (the preview in
  Preview). Arrows and the
  platform's type-ahead move the selection only. `outlineSelected` selects the
  heading holding the caret without an event.
- `refresh` keeps the items before the first entry whose parent changed, with
  their expansion, and sets their text; the rest are disposed and created
  again. Creation runs in slices of 500 items, one per UI turn through
  `UiDispatcher.asyncExec`, and a newer refresh abandons the running fill. A
  cut of more than 2,000 items clears the tree, as the other docks do.
- GTK appends a row by walking its siblings, so appending a long sibling list
  is quadratic: 10,000 headings under one H1 took 2.1 s with turns up to
  189 ms (measured, Xvfb). `OutlineDock` creates each parent's new children
  last first, inserted after its kept children (a prepend when it has none),
  and expands the rows once the fill ends: 178 ms, longest turn 13 ms.
- The tree is not virtual: GTK disables type-ahead in a virtual `Tree`.
- On win32 the window's `ChromeColors` color the tree under the theme, as the
  workspace browser's tree; elsewhere the native tree follows the theme.

## Replace windows

`ReplaceDialog` (`ui.swt.search`) is the replace preview: modal and resizable,
the replacement field, the files and the selected file's matches as virtual
check tables, the before and after view in a monospace `StyledText` (removed
lines over a red tint of the editor background, added lines over a green one),
the summary and Apply.

`ReportWindow` is a run's report, modeless: the summary, a virtual table of
file, folder and result (skips and failures in dark red), Stop, Restore Run and
Close; closing it does not stop the run. `RunChooser` lists the runs kept for
Restore Replace Run.

## Change prompt and comparison

`ChangeDialog` asks about a file another program changed: Keep Mine, Reload and
Compare, or, for a save that found the change, Overwrite, Reload, Compare and
Cancel. It is modeless, so the comparison window it opens takes input; Compare
leaves it open, and closing it answers Keep Mine, or Cancel for a save.

`DiffWindow` shows a `Comparison`: a modeless window owned by the main window,
two read-only monospace `StyledText` controls in a `SashForm`, each row's
background from `Palette.diffRemoved`, `diffAdded` and `diffChanged` (a filler
row in the gutter color), recolored on a palette change. Each side's content is
a `ShownContent` over the widget's own, and `ControlBoxes` draws its control
chars ([editor pane](editor.md#control-chars)); copy reads the original chars.

- After a scroll bar move, a caret move or a paint, each side copies its top
  line and horizontal offset to the other when they differ from the last
  position either side reached; the side that moved leads, so a stale paint of
  the other side does not pull it back.
- It opens at the first difference; Next and Previous Difference, F7 and
  Shift+F7, scroll both sides to a run of changes three rows below the top,
  wrapping; Esc closes it.

## User guide window

`GuideWindow` is a modeless window, one per main window, over an SWT `Browser`
with JavaScript off. A `LocationListener` refuses every location but the
guide's file, with or without an anchor, and `about:blank`.

| Platform | Behavior |
|---|---|
| GTK 3 | WebKitGTK; without it `Browser` creation fails and the fallback runs. In explicit and portable mode `HelpController` does not call `showGuide` |
| GTK 4 (`SWT_GTK4=1`) | `MainWindow.showGuide` shows a notice naming the file and creates no `Browser`: WebKitGTK 6.0 aborts the JVM ("Failed to fully launch dbus-proxy"). DO NOT CREATE ONE THERE |
| cocoa | WebKit opens a modal "Page Load Failed" alert for a missing page, so the page is written before the load. An SWT timer runs inside any nested run loop a native call spins, so a bound that expires during this window's own `setUrl` or `Shell.open` closes the window after the call returns (`calls`, `deferredFailure`) |
| win32 | WebView2 creation can stall; its data folder is `<settings>/cache/webview2` (`PlatformSetup`), inside the settings directory in every mode |

An `SWTException` or `SWTError` from any browser call, or an expired bound,
closes the window and calls `ApplicationController.showGuideElsewhere`; during
`create` it returns null and `showGuide` returns false. Each `setUrl` arms the
start bound, `START_BOUND_MILLIS` (60 s); the guide's own navigation
(`changing` on its `file:` URL) replaces it with the load bound,
`LOAD_BOUND_MILLIS` (15 s), and the loaded page meets either. Both are
`LoadBound`s on `UiDispatcher.timerExec`, and `BrowserPane` uses the same two.
The start bound covers the WebView2 start of a new browser control on win32,
measured at 6-16 s on a freshly booted CI runner. The location comes from the
`changed` event: `Browser.getUrl` can throw on win32.
