# Views and commands

The command line, the view contracts, commands, key bindings and menu state, in
`app.cli`, `app.view`, `app.command` and `app.controller` of
[qcaret-app](../README.md).

## Command line

`CommandLineParser` turns the arguments into a `CommandLine`: an action (run,
help, version), the `-d` level, `-l`, and a `StartupRequest` holding the files,
the `--window-*` geometry, `--settings`, `-m`, `-n`, `--session` or
`--no-session` (`SessionMode`) and the two `--diff` files.

- Each `FileRequest` carries its `+<line>[:<column>]`, which goes to the next
  file only, and the `-r` and `-e` in force, which apply to every later file.
- It accepts `--opt value`, `--opt=value`, `-o value`, combined short options
  (`-ml`, `-md2`) and `--`, after which every argument is a file, a `+<line>`
  too. `--diff` takes the next two arguments. `-e` is checked against the
  runtime's charsets.
- Paths resolve against the working directory without touching the file system;
  the caller validates them.
- An unknown option, a bad value, a position with no file after it, or `-n`
  with `-m` throws `CommandLineException` with one line naming the argument.

`StartupRequest.placesWindow` reports whether any `--window-*` option was
given; `restoresSession` applies the session default. `Version.get` reads
`Implementation-Version` from this module's jar manifest.

## UI thread

`UiDispatcher` is the only route onto the UI thread: `asyncExec` from any
thread, `timerExec` and `cancelTimer` on the UI thread, no synchronous variant.
Submissions after disposal are dropped.

## Busy cursor

A user-started operation on a worker shows the busy cursor while it runs, or
shows its own progress. `MainWindow` shows `SWT.CURSOR_APPSTARTING` on the
shell while its `BusyCounter` counts an active operation; each
`EditorController` has its own `BusyCounter` behind `EditorView.setBusy`, which
sets the cursor over the text, where the shell's does not show. Never
`CURSOR_WAIT`: input is never blocked.

`BusyWork` raises the counter at `start` and runs `work` on a worker, then
`finished` on the UI thread after `work` returns or throws and after every task
`work` posted before it returned; the counter goes idle after `finished` on
every path. A refused start returns false with the counter idle and `finished`
not run. A new user-started operation uses it.

| Cursor | Operations |
|---|---|
| Window, through `BusyWork` | Save As and Save a Copy target checks, the copy write, save cleanup, `FileReload`, `DocumentEncodings`, `SelectionDelete`, `RecentFiles` Clean Up, `OpenAll`, `DroppedFiles`, `NavigationHistory` to a closed file, `WorkspaceController` set and Open in Default Application, `WorkspaceBrowse` folder changes, `WorkspaceFiles` create and rename, HyperSearch's folder check, `ResultsNavigation.check`, the replace previews, `ReplaceRestore`, the checksum list save, `SelectionChecksum`, `GrammarLibrary.reload`, every `HelpController` worker, `MemoryIndicator.collect` |
| Window, by hand | `DocumentOpener` (one load spans the path check, the read and the listener's answer), `SaveAndReload.write` (posts its result while holding the file's write lock, so a later save's result lands after it), `CompareController` (an IO read then a CPU build, ended by `dispose` too) |
| Editor | `EditorController` Add Next Occurrence, Go to Matching Bracket and `FoldScheduling` Fold All through `BusyWork`; `LineTransformRuns` and `SelectionCommands` set `EditorView.setBusy` directly |

`LaunchCursor` holds one operation for 1 s on a UI timer after
`MainView.launch` or `launchUrl` returns true, since the application's window
appears later: Open in Default Application, Open Containing Folder, the log and
settings folders, Report Issue and the user guide outside its window.

HyperSearch, file reruns, Replace in Files, the checksum dialogs, statistics,
printing, the Quick Open index and symbol scan, the find bar and the workspace
browser's listings show their own progress. Upkeep the user did not start
(highlighting, folds, rewraps, the overview strip, line changes, polling,
session, settings and recovery writes) shows nothing, as do per-keystroke work
in a popup or dialog (Quick Open matching, word completion, the text checksum)
and small reads of QCaret's own files.

`BusyCoverageTest` in `qcaret-launcher` lists every `Workers` submission in
this module with what it shows. It fails on an unlisted submission, and on a
listed operation that does not create a `BusyWork` or make the call its row
names.

## View contracts

`MainView` is the main window as the controller sees it; every method runs on
the UI thread and returns without waiting for the user. It extends these parts:

| Part | Contract |
|---|---|
| `DocumentsView` | Adds a tab per document at an index (`addTab` returns a `TabView`) and runs the file choosers, the unsaved-changes prompt and the binary-file prompt, each answering through a callback; `documentStateChanged` makes the view read menu and status state again |
| `SearchView` | The HyperSearch dialog takes and returns a `SearchForm` (`SearchScope` is one of the six scopes); `chooseFolder` asks for a folder; the results dock reads the `ResultsInput` model by index (`ResultLabel` rows) after each `resultsChanged` |
| `BookmarksView` | Shows the Bookmarks list, which reads the `BookmarksInput` model by index (`BookmarkRow` rows) after each `bookmarksChanged`, and asks for a bookmark comment, answering null on a cancel |
| `WorkspaceView` | Shows the workspace browser, which reads `WorkspaceInput` rows (`WorkspaceNode`) per directory after each `workspaceChanged`, and the Quick Open popup, which reads `QuickOpenInput` rows (`QuickOpenRow`) after each filter change and `quickOpenChanged` |
| `CompareView` | Asks about an external change (`askExternalChange`, answering `ChangeChoice`s; Compare can come more than once before the last answer), asks for a document to compare with, and shows a `Comparison` |
| `ActivityIndicator` | `setActive` and `setIdle` come from any thread; `BusyCounter` counts overlapping operations on the UI thread for the busy cursor |

`StatusFields` is the status bar's text for the selected document;
`MemoryFields` is the memory indicator's text, tool tip and fill.

### Tabs and editors

- A tab forwards its close button, selection, drags and context-menu items as
  `TabInput`.
- An editor forwards its input as `EditorInput`, in display offsets, and runs
  editing commands through `EditorInput.execute`.
- A view's `ViewLayout` answers the pixel and column queries caret navigation
  needs: rows per page, the row at a y, and the visual column at an x, past a
  line's end too.
- A tab holds one or two `EditorView`s of its document (`TabView.firstView`,
  `split`, `setSideBySide`, `unsplit`). A view reports focus through
  `TabInput.viewFocused` and its visible display range through
  `EditorInput.viewportChanged`, and shows find matches through
  `EditorView.showMatches`.
- An editor view shows its overview strip's `OverviewMarks` (`showOverview`,
  `setOverviewShown`) and reports the strip's height through
  `EditorInput.overviewResized`.
- The find bar reads `FindState` and listens as a `FindListener`.

### Window placement

`WindowGeometry.place` sizes and positions the main window before it opens. Per
dimension the command line wins over the remembered geometry, which wins over
1024 x 768. The size is the client area plus the trim. The monitor is the one
containing the requested or remembered top-left corner, else the primary; the
size is clamped to its work area, then the location so the whole window lies
inside it. Without a position, the window system places the window, and where
it ignores a position (Wayland) only the size applies.

## Commands and menus

`CommandId` lists every command with its group (`CommandGroup`, the menu that
lists it), label, check state and cascade. `MenuTable` lays out the menus: each
menu's commands in order with its separators, the editor and tab context menus,
and the check commands shown as radio items (Theme, Tab Width, Line Endings).
Consecutive commands with one cascade form that cascade; a separator before a
cascade's first command sits in the parent menu. Every item of a menu, cascade
and the editor, tab and row context menus has a mnemonic (`&` before the letter,
`&&` a literal ampersand), and no two in one menu share one (`MenuTableTest`,
`ToolbarAndMenusTest`).

| Menu | Holds, `\|` between groups |
|---|---|
| File | New, Open, Reload from Disk \| Recent Files, Reopen Closed Tab, Recently Closed \| Set Workspace Folder, Quick Open \| the saves \| Close, Close All, Close Multiple Tabs (Close Others, Close to the Left, Close to the Right) \| Print \| Exit |
| Edit | Undo, Redo \| clipboard \| Select All \| the Line, Sort Lines and Convert Case cascades \| comments \| indent, Tab Width, Indent with Spaces \| Complete Word, Insert Date and Time, Transpose, Delete Word Back and Forward \| Read-Only |
| Selection | Select Line, Expand Selection, Select to Matching Bracket \| carets and occurrences, Split into Lines \| Column Selection Mode \| Undo and Redo Selection |
| Search | Find, Find Next, Find Previous, Replace \| HyperSearch and its results \| Go to Line, Go to Symbol, Go to Matching Bracket, Go Back, Go Forward \| Bookmarks, with Edit Bookmark Comment |
| View | Word Wrap and the display toggles \| Syntax, Folding \| Split, Compare \| zoom \| Theme, Toolbar, Workspace Browser, Always on Top \| Command Palette |
| Encoding | The view's charset cascades \| Line Endings |
| Tools | A cascade per checksum algorithm \| Verify Checksums \| Document Statistics \| Preferences ([Tools](tools.md)) |
| Window | New Window, Move to New Window \| Next Tab, Previous Tab, Switch Document; the view lists the windows and documents |
| Help | User Guide, Keyboard Shortcuts, Command-Line Options, Tip of the Day \| logs and folders \| Report an Issue \| About |

The `EDITOR` group holds the caret moves, block selection keys, Tab, Enter,
Backspace, Delete and Esc, which have no menu. Where the system supplies an
application menu (macOS), About, Exit and Preferences sit there and are left
out of Help, File and Tools.

## Key bindings

`Keymap` is the one table of key bindings (`Binding`: a command, a `KeyStroke`,
a scope). Menu item text, accelerators, the editor's key dispatch and Help >
Keyboard Shortcuts all read it.

- A `WINDOW` binding is a menu accelerator when it is the command's first
  window binding; a second one (Ctrl plus keypad +) runs from the window's key
  filter.
- An `EDITOR` binding runs only from the focused editor, so `Ctrl+C` never
  fires in another control.
- `Keymap.defaults(mac, linux)` binds `PRIMARY` (Ctrl, or Command on macOS).
  `KeyStroke.CONTROL` is the macOS Control key.
- `reference(filter)` lists the bindings whose group, command or keys contain
  the filter.

Platform choices in the defaults, with the conflict each avoids:

| Command | Keys | Reason |
|---|---|---|
| Redo `Ctrl+Y`, Insert and Delete clipboard keys | Off macOS only | - |
| Upper and lower case | None on Linux | IBus takes Ctrl+Shift+U |
| Block selection | Alt+Shift with an arrow key; Command+Option+Shift on macOS | macOS Option+Shift with Left or Right selects words |
| Find Next, Find Previous | F3, Shift+F3; on macOS Cmd+G and Cmd+Shift+G first, so the menu shows them | - |
| Exit | Ctrl+Q off macOS | macOS Quit is the application menu's Command+Q |
| Replace | Mod+H; Cmd+Option+F on macOS | Cmd+H hides the application |
| Add Caret Above and Below | Ctrl+Shift+Up and Down on every platform | Linux desktops take Ctrl+Alt+Up and Down, and GNOME Ctrl+Alt+Shift+Up and Down, for workspaces |
| Edit Bookmark Comment | Mod+Shift+F2 | Alt+F2 opens the GNOME and KDE run dialogs; Ctrl+F2 focuses the macOS menu bar |
| Complete Word | Ctrl+Space; Option+Esc on macOS | macOS Ctrl+Space switches the input source |
| Go Back, Go Forward | Alt+Left, Alt+Right; Control+Command+Left and Right on macOS | macOS Option+Left and Right move by word |
| Select Line, Transpose | Ctrl+L, Ctrl+T; Control+L and Control+T on macOS | Command+L is Go to Line on macOS |
| Delete Word Back and Forward | Ctrl+Backspace, Ctrl+Delete; Option on macOS | The word-move modifier of each platform |
| Title Case, Swap Case | None | Ctrl+Alt+letter is AltGr on Windows and types a char on many layouts |
| Fold All, Unfold All | Alt+[ and Alt+]; Cmd+Option+[ and ] on macOS | Windows delivers AltGr as Ctrl+Alt, and AltGr+[ types a char on layouts such as US-International |
| Save All | Cmd+Option+S on macOS only | Ctrl+Alt+S off macOS is AltGr+S on Windows |

Other defaults: Find Mod+F; F6 focuses the other split view; Fold and Unfold
Mod+Shift+[ and ], as editor bindings;
bookmarks Mod+F2 (toggle), F2 and Shift+F2 (next, previous); Go to and Select
to Matching Bracket Mod+B and Mod+Shift+B; Command Palette Mod+Shift+P;
HyperSearch Mod+Shift+F, Next and Previous Result F4 and Shift+F4; Quick Open
Mod+P and Switch Document Mod+Shift+O; Go to Symbol Mod+R. Select All
Occurrences Alt+Enter, Skip Occurrence Mod+K, Expand Selection Mod+Shift+Space,
as editor bindings.

Commands with no default key include the split commands, Toggle Fold, Sort,
Remove Duplicate Lines, Trim Trailing Whitespace, Wrap Paragraph, Title Case,
Swap Case, Insert Date and Time, Clear Bookmarks, Bookmarks List, Search
Results, the View display toggles, Workspace Browser, Always on Top, Set
Workspace Folder, Save a Copy As, Print (Mod+P is Quick Open), Close Others,
Close to the Left and Right, Open All, Clean Up and the Clear items of Recent
Files and Recently Closed, Undo Selection, Redo Selection, and every Tools command but
Preferences (Mod+,). User overrides are in
[Settings](settings.md#key-binding-overrides).

## Menu and status state

`CommandStates` computes every menu item's checked and enabled state, the
Window menu's document list, a tab menu's state and the status bar's
`StatusFields` (built by `StatusBarText`), reading the controllers on each call. Views read this state
there and never from controller getters (ArchUnit
`viewsTakeCheckStateFromCommandStates`). The main toolbar's buttons take their
enabled state from the same call.

| Condition | Enabled |
|---|---|
| No document | `WINDOW_COMMANDS` (zoom, theme, HyperSearch and Preferences among them), Help, and the checksum commands but Generate from Selection |
| Read-only document | Not the edits or the line-ending conversions; Copy stays. Save is off while its file's bytes do not encode back |
| Binary file's view | Also not Save, Save As, Save a Copy As, Read-Only or either encoding cascade |
| Undo, Redo | A step in the document's history |
| Cut, Copy | A selection (`EditorController.hasSelection` walks the carets, never the text) |
| Next and Previous Result | A match in a retained set |
| Go Back, Go Forward | A position that way |
| Open All, Clean Up, Clear Recent Files, Clear Recently Closed | A listed file |
| Reload from Disk | A file on the document |
| Close to the Left, Close to the Right | A tab on that side of the selected one |

Undo, Redo, Cut and Copy are `EDIT_STATE`: their keys are editor bindings, not
accelerators, so a disabled item leaves them live in the editor and in native
text fields. A disabled menu item's accelerator does not fire, so the view
syncs enablement on every `documentStateChanged`.

## Memory indicator

`MemoryIndicator` holds View > Memory Indicator (`ui.memoryIndicator`) for one
window and passes `MemoryFields` to `MainView.showMemory`. While the indicator
shows, a `MemoryProbe` read runs on a CPU worker, and the next read is armed
with `timerExec` 2 s after the result arrives; hiding it cancels the timer and
starts no read. A read result carries the generation it started in; one from
before a hide is dropped. `ApplicationController.collectGarbage`, the view's
click, runs `System.gc()` on a CPU worker between two reads and shows the
drop in live used heap as a notice; a click while one runs, or while the
indicator is hidden, does nothing. Each window reads on its own.

`MemoryStatus.of` picks the figure the text and bar show:

| Heap pools | Figure |
|---|---|
| An old generation (`G1 Old Gen`, `PS Old Gen`, `Tenured Gen`, `ZGC Old Generation`) | The larger of its usage after its last collection and its usage now |
| No old generation, collection usage reported (`Shenandoah`) | The pools' usage after their last collection, summed |
| No collection usage reported, or the sum above is 0 | The live used heap |

G1, Parallel and Serial update the old generation's collection usage only at an
old or concurrent collection, so it reads 0 before the first one and lags the
promotions since (measured on Temurin 21.0.12); its usage now covers both.
The maximum is `Runtime.maxMemory()`, the heap `MemoryBudget` takes its 60% of.
The tool tip adds the live used heap, the open documents' reservation against
that budget and the `-Xmx` hint.

## Command palette

View > Command Palette opens the view's palette over `CommandPalette`: every
menu command with its menu, keys and enabled state, filtered with `Fuzzy` on
its title, then on its menu and title. `Fuzzy` scores the query's chars in
order, ignoring case, more for runs and word starts. Choosing an enabled row
runs the command.

The palette, Keyboard Shortcuts and the Preferences Keys tab show
`CommandId.qualifiedTitle`: the cascade title, a colon and the item title
(`SHA-256: Generate`), or the item title alone when it starts with the cascade
title as a whole word (`Split Horizontally`).
