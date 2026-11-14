package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MemoryFields;
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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The status bar's memory indicator in a real window: View > Memory Indicator, its place and
 * figures, and a click's garbage collection. With {@code -Dqcaret.rm.out=<dir>} it also writes the
 * window with the indicator shown, light and dark, with this JVM's figures and with a 40% fill.
 */
@Tag("swt")
class MemoryGaugeTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private ConfigStore config;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    Files.createDirectories(temp.resolve("conf"));
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
    config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf")));
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            config,
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 900, 500), null);
    window.open();
    drain();
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

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void awaitFigures() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.memoryGauge().text().matches("\\d+/\\d+ MB");
          }
        },
        "figures shown");
  }

  @Test
  void viewMemoryIndicatorShowsTheFiguresAtTheStatusBarsEndAndSavesTheChoice() {
    final Canvas canvas = window.memoryGauge().control();
    assertFalse(canvas.getVisible());
    MenuItem toggle = window.menuBar().item(CommandId.MEMORY_INDICATOR);
    window.menuBar().sync(CommandGroup.VIEW);
    assertFalse(toggle.getSelection());
    toggle.notifyListeners(SWT.Selection, new Event());
    drain();
    assertTrue(canvas.getVisible());
    awaitFigures();
    drain();
    long max = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    assertTrue(window.memoryGauge().text().endsWith("/" + max + " MB"));
    assertTrue(canvas.getToolTipText().contains("Maximum heap: " + max + " MB"));
    assertTrue(canvas.getSize().x > canvas.getSize().y, canvas.getSize().toString());
    for (Control field : window.statusBar().getChildren()) {
      if (field != canvas && field.getVisible()) {
        assertTrue(field.getBounds().x < canvas.getBounds().x, field + " " + field.getBounds());
      }
    }
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return config.load().state().memoryIndicator();
          }
        },
        "saved");
    controller.execute(CommandId.MEMORY_INDICATOR);
    drain();
    assertFalse(canvas.getVisible());
  }

  @Test
  void aClickRequestsACollectionAndShowsTheHeapFreed() {
    controller.execute(CommandId.MEMORY_INDICATOR);
    awaitFigures();
    Event click = new Event();
    click.button = 1;
    click.x = 2;
    click.y = 2;
    window.memoryGauge().control().notifyListeners(SWT.MouseUp, click);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.noticeText().startsWith("Garbage collection freed ");
          }
        },
        "notice");
  }

  @Test
  void theBarFillsItsShareInAMixOfTheTextColor() {
    MemoryGauge gauge = window.memoryGauge();
    gauge.setShown(true);
    gauge.show(new MemoryFields("1/4 MB", "tip", 0.25));
    assertEquals(25, gauge.filledWidth(100));
    assertEquals("tip", gauge.control().getToolTipText());
    assertEquals(
        new RGB(70, 85, 100), MemoryGauge.mix(new RGB(100, 100, 100), new RGB(0, 50, 100), 30));
  }

  @Test
  @EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
  void screenshots() throws Exception {
    Path out = Files.createDirectories(Path.of(System.getProperty("qcaret.rm.out")));
    Path file = temp.resolve("notes.txt");
    Files.writeString(file, "mock notes\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    controller.execute(CommandId.MEMORY_INDICATOR);
    awaitFigures();
    save(window.shell(), out.resolve("memory-light.png"));
    controller.execute(CommandId.THEME_DARK);
    awaitFigures();
    save(window.shell(), out.resolve("memory-dark.png"));
    controller.execute(CommandId.MEMORY_INDICATOR);
    MemoryGauge gauge = window.memoryGauge();
    gauge.setShown(true);
    gauge.show(new MemoryFields("1638/4096 MB", "", 0.4));
    window.statusBar().layout(true);
    save(window.shell(), out.resolve("memory-fill-dark.png"));
    controller.execute(CommandId.THEME_LIGHT);
    save(window.shell(), out.resolve("memory-fill-light.png"));
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
