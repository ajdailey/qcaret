package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery dialog and a restored session on a real window: rows, Restore, Discard, the top line
 * of a tab shown later, split weights. With {@code -Dqcaret.screenshots=<dir>}, {@code
 * recovery-dialog.png} and {@code restored-split.png}.
 */
@Tag("swt")
class RecoverySurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Path settings;
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    window.attach(controller);
    window.place(
        new io.github.ajdailey.qcaret.app.view.WindowGeometry.Request(0, 0, 1000, 640),
        new io.github.ajdailey.qcaret.app.view.WindowGeometry.Request(null, null, null, null));
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void start() {
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    controller.startSession(store, store.load(), true);
  }

  private void until(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void deadBuffer(String id, String path, String title, String text) throws IOException {
    RecoveryStore store = new RecoveryStore(settings, "dead");
    store.open();
    store.write(
        new RecoveryFile.Header(
            id,
            path,
            title,
            "2026-11-05T10:00:00Z",
            "2026-11-05T10:00:30Z",
            3,
            "UTF-8",
            false,
            "LF",
            -1,
            "",
            "",
            "",
            "0,0",
            ""),
        Document.of(
                text,
                TextEncoding.of(StandardCharsets.UTF_8, false),
                LineEnding.LF,
                UndoLimits.DEFAULTS)
            .text());
    store.close(true);
  }

  private RecoveryDialog awaitDialog() {
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.recoveryDialog() != null;
          }
        },
        "the dialog");
    return window.recoveryDialog();
  }

  @Test
  void theDialogListsBuffersAndRestoreOpensThem() throws Exception {
    deadBuffer("b1", "", "Untitled 7", "mock draft\n");
    deadBuffer("b2", "", "Untitled 8", "mock other\n");
    start();
    final RecoveryDialog dialog = awaitDialog();
    assertEquals(2, dialog.rowCount());
    dialog.discardRow(1);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dialog.rowCount() == 1;
          }
        },
        "the row went");
    dialog.restore();
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dialog.shell().isDisposed();
          }
        },
        "the dialog closed");
    assertEquals(
        "mock draft\n", controller.documents().activeEditor().document().text().toString());
    assertTrue(controller.documents().activeEditor().document().isDirty());
  }

  @Test
  void laterKeepsTheFile() throws Exception {
    deadBuffer("b1", "", "Untitled 7", "mock draft\n");
    start();
    RecoveryDialog dialog = awaitDialog();
    dialog.shell().dispose();
    Path file = settings.resolve(RecoveryStore.DIRECTORY).resolve("dead").resolve("b1.qcr");
    assertTrue(Files.exists(file));
  }

  private Path lines(String name, int count) throws IOException {
    StringBuilder text = new StringBuilder("void mock() {\n");
    for (int i = 1; i < count; i++) {
      text.append("  line ").append(i).append(";\n");
    }
    text.append("}\n");
    return Files.writeString(temp.resolve(name), text.toString()).toRealPath();
  }

  private void saveSession(Path split, Path plain, String folds) {
    saveSession(split, plain, folds, Session.Display.DEFAULTS);
  }

  private void saveSession(Path split, Path plain, String folds, Session.Display display) {
    Session.Tab first =
        new Session.Tab(
            plain.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(new Session.View("600,600", 40, "")),
            0,
            false,
            "",
            "12",
            List.of(),
            false);
    Session.Tab second =
        new Session.Tab(
            split.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(new Session.View("0,0", 0, folds), new Session.View("900,900", 50, "")),
            1,
            true,
            "30,70",
            "",
            List.of(),
            false);
    Session.Window saved =
        new Session.Window(
            WindowState.UNKNOWN,
            List.of(first, second),
            1,
            "",
            false,
            "",
            List.of(),
            false,
            "",
            List.of(),
            List.of(),
            display);
    new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings))
        .save(new Session(List.of(saved), List.of(), List.of()));
  }

  private void awaitTabs(final int count) {
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().count() == count && controller.documents().loading() == 0;
          }
        },
        count + " tabs");
  }

  private static void texts(Control control, List<StyledText> found) {
    if (control instanceof StyledText text) {
      found.add(text);
    } else if (control instanceof Composite parent) {
      for (Control child : parent.getChildren()) {
        texts(child, found);
      }
    }
  }

  private StyledText visibleText(int index) {
    List<StyledText> found = new ArrayList<StyledText>();
    texts(window.tabs().getSelection().getControl(), found);
    return found.get(index);
  }

  @Test
  void aRestoredSplitShowsItsTopLinesAndAHiddenTabScrollsWhenShown() throws Exception {
    saveSession(lines("split.txt", 200), lines("plain.txt", 200), "");
    start();
    awaitTabs(2);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return visibleText(1).getTopIndex() == 50;
          }
        },
        "the second view scrolled");
    assertEquals(0, visibleText(0).getTopIndex());
    assertFalse(visibleText(0).getBounds().width == visibleText(1).getBounds().width);
    controller.documents().select(0);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return visibleText(0).getTopIndex() == 40;
          }
        },
        "the hidden tab scrolled");
  }

  @Test
  void aRestoredWindowWrapsShowsTheBookmarksListAndKeepsItsTopLines() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      text.append("mock ".repeat(100)).append('\n');
    }
    Path wide = Files.writeString(temp.resolve("wide.txt"), text.toString()).toRealPath();
    Path split = Files.writeString(temp.resolve("split.txt"), text.toString()).toRealPath();
    saveSession(split, wide, "", new Session.Display(true, false, false, false, true));
    start();
    awaitTabs(2);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorController editor = controller.documents().activeEditor();
            return visibleText(1).getLineCount() > 400
                && editor.projection().lineOfRow(visibleText(1).getTopIndex()) == 50;
          }
        },
        "the selected tab's wrapped rows with its saved top line");
    controller.documents().select(0);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorController editor = controller.documents().activeEditor();
            return visibleText(0).getLineCount() > 400
                && editor.projection().lineOfRow(visibleText(0).getTopIndex()) == 40;
          }
        },
        "wrapped rows with the saved top line");
    assertTrue(controller.documents().wrap());
    assertTrue(window.bookmarksDock().control().getVisible());
  }

  @Test
  void aRestoredFoldCollapsesInItsOwnViewOnly() throws Exception {
    saveSession(lines("split.txt", 200), lines("plain.txt", 200), "0");
    start();
    awaitTabs(2);
    until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return visibleText(0).getLineCount() < 10;
          }
        },
        "the first view folded");
    assertTrue(visibleText(1).getLineCount() > 100);
  }

  @Test
  void screenshotsWhenAsked() throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    saveSession(lines("split.txt", 200), lines("plain.txt", 200), "");
    deadBuffer("b1", "/mock/notes/plan.txt", "", "mock\n");
    deadBuffer("b2", "", "Untitled 2", "mock draft\n");
    Path bad = settings.resolve(RecoveryStore.DIRECTORY).resolve("dead").resolve("b3.qcr");
    Files.writeString(bad, "mock");
    start();
    awaitTabs(2);
    RecoveryDialog dialog = awaitDialog();
    dialog.shell().forceActive();
    capture(dir, "recovery-dialog");
    dialog.shell().dispose();
    capture(dir, "restored-split");
  }

  private void pause(int millis) {
    final long until = System.nanoTime() + millis * 1_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  /** Copies the screen to a PNG. */
  private void capture(String dir, String name) {
    pause(900);
    Rectangle bounds = display.getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(Path.of(dir, name + ".png").toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
