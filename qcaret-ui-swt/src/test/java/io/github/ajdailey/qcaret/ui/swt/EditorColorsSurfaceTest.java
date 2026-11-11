package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.PreviewLine;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Preferences editor color scheme on a real window: the editor and the tab strip take the
 * chosen palette side, read from printed pixels, while the theme stays where it is. With {@code
 * -Dqcaret.screenshots=<dir>}, both mixed combinations as PNGs.
 */
@Tag("swt")
class EditorColorsSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
  }

  @AfterEach
  void close() {
    try {
      display.setDarkThemePreferred(false);
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  @Test
  void darkEditorColorsInTheLightThemePaintTheEditorAndTabsAndSwitchBackLive() {
    assertPainted(Palette.LIGHT);
    PreferencesDialog dialog = dialog();
    assertEquals(0, dialog.editorColors().getSelectionIndex());
    Control page = dialog.editorColors();
    while (page != null && page != dialog.folder().getItem(0).getControl()) {
      page = page.getParent();
    }
    assertTrue(page != null, "Editor colors is not on the Editor tab");
    scheme(dialog, EditorSchemes.DARK);
    assertSame(Palette.DARK, window.palette());
    assertEquals(EditorSchemes.DARK, controller.preferences().appearance().editorColors());
    assertSame(ThemeMode.LIGHT, controller.theme().mode());
    assertPainted(Palette.DARK);
    scheme(dialog, EditorSchemes.LIGHT);
    assertPainted(Palette.LIGHT);
    scheme(dialog, EditorSchemes.DARK);
    assertPainted(Palette.DARK);
    dialog.shell().dispose();
  }

  @Test
  void lightEditorColorsKeepTheEditorLightInTheDarkTheme() {
    PreferencesDialog dialog = dialog();
    scheme(dialog, EditorSchemes.LIGHT);
    dialog.shell().dispose();
    pick(CommandId.THEME_DARK);
    assertSame(Palette.LIGHT, window.palette());
    assertPainted(Palette.LIGHT);
    dialog = dialog();
    scheme(dialog, EditorSchemes.FOLLOW);
    assertPainted(Palette.DARK);
    dialog.shell().dispose();
  }

  @Test
  void theReplacePreviewTakesThePaletteNotTheNativeTheme() {
    controller.theme().setEditorColors(EditorSchemes.DARK);
    ReplaceDialog preview =
        new ReplaceDialog(window.shell(), new MockReplace(), window.palette(), "Monospace", 10);
    Rgb back = Palette.DARK.background();
    assertEquals(new RGB(back.red(), back.green(), back.blue()), preview.previewBackground());
    preview.shell().dispose();
  }

  /** A replace preview with no files. */
  private static final class MockReplace implements ReplaceInput {
    @Override
    public String title() {
      return "mock";
    }

    @Override
    public String replacement() {
      return "";
    }

    @Override
    public void setReplacement(String text) {}

    @Override
    public int fileCount() {
      return 0;
    }

    @Override
    public String file(int file) {
      return "";
    }

    @Override
    public boolean fileChecked(int file) {
      return false;
    }

    @Override
    public void setFileChecked(int file, boolean checked) {}

    @Override
    public int matchCount(int file) {
      return 0;
    }

    @Override
    public ResultLabel match(int file, int match) {
      return null;
    }

    @Override
    public boolean matchChecked(int file, int match) {
      return false;
    }

    @Override
    public void setMatchChecked(int file, int match, boolean checked) {}

    @Override
    public void select(int file) {}

    @Override
    public List<PreviewLine> preview() {
      return List.of();
    }

    @Override
    public String summary() {
      return "";
    }

    @Override
    public boolean canApply() {
      return false;
    }

    @Override
    public boolean apply() {
      return false;
    }

    @Override
    public void closed() {}
  }

  private PreferencesDialog dialog() {
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.preferencesDialog() != null
                    && !window.preferencesDialog().shell().isDisposed();
              }
            },
            BOUND));
    return window.preferencesDialog();
  }

  private static void scheme(PreferencesDialog dialog, String name) {
    dialog.editorColors().select(EditorSchemes.names().indexOf(name));
    assertTrue(dialog.apply());
  }

  @Test
  void screenshots() throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    Path file = temp.resolve("Shelf.java");
    Files.writeString(file, SHELF, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() >= 2 && controller.documents().loading() == 0;
              }
            },
            BOUND));
    window.shell().setSize(900, 560);
    PreferencesDialog dialog = dialog();
    scheme(dialog, EditorSchemes.DARK);
    dialog.shell().dispose();
    capture(dir, "editor-colors-dark-in-light-theme");
    dialog = dialog();
    scheme(dialog, EditorSchemes.LIGHT);
    dialog.shell().dispose();
    pick(CommandId.THEME_DARK);
    capture(dir, "editor-colors-light-in-dark-theme");
  }

  private static final String SHELF =
      """
      package mock.shelf;

      /** A mock shelf of items. */
      public final class Shelf {
        private final int capacity;

        // Holds at most capacity items.
        public Shelf(int capacity) {
          this.capacity = capacity;
        }

        public String label() {
          return "mock-shelf-" + capacity;
        }
      }
      """;

  /** Copies the main window from the screen to a PNG after the event loop settles. */
  private void capture(String dir, String name) {
    final long until = System.nanoTime() + 1_000_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
    Rectangle bounds = window.shell().getBounds();
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

  private void pick(CommandId command) {
    MenuItem item = item(command);
    item.setSelection(true);
    item.notifyListeners(SWT.Selection, null);
  }

  private MenuItem item(CommandId command) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      MenuItem found = find(header, command);
      if (found != null) {
        return found;
      }
    }
    throw new AssertionError("no menu item for " + command);
  }

  private static MenuItem find(MenuItem parent, CommandId command) {
    if (parent.getMenu() == null) {
      return parent.getData() == command ? parent : null;
    }
    for (MenuItem child : parent.getMenu().getItems()) {
      MenuItem found = find(child, command);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Reads the editor below its last line and the tab strip right of its last tab. */
  private void assertPainted(Palette palette) {
    StyledText text = window.activeText();
    Rectangle area = text.getClientArea();
    assertNear(palette.background(), pixel(text, area.width - 4, area.height - 4), "editor");
    CTabFolder tabs = window.tabs();
    CTabItem last = tabs.getItem(tabs.getItemCount() - 1);
    Rectangle tab = last.getBounds();
    assertTrue(tab.x + tab.width + 4 < tabs.getSize().x, "no strip right of the last tab");
    assertNear(
        palette.gutter(), pixel(tabs, tab.x + tab.width + 4, tab.y + tab.height / 2), "tabs");
  }

  private static RGB pixel(Control control, int x, int y) {
    Image image = new Image(control.getDisplay(), control.getSize().x, control.getSize().y);
    GC gc = new GC(image);
    try {
      assumeTrue(control.print(gc), "Control.print not supported here");
      ImageData data = image.getImageData();
      return data.palette.getRGB(data.getPixel(x, y));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static void assertNear(Rgb wanted, RGB seen, String what) {
    assertTrue(
        Math.abs(seen.red - wanted.red()) <= 8
            && Math.abs(seen.green - wanted.green()) <= 8
            && Math.abs(seen.blue - wanted.blue()) <= 8,
        what + " " + seen + ", wanted " + wanted);
  }
}
