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
`FindListener`. `MainView` is also an
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
selection mode), Search (Find, Find Next, Find Previous, Replace), View (word
wrap, zoom, theme, the Split cascade), Encoding (the line-ending conversions;
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
Above and Below are Mod+Alt+Shift+Up and Down, and Ctrl+Shift+Up and Down on
macOS (Ctrl+Alt+Up and Down switch workspaces on several Linux desktops).
GNOME's defaults bind Ctrl+Alt+Shift+Up and Down to move the window to another
workspace (verified on Ubuntu GNOME), so there only the Selection menu reaches
the commands. `KeyStroke.CONTROL` is the macOS Control key. F6 focuses the
other split view; the split commands have no key. `reference(filter)` lists
the bindings whose group,
command or keys contain the filter. Shortcut overrides are not built.

## Controller (`app.controller`)

`ApplicationController` runs the window's commands and owns the settings, the
theme, the keymap, the editor font step and the window's `DocumentController`,
`HelpController` and `FindController`. It is confined to the UI thread; settings writes run on
`qcaret-lifecycle-` workers. Edit commands from the menu go to the selected
editor's `EditorController`. After `quit` starts, `execute` does nothing.

`CommandStates` computes every menu item's checked and enabled state, the
Window menu's document list, a tab menu's state and the status bar's
`StatusFields`, reading the controllers on each call. Views read this state
there and never from controller getters (ArchUnit
`viewsTakeCheckStateFromCommandStates`). Without a document only the window
commands, zoom, theme and Help are enabled; a read-only document disables the
edits and the line-ending conversions; a binary file's view also disables Save,
Save As and both encoding cascades. A disabled menu item's accelerator does
not fire, so the view syncs enablement on every `documentStateChanged`.
`EncodingChoices` lists `TextEncoding.MENU` first, then every other charset;
`Charset.availableCharsets` costs 130 to 229 ms cold (measured in QLogg), so
`ApplicationController.loadEncodings` computes the list once per run on a
worker.

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

- A theme change saves `qcaret.conf` at once and pushes the palette. When
  native controls show the change only at the next start (`ThemeService
  .showsNow`), the view shows a notice.
- Exit (`QUIT`, the window's close button, the macOS Quit) first closes the
  documents through `DocumentController.closeAll`, which asks about each dirty
  one; Cancel keeps the window open.
- `quit` asks nothing and runs once: it closes the documents, records the
  window's normal bounds and maximized state,
  hides the window, then on a worker saves the settings, closes `Workers`,
  runs the finish listeners and posts the window's disposal. With
  `rememberWindow` false (a `--window-*` option or `-m`) it records and saves
  nothing.
- Each save captures the settings with a sequence number on the UI thread.
  The writer holds `saveLock` and skips a capture older than the last one
  written, so a theme save that finishes after the quit save cannot replace
  it.
- `showLicenses` reads `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` from the jar
  on a worker and shows them through `MainView.showText`.
- Zoom In, Zoom Out and Reset Zoom step the editor font one point, from 6
  below to 24 above the default, for this run; the view changes the font in
  place.

`DocumentController` holds the window's documents, one tab each:

- New opens an untitled UTF-8 document ("Untitled N"). Open goes through
  `DocumentOpener`; a missing file opens an empty document for its path, a
  file already open selects its tab, and a document whose text does not encode
  back to the file's bytes opens read-only with a notice.
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
  A target changed on disk since the load or the last save is not written and
  the notice points to Save As.
- Close asks Save, Don't Save or Cancel for a dirty document; Save closes once
  the save succeeds. A closed document disposes its editor and releases its
  heap reservation.
- Word wrap is one setting for the window's editors.
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
- Every match is computed on a `qcaret-cpu-` worker from a snapshot; a newer
  request cancels the running one. A result carries its generation and
  revision and is dropped when either is stale. An invalid regex, a stack
  overflow and an invalid replacement end with a notice in the bar.

`DocumentOpener` opens files for one window on `qcaret-io-` workers. Before
reading, it estimates the load (`DocumentFiles.estimate`) and reserves it from
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
- Turning wrap on or off, a new wrap width and, with wrap on, a new tab width
  need a rewrap of every line:
  `rewrapRequest` returns a `Rewrap`, `compute` runs on a worker from the
  request's snapshot, and `install` applies it only while the document is
  still at that revision; a stale or superseded result is dropped and the
  owner asks again.

`ColumnMetrics` holds a monospace font's column geometry: the advance is the
measured width of 1,000 digits over 1,000, kept fractional, and column `c`
starts at `round(c * advance)`. The gutter width and the wrap width in columns
use it; text outside printable ASCII is placed at the widget's measured glyph
positions.

## Settings (`app.config`)

`SettingsLocation` picks the settings directory: `--settings <dir>`
(explicit), the installation directory `qcaret.home` when it holds
`qcaret.conf` (portable), else the per-user directory of the OS. The
bootstrap applies the same order to the SWT native cache.

`ConfigStore` reads and writes `Preferences` (`ui.theme`, `window.*` and
`log.debugFile`, which attaches the debug log file at the next start) in
`qcaret.conf`: Java properties, ISO-8859-1, sorted keys, no date comment,
`version=1`. Both calls block and run off the UI thread; a lock serializes
them.

| Condition | Behavior |
|---|---|
| File missing | Defaults |
| No `version` key | Read as version 1, so an empty portable marker is valid |
| Unreadable, over 1 MiB, does not parse, or another version | Malformed: defaults, one WARN, and the first save copies it to `qcaret.conf.invalid` before replacing it |
| A value that does not parse or is out of range | That value's default, one WARN naming the key; the file is not malformed |
| Read-only directory | Saving disabled with one WARN |
| Per-user directory missing | Created owner-only by the first save |

Writes go through `AtomicFiles`. Only file names and keys are logged, never
values.

## Themes (`app.theme`)

`ThemeMode` is `ui.theme`: light, dark or system. `ThemeController` resolves
it to a `Palette` (system follows `ThemeService.systemDark` and its change
events), calls `ThemeService.preferDark` so native controls match, and pushes
a changed palette to its `PaletteListener`s.

`Palette` is the editor color scheme as toolkit-free `Rgb` values: text,
background, selection, current line, gutter and line numbers, caret,
whitespace marks, the find-match background, and a `TokenStyle` (color, bold,
italic, underline) for every `TokenRole`, which S4's highlighter assigns. The
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
any platform, the platform key choices (Add Caret, F6), the reference filter
and the accelerator, editor and window-filter lookups. `DocumentOpenerTest`
holds a load in its decoder past the window's
close and closes after a result is posted; neither reaches the listener and
both release their reservation; a load over the budget is refused unread.
`DocumentControllerTest` covers open, edit and save, Save As for an untitled
document, a missing file, a second open of one file, the unsaved prompt's
three answers, `closeAll` stopping at Cancel, word wrap across editors and the
heap refusal. Two cases hold a save in the encoder of `GatedCharset` and
cancel it with Esc and with a tab close: the file must be unchanged, and a
second save while the first is held shows the notice.
`MultiCaretTest` covers every caret moving and selecting, vertical and page
moves at the preferred column, block keys into virtual space with padded
typing undone as one step, a block from the view's columns with copy and cut,
column selection mode, add caret above and below and the click toggle, Add
Next Occurrence from a worker, line commands whose undo restores every caret,
paste per caret, composition text with a commit as one undo step, and Go to
with a tab width change. `EditingSettingsTest` covers Save All, Reopen Closed
Tab, Read-Only, the tab settings and the Go to Line prompt.

`SplitViewTest` covers two views with their own selections over one text,
the arrangement switch, focus, Unsplit keeping the focused view, and both
views stopped with the tab and reached by word wrap. `FindControllerTest`
covers incremental find and highlights, wrap notices, the selection seed, a
result dropped after an edit, regex and replacement failures, Replace and
Replace All as one undo step each, the notice for a Replace All dropped after
an edit, Esc dropping extra carets before it closes the bar, a regex
replacement with the line ending,
a read-only document, highlights in both views and their shift on an edit.
`EditorControllerTest` covers multi-caret typing as one undo step, Enter's
line ending, Backspace and Delete over CR LF and surrogate pairs, cut, copy and
paste with line ending conversion and one line per caret, a read-only
document, view offsets mapped through virtual breaks, and Esc.
`DisplayProjectionTest` compares the projection with a display text built
line by line from `RowBreaks` after 1,500 seeded random edits that join and
part CR LF pairs, wrap on and off, replaying each display change on a mirror
string; it also covers stale and superseded rewraps and the large-change
fallback, one change over distant edits, and a rewrap on a tab width change.
`ColumnMetricsTest` checks column 150 at a 7.22 px advance.
`ConfigStoreTest` covers the round trip, defaults, malformed and
other-version files, the `.invalid` copy, the 1 MiB cap, a read-only directory
and owner-only creation. `CommandLineParserTest`, `SettingsLocationTest`,
`WindowGeometryTest`, `ThemeControllerTest`, `PaletteTest`, `MenuTableTest` (mnemonics unique
per menu), `UiTextTest`, `RecentLogTest`, `EventLoopWatchdogTest`,
`EncodingChoicesTest` and `LineConversionTest` cover the rest.
