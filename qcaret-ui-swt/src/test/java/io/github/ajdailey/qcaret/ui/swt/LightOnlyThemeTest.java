package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main window under a GTK theme with no dark variant: native chrome stays light under the dark
 * palette, so the ink and the About logo stay those of a light background. Runs in the module's
 * {@code light-only-gtk-theme} surefire execution, whose settings name a theme that is not
 * installed, and skips under any other settings.
 */
@Tag("swt")
class LightOnlyThemeTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @AfterEach
  void close() {
    if (display != null) {
      display.setDarkThemePreferred(false);
      workers.close();
      display.dispose();
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void aDarkPaletteKeepsDarkInkAndTheLightLogoOnLightChrome() {
    String settings = System.getenv("XDG_CONFIG_HOME");
    assumeTrue(settings != null && settings.endsWith("light-only"), "another surefire execution");
    assumeTrue("gtk".equals(SWT.getPlatform()), "only GTK reads the settings file");
    assumeTrue(!"1".equals(System.getenv("SWT_GTK4")), "the settings file is GTK 3's");
    open();
    settle();
    ScreenProbe.assumeShows(window.shell());
    check("dark start", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    check("switch to light", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    check("switch to dark", true);
  }

  private void open() {
    display = new Display();
    SwtThemeService system = SwtThemeService.open(display, ThemeMode.DARK);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    window = new MainWindow(display, Palette.DARK);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(ThemeMode.DARK),
            system,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
  }

  private void check(String step, boolean darkPalette) {
    assertEquals(darkPalette, ToolIcons.dark(window.tabs().getBackground()), step + " palette");
    assertFalse(ToolIcons.dark(drawnStatusBar()), step + " drawn chrome");
    assertFalse(window.toolIcons().darkBackground(), step + " toolbar ink");
    assertFalse(window.menuIcons().icons().darkBackground(), step + " menu ink");
    assertEquals(Icons.Variant.LIGHT, MainWindow.logoVariant(window.shell().getBackground()), step);
  }

  /** Reads the status bar from the screen, independently of {@code ToolIcons}. */
  private Color drawnStatusBar() {
    Point corner = window.statusBar().toDisplay(2, 2);
    Image image = new Image(display, 1, 1);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, corner.x, corner.y);
      ImageData data = image.getImageData();
      return new Color(data.palette.getRGB(data.getPixel(0, 0)));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Runs the event loop for a fixed turn: nothing signals that GTK finished restyling. */
  private void settle() {
    final long end = System.nanoTime() + Duration.ofMillis(500).toNanos();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() >= end;
          }
        },
        BOUND);
  }
}
