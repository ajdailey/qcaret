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
thread, `timerExec` on the UI thread, no synchronous variant, and submissions
after disposal are dropped. `MainView` is the main window as the controller
sees it; every method runs on the UI thread and returns without waiting for
the user. Its `DocumentsView` part adds a tab per document (`addTab` returns
a `TabView`) and runs the file choosers and the unsaved-changes prompt, each
answering through a callback. A tab forwards its close button, selection and
drags as `TabInput`; an editor forwards its input as `EditorInput`, in
display offsets.

`WindowGeometry.place` sizes and positions the main window before it opens.
Per dimension the command line wins over the remembered geometry, which wins
over 1024 x 768. The size is the client area plus the trim. The monitor is the
one containing the requested or remembered top-left corner, else the primary;
the size is clamped to its work area, then the location so the whole window
lies inside it. Without a position, the window system places the window, and
where it ignores a position (Wayland) only the size applies.

## Commands (`app.command`)

`CommandId` lists every command with its menu (`CommandGroup`), label, check
state, cascade and, for a window-wide command, its accelerator: File > New,
Open, Save, Save As, Close and Exit; Edit > Undo, Redo, Cut, Copy, Paste and
Select All; View > Word Wrap and Theme > Light, Dark and System Theme; Help >
About QCaret. Editing keys are not menu accelerators: the editor dispatches
them, so they never fire from another control. `MenuTable.commands` returns a menu's commands in
declaration order. Where the system supplies an application menu (macOS),
About and Exit sit there and are left out of Help and File.

## Controller (`app.controller`)

`ApplicationController` runs the window's commands and owns the settings, the
theme and the window's `DocumentController`. It is confined to the UI thread;
settings writes and the license read run on `qcaret-lifecycle-` workers. Edit
commands from the menu go to the selected editor's `EditorController`.

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

`DocumentController` holds the window's documents, one tab each:

- New opens an untitled UTF-8 document ("Untitled N"). Open goes through
  `DocumentOpener`; a missing file opens an empty document for its path, a
  file already open selects its tab, and a document whose text does not encode
  back to the file's bytes opens read-only with a notice.
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
  changed lines (all of a batch's edits in one range).
- `RowBreaks` places a line's breaks: a row ends where the next char would
  pass the wrap column, after the row's last space or tab when it has one,
  and holds at most `MAX_ROW_CHARS` chars; columns count from the row start
  (tab stops, wide chars 2), and a surrogate pair is never split.
- `RowIndex` keeps only the lines with breaks, sorted, with their breaks in
  one array; edits replace a line range and shift the tail.
- A document change recomputes the changed lines' rows on the UI thread. A
  change over 1 Mi chars with wrap on gets only the long-line splits there,
  and the projection asks its owner for a rewrap.
- Turning wrap on or off and a new wrap width need a rewrap of every line:
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

`ConfigStore` reads and writes `Preferences` (`ui.theme` and `window.*`) in
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
a changed palette to its `PaletteListener`s. `Palette` holds the text view's
colors as toolkit-free `Rgb` values.

## UI text, About and logs

`UiText` names every string the UI shows; the English bundle
`messages.properties` holds them, and `ArchitectureTest.everyUiTextIsUsed`
fails on a constant nothing reads. `AboutInfo` collects the version, Java, OS,
toolkit and settings directory for About and Copy Details. `RecentLog` keeps
the last log lines for the launcher's `recent` appender, `LogFiles` is the
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
and About. `DocumentOpenerTest` holds a load in its decoder past the window's
close and closes after a result is posted; neither reaches the listener and
both release their reservation; a load over the budget is refused unread.
`DocumentControllerTest` covers open, edit and save, Save As for an untitled
document, a missing file, a second open of one file, the unsaved prompt's
three answers, `closeAll` stopping at Cancel, word wrap across editors and the
heap refusal. Two cases hold a save in the encoder of `GatedCharset` and
cancel it with Esc and with a tab close: the file must be unchanged, and a
second save while the first is held shows the notice.
`EditorControllerTest` covers multi-caret typing as one undo step, Enter's
line ending, Backspace and Delete over CR LF and surrogate pairs, cut, copy and
paste with line ending conversion and one line per caret, a read-only
document, view offsets mapped through virtual breaks, and Esc.
`DisplayProjectionTest` compares the projection with a display text built
line by line from `RowBreaks` after 1,500 seeded random edits that join and
part CR LF pairs, wrap on and off, replaying each display change on a mirror
string; it also covers stale and superseded rewraps and the large-change
fallback. `ColumnMetricsTest` checks column 150 at a 7.22 px advance.
`ConfigStoreTest` covers the round trip, defaults, malformed and
other-version files, the `.invalid` copy, the 1 MiB cap, a read-only directory
and owner-only creation. `CommandLineParserTest`, `SettingsLocationTest`,
`WindowGeometryTest`, `ThemeControllerTest`, `MenuTableTest` (mnemonics unique
per menu), `UiTextTest`, `RecentLogTest` and `EventLoopWatchdogTest` cover the
rest.
