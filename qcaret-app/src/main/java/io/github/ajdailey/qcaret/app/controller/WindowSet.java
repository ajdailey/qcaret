package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionRewrite;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.FontFiles;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.AppearanceChoices;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main windows of one instance and what they share: {@link Settings}, the theme, the key
 * bindings, the grammars, Recent Files, the memory budget, the recent log lines, the debug log
 * file, the session and the recovery files. Confined to the UI thread, except {@link #isQuitting}
 * and {@link #addFinishListener}.
 *
 * <p>Handed launches ({@link #openHandoff}) go to the window activated last, or a new one with
 * {@code -n}. Those that arrive while the session restores wait and open in arrival order after the
 * restore and the command line's files; a quit first drops them with a WARN. An open that resolves
 * to a file another window holds selects that tab and raises the window, so an instance holds one
 * buffer per file. New Window opens a window through the {@link Factory}. {@link #moveDocument}
 * moves a tab with its live document, through {@link TabTransfer}.
 *
 * <p>Closing a window that is not the last asks about its unsaved documents and closes them.
 * Closing the last one, or Quit from any window, runs {@link QuitPrompts}: every dirty document of
 * every window is asked about before anything closes or saves. With {@code session.snapshot} on and
 * this instance owning the session, Quit writes the dirty buffers as a snapshot instead and asks
 * nothing; a failed write falls back to asking. {@link #quit} then asks nothing: capture the
 * session, queue the last recovery writes (a dirty buffer it finds stays in a recovery file for the
 * next start), stop every window and record the placement of the window activated last on the UI
 * thread, hide the windows, on a {@code qcaret-lifecycle-} worker save, finish recovery, run the
 * quit listeners, close {@link Workers} and run the finish listeners, and dispose the windows on
 * the UI thread, which ends the event loop. {@link QuitSequence} runs window close and quit.
 */
public final class WindowSet {
  /** Creates and opens a main window. */
  public interface Factory {
    /**
     * Creates a window over the set, places and opens it, with no document; called on the UI
     * thread.
     *
     * @param set the set it joins
     * @param at the requested geometry; a null field leaves it to the remembered size or the window
     *     system
     * @param maximized true to open maximized over those normal bounds
     * @return the window's controller, or null when it could not be created
     */
    ApplicationController create(WindowSet set, WindowGeometry.Request at, boolean maximized);
  }

  /**
   * Where a moved tab goes.
   *
   * @param index its tab position in an existing window, 0 first; clamped to the tabs
   * @param x a new window's left edge in display coordinates, or null for the window system
   * @param y a new window's top edge in display coordinates, or null for the window system
   */
  public record Placement(int index, Integer x, Integer y) {}

  private static final Logger LOG = LoggerFactory.getLogger(WindowSet.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final ConfigStore config;
  private final MemoryBudget budget;
  private final Settings settings;
  private final ThemeController theme;
  private final GrammarLibrary grammars;
  private final Keymap defaultKeymap;
  private final boolean rememberWindow;
  private final RecoveryController recovery;
  private final SessionController session;
  private final BrowserWindows browsers = new BrowserWindows();
  private final RunningCopies copies;
  private final RunningWrites writes = new RunningWrites();
  private final QuitSequence quitSequence;
  private final TabTransfer transfer;
  private final List<ApplicationController> windows = new ArrayList<ApplicationController>();
  private final List<ApplicationController> activations = new ArrayList<ApplicationController>();
  private final List<Path> recentPaths = new ArrayList<Path>();
  private final List<StartupRequest> handed = new ArrayList<StartupRequest>();
  private final AtomicBoolean warningPosted = new AtomicBoolean();
  private Keymap keymap;
  private Factory factory;
  private RecentLog recent;
  private LogFiles logFiles = LogFiles.NONE;
  private long seenWarnings;
  private long themeGeneration;
  private int nextUntitled = 1;
  private int nextBuffer = 1;

  private final DocumentController.Elsewhere elsewhere =
      new DocumentController.Elsewhere() {
        @Override
        public boolean show(DocumentController asking, Path path, Consumer<EditorController> then) {
          for (ApplicationController window : windows) {
            if (window.documents() != asking && window.documents().showOpen(path, then)) {
              window.toFront();
              return true;
            }
          }
          return false;
        }

        @Override
        public DocumentController holderOf(Document document) {
          for (ApplicationController window : windows) {
            if (window.documents().isOpen(document)) {
              return window.documents();
            }
          }
          return null;
        }

        @Override
        public Document documentAt(Path path) {
          for (ApplicationController window : windows) {
            Document document = window.documents().documentAt(path);
            if (document != null) {
              return document;
            }
          }
          return null;
        }

        @Override
        public List<Document> documents(DocumentController asking) {
          List<Document> list = new ArrayList<Document>(asking.openDocuments());
          for (ApplicationController window : windows) {
            if (window.documents() != asking) {
              list.addAll(window.documents().openDocuments());
            }
          }
          return list;
        }

        @Override
        public void raise(DocumentController holder) {
          for (ApplicationController window : windows) {
            if (window.documents() == holder) {
              window.toFront();
              activated(window);
            }
          }
        }

        @Override
        public void closed(DocumentController holder, Document document) {
          for (ApplicationController window : windows) {
            if (window.documents() != holder) {
              window.search().documentClosed(document);
            }
          }
        }

        @Override
        public int nextUntitled() {
          return nextUntitled++;
        }

        @Override
        public int nextBuffer() {
          return nextBuffer++;
        }

        @Override
        public RunningCopies copies() {
          return copies;
        }

        @Override
        public RunningWrites writes() {
          return writes;
        }
      };

  /**
   * Creates the set with no window.
   *
   * @param workers the thread owner; {@link #quit} closes it
   * @param ui the route onto the UI thread
   * @param config the settings store
   * @param preferences the settings loaded at startup
   * @param system the system's light or dark setting
   * @param budget the documents' heap share
   * @param keymap the default key bindings; the settings' overrides apply on top
   * @param rememberWindow false when the quit saves no window placement
   */
  public WindowSet(
      Workers workers,
      UiDispatcher ui,
      ConfigStore config,
      Preferences preferences,
      ThemeService system,
      MemoryBudget budget,
      Keymap keymap,
      boolean rememberWindow) {
    this.workers = workers;
    this.ui = ui;
    this.config = config;
    this.budget = budget;
    this.rememberWindow = rememberWindow;
    this.settings = new Settings(config, workers, ui, preferences);
    this.theme =
        new ThemeController(system, preferences.theme(), preferences.appearance().editorColors());
    this.defaultKeymap = keymap;
    this.keymap = keymap.withOverrides(preferences.shortcuts());
    this.grammars =
        new GrammarLibrary(
            workers,
            ui,
            new Consumer<String>() {
              @Override
              public void accept(String notice) {
                ApplicationController window = target();
                if (window != null) {
                  window.showNotice(notice);
                }
              }
            },
            config.location().directory().resolve("grammars"));
    Options options = preferences.options();
    this.recovery =
        new RecoveryController(
            this,
            workers,
            ui,
            budget,
            config.location().directory(),
            options.recoveryIntervalSeconds(),
            options.recoveryKeepDays(),
            Clock.systemDefaultZone());
    this.session = new SessionController(this, recovery, workers, ui);
    this.copies = new RunningCopies(this, ui);
    this.quitSequence = new QuitSequence(this, session, recovery, copies, settings, workers, ui);
    this.transfer = new TabTransfer(this);
    transfer.addMoveListener(session::changed);
    settings.addListener(
        new Settings.Listener() {
          @Override
          public void changed(Preferences before, Preferences after) {
            apply(before, after);
          }
        });
    logConflicts();
  }

  /** Brings what the windows share in line first; each window's listener runs after this one. */
  private void apply(Preferences before, Preferences after) {
    Options old = before.options();
    Options now = after.options();
    if (now.recoveryIntervalSeconds() != old.recoveryIntervalSeconds()) {
      recovery.setIntervalSeconds(now.recoveryIntervalSeconds());
    }
    if (now.recoveryKeepDays() != old.recoveryKeepDays()) {
      recovery.setKeepDays(now.recoveryKeepDays());
    }
    if (!after.shortcuts().equals(before.shortcuts())) {
      keymap = defaultKeymap.withOverrides(after.shortcuts());
      logConflicts();
    }
    if (after.theme() != before.theme() && !theme.setMode(after.theme())) {
      ApplicationController window = target();
      if (window != null) {
        window.showThemeRestart();
      }
    }
    if (!after.appearance().themeFile().equals(before.appearance().themeFile())) {
      loadTheme();
    }
    if (!after.appearance().editorColors().equals(before.appearance().editorColors())) {
      theme.setEditorColors(after.appearance().editorColors());
    }
  }

  /**
   * Reads the chosen theme file on a worker and pushes its colors; a newer read drops this one. No
   * file clears the colors at once, so a scheme chosen in its place never shows them.
   */
  void loadTheme() {
    final long started = ++themeGeneration;
    final String name = settings.current().appearance().themeFile();
    if (name.isEmpty()) {
      theme.setColors(ThemeColors.NONE);
      return;
    }
    final Path directory = config.location().directory();
    boolean running =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final ThemeColors colors = ThemeColors.read(directory, name);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (started == themeGeneration) {
                          theme.setColors(colors);
                        }
                      }
                    });
              }
            });
    if (!running) {
      LOG.debug("Theme file not read: workers are closed");
    }
  }

  /** Lists the theme files and the monospace font families on a worker. */
  void loadChoices(final Consumer<AppearanceChoices> done) {
    final Path directory = config.location().directory();
    boolean running =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final AppearanceChoices choices =
                    new AppearanceChoices(
                        ThemeColors.list(directory), FontFiles.installedFamilies());
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        done.accept(choices);
                      }
                    });
              }
            });
    if (!running) {
      LOG.debug("Appearance choices not listed: workers are closed");
    }
  }

  private void logConflicts() {
    for (String conflict : keymap.conflicts()) {
      LOG.warn("Key conflict: {}", LogText.oneLine(conflict));
    }
  }

  /**
   * Sets how New Window creates a window; without one New Window does nothing.
   *
   * @param value the factory
   */
  public void setFactory(Factory value) {
    factory = value;
  }

  /**
   * Connects Help > Show Log and the warning marker of every window to the recent log lines, and
   * Help > Debug Log File to the log file. A warning logged on any thread shows the marker through
   * one posted task at a time.
   *
   * @param lines the recent lines
   * @param files the debug log file
   */
  public void setLogging(RecentLog lines, LogFiles files) {
    recent = lines;
    logFiles = files;
    seenWarnings = lines.warnings();
    lines.setWarningListener(
        new Runnable() {
          @Override
          public void run() {
            if (warningPosted.compareAndSet(false, true)) {
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      warningPosted.set(false);
                      showWarningMarkers();
                    }
                  });
            }
          }
        });
    for (ApplicationController window : windows) {
      window.logging(lines, files);
    }
  }

  private void showWarningMarkers() {
    boolean shown = newWarnings();
    for (ApplicationController window : windows) {
      window.showWarningMarker(shown);
    }
  }

  /** Reports whether a warning arrived after Show Log last opened. */
  boolean newWarnings() {
    return recent != null && recent.warnings() > seenWarnings;
  }

  /** Records that Show Log shows every warning so far and clears each window's marker. */
  void warningsSeen() {
    if (recent != null) {
      seenWarnings = recent.warnings();
    }
    showWarningMarkers();
  }

  /** Returns the recent log lines, or null before {@link #setLogging}. */
  RecentLog recent() {
    return recent;
  }

  /** Returns the debug log file. */
  LogFiles logFiles() {
    return logFiles;
  }

  /** Adds a window; its controller's constructor calls it. */
  void register(ApplicationController window) {
    windows.add(window);
    activations.add(window);
    window.documents().setElsewhere(elsewhere);
    session.attach(window.documents());
    recovery.attach(window.documents());
  }

  // Shared state.

  Workers workers() {
    return workers;
  }

  UiDispatcher ui() {
    return ui;
  }

  ConfigStore config() {
    return config;
  }

  MemoryBudget budget() {
    return budget;
  }

  List<Path> recentPaths() {
    return recentPaths;
  }

  /**
   * Returns the running settings.
   *
   * @return the one copy every window reads
   */
  public Settings settings() {
    return settings;
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
   * Returns the grammars.
   *
   * @return the library
   */
  public GrammarLibrary grammars() {
    return grammars;
  }

  /**
   * Returns the key bindings in effect.
   *
   * @return the defaults with the user's overrides
   */
  public Keymap keymap() {
    return keymap;
  }

  Keymap defaultKeymap() {
    return defaultKeymap;
  }

  RecoveryController recovery() {
    return recovery;
  }

  SessionController session() {
    return session;
  }

  BrowserWindows browsers() {
    return browsers;
  }

  // Session and recovery.

  /**
   * Takes the session read at startup and starts recovery files. With {@code restore} true every
   * saved window opens and this instance saves the session from then on, opening an untitled
   * document in a further window that ends without a tab; with false only the Recent Files and the
   * per-file state apply.
   *
   * @param first the window open now
   * @param store the store the session was read from
   * @param loaded the session
   * @param restore true to restore and own the session
   * @param then runs on the UI thread after the restore
   */
  void startSession(
      ApplicationController first,
      SessionStore store,
      Session loaded,
      boolean restore,
      Runnable then) {
    session.start(first, store, loaded, restore, then);
  }

  /** Returns every window's buffers with unsaved changes, each with a snapshot taken now. */
  List<DocumentController.Buffer> dirtyBuffers() {
    List<DocumentController.Buffer> buffers = new ArrayList<DocumentController.Buffer>();
    for (ApplicationController window : windows) {
      buffers.addAll(window.documents().dirtyBuffers());
    }
    return buffers;
  }

  /** Returns the documents of the window holding a file, or null. */
  DocumentController holderAt(Path path) {
    for (ApplicationController window : windows) {
      if (window.documents().documentAt(path) != null) {
        return window.documents();
      }
    }
    return null;
  }

  /**
   * Drops HyperSearch's last run and retained searches in every window and removes the searches
   * from the session file now; Clear History.
   *
   * @param done receives the session file's result on the UI thread
   */
  void forgetHistory(Consumer<SessionRewrite.Result> done) {
    for (ApplicationController window : windows) {
      window.search().forgetHistory();
    }
    session.forgetSearches(done);
  }

  /** Opens a saved window at its bounds; null when it cannot be created. */
  ApplicationController openRestored(WindowState bounds) {
    return openWindow(
        new WindowGeometry.Request(bounds.x(), bounds.y(), bounds.width(), bounds.height()),
        bounds.maximized());
  }

  // Windows.

  /**
   * Returns the open windows.
   *
   * @return in creation order; closed windows are left out
   */
  public List<ApplicationController> windows() {
    return Collections.unmodifiableList(windows);
  }

  /** Records the window the user activated last; handed files go to it. */
  void activated(ApplicationController window) {
    if (windows.contains(window)) {
      activations.remove(window);
      activations.add(window);
    }
  }

  /** Returns the open windows, activated last at the end. */
  List<ApplicationController> activationOrder() {
    return new ArrayList<ApplicationController>(activations);
  }

  /**
   * Returns the window handed files go to.
   *
   * @return the window activated last, or null when none is open
   */
  public ApplicationController target() {
    return activations.isEmpty() ? null : activations.get(activations.size() - 1);
  }

  /** Opens an untitled document in a new window; File > New Window. */
  public void newWindow() {
    ApplicationController window =
        openWindow(new WindowGeometry.Request(null, null, null, null), false);
    if (window != null) {
      window.documents().newDocument();
    }
  }

  /** Opens a window through the factory; null while quitting or when none was created. */
  ApplicationController openWindow(WindowGeometry.Request at, boolean maximized) {
    if (isQuitting() || factory == null) {
      return null;
    }
    ApplicationController window = factory.create(this, at, maximized);
    if (window == null) {
      LOG.warn("New window not created");
    } else {
      activated(window);
      session.changed();
    }
    return window;
  }

  /**
   * Opens a launch another process handed over and brings its window to the front. A file another
   * window holds shows there instead. While the session restores the launch waits for {@link
   * #openHandedLaunches}: its tabs would otherwise land among the saved tabs.
   *
   * @param request the files and their options, {@code -n}, the geometry and {@code --diff}
   */
  public void openHandoff(StartupRequest request) {
    if (isQuitting()) {
      logDropped(request);
      return;
    }
    if (session.restoring()) {
      handed.add(request);
      return;
    }
    ApplicationController window =
        request.newWindow()
            ? openWindow(
                new WindowGeometry.Request(
                    request.x(), request.y(), request.width(), request.height()),
                false)
            : target();
    if (window == null) {
      return;
    }
    window.toFront();
    window.open(request, request.newWindow());
  }

  /** Opens the launches handed during the restore, in arrival order; the restore has ended. */
  void openHandedLaunches() {
    List<StartupRequest> waiting = new ArrayList<StartupRequest>(handed);
    handed.clear();
    for (StartupRequest request : waiting) {
      openHandoff(request);
    }
  }

  private static void logDropped(StartupRequest request) {
    LOG.warn("Handed launch of {} files not opened: quitting", request.files().size());
  }

  /**
   * Opens a file the system asked to open, as a handoff does; macOS Open With and a Dock drop.
   *
   * @param path the file
   */
  public void openFile(Path path) {
    openHandoff(
        StartupRequest.handoff(
            List.of(new FileRequest(path)), false, null, null, null, null, null, null));
  }

  // Tab move between windows.

  /**
   * Moves a tab to another window or a new one with its live document; nothing is read again. The
   * source window shows its next tab, or stays open and empty. The target window shows the tab and
   * comes to the front. Call on the UI thread.
   *
   * <p>Nothing moves while quitting, while the tab's save, copy, reload or conversion runs or its
   * change prompt shows (a notice says so), when the target holds the same file (a notice says so),
   * or when no new window could be created.
   *
   * @param source the window the tab is in
   * @param document the tab's document
   * @param target the window it goes to, or null for a new window
   * @param placement the tab position in {@code target}, or the new window's corner
   * @return the window it moved to, or null when it did not move
   */
  public ApplicationController moveDocument(
      ApplicationController source,
      Document document,
      ApplicationController target,
      Placement placement) {
    return transfer.moveDocument(source, document, target, placement);
  }

  /**
   * Adds code to run once after each tab move, such as a session save.
   *
   * @param listener runs on the UI thread
   */
  public void addMoveListener(Runnable listener) {
    transfer.addMoveListener(listener);
  }

  // Window close and quit.

  /**
   * Reports whether {@link #quit} has begun; callable from any thread.
   *
   * @return true once quitting
   */
  public boolean isQuitting() {
    return quitSequence.isQuitting();
  }

  /**
   * Reports whether a quit is asking, writing its snapshot or has begun.
   *
   * @return true from the quit request on, until a Cancel or a failed save ends it
   */
  boolean quitRunning() {
    return quitSequence.quitRunning();
  }

  /**
   * Adds code to run after quit has saved and before it closes {@link Workers}, such as closing a
   * listener whose thread {@link Workers#close} would otherwise wait for and whose lock a new
   * primary must not take before the session file is written. Runs on the UI thread when the
   * workers were closed already.
   *
   * @param listener the code; must not block for long
   */
  public void addQuitListener(Runnable listener) {
    quitSequence.addQuitListener(listener);
  }

  /**
   * Adds code to run once quit has saved and closed {@link Workers}, before the windows are
   * disposed. Runs on the lifecycle worker, or on the UI thread when the workers were closed
   * already; at once when quit has finished.
   *
   * @param listener the code; must not block
   */
  public void addFinishListener(Runnable listener) {
    quitSequence.addFinishListener(listener);
  }

  /**
   * Closes one window, asking about its unsaved documents; a Cancel keeps it open. The last open
   * window quits instead. Nothing closes while the session restores (the restore holds the window)
   * or while a quit asks, writes its snapshot or runs.
   *
   * @param window the window
   */
  void requestClose(ApplicationController window) {
    quitSequence.requestClose(window);
  }

  /**
   * Asks about the unsaved changes of every window, then closes the documents and quits unless the
   * user canceled or a save failed; with {@code session.snapshot} on, writes them as a snapshot and
   * quits. A snapshot quit during a restore waits for {@link #restoreEnded}: writing one drops the
   * snapshots of buffers not restored yet. A request while a quit asks does nothing.
   */
  public void requestQuit() {
    quitSequence.requestQuit();
  }

  /** Runs a Quit requested during the restore, which has ended. */
  void restoreEnded() {
    quitSequence.restoreEnded();
  }

  /** Starts the quit that asks about every dirty document. */
  void closeAndQuit() {
    quitSequence.closeAndQuit();
  }

  /** Returns the Save a Copy As writes running in every window, which a quit waits for. */
  RunningCopies copies() {
    return copies;
  }

  /** Starts quitting every window without asking; a second call does nothing. */
  public void quit() {
    quitSequence.quit();
  }

  /**
   * Drops the launches waiting for the restore with a WARN and stops the warning marker; the quit
   * has begun.
   */
  void quitStarted() {
    for (StartupRequest request : handed) {
      logDropped(request);
    }
    handed.clear();
    if (recent != null) {
      recent.setWarningListener(null);
    }
  }

  /** Takes a closing window out of the open windows. */
  void remove(ApplicationController window) {
    windows.remove(window);
    activations.remove(window);
  }

  /** Takes every window out of the open windows; the quit has stopped them. */
  void removeAll() {
    windows.clear();
    activations.clear();
  }

  /** Reports whether a window records its placement when it closes. */
  boolean rememberWindow() {
    return rememberWindow;
  }
}
