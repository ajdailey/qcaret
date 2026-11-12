package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import io.github.ajdailey.qcaret.ui.swt.search.ReportWindow;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replace in a real window: the dock's Replace opens the preview over a tree search, a typed
 * replacement and an unchecked match change the before and after view, Apply writes the files and
 * opens the report, and the report's Restore Run writes them back in a second report. With {@code
 * -Dqcaret.screenshots=<dir>} it saves {@code replace-preview.png} and {@code replace-report.png}
 * there.
 */
@Tag("swt")
class ReplaceSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 900, 600), null);
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
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  @Test
  void thePreviewAppliesAndTheReportRestores() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path first = tree.resolve("config.properties");
    Files.writeString(
        first,
        "# mock settings\nhost=alpha.example\nport=8080\nbackup.host=alpha.example\n",
        StandardCharsets.UTF_8);
    Path second = Files.createDirectories(tree.resolve("sub")).resolve("notes.txt");
    Files.writeString(
        second, "Connects to alpha.example on start.\nRetries alpha.example twice.\n");
    search("alpha.example", tree);
    ResultsDock dock = window.resultsDock();
    dock.press(UiText.RESULTS_REPLACE);
    drain();
    final ReplaceDialog preview = window.replaceDialog();
    assertNotNull(preview);
    preview.setReplacement("beta.example");
    awaitPreview(preview, "+ host=beta.example");
    preview.checkMatch(1, false);
    awaitPreview(preview, "backup.host=alpha.example");
    assertFalse(
        preview.previewText().contains("+ backup.host=beta.example"), preview.previewText());
    screenshot(preview.shell(), "replace-preview");
    preview.pressApply();
    assertTrue(preview.shell().isDisposed());
    final ReportWindow report = awaitReport(0);
    assertEquals(
        "config.properties\t" + tree.toRealPath() + "\t" + UiText.REPLACE_ROW_CHANGED.format(1),
        report.text(0));
    assertEquals(
        "host=beta.example", Files.readString(first, StandardCharsets.UTF_8).split("\n")[1]);
    assertTrue(Files.readString(second).contains("Retries beta.example twice."));
    screenshot(report.shell(), "replace-report");
    report.press(UiText.REPLACE_RESTORE);
    ReportWindow restore = awaitReport(1);
    // Backups are numbered as the writers finish, so the rows' order varies.
    String notes =
        "notes.txt\t" + second.getParent().toRealPath() + "\t" + UiText.REPLACE_ROW_RESTORED.text();
    assertTrue(
        restore.text(0).equals(notes) || restore.text(1).equals(notes),
        restore.text(0) + " / " + restore.text(1));
    assertTrue(Files.readString(first).contains("host=alpha.example\n"));
    restore.press(UiText.BUTTON_CLOSE);
    report.press(UiText.BUTTON_CLOSE);
    assertTrue(window.reportWindows().isEmpty());
  }

  /** Runs a tree search from the dialog and waits for its set to end. */
  private void search(String text, Path directory) {
    controller.execute(CommandId.HYPERSEARCH);
    drain();
    SearchDialog dialog = window.searchDialog();
    dialog.text().setText(text);
    dialog.scope().select(SearchScope.TREE.ordinal());
    dialog.scope().notifyListeners(SWT.Selection, new Event());
    dialog.directory().setText(directory.toString());
    final ResultsInput results = controller.search();
    dialog.search();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return results.setCount() == 1 && !results.running(0);
          }
        },
        "search for " + text);
    drain();
  }

  private void awaitPreview(final ReplaceDialog preview, final String text) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return preview.previewText().contains(text);
          }
        },
        "preview shows " + text);
  }

  private ReportWindow awaitReport(final int index) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<ReportWindow> open = window.reportWindows();
            return open.size() > index && !open.get(index).input().running();
          }
        },
        "report " + index + " ends");
    drain();
    return window.reportWindows().get(index);
  }

  /** Saves a shell as {@code <name>.png} under {@code qcaret.screenshots}, when set. */
  private void screenshot(Shell shell, String name) {
    String dir = System.getProperty("qcaret.screenshots");
    if (dir == null) {
      return;
    }
    shell.forceActive();
    final long until = System.nanoTime() + 500_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
    Rectangle bounds = shell.getBounds();
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
