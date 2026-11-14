package io.github.ajdailey.qcaret.ui.swt;

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
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A window opened with the {@code ui.*} defaults shows them from its first paint. */
@Tag("swt")
class ViewDefaultsSurfaceTest {
  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @AfterEach
  void close() {
    try {
      if (workers != null) {
        workers.close();
      }
      if (display != null) {
        display.dispose();
      }
    } finally {
      assertNull(failure.get());
    }
  }

  private MainWindow open(ViewDefaults view) {
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
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withState(Preferences.DEFAULTS.state().withView(view)),
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys(), PlatformTraits.PLATFORM.linuxKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
    return window;
  }

  @Test
  void theBrowserAndWrapShowAndLineNumbersHideAsTheDefaultsSay() {
    MainWindow window = open(new ViewDefaults(true, false, false, true, false, true));
    assertTrue(window.workspaceShown());
    window.menuBar().sync(CommandGroup.VIEW);
    assertTrue(window.menuBar().item(CommandId.WORKSPACE_BROWSER).getSelection());
    assertTrue(window.menuBar().item(CommandId.WORD_WRAP).getSelection());
    assertFalse(window.menuBar().item(CommandId.LINE_NUMBERS).getSelection());
  }

  @Test
  void theBuiltInDefaultsKeepTheBrowserHidden() {
    MainWindow window = open(ViewDefaults.DEFAULTS);
    assertFalse(window.workspaceShown());
    window.menuBar().sync(CommandGroup.VIEW);
    assertTrue(window.menuBar().item(CommandId.LINE_NUMBERS).getSelection());
    assertFalse(window.menuBar().item(CommandId.WORD_WRAP).getSelection());
  }
}
