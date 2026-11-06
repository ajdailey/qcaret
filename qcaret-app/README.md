# qcaret-app

The application layer without a UI toolkit: command line, commands and menus,
the controllers, the display projection, view contracts, settings, themes, UI
strings, About facts and the log ring. It depends on `qcaret-core` and slf4j
only; another toolkit reuses all of it and supplies `MainView` (with its tabs
and editors), `UiDispatcher` and `ThemeService`. The packages `app.search`,
`app.session`, `app.recovery` and `app.ipc` are empty.

## Command line (`app.cli`)

`CommandLineParser` turns the arguments into a `CommandLine`: an action (run,
help, version), the `-d` level, `-l`, and a `StartupRequest` holding the files,
the `--window-*` geometry, `--settings` and `-m`. It accepts `--opt value`,
`--opt=value`, `-o value`, combined short options (`-ml`, `-md2`) and `--`,
after which every argument is a file. Paths resolve against the working
directory without touching the file system; the caller validates them. An
unknown option or a bad value throws `CommandLineException` with one line
naming the argument. `StartupRequest.placesWindow` reports whether any
`--window-*` option was given. `Version.get` reads `Implementation-Version`
from this module's jar manifest.

## Views and the UI thread (`app.view`)

`UiDispatcher` is the only route onto the UI thread: `asyncExec` from any
thread, `timerExec` and `cancelTimer` on the UI thread, no synchronous
variant, and submissions after disposal are dropped. `MainView` is the main
window as the controller sees it; every method runs on the UI thread and
returns without waiting for the user. Its `DocumentsView` part adds a tab
per document at an index
(`addTab` returns a `TabView`) and runs the file choosers, the unsaved-changes
prompt and the binary-file prompt, each answering through a callback;
`documentStateChanged` tells the view to read menu and status state again. A
tab forwards its close button, selection, drags and context-menu items as
`TabInput`; an editor forwards its input as `EditorInput`, in display offsets,
and runs editing commands through `EditorInput.execute`. A view's `ViewLayout`
answers the pixel and column queries caret navigation needs: rows per page, the
row at a y, and the visual column at an x, past a line's end too. A tab holds
one or two `EditorView`s of its document (`TabView.firstView`, `split`,
`setSideBySide`, `unsplit`); a view reports focus through
`TabInput.viewFocused` and its visible display range through
`EditorInput.viewportChanged`, and shows find matches through
`EditorView.showMatches`. The find bar reads `FindState` and listens as a
`FindListener`. An editor view shows its overview strip's `OverviewMarks`
(`showOverview`, `setOverviewShown`) and reports the strip's height through
`EditorInput.overviewResized`. `MainView` is also a `SearchView`: the
HyperSearch dialog takes and returns a `SearchForm` (`SearchScope` is one of
the six scopes), `chooseFolder` asks for a folder, and the results dock reads
the `ResultsInput` model by index (`ResultLabel` rows) after each
`resultsChanged`. As a `BookmarksView`, `MainView` shows the Bookmarks list,
which reads the `BookmarksInput` model by index (`BookmarkRow` rows) after
each `bookmarksChanged`, and asks for a bookmark comment, answering null on a
cancel. As a `WorkspaceView`, it shows the workspace browser, which reads
`WorkspaceInput` rows (`WorkspaceNode`) per directory after each
`workspaceChanged`, and the Quick Open popup, which reads `QuickOpenInput`
rows (`QuickOpenRow`) after each filter change and `quickOpenChanged`.
As a `CompareView` it asks about an external change
(`askExternalChange`, answering `ChangeChoice`s; Compare can come more than
once before the last answer), asks for a document to compare with, and shows a
`Comparison`. `MainView` is also an
`ActivityIndicator`: `setActive` and `setIdle` come from any thread, and
`BusyCounter` counts overlapping operations on the UI thread for the busy
cursor. `StatusFields` is the status bar's text for the selected document.

`WindowGeometry.place` sizes and positions the main window before it opens.
Per dimension the command line wins over the remembered geometry, which wins
over 1024 x 768. The size is the client area plus the trim. The monitor is the
one containing the requested or remembered top-left corner, else the primary;
the size is clamped to its work area, then the location so the whole window
lies inside it. Without a position, the window system places the window, and
where it ignores a position (Wayland) only the size applies.

## Commands (`app.command`)

`CommandId` lists every command with its group (`CommandGroup`), label, check
state and cascade. The menus are File, Edit (also the line commands, case,
indent, tab width, indent with spaces, Go to Line and Read-Only), Selection
(add caret above and below, add next occurrence, split into lines, column
selection mode), Search (Find, Find Next, Find Previous, Replace, HyperSearch,
Next and Previous Result, Search Results), View (word wrap, zoom, theme, the
Split and Folding cascades, Overview Strip), Encoding (the line-ending conversions;
the view adds the charset cascades), Window (next and previous tab, Close
Others; the view lists the documents) and Help. The `EDITOR` group holds the
caret moves, block selection keys, Tab, Enter, Backspace, Delete and Esc, which
have no menu. `MenuTable.commands` returns a menu's commands in declaration
order. Where the system supplies an application menu (macOS), About and Exit
sit there and are left out of Help and File.

`Keymap` is the one table of key bindings (`Binding`: a command, a
`KeyStroke`, a scope). Menu item text, accelerators, the editor's key dispatch
and Help > Keyboard Shortcuts all read it. A `WINDOW` binding is a menu
accelerator when it is the command's first window binding; a second one (Ctrl
plus keypad +) runs from the window's key filter. An `EDITOR` binding runs
only from the focused editor, so `Ctrl+C` never fires in another control.
`Keymap.defaults(mac, linux)` binds `PRIMARY` (Ctrl, or Command on macOS);
Ctrl+Y and the Insert and Delete clipboard keys are bound off macOS only. Upper
and lower case have no keys on Linux (IBus takes Ctrl+Shift+U). Block selection
is Alt+Shift with an arrow key, and Command+Option+Shift on macOS, where
Option+Shift with Left or Right selects words. Find is Mod+F, Find Next and
Previous F3 and Shift+F3 (macOS adds Cmd+G and Cmd+Shift+G), and Replace
Mod+H, Cmd+Option+F on macOS, where Cmd+H hides the application. Add Caret
Above and Below are Ctrl+Shift+Up and Down on every platform: Linux desktops
take Ctrl+Alt+Up and Down, and GNOME Ctrl+Alt+Shift+Up and Down, for
workspaces. `KeyStroke.CONTROL` is the macOS Control key. F6 focuses the
other split view; the split commands have no key. Fold and Unfold are
Mod+Shift+[ and ], Fold All and Unfold All Mod+Alt+[ and ], editor bindings;
Toggle Fold has no key. `reference(filter)` lists
the bindings whose group,
command or keys contain the filter. Shortcut overrides are not built.

Bookmarks are Mod+F2 (toggle), F2 and Shift+F2 (next, previous), Edit
Bookmark Comment Mod+Shift+F2 (Alt+F2 opens the GNOME and KDE run dialogs,
Ctrl+F2 focuses the macOS menu bar); Go to and
Select to Matching Bracket Mod+B and Mod+Shift+B; Complete Word Ctrl+Space,
Option+Esc on macOS, where Ctrl+Space switches the input source; Command
Palette Mod+Shift+P. Sort, Remove Duplicate Lines, Trim Trailing Whitespace,
Clear Bookmarks, Bookmarks List and Show Whitespace and Line Endings are menu
only.
HyperSearch is Mod+Shift+F, Next and Previous Result F4 and Shift+F4; Set
Workspace Folder (File), Search Results and Overview Strip are menu only. `Fuzzy`
scores the palette's filter: the query's chars in order, ignoring case, more
for runs and word starts.
Quick Open is Mod+P and Switch Document Mod+Shift+O. Go Back and Go Forward
are Alt+Left and Alt+Right, and Control+Command+Left and Right on macOS, where
Option+Left and Right move by word. Workspace Browser and Clear Recent Files
are menu only.

## Controller (`app.controller`)

`ApplicationController` runs the window's commands and owns the settings, the
theme, the keymap, `Settings` and the window's `DocumentController`,
`HelpController`, `FindController`, `SearchController`, `WorkspaceController`,
`NavigationHistory`, `RecentFiles` and `GrammarLibrary`. It is confined to the UI thread; settings writes run on
`qcaret-lifecycle-` workers. Edit commands from the menu go to the selected
editor's `EditorController`. After `quit` starts, `execute` does nothing.

`CommandStates` computes every menu item's checked and enabled state, the
Window menu's document list, a tab menu's state and the status bar's
`StatusFields`, reading the controllers on each call. Views read this state
there and never from controller getters (ArchUnit
`viewsTakeCheckStateFromCommandStates`). Without a document only the window
commands, zoom, theme and Help are enabled; a read-only document disables the
edits and the line-ending conversions; a binary file's view also disables Save,
Save As and both encoding cascades. HyperSearch runs without a document;
Next and Previous Result need a match in a retained set; Go Back and Go
Forward a position that way, Clear Recent Files a file. A disabled menu
item's accelerator does not fire, so the view syncs enablement on every `documentStateChanged`.
`EncodingChoices` lists `TextEncoding.MENU` first, then every other charset;
`Charset.availableCharsets` costs 130 to 229 ms cold (measured in QLogg), so
`ApplicationController.loadEncodings` computes the list once per run on a
worker.

View > Command Palette opens the view's palette over `CommandPalette`: every
menu command with its menu, keys and enabled state, filtered with `Fuzzy` on
its title, then on its menu and title. Choosing an enabled row runs the
command.

`HelpController` runs the Help commands. Keyboard Shortcuts opens the view's
filtered table of the `Keymap`; Command-Line Options shows the usage text. User Guide
copies the guide page from the jar to `<settings>/cache/guide.html` on a
worker and opens it with the system's HTML application, else names the file
in a notice; the page in this build is a placeholder. Show Log shows the
`RecentLog` lines and clears the status-bar marker, which a WARN or ERROR line
on any thread shows through one posted task at a time. Debug Log File attaches
or detaches the log file on a worker, saves `log.debugFile`, and its check
follows `LogFiles.attached`. Open Log Folder and Open Settings Folder create
the folder owner-only when missing. Report an Issue copies the About details
to the clipboard and opens the issue page; it is QCaret's only web address
and opens only when the user picks the item. About and the license notices are
read on workers.

- A theme change pushes the palette. When native controls show the change
  only at the next start (`ThemeService.showsNow`), the view shows a notice.
- Exit (`QUIT`, the window's close button, the macOS Quit) first closes the
  documents through `DocumentController.closeAll`, which asks about each dirty
  one; Cancel keeps the window open.
- `quit` asks nothing and runs once: it closes the documents, records the
  window's normal bounds and maximized state,
  hides the window, then on a worker saves the settings, closes `Workers`,
  runs the finish listeners and posts the window's disposal. With
  `rememberWindow` false (a `--window-*` option or `-m`) it records and saves
  nothing.
- `Settings` queues each change for a worker; see Settings below.
- `showLicenses` reads `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` from the jar
  on a worker and shows them through `MainView.showText`.
- Zoom In and Zoom Out step `editor.fontSize` one point, from 4 to 72; Reset
  Zoom sets it to 0, the platform's default. The view changes the font in
  place.

`DocumentController` holds the window's documents, one tab each:

- New opens an untitled UTF-8 document ("Untitled N"). Open goes through
  `DocumentOpener`; a missing file opens an empty document for its path, a
  file already open selects its tab, and a document whose text does not encode
  back to the file's bytes opens read-only with a notice. The match is by real
  path, resolved on the opener's worker and looked up (`find`) on the UI thread
  before the file is read, so a file reached again through a link or a short
  name selects its tab unread.
- A binary file opens only after the user accepts the prompt, as a read-only
  ISO-8859-1 view whose tab and status bar say so. Save and Save As refuse it
  in the controller, whichever command path calls them, and the editor
  refuses its edits.
- Reopen with Encoding loads a clean document's file again with the chosen
  charset and replaces the document in its tab once loaded; a failed load
  leaves it open. Save with Encoding changes only what the next save writes
  and makes the document dirty; the byte order mark is dropped for another
  charset.
- A line-ending conversion builds the new text on a `qcaret-cpu-` worker
  (`LineConversion`) and applies it as one whole-text replacement and one undo
  step, then makes the ending the one Enter inserts. A result whose document
  changed or closed meanwhile is dropped. Mixed endings are otherwise kept.
- Save writes a `DocumentSnapshot` through `DocumentFiles.save` on a
  `qcaret-io-` worker while editing goes on, then calls `markSaved`. An
  untitled document asks for a file first. A save while one runs for the same
  document shows a notice instead. Esc in the editor and closing the tab
  cancel a running save; a cancel before the move leaves the target unchanged.
  Save writes through the path the user opened, so a symbolic link is
  followed at each save. A target changed on disk since the load, the last
  save or the last reload is not written; the save conflict prompt offers
  Overwrite, Reload, Compare and Cancel (see External changes). Save As keeps
  that check when the target's
  real path, resolved on the save worker, is the document's file, so Save As
  through a link to it is checked too. Save As onto a file another tab holds
  (by real path, resolved on a `qcaret-io-` worker) closes that tab once the
  write succeeds; when that tab has unsaved changes it asks first, and a
  decline writes nothing.
- Close asks Save, Don't Save or Cancel for a dirty document; Save closes once
  the save succeeds. A closed document disposes its editor and releases its
  heap reservation.
- Word wrap, Show Whitespace, Show Line Endings and the completion trigger
  are settings for the window's editors.
- Each document has one `Bookmarks`, shared by its views and disposed on
  close. `WordCompletion` keeps one `WordIndex` per open document and builds
  it again on a `qcaret-cpu-` worker when the document's revision moved since
  the last build.
- Split shows the selected document in a second view, side by side or one
  above the other; with two views, the other arrangement only rearranges them.
  Each view is an `EditorController` with its own `SelectionModel`,
  `DisplayProjection` (copied from the focused view's rows) and scroll
  position, so view state lives per view, not per tab. The focused view is the
  one menu and find commands act on. Unsplit closes every view but the
  focused one; closing the tab disposes every view.
- Close All and Close Others ask for each dirty document in tab order and stop
  at Cancel. Next and Previous Tab wrap around.
- Loads, saves and conversions each count as one view activity until their
  worker ends.
- Save All saves every dirty document in tab order. Reopen Closed Tab opens the
  file of the last of up to 20 closed tabs again. Read-Only, the tab width and
  indentation with spaces are per document; a binary file's view stays
  read-only. Go to Line asks for `line`, `line:column` or `line,column`, from
  1; other input shows a notice.

`FindController` runs the find bar against the selected document:

- Find opens the bar; a selection within one line of up to 1,000 chars becomes
  the search text. Typing finds from where the selection started when the bar
  opened, or from the last match found. Find Next and Previous search from the
  focused view's selection to the end (start), then wrap with a notice; with
  no text they open the bar.
- While the bar is open, each view of the selected document highlights the
  matches in its visible range, at most 5,000. A text change shifts the shown
  highlights at once, dropping those it touches, and recomputes them.
- Replace replaces the selection when the query matches exactly that range,
  then finds the next match; Replace All replaces every match. Each is one
  undo step, line breaks in the replacement become the document's line ending,
  and a read-only document disables both (`FindState.replaceEnabled`). A
  Replace All result dropped because the document changed shows a notice.
- Esc in a view with one caret and no block closes the bar and removes the
  highlights; with extra carets or a block, Esc drops those first
  (`EditorController.cancel`, `DocumentController.ViewListener.escapePressed`).
- Find Next, Find Previous and closing the bar put a query that compiles first
  in `UiState.texts`, the list HyperSearch reads. Replace and Replace All also
  put the replacement first in `UiState.replacements`. Typing saves nothing.
  `FindState` carries both lists; `ApplicationController.apply` updates an open
  bar when another source changes them.
- Every match is computed on a `qcaret-cpu-` worker from a snapshot; a newer
  request cancels the running one. A result carries its generation and
  revision and is dropped when either is stale. An invalid regex, a stack
  overflow and an invalid replacement end with a notice in the bar.

### HyperSearch

`SearchController` runs HyperSearch (Search > HyperSearch) and is the results
dock's `ResultsInput`. The dialog opens with a one-line selection of up to
1,000 chars as its text, or the selection scope for a selection over several
lines, and the last run's options; texts, directories and include masks are
kept in `UiState`, 20 each, up to 1,000 chars an entry; the texts and the
toggles are shared with the find bar. A request without text, with a regex or mask
that does not parse, without a document, selection or workspace folder for its
scope, or without a directory shows a message instead.

| Scope | Reads |
|---|---|
| Current document, Selection | The selected document's snapshot; Selection only inside the focused view's selections |
| Open documents | Every open document's snapshot, binary views left out |
| Workspace folder, Directory and subdirectories | The folder's tree through `FileSearch`; Directory reads its files only |

Open documents are searched from their snapshots in every scope, so a folder
search reads a dirty buffer as edited and not its file. The match is by real
path: a document holds its file's real path (`SafePaths.userFile`, and Save
As records the real path), and a search root is resolved to its real path on
a `qcaret-io-` worker before the search starts, when the dialog submits a
directory or tree scope and when the workspace folder is set. A rerun uses
the stored root. Without that, a folder reached through a link (macOS `/var`)
or a Windows short name holds no open document, the walk reads the file on
disk, and a link at the root is not walked at all. A root that does not
resolve keeps its absolute path and reports as unreadable. Each run is an
`app.search.HyperSearch` filling a `ResultSet`:

- Documents are matched on `qcaret-cpu-` workers, one task each; a folder is
  walked from a `qcaret-search-` worker. Workers queue file groups
  (`FileResult`), and the UI thread moves them into the set at most every
  100 ms, then calls `resultsChanged`; open documents come first in tab
  order, files by path, once the search ends.
- At most 100,000 matches per set (`HyperSearch.Limits`): the group crossing
  the limit is cut, a source left unsearched for lack of room ends the set
  truncated, and so does a group whose heap (`FileResult.bytes`, 96 bytes
  per match plus its snippet) the `MemoryBudget` refuses. A dropped set
  returns its reservation, groups that arrived after the drop included.
- Files over 64 MiB, binary files and unreadable files are skipped and
  counted; a line over 1,000,000 chars is skipped and counted (QLogg
  CORE-4); a regex stack overflow ends that source and names its line in its
  group row (QLogg CORE-2).
- Stop ends a set canceled with what arrived. Rerun runs the request again
  while the set shows its results; they are replaced when the rerun ends done
  or truncated and kept, with a notice, when it is stopped (QLogg APP-2).
- Sets are listed newest first; past ten unpinned sets the oldest unpinned
  one is dropped. Pin keeps a set; Clear and Clear All stop and drop sets.

Staleness: a document group maps its matches to the current revision through
`Document.positions` (`FileResult.rebase`, on each row, navigation and
overview read); a match whose text then differs in length or hash is stale,
and past the position log the group is stale. A file group keeps the file's
`FileStamp` and SHA-256; expanding it or opening one of its matches checks
the stamp on a `qcaret-io-` worker, and a changed stamp whose bytes hash
differently marks it stale. A closed document's groups keep their offsets
and open the file from then on. Opening a match selects it only when the text
there has the searched length and hash; otherwise the match is marked stale,
the caret goes to its line and a notice says so. Next and Previous Result
step through the set last navigated, or the newest set, wrapping at its ends.

Rerun File (dock) searches one stale group's source again with the set's
query (`app.search.FileRerun`): an open document from a snapshot, a file from
its bytes. The new group takes the old one's place and may use the matches
the set's limit leaves free; a source without matches leaves the set. A
selection set reruns whole, since the selection is not kept.

### Workspace folder, browser and Quick Open

File > Set Workspace Folder stores one folder per window for the run, as its
real path (`WorkspaceController`). Both the browser and the index leave out
hidden entries and `workspace.excludedDirectories`; a change lists and indexes
the folder again.

- View > Workspace Browser shows the folder's tree. A directory is listed on a
  `qcaret-io-` worker the first time it expands (`DirectoryListing`), 500
  entries at a time behind a "Show N more" row. A link to a directory that
  contains it lists as a row that does not expand. Refresh lists every listed
  directory again, keeping the old rows until the new ones arrive, and
  rebuilds the index. Reveal Active File lists each directory down to the
  selected document's file, shows enough entries to hold it and selects it.
  A file row opens on activation; the row menu offers Open in Default
  Application, Set as Workspace Folder and Copy Path.
- Open in Default Application refuses a file the system would run: on
  Windows by extension (QLogg SWT-8: `Files.isExecutable` is true for every
  readable file there), elsewhere by the executable bit, read on a worker.
- The file index (`FileIndex`, at most 200,000 files, charged to the
  `MemoryBudget`) is built on a `qcaret-search-` worker when the folder is
  set and on Refresh; a new build cancels the running one. A folder change
  drops the old index at once; Refresh keeps it until the new one arrives. A
  dropped, replaced or stale index and window close return its charge.
- File > Quick Open (`QuickOpen`) lists the open documents that match, in tab
  order among equals, then the index's files, at most 100 rows. `Fuzzy`
  scores the file name; a file whose name does not match is scored on its
  path below the folder and ranks after every name match; equal scores put
  the shorter path first. A file open in a tab is listed once, as the
  document. A typed query also matches the Recent Files neither open nor in
  the index, after the workspace files, without a folder too; a link inside
  the folder is indexed under its own path and can list beside its target. An
  empty query lists the open documents in tab order, then the
  Recent Files not open, newest first, and the summary counts the indexed
  files; both lists hold real paths, so a file shows once. A row's detail is the path below the folder, the whole path
  outside it, or empty for an untitled document; `QuickOpenRow.open` marks
  an open document. A query ending in `:line` or `:line:column` opens there. An index
  of up to 2,000 files is matched on the UI thread, a larger one on a
  `qcaret-cpu-` worker canceled by the next query or the popup's close. An
  index build that ends while the popup shows matches the query again.
  Window > Switch Document is the same popup over the open documents only.

### Navigation history and Recent Files

`NavigationHistory` keeps Go Back and Go Forward's caret positions (Search
menu), at most 100. A position is recorded where the caret was before a
jump: Go to Line, Next or Previous Bookmark, a Bookmarks list row, a search
result, Quick Open (`EditorController.jumping`), and a change of the selected
document, which records the caret of the document left. A jump in the turn a
document was selected records nothing more, so opening a result in another
document adds one position. Ordinary caret moves record nothing. A position
in an open document is an `Anchor` and follows edits; when the document
closes it becomes the file's line and column, and an untitled document's
positions are dropped. Going to a closed file's position checks on a
`qcaret-io-` worker that the file exists, then opens it; a missing file's
position is dropped with a notice and the move goes on.

`RecentFiles` is File > Recent Files for the run: the last 20 files opened,
newest first, once each by real path, so a file opened through a link and
through its target is one entry. Clear Recent Files empties it.

### Replace

`ReplaceController` replaces the matches of a finished set (dock Replace, or
Search > Replace in Results, Mod+Shift+H, on the set last navigated or the
newest set with matches). Every scope goes through the same preview and run.

- The preview lists the set's groups with matches, every file and match
  checked, and the replacement text (a regex query's `$n`, `${name}`, `
`,
  `	`; a literal query's text as is). The selected file's before and after
  view (`app.search.ReplacePreview`: changed lines, two lines of context,
  regions diffed by `core.diff.LineDiff`, at most 2,000 lines) is computed
  on a worker from the open document's text or the file's bytes, again on
  each change of the text or the checks; a file Apply would skip shows why.
- Apply starts an `app.search.ReplaceRun` and opens its report; one run or
  restore at a time. An open document is replaced in only at the revision
  searched, as one `EditBatch` and one undo step, and stays dirty; a file
  group whose file is open now is edited as its buffer while the buffer's
  text, encoded, has the SHA-256 searched. Replacements are computed on the
  worker from a snapshot and applied on the UI thread, which skips the
  document if it changed, closed or became read-only meanwhile.
- A file not open goes through `FileReplace` (see `qcaret-core`) on up to
  four `qcaret-io-` workers, with its original bytes in the run's
  `ReplaceBackups` under `<settings>/replace-backups/<run>/`; each run first
  deletes runs older than `replace.backupDays`. Cancel (the
  report's Stop) stops between files.
- The report lists each file as replaced, skipped with the reason, failed
  with the error, or not attempted. When the run ends, each file group it
  changed is marked stale. No undo spans files: Restore Run in the report, or
  Search > Restore Replace Run, writes a run's backups back, refusing a file
  changed since the run. An open document whose file a restore wrote is
  checked at once: a clean one reloads, a dirty one asks.

### External changes

`ExternalChanges` checks each open document's file through `FileCheck` (see
`qcaret-core`) on the `FileMonitor`'s `qcaret-watch-` thread: every
`file.pollSeconds`, on window activation (`ApplicationController.
windowActivated`), on a directory event and after Restore Run. It checks the
path the user opened, kept per document beside the real path that names the
tab and deduplicates opens. A result reaches `DocumentController` on the UI
thread only when the document's watch generation is unchanged: a save or a
reload pauses the watch and raises the generation, so the document's own
write is never reported.

| Check result | Clean document | Dirty document |
|---|---|---|
| Same bytes, newer stamp | Stamp taken as the save baseline | Same, unless the user kept a change |
| Other bytes | Reload; with `file.autoReloadClean` off, the prompt | Prompt: Keep Mine, Reload, Compare |
| No file | Kept, marked missing in the tab and dirty | Same |
| File back with the same bytes | Missing mark cleared; clean again when the text is unchanged since | Missing mark cleared |

- Reload reads the file on a `qcaret-io-` worker in the document's charset
  (detection when that does not decode; ISO-8859-1 for a binary file's view),
  reserving the load's cost from the budget while it reads, and replaces the
  span between the common prefix and suffix as one undo step. Each view's
  caret keeps its line and column; the views keep their top line. The
  document is clean afterwards and undo returns to the text before, dirty. A
  document edited during the read reloads again, at most three times.
- One prompt per document at a time. A newer change while it is open becomes
  the change the answer acts on. Keep Mine takes the file's new baseline for
  the checks but keeps the save baseline, so the next save asks; closing the
  prompt keeps.
- The save conflict prompt replaces the save's notice: Overwrite writes
  without the baseline check, Reload and Compare act as in the change prompt,
  Cancel leaves the document dirty.
- Save of a missing file writes it without a prompt and clears the mark.

`CompareController` opens the read-only comparison: Compare Documents (View >
Compare) asks for another open document, and Compare with File on Disk and
the prompts' Compare put the file on disk left of the buffer. The texts are
snapshots; the file is read as a reload reads it; `Comparisons` builds the
rows with `LineDiff` on a `qcaret-cpu-` worker. A side over 100,000 lines or
16 Mi chars gets a notice instead (`Comparisons.MAX_LINES`, `MAX_CHARS`); a
line over 10,000 chars is compared whole and shown cut. A `Comparison` holds
both sides' rows (a line on one side only faces an empty filler row), each
row's kind and the first row of each run of changes.

### Bookmarks list

`BookmarkList` is the Bookmarks list's model (Search > Bookmarks > Bookmarks
List). While shown it follows the selected document through
`DocumentController.addActiveListener`, its edits and its `Bookmarks`
changes; hidden, it reads nothing. A read waits 150 ms, then builds the rows
on a `qcaret-cpu-` worker from a text snapshot and the bookmarks' offsets:
the line, the comment and the line's first 160 chars, control chars as
spaces, at most 10,000 rows. Rows show only at the snapshot's revision; an
older result is dropped and read again. Each row reserves 96 bytes plus 2
per char from the `MemoryBudget` while listed; rows past a refusal are left
out, and the summary reads "first N of M". Go To, Edit Comment and Remove
act on the row's bookmark at its current line (`Bookmarks.line(key)`), so an
edit since the read does not move them to another line; Go To into a
collapsed region expands it.

### Overview strip

Each editor view has an `OverviewUpdater` that computes its strip's
`OverviewMarks` on a `qcaret-cpu-` worker: the find bar's matches over the
whole document while the bar is open on the selected document (at most
1,000,000), the matches of the set last navigated or else the newest set,
and the bookmarks. A request waits 150 ms, so an edit burst computes once;
edits, bookmark changes, the find query, the results and the strip's height
request it. A result shows only while the document is at its revision and the
strip at its height. A hidden strip (View > Overview Strip, window-wide)
computes nothing.

`DocumentOpener` opens files for one window on `qcaret-io-` workers. A worker
resolves the path (`SafePaths.userFile`) and posts it to the UI thread, where
`Listener.resolved` ends the open unread for an open document; otherwise a
second worker reads it. An open waiting for the UI thread is ended by `close`
too, so a task the dispatcher drops still ends its activity. Before reading,
it estimates the load (`DocumentFiles.estimate`) and reserves it from
the `MemoryBudget`; a load the budget cannot hold is refused unread, and the
notice names the size, the memory needed and a `-Xmx` value. A loaded
document arrives with its rows (`DisplayProjection.layout`) and its
reservation lowered to the retained cost. `close` sets a flag and cancels the
running loads; a load that finishes after it drops its document and releases
its reservation, whether the worker checks the flag before posting or the
posted task checks it on the UI thread, so no document reaches a closed window.

`EditorController` runs one editor's `EditorInput` against its document:
every edit is one `Document.apply` with the view's `SelectionModel` as origin,
and undo and redo restore that model. Typing, Enter (the document's line
ending), Backspace and Delete apply at every selection and coalesce by kind;
Backspace and Delete take a CR LF or a surrogate pair whole. Copy joins the
selections with the line ending; Paste converts line breaks to it and, with as
many lines as selections, puts one line at each. An edit the widget made on
its own is mapped from display offsets and applied as a command. A read-only
document ignores edits. The controller also runs the projection's rewraps on
`qcaret-cpu-` workers, a newer request canceling the running one.

- Caret moves apply to every selection; a move without Shift collapses a range
  to its side first. Up, Down and the page keys follow the projection's rows
  (`CaretNavigation`) at each caret's preferred column; a page is the view's
  `ViewLayout.pageRows`.
- Block selection: Alt+Shift with an arrow key, a mouse drag with Alt (the
  view reads rows and columns through `ViewLayout`), or Shift with an arrow key
  in column selection mode, moves the block's caret corner; columns past a
  line's end are virtual. In block mode typing, Backspace, Delete, cut, copy
  and paste follow `BlockEdits` and the block stays a block.
- Line, case and indent commands are `core.edit` functions applied as one
  command each; undo restores every caret.
- Add Next Occurrence selects the word at a primary caret, else searches a
  snapshot for the primary's text on a `qcaret-cpu-` worker and adds the match
  as the new primary; a result for another revision is dropped.
- Composition text from the input method goes to `Document.preedit`, outside
  undo; a commit the view reports with the composition becomes one undo step.
  Every edit removes a composition first.
- Esc cancels a running save and drops the secondary selections and the
  block; with neither, it closes the find bar.
- Toggle Line Comment and Toggle Block Comment apply `CommentEdits` with the
  document grammar's delimiters at every selection, as one undo step; a
  grammar without a line comment toggles a block comment around each block of
  lines.
- Enter and a typed closer go through `SmartIndent` with the view's
  `IndentRules`: `PatternIndentRules` over the grammar's `indent` regexes,
  `IndentRules.BRACKETS` for plain text and a grammar without them. A typed
  char that `AutoClose` handles does not outdent.
- Folding: Fold collapses the region the primary caret's line starts, else the
  innermost expanded region holding it; Unfold expands the collapsed region
  the line starts; Toggle Fold does either; Fold All and Unfold All take every
  region of the view. Fold All measures its spans on a `qcaret-cpu-` worker
  against a snapshot and a copy of the rows and collapses them only if the
  text, rows and offered regions are unchanged, else asks again; a fold
  command meanwhile drops it. Folding moves each caret and anchor in hidden lines to
  the end of the line before them. A primary caret that any other command
  puts in hidden lines (find, Go to Line, undo) expands the regions hiding
  it. Left, Right, the word moves and Mod+End step over hidden lines.
- Fold regions come from a `FoldRegionSource` on a `qcaret-cpu-` worker, 300
  ms after the last change and at once on a tab width change, one run at a
  time; a result for an older revision is dropped. Each view computes its
  own. A `brackets` or `markers` grammar's source is the document's
  `SyntaxHighlighter`, whose new regions run the step again; other grammars
  and plain text use `IndentFoldSource` at the document's tab width. A source
  that has no regions for the snapshot's revision returns null, and the
  installed regions stay.
- Typing and Backspace follow `AutoClose` with the pairs `pairs()` returns: the
  grammar's `autoClose` pairs, `Pairs.DEFAULT` for plain text and a grammar
  without any; a block types plainly.
- Sort, Remove Duplicate Lines and Trim Trailing Whitespace compute their
  `LineTransforms` edit on a `qcaret-cpu-` worker and apply it on the UI
  thread as one undo step, with the view's busy cursor from the command to
  the apply; a result for an older revision is dropped with a notice.
- Bookmarks: Toggle at every caret's line, Next and Previous from the primary
  caret's line with wrap-around, Clear; Next and Previous onto a hidden line
  expand the regions hiding it. The `Bookmarks` are the document's, shared by
  its views. Edit Bookmark Comment asks for the primary caret's line's
  comment; the answer bookmarks the line and sets the comment, an empty one
  keeps the bookmark without a comment, and a refusal (too long, too many)
  shows a notice and changes nothing. An anchor follows the line while the
  prompt shows.
- Bracket matching pairs the grammar's `brackets` (`Pairs.DEFAULT` for plain
  text) and skips a bracket whose token is a comment, string or escape
  (`TokenBracketFilter`). A line without tokens yet counts every bracket. A
  worker search reads a copy of the token cache taken when it starts.
- Go to Matching Bracket moves each caret beside a bracket past its partner;
  Select to Matching Bracket selects from the bracket to the partner. Each
  partner is sought on the UI thread within 16 Ki chars of its bracket; one
  further away sends every search to a worker, applied only at the same
  revision and selections.
- One UI turn after a caret move, an edit or a scroll, the view gets the
  bracket beside the primary caret with its partner (sought on the UI thread
  within the visible range and 16 Ki chars on each side, else on a worker) and
  the whole-word occurrences of the word at the primary caret in the visible
  range (on a worker, at most 2,000, shown when there are two or more). An
  edit shifts both at once; a result for an older revision is dropped.
- Complete Word looks up the word chars before the primary caret in every open
  document (`WordCompletion`); one match is inserted at once, more show the
  view's list. With `editor.completionChars` above 0 the list also opens after
  that many word chars are typed. While it shows, Up, Down, Page Up and Page
  Down move in it, Enter and Tab replace the typed prefix at every caret that
  ends with it, Esc closes it, typing narrows it, and any other command or a
  caret moving off the word closes it.

### Syntax highlighting

`GrammarLibrary` reads the built-in grammars and the user's
`<settings>/grammars/*.xml` on a `qcaret-io-` worker at startup
(`loadGrammars`) and on View > Syntax > Reload Grammars. A user file that does
not parse, fails the schema or compiles badly, or is over 1 MiB, is skipped
with a WARN log line and one notice per read; a user grammar with a built-in's
id replaces it.

`DocumentController` gives each document a `SyntaxHighlighter` and picks its
grammar on a `qcaret-cpu-` worker with `GrammarSet.detect` (file name,
extension, then the first 1,000 chars of the first line) when it opens, when a
save gives it another name and when the grammars are read again. View >
Syntax or a click on the status bar's language field sets one for the
document (`setSyntax`); that choice survives a reload while the grammar
exists. Each grammar switch, the reload included, passes the grammar to every
view's `EditorController.setGrammar`: comment delimiters, fold source, indent
rules and pairs.

`CommandStates.syntaxMenu` builds the `SyntaxMenu` both lists show: the last
five grammars chosen with `setSyntax` in this run, Plain Text included, then
Plain Text, then one submenu per category that holds a grammar, in `byName` order.
A grammar's `category` picks the submenu (`programming` splits into A-M and
N-Z by the name's first letter, then `markup`, `build`); a grammar without one
goes in User.

`SyntaxHighlighter` keeps the painted tokens in a `TokenCache` on the UI
thread and runs a `SyntaxEngine` on the CPU pool; the UI thread runs no regex.

- Each change, grammar switch, view range and request for missing lines goes
  to a queue. One task at a time drains it, runs the engine for at most 10 ms,
  posts the output and submits itself again while work remains.
- An output carries the switch generation and the revision the engine read.
  Its tokens apply only at the current revision; the lines the frontier
  tokenized are dropped from the cache at any revision, mapped through the
  `PositionMap`, so an edit the engine has seen leaves no stale entry.
- A change shifts the cache at once, so typing does not flash plain text. An
  output repaints the views only when it changes a line one of them shows.
- The fold regions of the last complete pass are the document's
  `FoldRegionSource` (`activeFoldRegions`) for that revision.
- Lines over 10,000 chars stay plain; the count goes to the log at INFO
  when a complete pass finds a new count.
## Editor state (`app.editor`)

`DisplayProjection` maps a document's text to the display text an editor
shows: model lines split into rows at virtual breaks, one display char each,
where word wrap ends a row or a line passes `MAX_ROW_CHARS` (16,384). A line
without breaks maps one to one. A display offset on a virtual break maps to
the model offset of the break; a model offset at a break maps to the start of
the next row.

- The projection answers for the text it last reported: during
  `Listener.changing` for the text before a change, during `changed` for the
  text after it. A document change becomes one display change covering the
  changed lines (all of a batch's edits in one range): `StyledText` shifts its
  per-line caches once per change, so one change per edited line cost 10,000
  spread carets about 5 times as much (measured).
- `RowBreaks` places a line's breaks: a row ends where the next char would
  pass the wrap column, after the row's last space or tab when it has one,
  and holds at most `MAX_ROW_CHARS` chars; columns count from the row start
  (tab stops, wide chars 2), and a surrogate pair is never split.
- `RowIndex` keeps only the lines with breaks, sorted, with their breaks in
  one array; edits replace a line range and shift the tail.
- A document change recomputes the changed lines' rows on the UI thread. A
  change over 1 Mi chars with wrap on gets only the long-line splits there,
  and the projection asks its owner for a rewrap.
- Folds: `setFoldRegions` gives the regions a view offers, one per start
  line; `fold`, `foldAt`, `unfold`, `foldAll`, `unfoldAll` and `reveal`
  change the collapsed set, each as one display change over the lines it
  affects. `FoldLayer` holds the collapsed regions, sorted by start line, and
  the hidden spans they merge into: region `(a, b)` hides lines `a + 1` to
  `b`, and line `a` is followed by line `b`'s delimiter. Each span keeps the
  chars and rows it hides and where its hidden text starts in the unfolded
  display, so a display lookup is a binary search over spans and reads no
  text. A model offset in hidden text maps to the end of the span's first
  line (`visibleOffset` gives a shown offset in either direction).
- An edit that touches a collapsed region's hidden text, from the end of its
  first line to the end of its last, expands it. An edit that ends at the
  first line's end, or inserts there without a line break, keeps it, unless a
  CR would join that line's LF; the region then follows the edit. Spans after
  an edit move by the change's char and row deltas; spans inside it are
  measured again. With a deferred rewrap (over 1 Mi chars), a region whose
  hidden lines lose their wrap also expands. Offered regions follow edits
  approximately until the next `setFoldRegions`, which also expands a
  collapsed region when no offered region starts on its first line and takes
  a changed end.
- Turning wrap on or off, a new wrap width and, with wrap on, a new tab width
  need a rewrap of every line:
  `rewrapRequest` returns a `Rewrap`, `compute` runs on a worker from the
  request's snapshot, and `install` applies it only while the document is
  still at that revision; a stale or superseded result is dropped and the
  owner asks again.

`HighlightComposer` composes what one view paints on a row: the syntax
tokens' foreground (`TokenSource`) and one background per char, taken from
the first layer that covers it in the order secondary selection (which also
sets the foreground), bracket pair, find match, word occurrence. It returns
runs of role and background ordinals; the view maps them to its toolkit
styles.
`TokenCache` holds the tokens of the last 2,000 painted lines, maps each token
type to the `TokenRole` of the same name and shifts entries through a change:
an edit without breaks moves the tokens in its line, an insert with breaks
splits the line's tokens around it, any other edit drops the lines it touches,
and a CR and LF joining or parting at an edit (line count off from the edits'
sum) drops every line; `copy` gives a worker the entries held.
`FoldRegionSource` is the fold layer's input: `{start, end}` line pairs per
revision, null while that revision's are not computed. `TokenBracketFilter`
reads a `TokenSource` and leaves comment, string and escape brackets out of
`Brackets` matching. `PatternIndentRules` finds a grammar's `indent` regexes
in at most 1,000 chars of the line through a `BoundedChars`; a regex that
reaches the read bound or overflows the stack does not match.

`IndentFoldSource` opens a region on a non-blank line when the next non-blank
line is indented deeper and ends it on the last non-blank line before a line
indented no deeper; blank lines stay outside. `SmartIndent`: Enter copies the
caret line's leading whitespace up to the caret; after an opener
(`IndentRules.increase`) it adds a tab or a tab width of spaces as the
document indents, and with a closer after the caret (`IndentRules.decrease`)
the closer moves to its own line at the old indentation; after a block-ending
statement (`IndentRules.dedentNext`, Python's `return` and `pass`) the new
line takes one level less. A closer typed after
only whitespace outdents the line to the previous non-blank line's
indentation, one level less unless that line opens one, and never indents it
further.

`ColumnMetrics` holds a monospace font's column geometry: the advance is the
measured width of 1,000 digits over 1,000, kept fractional, and column `c`
starts at `round(c * advance)`. The gutter width and the wrap width in columns
use it; text outside printable ASCII is placed at the widget's measured glyph
positions.

`Bookmarks` holds one document's bookmarks as `Anchor`s at their lines'
starts with `Bias.AFTER`, so a break inserted at that start moves a bookmark
down with its line's text. Each bookmark has an optional comment: one line
(`clean` turns breaks and other control chars into spaces), at most 1,000
chars, at most 1,000 per document; removing the bookmark removes it.
`Bookmarks` listens to the document and, for each edit that removes text,
works in the coordinates before the edit:

| Edit | Bookmark |
|---|---|
| Replaces the line, and the inserted text holds a line with the same text (Move Line, Sort) | Moves to that line |
| Removes the line's text and its break, or the last line's text and the break before it | Removed |
| Joins its line to a bookmarked line above | Removed; the upper one stays |
| Anything else | Follows its anchor |

A removed bookmark is kept with the removed text's length and hash and two
anchors around the inserted text, for the newest 1,000 such edits; an undo or
redo that puts the same text back between those anchors (at the start or the
end of the span, so coalesced deletes match too) restores the bookmark with
its comment. Toggle, Clear and Remove are not undoable, and Clear drops the
kept ones.
`LineEnds` names the break after a line for the line-ending marks.

## Settings (`app.config`)

`SettingsLocation` picks the settings directory: `--settings <dir>`
(explicit), the installation directory `qcaret.home` when it holds
`qcaret.conf` (portable), else the per-user directory of the OS. The
bootstrap applies the same order to the SWT native cache.

`Preferences` holds `ui.theme`, `window.*`, `log.debugFile` (attaches the debug
log file at the next start), `Options` (the Preferences dialog's settings),
`UiState` (search toggles, histories, recent grammars, dialog sizes) and
`ShortcutOverrides`. The root README lists every key. `PreferenceKeys` maps
them to and from `qcaret.conf`: a new setting is a record component and its
lines there, plus a `PreferencesForm` field when the dialog shows it.

`ConfigStore` has two blocking calls, serialized by a lock, that run off the
UI thread apart from the first load before the Display:

- `load()` reads the file.
- `save(before, after)` re-reads the file and writes only the keys that differ
  between the two settings, so another process's newer values and unknown keys
  stay. No difference writes nothing. A missing or malformed file gets every
  key of `after`.

`merge(base, theirs, mine)` applies the keys that differ from `base` to
`theirs` over `mine`, except keys `mine` also changed. The file is Java properties, ISO-8859-1, sorted keys, no
date comment, `version=1`.

| Condition | Behavior |
|---|---|
| File missing | Defaults |
| No `version` key | Read as version 1, so an empty portable marker is valid |
| Unreadable, over 1 MiB, does not parse, or another version | Malformed: defaults, one WARN, and the first save copies it to `qcaret.conf.invalid` before replacing it |
| A value that does not parse or is out of range | That value's default, one WARN naming the key; the file is not malformed |
| Read-only directory | Saving disabled with one WARN |
| A save over 1 MiB | Refused with one WARN; the file stays |
| Malformed since the last load | As above: the save keeps it as `qcaret.conf.invalid` |
| Per-user directory missing | Created owner-only by the first save |

Writes go through `AtomicFiles`. Only file names and keys are logged, never
values. No file lock is held: two processes saving within one read and write
can lose one of the two.

`Settings` (`app.controller`) is the one running copy. Every controller reads
`current()`; no toggle or history has a per-window field. `set` replaces the
copy, calls the listeners on the UI thread and queues the change for a
`qcaret-lifecycle-` worker. Workers drain the queue in order under `saveLock`,
which the UI thread never takes, as one `save` from the first change's `before`
to the last change's `after`. `ApplicationController.apply` is the listener that
brings the running window in line, whatever the change's source.

`refresh` drains the queue, loads the file on a worker and takes up what another
process changed; a change made meanwhile survives through `merge`. A failed save
or a read-only directory keeps the running settings. `PreferencesController`
runs it before the dialog opens, so the dialog shows the file's values, and
hands checked fields to `set`; a failed save shows a notice and the settings
stay applied for the run. `clearHistory` empties every `search.history.*.N` list
and `SearchController`'s last run at once, apart from `apply`, so Cancel does
not restore them. It runs `refresh` first, so another process's entries go too.
An open find bar's list follows; its field texts stay. A failed save shows
`PREFERENCES_HISTORY_NOT_REMOVED`: the lists stay empty for the run and the
entries stay in the file. `PreferencesController` keeps the settings the file
holds, and `Settings.setFrom` saves from them on the next press, which `set`
would skip because the running settings already match.

`Keymap.withOverrides` replaces a command's keys from `ShortcutOverrides`. A key
takes the scope of the command's default keys, or window scope for a menu
command without any. `ShortcutOverrides.parse` refuses a value with a stroke
that does not parse or that types text (`KeyStroke.types`), one WARN naming the
key; a key naming no command stays in the file. `KeyStroke.stored` is the
platform-free form: `Mod+Alt+K`. `Keymap.conflicts` is logged at start and on a
change and ends the shortcut reference.

## Themes (`app.theme`)

`ThemeMode` is `ui.theme`: light, dark or system. `ThemeController` resolves
it to a `Palette` (system follows `ThemeService.systemDark` and its change
events), calls `ThemeService.preferDark` so native controls match, and pushes
a changed palette to its `PaletteListener`s.

`Palette` is the editor color scheme as toolkit-free `Rgb` values: text,
background, selection, current line, gutter and line numbers, caret,
whitespace marks, the find-match background, the comparison window's row
backgrounds (`diffRemoved`, `diffAdded`, `diffChanged`), and a `TokenStyle`
(color, bold,
italic, underline) for every `TokenRole`; a grammar token type paints with the
role of the same name. The
light and dark defaults derive from the brand navy `#1B2E44`, cyan `#56C1F0`
and amber `#F3B65B`; every token color reaches a contrast ratio of 4.5 on the
background, the current line and a find match (`PaletteTest`).

## UI text, About and logs

`UiText` names every string the UI shows; the English bundle
`messages.properties` holds them, and `ArchitectureTest.everyUiTextIsUsed`
fails on a constant nothing reads. `AboutInfo` collects the version, Java, OS,
toolkit and settings directory for About and Copy Details. `RecentLog` keeps
the last log lines for the launcher's `RecentHandler`, `LogFiles` is the
debug log file's contract, and `EventLoopWatchdog` reports UI-thread stalls at
`-d 2` and above.

## Tests

The controller tests run the UI thread on the test thread through
`ManualUiDispatcher`. `FakeView` calls `ManualUiDispatcher.checkUiThread` in
every method, so a view call from a worker fails the test instead of passing
on the test thread. `ManualUiDispatcher` is in the test jar for the other
modules.

`ConfigStoreTest` covers two stores merging on one directory, a save that
changes nothing, unknown keys, every key's round trip and invalid values.
`SettingsTest` covers the ordered queue and `refresh`, including a change made
during one, a read-only directory and `setFrom`. `PreferencesControllerTest` drives
`PreferencesInput` and asserts each setting's effect on the running window,
and Clear History in the file, after Apply, over another process's entries,
for HyperSearch's last run and on a read-only directory; the remaining effects sit with their area (`ExternalChangeTest`,
`SyntaxHighlighterTest`, `SearchControllerTest`, `ReplaceControllerTest`,
`FileMonitorTest`).

`ApplicationControllerTest` covers the theme save and its restart notice, the
quit's placement save, the quit that saves nothing, licenses read on a worker
and About. `Harness` builds a controller over `FakeView` with a recent log and
a mock debug log file. `CommandDispatchTest` runs every `CommandId` with no
document and with a document holding two selections: none throws on the UI
thread or a worker, and none leaves an activity running. `CommandStatesTest`
records every command's checked and enabled state with no document, a text
document and a binary view, and the status fields. `ShellCommandsTest` runs
every command but the open and close ones on a binary view and checks that the
file's bytes and its folder's listing are unchanged; it also covers a declined
binary prompt, Reopen and Save with Encoding, the line-ending conversion and
its undo, a conversion dropped after an edit, the tab commands, the warning
marker, Debug Log File with a failing attach, the user guide copy, Report an
Issue and the zoom bounds. `KeymapTest` checks that no stroke is bound twice on
any platform, the platform key choices (Add Caret, F6, Go Back and Go
Forward), the Quick Open keys, the reference filter
and the accelerator, editor and window-filter lookups. `DocumentOpenerTest`
holds a load in its decoder past the window's
close and closes after a result is posted; neither reaches the listener and
both release their reservation; a load over the budget is refused unread; an
open document ends the open unread; a close ends an open waiting for the UI
thread.
`DocumentControllerTest` covers open, edit and save, Save As for an untitled
document, a missing file, a second open of one file, a file opened again
through a symbolic link selecting its tab and running the HyperSearch
callback, Save As through a link to a file changed on disk opening the save
conflict prompt (both skipped where links cannot be created), the unsaved prompt's
three answers, `closeAll` stopping at Cancel, word wrap across editors and the
heap refusal. Two cases hold a save in the encoder of `GatedCharset` and
cancel it with Esc and with a tab close: the file must be unchanged, and a
second save while the first is held shows the notice.
`ExternalChangeTest` covers a clean document reloaded as one undo step with
its caret line kept, a dirty document's prompt with Keep Mine making the next
save ask and Overwrite writing, Reload keeping the edits in undo, a newer
change updating the open prompt, a deleted file kept missing and dirty until
the same bytes return, a missing file saved without a prompt, a touch with the
same bytes neither prompting nor blocking the save, five saves never reported
as external changes, Compare from the prompt, Compare Documents, a
retargeted link reloading and saving its new target (skipped where links
cannot be created), and a 3 MiB file reloaded. Each check runs on window
activation; a case that asserts nothing was reported waits for two finished
check passes requested after the change.
`MultiCaretTest` covers every caret moving and selecting, vertical and page
moves at the preferred column, block keys into virtual space with padded
typing undone as one step, a block from the view's columns with copy and cut,
column selection mode, add caret above and below and the click toggle, Add
Next Occurrence from a worker, line commands whose undo restores every caret,
paste per caret, composition text with a commit as one undo step, and Go to
with a tab width change. `EditingSettingsTest` covers Save All, Reopen Closed
Tab, Read-Only, the tab settings and the Go to Line prompt.
`SyntaxHighlighterTest` runs the highlighter on real workers: view lines
arrive and the regions follow the pass, an edit's shifted tokens paint at once
and the final tokens equal a new pass, and outputs queued for an older
revision do not overwrite newer text. `SyntaxControllerTest` covers detection
by name and first line, the manual choice across a reload, the Syntax
menu's five recent grammars, a user grammar
replacing a built-in, the notice for an invalid file, and both comment
commands at two carets as one undo step. `SyntaxMenuTest` covers the
category submenus of the built-in grammars, a user grammar in its category or
in User, and the recent list. `GrammarWiringTest` covers fold
regions from the highlighter for a `brackets` grammar and from indentation
for the others through switches and a reload, the grammar's indent rules and
pairs, and bracket matching that counts a string's bracket until the tokens
arrive and skips it after. `HighlightComposerTest` (the background
precedence), `TokenCacheTest` and `GrammarRulesTest` cover the layers, the
cache through edits, the indent regexes and the token filter.

`SplitViewTest` covers two views with their own selections over one text,
the arrangement switch, focus, Unsplit keeping the focused view, and both
views stopped with the tab and reached by word wrap. `FindControllerTest`
covers incremental find and highlights, wrap notices, the selection seed, a
result dropped after an edit, regex and replacement failures, Replace and
Replace All as one undo step each, the notice for a Replace All dropped after
an edit, Esc dropping extra carets before it closes the bar, a regex
replacement with the line ending,
a read-only document, highlights in both views and their shift on an edit, and
the saved history: what saves an entry, the 20-entry and 1,000-char limits, a
refused Replace saving nothing, and the text list shared with HyperSearch.
`SearchControllerTest` covers every scope, a dirty buffer searched as edited
inside a tree search, the dock's rows, opening a match in an open document
and in a closed file, an edit moving a match and one marking it stale, a file
changed on disk, Next and Previous Result wrapping, a stopped rerun keeping
the finished set and a done one replacing it (QLogg APP-2), the retained
limit with a pin, the dialog's seed and history, the refused requests,
the workspace folder, and a tree searched through a symbolic link reading the
open buffer once (skipped where links cannot be created). `HyperSearchTest` covers documents and a folder in one
set, the match limit, a refused reservation, a cancel, a regex overflow on
its group and a document group following edits. `ReplaceControllerTest` covers
a selection replaced as one undo step, open documents with one changed since
the search skipped, a tree replace with an unchecked match and file, a CRLF
file, a file changed between the preview and Apply, a read-only file, a file
opened after the search edited as its buffer, Restore Run refusing a file
changed since and restoring the others, a restore reloading a clean open
document, Stop between 300 files leaving the
rest untouched, Rerun File on a stale group, and Save As onto a dirty file
open in another tab. `OverviewTest` covers find,
HyperSearch and bookmark marks, recomputed after an edit, and a hidden strip
computing nothing; `OverviewMarksTest` the line and row mapping.
`EditorControllerTest` covers multi-caret typing as one undo step, Enter's
line ending, Backspace and Delete over CR LF and surrogate pairs, cut, copy and
paste with line ending conversion and one line per caret, a read-only
document, view offsets mapped through virtual breaks, and Esc.
`DisplayProjectionTest` compares the projection with a display text built
line by line from `RowBreaks` after 1,500 seeded random edits that join and
part CR LF pairs, wrap on and off, replaying each display change on a mirror
string; it also covers stale and superseded rewraps and the large-change
fallback, one change over distant edits, and a rewrap on a tab width change.
`FoldingTest` compares the projection with a display text built line by line
with the collapsed lines left out, through 80 seeded rounds of random region
sets, folds, unfolds, reveals and edits that join and part CR LF pairs, wrap
on and off, replaying each display change on a mirror (`-DfoldRounds=N` runs
more; 20,000 passed); it also covers rewraps with folds, the expand rules, the
offered regions' reconciliation, indentation regions, and a worker-measured
Fold All applied, stale after an edit or a rewrap and dropped after a fold.
`FoldingCommandsTest` runs the fold commands, caret moves over a collapsed
region, the reveal, Next and Previous Bookmark onto a hidden line, an edit at
a hidden secondary caret, independent split views and smart indent through
the controllers; `CommandDispatchTest` also runs every command with
every region collapsed in a split view.
`ColumnMetricsTest` checks column 150 at a 7.22 px advance.

`ProductivityTest` covers typed pairs, type-over and pair delete at two
carets, a block typing plainly, a sort on a worker as one undo step with the
view busy until it applies, a line transform dropped with a notice after an
edit, bookmarks following an
inserted break with wrap-around navigation, Go to and Select to Matching
Bracket at every caret and past the UI-thread bound, the bracket and
occurrence highlights, and completion: words of another open document, the
list narrowing, arrows, Esc, Enter, the single-word insert and the automatic
trigger. `CommandPaletteTest` covers `Fuzzy`, every menu command listed in
menu order, cascade titles, keys and enabled state, and running only an
enabled command. `BookmarksTest` covers bookmarks and comments through edits:
an insert above, a deleted line and last line restored by undo and removed
again by redo, a join keeping the upper bookmark, a cleared line, moved and
sorted lines, coalesced deletes undone; the comment bounds, wrap-around and
`LineEnds`. `BookmarkListTest` covers Edit Bookmark Comment (cleaned text, an
empty answer, a cancel, a refusal, the prompt's line followed through an
edit), the list following an insert, undo, a tab change and close, Go To
into a collapsed region, Edit Comment and Remove from the list, and the row
limit, text cut and a budget refusal.

`WorkspaceControllerTest` covers a directory listed when it expands, Refresh
keeping the old rows until a new file arrives, 1,200 entries shown 500 at a
time, Reveal Active File's chain and a file outside the folder, the index's
charge across a folder change and its return on dispose, and Open in Default
Application by extension and by the executable bit (POSIX only).
`QuickOpenTest` covers `:line` and `:line:column` parsing, name matches before
path matches and shorter paths first, an open document listed once and first,
a file opened at a line and column, Switch Document, and an index over 2,000
files matched on a worker. `NavigationHistoryTest` covers Go to Line and Next
Bookmark positions following an insert, a jump after Go Back dropping the
positions ahead, plain caret moves recording nothing, a tab switch recorded
once, a closed file reopened at its line with an untitled document skipped, a
deleted file dropped with a notice, and the 100-position limit.
`RecentFilesTest` covers newest first, a reopen moving a file up, a file
opened through a link and its target as one entry (skipped where links are
refused), the 20-file limit and Clear.

`ConfigStoreTest` covers the round trip, defaults, malformed and
other-version files, the `.invalid` copy, the 1 MiB cap, four full histories of
escaped text staying under half of it, a read-only directory and owner-only
creation. `CommandLineParserTest`, `SettingsLocationTest`,
`WindowGeometryTest`, `ThemeControllerTest`, `PaletteTest`, `MenuTableTest` (mnemonics unique
per menu), `UiTextTest`, `RecentLogTest`, `EventLoopWatchdogTest`,
`EncodingChoicesTest` and `LineConversionTest` cover the rest.
