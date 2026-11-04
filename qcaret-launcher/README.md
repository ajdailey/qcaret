# qcaret-launcher

Composes the application: parses the command line, configures logging and the
platform, then runs the SWT event loop. It is the only module that depends on
logback, and its test class path sees every module except the bootstrap, so
it holds the architecture rules.

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

`Gui` runs on the calling thread, which becomes the UI thread:

1. `Display.setAppName("QCaret")`, then the Display.
2. `Workers`, then `SwtThemeService.open`, before the first Shell: for light
   and dark it sets the native controls' theme (system leaves them to the OS);
   on macOS native controls always follow the OS.
3. The `MainWindow` with the resolved palette and its
   `ApplicationController`, whose documents draw on `MemoryBudget.fromRuntime()`; `AppMenu.hook` connects the macOS application menu
   (a no-op elsewhere).
4. The window is placed from the `--window-*` options, else the remembered
   `window.*` bounds, and maximized when it was maximized and no `--window-*`
   option is given. It opens and logs
   `Main window open: client area <w>x<h>, location <x>,<y>` at INFO.
5. File arguments open in tabs through `DocumentController.open`; without
   any, an untitled document opens.
6. At `-d 2` and above an `EventLoopWatchdog` logs UI-thread stalls.

The controller saves the placement at quit only when neither `-m` nor a
`--window-*` option was given, so a command-line placement applies to that run
only. The loop ends when the window is disposed; then the Display is disposed
and `run` returns 0.

A JVM shutdown hook covers an exit that skips the ordered quit (SIGTERM,
logoff, a parent ending the process). `ShutdownHook` posts
`ApplicationController.quit` through the dispatcher, waits up to 3 s for it to
save and close the workers, logs the threads still running past that bound,
and touches no widget; that quit asks nothing about unsaved documents. After
an ordered quit `Gui` removes the hook.

## Logging

`LogbackConfigurator` configures logback in code. It is registered in
`META-INF/services` at `CUSTOM_TOP_PRIORITY` and returns
`DO_NOT_INVOKE_NEXT_IF_ANY`, so logback never reads a `logback.xml`. There is
no XML configuration, so no logback condition is evaluated, and the enforcer
bans janino.

| Appender | Level | Destination |
|---|---|---|
| `stderr` | `-d`, WARN when absent | Standard error |
| `recent` | INFO, or `-d` when finer | `LogbackConfigurator.RECENT` (`RecentLog`); WARN and ERROR lines count as warnings |
| `qcaret-logfile` | DEBUG, or `-d` when finer | `<settings>/logs/qcaret-<pid>.log` through `FileLog`, while attached |

The root level is the finest of them. `FileLog` wraps a `RollingFileAppender`
(rolled to `.1` at 10 MiB) in an `AsyncAppender` with a 4,096-line queue that
drops a line when full and never blocks the logging thread; logback's own
daemon thread `AsyncAppender-Worker-qcaret-logfile` writes the file, the one
thread `Workers` does not own. The `logs` folder is created owner-only, and on
POSIX the file `rw-------`. An ERROR status from the file appender, its
rolling policy or its output stream detaches the file and logs an ERROR.

Every process names its file `qcaret-<pid>.log`; `FileLog.fileName` gives
`qcaret.log` only to the holder of the single-instance lock, which does not
exist yet. A process started with the file attached runs `FileLog.prune` on a
`qcaret-lifecycle-` worker: it deletes each `qcaret-<pid>.log` or `.log.1`
modified more than 7 days ago whose process is not running
(`ProcessHandle.of`).

The pattern ends with `%replace(%msg){...}`, which turns CR, LF, NEL, U+2028
and U+2029 into `_` behind `LogText.oneLine` at the call sites. The uncaught
exception handler logs the throwable with its stack trace.

## Architecture rules

`ArchitectureTest` checks every class except the bootstrap, which
`BootstrapArchitectureTest` keeps on the JDK; test classes and test jars are
not imported. A rule is never deleted to pass a build. A needed violation is
an `ignoreDependency` or a narrowed predicate naming the class, approved and
recorded with the design's rule table.

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
| logback only in `launcher` | Everything else logs through slf4j |
| No `java.net.http`, `URLConnection`, `Socket` or `URL.openStream`; `SocketChannel` and `ServerSocketChannel` only in `app.ipc` | No outbound network |
| No `ProcessBuilder`, `Runtime.exec`, object streams, `XMLDecoder`, or `java.util.Random` and its subtypes other than `SecureRandom` | Security |
| `System.exit` and `Runtime.halt` only in `launcher.Main` | Exit only before the Display exists |
| No `Display.syncExec`; `Display.asyncExec` and `timerExec` only in `SwtUiDispatcher` | A worker never waits on the UI thread; submissions after disposal are dropped |
| No `Future.get`, `Thread.join` or `sleep`, `Object.wait`, `Lock.lock*`, `Condition.await*`, `Semaphore.acquire*`, `BlockingQueue.put` or `take`, `CountDownLatch.await`, `OperationHandle.await` or `java.nio.channels` in `ui.swt` and `app.view` | The UI thread never blocks |
| No `Future.get`, `Thread.join`, `Thread.sleep`, `OperationHandle.await` or `CountDownLatch.await` in `app.controller` and `app.command` | Controllers run on the UI thread |
| No `System.getProperty` in `app.controller` | Platform facts come from the caller |
| In `app.controller`, `Files`, `AtomicFiles`, `SafePaths`, `ConfigStore.load` and `save`, `AboutInfo.licenseText` and any `@WorkerThread` method are called only from a `@WorkerThread` method or an anonymous `Runnable.run` | Blocking work stays off the UI thread |
| No `new Thread`, `Thread.ofVirtual`, `ofPlatform`, `startVirtualThread`, `Executors`, pool or `AbstractExecutorService` types, `java.util.Timer` or `ThreadFactory` implementations outside `Workers` | One owner for every thread |
| No `synchronized` methods in `core.concurrent`, `core.io` and `app.ipc` | A monitor pins a virtual thread; blocking code uses `ReentrantLock`. Synchronized blocks are not visible to ArchUnit |
| `StyledTextContent` implemented only in `ui.swt.editor` | One content adapter |
| `ui.swt` does not call `Document.apply`, `undo` or `redo` | Every edit goes through a controller |
| `DocumentBuilderFactory`, `SAXParserFactory` and `XMLInputFactory` only in `core.syntax` | Grammar files are the only XML parsed |

## Tests

`GuiTest` (tag `swt`) runs the composed application: a `-m` run with a file at
900 x 600, location 50,40 shuts down without a surviving `qcaret-` thread; a
remembered placement opens and is saved again; a `--window-width` run and a
`-m` run leave the saved placement unchanged. `PortableRunTest` (tag `swt`)
runs `Gui` twice over a portable bundle with `user.home` set as
`PlatformSetup` sets it: the first run switches to the dark theme and the
second starts dark; afterwards every new or changed file lies inside the
bundle and the home directory is empty. `SigtermTest` (tag `swt`, Linux)
starts `Main` with `-l` as a child process, waits for the window line in its
log, sends SIGTERM (`Process.destroy`) and checks that the process ends within
the hook's bound and saved the placement. Windows has no SIGTERM; `destroy`
there terminates without hooks.

`ShutdownHookTest` runs the hook's task directly. `LogbackConfiguratorTest`
puts a `logback.xml` on the test class path and checks that logback ignores
it. `FileLogTest` checks the file's location and levels, the per-process name,
the detach on an ERROR status, and which files prune deletes. `LauncherTest`
checks help, version, exit 1 for a bad option or settings path, `user.home`
per settings mode on Linux and the WebView2 data folder on Windows.
