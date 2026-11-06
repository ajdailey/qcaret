package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tab strip's and status bar's screen pixels across theme changes, with focus outside and
 * inside the folder: each tab is readable and the strip follows the theme. Skipped on GTK 4, where
 * {@code GC.copyArea} from the Display reads white. Where a screen read does not show the window,
 * win32 reads through {@code Control.print} and other platforms fail.
 */
@Tag("swt")
class TabColorsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final double READABLE = 4.5;
  private static final double DARK_LUMINANCE = 0.2;
  private static final double LIGHT_LUMINANCE = 0.5;
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
  private static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);
  private static final int PROBE_TOLERANCE = 8;
  private static final int PROBE_READS = 6;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<String> faults = new ArrayList<String>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private boolean screenShowsWindows;

  private void open(ThemeMode mode) {
    assumeFalse("1".equals(System.getenv("SWT_GTK4")), "GC.copyArea reads white on GTK 4");
    display = new Display();
    final SwtThemeService system = SwtThemeService.open(display, mode);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    window = new MainWindow(display, mode == ThemeMode.DARK ? Palette.DARK : Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(mode),
            new ThemeService() {
              @Override
              public boolean systemDark() {
                return system.systemDark();
              }

              @Override
              public void addListener(ThemeListener listener) {
                system.addListener(listener);
              }

              @Override
              public void preferDark(boolean dark) {
                system.preferDark(dark);
              }

              /** True, so win32 opens no modal restart notice in the test. */
              @Override
              public boolean showsNow(boolean dark) {
                return true;
              }
            },
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    controller.documents().newDocument();
    window.open();
    screenShowsWindows = screenShowsWindows();
    assertTrue(screenShowsWindows || WIN32, "A screen read does not show the main window");
  }

  /**
   * Reports whether a screen read shows the main window, from a child of a known color above its
   * other children, so that no window stacking decides the answer. The windows-11-arm runner's
   * desktop lies under a full-screen first-run page, so a read there returns that page.
   */
  private boolean screenShowsWindows() {
    Composite probe = new Composite(window.shell(), SWT.NONE);
    GridData excluded = new GridData();
    excluded.exclude = true;
    probe.setLayoutData(excluded);
    try {
      probe.setBackground(new Color(PROBE));
      probe.setBounds(100, 100, 64, 64);
      probe.moveAbove(null);
      window.shell().forceActive();
      Point center = probe.toDisplay(32, 32);
      for (int i = 0; i < PROBE_READS; i++) {
        settle();
        ImageData data = capture(new Rectangle(center.x, center.y, 4, 4));
        if (near(data.palette.getRGB(data.getPixel(2, 2)))) {
          return true;
        }
      }
      return false;
    } finally {
      probe.dispose();
    }
  }

  private static boolean near(RGB seen) {
    return Math.abs(seen.red - PROBE.red) <= PROBE_TOLERANCE
        && Math.abs(seen.green - PROBE.green) <= PROBE_TOLERANCE
        && Math.abs(seen.blue - PROBE.blue) <= PROBE_TOLERANCE;
  }

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
  void tabsFollowLightDarkLight() {
    open(ThemeMode.LIGHT);
    check("light-start", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    check("light-then-dark", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    check("light-dark-light", false);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void tabsFollowDarkLightDark() {
    open(ThemeMode.DARK);
    check("dark-start", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    check("dark-then-light", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    check("dark-light-dark", true);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void theStatusBarAndLogoFollowLightDarkLight() {
    open(ThemeMode.LIGHT);
    checkChrome("light-start", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    checkChrome("light-then-dark", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    checkChrome("light-dark-light", false);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void theStatusBarAndLogoFollowDarkLightDark() {
    open(ThemeMode.DARK);
    checkChrome("dark-start", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    checkChrome("dark-then-light", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    checkChrome("dark-light-dark", true);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  /** Native chrome follows a switch on GTK only; win32 waits for a restart and cocoa the system. */
  private void checkChrome(String step, boolean dark) {
    assumeTrue("gtk".equals(SWT.getPlatform()));
    window.shell().forceActive();
    settle();
    window.showWarningMarker(true);
    settle();
    Rectangle shellArea = window.shell().getBounds();
    ImageData data = capture(shellArea);
    inspect(step + " status bar", data, area(window.statusBar(), shellArea), dark);
    for (Control child : window.statusBar().getChildren()) {
      if (child instanceof Link) {
        inspect(step + " warnings link", data, area(child, shellArea), dark);
      }
    }
    assertEquals(
        dark ? Icons.Variant.DARK : Icons.Variant.LIGHT,
        MainWindow.logoVariant(window.shell().getBackground()),
        step);
  }

  private static Rectangle area(Control control, Rectangle shellArea) {
    Point origin = control.toDisplay(0, 0);
    Rectangle size = control.getBounds();
    return new Rectangle(origin.x - shellArea.x, origin.y - shellArea.y, size.width, size.height);
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

  private void check(String step, boolean dark) {
    window.shell().forceActive();
    settle();
    window.shell().forceFocus();
    inspectStrip(window.tabs(), step + " unfocused", dark);
    window.tabs().forceFocus();
    inspectStrip(window.tabs(), step + " focused", dark);
  }

  private void inspectStrip(CTabFolder tabs, String step, boolean dark) {
    tabs.redraw();
    tabs.update();
    settle();
    ImageData data;
    int dx = 0;
    int dy = 0;
    if (screenShowsWindows) {
      Rectangle shellArea = tabs.getShell().getBounds();
      data = capture(shellArea);
      Point origin = tabs.toDisplay(0, 0);
      dx = origin.x - shellArea.x;
      dy = origin.y - shellArea.y;
    } else {
      data = print(tabs);
    }
    CTabItem selected = tabs.getSelection();
    for (CTabItem item : tabs.getItems()) {
      Rectangle bounds = item.getBounds();
      Rectangle area = new Rectangle(bounds.x + dx, bounds.y + dy, bounds.width, bounds.height);
      inspect(step + (item == selected ? " selected" : " unselected"), data, area, dark);
    }
  }

  /** Reads a covered folder's pixels on win32, where they equal the screen's (measured). */
  private ImageData print(CTabFolder tabs) {
    Rectangle size = tabs.getBounds();
    Image image = new Image(display, size.width, size.height);
    GC gc = new GC(image);
    try {
      assertTrue(tabs.print(gc), "print");
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Reads pixels from the screen: on GTK {@code Control.print} draws what the screen does not. */
  private ImageData capture(Rectangle area) {
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void inspect(String label, ImageData data, Rectangle area, boolean dark) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    for (int y = area.y + 3; y < area.y + area.height - 3; y++) {
      for (int x = area.x + 3; x < area.x + area.width - 3; x++) {
        counts.merge(data.getPixel(x, y), 1, Integer::sum);
      }
    }
    RGB ground = null;
    int most = 0;
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > most) {
        most = entry.getValue();
        ground = data.palette.getRGB(entry.getKey());
      }
    }
    double best = 0;
    RGB text = ground;
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      RGB rgb = data.palette.getRGB(entry.getKey());
      double contrast = contrast(rgb, ground);
      if (entry.getValue() >= 3 && contrast > best) {
        best = contrast;
        text = rgb;
      }
    }
    double luminance = luminance(ground);
    String seen = String.format("%s: ground %s text %s contrast %.2f", label, ground, text, best);
    if (best < READABLE) {
      faults.add("unreadable " + seen);
    }
    if (dark ? luminance > DARK_LUMINANCE : luminance < LIGHT_LUMINANCE) {
      faults.add("wrong theme " + seen);
    }
  }

  private static double contrast(RGB a, RGB b) {
    double high = Math.max(luminance(a), luminance(b));
    double low = Math.min(luminance(a), luminance(b));
    return (high + 0.05) / (low + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
