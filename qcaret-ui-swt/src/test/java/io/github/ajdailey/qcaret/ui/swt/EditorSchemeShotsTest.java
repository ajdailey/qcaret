package io.github.ajdailey.qcaret.ui.swt;

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
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
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
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes {@code <scheme>.png} for every editor color scheme to {@code -Dqcaret.scheme.shots=<dir>}:
 * the main window with a Java, a Markdown, an XML and a shell file, tiled two by two. With {@code
 * -Dqcaret.guide.shots=<dir>} it writes the guide's {@code editor-colors.png}, the Editor colors
 * list open, copied from the screen.
 */
@Tag("swt")
class EditorSchemeShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String JAVA =
      """
      package mock.shelf;

      /** Keeps mock items; "BLOCKTERM" is a mock value. */
      @Deprecated
      public final class Shelf {
        private static final int LIMIT = 0x40;
        private final String label = "mock\\tshelf";

        boolean add(String name) {
          if (name == null || LIMIT < 1) {
            return false; // Nothing to add.
          }
          return name.strip().length() > 0;
        }
      }
      """;

  private static final String MARKDOWN =
      """
      # Mock shelf

      A *mock* shelf keeps **items** in order.

      ## Use

      - Run `add` with a [name](https://example.invalid/mock).
      - Keep at most 64 items.
      """;

  private static final String XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <!-- Mock shelf settings -->
      <shelf name="mock" limit="64">
        <item id="1">flagword &amp; more</item>
        <empty/>
      </shelf>
      """;

  private static final String SHELL =
      """
      #!/bin/sh
      # Lists the mock shelf.
      set -eu
      limit=64
      for item in "$@"; do
        if [ "${#item}" -gt "$limit" ]; then
          echo "too long: $item" >&2
          exit 1
        fi
      done
      """;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;

  @Test
  void schemeAndListShots() throws Exception {
    String schemes = System.getProperty("qcaret.scheme.shots", "");
    String guide = System.getProperty("qcaret.guide.shots", "");
    assumeTrue(!schemes.isEmpty() || !guide.isEmpty(), "no shot directory set");
    display = new Display();
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            })) {
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
      controller.loadGrammars();
      window.place(new WindowGeometry.Request(0, 0, 620, 330), null);
      window.open();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return controller.grammars().set() != null;
            }
          });
      if (!schemes.isEmpty()) {
        shootSchemes(Files.createDirectories(Path.of(schemes)));
      }
      if (!guide.isEmpty()) {
        shootList(Files.createDirectories(Path.of(guide)));
      }
      controller.documents().dispose();
    } finally {
      display.dispose();
    }
    assertNull(failure.get());
  }

  private void shootSchemes(Path out) throws Exception {
    String[][] files = {
      {"Shelf.java", JAVA}, {"README.md", MARKDOWN}, {"shelf.xml", XML}, {"list.sh", SHELL}
    };
    for (int i = 0; i < files.length; i++) {
      Path file = temp.resolve(files[i][0]);
      Files.writeString(file, files[i][1], StandardCharsets.UTF_8);
      final int count = i + 1;
      controller.documents().open(List.of(file));
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.tabs().getItemCount() >= count && controller.documents().loading() == 0;
            }
          });
    }
    controller.documents().activeEditor().goTo(5, 3);
    for (String name : EditorSchemes.names()) {
      controller.theme().setEditorColors(name);
      tile(out.resolve(name + ".png"), files.length);
    }
    controller.theme().setMode(ThemeMode.DARK);
    controller.theme().setEditorColors(EditorSchemes.SANDSTONE);
    tile(out.resolve("sandstone-in-dark-theme.png"), files.length);
    controller.theme().setMode(ThemeMode.LIGHT);
    controller.theme().setEditorColors(EditorSchemes.FOLLOW);
  }

  /** Prints the main window once per tab into a two-column grid and writes a PNG. */
  private void tile(Path file, int tabs) {
    Point size = window.shell().getSize();
    Image grid = new Image(display, size.x * 2, size.y * ((tabs + 1) / 2));
    GC gridGc = new GC(grid);
    try {
      for (int tab = 0; tab < tabs; tab++) {
        controller.documents().select(tab);
        settle();
        Image one = new Image(display, size.x, size.y);
        GC gc = new GC(one);
        try {
          window.shell().print(gc);
        } finally {
          gc.dispose();
        }
        gridGc.drawImage(one, (tab % 2) * size.x, (tab / 2) * size.y);
        one.dispose();
      }
      write(grid.getImageData(), file);
    } finally {
      gridGc.dispose();
      grid.dispose();
    }
  }

  /**
   * Opens Preferences on the Editor tab with one mock theme file, drops the Editor colors list and
   * copies the screen.
   */
  private void shootList(Path out) throws Exception {
    Path themes = Files.createDirectories(temp.resolve("conf").resolve("themes"));
    Files.writeString(themes.resolve("harbor.theme"), "light.background = #fdfbf5\n");
    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            PreferencesDialog dialog = window.preferencesDialog();
            return dialog != null
                && dialog.editorColors().getItemCount() > EditorSchemes.names().size();
          }
        });
    PreferencesDialog dialog = window.preferencesDialog();
    // Tall enough that the dropped list lies over the dialog, not the bare screen.
    Point size = dialog.shell().getSize();
    dialog.shell().setBounds(0, 0, size.x, size.y + 400);
    dialog.folder().setSelection(0);
    Combo list = dialog.editorColors();
    assertNotNull(list);
    settle();
    list.setListVisible(true);
    settle();
    Rectangle combo = display.map(list.getParent(), null, list.getBounds());
    Rectangle page = display.map(list.getParent(), null, list.getParent().getClientArea());
    Rectangle screen = display.getBounds();
    int x = page.x;
    int y = Math.max(0, combo.y - 2);
    int width = Math.min(screen.width - x, combo.x + combo.width + 8 - x);
    // A GTK list row is taller than the Combo's item height; the shot ends below the last row.
    int height = Math.min(screen.height - y, combo.height * (list.getItemCount() + 1));
    Image image = new Image(display, width, height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, x, y);
      write(image.getImageData(), out.resolve("editor-colors.png"));
    } finally {
      gc.dispose();
      image.dispose();
    }
    list.setListVisible(false);
    dialog.shell().dispose();
    settle();
  }

  private static void write(ImageData data, Path file) {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    loader.save(file.toString(), SWT.IMAGE_PNG);
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND));
  }

  /** Runs the event loop for half a second, so queued view updates reach the widgets. */
  private void settle() {
    final long until = System.nanoTime() + 500_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }
}
