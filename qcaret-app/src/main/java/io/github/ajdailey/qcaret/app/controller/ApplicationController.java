package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the main window's commands and owns the settings and the theme. Confined to the UI thread;
 * file reads and settings writes run on {@code qcaret-lifecycle-} workers.
 *
 * <p>A theme change saves {@code qcaret.conf} at once. {@link #quit} records and saves the window's
 * normal bounds and maximized state unless {@code rememberWindow} is false (a {@code --window-*}
 * option or {@code -m} was given), closes the workers, then disposes the view. Each save captures
 * the settings with a sequence number on the UI thread; the writer holds {@link #saveLock} and
 * skips a capture older than the last one written, so a theme save that finishes after the quit
 * save cannot replace it.
 */
public final class ApplicationController {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final ConfigStore config;
  private final ThemeController theme;
  private final boolean rememberWindow;
  private final List<Runnable> finishListeners = new ArrayList<Runnable>();
  private Preferences preferences;
  private boolean quitting;
  private long captured;

  /** Orders settings writes from different lifecycle workers; the UI thread never takes it. */
  private final ReentrantLock saveLock = new ReentrantLock();

  /** The sequence of the last capture written; guarded by {@link #saveLock}. */
  private long written;

  /**
   * Creates the controller.
   *
   * @param workers the thread owner; {@link #quit} closes it
   * @param ui the route onto the UI thread
   * @param view the main window
   * @param config the settings store
   * @param preferences the settings loaded at startup
   * @param system the system's light or dark setting
   * @param rememberWindow false when the quit saves nothing
   */
  public ApplicationController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      ConfigStore config,
      Preferences preferences,
      ThemeService system,
      boolean rememberWindow) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.config = config;
    this.preferences = preferences;
    this.theme = new ThemeController(system, preferences.theme());
    this.rememberWindow = rememberWindow;
  }

  /**
   * Returns the theme, which pushes palette changes to the views.
   *
   * @return the theme controller
   */
  public ThemeController theme() {
    return theme;
  }

  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  public Preferences preferences() {
    return preferences;
  }

  /**
   * Reports whether {@link #quit} has run.
   *
   * @return true once quitting
   */
  public boolean isQuitting() {
    return quitting;
  }

  /**
   * Adds a listener that runs on the lifecycle worker once the quit has saved and closed the
   * workers.
   *
   * @param listener the listener
   */
  public void addFinishListener(Runnable listener) {
    finishListeners.add(listener);
  }

  /**
   * Runs a command.
   *
   * @param command the command
   */
  public void execute(CommandId command) {
    switch (command) {
      case QUIT -> quit();
      case THEME_LIGHT -> setTheme(ThemeMode.LIGHT);
      case THEME_DARK -> setTheme(ThemeMode.DARK);
      case THEME_SYSTEM -> setTheme(ThemeMode.SYSTEM);
      case ABOUT -> showAbout();
    }
  }

  /**
   * Sets {@code ui.theme}, saves it and applies the palette. When native controls show the theme
   * only from the next start, says so.
   *
   * @param mode the theme
   */
  public void setTheme(ThemeMode mode) {
    if (quitting) {
      return;
    }
    preferences = preferences.withTheme(mode);
    save(capture());
    if (!theme.setMode(mode)) {
      view.showInfo(UiText.THEME_RESTART_TITLE.text(), UiText.THEME_RESTART_MESSAGE.text());
    }
  }

  /** Shows the About dialog. */
  public void showAbout() {
    view.showAbout(AboutInfo.collect(view.toolkitDescription(), config.location()));
  }

  /** Reads the license notices from the jar on a worker, then shows them. */
  public void showLicenses() {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final String text = readLicenses();
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        view.showText(UiText.LICENSES_TITLE.text(), text);
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Licenses not shown: workers are closed");
    }
  }

  @WorkerThread
  private static String readLicenses() {
    try {
      return AboutInfo.licenseText();
    } catch (IOException e) {
      return UiText.LICENSES_READ_FAILED.format(LogText.oneLine(e.getMessage()));
    }
  }

  /**
   * Quits: records and saves the window placement when remembered, hides the window, closes the
   * workers and disposes the window. A second call does nothing. Call on the UI thread.
   */
  public void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    if (rememberWindow) {
      WindowGeometry.Request bounds = view.windowBounds();
      preferences =
          preferences.withWindow(
              new WindowState(
                  bounds.x(), bounds.y(), bounds.width(), bounds.height(), view.windowMaximized()));
    }
    // Without a placement to record there is nothing new to write.
    final Snapshot snapshot = rememberWindow ? capture() : null;
    view.hide();
    Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            if (snapshot != null) {
              write(snapshot);
            }
            if (!workers.close(Workers.CLOSE_BOUND)) {
              LOG.warn(
                  "Worker threads still running after {}: {}",
                  Workers.CLOSE_BOUND,
                  LogText.oneLine(workers.liveThreadNames()));
            }
            finished();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    view.dispose();
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, finish)) {
      finished();
      view.dispose();
    }
  }

  private void finished() {
    for (Runnable listener : finishListeners) {
      listener.run();
    }
  }

  /** The settings captured on the UI thread, with their capture order. */
  private record Snapshot(long sequence, Preferences preferences) {}

  private Snapshot capture() {
    captured++;
    return new Snapshot(captured, preferences);
  }

  /** Writes a snapshot on a lifecycle worker. */
  private void save(final Snapshot snapshot) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                write(snapshot);
              }
            });
    if (!started) {
      LOG.warn("Settings not saved: workers are closed");
    }
  }

  /** Writes a snapshot unless a later capture has been written. */
  @WorkerThread
  private void write(Snapshot snapshot) {
    saveLock.lock();
    try {
      if (snapshot.sequence() > written) {
        written = snapshot.sequence();
        config.save(snapshot.preferences());
      }
    } finally {
      saveLock.unlock();
    }
  }
}
