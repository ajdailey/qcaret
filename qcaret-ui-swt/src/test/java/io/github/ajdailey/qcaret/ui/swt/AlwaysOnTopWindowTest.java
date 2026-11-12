package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** View > Always on Top in a real window: the item's state on this platform and its toggle. */
@Tag("swt")
class AlwaysOnTopWindowTest {
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
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

  @Test
  void theViewMenuItemFollowsThePlatform() {
    MenuItem item = window.menuBar().item(CommandId.ALWAYS_ON_TOP);
    assertSame(window.menuBar().menu(CommandGroup.VIEW), item.getParent());
    assertTrue((item.getStyle() & SWT.CHECK) != 0);
    assertEquals(0, item.getAccelerator());
    boolean supported = WindowStacking.PLATFORM.supported();
    assertEquals(supported, window.onTopSupported());
    assertEquals(supported, item.getEnabled());
    // SWT drops a menu item's tool tip on GTK 4.
    String tip =
        !supported && "gtk".equals(SWT.getPlatform()) && !"1".equals(System.getenv("SWT_GTK4"))
            ? UiText.ALWAYS_ON_TOP_UNSUPPORTED.text()
            : null;
    assertEquals(tip, item.getToolTipText());
  }

  @Test
  void theCommandTogglesTheCheckWhereSupported() {
    MenuItem item = window.menuBar().item(CommandId.ALWAYS_ON_TOP);
    boolean supported = window.onTopSupported();
    item.notifyListeners(SWT.Selection, null);
    assertEquals(supported, item.getSelection());
    assertEquals(supported, controller.alwaysOnTop().saved());
    item.notifyListeners(SWT.Selection, null);
    assertFalse(item.getSelection());
    assertEquals(supported, window.onTopSupported());
  }
}
