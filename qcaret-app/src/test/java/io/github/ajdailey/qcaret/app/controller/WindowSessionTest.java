package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.recovery.RecoveryTexts;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.document.Document;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session and the recovery files across windows: one file for every window, the capture before
 * a quit closes anything, and a moved tab's recovery file.
 */
class WindowSessionTest {
  private static final WindowSet.Placement NEW = new WindowSet.Placement(0, null, null);

  @TempDir Path temp;

  private static final Consumer<Boolean> IGNORED =
      new Consumer<Boolean>() {
        @Override
        public void accept(Boolean removed) {
          assertTrue(removed);
        }
      };

  private Path settings;
  private final List<Windows> open = new ArrayList<Windows>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Windows windows : open) {
      if (!windows.views.get(0).disposed) {
        windows.close();
      }
    }
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
  }

  private Path file(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text).toRealPath();
  }

  private Windows windows(boolean remember) {
    Windows windows = new Windows(temp, remember);
    open.add(windows);
    return windows;
  }

  /** Starts over the saved session and runs until the restore ends. */
  private Windows start(boolean remember) throws InterruptedException {
    Windows windows = windows(remember);
    SessionStore store = store();
    windows.first().startSession(store, store.load(), true);
    awaitRestore(windows);
    return windows;
  }

  private static void awaitRestore(final Windows windows) throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !windows.set.session().restoring();
          }
        },
        "the restore ended");
    windows.settle("restore");
  }

  private static void quit(Windows windows) throws InterruptedException {
    windows.close();
  }

  private static List<String> tabs(Session.Window window) {
    List<String> paths = new ArrayList<String>();
    for (Session.Tab tab : window.tabs()) {
      paths.add(tab.path());
    }
    return paths;
  }

  private static Session captured(Windows windows) {
    return windows.set.session().capture(false).session();
  }

  private static void edit(ApplicationController window, String text) {
    window.documents().activeEditor().type(text);
  }

  private List<Path> recoveryFiles() throws IOException {
    List<Path> found = new ArrayList<Path>();
    Path root = settings.resolve(RecoveryStore.DIRECTORY);
    if (!Files.isDirectory(root)) {
      return found;
    }
    try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
      for (Path directory : directories) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.qcr")) {
          for (Path file : files) {
            found.add(file);
          }
        }
      }
    }
    return found;
  }

  private void awaitRecoveryFiles(Windows windows, final int count) throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return recoveryFiles().size() == count;
            } catch (IOException e) {
              return false;
            }
          }
        },
        count + " recovery files");
  }

  /** Runs the recovery interval and waits for the queue and its results. */
  private static void tick(final Windows windows) throws InterruptedException {
    windows.ui.runLongTimers();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.first().recovery().idle();
          }
        },
        "the recovery queue");
    windows.ui.runPending();
  }

  @Test
  void everyWindowRestoresWithItsTabsBoundsAndSelectedTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Path c = file("c.txt", "c\n");
    Windows before = start(true);
    ApplicationController first = before.first();
    first.documents().closeAll(null);
    before.open(first, a);
    before.open(first, b);
    first.documents().select(0);
    ApplicationController second = before.second();
    before.open(second, c);
    before.view(first).bounds = new WindowGeometry.Request(10, 20, 800, 600);
    before.view(second).bounds = new WindowGeometry.Request(300, 200, 640, 480);
    before.view(second).maximized = true;
    before.ui.runPending();
    quit(before);

    Session saved = store().load();
    assertEquals(2, saved.windows().size());
    assertEquals(new WindowState(10, 20, 800, 600, false), saved.windows().get(0).bounds());

    Windows after = start(true);
    assertEquals(2, after.set.windows().size());
    assertEquals(new WindowGeometry.Request(300, 200, 640, 480), after.geometries.get(0));
    assertEquals(List.of(true), after.maximized);
    assertEquals(List.of(a.toString(), b.toString()), after.first().documents().labels());
    assertEquals(0, after.first().documents().activeIndex());
    assertEquals(List.of(c.toString()), after.controllers.get(1).documents().labels());
  }

  @Test
  void theCommandLinesFilesOpenAfterTheRestoreAndNoUntitledDocument() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows before = start(true);
    before.first().documents().closeAll(null);
    before.open(before.first(), a);
    quit(before);

    final Windows after = windows(true);
    SessionStore store = store();
    StartupRequest request =
        StartupRequest.handoff(
            List.of(new FileRequest(b)), false, null, null, null, null, null, null);
    after.first().startSession(store, store.load(), true, request);
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return after.first().documents().count() == 2;
          }
        },
        "both tabs");
    after.settle("open");
    assertEquals(List.of(a.toString(), b.toString()), after.first().documents().labels());
  }

  @Test
  void aFileListedInTwoWindowsEndsTheRestoreWithOneTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Windows before = start(true);
    before.first().documents().closeAll(null);
    before.open(before.first(), a);
    Session.Window window = captured(before).windows().get(0);
    quit(before);
    assertTrue(store().save(new Session(List.of(window, window), List.of(), List.of())));

    Windows after = start(true);

    assertEquals(2, after.set.windows().size());
    assertEquals(List.of(a.toString()), after.first().documents().labels());
    assertEquals(1, after.controllers.get(1).documents().count());
    assertEquals(null, after.controllers.get(1).documents().documentAt(a));
  }

  @Test
  void aSavedWindowThatCannotOpenGivesItsTabsToTheFirstWindow() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows before = start(true);
    before.first().documents().closeAll(null);
    before.open(before.first(), a);
    before.open(before.second(), b);
    quit(before);

    Windows after = windows(true);
    after.set.setFactory(null);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    awaitRestore(after);

    assertEquals(1, after.set.windows().size());
    assertEquals(List.of(a.toString(), b.toString()), after.first().documents().labels());
  }

  @Test
  void aStartWithWindowOptionsKeepsTheSavedPlacements() throws Exception {
    Windows before = start(true);
    before.view(before.first()).bounds = new WindowGeometry.Request(10, 20, 800, 600);
    quit(before);

    Windows placed = start(false);
    placed.view(placed.first()).bounds = new WindowGeometry.Request(1, 2, 3, 4);

    assertEquals(
        new WindowState(10, 20, 800, 600, false), captured(placed).windows().get(0).bounds());
  }

  /** Saves a session of one window with these tabs. */
  private void saveSession(Path... tabs) throws InterruptedException {
    Windows before = start(true);
    before.first().documents().closeAll(null);
    for (Path tab : tabs) {
      before.open(before.first(), tab);
    }
    quit(before);
  }

  private static StartupRequest handed(Path file) {
    return StartupRequest.handoff(
        List.of(new FileRequest(file)), false, null, null, null, null, null, null);
  }

  private static void awaitTabs(final Windows windows, final int count)
      throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.first().documents().count() == count;
          }
        },
        count + " tabs");
    windows.settle("open");
  }

  @Test
  void launchesHandedDuringARestoreOpenAfterItAndTheSessionKeepsEveryTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Path own = file("own.txt", "own\n");
    Path c = file("c.txt", "c\n");
    Path d = file("d.txt", "d\n");
    saveSession(a, b);

    Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true, handed(own));
    after.set.openHandoff(handed(c));
    after.set.openHandoff(handed(d));
    assertEquals(0, after.first().documents().count());
    awaitRestore(after);
    awaitTabs(after, 5);

    List<String> labels = after.first().documents().labels();
    assertEquals(List.of(a.toString(), b.toString()), labels.subList(0, 2));
    assertTrue(
        labels.containsAll(List.of(own.toString(), c.toString(), d.toString())), "" + labels);
    assertEquals(1, after.set.windows().size());
    quit(after);
    assertEquals(labels, tabs(store().load().windows().get(0)));
  }

  @Test
  void aFileHandedDuringARestoreThatTheSessionHoldsEndsWithOneTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    saveSession(a, b);

    Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    after.set.openHandoff(handed(b));
    awaitRestore(after);

    assertEquals(List.of(a.toString(), b.toString()), after.first().documents().labels());
  }

  @Test
  void newWindowsHandedDuringARestoreOpenAfterItInArrivalOrder() throws Exception {
    Path a = file("a.txt", "a\n");
    Path c = file("c.txt", "c\n");
    Path d = file("d.txt", "d\n");
    saveSession(a);

    Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    after.set.openHandoff(
        StartupRequest.handoff(List.of(new FileRequest(c)), true, 10, 0, 300, 200, null, null));
    after.set.openHandoff(
        StartupRequest.handoff(List.of(new FileRequest(d)), true, 20, 0, 300, 200, null, null));
    assertEquals(1, after.set.windows().size());
    awaitRestore(after);

    assertEquals(3, after.set.windows().size());
    assertEquals(List.of(a.toString()), after.first().documents().labels());
    assertEquals(List.of(c.toString()), after.controllers.get(1).documents().labels());
    assertEquals(List.of(d.toString()), after.controllers.get(2).documents().labels());
  }

  @Test
  void aComparisonHandedDuringARestoreOpensAfterIt() throws Exception {
    Path a = file("a.txt", "a\n");
    Path left = file("left.txt", "one\n");
    Path right = file("right.txt", "two\n");
    saveSession(a);

    final Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    after.set.openHandoff(
        StartupRequest.handoff(List.of(), false, null, null, null, null, left, right));
    final FakeView view = after.view(after.first());
    assertTrue(view.comparisons.isEmpty());
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.comparisons.isEmpty();
          }
        },
        "the comparison");
    assertFalse(after.set.session().restoring());
    assertEquals(List.of(a.toString()), after.first().documents().labels());
  }

  @Test
  void aQuitDuringARestoreDropsHandedLaunchesAndLeavesTheSessionFile() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Path c = file("c.txt", "c\n");
    saveSession(a, b);
    Session saved = store().load();

    Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    after.set.openHandoff(handed(c));
    quit(after);

    assertEquals(saved, store().load());
    assertEquals(0, after.first().documents().count());
  }

  @Test
  void quitListenersRunAfterTheSessionIsWritten() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows windows = start(true);
    windows.first().documents().closeAll(null);
    windows.open(windows.first(), a);
    windows.open(windows.first(), b);
    final List<Session> seen = new CopyOnWriteArrayList<Session>();
    windows.set.addQuitListener(
        new Runnable() {
          @Override
          public void run() {
            seen.add(store().load());
          }
        });
    windows.first().documents().select(0);
    quit(windows);

    assertEquals(0, seen.get(0).windows().get(0).active());
  }

  @Test
  void aFileHandedWhileTheRecoveryDialogShowsOpensAndARestoreFillsItsTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Windows dead = start(true);
    dead.first().documents().closeAll(null);
    dead.open(dead.first(), a);
    edit(dead.first(), "mine ");
    quit(dead);

    final Windows windows = windows(true);
    final ApplicationController first = windows.first();
    SessionStore store = store();
    first.startSession(store, store.load(), false);
    final FakeView view = windows.view(first);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.recovery != null;
          }
        },
        "the dialog");

    windows.set.openHandoff(handed(a));
    awaitTabs(windows, 1);
    view.recovery.restore(new int[] {0});
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().anyDirty();
          }
        },
        "the buffer");

    assertEquals(1, first.documents().count());
    assertEquals("mine a\n", first.documents().activeDocument().text().toString());
  }

  @Test
  void aWindowStaysOpenWhileTheRestoreRuns() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows before = start(true);
    before.first().documents().closeAll(null);
    before.open(before.first(), a);
    before.open(before.second(), b);
    quit(before);

    final Windows after = windows(true);
    SessionStore store = store();
    after.first().startSession(store, store.load(), true);
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return after.set.windows().size() == 2;
          }
        },
        "the second window");
    assertTrue(after.set.session().restoring());

    after.controllers.get(1).closeWindow();

    assertEquals(2, after.set.windows().size());
    awaitRestore(after);
    assertEquals(List.of(b.toString()), after.controllers.get(1).documents().labels());
  }

  @Test
  void aQuitClosesNothingBeforeTheLastAnswerAndCapturesEveryWindow() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows windows = start(true);
    ApplicationController first = windows.first();
    first.documents().closeAll(null);
    windows.open(first, a);
    ApplicationController second = windows.second();
    windows.open(second, b);
    edit(second, "x");
    first.windowActivated();

    first.execute(CommandId.QUIT);

    assertEquals(1, first.documents().count());
    assertNotNull(windows.view(second).unsaved);
    windows.ui.runPending();
    assertEquals(List.of(a.toString()), tabs(captured(windows).windows().get(0)));
    assertEquals(List.of(b.toString()), tabs(captured(windows).windows().get(1)));

    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.views.get(0).disposed;
          }
        },
        "quit");

    Session saved = store().load();
    assertEquals(List.of(a.toString()), tabs(saved.windows().get(0)));
    assertEquals(List.of(b.toString()), tabs(saved.windows().get(1)));
    assertEquals(2, saved.files().size());
  }

  @Test
  void aCancelReturnsTheSessionToTheLiveWindowsAndSavingResumes() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    final Windows windows = start(true);
    ApplicationController first = windows.first();
    first.documents().closeAll(null);
    windows.open(first, a);
    ApplicationController second = windows.second();
    windows.open(second, b);
    edit(second, "x");
    first.windowActivated();
    first.execute(CommandId.QUIT);

    windows.view(second).unsaved.accept(UnsavedChoice.CANCEL);

    assertFalse(windows.set.isQuitting());
    assertEquals(List.of(a.toString()), tabs(captured(windows).windows().get(0)));
    final Path c = file("c.txt", "c\n");
    windows.open(first, c);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            windows.ui.runPending();
            Session saved = store().load();
            return saved.windows().size() == 2
                && tabs(saved.windows().get(0)).equals(List.of(a.toString(), c.toString()));
          }
        },
        "the saved session follows the windows");
  }

  @Test
  void aProcessEndDuringAQuitPromptKeepsEveryTabAndTheDirtyBuffer() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Windows before = start(true);
    ApplicationController first = before.first();
    first.documents().closeAll(null);
    before.open(first, a);
    ApplicationController second = before.second();
    before.open(second, b);
    edit(second, "mine ");
    first.windowActivated();
    first.execute(CommandId.QUIT);
    assertNotNull(before.view(second).unsaved);

    quit(before);

    Session saved = store().load();
    assertEquals(List.of(a.toString()), tabs(saved.windows().get(0)));
    assertEquals(List.of(b.toString()), tabs(saved.windows().get(1)));
    assertEquals(1, recoveryFiles().size());
    assertEquals("mine b\n", RecoveryTexts.read(recoveryFiles().get(0)));

    Windows after = start(true);
    final FakeView view = after.view(after.set.target());
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.recovery != null;
          }
        },
        "the dialog");
    assertEquals(1, view.recovery.rows().size());
  }

  @Test
  void buffersOfTwoWindowsHaveTheirOwnFilesAndAMovedBufferKeepsItsFile() throws Exception {
    Windows windows = start(true);
    ApplicationController first = windows.first();
    edit(first, "one");
    Document moved = first.documents().activeDocument();
    windows.set.newWindow();
    ApplicationController second = windows.controllers.get(1);
    edit(second, "two");
    tick(windows);
    awaitRecoveryFiles(windows, 2);
    String id = first.documents().dirtyBuffers().get(0).id();
    assertNotEquals(id, second.documents().dirtyBuffers().get(0).id());
    Path own = recoveryFiles().get(0).getParent().resolve(id + ".qcr");
    long stamp = Files.getLastModifiedTime(own).toMillis();

    assertNotNull(windows.set.moveDocument(first, moved, second, NEW));
    tick(windows);

    assertEquals(2, recoveryFiles().size());
    assertEquals(stamp, Files.getLastModifiedTime(own).toMillis());
    assertEquals(2, second.documents().dirtyBuffers().size());
    assertEquals(id, second.documents().dirtyBuffers().get(0).id());

    edit(second, "more");
    tick(windows);
    assertEquals("onemore", RecoveryTexts.read(own));

    Path saved = temp.resolve("saved.txt");
    second.documents().saveAs();
    windows.view(second).saveChooser.accept(saved);
    windows.settle("save");
    tick(windows);
    awaitRecoveryFiles(windows, 1);
    assertFalse(Files.exists(own));
  }

  @Test
  void aMoveSavesTheSession() throws Exception {
    Path a = file("a.txt", "a\n");
    final Windows windows = start(true);
    ApplicationController first = windows.first();
    first.documents().closeAll(null);
    windows.open(first, a);
    Document document = first.documents().activeDocument();

    assertNotNull(windows.set.moveDocument(first, document, null, NEW));

    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            windows.ui.runPending();
            Session saved = store().load();
            return saved.windows().size() == 2
                && tabs(saved.windows().get(1)).equals(List.of(a.toString()));
          }
        },
        "the saved session holds the moved tab");
  }

  @Test
  void aSnapshotQuitCoversEveryWindow() throws Exception {
    Windows before = start(true);
    ApplicationController first = before.first();
    Settings running = first.settings();
    Options o = running.current().options();
    running.set(
        running
            .current()
            .withOptions(
                new Options(
                    o.completionChars(),
                    o.autoClose(),
                    o.maxHighlightLineChars(),
                    o.fontSize(),
                    o.pollSeconds(),
                    o.autoReloadClean(),
                    o.backupDays(),
                    o.searchMaxLineChars(),
                    o.excludedDirectories(),
                    o.recoveryIntervalSeconds(),
                    o.recoveryKeepDays(),
                    true,
                    o.tabWidth(),
                    o.insertSpaces(),
                    Options.DEFAULTS.rightMargin(),
                    Options.DEFAULTS.dateFormat())));
    edit(first, "left");
    ApplicationController second = before.second();
    second.documents().newDocument();
    edit(second, "right");

    first.requestQuit();
    before.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return before.views.get(0).disposed;
          }
        },
        "quit");

    final Windows after = start(true);
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return after.set.windows().size() == 2
                && after.first().documents().anyDirty()
                && after.controllers.get(1).documents().anyDirty();
          }
        },
        "both buffers in their windows");
    assertEquals("left", after.first().documents().activeDocument().text().toString());
    assertEquals("right", after.controllers.get(1).documents().activeDocument().text().toString());
  }

  @Test
  void aRecoveredFileOpensInTheWindowHoldingIt() throws Exception {
    Path a = file("a.txt", "a\n");
    Windows dead = start(true);
    dead.first().documents().closeAll(null);
    dead.open(dead.first(), a);
    edit(dead.first(), "mine ");
    quit(dead);
    assertEquals(1, recoveryFiles().size());

    final Windows windows = windows(true);
    final ApplicationController first = windows.first();
    windows.open(first, a);
    ApplicationController second = windows.second();
    second.documents().newDocument();
    SessionStore store = store();
    first.startSession(store, store.load(), false);
    final FakeView view = windows.view(windows.set.target());
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.recovery != null;
          }
        },
        "the dialog");

    view.recovery.restore(new int[] {0});

    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().anyDirty();
          }
        },
        "the buffer");
    assertEquals(1, first.documents().count());
    assertEquals("mine a\n", first.documents().activeDocument().text().toString());
    assertEquals(1, second.documents().count());
    assertFalse(second.documents().anyDirty());
  }

  @Test
  void clearHistoryForgetsTheLastRunOfEveryWindow() throws Exception {
    Windows windows = start(true);
    ApplicationController first = windows.first();
    ApplicationController second = windows.second();
    runSearch(first);
    runSearch(second);
    first.execute(CommandId.HYPERSEARCH);
    assertEquals("*.txt", windows.view(first).searchForm.include());
    second.execute(CommandId.HYPERSEARCH);
    assertEquals("*.txt", windows.view(second).searchForm.include());
    first.execute(CommandId.PREFERENCES);
    final FakeView view = windows.view(first);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.preferences != null;
          }
        },
        "the dialog");

    view.preferences.clearHistory();

    first.execute(CommandId.HYPERSEARCH);
    assertEquals("", windows.view(first).searchForm.include());
    second.execute(CommandId.HYPERSEARCH);
    assertEquals("", windows.view(second).searchForm.include());
  }

  @Test
  void theDisplayStateAndReadOnlyComeBackPerWindow() throws Exception {
    Path a = file("a.txt", "a\n");
    Windows before = start(true);
    ApplicationController first = before.first();
    first.documents().closeAll(null);
    before.open(first, a);
    first.execute(CommandId.WORD_WRAP);
    first.execute(CommandId.SHOW_WHITESPACE);
    first.execute(CommandId.SHOW_LINE_ENDINGS);
    first.execute(CommandId.OVERVIEW_STRIP);
    first.execute(CommandId.BOOKMARKS_LIST);
    first.execute(CommandId.READ_ONLY);
    quit(before);

    Windows after = start(true);
    DocumentController documents = after.first().documents();
    assertTrue(documents.wrap());
    assertTrue(documents.whitespaceShown());
    assertTrue(documents.lineEndingsShown());
    assertFalse(documents.overviewShown());
    assertTrue(after.first().bookmarkList().shown());
    assertTrue(documents.activeDocument().isReadOnly());
  }

  @Test
  void aDisplayToggleSavesTheSession() throws Exception {
    final Windows windows = start(true);
    windows.first().execute(CommandId.OVERVIEW_STRIP);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            windows.ui.runPending();
            List<Session.Window> saved = store().load().windows();
            return !saved.isEmpty() && !saved.get(0).display().overview();
          }
        },
        "the session follows the toggle");
  }

  private void awaitNoSearchInFile(final Windows windows) throws Exception {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            windows.ui.runPending();
            try {
              return !Files.readString(settings.resolve(SessionStore.FILE)).contains(".search.");
            } catch (IOException e) {
              return false;
            }
          }
        },
        "the session file holds no search");
  }

  private void saveSessionWithSearch(final Windows windows) throws Exception {
    runSearch(windows.first());
    windows.settle("search");
    windows.ui.runPending();
    assertEquals(1, captured(windows).windows().get(0).searches().size());
    // The window's own timer save can land after this one with a capture from before the search.
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return store().save(captured(windows))
                  && Files.readString(settings.resolve(SessionStore.FILE)).contains(".search.");
            } catch (IOException e) {
              return false;
            }
          }
        },
        "the session file holds the search");
  }

  @Test
  void clearHistoryRemovesRetainedSearchesFromTheFileAtOnce() throws Exception {
    Windows windows = start(true);
    saveSessionWithSearch(windows);
    int sets = windows.first().search().setCount();

    windows.set.forgetHistory(IGNORED);

    awaitNoSearchInFile(windows);
    assertEquals(sets, windows.first().search().setCount());
    assertEquals(List.of(), captured(windows).windows().get(0).searches());
  }

  @Test
  void clearHistoryInAnInstanceThatDoesNotSaveTheSessionStillRewritesTheFile() throws Exception {
    Windows owner = start(true);
    saveSessionWithSearch(owner);
    quit(owner);
    assertTrue(Files.readString(settings.resolve(SessionStore.FILE)).contains(".search."));

    Windows windows = windows(true);
    SessionStore store = store();
    windows.first().startSession(store, store.load(), false);
    windows.settle("start");
    windows.set.forgetHistory(IGNORED);
    awaitNoSearchInFile(windows);
    assertEquals(1, store().load().windows().size());
  }

  /** A debounced save may land between the two steps; a directory stops every later one. */
  private static void replaceWithDirectory(Path file) throws IOException {
    for (int attempt = 0; attempt < 1000; attempt++) {
      Files.deleteIfExists(file);
      try {
        Files.createDirectory(file);
        return;
      } catch (FileAlreadyExistsException e) {
        Thread.onSpinWait();
      }
    }
    throw new IOException("A save kept replacing the file");
  }

  @Test
  void aFailedSessionWriteAtClearHistoryReportsItAndAPressAfterTheFaultWrites() throws Exception {
    final Windows windows = start(true);
    saveSessionWithSearch(windows);
    Path session = settings.resolve(SessionStore.FILE);
    replaceWithDirectory(session);
    final AtomicReference<Boolean> removed = new AtomicReference<Boolean>();
    Consumer<Boolean> done = removed::set;
    windows.set.forgetHistory(done);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return removed.get() != null;
          }
        },
        "the write ended");
    assertFalse(removed.get());

    Files.delete(session);
    removed.set(null);
    windows.set.forgetHistory(done);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return removed.get() != null;
          }
        },
        "the second write ended");
    assertTrue(removed.get());
    awaitNoSearchInFile(windows);
  }

  private static void runSearch(ApplicationController window) {
    window
        .search()
        .run(
            new SearchForm(
                "x",
                false,
                false,
                false,
                SearchScope.OPEN_DOCUMENTS,
                "",
                "*.txt",
                "",
                false,
                "",
                List.of(),
                List.of(),
                List.of()));
  }
}
