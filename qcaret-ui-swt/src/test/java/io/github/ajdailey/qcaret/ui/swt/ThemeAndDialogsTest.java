package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
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
            Keymap.defaults(SwtKeys.mac()),
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
        new Color(
                Palette.DARK.background().red(),
                Palette.DARK.background().green(),
                Palette.DARK.background().blue())
            .getRGB(),
        window.activeText().getBackground().getRGB());
    assertTrue(dark.getSelection());
    assertFalse(item(CommandId.THEME_LIGHT).getSelection());
  }

  @Test
  void theTabStripTakesThePaletteAndFollowsASwitch() {
    controller.documents().newDocument();
    assertEquals(2, window.tabs().getItemCount());
    assertTabColors(Palette.LIGHT);
    pick(CommandId.THEME_DARK);
    assertTabColors(Palette.DARK);
    pick(CommandId.THEME_LIGHT);
    assertTabColors(Palette.LIGHT);
  }

  private void pick(CommandId theme) {
    MenuItem item = item(theme);
    item.setSelection(true);
    item.notifyListeners(SWT.Selection, null);
  }

  /** Reads the selected tab with focus inside the folder and outside it, and the unselected tab. */
  private void assertTabColors(Palette palette) {
    CTabFolder tabs = window.tabs();
    CTabItem selected = tabs.getSelection();
    CTabItem other = tabs.getItem(tabs.getSelectionIndex() == 0 ? 1 : 0);
    tabs.notifyListeners(SWT.Activate, new Event());
    ImageData focused = printed(tabs);
    // The print read black on the windows-11-arm runner, whose desktop is covered.
    Rectangle last = tabs.getItem(tabs.getItemCount() - 1).getBounds();
    ScreenProbe.assumeDrawn(
        focused,
        last.x + last.width + 8,
        last.y + last.height / 2,
        tabs.getBackground().getRGB(),
        "the folder's print right of the last tab");
    assertTab(focused, selected, palette.selection(), true);
    assertTab(focused, other, palette.gutter(), false);
    tabs.notifyListeners(SWT.Deactivate, new Event());
    assertTab(printed(tabs), selected, palette.gutter(), true);
  }

  private static ImageData printed(CTabFolder tabs) {
    Image image = new Image(tabs.getDisplay(), tabs.getSize().x, tabs.getSize().y);
    GC gc = new GC(image);
    try {
      assumeTrue(tabs.print(gc), "Control.print not supported here");
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Checks a tab's background, its label's contrast and, on the selected tab, the close button. */
  private static void assertTab(ImageData data, CTabItem item, Rgb back, boolean close) {
    Rectangle bounds = item.getBounds();
    RGB seen = data.palette.getRGB(data.getPixel(bounds.x + 2, bounds.y + bounds.height / 2));
    assertTrue(
        Math.abs(seen.red - back.red()) <= 8
            && Math.abs(seen.green - back.green()) <= 8
            && Math.abs(seen.blue - back.blue()) <= 8,
        item.getText() + " background " + seen + ", wanted " + back);
    int closeLeft = bounds.x + bounds.width - 16;
    double label = contrast(data, bounds, bounds.x + 2, closeLeft, seen);
    assertTrue(label >= 4.5, item.getText() + " label contrast " + label);
    if (close) {
      double button = contrast(data, bounds, closeLeft, bounds.x + bounds.width - 2, seen);
      assertTrue(button >= 3, item.getText() + " close button contrast " + button);
    }
  }

  /** Returns the highest WCAG 2 contrast ratio against a background in a column range of a tab. */
  private static double contrast(ImageData data, Rectangle tab, int left, int right, RGB back) {
    double best = 1;
    double base = luminance(back);
    for (int y = tab.y + 3; y < tab.y + tab.height - 1; y++) {
      for (int x = left; x < right; x++) {
        double ink = luminance(data.palette.getRGB(data.getPixel(x, y)));
        best = Math.max(best, (Math.max(ink, base) + 0.05) / (Math.min(ink, base) + 0.05));
      }
    }
    return best;
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * channel(rgb.red) + 0.7152 * channel(rgb.green) + 0.0722 * channel(rgb.blue);
  }

  private static double channel(int value) {
    double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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
  void checkBoxUnderTheDarkStartupSettingIsAnEnabledCheckBox() {
    Shell shell = new Shell(display);
    try {
      SwtThemeService.open(display, ThemeMode.DARK);
      Button check = SwtThemeService.check(shell);
      assertTrue((check.getStyle() & SWT.CHECK) != 0);
      assertTrue(check.getEnabled());
    } finally {
      SwtThemeService.open(display, ThemeMode.LIGHT);
      shell.dispose();
    }
  }

  @Test
  void refreshReportsASystemChangeOnce() {
    final boolean[] setting = {false};
    SwtThemeService service =
        new SwtThemeService(
            display,
            false,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return setting[0];
              }
            });
    final List<Boolean> changes = new ArrayList<Boolean>();
    service.addListener(
        new ThemeListener() {
          @Override
          public void systemThemeChanged(boolean dark) {
            changes.add(dark);
          }
        });
    service.refresh();
    assertEquals(List.of(), changes);
    setting[0] = true;
    service.refresh();
    service.refresh();
    assertEquals(List.of(true), changes);
    assertTrue(service.systemDark());
  }

  @Test
  void cocoaChromeFollowsAnAppearanceThatDiffersFromTheStartupOne() {
    RGB windowColor = new RGB(0x32, 0x32, 0x32);
    Shell shell = new Shell(display);
    Composite row = new Composite(shell, SWT.NONE);
    CTabFolder folder = new CTabFolder(shell, SWT.NONE);
    Text field = new Text(shell, SWT.SINGLE);
    RGB before = row.getBackground().getRGB();
    RGB fieldBefore = field.getBackground().getRGB();
    final int[] settings = {0};
    row.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            settings[0]++;
          }
        });
    CocoaChrome chrome = new CocoaChrome(display, false);
    try {
      chrome.apply(true);
      assertEquals(windowColor, row.getBackground().getRGB());
      assertEquals(new RGB(0xff, 0xff, 0xff), folder.getForeground().getRGB());
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), field.getBackground().getRGB());
      assertEquals(1, settings[0]);
      Shell shown = new Shell(display);
      Composite shownRow = new Composite(shown, SWT.NONE);
      shown.open();
      assertEquals(windowColor, shownRow.getBackground().getRGB());
      shown.dispose();
    } finally {
      chrome.apply(false);
    }
    assertEquals(before, row.getBackground().getRGB());
    assertEquals(fieldBefore, field.getBackground().getRGB());
    assertEquals(2, settings[0]);
    shell.dispose();
  }

  /** The editor, the tab strip and the palette's owners keep their colors; the tree recolors. */
  @Test
  void cocoaChromeLeavesPaletteColoredControlsAloneAndColorsTheWorkspaceTree() {
    RGB list = new RGB(0x1e, 0x1e, 0x1e);
    RGB editor = window.activeText().getBackground().getRGB();
    RGB strip = window.tabs().getForeground().getRGB();
    RGB selected = window.tabs().getSelectionBackground().getRGB();
    CocoaChrome chrome = new CocoaChrome(display, false);
    try {
      chrome.apply(true);
      assertEquals(editor, window.activeText().getBackground().getRGB());
      assertEquals(strip, window.tabs().getForeground().getRGB());
      assertEquals(selected, window.tabs().getSelectionBackground().getRGB());
      assertEquals(list, window.workspaceDock().tree().getBackground().getRGB());
      chrome.apply(false);
      assertEquals(editor, window.activeText().getBackground().getRGB());
      assertEquals(strip, window.tabs().getForeground().getRGB());
    } finally {
      chrome.apply(false);
    }
  }

  /** The docks replace their tables while the window is open, with no {@code SWT.Show}. */
  @Test
  void cocoaChromeColorsAControlCreatedWhileTheAppearanceDiffers() {
    CocoaChrome chrome = new CocoaChrome(display, false);
    Shell shell = new Shell(display);
    try {
      chrome.apply(true);
      final Table table = new Table(shell, SWT.NONE);
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return new RGB(0x1e, 0x1e, 0x1e).equals(table.getBackground().getRGB());
                }
              },
              BOUND));
    } finally {
      chrome.apply(false);
      shell.dispose();
    }
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
  void theReplacePromptReportsEveryEndThatIsNotReplace() {
    final List<String> ends = new ArrayList<String>();
    Runnable replace =
        new Runnable() {
          @Override
          public void run() {
            ends.add("replace");
          }
        };
    Runnable declined =
        new Runnable() {
          @Override
          public void run() {
            ends.add("declined");
          }
        };
    ReplaceOpenDialog dialog = new ReplaceOpenDialog(window.shell(), "mock", replace, declined);
    dialog.open();
    dialog.shell().close();
    assertEquals(List.of("declined"), ends);
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
    final Shell[] about = window.shell().getShells();
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
    assertSame(CommandId.PREFERENCES, AppMenu.command(SWT.ID_PREFERENCES));
    assertNull(AppMenu.command(SWT.ID_HIDE));
    assertNotNull(item(CommandId.ABOUT));
    assertNotNull(item(CommandId.QUIT));
  }
}
