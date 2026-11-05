package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Split views and the find bar in a real window: two views edit one text with their own carets, the
 * find bar selects and paints matches in both views, Escape closes it, and a theme switch recolors
 * the match background in place.
 */
@Tag("swt")
class SplitAndFindTest {
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
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
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

  private void openFile(String name, String text) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        name);
    drain();
  }

  private static void key(StyledText text, char character) {
    Event event = new Event();
    event.character = character;
    event.keyCode = character;
    text.notifyListeners(SWT.KeyDown, event);
  }

  @Test
  void twoViewsEditOneTextWithTheirOwnCarets() throws Exception {
    openFile("split.txt", "first\nsecond\nthird\n");
    EditorPane first = window.activeEditor();
    first.text().setSelection(6);
    drain();
    controller.execute(CommandId.SPLIT_VERTICAL);
    drain();
    List<EditorPane> panes = window.activeEditors();
    assertEquals(2, panes.size());
    SashForm sash = (SashForm) first.text().getParent();
    assertEquals(SWT.HORIZONTAL, sash.getOrientation());
    EditorPane second = panes.get(1);
    assertSame(second, window.activeEditor());
    second.text().setSelection(0);
    drain();
    key(second.text(), 'x');
    drain();
    assertEquals("xfirst\nsecond\nthird\n", first.text().getText());
    assertEquals(7, first.text().getCaretOffset());
    assertEquals(1, second.text().getCaretOffset());
    controller.execute(CommandId.SPLIT_HORIZONTAL);
    assertEquals(SWT.VERTICAL, sash.getOrientation());
    first.text().notifyListeners(SWT.FocusIn, new Event());
    drain();
    assertSame(first, window.activeEditor());
    controller.execute(CommandId.UNSPLIT);
    drain();
    assertEquals(List.of(first), window.activeEditors());
    assertTrue(second.text().isDisposed());
  }

  @Test
  void theFindBarSelectsAndPaintsMatchesInBothViews() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append("line ").append(i).append(" needle and hay\n");
    }
    openFile("find.txt", text.toString());
    controller.execute(CommandId.SPLIT_VERTICAL);
    drain();
    controller.execute(CommandId.FIND);
    drain();
    final FindBar bar = window.findBar();
    assertTrue(bar.control().isVisible());
    bar.field().setText("needle");
    final EditorController editor = controller.documents().activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Selection s = editor.selections().selections().primary();
            return s.start() == 7 && s.end() == 13;
          }
        },
        "first match selected");
    for (final EditorPane pane : window.activeEditors()) {
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              int[] m = pane.matches();
              return m.length >= 4 && m[0] == 7 && m[1] == 13;
            }
          },
          "matches painted");
    }
    EditorPane pane = window.activeEditors().get(0);
    assertMatchBackground(pane, 7 + 22, Palette.LIGHT.findMatch());
    controller.execute(CommandId.THEME_DARK);
    drain();
    assertMatchBackground(pane, 7 + 22, Palette.DARK.findMatch());
    Event escape = new Event();
    escape.keyCode = SWT.ESC;
    bar.field().notifyListeners(SWT.KeyDown, escape);
    drain();
    assertFalse(bar.control().isVisible());
    assertEquals(0, pane.matches().length);
  }

  /** Checks the background just inside a match on a row the editor shows. */
  private void assertMatchBackground(EditorPane pane, int offset, Rgb expected) {
    StyledText text = pane.text();
    drain();
    Point size = text.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    boolean printed;
    try {
      printed = text.print(gc);
    } finally {
      gc.dispose();
    }
    // Control.print returns false under GTK 4 (SWT issue 2819: no offscreen draw of a control).
    assumeTrue(printed, "Control.print not supported here");
    ImageData data = image.getImageData();
    image.dispose();
    Point at = text.getLocationAtOffset(offset);
    RGB back = new RGB(expected.red(), expected.green(), expected.blue());
    int found = 0;
    for (int y = at.y; y < at.y + text.getLineHeight(); y++) {
      if (back.equals(data.palette.getRGB(data.getPixel(at.x + 1, y)))) {
        found++;
      }
    }
    assertTrue(found > 0, "match background at " + at);
  }
}
