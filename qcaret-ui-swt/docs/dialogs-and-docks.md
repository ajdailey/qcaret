# Dialogs and docks

The dialogs, windows and docks around the main window, in `ui.swt` and
`ui.swt.search` of [qcaret-ui-swt](../README.md).

## Nested event loops

Dialogs (`Dialogs`, `ErrorDialog`, `AboutDialog`, `TextWindow`) open without a
nested event loop: `open` returns at once and buttons act through listeners.
`showText` and `showLog` open their window modal and owned by the innermost
visible modal dialog when one is open, because GTK gives input only to the
newest modal shell; Licenses opened from About therefore stays usable.

Four calls do run a nested event loop, so code around them must tolerate
re-entrant events: `FileDialog.open`, `DirectoryDialog.open`,
`PrintDialog.open`, and `Clipboard.getContents` on GTK, which
`MainWindow.clipboardText` uses.

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
| `GoToDialog` | Search > Go to Line; modal, without a nested event loop |
| `TabWidthDialog` | Edit > Tab Width > Other: a modal width field; OK answers it, Cancel and Esc close without an answer |
| `ReloadDialog` | Asks before Reload from Disk replaces unsaved changes; modal, Cancel is the default |
| `ReplaceOpenDialog` | Asks before Save As replaces a file another tab holds with unsaved changes |
| `ClearHistoryDialog` | Asks before Clear History |
| `DocumentChooser` | Asks for the document Compare Documents compares with |
| `DateFormatChooser` | Edit > Insert Date and Time: a modal list of formats with the current time; Insert, Enter or a double click inserts the selected one |
| `StatisticsDialog` | Tools > Document Statistics: modeless; the document and selection counts, the percent read or a message, Refresh and Close; Esc closes it and closing cancels the count |
| `HashTextDialog` | Tools > *algorithm* > Generate: modeless; a single-line field (the same text on every platform, unlike a multi-line `Text`), its hash in a read-only monospace field, Copy and Close; Esc closes it |
| `ChecksumDialog` | Tools > *algorithm* > Generate from Files and Verify Checksums: modeless; the source, the lines in a read-only monospace field, a `ProgressRow` with Cancel, a status line, Choose, Copy, Save As and Close; Esc cancels a run while the row shows and closes the window otherwise |
| `TipDialog` | Help > Tip of the Day: modeless; the tip in a read-only text area, the Show tips on startup check box, Next Tip and Close; Esc closes it and closing calls `TipInput.closed` |
| `BookmarkCommentDialog` | A modal one-line field limited to the comment length, starting with the current comment selected; OK answers its text, and Cancel, Esc and closing answer null |

## Shortcuts and Preferences

Help > Keyboard Shortcuts opens `ShortcutsWindow`, a modeless table of every
bound command by menu with a filter field; Copy puts the filtered rows on the
clipboard as `Keymap.reference(filter)` writes them. A second request brings
the open window forward.

Tools > Preferences (the application menu on macOS) opens `PreferencesDialog`, a
modeless window with one tab per `PreferencesForm.Group`, over
`PreferencesInput`.

- The Editor tab's Editor colors list (`EditorColorsChoice`) holds the bundled
  schemes, then the theme files a worker lists; a saved theme file missing from
  the list is added so it stays chosen.
- OK and Apply pass the fields to the controller; a refused field shows its
  message above the buttons, selects its tab and keeps the dialog open.
- Restore Defaults fills every tab and applies nothing until OK or Apply.
- Clear History on the Search and Workspace tab asks through
  `ClearHistoryDialog`, then clears the saved histories, HyperSearch's last run
  and the session's retained searches at once; Cancel on the dialog does not
  restore them.
- The Keys tab lists every command; the key field records one stroke. Add
  refuses a stroke that types or that another command holds and names that
  command; Remove and Default Keys edit the selected command.
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
command on the next UI turn, after the editor has its focus back.

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
- Size and Modified start as wide as their headers and widen to the widest
  text a fill adds; Name takes the rest of the tree's width at each resize,
  down to its header's width. Modified shows the date.
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
  that remain.
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
editable `Combo`s listing the saved history from `FindState`, as `SearchDialog`
does.

- Typing or choosing an entry finds incrementally.
- Enter finds the next match, Shift+Enter the previous one, and Enter in the
  replace field replaces. Escape anywhere in the bar closes it and focuses the
  editor. While a field's list is open, Enter and Escape go to the list.
- Mod+F with the bar open focuses and selects the field.

`SearchDialog` (Search > HyperSearch) is modal: the text with its history,
Match case, Whole word, Regex, the scope, the workspace folder, the directory
with its history and Browse, the include masks with their history, the exclude
masks and Hidden files. The directory fields are enabled for the directory
scopes, the masks for the folder scopes. Search passes a `SearchForm` to the
controller and closes.

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

## Bookmarks dock

`BookmarksDock` shows the `BookmarksInput` model in a virtual `Table` with Line
(from 1), Comment and Text columns under Go To, Edit Comment, Remove, Hide and
the model's summary. Enter and a double click go to the selected row, and
Delete removes its bookmark; a cut of more than 2,000 rows replaces the table,
as in the results dock.

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
row in the gutter color), recolored on a palette change.

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

An `SWTException` or `SWTError` from any browser call, or a page not loaded
within `LOAD_BOUND_MILLIS` (15 s, `LoadBound` on `UiDispatcher.timerExec`),
closes the window and calls `ApplicationController.showGuideElsewhere`; during
`create` it returns null and `showGuide` returns false. The location comes from
the `changed` event: `Browser.getUrl` can throw on win32.
