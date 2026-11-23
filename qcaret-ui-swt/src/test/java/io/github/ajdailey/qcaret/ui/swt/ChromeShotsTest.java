package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
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
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes screenshots of the main window, light and dark, with {@link ChromeColors} for win32 traits
 * on the running platform's widgets, to the directory {@code qcaret.rm.out} names: the window and
 * the toolbar row alone. Skipped without it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
class ChromeShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(120);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path out;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    out = Files.createDirectories(Path.of(System.getProperty("qcaret.rm.out")));
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
    window.place(new WindowGeometry.Request(null, null, 1200, 700), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
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

  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "settled");
  }

  private void save(Shell shell, String name, String rowName) {
    settle();
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageData data = image.getImageData();
      write(data, name);
      // The print holds the menu bar above the client area.
      Rectangle row = window.toolbarRow().row().getBounds();
      row.y += size.y - shell.getClientArea().height;
      Image crop = new Image(display, row.width, row.height);
      GC cropGc = new GC(crop);
      try {
        cropGc.drawImage(image, row.x, row.y, row.width, row.height, 0, 0, row.width, row.height);
        write(crop.getImageData(), rowName);
      } finally {
        cropGc.dispose();
        crop.dispose();
      }
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void write(ImageData data, String name) {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    loader.save(out.resolve(name).toString(), SWT.IMAGE_PNG);
  }

  @Test
  void screenshots() throws Exception {
    Path file =
        Files.writeString(temp.resolve("notes.txt"), "Mock text.\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().loading() == 0 && window.activeEditor() != null;
          }
        },
        "open");
    controller.execute(CommandId.FIND);
    ChromeColors chrome = new ChromeColors(PlatformTraits.of("win32", false), display);
    chrome.add(window.toolbarRow().row());
    chrome.add(window.statusBar());
    chrome.add(window.findBar().control());
    chrome.add(window.toolIcons());
    save(window.shell(), "chrome-win32-forced-light.png", "toolbar-win32-forced-light.png");
    controller.execute(CommandId.THEME_DARK);
    chrome.apply(true);
    save(window.shell(), "chrome-win32-forced-dark.png", "toolbar-win32-forced-dark.png");
  }
}
