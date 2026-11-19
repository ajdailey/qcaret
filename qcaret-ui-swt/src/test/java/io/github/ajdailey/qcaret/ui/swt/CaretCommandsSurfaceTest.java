package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Insert Number Sequence dialog and View > Full Screen in a real window. With {@code
 * qcaret.rm.out} set, also writes the dialog's screenshots, light and dark, there.
 */
@Tag("swt")
class CaretCommandsSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

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
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      window.setFullScreen(false);
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Run the posted tasks.
    }
  }

  private EditorController openFile(String text) throws Exception {
    Path file = Files.writeString(temp.resolve("a.txt"), text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().count() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    flush();
    return controller.documents().activeEditor();
  }

  private NumberSequenceDialog askSequence() {
    window.menuBar().item(CommandId.INSERT_NUMBER_SEQUENCE).notifyListeners(SWT.Selection, null);
    NumberSequenceDialog dialog = window.numberSequenceDialog();
    assertTrue(dialog.shell().isVisible());
    return dialog;
  }

  @Test
  void theSequenceDialogRefusesANumberOutsideLongAndInsertsAtEachCaret() throws Exception {
    EditorController editor = openFile("a\nb\nc\n");
    editor
        .selections()
        .set(
            SelectionSet.of(
                List.of(Selection.caret(0), Selection.caret(2), Selection.caret(4)), 0));
    NumberSequenceDialog dialog = askSequence();
    assertEquals("1", dialog.start().getText());
    assertEquals("1", dialog.step().getText());
    assertEquals(0, dialog.width().getSelection());
    assertTrue(dialog.ok().getEnabled());
    dialog.start().setText("9223372036854775808");
    assertFalse(dialog.ok().getEnabled());
    dialog.start().setText("0x0E");
    dialog.step().setText("-1");
    dialog.width().setSelection(2);
    dialog.base().select(2);
    assertTrue(dialog.ok().getEnabled());
    dialog.submit();
    assertTrue(dialog.shell().isDisposed());
    assertEquals("0ea\n0db\n0cc\n", editor.document().text().toString());
    NumberSequenceDialog again = askSequence();
    assertEquals("14", again.start().getText());
    assertEquals(2, again.base().getSelectionIndex());
    again.shell().close();
  }

  @Test
  void fullScreenTogglesAndTheSavedBoundsStayTheNormalOnes() {
    MenuItem item = window.menuBar().item(CommandId.FULL_SCREEN);
    assertSame(window.menuBar().menu(CommandGroup.VIEW), item.getParent());
    assertTrue((item.getStyle() & SWT.CHECK) != 0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.windowBounds().width() != null;
          }
        },
        "bounds tracked");
    WindowGeometry.Request normal = window.windowBounds();
    boolean maximized = window.windowMaximized();
    item.notifyListeners(SWT.Selection, null);
    awaitFullScreen(true);
    assertTrue(item.getSelection());
    window.shell().setSize(1100, 900);
    flush();
    assertEquals(normal, window.windowBounds());
    assertEquals(maximized, window.windowMaximized());
    item.notifyListeners(SWT.Selection, null);
    awaitFullScreen(false);
    assertFalse(item.getSelection());
  }

  /** Waits for the shell's state, which cocoa changes after an animation, and syncs the menu. */
  private void awaitFullScreen(final boolean on) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.fullScreen() == on && window.shell().getFullScreen() == on;
          }
        },
        "full screen " + on);
    window.menuBar().sync(CommandGroup.VIEW);
  }

  @Test
  @EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
  void screenshots() throws Exception {
    Path out = Files.createDirectories(Path.of(System.getProperty("qcaret.rm.out")));
    openFile("x\n");
    NumberSequenceDialog light = askSequence();
    save(light.shell(), out.resolve("number-sequence-light.png"));
    light.shell().close();
    controller.execute(CommandId.THEME_DARK);
    flush();
    NumberSequenceDialog dark = askSequence();
    save(dark.shell(), out.resolve("number-sequence-dark.png"));
    dark.shell().close();
  }

  private void save(Shell shell, Path file) {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "settled");
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
