package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the user guide's macro images to {@code -Dqcaret.guide.shots=<dir>} ({@code
 * tools/screenshots.sh}): the open Macros menu with three mock saved macros ({@code
 * macros-menu.png}) and the Manage Macros dialog over them with one unreadable file ({@code
 * manage-macros.png}) with a readable one selected, light theme, each copied from the screen. The
 * menu opens from a posted Alt+M, so the run needs GTK 3. With {@code -Dqcaret.rm.out=<dir>} the
 * dark theme's two are written there as well.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.guide.shots", matches = ".+")
class MacroShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;

  @Test
  void macroShots() throws Exception {
    PostedInput.assumeGtk();
    Path guide = Files.createDirectories(Path.of(System.getProperty("qcaret.guide.shots")));
    String review = System.getProperty("qcaret.rm.out", "");
    String files = System.getProperty("qcaret.screenshots.files", "");
    Path dir = files.isEmpty() ? temp : Files.createDirectories(Path.of(files));
    Path text = Files.writeString(dir.resolve("shelf.txt"), "mock shelf one\nmock shelf two\n");
    writeMacros(Files.createDirectories(temp.resolve("conf").resolve(MacroStore.DIRECTORY)));
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
      WindowSet set =
          new WindowSet(
              workers,
              new SwtUiDispatcher(display),
              new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
              Preferences.DEFAULTS,
              SwtThemeService.open(display, ThemeMode.LIGHT),
              new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
              Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
              false);
      window = new MainWindow(display, Palette.LIGHT);
      controller = new ApplicationController(set, window);
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 900, 520), null);
      window.open();
      display.setCursorLocation(new Point(1270, 1010));
      controller.documents().open(List.of(text));
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return controller.documents().activeEditor() != null
                  && controller.keymap().macros().keys().entries().size() == 3;
            }
          });
      shoot(guide.resolve("macros-menu.png"), guide.resolve("manage-macros.png"));
      if (!review.isEmpty()) {
        Path out = Files.createDirectories(Path.of(review));
        controller.execute(CommandId.THEME_DARK);
        shoot(out.resolve("macros-menu-dark.png"), out.resolve("manage-macros-dark.png"));
        controller.execute(CommandId.THEME_LIGHT);
        shoot(out.resolve("macros-menu-light.png"), out.resolve("manage-macros-light.png"));
      }
      controller.documents().dispose();
    } finally {
      display.dispose();
    }
    assertNull(failure.get());
  }

  private static void writeMacros(Path folder) throws Exception {
    String[][] macros = {
      {"Quote Line", "Mod+Shift+1", "command LINE_START\ntype \"> \"\ncommand CARET_DOWN\n"},
      {"Tag Item", "Mod+Shift+2", "command LINE_END\ntype \" [mock]\"\ncommand CARET_DOWN\n"},
      {"Trim Shelf", "", "find \"shelf \" forward case=off word=off regex=off\ntype \"x\"\n"}
    };
    for (String[] macro : macros) {
      String key = macro[1].isEmpty() ? "" : "key " + KeyStroke.parse(macro[1]).stored() + "\n";
      Files.writeString(
          folder.resolve(macro[0] + MacroStore.SUFFIX),
          "qcaret-macro 1\n" + key + macro[2],
          StandardCharsets.UTF_8);
    }
    Files.writeString(folder.resolve("Draft" + MacroStore.SUFFIX), "qcaret-macro 1\nwait 5\n");
  }

  private void shoot(Path menu, Path manage) {
    window.shell().forceActive();
    window.activeText().setFocus();
    settle();
    post(SWT.KeyDown, SWT.ALT, 0);
    post(SWT.KeyDown, 0, 'm');
    post(SWT.KeyUp, 0, 'm');
    post(SWT.KeyUp, SWT.ALT, 0);
    settle();
    Rectangle bounds = window.shell().getBounds();
    capture(new Rectangle(bounds.x, bounds.y, 760, 300), menu);
    post(SWT.KeyDown, SWT.ESC, 0);
    post(SWT.KeyUp, SWT.ESC, 0);
    settle();
    controller.execute(CommandId.MANAGE_MACROS);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            ManageMacrosDialog dialog = window.macroDialogs().manageDialog();
            return dialog != null && !dialog.shell().isDisposed();
          }
        });
    ManageMacrosDialog manager = window.macroDialogs().manageDialog();
    manager.table().setSelection(1);
    manager.table().notifyListeners(SWT.Selection, new Event());
    Shell dialog = manager.shell();
    dialog.forceActive();
    settle();
    capture(dialog.getBounds(), manage);
    dialog.dispose();
    settle();
  }

  private void post(int type, int keyCode, int character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = (char) character;
    assertTrue(display.post(event), "Display.post failed");
  }

  /** Copies an area of the screen to a PNG. */
  private void capture(Rectangle area, Path file) {
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND));
  }

  /** Runs the event loop for a second, so the menu and dialogs reach the screen. */
  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }
}
