# qcaret-launcher

Composes the application: parses the command line, configures logging and the
platform, then runs the SWT event loop. It is the only module that configures
java.util.logging (through `slf4j-jdk14`), and its test class path sees every
module except the bootstrap, so it holds the architecture rules.

## Startup and exit

`Launcher.run` returns the exit status:

| Status | Cause |
|---|---|
| 0 | Help, version, a launch handed to the running instance, or the window closed |
| 1 | Bad command line, a `--settings` path that cannot be a directory, or an `IOException` out of the log or GUI setup |
| 3 | SWT failed to start: its natives failed to load (`LinkageError`) or `SWTError`, from `Display` class initialization or `new Display()`; typically no loadable GTK |
| 4 | `UiFailures` quit the application |

`Main` calls `System.exit` only for a nonzero status, which arises before the
Display exists or, for 4, after the quit. After a normal close `main` returns and the JVM exits on
its own.

Before the Display exists, `Launcher` resolves the settings directory
(`SettingsLocation.fromSystem`) and loads `qcaret.conf`. In explicit and
portable mode on Linux, `PlatformSetup` then sets `user.home` to the settings
directory, so GTK SWT writes its `.swt/trims.prefs` there. On Windows, in
every mode, it sets `org.eclipse.swt.browser.EdgeDataDir` to
`<settings>/cache/webview2`, so an embedded WebView2 browser keeps its data in
the settings directory. With `-l` or `log.debugFile` it attaches the debug log
file.

`Gui` reads `qcaret_session.conf` and the theme file `ui.editorColors` names before
the Display. Without `-m`, a start
`StartupRequest.restoresSession` accepts (no `--diff` or `--no-session`, with or
without files, or `--session`) takes `SessionLock`, then restores the session
and owns it (`StartupRequest.ownsSession`); otherwise, or when another instance
holds the lock, only Recent Files and per-file state apply
([session lock](../qcaret-app/docs/sessions-and-recovery.md#session-lock)).
With `session.restore` off and no `--session`, the owning start opens none of
the saved windows
([session restore preference](../qcaret-app/docs/sessions-and-recovery.md#session-restore-preference)).
`run` releases the lock after the Display is disposed, so the quit's last
save comes first. It runs on the calling thread, which becomes the
UI thread:

1. `Display.setAppName("QCaret")`, then the Display.
2. `SwtThemeService.open`, before the first Shell, starts the platform's
   `ThemeFollow`: `GtkThemeFollow` sets the native theme for light and dark and
   leaves system to the OS; `Win32ThemeFollow` sets it for every mode, and a
   later change of side rebuilds the main windows through the `WindowViews`
   passed to `setWindowRebuild`; `CocoaThemeFollow` pins the application
   appearance to the resolved side, and `CocoaChrome` recolors the shells.
3. The `WindowSet`, whose documents draw on `MemoryBudget.fromRuntime()`, and
   its factory for later windows; `AppMenu.hook` connects the macOS application
   menu through `MenuBehavior.hookAppMenu` (a no-op except
   `CocoaMenuBehavior`). Then the first `MainWindow` with the resolved
   palette and its `ApplicationController`.
4. The window is placed from the `--window-*` options, else the session's first
   window on a restoring start, else the `window.*` bounds of `qcaret.conf`, and
   maximized when it was maximized and no `--window-*` option is given. It opens
   and logs `Main window open: client area <w>x<h>, location <x>,<y>, size
   <w>x<h>, work area <x>,<y> <w>x<h>, zoom <percent>` at INFO.
5. `ApplicationController.startSession` restores every saved window, then file
   arguments and `--diff` open in the first window through
   `ApplicationController.open`, and a file a restored tab shows has that tab
   selected; without a
   file, a window left empty opens an untitled document. After an error exit
   the restore skips the saved windows
   ([Error exit](../qcaret-app/docs/sessions-and-recovery.md#error-exit)).
6. At `-d 2` and above an `EventLoopWatchdog` logs UI-thread stalls.
7. On the primary instance `InstanceServer.deliverTo` releases the held
   launches and every later one to `WindowSet.openHandoff` on the UI thread.
   Quit closes the server after the session is written, so a new primary reads
   the final file, and before the workers.

The controller saves the placement at quit only when neither `-m` nor a
`--window-*` option was given, so a command-line placement applies to that run
only. The loop ends when every main window is disposed; then the Display is
disposed and `run` returns 0.

Before the Display, without `-m`, `Launcher` claims the instance lock
([single instance](../qcaret-app/docs/single-instance.md)). A held lock hands
the launch over and exits 0, before `-l` attaches a log file. A failure to take
the lock logs one WARN and runs on alone (`Handoff.ALONE`); it restores only if
it takes the session lock. An endpoint file the client refuses fails the
handoff as a missing one does. A JDK where `flock` cannot be linked
([`ProcessLock`](../qcaret-core/docs/files-and-encodings.md#process-lock)) is
such a failure; `RecoveryController` then also logs `Recovery files are off`.

`Gui.run` creates `Workers` and calls `InstanceServer.start` as its first steps,
before the session load and the Display; only the `FileLog` setup runs between
`claim` and `start`, and connections made in that gap wait in the socket backlog.
A run that ends before the first window closes the server, which answers held
launches `ERR`.

Before a handoff the launch calls `ForegroundRights.grantAny`
(`OsTraits.foregroundRights`). On Windows only the foreground process, or one
it permits, brings a window to the front, and a launch from Explorer's Open
With holds that right while the running instance does not: without the grant,
`MainWindow.toFront` only flashes the taskbar button. `WindowsForegroundRights`
calls `AllowSetForegroundWindow(ASFW_ANY)`; a failure is logged at debug and
the handoff goes ahead. Measured on Windows 11: Open With from Explorer
raised and focused the running window in 5 of 5 tries with the grant, and
flashed its taskbar button in 5 of 5 without it.

On macOS a Finder Open With can start a second registered copy of the bundle
with no file arguments; the files arrive only as `SWT.OpenDocument` events.
When the lock is held and the launch names no file, diff or session mode,
`HandoffDocuments.beforeHandoff` calls `LaunchDocuments.await` with a wait that
creates a Display without a window, runs `DocumentEventLoop.await`, then
disposes the Display; cocoa (`CocoaLaunchDocuments`) runs the wait and the
launch hands off the collected files, or none. Elsewhere
`NativeLaunchDocuments` returns none without a Display. The wait
ends 200 ms after the last event, or after 1 s without one (`QUIET_MILLIS`,
`FIRST_MILLIS`; unmeasured on a Mac). Those files also open in a `BESIDE` or
new-primary window.

After a failed handoff `Launcher.awaitSuccessor` polls for up to 2 s, so when a
primary quits or dies one launch becomes the primary and the others hand over to
it:

| Poll result | Launch |
|---|---|
| `InstanceServer.claim` succeeds | Primary: restores and saves as any primary |
| A different endpoint appears | Hands off again, at most 3 attempts in all |
| Neither within the 2 s | One WARN, then `Handoff.BESIDE` |

`BESIDE` runs next to the primary: `Gui` reads Recent Files from the session, and
restores and saves no session and no window placement. A primary that never
answers costs a launch 37 s before its window. `-d` and `-l` of a launch
that hands off apply to that process only, and so does `--session`: the running
instance restored its session at its own start.

### Names a launch does not open

On Windows the Java launcher hands a name outside the ANSI code page over with
`?` in place of those characters, and expands an unquoted argument holding `*`
or `?` into the files it matches before `main` runs. `Main` compares the
arguments with `sun.java.command`, which holds them unexpanded
(`WildcardExpansion`); after an expansion `UnopenedNames` drops every file and
`--diff` of the launch, since an expanded name can be another file. A file
argument holding `?` or `*` that is not a valid path is left out by the parser
(`CommandLine.unpassed`). Either way `Launcher` logs the message at INFO and the
first window shows it in a dialog after the session starts. Such a launch never
hands off: with a primary running it runs `BESIDE`, so the message shows in its
own window. `qcaret.cmd` passes the arguments through the environment, where
neither applies (`qcaret-bootstrap`).

Under `qcaret.cmd` `CmdStreams` closes standard output and error once the first
window is open, so a caller reading them through a pipe reaches the end;
`-v`, `-h` and command-line errors print before that.

A JVM shutdown hook covers an exit that skips the ordered quit (SIGTERM,
logoff, a parent ending the process). `ShutdownHook` posts
`WindowSet.quit` through the dispatcher, waits up to 3 s for it to
save and close the workers, logs the threads still running past that bound,
and touches no widget; that quit asks nothing about unsaved documents. It then
closes the `InstanceServer`, removing the endpoint. After an ordered quit `Gui`
removes the hook.

### UI-thread failures

`UiFailures` is the Display's runtime-exception and error handler
(`Display.setRuntimeExceptionHandler`, `setErrorHandler`), which SWT calls for a
failure of a listener, `asyncExec` or timer task; the event loop passes it any
failure out of `readAndDispatch` as well. Each failure is logged at ERROR with
its stack and the loop continues. More than 10 runtime exceptions within 1 s,
or any `Error`, writes `qcaret_error.flag` (`ErrorExitMarker`), runs
`WindowSet.quit` once and makes `Gui.run` return 4. That quit shows no dialog:
dirty buffers go to recovery files. A failure during the quit is logged and
starts no second one. A quit that throws leaves its hidden windows undisposed:
`UiFailures` logs it at ERROR, the event loop ends and `Gui.run` returns 4 with
the marker written. The marker write runs on the UI thread without SWT,
because a dialog or a worker may not run after an `Error`.

## Logging

SLF4J logs through `slf4j-jdk14` to java.util.logging (JUL). `Main` calls
`LogConfigurator.install` before any class logs. It sets
`java.util.logging.manager` to `RetainingLogManager` unless the property is set,
then calls `LogManager.reset()`, which drops what the JDK read from
`$JAVA_HOME/conf/logging.properties` or a `java.util.logging.config.*`
property, and attaches the root handlers:

| Handler | Level | Destination |
|---|---|---|
| `StderrHandler` | `-d`, WARN when absent | Standard error |
| `RecentHandler` | INFO, or `-d` when finer | `LogConfigurator.RECENT` (`RecentLog`); WARN and ERROR lines count as warnings |
| `FileRelayHandler` | DEBUG, or `-d` when finer | The `LogFileHandler` `FileLog` attaches: `<settings>/logs/qcaret.log` on the primary, else `qcaret-<pid>.log` |

`-d` maps to JUL as slf4j-jdk14 maps the SLF4J levels: `error` SEVERE, `warn`
WARNING, `info` INFO, `debug` FINE, `trace` FINEST. The
`io.github.ajdailey.qcaret` logger takes the finest handler level; every other
logger, including the JDK's own, stays at INFO, so `-d` and the file do not
turn on JDK debug output.

Every handler formats with `LineFormatter` and publishes on the logging thread:
`2026-11-04 12:00:00,123 WARN  [thread] i.g.a.q.l.Launcher - message`, then
the stack trace when there is one. The logger name is cut to 20 characters by
shortening packages from the left. CR, LF, NEL, U+2028 and U+2029 in the
message become `_`, a backstop behind `LogText.oneLine` at the call sites.

`LogFileHandler` writes each line with one unbuffered write, so a crash loses
no line, and a slow disk holds the thread that logs. Before a write would take
the file past 10 MiB it moves the file to `.1` and starts a new one. It writes
no lock file. The `logs` folder is created owner-only, and on POSIX the file
`rw-------`. A failed move reopens the file and keeps appending; the move is
tried again once the file has grown another 10 MiB, or at the next start, and
the first failure after a successful move logs a WARN. A failed write or open detaches the file and logs an ERROR.

From the command-line parse until the attach, `Launcher` holds the file's
records (`LogConfigurator.holdForFile`): `FileRelayHandler` keeps each one,
formatted, at the file's level, up to 1,048,576 characters, and counts the
records past that. The attach writes the kept records first, then one WARN
with the dropped count, so a WARN logged before the file opens, such as a
refused handoff, is in the file once. A launch that hands off, opens no file or
fails to open it discards the kept records and writes no file. The hold does
not change stderr or Show Log.

`RetainingLogManager` ignores `reset()` once configured. The JDK's logging
shutdown hook calls `reset()`, which would remove the handlers while
`ShutdownHook` still logs.

`FileLog.fileName` gives `qcaret.log` to the holder of the single-instance
lock and `qcaret-<pid>.log` to any other process, so no two processes roll one
file. The primary, started with the file attached, runs `FileLog.prune` on a
`qcaret-lifecycle-` worker: it deletes each `qcaret-<pid>.log` or `.log.1`
modified more than 7 days ago whose process is not running
(`ProcessHandle.of`).

The uncaught exception handler logs the throwable with its stack trace.

## Architecture rules

`ArchitectureTest` checks every class except the bootstrap, which
`BootstrapArchitectureTest` keeps on the JDK; test classes and test jars are
not imported. A rule is never deleted to pass a build. A needed violation is
an `ignoreDependency` or a narrowed predicate naming the class, approved and
recorded with the design's rule table.

Each rule is shown to fail: a fixture class in an `archfixture` package of the
test sources breaks it, and a test asserts that the rule's report names the
violation, and not the allowed call beside it where the fixture has one.
`unusedConstants` runs the `UiText` check on a fixture enum. Fixtures are test
classes, so the rules over the main classes never see them.

| Rule | Why |
|---|---|
| SWT only in `ui.swt` and `launcher` | Model and controllers test without a display |
| No dependency on `org.eclipse.swt.internal` | Public SWT API only, so GTK3 and GTK4 behave the same |
| `Class.forName`, `getDeclared*`, `getField`, `getMethod` and `setAccessible` only in `Gtk4ToolItemSizing`, `Win32Stacking`, `CocoaStacking`, `GtkX11Stacking`, `CocoaPrintJobNaming`, `Gtk3NativeNames`, `Win32BrowserBehavior`, `Win32ComboListSelection` (all `ui.swt.platform`) and `core.io.Flock` | SWT internals differ per platform, and `Flock` links the Java 21 foreign-function API; only these classes reach them by reflection |
| `SWT.getPlatform()` only in `ui.swt.platform`; `System.getenv` in `ui.swt` and `launcher` only there | Each view-layer platform difference goes through `PlatformTraits`: a value or a seam it chooses |
| A string starting `org.eclipse.swt.internal.` only in `ui.swt.platform` and the reflection classes above | SWT internal properties and class names go through `PlatformTraits`; the check reads each class file's constant pool |
| `core` does not depend on `app`, `ui` or `launcher`; `app` not on `ui` or `launcher` | Layering |
| `app.view` not on `app.{controller, search, config, session, recovery, cli, ipc}`; `app.editor` not on `app.controller`; `ui.swt` not on `core.{concurrent, io, buffer, edit, syntax, search, diff, watch, workspace}` or `app.{config, session, recovery, cli, ipc}` | Layering |
| Core layers, lowest first: `util`, `concurrent`, `io`, `text`, `buffer`, `document`, then `edit`, `syntax`, `search`, `diff`, `format`, `markdown`, which do not use each other; `watch` and `workspace` use `io` and below | Layering; an empty layer passes |
| No package cycles | Layering |
| `core.util` depends on no other QCaret package | Utilities stay leaf classes |
| Every `UiText` constant is read outside `UiText` | No unused bundle entry |
| `java.util.logging` and `org.slf4j.jul` only in `launcher` | Everything else logs through slf4j; only the launcher sets handlers, levels or the `LogManager` |
| No `java.net.http`, `URLConnection`, `Socket`, `ServerSocket`, `DatagramSocket`, `DatagramChannel`, `AsynchronousSocketChannel`, `URL.openStream` or `getContent`, or `InetAddress.getByName` or `getAllByName`; `SocketChannel` and `ServerSocketChannel` only in `app.ipc` | No network calls from QCaret's own code |
| No `ProcessBuilder`, any `Runtime.exec` overload, object streams, `XMLDecoder`, or `java.util.Random` and its subtypes other than `SecureRandom` | Security |
| `System.exit`, `Runtime.exit` and `Runtime.halt` only in `launcher.Main` | One exit point: `Main` exits with the nonzero status `Launcher.run` returns |
| No `Display.syncExec` or `syncCall`; `Display.asyncExec`, `timerExec` and `execute` only in `SwtUiDispatcher` | A worker never waits on the UI thread; submissions after disposal are dropped |
| No `Future.get`, `Thread.join` or `sleep`, `Object.wait`, `Lock.lock*`, `Condition.await*`, `Semaphore.acquire*`, `BlockingQueue.put` or `take`, `CountDownLatch.await`, `Charset.availableCharsets`, `Charsets.available` or `java.nio.channels` in `ui.swt` and `app.view` | The UI thread never blocks |
| No `Future.get`, `Thread.join`, `Thread.sleep` or `CountDownLatch.await` in `app.controller` and `app.command` | Controllers run on the UI thread |
| No `System.getProperty`, `getProperties` or `getenv`, and no `OsTraits.current()`, in `app.controller` | Platform facts come from the caller |
| In `app.controller`, the blocking calls (`BLOCKING` in `ArchitectureTest`: `DocumentFiles` estimates, loads and saves, `SafeSave`, `Files`, `FileChannel`, `Path.toRealPath`, `Charset.availableCharsets`, `Charsets.available`, `OsTraits.runsInsteadOfOpening`, `AtomicFiles`, `SafePaths`, `ConfigStore` and `SessionStore` `load` and `save`, `SessionLock`, `SessionRewrite.withoutSearches`, `RecoveryStore`, `RecoveryFile`, `AboutInfo.collect` and `licenseText`, `UserGuide.writeCopy`, `LogFiles.attach` and `detach`, `Workers.close`, `CpuSteps`, which runs a step inline off a worker) are made only from a `@WorkerThread` method or from `run` of an anonymous `Runnable` its creating method submits to `Workers`, directly or through a helper of its class that takes a `Runnable`; a creating method that makes another anonymous `Runnable` or takes a method reference does not qualify. `@WorkerThread` methods are called only from such code | The UI thread never waits; annotating a UI-thread method fails at its caller |
| No `new Thread`, `Thread.ofVirtual`, `ofPlatform`, `startVirtualThread`, `Executors`, pool or `AbstractExecutorService` types, `java.util.Timer`, `ForkJoinTask` types, `CompletableFuture` `*Async` calls, `BaseStream.parallel`, `Arrays.parallel*` or `ThreadFactory` implementations outside `Workers` | One owner for every thread |
| `FileChannel.lock` and `tryLock` only in `core.io.ProcessLock` | A POSIX record lock drops when any channel on the file closes |
| No `synchronized` methods in `core.concurrent`, `core.io` and `app.ipc` | A monitor pins a virtual thread; blocking code uses `ReentrantLock`. Synchronized blocks are not visible to ArchUnit |
| `StyledTextContent` implemented only in `ui.swt.editor` | One content adapter |
| `setImage` on `ToolItem`, `MenuItem`, `Button`, `TableItem`, `TreeItem`, `Item` and `CTabItem` only in `ToolIcons` | `ToolIcons` picks the icon's ink for the background it shows on |
| A code unit that creates a `Shell` calls `Dialogs.inheritIcons` and `DialogColors.apply`, except `MainWindow`'s top-level shell and `CompletionPopup` | Every dialog carries the application icon and the platform's dialog colors |
| A native `TabFolder` is created only in `ui.swt.platform` (`tabFoldersOnlyThroughDialogTabs`) | A dialog takes its folder from `PlatformTraits.dialogTabs`: `Win32DialogTabs` while win32's native theme is dark, `NativeDialogTabs` or `CocoaDialogTabs` otherwise |
| `Table.setLinesVisible` is called only in `ui.swt.platform` (`gridLinesOnlyThroughDialogColors`) | A table shows its lines through `Dialogs.gridLines`, drawn dark in a dark win32 dialog |
| A string starting `org.eclipse.swt.internal.win32.Table` only in `Win32HeaderLines` (`headerLineKeyOnlyInWin32HeaderLines`) | The header divider color key is a Public SWT API exception confined to one class |
| `Dialog.open` is called only in `ui.swt.NativeDialogs` (`nativeChoosersOnlyThroughNativeDialogs`) | A window rebuild waits only for a native dialog `NativeDialogs` counts |
| `ui.swt` does not call `Document.apply`, `undo` or `redo` | Every edit goes through a controller |
| `ui.swt` does not call `ThemeController.mode`, `DocumentController.wrap` or `count`, `LogFiles.attached`, or the `Document` state getters (`isReadOnly`, `isDirty`, `canUndo`, `canRedo`, `encoding`, `lineEndings`) | `CommandStates` computes menu and status state |
| `DocumentBuilderFactory`, `SAXParserFactory`, `XMLInputFactory` and `SchemaFactory` only in `core.xml` | `SecureXml` sets up every parser with external DTD, schema and entity access off ([XML parsers](../qcaret-core/docs/grammars.md#xml-parsers)) |
| The string `os.name` only in `core.util.os` | Operating system questions go through `OsTraits`; the check reads each class file's constant pool, since ArchUnit does not see call arguments |
| No `SWT.getPlatform()` or string `SWT_GTK4` in `core`, `app` or `launcher` | Toolkit platform questions stay in the view layer |
| No string `SWT_GTK4` outside `ui.swt.platform` (`platformOnlyInTraits`) | The traits answer which GTK SWT loaded |
| Outside `ui.swt.platform` and `core.util.os`, no call of a boolean `PlatformTraits` or `OsTraits` method other than the value traits (`macKeys`, `linuxKeys`, `fileNamesIgnoreCase`, `hidden`, `runsInsteadOfOpening`, `watchServiceByDefault`; `behaviorOnlyThroughSeams`) | Platform behavior goes through a seam interface the traits choose; a trait answers a value |
| No call of `Display.getSystemMenu`, `getSystemTray`, `getSystemTaskBar`, `isSystemDarkTheme` or `Program.findProgram` outside `ui.swt.platform` (`platformQueriesOnlyInThePlatformPackage`) | Their answer is the platform; shared code takes it from a seam or a value trait |
| Fields, parameters and return types declare a collection interface, never a class implementing `Collection` or `Map`; `Properties` excepted (`collectionsDeclaredByInterface`) | One declared type per role; locals are a review item |
| A boolean or enum value trait is called only by the classes `VALUE_TRAIT_CALLERS` names (`valueTraitsOnlyInReviewedCallers`) | ArchUnit does not see a condition, so each new caller is reviewed: it passes the value on, or calls a seam instead |
| No `Charset.forName` or `Charset.availableCharsets` outside `core.text.Charsets`, `core.io.Flock` excepted (`charsetLookupsOnlyInCharsets`) | Every charset name, alias included, resolves `windows-1252` to `Windows1252`; `Flock` sits below `core.text` and looks up only the path encoding `sun.jnu.encoding` |

## Testing

| Class | Covers |
|---|---|
| `GuiTest` (tag `swt`) | The composed application: a `-m` run with a file at 900 x 600, location 50,40 shows the file's title and shuts down without a surviving `qcaret-` thread; a remembered placement opens with the untitled document's title and is saved again; a `--window-width` run and a `-m` run leave the saved placement unchanged; a saved theme file's dark colors on the first window of a dark-theme start, set before it opens; a `BESIDE` launch restores no session and saves no session or placement; a quit with a reopen's read held in the decoder exits 0 with no surviving thread. The title arrives on a later UI turn, so the test waits for it within a bound; the untitled document's title is set before the event loop starts; a listener that throws costs one event and the loop runs the next one. A size or location, shown or saved, one point off at a fractional zoom counts as exact, and a location is expected clamped into the work area as `WindowGeometry.place` clamps it |
| `UiFailuresTest` | 10 failures within 1 s keep running, the 11th quits once and later failures start no second quit, failures more than 1 s apart keep running, an `Error` quits; each quit writes the marker, and a failed write still quits; a quit that throws sets `quitFailed` once |
| `ErrorExitStartTest` | Without the marker nothing changes; a restoring start skips, shows the notice and deletes the marker; a start without the session shows the short notice once and marks it shown, and a later restoring start skips without a notice; only a skipping start moves the snapshot files to `recovery/` |
| `ErrorExitGuiTest` (tag `swt`) | An `Error` twice from UI tasks after a restored tab is typed into: status 4, the marker written, one recovery file, the session still listing the tab. The next restoring start with a file shows the notice, opens only that file, offers the buffer and deletes the marker; the start after it restores that file without a notice. A `--no-session` start with a file shows the short notice once; a later restoring start skips the saved tab. A skipped session's snapshot buffer is offered for recovery. A quit that throws (a quit listener, run on the UI thread after the workers are closed) ends the loop with status 4 and the marker written |
| `StartWithFilesGuiTest` (tag `swt`) | With `session.snapshot` on: a start with a file over a saved session shows the saved tab and the file, selected, and its Quit asks nothing; the next plain start restores both, the file's buffer unsaved. With the session lock held in this JVM, a start with a file opens only it, its Quit asks and the session file keeps its tab. Files from open-document events (a `LaunchDocuments` stand-in for cocoa) restore and save the session as command-line files do |
| `SessionRestoreGuiTest` (tag `swt`) | With `session.restore` off: `--session` restores the saved tab; a plain start opens an untitled document, keeps Recent Files and saves the session without the tab; a snapshot buffer is offered for recovery. With it on, a plain start restores |
| `BusyCoverageTest` | Every `Workers` submission site in `qcaret-app` listed with what the user sees while it runs: an unlisted or vanished site fails, and so does a busy-cursor or progress row whose call its operation does not make; the call check shown to fail on a missing call |
| `PortableRunTest` (tag `swt`) | `Gui` run twice over a portable bundle with `user.home` set as `PlatformSetup` sets it: the first run switches to the dark theme and the second starts dark; afterwards every new or changed file lies inside the bundle and the home directory is empty |
| `PortableGuideTest` (tag `swt`, Linux, GTK 3) | A child JVM with its own `HOME`, no XDG base directory variables and system data directories without `applications` runs User Guide in a portable run: the main window names the guide's copy in a notice, no second shell opens, and the home holds no `.cache/QCaret`, `.local/share/QCaret` or `.cache/mesa_shader_cache`. GLib reads `HOME` from the environment, hence the separate process |
| `GtkFallbackTest` (tag `swt`, Linux) | A child JVM with `SWT_GTK4=1` and an empty `libgtk-4.so.1` first on `LD_LIBRARY_PATH`, so SWT loads GTK 3: `PlatformTraits.PLATFORM` gives the GTK 3 answers and the fallback WARN is logged |
| `SigtermTest` (tag `swt`, Linux) | `Main` with `-l` as a child process, SIGTERM (`Process.destroy`) after the window line: the process ends within the hook's bound, saved the placement and logged the hook's INFO line to the file. Windows has no SIGTERM; `destroy` there terminates without hooks |
| `WindowsGuiTest` (tag `swt`) | A second window through the composed application, a file handed over a real loopback exchange, the endpoint gone after quit; a handoff sent before the first window opens as a tab; a start over a two-window session (`--no-session` opens one window with an untitled document, a plain start opens both saved windows). It does not assert which window takes the file: a late `SWT.Activate` moves the target under load without a window manager, and `WindowSetTest` in `qcaret-app` pins the target |
| `QuitPromptsGuiTest` (tag `swt`) | Quit with a dirty document in each of two windows through the real unsaved dialogs: Cancel keeps both windows and both documents dirty; then Save writes one file, Don't Save leaves the other, and both windows close |
| `BesideLaunchTest` (tag `swt`) | `Launcher.run` in the test JVM while this JVM holds the lock with the endpoint deleted: one untitled window, the session's recent file in the File menu, its window not restored, the session file unchanged and no placement saved |
| `AbnormalShutdownTest` (tag `swt`) | `CrashChild` types into the untitled document, waits for its recovery file and is killed with `destroyForcibly`; `Gui` started again offers the buffer and restores it dirty |
| `BesideRunTest` (tag `swt`, Linux) | `Main` as a child process whose handoff fails under a held `instance.lock`, and one with a directory at `instance.lock` (`ALONE`) while this JVM holds `SessionLock`: each opens a window and, after SIGTERM, has left the session file and the placement unchanged. With `-l`, a handoff the endpoint check refuses (`rw-r--r--`) leaves its WARN in the child's log file once |
| `StoreFaultsTest` (tag `swt`) | A start over a malformed, oversized and unknown-version session file, and a remembered charset the runtime lacks |
| `ScreenshotsTest` (tag `swt`) | Runs only with `-Dqcaret.screenshots=<dir>` (`tools/screenshots.sh`): the README screenshots from generated mock files, in the light and the dark theme |
| `ShutdownHookTest` | The hook's task run directly |
| `LogConfiguratorTest` | The handlers, levels and properties of the planted `java.util.logging.config.file` (`planted-logging.properties`, set by surefire) gone after `install`; the format, level mapping and logger-name shortening |
| `RetainingLogManagerTest` | `reset()` keeping the handlers once retained |
| `FileRelayHandlerTest` | Held records written first and once, in order, then later records; records past the bound dropped and counted in one WARN; a release discarding them; nothing kept without a hold |
| `FileLogTest` | The file's location and levels, the folder holding only the file after a detach, the per-process name, the roll to `.1`, a failed roll (a non-empty directory named `qcaret.log.1`) writing on with one WARN, not tried again before the file grows by the size, and rolling then, a failed reopen (a read-only file) detaching the file with its ERROR in the recent lines, which files prune deletes; a hold's DEBUG line written once before the attach's lines, the level back to INFO after the hold and the detach, and a hold ended without a file writing nothing |
| `HandoffDocumentsTest` (tag `swt`) | Which launches take the system's files, the files becoming the request's, a launch naming a file or off cocoa handed as it is; on Linux the wait ends with no files and a later Display opens. The open-document events and stop rule are in `DocumentEventLoopTest` |
| `LauncherTest` | Help, version, exit 1 for a bad option or settings path, `user.home` per settings mode on Linux, the WebView2 data folder on Windows; a handoff a live primary does not take runs `BESIDE`, and a launch claims the lock a primary releases during its handoff |
| `UnopenedNamesTest` | After an expansion the request keeps its options and loses its files and comparison; without one it stands; the message for unpassed names and for an expansion, none when every name opens |
| `LauncherProcessTest` | 16 `LaunchMain` child JVMs against a closing primary and against a killed one: one `PRIMARY`, 15 `HANDED`, 15 launches received. A primary that reads every file in the settings directory keeps the lock and a second launch is `HANDED`; so does a primary refused a save over its lock. A primary refused a save over its `owner.lock` leaves a second start no buffers to claim. A child denied native access is `ALONE` with one WARN and no `instance.lock` |
| `ArchitectureTest` | The rules above, each shown to fail on its fixture |
