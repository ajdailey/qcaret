package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.app.log.EventLoopWatchdog;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.AppMenu;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import io.github.ajdailey.qcaret.ui.swt.SwtThemeService;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes the application and runs the SWT event loop on the calling thread, which becomes the UI
 * thread. The theme resolves before the first Shell: light and dark also set the native controls'
 * theme ({@link SwtThemeService#open}), which on win32 a later switch reaches only at the next
 * start; on cocoa native controls follow the system. The window opens at the command-line geometry,
 * else the remembered one; the placement is saved at quit only when the command line gave no {@code
 * --window-*} option and no {@code -m}. A start that restores the session ({@link
 * StartupRequest#restoresSession}, never with {@code -m}) owns it: the session's bounds place every
 * window it lists, the first included, and {@code qcaret.conf} places the first window of any other
 * start. A {@code --window-*} option wins over both. File arguments open in tabs, after the
 * restore; a window left without a tab opens an untitled document. The session file is read before
 * the Display, like the settings. A launch whose handoff failed while a primary runs reads Recent
 * Files from it, and restores and saves no session. At {@code -d 2} and above an {@link
 * EventLoopWatchdog} logs UI-thread stalls. A JVM shutdown hook ({@link ShutdownHook}) runs the
 * quit when the process is ended without it. On the primary instance with the debug log file
 * attached, other processes' old log files are deleted on a lifecycle worker ({@link
 * FileLog#prune}). The {@link InstanceServer} delivers handed launches to {@link
 * WindowSet#openHandoff}, and quit closes it before the workers. The loop ends when every main
 * window is disposed, then the Display is disposed and {@link #run} returns.
 */
final class Gui {
  private static final Logger LOG = LoggerFactory.getLogger(Gui.class);

  /** Receives the composed application once the window is open; for tests. */
  interface Observer {
    /**
     * Called on the UI thread after the window opens.
     *
     * @param window the main window
     * @param controller the application controller
     * @param workers the thread owner
     */
    void opened(MainWindow window, ApplicationController controller, Workers workers);
  }

  private final StartupRequest request;
  private final ConfigStore config;
  private final Preferences preferences;
  private final PrintStream err;
  private final Observer observer;
  private final LogFiles logFiles;
  private final InstanceServer server;
  private final boolean beside;
  private final List<Shell> shells = new ArrayList<Shell>();

  /**
   * Creates the composition of an instance without a handoff listener.
   *
   * @param request the command line
   * @param config the settings store
   * @param preferences the settings loaded before the Display
   * @param err receives the SWT start failure
   * @param observer notified once the window is open, or null
   * @param logFiles the debug log file
   */
  Gui(
      StartupRequest request,
      ConfigStore config,
      Preferences preferences,
      PrintStream err,
      Observer observer,
      LogFiles logFiles) {
    this(request, config, preferences, err, observer, null, logFiles, false);
  }

  /**
   * Creates the composition.
   *
   * @param request the command line
   * @param config the settings store
   * @param preferences the settings loaded before the Display
   * @param err receives the SWT start failure
   * @param observer notified once the window is open, or null
   * @param server the handoff listener when this instance is the primary, else null; {@link #run}
   *     closes it
   * @param logFiles the debug log file
   * @param beside true when a primary runs and this launch could not hand over to it
   */
  Gui(
      StartupRequest request,
      ConfigStore config,
      Preferences preferences,
      PrintStream err,
      Observer observer,
      InstanceServer server,
      LogFiles logFiles,
      boolean beside) {
    this.request = request;
    this.config = config;
    this.preferences = preferences;
    this.err = err;
    this.observer = observer;
    this.logFiles = logFiles;
    this.server = server;
    this.beside = beside;
  }

  /**
   * Runs until every main window closes.
   *
   * @return 0, or {@link Launcher#EXIT_SWT} when the Display cannot be created
   */
  int run() {
    SessionStore sessions = new SessionStore(config.location());
    Session session = beside ? sessions.load().recentOnly() : sessions.load();
    Display.setAppName("QCaret");
    final Display display;
    try {
      display = new Display();
    } catch (UnsatisfiedLinkError | SWTError e) {
      err.println("qcaret: SWT failed to start: " + LogText.oneLine(e.getMessage()));
      LOG.debug("SWT failed to start", e);
      return Launcher.EXIT_SWT;
    }
    try (Workers workers =
        new Workers(Runtime.getRuntime().availableProcessors(), new Launcher.FailureLogger())) {
      final SwtThemeService system = SwtThemeService.open(display, preferences.theme());
      final Palette palette = ThemeController.resolve(preferences.theme(), system.systemDark());
      if (server != null && logFiles instanceof FileLog fileLog && fileLog.attached()) {
        pruneLogs(workers, fileLog);
      }
      final SwtUiDispatcher ui = new SwtUiDispatcher(display);
      boolean remember = !request.multi() && !beside && !request.placesWindow();
      final WindowSet set =
          new WindowSet(
              workers,
              ui,
              config,
              preferences,
              system,
              MemoryBudget.fromRuntime(),
              Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
              remember);
      set.setLogging(LogConfigurator.RECENT, logFiles);
      set.setFactory(
          new WindowSet.Factory() {
            @Override
            public ApplicationController create(
                WindowSet owner, WindowGeometry.Request at, boolean maximized) {
              WindowState remembered = owner.settings().current().window();
              MainWindow window = new MainWindow(display, palette);
              ApplicationController controller = new ApplicationController(owner, window);
              window.attach(controller);
              shells.add(window.shell());
              window.place(
                  at,
                  new WindowGeometry.Request(null, null, remembered.width(), remembered.height()));
              if (maximized) {
                window.shell().setMaximized(true);
              }
              window.open();
              return controller;
            }
          });
      // macOS application menu.
      AppMenu.hook(display, set);
      final MainWindow window = new MainWindow(display, palette);
      ApplicationController controller = new ApplicationController(set, window);
      window.attach(controller);
      shells.add(window.shell());
      controller.loadGrammars();
      boolean restore = !request.multi() && !beside && request.restoresSession();
      WindowState remembered =
          restore && !session.windows().isEmpty()
              ? session.windows().get(0).bounds()
              : preferences.window();
      window.place(
          new WindowGeometry.Request(request.x(), request.y(), request.width(), request.height()),
          new WindowGeometry.Request(
              remembered.x(), remembered.y(), remembered.width(), remembered.height()));
      if (remembered.maximized() && !request.placesWindow()) {
        window.shell().setMaximized(true);
      }
      window.open();
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              logGeometry(window.shell());
            }
          });
      controller.startSession(sessions, session, restore, request);
      if (EventLoopWatchdog.enabled()) {
        new EventLoopWatchdog(
                workers,
                ui,
                display.getThread(),
                EventLoopWatchdog.INTERVAL_MILLIS,
                EventLoopWatchdog.BOUND_MILLIS,
                EventLoopWatchdog.LOGGING)
            .start();
      }
      Thread hook = registerHook(set, ui, workers);
      try {
        if (server != null) {
          listen(set, ui, workers);
        }
        if (observer != null) {
          observer.opened(window, controller, workers);
        }
        while (anyOpen()) {
          if (!display.readAndDispatch()) {
            display.sleep();
          }
        }
      } finally {
        removeHook(hook);
      }
    } finally {
      if (server != null) {
        server.close();
      }
      display.dispose();
    }
    return 0;
  }

  /** Delivers handed launches to the UI thread; quit closes the listener first. */
  private void listen(final WindowSet set, final SwtUiDispatcher ui, Workers workers) {
    set.addQuitListener(server::close);
    boolean started =
        server.start(
            workers,
            new InstanceServer.Receiver() {
              @Override
              public boolean deliver(final StartupRequest handed) {
                if (set.isQuitting()) {
                  return false;
                }
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        set.openHandoff(handed);
                      }
                    });
                return true;
              }
            });
    if (!started) {
      LOG.debug("Handoff listener not started: workers are closed");
    }
  }

  private boolean anyOpen() {
    for (Shell shell : shells) {
      if (!shell.isDisposed()) {
        return true;
      }
    }
    return false;
  }

  /** Runs {@link FileLog#prune} on a {@code qcaret-lifecycle-} worker. */
  private static void pruneLogs(Workers workers, final FileLog fileLog) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                fileLog.prune(Instant.now(), FileLog::running);
              }
            });
    if (!started) {
      LOG.debug("Old log files not pruned: workers are closed");
    }
  }

  /** Registers the JVM shutdown hook that quits when the process is ended without a quit. */
  private Thread registerHook(
      final WindowSet set, final SwtUiDispatcher ui, final Workers workers) {
    CountDownLatch finished = new CountDownLatch(1);
    set.addFinishListener(finished::countDown);
    Thread hook =
        workers.shutdownHook(
            new ShutdownHook(
                new Runnable() {
                  @Override
                  public void run() {
                    ui.asyncExec(
                        new Runnable() {
                          @Override
                          public void run() {
                            set.quit();
                          }
                        });
                  }
                },
                finished,
                new Runnable() {
                  @Override
                  public void run() {
                    if (server != null) {
                      server.close();
                    }
                  }
                },
                ShutdownHook.BOUND,
                workers::liveThreadNames));
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  /** Removes the hook after an ordered quit; the JVM may already be running it. */
  private static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException e) {
      LOG.debug("Shutdown in progress; the hook stays");
    }
  }

  private static void logGeometry(Shell shell) {
    if (shell.isDisposed() || !LOG.isInfoEnabled()) {
      return;
    }
    Rectangle area = shell.getClientArea();
    Point location = shell.getLocation();
    LOG.info(
        "Main window open: client area {}x{}, location {},{}",
        area.width,
        area.height,
        location.x,
        location.y);
  }
}
