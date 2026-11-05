package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.EventLoopWatchdog;
import io.github.ajdailey.qcaret.app.log.LogFiles;
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
import java.nio.file.Path;
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
 * --window-*} option and no {@code -m}. File arguments open in tabs; without any, the window opens
 * an untitled document. At {@code -d 2} and above an {@link EventLoopWatchdog} logs UI-thread
 * stalls. A JVM shutdown hook ({@link ShutdownHook}) runs the quit when the process is ended
 * without it. With the debug log file attached, other processes' old log files are deleted on a
 * lifecycle worker ({@link FileLog#prune}). The loop ends when the window is disposed, then the
 * Display is disposed and {@link #run} returns.
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

  /**
   * Creates the composition.
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
    this.request = request;
    this.config = config;
    this.preferences = preferences;
    this.err = err;
    this.observer = observer;
    this.logFiles = logFiles;
  }

  /**
   * Runs until the main window closes.
   *
   * @return 0, or {@link Launcher#EXIT_SWT} when the Display cannot be created
   */
  int run() {
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
      Palette palette = ThemeController.resolve(preferences.theme(), system.systemDark());
      if (logFiles instanceof FileLog fileLog && fileLog.attached()) {
        pruneLogs(workers, fileLog);
      }
      final SwtUiDispatcher ui = new SwtUiDispatcher(display);
      final MainWindow window = new MainWindow(display, palette);
      boolean remember = !request.multi() && !request.placesWindow();
      ApplicationController controller =
          new ApplicationController(
              workers,
              ui,
              window,
              config,
              preferences,
              system,
              MemoryBudget.fromRuntime(),
              Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
              remember);
      controller.setLogging(LogbackConfigurator.RECENT, logFiles);
      window.attach(controller);
      // macOS application menu.
      AppMenu.hook(display, controller);
      WindowState remembered = preferences.window();
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
      if (request.files().isEmpty()) {
        controller.documents().newDocument();
      } else {
        List<Path> paths = new ArrayList<Path>();
        for (FileRequest file : request.files()) {
          paths.add(file.path());
        }
        controller.documents().open(paths);
      }
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
      Thread hook = registerHook(controller, ui, workers);
      try {
        if (observer != null) {
          observer.opened(window, controller, workers);
        }
        while (!window.shell().isDisposed()) {
          if (!display.readAndDispatch()) {
            display.sleep();
          }
        }
      } finally {
        removeHook(hook);
      }
    } finally {
      display.dispose();
    }
    return 0;
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
  private static Thread registerHook(
      final ApplicationController controller, final SwtUiDispatcher ui, final Workers workers) {
    CountDownLatch finished = new CountDownLatch(1);
    controller.addFinishListener(finished::countDown);
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
                            controller.quit();
                          }
                        });
                  }
                },
                finished,
                new Runnable() {
                  @Override
                  public void run() {
                    // No handoff endpoint to remove.
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
