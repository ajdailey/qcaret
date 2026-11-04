# qcaret-app

The application layer without a UI toolkit: command line, commands and menus,
the controller, view contracts, settings, themes, UI strings, About facts and
the log ring. It depends on `qcaret-core` and slf4j only; another toolkit
reuses all of it and supplies `MainView`, `UiDispatcher` and `ThemeService`.
The packages `app.editor`, `app.search`, `app.session`, `app.recovery` and
`app.ipc` are empty.

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
the user.

`WindowGeometry.place` sizes and positions the main window before it opens.
Per dimension the command line wins over the remembered geometry, which wins
over 1024 x 768. The size is the client area plus the trim. The monitor is the
one containing the requested or remembered top-left corner, else the primary;
the size is clamped to its work area, then the location so the whole window
lies inside it. Without a position, the window system places the window, and
where it ignores a position (Wayland) only the size applies.

## Commands (`app.command`)

`CommandId` lists every command with its menu (`CommandGroup`), label, check
state and cascade: File > Exit, View > Theme > Light, Dark and System Theme,
Help > About QCaret. `MenuTable.commands` returns a menu's commands in
declaration order. Where the system supplies an application menu (macOS),
About and Exit sit there and are left out of Help and File.

## Controller (`app.controller`)

`ApplicationController` runs the window's commands and owns the settings and
the theme. It is confined to the UI thread; settings writes and the license
read run on `qcaret-lifecycle-` workers.

- A theme change saves `qcaret.conf` at once and pushes the palette. When
  native controls show the change only at the next start (`ThemeService
  .showsNow`), the view shows a notice.
- `quit` runs once: it records the window's normal bounds and maximized state,
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
and About. `ConfigStoreTest` covers the round trip, defaults, malformed and
other-version files, the `.invalid` copy, the 1 MiB cap, a read-only directory
and owner-only creation. `CommandLineParserTest`, `SettingsLocationTest`,
`WindowGeometryTest`, `ThemeControllerTest`, `MenuTableTest` (mnemonics unique
per menu), `UiTextTest`, `RecentLogTest` and `EventLoopWatchdogTest` cover the
rest.
