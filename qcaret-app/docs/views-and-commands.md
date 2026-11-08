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

`StatusFields` is the status bar's text for the selected document.

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

`CommandId` lists every command with its group (`CommandGroup`), label, check
state and cascade. `MenuTable.commands` returns a menu's commands in
declaration order.

| Menu | Holds |
|---|---|
| File | File commands; Set Workspace Folder |
| Edit | Also the line commands, Wrap Paragraph, case, indent, tab width with Other for 1 to `Document.MAX_TAB_WIDTH`, indent with spaces, Go to Line, Go to Symbol, Read-Only, Insert Date and Time, Document Statistics, Delete Word Back and Forward, Title Case, Swap Case, Transpose |
| Selection | Add caret above and below, add next occurrence, split into lines, column selection mode, Select to Matching Bracket, Select All Occurrences, Skip Occurrence, Expand Selection, Select Line, Undo and Redo Selection |
| Search | Find, Find Next, Find Previous, Replace, HyperSearch, Next and Previous Result, Search Results |
| View | Word wrap, zoom, theme, the Split and Folding cascades, Overview Strip, Line Numbers, Right Margin, Indent Guides, Modified Line Marks |
| Encoding | The line-ending conversions; the view adds the charset cascades |
| Window | Next and previous tab, Close Others; the view lists the documents |
| Help | Help commands |

The `EDITOR` group holds the caret moves, block selection keys, Tab, Enter,
Backspace, Delete and Esc, which have no menu. Where the system supplies an
application menu (macOS), About and Exit sit there and are left out of Help and
File.

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
| Find Next, Find Previous | F3, Shift+F3; macOS adds Cmd+G and Cmd+Shift+G | - |
| Replace | Mod+H; Cmd+Option+F on macOS | Cmd+H hides the application |
| Add Caret Above and Below | Ctrl+Shift+Up and Down on every platform | Linux desktops take Ctrl+Alt+Up and Down, and GNOME Ctrl+Alt+Shift+Up and Down, for workspaces |
| Edit Bookmark Comment | Mod+Shift+F2 | Alt+F2 opens the GNOME and KDE run dialogs; Ctrl+F2 focuses the macOS menu bar |
| Complete Word | Ctrl+Space; Option+Esc on macOS | macOS Ctrl+Space switches the input source |
| Go Back, Go Forward | Alt+Left, Alt+Right; Control+Command+Left and Right on macOS | macOS Option+Left and Right move by word |
| Select Line, Transpose | Ctrl+L, Ctrl+T; Control+L and Control+T on macOS | Command+L is Go to Line on macOS |
| Delete Word Back and Forward | Ctrl+Backspace, Ctrl+Delete; Option on macOS | The word-move modifier of each platform |
| Title Case, Swap Case | None | Ctrl+Alt+letter is AltGr on Windows and types a char on many layouts |

Other defaults: Find Mod+F; F6 focuses the other split view; Fold and Unfold
Mod+Shift+[ and ], Fold All and Unfold All Mod+Alt+[ and ], as editor bindings;
bookmarks Mod+F2 (toggle), F2 and Shift+F2 (next, previous); Go to and Select
to Matching Bracket Mod+B and Mod+Shift+B; Command Palette Mod+Shift+P;
HyperSearch Mod+Shift+F, Next and Previous Result F4 and Shift+F4; Quick Open
Mod+P and Switch Document Mod+Shift+O; Go to Symbol Mod+R. Select All
Occurrences Alt+Enter, Skip Occurrence Mod+K, Expand Selection Mod+Shift+Space,
as editor bindings.

Menu only: the split commands, Toggle Fold, Sort, Remove Duplicate Lines, Trim
Trailing Whitespace, Wrap Paragraph, Clear Bookmarks, Bookmarks List, Show
Whitespace and Line Endings, Set Workspace Folder, Search Results, Overview
Strip, Workspace Browser, Clear Recent Files, Right Margin, Indent Guides,
Modified Line Marks, Insert Date and Time, Document Statistics, Title Case, Swap
Case, Undo Selection and Redo Selection. User overrides are in
[Settings](settings.md#key-binding-overrides).

## Menu and status state

`CommandStates` computes every menu item's checked and enabled state, the
Window menu's document list, a tab menu's state and the status bar's
`StatusFields`, reading the controllers on each call. Views read this state
there and never from controller getters (ArchUnit
`viewsTakeCheckStateFromCommandStates`). The main toolbar's buttons take their
enabled state from the same call.

| Condition | Enabled |
|---|---|
| No document | Only the window commands, zoom, theme, HyperSearch and Help |
| Read-only document | Not the edits or the line-ending conversions; Copy stays. Save is off while its file's bytes do not encode back |
| Binary file's view | Also not Save, Save As or either encoding cascade |
| Undo, Redo | A step in the document's history |
| Cut, Copy | A selection (`EditorController.hasSelection` walks the carets, never the text) |
| Next and Previous Result | A match in a retained set |
| Go Back, Go Forward | A position that way |
| Clear Recent Files, Clear Recently Closed | A file, a closed file |
| Reload from Disk | A file on the document |

Undo, Redo, Cut and Copy are `EDIT_STATE`: their keys are editor bindings, not
accelerators, so a disabled item leaves them live in the editor and in native
text fields. A disabled menu item's accelerator does not fire, so the view
syncs enablement on every `documentStateChanged`.

## Command palette

View > Command Palette opens the view's palette over `CommandPalette`: every
menu command with its menu, keys and enabled state, filtered with `Fuzzy` on
its title, then on its menu and title. `Fuzzy` scores the query's chars in
order, ignoring case, more for runs and word starts. Choosing an enabled row
runs the command.
