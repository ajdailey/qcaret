package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * {@code -n}. An open that resolves to a file another window holds selects that tab and raises the
 * window, so an instance holds one buffer per file. New Window opens a window through the {@link
 * Factory}. {@link #moveDocument} moves a tab with its live document.
 *
 * <p>Closing a window that is not the last asks about its unsaved documents and closes them.
 * Closing the last one, or Quit from any window, runs {@link QuitPrompts}: every dirty document of
 * every window is asked about before anything closes or saves. With {@code session.snapshot} on and
 * this instance owning the session, Quit writes the dirty buffers as a snapshot instead and asks
 * nothing; a failed write falls back to asking. {@link #quit} then asks nothing: capture the
 * session, queue the last recovery writes (a dirty buffer it finds stays in a recovery file for the
 * next start), stop every window and record the placement of the window activated last on the UI
 * thread, hide the windows, on a {@code qcaret-lifecycle-} worker run the quit listeners, save,
 * finish recovery, close {@link Workers} and run the finish listeners, and dispose the windows on
 * the UI thread, which ends the event loop.
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
  private final List<ApplicationController> windows = new ArrayList<ApplicationController>();
  private final List<ApplicationController> activations = new ArrayList<ApplicationController>();
  private final List<Path> recentPaths = new ArrayList<Path>();
  private final List<Runnable> quitListeners = new CopyOnWriteArrayList<Runnable>();
  private final List<Runnable> finishListeners = new CopyOnWriteArrayList<Runnable>();
  private final List<Runnable> moveListeners = new ArrayList<Runnable>();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicBoolean warningPosted = new AtomicBoolean();
  private Keymap keymap;
  private Factory factory;
  private RecentLog recent;
  private LogFiles logFiles = LogFiles.NONE;
  private long seenWarnings;
  private int nextUntitled = 1;
  private int nextBuffer = 1;
  private boolean snapshotting;
  private boolean snapshotWritten;
  private QuitPrompts prompts;
  private volatile boolean quitting;

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
    this.theme = new ThemeController(system, preferences.theme());
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
            options.recoveryKeepDays());
    this.session = new SessionController(this, recovery, workers, ui);
    moveListeners.add(session::changed);
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
   * @param done receives on the UI thread whether the session file holds no retained search
   */
  void forgetHistory(Consumer<Boolean> done) {
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

  private ApplicationController openWindow(WindowGeometry.Request at, boolean maximized) {
    if (quitting || factory == null) {
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
   * window holds shows there instead.
   *
   * @param request the files and their options, {@code -n}, the geometry and {@code --diff}
   */
  public void openHandoff(StartupRequest request) {
    ApplicationController window =
        request.newWindow()
            ? openWindow(
                new WindowGeometry.Request(
                    request.x(), request.y(), request.width(), request.height()),
                false)
            : target();
    if (quitting || window == null) {
      return;
    }
    window.toFront();
    window.open(request, request.newWindow());
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
   * <p>Nothing moves while quitting, while the tab's save, reload or conversion runs or its change
   * prompt shows (a notice says so), when the target holds the same file (a notice says so), or
   * when no new window could be created.
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
    if (quitting
        || target == source
        || !windows.contains(source)
        || !source.documents().isOpen(document)
        || (target != null && !windows.contains(target))) {
      return null;
    }
    String name = source.documents().nameOf(document);
    if (!source.documents().canMove(document)) {
      source.showNotice(UiText.NOTICE_MOVE_BUSY.format(name));
      return null;
    }
    Path path = source.documents().pathOf(document);
    if (target != null && path != null && target.documents().documentAt(path) != null) {
      source.showNotice(UiText.NOTICE_MOVE_SHOWN.format(name));
      return null;
    }
    ApplicationController destination = target;
    if (destination == null) {
      destination =
          openWindow(new WindowGeometry.Request(placement.x(), placement.y(), null, null), false);
      if (destination == null) {
        return null;
      }
    }
    source.documents().moveTo(document, destination.documents(), placement.index());
    if (target != null) {
      activated(target);
      target.toFront();
    }
    for (Runnable listener : new ArrayList<Runnable>(moveListeners)) {
      listener.run();
    }
    return destination;
  }

  /**
   * Adds code to run once after each tab move, such as a session save.
   *
   * @param listener runs on the UI thread
   */
  public void addMoveListener(Runnable listener) {
    moveListeners.add(listener);
  }

  // Window close and quit.

  /**
   * Reports whether {@link #quit} has begun; callable from any thread.
   *
   * @return true once quitting
   */
  public boolean isQuitting() {
    return quitting;
  }

  /**
   * Adds code to run first when quit reaches its lifecycle worker, before the save, such as closing
   * a listener whose thread {@link Workers#close} would otherwise wait for. Runs on the UI thread
   * when the workers were closed already.
   *
   * @param listener the code; must not block for long
   */
  public void addQuitListener(Runnable listener) {
    quitListeners.add(listener);
  }

  private void runQuitListeners() {
    for (Runnable listener : quitListeners) {
      listener.run();
    }
  }

  /**
   * Adds code to run once quit has saved and closed {@link Workers}, before the windows are
   * disposed. Runs on the lifecycle worker, or on the UI thread when the workers were closed
   * already; at once when quit has finished.
   *
   * @param listener the code; must not block
   */
  public void addFinishListener(Runnable listener) {
    finishListeners.add(listener);
    if (finished.get() && finishListeners.remove(listener)) {
      listener.run();
    }
  }

  private void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    for (Runnable listener : finishListeners) {
      if (finishListeners.remove(listener)) {
        listener.run();
      }
    }
  }

  /**
   * Closes one window, asking about its unsaved documents; a Cancel keeps it open. The last open
   * window quits instead. Nothing closes while the session restores: the restore holds the window.
   *
   * @param window the window
   */
  void requestClose(final ApplicationController window) {
    if (quitting || prompts != null || !windows.contains(window)) {
      return;
    }
    if (windows.size() == 1) {
      requestQuit();
      return;
    }
    if (session.restoring()) {
      return;
    }
    window
        .documents()
        .closeAll(
            new Runnable() {
              @Override
              public void run() {
                close(window);
              }
            });
  }

  private void close(final ApplicationController window) {
    if (quitting || !windows.contains(window)) {
      return;
    }
    if (windows.size() == 1) {
      requestQuit();
      return;
    }
    windows.remove(window);
    activations.remove(window);
    window.rememberWindow();
    window.stop();
    window.hide();
    session.changed();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            window.disposeView();
          }
        });
  }

  /**
   * Asks about the unsaved changes of every window, then closes the documents and quits unless the
   * user canceled or a save failed; with {@code session.snapshot} on, writes them as a snapshot and
   * quits. A request while a quit asks does nothing.
   */
  public void requestQuit() {
    if (quitting || snapshotting || prompts != null) {
      return;
    }
    if (session.owner() && settings.current().options().sessionSnapshot()) {
      writeSnapshot();
    } else {
      closeAndQuit();
    }
  }

  private void closeAndQuit() {
    if (quitting) {
      return;
    }
    prompts =
        new QuitPrompts(
            this,
            session,
            new Runnable() {
              @Override
              public void run() {
                prompts = null;
              }
            });
    prompts.start();
  }

  /** Writes the dirty buffers, again when one changed meanwhile, then quits. */
  private void writeSnapshot() {
    snapshotting = true;
    final List<DocumentController.Buffer> buffers = dirtyBuffers();
    recovery.writeSnapshot(
        buffers,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean ok) {
            snapshotWritten(buffers, ok);
          }
        });
  }

  private void snapshotWritten(List<DocumentController.Buffer> buffers, boolean ok) {
    snapshotting = false;
    if (quitting) {
      return;
    }
    if (!ok) {
      ApplicationController window = target();
      if (window != null) {
        window.showNotice(UiText.SESSION_SNAPSHOT_FAILED.text());
      }
      closeAndQuit();
    } else if (unchanged(buffers, dirtyBuffers())) {
      snapshotWritten = true;
      quit();
    } else {
      writeSnapshot();
    }
  }

  private static boolean unchanged(
      List<DocumentController.Buffer> written, List<DocumentController.Buffer> now) {
    if (written.size() != now.size()) {
      return false;
    }
    for (int i = 0; i < now.size(); i++) {
      if (written.get(i).snapshot().revision() != now.get(i).snapshot().revision()) {
        return false;
      }
    }
    return true;
  }

  /** Starts quitting every window without asking; a second call does nothing. */
  public void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    if (recent != null) {
      recent.setWarningListener(null);
    }
    ApplicationController last = target();
    final SessionController.Capture capture = session.close(snapshotWritten);
    recovery.quitting(snapshotWritten ? List.of() : dirtyBuffers());
    final List<ApplicationController> closing = new ArrayList<ApplicationController>(windows);
    for (ApplicationController window : closing) {
      window.stop();
    }
    if (last != null) {
      last.rememberWindow();
    }
    windows.clear();
    activations.clear();
    for (ApplicationController window : closing) {
      window.hide();
    }
    Runnable end =
        new Runnable() {
          @Override
          public void run() {
            runQuitListeners();
            settings.flush();
            if (capture != null) {
              session.write(capture);
            }
            recovery.finish();
            if (!workers.close(Workers.CLOSE_BOUND)) {
              LOG.warn(
                  "Worker threads still running after {}: {}",
                  Workers.CLOSE_BOUND,
                  LogText.oneLine(workers.liveThreadNames()));
            }
            finish();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    disposeViews(closing);
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, end)) {
      runQuitListeners();
      finish();
      disposeViews(closing);
    }
  }

  private static void disposeViews(List<ApplicationController> closing) {
    for (ApplicationController window : closing) {
      window.disposeView();
    }
  }

  /** Reports whether a window records its placement when it closes. */
  boolean rememberWindow() {
    return rememberWindow;
  }
}
