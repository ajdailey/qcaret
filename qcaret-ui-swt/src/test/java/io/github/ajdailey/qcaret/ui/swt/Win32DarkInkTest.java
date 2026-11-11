package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 the main toolbar's and the Workspace Browser's icons stand out from the background the
 * screen shows, under a dark startup setting, after a live theme switch and under a light one. The
 * check reads screen pixels, since {@code getBackground} can differ from what win32 draws: a push
 * button draws a dark face under the dark setting and reports the light window color. Each case
 * saves the window and its measurements to {@code target/diagnostics/win32-dark/}. It is skipped
 * when the screen shows another window, as on the {@code windows-11-arm} runner, whose desktop
 * shows the Windows setup screen.
 */
@Tag("swt")
class Win32DarkInkTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  /** WCAG contrast an enabled icon's strongest pixel keeps on its drawn background. */
  private static final double ICON_CONTRAST = 3.0;

  /** Side of an icon on a 100% screen. */
  private static final int ICON = 16;

  /** Pixels cut from each edge of a control before reading its background, past the border. */
  private static final int INSET = 3;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final StringBuilder report = new StringBuilder();

  private void open(ThemeMode mode) {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 draws push buttons in the dark theme");
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
    SwtThemeService system = SwtThemeService.open(display, mode);
    boolean dark = mode == ThemeMode.DARK;
    window = new MainWindow(display, dark ? Palette.DARK : Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(mode),
            system,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
    controller.execute(CommandId.WORKSPACE_BROWSER);
    window.shell().forceActive();
  }

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

  @Test
  void iconsStandOutUnderADarkStart() {
    open(ThemeMode.DARK);
    assertIconsStandOut("dark");
  }

  @Test
  void iconsStandOutAfterALiveSwitch() {
    open(ThemeMode.DARK);
    controller.execute(CommandId.THEME_LIGHT);
    assertIconsStandOut("dark-then-light");
  }

  @Test
  void iconsStandOutUnderALightStart() {
    open(ThemeMode.LIGHT);
    assertIconsStandOut("light");
  }

  /** Paints the window, saves it and checks every enabled icon against the pixels around it. */
  private void assertIconsStandOut(String name) {
    settle();
    StyledText text = window.activeEditor().text();
    ScreenProbe.assumeShows(window.shell());
    RGB editor = mode(capture(screenArea(text)), INSET);
    assumeTrue(
        editor.equals(text.getBackground().getRGB()),
        "the screen does not show the window: the editor reads " + hex(editor));
    List<String> faint = new ArrayList<String>();
    ToolBar bar = window.mainToolBar().bar();
    for (ToolItem item : bar.getItems()) {
      if (item.getImage() != null) {
        Rectangle area = item.getBounds();
        Point origin = bar.toDisplay(area.x, area.y);
        check(
            "toolbar " + item.getToolTipText(),
            item.getEnabled(),
            new Rectangle(origin.x, origin.y, area.width, area.height),
            faint);
      }
    }
    List<ToolBar> bars = new ArrayList<ToolBar>();
    collect(window.workspaceDock().control(), bars);
    assertFalse(bars.isEmpty(), "no icon tool bars");
    for (ToolBar one : bars) {
      ToolItem item = one.getItem(0);
      check("browser " + item.getToolTipText(), item.getEnabled(), screenArea(one), faint);
    }
    measure("path field", window.workspaceDock().bar().path());
    measure("filter field", window.workspaceDock().bar().filter());
    measure("tree", window.workspaceDock().tree());
    save(name);
    assertTrue(faint.isEmpty(), "faint icons " + faint + "\n" + report);
  }

  /** Records an item's drawn background and icon contrast, and lists an enabled faint one. */
  private void check(String label, boolean enabled, Rectangle area, List<String> faint) {
    ImageData data = capture(area);
    RGB ground = mode(data, INSET);
    double contrast = strongest(data, ground);
    report.append(
        String.format(
            "%s enabled=%s ground=%s contrast=%.2f%n", label, enabled, hex(ground), contrast));
    if (enabled && contrast < ICON_CONTRAST) {
      faint.add(label);
    }
  }

  private void measure(String label, Control control) {
    RGB ground = mode(capture(screenArea(control)), INSET);
    report.append(
        String.format(
            "%s drawn=%s getBackground=%s%n",
            label, hex(ground), hex(control.getBackground().getRGB())));
  }

  /** Runs queued events and paints every pending area. */
  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        BOUND);
    display.update();
  }

  private static Rectangle screenArea(Control control) {
    Point size = control.getSize();
    Point origin = control.toDisplay(0, 0);
    return new Rectangle(origin.x, origin.y, size.x, size.y);
  }

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

  private void save(String name) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-dark"));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {capture(window.shell().getBounds())};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
      Files.writeString(dir.resolve(name + ".txt"), report.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the most frequent color inside the inset. */
  private static RGB mode(ImageData data, int inset) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = inset; y < data.height - inset; y++) {
      for (int x = inset; x < data.width - inset; x++) {
        int pixel = data.getPixel(x, y);
        Integer count = counts.get(pixel);
        int next = count == null ? 1 : count + 1;
        counts.put(pixel, next);
        if (next > bestCount) {
          best = pixel;
          bestCount = next;
        }
      }
    }
    return data.palette.getRGB(best);
  }

  /** Returns the highest contrast with the ground of a pixel in the centered icon square. */
  private static double strongest(ImageData data, RGB ground) {
    int left = Math.max(0, (data.width - ICON) / 2);
    int top = Math.max(0, (data.height - ICON) / 2);
    double max = 1;
    for (int y = top; y < Math.min(data.height, top + ICON); y++) {
      for (int x = left; x < Math.min(data.width, left + ICON); x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        max = Math.max(max, SwtThemeService.contrast(rgb, ground));
      }
    }
    return max;
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }

  private static void collect(Composite parent, List<ToolBar> bars) {
    for (Control child : parent.getChildren()) {
      if (child instanceof ToolBar bar) {
        bars.add(bar);
      } else if (child instanceof Composite composite) {
        collect(composite, bars);
      }
    }
  }
}
