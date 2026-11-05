package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the main window's commands and owns the settings, the theme and the window's {@link
 * DocumentController}. Confined to the UI thread; file reads and settings writes run on {@code
 * qcaret-lifecycle-} workers. Edit commands from the menu go to the selected editor.
 *
 * <p>{@link CommandId#QUIT} first closes the documents, asking about each dirty one; a Cancel keeps
 * the window open. {@link #quit} itself asks nothing: the shutdown hook calls it.
 *
 * <p>Help commands run through {@link HelpController}. Menu check and enable state is read through
 * {@link CommandStates}. The editor font size steps by one point per zoom command between {@link
 * #MIN_FONT_STEP} and {@link #MAX_FONT_STEP} for this run.
 *
 * <p>A theme or Debug Log File change saves {@code qcaret.conf} at once. {@link #quit} records and
 * saves the window's normal bounds and maximized state unless {@code rememberWindow} is false (a
 * {@code --window-*} option or {@code -m} was given), closes the workers, then disposes the view.
 * Each save captures the settings with a sequence number on the UI thread; the writer holds {@link
 * #saveLock} and skips a capture older than the last one written, so a theme save that finishes
 * after the quit save cannot replace it.
 */
public final class ApplicationController {
  /** Smallest font step: points below the default size. */
  public static final int MIN_FONT_STEP = -6;

  /** Largest font step: points above the default size. */
  public static final int MAX_FONT_STEP = 24;

  private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final ConfigStore config;
  private final ThemeController theme;
  private final DocumentController documents;
  private final HelpController help;
  private final Keymap keymap;
  private final boolean rememberWindow;
  private final List<Runnable> finishListeners = new ArrayList<Runnable>();
  private Preferences preferences;
  private boolean quitting;
  private int fontStep;
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
   * @param budget the documents' heap share
   * @param keymap the key bindings
   * @param rememberWindow false when the quit saves nothing
   */
  public ApplicationController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      ConfigStore config,
      Preferences preferences,
      ThemeService system,
      MemoryBudget budget,
      Keymap keymap,
      boolean rememberWindow) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.config = config;
    this.preferences = preferences;
    this.theme = new ThemeController(system, preferences.theme());
    this.documents = new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS);
    this.keymap = keymap;
    this.help =
        new HelpController(
            workers,
            ui,
            view,
            config.location(),
            keymap,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return quitting;
              }
            });
    this.rememberWindow = rememberWindow;
  }

  /**
   * Connects Help > Show Log and the status bar's warning marker to the recent log lines, and Help
   * > Debug Log File to the log file. Call once, before the window opens.
   *
   * @param lines the recent lines
   * @param files the debug log file
   */
  public void setLogging(RecentLog lines, LogFiles files) {
    help.setLogging(lines, files);
    view.showWarningMarker(help.newWarnings());
  }

  /**
   * Returns the key bindings.
   *
   * @return the keymap
   */
  public Keymap keymap() {
    return keymap;
  }

  /**
   * Starts listing every charset on a worker for the Encoding menu; the listener receives them on
   * the UI thread while the window is open. The list is computed once per run.
   *
   * @param listener receives the choices
   */
  public void loadEncodings(final EncodingChoices.Listener listener) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final EncodingChoices choices = EncodingChoices.available();
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (!quitting) {
                          listener.loaded(choices);
                        }
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Encodings not listed: workers are closed");
    }
  }

  /** Reports whether the log goes to the debug log file; for {@link CommandStates}. */
  boolean debugLogFile() {
    return help.debugLogFile();
  }

  /** Returns the editor font step; for {@link CommandStates}. */
  int fontStep() {
    return fontStep;
  }

  /**
   * Returns the window's documents.
   *
   * @return the document controller
   */
  public DocumentController documents() {
    return documents;
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
    if (quitting) {
      return;
    }
    switch (command) {
      case NEW -> documents.newDocument();
      case OPEN -> documents.open();
      case SAVE -> documents.save();
      case SAVE_AS -> documents.saveAs();
      case SAVE_ALL -> documents.saveAll();
      case REOPEN_CLOSED -> documents.reopenClosed();
      case CLOSE -> documents.closeActive();
      case CLOSE_ALL -> documents.closeAll(null);
      case QUIT -> requestQuit();
      case UNDO,
          REDO,
          CUT,
          COPY,
          PASTE,
          SELECT_ALL,
          NEWLINE,
          DELETE_BACK,
          DELETE_FORWARD,
          CANCEL,
          DUPLICATE,
          DELETE_LINE,
          MOVE_LINES_UP,
          MOVE_LINES_DOWN,
          JOIN_LINES,
          UPPER_CASE,
          LOWER_CASE,
          INDENT,
          OUTDENT,
          INSERT_TAB,
          ADD_CARET_ABOVE,
          ADD_CARET_BELOW,
          ADD_NEXT_OCCURRENCE,
          SPLIT_INTO_LINES,
          COLUMN_MODE,
          CARET_LEFT,
          CARET_RIGHT,
          CARET_UP,
          CARET_DOWN,
          WORD_LEFT,
          WORD_RIGHT,
          LINE_START,
          LINE_END,
          PAGE_UP,
          PAGE_DOWN,
          DOCUMENT_START,
          DOCUMENT_END,
          SELECT_LEFT,
          SELECT_RIGHT,
          SELECT_UP,
          SELECT_DOWN,
          SELECT_WORD_LEFT,
          SELECT_WORD_RIGHT,
          SELECT_LINE_START,
          SELECT_LINE_END,
          SELECT_PAGE_UP,
          SELECT_PAGE_DOWN,
          SELECT_DOCUMENT_START,
          SELECT_DOCUMENT_END,
          BLOCK_LEFT,
          BLOCK_RIGHT,
          BLOCK_UP,
          BLOCK_DOWN ->
          edit(command);
      case TAB_WIDTH_2 -> documents.setTabWidth(2);
      case TAB_WIDTH_4 -> documents.setTabWidth(4);
      case TAB_WIDTH_8 -> documents.setTabWidth(8);
      case INDENT_WITH_SPACES -> documents.toggleInsertSpaces();
      case GO_TO_LINE -> documents.goToLine();
      case READ_ONLY -> documents.toggleReadOnly();
      case WORD_WRAP -> documents.toggleWrap();
      case ZOOM_IN -> setFontStep(fontStep + 1);
      case ZOOM_OUT -> setFontStep(fontStep - 1);
      case ZOOM_RESET -> setFontStep(0);
      case THEME_LIGHT -> setTheme(ThemeMode.LIGHT);
      case THEME_DARK -> setTheme(ThemeMode.DARK);
      case THEME_SYSTEM -> setTheme(ThemeMode.SYSTEM);
      case LINE_ENDING_CRLF -> documents.convertLineEndings(LineEnding.CRLF);
      case LINE_ENDING_LF -> documents.convertLineEndings(LineEnding.LF);
      case LINE_ENDING_CR -> documents.convertLineEndings(LineEnding.CR);
      case NEXT_TAB -> documents.selectNext(true);
      case PREVIOUS_TAB -> documents.selectNext(false);
      case CLOSE_OTHERS -> documents.closeOthers();
      case KEYBOARD_SHORTCUTS -> help.showShortcuts();
      case COMMAND_LINE_OPTIONS -> help.showCommandLine();
      case USER_GUIDE -> help.showGuide();
      case SHOW_LOG -> help.showLog();
      case DEBUG_LOG_FILE -> setDebugLogFile(!help.debugLogFile());
      case OPEN_LOG_FOLDER -> help.openLogFolder();
      case OPEN_SETTINGS_FOLDER -> help.openSettingsFolder();
      case REPORT_ISSUE -> help.reportIssue();
      case ABOUT -> help.showAbout();
    }
  }

  /**
   * Reloads the selected document's file decoded with a charset; see {@link
   * DocumentController#reopenWithEncoding}.
   *
   * @param charset the charset
   */
  public void reopenWithEncoding(Charset charset) {
    if (!quitting) {
      documents.reopenWithEncoding(charset);
    }
  }

  /**
   * Sets the charset the selected document's next save writes; see {@link
   * DocumentController#saveWithEncoding}.
   *
   * @param charset the charset
   */
  public void saveWithEncoding(Charset charset) {
    if (!quitting) {
      documents.saveWithEncoding(charset);
    }
  }

  private void edit(CommandId command) {
    EditorInput editor = documents.activeInput();
    if (editor != null) {
      editor.execute(command);
    }
  }

  private void setFontStep(int step) {
    int bounded = Math.max(MIN_FONT_STEP, Math.min(MAX_FONT_STEP, step));
    if (bounded != fontStep) {
      fontStep = bounded;
      view.setFontStep(bounded);
    }
  }

  /**
   * Opens or closes the debug log file and saves the choice in {@code log.debugFile}.
   *
   * @param on true to open it
   */
  private void setDebugLogFile(boolean on) {
    if (preferences.debugLogFile() != on) {
      preferences = preferences.withDebugLogFile(on);
      save(capture());
    }
    help.setDebugLogFile(
        on,
        new Runnable() {
          @Override
          public void run() {
            view.documentStateChanged();
          }
        });
  }

  /** Closes the documents, asking about unsaved changes, then quits unless the user canceled. */
  public void requestQuit() {
    if (quitting) {
      return;
    }
    documents.closeAll(
        new Runnable() {
          @Override
          public void run() {
            quit();
          }
        });
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

  /** Reads the license notices from the jar on a worker, then shows them; About's Licenses. */
  public void showLicenses() {
    help.showLicenses();
  }

  /**
   * Quits without asking: closes the documents, records and saves the window placement when
   * remembered, hides the window, closes the workers and disposes the window. A second call does
   * nothing. Call on the UI thread.
   */
  public void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    help.close();
    documents.dispose();
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
