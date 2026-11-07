# qcaret-launcher

Composes the application: parses the command line, configures logging and the
platform, then runs the SWT event loop. It is the only module that configures
java.util.logging (through `slf4j-jdk14`), and its test class path sees every
module except the bootstrap, so it holds the architecture rules.

## Startup and exit

`Launcher.run` returns the exit status:

| Status | Cause |
|---|---|
| 0 | Help, version, or the window closed |
| 1 | Bad command line, or a `--settings` path that cannot be a directory |
| 3 | SWT failed to load (`UnsatisfiedLinkError` or `SWTError` from `new Display()`), typically missing GTK 3 libraries |

`Main` calls `System.exit` only for a nonzero status, which arises only before
the Display exists. After a normal close `main` returns and the JVM exits on
its own.

Before the Display exists, `Launcher` resolves the settings directory
(`SettingsLocation.fromSystem`) and loads `qcaret.conf`. In explicit and
portable mode on Linux, `PlatformSetup` then sets `user.home` to the settings
directory, so GTK SWT writes its `.swt/trims.prefs` there. On Windows, in
every mode, it sets `org.eclipse.swt.browser.EdgeDataDir` to
`<settings>/cache/webview2`, so an embedded WebView2 browser keeps its data in
the settings directory. With `-l` it attaches the debug log file.

`Gui` reads `qcaret_session.conf` and the theme file `ui.themeFile` names before
the Display. Without `-m`, a start
`StartupRequest.restoresSession` accepts (no file or `--diff`, or `--session`)
restores the session and owns it; otherwise only Recent Files and per-file state
apply. It runs on the calling thread, which becomes the
UI thread:

1. `Display.setAppName("QCaret")`, then the Display.
2. `Workers`, then `SwtThemeService.open`, before the first Shell: for light
   and dark it sets the native controls' theme (system leaves them to the OS);
   on macOS native controls always follow the OS.
3. The `WindowSet`, whose documents draw on `MemoryBudget.fromRuntime()`, and
   its factory for later windows; `AppMenu.hook` connects the macOS application
   menu (a no-op elsewhere). Then the first `MainWindow` with the resolved
   palette and its `ApplicationController`.
4. The window is placed from the `--window-*` options, else the session's first
   window on a restoring start, else the `window.*` bounds of `qcaret.conf`, and
   maximized when it was maximized and no `--window-*` option is given. It opens
   and logs `Main window open: client area <w>x<h>, location <x>,<y>` at INFO.
5. `ApplicationController.startSession` restores every saved window, then file
   arguments and `--diff` open through `ApplicationController.open`; without a
   file, a window left empty opens an untitled document.
6. At `-d 2` and above an `EventLoopWatchdog` logs UI-thread stalls.
7. On the primary instance `InstanceServer.deliverTo` releases the held
   launches and every later one to `WindowSet.openHandoff` on the UI thread.
   Quit closes the server after the session is written, so a new primary reads
   the final file, and before the workers.

The controller saves the placement at quit only when neither `-m` nor a
`--window-*` option was given, so a command-line placement applies to that run
only. The loop ends when every main window is disposed; then the Display is
disposed and `run` returns 0.

Before the Display, without `-m`, `Launcher` claims the instance lock (see the
`qcaret-app` README). A held lock hands the launch over and exits 0, before
`-l` attaches a log file. A settings directory another user owns or can write,
or a failure to take the lock, logs one WARN and runs on alone, as with `-m`.
A JDK where `flock` cannot be linked (`ProcessLock`, `qcaret-core` README) is
such a failure; `RecoveryController` then also logs `Recovery files are off`.

`Gui.run` creates `Workers` and calls `InstanceServer.start` as its first steps,
before the session load and the Display; only the `FileLog` setup runs between
`claim` and `start`, and connections made in that gap wait in the socket backlog.
A run that ends before the first window closes the server, which answers held
launches `ERR`.

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

A JVM shutdown hook covers an exit that skips the ordered quit (SIGTERM,
logoff, a parent ending the process). `ShutdownHook` posts
`WindowSet.quit` through the dispatcher, waits up to 3 s for it to
save and close the workers, logs the threads still running past that bound,
and touches no widget; that quit asks nothing about unsaved documents. It then
closes the `InstanceServer`, removing the endpoint. After an ordered quit `Gui`
removes the hook.

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
| `LogFileHandler` | DEBUG, or `-d` when finer | `<settings>/logs/qcaret.log` on the primary, else `qcaret-<pid>.log`, through `FileLog`, while attached |

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
`rw-------`. A failed move reopens the file and keeps appending; the next line
over the size tries the move again, and the first failure after a successful
move logs a WARN. A failed write or open detaches the file and logs an ERROR.

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
| `core` does not depend on `app`, `ui` or `launcher`; `app` not on `ui` or `launcher` | Layering |
| `app.view` not on `app.{controller, search, config, session, recovery, cli, ipc}`; `app.editor` not on `app.controller`; `ui.swt` not on `core.{concurrent, io, buffer, edit, syntax, search, diff, watch, workspace}` or `app.{config, session, recovery, cli, ipc}` | Layering |
| Core layers, lowest first: `util`, `concurrent`, `io`, `text`, `buffer`, `document`, then `edit`, `syntax`, `search`, `diff`, which do not use each other; `watch` and `workspace` use `io` and below | Layering; an empty layer passes |
| No package cycles | Layering |
| `core.util` depends on no other QCaret package | Utilities stay leaf classes |
| Every `UiText` constant is read outside `UiText` | No unused bundle entry |
| `java.util.logging` and `org.slf4j.jul` only in `launcher` | Everything else logs through slf4j; only the launcher sets handlers, levels or the `LogManager` |
| No `java.net.http`, `URLConnection`, `Socket` or `URL.openStream`; `SocketChannel` and `ServerSocketChannel` only in `app.ipc` | No outbound network |
| No `ProcessBuilder`, `Runtime.exec`, object streams, `XMLDecoder`, or `java.util.Random` and its subtypes other than `SecureRandom` | Security |
| `System.exit` and `Runtime.halt` only in `launcher.Main` | Exit only before the Display exists |
| No `Display.syncExec`; `Display.asyncExec` and `timerExec` only in `SwtUiDispatcher` | A worker never waits on the UI thread; submissions after disposal are dropped |
| No `Future.get`, `Thread.join` or `sleep`, `Object.wait`, `Lock.lock*`, `Condition.await*`, `Semaphore.acquire*`, `BlockingQueue.put` or `take`, `CountDownLatch.await`, `OperationHandle.await` or `java.nio.channels` in `ui.swt` and `app.view` | The UI thread never blocks |
| No `Future.get`, `Thread.join`, `Thread.sleep`, `OperationHandle.await` or `CountDownLatch.await` in `app.controller` and `app.command` | Controllers run on the UI thread |
| No `System.getProperty` in `app.controller` | Platform facts come from the caller |
| In `app.controller`, the blocking calls (`BLOCKING` in `ArchitectureTest`: `DocumentFiles` loads and saves, `SafeSave`, `Files`, `FileChannel`, `Path.toRealPath`, `Charset.availableCharsets`, `AtomicFiles`, `SafePaths`, `ConfigStore.load` and `save`, `AboutInfo.collect` and `licenseText`, `UserGuide.writeCopy`, `LogFiles.attach` and `detach`, `Workers.close`) are made only from a `@WorkerThread` method or from `run` of an anonymous `Runnable` its creating method submits to `Workers`, directly or through a helper of its class that takes a `Runnable`; a creating method that makes another anonymous `Runnable` or takes a method reference does not qualify. `@WorkerThread` methods are called only from such code | The UI thread never waits; annotating a UI-thread method fails at its caller |
| No `new Thread`, `Thread.ofVirtual`, `ofPlatform`, `startVirtualThread`, `Executors`, pool or `AbstractExecutorService` types, `java.util.Timer` or `ThreadFactory` implementations outside `Workers` | One owner for every thread |
| `FileChannel.lock` and `tryLock` only in `core.io.ProcessLock` | A POSIX record lock drops when any channel on the file closes |
| No `synchronized` methods in `core.concurrent`, `core.io` and `app.ipc` | A monitor pins a virtual thread; blocking code uses `ReentrantLock`. Synchronized blocks are not visible to ArchUnit |
| `StyledTextContent` implemented only in `ui.swt.editor` | One content adapter |
| `ui.swt` does not call `Document.apply`, `undo` or `redo` | Every edit goes through a controller |
| `ui.swt` does not call `ThemeController.mode`, `DocumentController.wrap` or `count`, `LogFiles.attached`, or the `Document` state getters (`isReadOnly`, `isDirty`, `canUndo`, `canRedo`, `encoding`, `lineEndings`) | `CommandStates` computes menu and status state |
| `DocumentBuilderFactory`, `SAXParserFactory` and `XMLInputFactory` only in `core.syntax` | Grammar files are the only XML parsed |

## Tests

`GuiTest` (tag `swt`) runs the composed application: a `-m` run with a file at
900 x 600, location 50,40 shows the file's title and shuts down without a
surviving `qcaret-` thread; a remembered placement opens with the untitled
document's title and is saved again; a `--window-width` run and a `-m` run
leave the saved placement unchanged. The title arrives on a later UI turn, so
the test waits for it within a bound; the untitled document's title is set
before the event loop starts. A quit with a reopen's read held in the decoder
exits 0 with no surviving thread. `PortableRunTest` (tag `swt`)
runs `Gui` twice over a portable bundle with `user.home` set as
`PlatformSetup` sets it: the first run switches to the dark theme and the
second starts dark; afterwards every new or changed file lies inside the
bundle and the home directory is empty. `SigtermTest` (tag `swt`, Linux)
starts `Main` with `-l` as a child process, waits for the window line in its
log, sends SIGTERM (`Process.destroy`) and checks that the process ends within
the hook's bound, saved the placement, and logged the hook's INFO line to the
file. Windows has no SIGTERM; `destroy` there terminates without hooks.
`WindowsGuiTest` (tag `swt`) opens a second window through the composed
application, hands a file over a real loopback exchange, quits and checks that
the endpoint is gone. It does not assert which window takes the file: a late
`SWT.Activate` moves the target under load without a window manager.
`WindowSetTest` in `qcaret-app` pins the target.
It also starts over a two-window session: `--no-session` opens one window with
an untitled document, a plain start opens both saved windows.
`ScreenshotsTest` (tag `swt`) runs only with `-Dqcaret.screenshots=<dir>`
(`tools/screenshots.sh`): it opens generated mock files and writes the README
screenshots in the light and the dark theme.

`AbnormalShutdownTest` (tag `swt`) starts `CrashChild`, which types into the
untitled document, waits for its recovery file, kills it with `destroyForcibly`
and starts `Gui` again: the buffer is offered and restores dirty. `StoreFaultsTest`
(tag `swt`) starts over a malformed, oversized and unknown-version session file
and a remembered charset the runtime lacks.

`ShutdownHookTest` runs the hook's task directly. Surefire plants
`java.util.logging.config.file` (`planted-logging.properties`);
`LogConfiguratorTest` checks that its handlers, levels and properties are gone
after `install`, and the format, level mapping and logger-name shortening.
`RetainingLogManagerTest` checks that `reset()` keeps the handlers once
retained. `FileLogTest` checks the file's location and levels, that the folder
holds only the file after a detach, the per-process name, the roll to `.1`,
that a failed roll (a non-empty directory named `qcaret.log.1`) keeps writing
the file with one WARN and rolls once the directory is gone, that a failed
reopen (a read-only file) detaches the file and its ERROR reaches the recent
lines, and which files prune deletes. `LauncherTest`
checks help, version, exit 1 for a bad option or settings path, `user.home`
per settings mode on Linux and the WebView2 data folder on Windows.
`LauncherProcessTest` runs 16 `LaunchMain` child JVMs against a closing primary
and against a killed one: one `PRIMARY`, 15 `HANDED`, 15 launches received. A
primary that reads every file in the settings directory keeps the lock and a
second launch is `HANDED`; so does a primary refused a save over its lock. A
primary refused a save over its `owner.lock` leaves a second start no buffers
to claim. A child denied native access is `ALONE` with one
WARN and no `instance.lock`.
