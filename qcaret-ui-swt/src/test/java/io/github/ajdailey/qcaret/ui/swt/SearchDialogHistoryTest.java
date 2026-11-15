package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HyperSearch dialog's history fields (text, directory, include masks): the arrow opens the
 * list, Up and Down move its highlight without a search, Enter chooses the entry without pressing
 * Search, Esc closes only the list, and the list's shell goes with the dialog and with a quit. A
 * cocoa native drop-down list ran a tracking loop that kept the process alive after a quit. With
 * {@code -Dqcaret.screenshots=<dir>} it saves {@code hypersearch-history-light.png} and {@code
 * -dark.png} there, the text field's list open.
 */
@Tag("swt")
class SearchDialogHistoryTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<SearchForm> runs = new ArrayList<SearchForm>();

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
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      if (!window.shell().isDisposed()) {
        controller.quit();
        await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.shell().isDisposed();
              }
            },
            "quit");
      }
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

  /** Opens the dialog at the tree scope with two entries in each history. */
  private SearchDialog showDialog() {
    SearchForm form =
        new SearchForm(
            "typed",
            false,
            false,
            false,
            SearchScope.TREE,
            "typed",
            "typed",
            "",
            false,
            "",
            List.of("text one", "text two"),
            List.of("dir one", "dir two"),
            List.of("*.one", "*.two"));
    window.showSearchDialog(
        form,
        new Consumer<SearchForm>() {
          @Override
          public void accept(SearchForm chosen) {
            runs.add(chosen);
          }
        });
    drain();
    SearchDialog dialog = window.searchDialog();
    assertNotNull(dialog);
    return dialog;
  }

  /**
   * Sends a key the way SWT does: for Enter and Esc a traversal first, which presses the default
   * button or closes the dialog unless a listener cancels it, then the key.
   */
  private static void press(Text field, int keyCode) {
    if (keyCode == SWT.CR || keyCode == SWT.ESC) {
      Event traverse = new Event();
      traverse.keyCode = keyCode;
      traverse.detail = keyCode == SWT.CR ? SWT.TRAVERSE_RETURN : SWT.TRAVERSE_ESCAPE;
      traverse.doit = true;
      field.notifyListeners(SWT.Traverse, traverse);
      if (traverse.doit) {
        if (keyCode == SWT.CR) {
          field.getShell().getDefaultButton().notifyListeners(SWT.Selection, new Event());
        } else {
          field.getShell().close();
        }
        return;
      }
    }
    Event key = new Event();
    key.keyCode = keyCode;
    field.notifyListeners(SWT.KeyDown, key);
  }

  private void checkField(Function<SearchDialog, HistoryField> kind, List<String> entries) {
    SearchDialog dialog = showDialog();
    HistoryField history = kind.apply(dialog);
    Text field = history.text();
    assertEquals(entries, history.items());
    assertEquals("typed", field.getText());
    assertArrayEquals(new Control[] {field}, history.control().getTabList());
    assertTrue(history.control().getSize().x >= SearchDialog.FIELD_WIDTH);
    history.arrow().notifyListeners(SWT.Selection, new Event());
    drain();
    assertTrue(history.listVisible());
    assertEquals(-1, history.highlighted());
    press(field, SWT.ARROW_DOWN);
    press(field, SWT.ARROW_DOWN);
    assertEquals(1, history.highlighted());
    press(field, SWT.ARROW_UP);
    assertEquals(0, history.highlighted());
    press(field, SWT.ARROW_DOWN);
    drain();
    assertEquals("typed", field.getText());
    press(field, SWT.CR);
    drain();
    assertFalse(history.listVisible());
    assertEquals(entries.get(1), field.getText());
    assertFalse(dialog.shell().isDisposed(), "Enter in the list pressed Search");
    press(field, SWT.ARROW_DOWN);
    assertTrue(history.listVisible());
    assertEquals(0, history.highlighted());
    press(field, SWT.ESC);
    drain();
    assertFalse(history.listVisible());
    assertFalse(dialog.shell().isDisposed(), "Esc in the list closed the dialog");
    assertTrue(runs.isEmpty());
    history.show(0);
    Shell list = history.popup();
    assertNotNull(list);
    dialog.shell().close();
    drain();
    assertTrue(list.isDisposed());
  }

  @Test
  void theTextFieldListChoosesWithoutSearching() {
    checkField(
        new Function<SearchDialog, HistoryField>() {
          @Override
          public HistoryField apply(SearchDialog dialog) {
            return dialog.text();
          }
        },
        List.of("text one", "text two"));
  }

  @Test
  void theDirectoryFieldListChoosesWithoutSearching() {
    checkField(
        new Function<SearchDialog, HistoryField>() {
          @Override
          public HistoryField apply(SearchDialog dialog) {
            return dialog.directory();
          }
        },
        List.of("dir one", "dir two"));
  }

  @Test
  void theIncludeFieldListChoosesWithoutSearching() {
    checkField(
        new Function<SearchDialog, HistoryField>() {
          @Override
          public HistoryField apply(SearchDialog dialog) {
            return dialog.include();
          }
        },
        List.of("*.one", "*.two"));
  }

  /** Enter with the list closed presses Search, and Esc closes the dialog, as before. */
  @Test
  void enterSearchesAndEscClosesWithTheListClosed() {
    SearchDialog dialog = showDialog();
    press(dialog.text().text(), SWT.CR);
    drain();
    assertTrue(dialog.shell().isDisposed());
    assertEquals(1, runs.size());
    assertEquals("typed", runs.get(0).text());
    dialog = showDialog();
    press(dialog.include().text(), SWT.ESC);
    drain();
    assertTrue(dialog.shell().isDisposed());
    assertEquals(1, runs.size());
  }

  @Test
  void aQuitWithTheListOpenDisposesEveryShell() {
    SearchDialog dialog = showDialog();
    dialog.directory().show(0);
    Shell list = dialog.directory().popup();
    assertNotNull(list);
    controller.quit();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        },
        "quit");
    assertTrue(list.isDisposed());
    assertEquals(0, display.getShells().length);
  }

  @Test
  void screenshots() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "no qcaret.screenshots");
    shot(Path.of(dir, "hypersearch-history-light.png"));
    controller.execute(CommandId.THEME_DARK);
    drain();
    shot(Path.of(dir, "hypersearch-history-dark.png"));
  }

  /** Saves the screen under the dialog and its open list. */
  private void shot(Path file) {
    SearchDialog dialog = showDialog();
    dialog.text().show(0);
    final long until = System.nanoTime() + 500_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "the list shows");
    Rectangle bounds = dialog.shell().getBounds().union(dialog.text().popup().getBounds());
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(file.toString(), SWT.IMAGE_PNG);
    image.dispose();
    dialog.shell().close();
    drain();
  }
}
