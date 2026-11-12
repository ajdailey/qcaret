package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionLock;
import io.github.ajdailey.qcaret.app.session.SessionRewrite;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.session.SessionText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores the session of every window and saves it; one per {@link WindowSet}. {@link #start}
 * takes the loaded file. With {@code restore} true this instance owns the session: the first saved
 * window restores into the window already open, each further one into a new window at its saved
 * bounds, and the tabs open one at a time in their saved order, which keeps the tab order whatever
 * the load times. From then on the instance saves. An instance that did not restore never saves, so
 * {@code -m} cannot replace the session.
 *
 * <p>A change arms one timer of {@value #DELAY_MILLIS} ms; further changes before it fires share
 * it, so a burst of edits cannot put the save off for good. The timer captures the state on the UI
 * thread and numbers the capture; a {@code qcaret-lifecycle-} worker writes it under {@link
 * #saveLock}, skipping a capture a later one has written, so the debounced save and the shutdown
 * save cannot land out of order. An unchanged session writes nothing.
 *
 * <p>A quit that asks saves nothing from {@link #quitStarted} on, and captures the windows after
 * the last answer and before the first close ({@link #quitAnswered}), so the last save lists every
 * tab and a file an untitled document was saved to at a prompt. A Cancel ({@link #quitCanceled})
 * returns to the live state.
 *
 * <p>Only the holder of {@link SessionLock} writes the file. The launcher takes it for an instance
 * that restores; any other instance takes it for the one rewrite of {@link #forgetSearches}.
 *
 * <p>The per-file LRU holds {@value SessionStore#MAX_FILES} entries keyed by the opened document's
 * real path. Confined to the UI thread apart from {@link #write}.
 */
final class SessionController implements DocumentController.FileMemory {
  /** Milliseconds from a change to the save it arms. */
  static final int DELAY_MILLIS = 2000;

  /** The most file names one notice lists. */
  private static final int NOTICE_NAMES = 5;

  private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);

  /** The per-file state, least recently used first. */
  private static final class FileLru extends LinkedHashMap<String, Session.FileState> {
    private static final long serialVersionUID = 1L;

    FileLru() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Session.FileState> eldest) {
      return size() > SessionStore.MAX_FILES;
    }
  }

  /** One numbered capture. */
  record Capture(long sequence, Session session) {}

  /** A window the restore opened tabs in, and the tab to select at the end. */
  private record Restored(ApplicationController window, int active) {}

  private final WindowSet set;
  private final RecoveryController recovery;
  private final Workers workers;
  private final UiDispatcher ui;
  private final FileLru files = new FileLru();
  private final List<String> skipped = new ArrayList<String>();
  private final List<Restored> restored = new ArrayList<Restored>();
  private final List<WindowState> placements = new ArrayList<WindowState>();
  private final Runnable changes =
      new Runnable() {
        @Override
        public void run() {
          changed();
        }
      };
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          armed = false;
          save();
        }
      };

  /** Orders the writes of different workers; the UI thread never takes it. */
  private final ReentrantLock saveLock = new ReentrantLock();

  /** The newest capture written; guarded by {@link #saveLock}. */
  private long written;

  /** The session the file holds; guarded by {@link #saveLock}. */
  private Session lastWritten;

  /** True when the newest capture did not reach the file; guarded by {@link #saveLock}. */
  private boolean writeFailed;

  private SessionStore store;
  private boolean owner;
  private boolean restoring;
  private boolean armed;
  private boolean closed;
  private long captured;
  private boolean held;
  private boolean searchesForgotten;
  private List<Session.Window> quitWindows;
  private ApplicationController first;
  private Runnable after =
      new Runnable() {
        @Override
        public void run() {
          LOG.debug("Session started with nothing to open after it");
        }
      };

  SessionController(WindowSet set, RecoveryController recovery, Workers workers, UiDispatcher ui) {
    this.set = set;
    this.recovery = recovery;
    this.workers = workers;
    this.ui = ui;
  }

  /** Follows a new window's documents. */
  void attach(DocumentController documents) {
    documents.setFileMemory(this);
    documents.setSessionListener(changes);
  }

  /**
   * Takes the loaded session.
   *
   * @param window the window open now
   * @param sessions the store the session was loaded from
   * @param loaded the session
   * @param restore true to open its windows and tabs and own the file from then on
   * @param then runs on the UI thread at the end; it gives the window open now its first document
   */
  void start(
      ApplicationController window,
      SessionStore sessions,
      Session loaded,
      boolean restore,
      Runnable then) {
    first = window;
    after = then;
    store = sessions;
    lastWritten = loaded;
    recovery.open();
    List<Session.FileState> states = loaded.files();
    for (int i = states.size() - 1; i >= 0; i--) {
      files.put(states.get(i).path(), states.get(i));
    }
    if (!restore) {
      restoreRecent(loaded);
      recovery.start();
      after.run();
      return;
    }
    owner = true;
    restoring = true;
    for (Session.Window saved : loaded.windows()) {
      placements.add(saved.bounds());
    }
    restoreWindow(loaded, 0);
  }

  private void restoreRecent(Session loaded) {
    List<Path> recent = paths(loaded.recent());
    for (ApplicationController window : set.windows()) {
      window.recentFiles().restore(recent);
    }
  }

  /**
   * Restores saved window {@code index}, then the next, then ends the restore. A window that cannot
   * be created gives its tabs to the first window.
   */
  private void restoreWindow(Session loaded, int index) {
    if (closed) {
      return;
    }
    if (index >= loaded.windows().size()) {
      finish(loaded);
      return;
    }
    Session.Window saved = loaded.windows().get(index);
    ApplicationController window = index == 0 ? first : set.openRestored(saved.bounds());
    if (window == null) {
      LOG.warn("A session window was not created; its tabs open in the first window");
      restoreTab(loaded, index, first, 0);
      return;
    }
    restored.add(new Restored(window, saved.active()));
    WorkspaceBrowse browse = window.workspace().browse();
    browse.restore(saved.browse().filter(), saved.browse().hidden());
    browse.restoreFolders(
        saved.workspace(), saved.browse().folder(), saved.browser(), paths(saved.expanded()));
    setWeights(window.view(), saved);
    window.search().restore(searchesForgotten ? List.of() : saved.searches(), saved.results());
    window.documents().restoreClosed(saved.closed());
    window.documents().restoreDisplay(saved.display());
    window.bookmarkList().restore(saved.display().bookmarks());
    window.alwaysOnTop().restore(saved.onTop());
    restoreTab(loaded, index, window, 0);
  }

  private static void setWeights(MainView view, Session.Window window) {
    int[] side = SessionText.numbers(window.sideWeights());
    if (validWeights(side)) {
      view.setSideWeights(side);
    }
    int[] docks = SessionText.numbers(window.dockWeights());
    if (validWeights(docks)) {
      view.setDockWeights(docks);
    }
    int[] results = SessionText.numbers(window.resultsWeights());
    if (validWeights(results)) {
      view.setResultsWeights(results);
    }
  }

  private static boolean validWeights(int[] weights) {
    return weights.length == 2 && weights[0] > 0 && weights[1] > 0;
  }

  private static List<Path> paths(List<String> names) {
    List<Path> paths = new ArrayList<Path>();
    for (String name : names) {
      try {
        paths.add(Path.of(name));
      } catch (InvalidPathException e) {
        LOG.debug("A session path does not parse");
      }
    }
    return paths;
  }

  /** Opens a saved window's tab {@code index}, then the next, then moves to the next window. */
  private void restoreTab(
      final Session loaded,
      final int windowIndex,
      final ApplicationController window,
      final int index) {
    if (closed) {
      return;
    }
    List<Session.Tab> tabs = loaded.windows().get(windowIndex).tabs();
    if (index >= tabs.size()) {
      restoreWindow(loaded, windowIndex + 1);
      return;
    }
    final Session.Tab tab = tabs.get(index);
    final DocumentController documents = window.documents();
    final Runnable next =
        new Runnable() {
          @Override
          public void run() {
            restoreTab(loaded, windowIndex, window, index + 1);
          }
        };
    final Consumer<String> failed =
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            skipped.add(name);
          }
        };
    if (tab.buffer().isEmpty()) {
      documents.restore(tab, failed, next);
      return;
    }
    recovery.restoreSnapshot(
        tab,
        documents,
        new Consumer<String>() {
          @Override
          public void accept(String problem) {
            LOG.warn("A snapshot buffer was not restored: {}", LogText.oneLine(problem));
            window.showNotice(problem);
            if (tab.path().isEmpty()) {
              skipped.add(tab.title());
            }
          }
        },
        new Runnable() {
          @Override
          public void run() {
            if (!tab.path().isEmpty() && documentAt(tab.path()) == null) {
              documents.restore(tab, failed, next);
            } else {
              next.run();
            }
          }
        });
  }

  /** Returns the document of a tab's file in any window; a recovered one may sit in another. */
  private Document documentAt(String path) {
    DocumentController holder = set.holderAt(Path.of(path));
    return holder == null ? null : holder.documentAt(Path.of(path));
  }

  private void finish(Session loaded) {
    restoring = false;
    for (Restored window : restored) {
      if (window.active() >= 0) {
        DocumentController documents = window.window().documents();
        documents.select(Math.min(window.active(), documents.count() - 1));
      }
    }
    for (ApplicationController window : set.windows()) {
      if (window != first && window.documents().count() == 0) {
        window.documents().newDocument();
      }
    }
    after.run();
    restoreRecent(loaded);
    set.openHandedLaunches();
    if (!skipped.isEmpty()) {
      reportSkipped();
    }
    recovery.start();
    reportDroppedComments(loaded.droppedComments());
    changed();
    set.restoreEnded();
  }

  /** Shows the count once; the save {@link #finish} arms clears the key. */
  private void reportDroppedComments(int dropped) {
    ApplicationController window = set.target();
    if (dropped > 0 && window != null) {
      LOG.warn("The last save left out {} bookmark comments", dropped);
      window.showNotice(UiText.SESSION_COMMENTS_DROPPED.format(dropped, SessionStore.MAX_COMMENTS));
    }
  }

  private void reportSkipped() {
    int shown = Math.min(NOTICE_NAMES, skipped.size());
    String names = String.join(", ", skipped.subList(0, shown));
    if (shown < skipped.size()) {
      names = UiText.SESSION_SKIPPED_MORE.format(names, skipped.size() - shown);
    }
    LOG.warn("{} session files were not opened", skipped.size());
    ApplicationController window = set.target();
    if (window != null) {
      window.view().showInfo(UiText.SESSION_TITLE.text(), UiText.SESSION_SKIPPED.format(names));
    }
    skipped.clear();
  }

  @Override
  public Session.FileState state(Path path) {
    return files.get(path.toString());
  }

  /** Moves the state of the files under a renamed entry to their new paths, keeping the order. */
  void moved(PathMove move) {
    List<Session.FileState> states = new ArrayList<Session.FileState>(files.values());
    List<Session.FileState> next = new ArrayList<Session.FileState>(states.size());
    boolean any = false;
    for (Session.FileState state : states) {
      Path path;
      try {
        path = Path.of(state.path());
      } catch (InvalidPathException e) {
        path = null;
      }
      Path moved = move.real(path);
      any |= moved != path;
      next.add(moved == path ? state : state.withPath(moved.toString()));
    }
    if (any) {
      files.clear();
      for (Session.FileState state : next) {
        files.put(state.path(), state);
      }
      changed();
    }
  }

  @Override
  public void remember(Session.FileState state) {
    files.put(state.path(), state);
    changed();
  }

  /**
   * Reports whether this instance saves the session.
   *
   * @return true after {@link #start} with {@code restore} true
   */
  boolean owner() {
    return owner;
  }

  /** Reports whether saved windows and tabs are still opening. */
  boolean restoring() {
    return restoring;
  }

  /** Arms the timer unless it runs or this instance does not save. */
  void changed() {
    if (owner && !restoring && !armed && !closed && !held) {
      armed = true;
      ui.timerExec(DELAY_MILLIS, timer);
    }
  }

  private void save() {
    if (closed) {
      return;
    }
    final Capture capture = capture(false);
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                write(capture);
              }
            });
    if (!started) {
      LOG.debug("Session not saved: workers are closed");
    }
  }

  /** Holds further saves while a quit asks about unsaved documents. */
  void quitStarted() {
    if (owner && !restoring && !closed) {
      held = true;
      ui.cancelTimer(timer);
      armed = false;
    }
  }

  /** Keeps the windows as they stand after the last answer, before the quit closes documents. */
  void quitAnswered() {
    if (held) {
      quitWindows = windows(false);
    }
  }

  /** Ends the hold after a Cancel or a failed save. */
  void quitCanceled() {
    held = false;
    changed();
  }

  /**
   * Removes the retained searches from the session file now: an owner writes a new capture, which
   * {@link SearchController#forgetHistory} left them out of, and restores the windows still to come
   * without theirs; any other instance goes through {@link SessionRewrite}.
   *
   * @param done receives the result on the UI thread
   */
  void forgetSearches(final Consumer<SessionRewrite.Result> done) {
    if (restoring) {
      searchesForgotten = true;
    }
    final boolean own = owner;
    final Capture capture = owner && !restoring && !closed ? capture(false) : null;
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final SessionRewrite.Result result =
                    capture == null ? removeSearches(own) : written(write(capture));
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        done.accept(result);
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Session searches not removed: workers are closed");
      done.accept(SessionRewrite.Result.FAILED);
    }
  }

  private static SessionRewrite.Result written(boolean ok) {
    return ok ? SessionRewrite.Result.REMOVED : SessionRewrite.Result.FAILED;
  }

  @WorkerThread
  private SessionRewrite.Result removeSearches(boolean own) {
    saveLock.lock();
    try {
      SessionStore sessions = store;
      return sessions == null
          ? SessionRewrite.Result.REMOVED
          : SessionRewrite.withoutSearches(sessions, own);
    } finally {
      saveLock.unlock();
    }
  }

  /**
   * Captures the session and numbers the capture. A quit's windows stand in for the live ones.
   *
   * @param buffers true when the dirty buffers were written as snapshots
   * @return the capture
   */
  Capture capture(boolean buffers) {
    List<Session.Window> windows = quitWindows == null ? windows(buffers) : quitWindows;
    List<String> names = new ArrayList<String>();
    for (Path path : set.recentPaths()) {
      names.add(path.toString());
    }
    List<Session.FileState> states = new ArrayList<Session.FileState>(files.values());
    Collections.reverse(states);
    return new Capture(++captured, new Session(windows, names, states));
  }

  private List<Session.Window> windows(boolean buffers) {
    List<Session.Window> list = new ArrayList<Session.Window>();
    for (ApplicationController window : set.windows()) {
      list.add(window(window, bounds(window, list.size()), buffers));
    }
    return list;
  }

  /** Returns a window's bounds; a {@code --window-*} start keeps the placements it loaded. */
  private WindowState bounds(ApplicationController window, int index) {
    if (!set.rememberWindow() && index < placements.size()) {
      return placements.get(index);
    }
    MainView view = window.view();
    WindowGeometry.Request bounds = view.windowBounds();
    return new WindowState(
        bounds.x(), bounds.y(), bounds.width(), bounds.height(), view.windowMaximized());
  }

  private static Session.Window window(
      ApplicationController window, WindowState bounds, boolean buffers) {
    DocumentController documents = window.documents();
    WorkspaceController workspace = window.workspace();
    SearchController search = window.search();
    MainView view = window.view();
    return new Session.Window(
        bounds,
        documents.sessionTabs(buffers),
        documents.sessionActive(buffers),
        workspace.root() == null ? "" : workspace.root().toString(),
        workspace.shown(),
        SessionText.numbers(view.sideWeights()),
        workspace.expandedDirectories(),
        search.resultsShown(),
        SessionText.numbers(view.dockWeights()),
        search.retained(),
        documents.closedFiles(),
        documents.sessionDisplay(window.bookmarkList().shown()),
        SessionText.numbers(view.resultsWeights()),
        new Session.Browse(
            workspace.browsed() == null ? "" : workspace.browsed().toString(),
            workspace.browse().options().filter().text(),
            workspace.browse().options().hidden()),
        window.alwaysOnTop().saved());
  }

  /**
   * Writes a capture unless a later one was written or the file holds it already.
   *
   * @param capture the capture
   * @return false when this capture, or the later one in its place, did not reach the file
   */
  @WorkerThread
  boolean write(Capture capture) {
    saveLock.lock();
    try {
      if (store == null) {
        return true;
      }
      if (capture.sequence() <= written) {
        return !writeFailed;
      }
      written = capture.sequence();
      writeFailed = false;
      if (!capture.session().equals(lastWritten)) {
        if (store.save(capture.session())) {
          lastWritten = capture.session();
        } else {
          writeFailed = true;
        }
      }
      return !writeFailed;
    } finally {
      saveLock.unlock();
    }
  }

  /**
   * Stops the timer and returns the last capture.
   *
   * @param buffers true when the dirty buffers were written as snapshots
   * @return the capture, or null when this instance does not save or a restore is running, whose
   *     partial tabs must not replace the file
   */
  Capture close(boolean buffers) {
    closed = true;
    ui.cancelTimer(timer);
    return owner && !restoring ? capture(buffers) : null;
  }
}
