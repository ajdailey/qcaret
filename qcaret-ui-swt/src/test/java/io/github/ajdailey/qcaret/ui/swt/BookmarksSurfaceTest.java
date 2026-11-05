package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bookmark comments in the gutter and the Bookmarks list dock: a commented bookmark's color in both
 * themes, its tooltip, the comment prompt, the list's rows and buttons, and, with {@code
 * -Dqcaret.screenshots=<dir>}, the gutter with a tooltip and the open list as PNG.
 */
@Tag("swt")
class BookmarksSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private static final String TEXT =
      "/** Mock totals. */\n"
          + "final class Totals {\n"
          + "  private int total;\n"
          + "\n"
          + "  void add(int value) {\n"
          + "    total += value;\n"
          + "  }\n"
          + "\n"
          + "  int total() {\n"
          + "    return total;\n"
          + "  }\n"
          + "}\n";

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
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
    window.place(new WindowGeometry.Request(0, 0, 900, 680), null);
    window.open();
    Path file = Files.writeString(temp.resolve("Totals.java"), TEXT, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    drain();
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

  private EditorPane pane() {
    return window.activeEditor();
  }

  private void caretAt(int line) {
    EditorController editor = controller.documents().activeEditor();
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(line)));
  }

  /** Runs Edit Bookmark Comment on a line and answers the prompt with OK. */
  private void comment(int line, String text) {
    caretAt(line);
    controller.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    BookmarkCommentDialog dialog = window.commentDialog();
    dialog.field().setText(text);
    dialog.ok().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
    drain();
  }

  /** Bookmarks line 1 plainly and line 4 with a comment. */
  private void bookmarks() {
    caretAt(1);
    controller.execute(CommandId.TOGGLE_BOOKMARK);
    comment(4, "Adds a mock value");
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  /** Reports whether a line's first row holds a color in the gutter left of the fold markers. */
  private boolean gutterHas(ImageData data, int line, Rgb color) {
    StyledText text = pane().text();
    RGB wanted = rgb(color);
    int top = text.getLinePixel(line);
    for (int y = top; y < top + text.getLineHeight(); y++) {
      for (int x = 0; x < pane().foldColumn(); x++) {
        if (wanted.equals(data.palette.getRGB(data.getPixel(x, y)))) {
          return true;
        }
      }
    }
    return false;
  }

  /** Prints the pane's widget; skips the test where {@code Control.print} is not supported. */
  private ImageData print() {
    StyledText text = pane().text();
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
    try {
      assumeTrue(printed, "Control.print not supported here");
      return image.getImageData();
    } finally {
      image.dispose();
    }
  }

  @Test
  void aCommentedBookmarkHasItsOwnColorInBothThemesAndItsCommentAsTooltip() {
    bookmarks();
    StyledText text = pane().text();
    Event move = new Event();
    move.x = 2;
    move.y = text.getLinePixel(4) + 2;
    text.notifyListeners(SWT.MouseMove, move);
    assertEquals("Adds a mock value", text.getToolTipText());
    move.y = text.getLinePixel(1) + 2;
    text.notifyListeners(SWT.MouseMove, move);
    assertNull(text.getToolTipText());
    move.y = text.getLinePixel(4) + 2;
    move.x = pane().foldColumn() + 1;
    text.notifyListeners(SWT.MouseMove, move);
    assertNull(text.getToolTipText());

    ImageData light = print();
    assertTrue(gutterHas(light, 1, Palette.LIGHT.caret()), "plain bookmark");
    assertTrue(gutterHas(light, 4, Palette.LIGHT.bookmarkComment()), "commented bookmark");
    assertTrue(!gutterHas(light, 4, Palette.LIGHT.caret()), "commented bookmark in caret color");
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gutterHas(print(), 4, Palette.DARK.bookmarkComment());
          }
        },
        "dark comment color");
    assertTrue(gutterHas(print(), 1, Palette.DARK.caret()), "dark plain bookmark");
  }

  @Test
  void theListShowsRowsAndItsButtonsActOnTheSelectedRow() {
    bookmarks();
    controller.execute(CommandId.BOOKMARKS_LIST);
    final BookmarksDock dock = window.bookmarksDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.bookmarksShown() && dock.table().getItemCount() == 2;
          }
        },
        "two rows");
    assertTrue(dock.summary().startsWith("Totals.java"), dock.summary());
    assertArrayEquals(new String[] {"2", "", "final class Totals {"}, dock.cells(0));
    assertArrayEquals(
        new String[] {"5", "Adds a mock value", "  void add(int value) {"}, dock.cells(1));

    caretAt(0);
    dock.press(1, UiText.BOOKMARKS_GO_TO);
    EditorController editor = controller.documents().activeEditor();
    assertEquals(
        editor.document().text().offsetOfLine(4),
        editor.selections().selections().primary().caret());

    dock.press(1, UiText.BOOKMARKS_EDIT);
    BookmarkCommentDialog dialog = window.commentDialog();
    assertEquals("Adds a mock value", dialog.field().getText());
    dialog.field().setText("Adds a value");
    dialog.ok().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.table().getItemCount() == 2 && dock.cells(1)[1].equals("Adds a value");
          }
        },
        "edited comment");

    dock.press(0, UiText.BOOKMARKS_REMOVE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.table().getItemCount() == 1;
          }
        },
        "removed row");
    dock.press(0, UiText.BOOKMARKS_HIDE);
    assertTrue(!window.bookmarksShown());
    controller.execute(CommandId.SEARCH_RESULTS);
    drain();
    assertTrue(window.resultsShown() && !window.bookmarksShown());
    controller.execute(CommandId.BOOKMARKS_LIST);
    drain();
    assertTrue(window.resultsShown() && window.bookmarksShown());
  }

  /**
   * Saves the gutter with a commented bookmark's tooltip ({@code bookmark-tooltip.png}) and the
   * open Bookmarks list ({@code bookmarks-list.png}) under the property's dir.
   */
  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    bookmarks();
    comment(9, "Read by the mock report");
    StyledText text = pane().text();
    text.setFocus();
    caretAt(2);
    drain();
    Point at = text.toDisplay(3, text.getLinePixel(4) + text.getLineHeight() / 2);
    Event move = new Event();
    move.type = SWT.MouseMove;
    move.x = at.x;
    move.y = at.y;
    display.post(move);
    move.x = at.x + 1;
    display.post(move);
    pause(1500);
    capture(dir, "bookmark-tooltip", true);
    move.x = at.x + 400;
    display.post(move);
    controller.execute(CommandId.BOOKMARKS_LIST);
    final BookmarksDock dock = window.bookmarksDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.table().getItemCount() == 3;
          }
        },
        "three rows");
    dock.table().setSelection(1);
    dock.table().notifyListeners(SWT.Selection, new Event());
    dock.table().setTopIndex(0);
    capture(dir, "bookmarks-list", false);
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

  /** Copies the shell's area, or the whole screen, to a PNG. */
  private void capture(String dir, String name, boolean screen) {
    pause(500);
    Rectangle bounds = screen ? display.getBounds() : window.shell().getBounds();
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
