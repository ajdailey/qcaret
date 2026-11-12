package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandId;
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
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help > About after a live theme switch each way: the dialog's client area holds its content and
 * every label shows all its lines. Regression: on a user's GTK desktop, About opened after View >
 * Theme System -> Dark too small, its text cut off.
 */
@Tag("swt")
class AboutFitTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  private void open(ThemeMode start) {
    display = new Display();
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    SwtThemeService system = SwtThemeService.open(display, start);
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(start),
            system,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
  }

  @AfterEach
  void close() {
    try {
      if (display != null) {
        display.setDarkThemePreferred(false);
        workers.close();
        display.dispose();
      }
    } finally {
      assertNull(failure.get());
    }
  }

  @Test
  void aboutFitsAfterSystemToDark() {
    switchThenAbout(ThemeMode.SYSTEM, CommandId.THEME_DARK);
  }

  @Test
  void aboutFitsAfterLightToDark() {
    switchThenAbout(ThemeMode.LIGHT, CommandId.THEME_DARK);
  }

  @Test
  void aboutFitsAfterDarkToLight() {
    switchThenAbout(ThemeMode.DARK, CommandId.THEME_LIGHT);
  }

  @Test
  void aboutGrowsBackToItsContentWhenItShowsSmaller() {
    open(ThemeMode.LIGHT);
    AboutInfo info =
        new AboutInfo(
            "0.0.0-mock", "21 (mock)", "Mock OS", "SWT mock", temp, SettingsMode.EXPLICIT);
    AboutDialog about =
        new AboutDialog(
            window.shell(),
            info,
            Icons.Variant.LIGHT,
            new Runnable() {
              @Override
              public void run() {}
            },
            window);
    about.open();
    settle();
    Point full = about.shell().getSize();
    about.shell().setSize(full.x - 120, full.y - 80);
    about.fit();
    settle();
    assertFits(about.shell());
    about.shell().dispose();
  }

  private void switchThenAbout(ThemeMode start, CommandId theme) {
    open(start);
    settle();
    controller.execute(theme);
    settle();
    controller.execute(CommandId.ABOUT);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.shell().getShells().length == 1;
              }
            },
            BOUND));
    settle();
    assertFits(window.shell().getShells()[0]);
  }

  /** Runs the event loop for half a second, so GTK applies a theme change and the window maps. */
  private void settle() {
    final long until = System.nanoTime() + 500_000_000L;
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

  private static void assertFits(Shell dialog) {
    Rectangle area = dialog.getClientArea();
    Point outer = dialog.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    Rectangle trim = dialog.computeTrim(0, 0, 0, 0);
    assertTrue(
        area.width >= outer.x - trim.width && area.height >= outer.y - trim.height,
        "client " + area + " under content " + outer + " less trim " + trim);
    assertLabelsFit(dialog);
  }

  private static void assertLabelsFit(Control control) {
    if (control instanceof Label label && (label.getStyle() & SWT.WRAP) != 0) {
      Rectangle bounds = label.getBounds();
      int needed = label.computeSize(bounds.width, SWT.DEFAULT, true).y;
      assertTrue(bounds.height >= needed, label.getText() + ": " + bounds + " needs " + needed);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        assertLabelsFit(child);
      }
    }
  }
}
