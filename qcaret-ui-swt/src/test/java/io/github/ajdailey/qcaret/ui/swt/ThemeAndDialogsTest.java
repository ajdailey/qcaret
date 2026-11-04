package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The theme, the menus and the dialogs of the main window, under a display. */
@Tag("swt")
class ThemeAndDialogsTest {
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
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    SwtThemeService system = SwtThemeService.open(display, ThemeMode.LIGHT);
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            system,
            new MemoryBudget(1L << 30, 60),
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

  private MenuItem item(CommandId command) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      MenuItem found = find(header, command);
      if (found != null) {
        return found;
      }
    }
    return null;
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

  @Test
  void theThemeMenuSwitchesThePaletteAndTheCheck() {
    MenuItem dark = item(CommandId.THEME_DARK);
    assertNotNull(dark);
    assertTrue(item(CommandId.THEME_LIGHT).getSelection());
    dark.setSelection(true);
    dark.notifyListeners(SWT.Selection, null);
    assertSame(Palette.DARK, window.palette());
    assertEquals(
        new Color(0x1e, 0x1e, 0x1e).getRGB(), window.activeText().getBackground().getRGB());
    assertTrue(dark.getSelection());
    assertFalse(item(CommandId.THEME_LIGHT).getSelection());
  }

  @Test
  void toolIconsTakeTheOtherInkWhenTheOwnerTurnsDark() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    bar.setBackground(new Color(240, 240, 240));
    ToolIcons icons = new ToolIcons(bar);
    ToolItem item = new ToolItem(bar, SWT.PUSH);
    assertTrue(icons.apply(item, ToolIcons.OPEN));
    Image light = item.getImage();
    assertFalse(icons.darkBackground());
    icons.reink();
    assertSame(light, item.getImage());
    bar.setBackground(new Color(30, 30, 30));
    icons.reink();
    assertTrue(icons.darkBackground());
    assertNotSame(light, item.getImage());
    assertTrue(light.isDisposed());
    shell.dispose();
  }

  @Test
  void openReportsWhenASwitchWaitsForRestart() {
    SwtThemeService service = SwtThemeService.open(display, ThemeMode.DARK);
    assertTrue(service.showsNow(true));
    assertEquals("win32".equals(SWT.getPlatform()), !service.showsNow(false));
  }

  @Test
  void theAboutLogoFollowsTheDialogBackground() {
    assertSame(Icons.Variant.DARK, MainWindow.logoVariant(new Color(30, 30, 30)));
    assertSame(Icons.Variant.LIGHT, MainWindow.logoVariant(new Color(240, 240, 240)));
    assertNotNull(Icons.logo(display, Icons.Variant.LIGHT, AboutDialog.LOGO_SIZE));
    assertEquals(7, Icons.load(display, Icons.Variant.DARK).size());
  }

  @Test
  void textOverAModalDialogOpensModalAndOwnedByIt() {
    Shell about = Dialogs.shell(window.shell(), "About", true);
    try {
      Dialogs.open(about, SWT.DEFAULT, SWT.DEFAULT);
      window.showText("Licenses", "mock license text");
      Shell[] owned = about.getShells();
      assertEquals(1, owned.length);
      assertTrue((owned[0].getStyle() & SWT.APPLICATION_MODAL) != 0);
      assertTrue(owned[0].isVisible());
    } finally {
      about.dispose();
    }
  }

  @Test
  void aboutOpensModalAndLicensesOpenOverIt() {
    controller.execute(CommandId.ABOUT);
    final Shell[] about = window.shell().getShells();
    assertEquals(1, about.length);
    assertTrue((about[0].getStyle() & SWT.APPLICATION_MODAL) != 0);
    controller.showLicenses();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return about[0].getShells().length == 1;
              }
            },
            BOUND));
    assertTrue((about[0].getShells()[0].getStyle() & SWT.APPLICATION_MODAL) != 0);
  }

  @Test
  void withoutTheApplicationMenuTheMenuBarKeepsAboutAndExit() {
    assumeFalse(AppMenu.present(display), "Cocoa always has the application menu");
    assertSame(CommandId.ABOUT, AppMenu.command(SWT.ID_ABOUT));
    assertSame(CommandId.QUIT, AppMenu.command(SWT.ID_QUIT));
    assertNull(AppMenu.command(SWT.ID_PREFERENCES));
    assertNull(AppMenu.command(SWT.ID_HIDE));
    assertNotNull(item(CommandId.ABOUT));
    assertNotNull(item(CommandId.QUIT));
  }
}
