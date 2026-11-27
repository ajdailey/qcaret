package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
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
 * win32 reads through {@code Control.print} and other platforms skip ({@link ScreenProbe}). The
 * pointer is parked off the window first: a tool bar button's tool tip covers the first tab, and on
 * win32 it keeps the startup theme until a restart.
 */
@Tag("swt")
class TabColorsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Duration STRIP_BOUND = Duration.ofSeconds(5);
  private static final Duration STRIP_READ_INTERVAL = Duration.ofMillis(100);
  private static final double READABLE = 4.5;
  private static final double DARK_LUMINANCE = 0.2;
  private static final double LIGHT_LUMINANCE = 0.5;
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
  // The neutral grays of the dark win32 chrome.
  private static final RGB PANEL = new RGB(0x20, 0x20, 0x20);
  private static final RGB FIELD = new RGB(0x2b, 0x2b, 0x2b);
  private static final int TEXT_TOLERANCE = 16;

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
            },
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    controller.documents().newDocument();
    window.open();
    window.shell().forceActive();
    ScreenProbe.parkPointer(window.shell());
    if (WIN32) {
      screenShowsWindows = ScreenProbe.shows(window.shell());
    } else {
      ScreenProbe.assumeShows(window.shell());
      screenShowsWindows = true;
    }
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

  /**
   * win32 under Dark: the tool bar, the status bar and the Workspace Browser's path field keep the
   * neutral grays under two schemes, and the tab strip takes each scheme's colors.
   */
  @Test
  void darkWin32PanelsKeepTheirGraysAndTheTabsFollowTheScheme() {
    assumeTrue(WIN32, "the win32 chrome");
    open(ThemeMode.DARK);
    controller.execute(CommandId.WORKSPACE_BROWSER);
    for (String scheme : new String[] {EditorSchemes.GRAPHITE, EditorSchemes.NIGHTWATER}) {
      controller.theme().setEditorColors(scheme);
      settle();
      checkPanels(scheme);
    }
    assertTrue(faults.isEmpty(), faults.toString());
  }

  private void checkPanels(String scheme) {
    Palette palette = EditorSchemes.palette(scheme, ThemeMode.DARK, true);
    window.shell().forceActive();
    settle();
    window.shell().forceFocus();
    settle();
    expectGround(scheme + " tool bar", window.toolbarRow().bar(), PANEL);
    expectGround(scheme + " status bar", window.statusBar(), PANEL);
    expectGround(scheme + " path field", window.workspaceDock().bar().path(), FIELD);
    CTabFolder tabs = window.tabs();
    ImageData strip = read(tabs);
    CTabItem selected = tabs.getSelection();
    Rectangle end = tabs.getItem(tabs.getItemCount() - 1).getBounds();
    expect(
        scheme + " strip",
        mode(strip, new Rectangle(end.x + end.width + 8, end.y + 3, 24, end.height - 6)),
        rgb(palette.gutter()));
    for (CTabItem item : tabs.getItems()) {
      if (item != selected) {
        Rectangle tab = inset(item.getBounds());
        expect(scheme + " unselected tab", mode(strip, tab), rgb(palette.gutter()));
        expectText(scheme + " unselected tab", strip, tab, rgb(palette.text()));
      }
    }
    tabs.forceFocus();
    settle();
    strip = read(tabs);
    Rectangle tab = inset(selected.getBounds());
    expect(scheme + " focused selected tab", mode(strip, tab), rgb(palette.background()));
    expectText(scheme + " focused selected tab", strip, tab, rgb(palette.text()));
  }

  private void expectGround(String step, Control control, RGB expected) {
    ImageData data = read(control);
    expect(step, mode(data, inset(new Rectangle(0, 0, data.width, data.height))), expected);
  }

  private void expect(String step, RGB seen, RGB expected) {
    if (Math.abs(seen.red - expected.red) > 2
        || Math.abs(seen.green - expected.green) > 2
        || Math.abs(seen.blue - expected.blue) > 2) {
      faults.add(step + ": " + seen + ", expected " + expected);
    }
  }

  /** Records a fault unless a pixel of the area is within {@link #TEXT_TOLERANCE} of a color. */
  private void expectText(String step, ImageData data, Rectangle area, RGB expected) {
    for (int y = area.y; y < Math.min(data.height, area.y + area.height); y++) {
      for (int x = area.x; x < Math.min(data.width, area.x + area.width); x++) {
        RGB seen = data.palette.getRGB(data.getPixel(x, y));
        if (Math.abs(seen.red - expected.red) <= TEXT_TOLERANCE
            && Math.abs(seen.green - expected.green) <= TEXT_TOLERANCE
            && Math.abs(seen.blue - expected.blue) <= TEXT_TOLERANCE) {
          return;
        }
      }
    }
    faults.add(step + ": no text near " + expected);
  }

  /** Returns the most frequent color in an area of the pixels. */
  private static RGB mode(ImageData data, Rectangle area) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = data.getPixel(Math.max(0, area.x), Math.max(0, area.y));
    for (int y = Math.max(0, area.y); y < Math.min(data.height, area.y + area.height); y++) {
      for (int x = Math.max(0, area.x); x < Math.min(data.width, area.x + area.width); x++) {
        int pixel = data.getPixel(x, y);
        int count = counts.merge(pixel, 1, Integer::sum);
        if (count > counts.getOrDefault(best, 0)) {
          best = pixel;
        }
      }
    }
    return data.palette.getRGB(best);
  }

  private static Rectangle inset(Rectangle area) {
    return new Rectangle(area.x + 3, area.y + 3, area.width - 6, area.height - 6);
  }

  /** Reads a control's pixels from the screen, or through {@code Control.print} on win32. */
  private ImageData read(Control control) {
    control.redraw();
    control.update();
    if (!screenShowsWindows) {
      ImageData printed = ControlPrint.of(display, control);
      assertTrue(printed != null, "print " + control);
      return printed;
    }
    Point at = control.toDisplay(0, 0);
    Point size = control.getSize();
    return capture(new Rectangle(at.x, at.y, size.x, size.y));
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Native chrome follows a switch on GTK only; win32 waits for a restart and cocoa the system. */
  private void checkChrome(String step, boolean dark) {
    assumeTrue("gtk".equals(SWT.getPlatform()), "native chrome follows a switch on GTK only");
    window.shell().forceActive();
    settle();
    window.showWarningMarker(true);
    settle();
    Rectangle shellArea = window.shell().getBounds();
    ImageData data = capture(shellArea);
    StripRead read = new StripRead(new ArrayList<String>());
    inspect(step + " status bar", data, area(window.statusBar(), shellArea), dark, read);
    for (Control child : window.statusBar().getChildren()) {
      if (child instanceof Link) {
        inspect(step + " warnings link", data, area(child, shellArea), dark, read);
      }
    }
    faults.addAll(read.faults());
    assertTrue(faults.isEmpty(), faults.toString());
    assertEquals(
        dark ? Icons.Variant.DARK : Icons.Variant.LIGHT,
        MainWindow.logoVariant(window.shell().getBackground()),
        step);
    assertEquals(dark, window.toolIcons().darkBackground(), step + " toolbar ink");
    assertEquals(dark, window.menuIcons().icons().darkBackground(), step + " menu ink");
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

  /**
   * Reads the strip until every tab passes or {@link #STRIP_BOUND} passes, then records the last
   * read's faults.
   */
  private void inspectStrip(final CTabFolder tabs, final String step, final boolean dark) {
    final StripRead[] last = new StripRead[1];
    final long[] next = new long[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            long now = System.nanoTime();
            if (last[0] != null && now < next[0]) {
              return false;
            }
            next[0] = now + STRIP_READ_INTERVAL.toNanos();
            last[0] = readStrip(tabs, step, dark);
            return last[0].faults().isEmpty();
          }
        },
        STRIP_BOUND);
    faults.addAll(last[0].faults());
  }

  /** The faults one read of the pixels found. */
  private record StripRead(List<String> faults) {}

  private StripRead readStrip(CTabFolder tabs, String step, boolean dark) {
    tabs.redraw();
    tabs.update();
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
    StripRead read = new StripRead(new ArrayList<String>());
    CTabItem selected = tabs.getSelection();
    for (CTabItem item : tabs.getItems()) {
      Rectangle bounds = item.getBounds();
      Rectangle area = new Rectangle(bounds.x + dx, bounds.y + dy, bounds.width, bounds.height);
      inspect(step + (item == selected ? " selected" : " unselected"), data, area, dark, read);
    }
    return read;
  }

  /** Reads a covered folder's pixels on win32, where they equal the screen's (measured). */
  private ImageData print(CTabFolder tabs) {
    Rectangle size = tabs.getBounds();
    Image image = new Image(display, size.width, size.height);
    GC gc = new GC(image);
    try {
      assertTrue(tabs.print(gc), "print");
      return Pixels.read(image, tabs);
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
      return Pixels.read(image, window.shell());
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static void inspect(
      String label, ImageData data, Rectangle area, boolean dark, StripRead read) {
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
      read.faults().add("unreadable " + seen);
    }
    if (dark ? luminance > DARK_LUMINANCE : luminance < LIGHT_LUMINANCE) {
      read.faults().add("wrong theme " + seen);
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
