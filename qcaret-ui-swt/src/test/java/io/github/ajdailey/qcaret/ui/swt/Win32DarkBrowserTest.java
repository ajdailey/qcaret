package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 the Workspace Browser's tree, header and fields take {@link Palette#DARK}'s colors under
 * a dark start and follow live theme switches to light and back.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class Win32DarkBrowserTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      display.setDarkThemePreferred(false);
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private void await(final BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  @Test
  void theBrowserFollowsADarkStartAndLiveSwitches() {
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
    MainWindow window = new MainWindow(display, Palette.DARK);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(ThemeMode.DARK),
            SwtThemeService.open(display, ThemeMode.DARK),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
    controller.execute(CommandId.WORKSPACE_BROWSER);
    final WorkspaceDock dock = window.workspaceDock();
    final Tree tree = dock.tree();
    final RGB dark = rgb(Palette.DARK.background());
    assertEquals(dark, tree.getBackground().getRGB());
    assertEquals(rgb(Palette.DARK.gutter()), tree.getHeaderBackground().getRGB());
    assertEquals(rgb(Palette.DARK.text()), tree.getHeaderForeground().getRGB());
    assertEquals(dark, dock.bar().path().getBackground().getRGB());
    assertEquals(dark, dock.bar().filter().getBackground().getRGB());
    final RGB system = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tree.getBackground().getRGB().equals(system);
          }
        },
        "light tree");
    assertEquals(system, dock.bar().filter().getBackground().getRGB());
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tree.getBackground().getRGB().equals(dark);
          }
        },
        "dark tree again");
    assertEquals(dark, dock.bar().path().getBackground().getRGB());
  }
}
