package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On cocoa a read-only {@link Combo} under {@link CocoaChrome}, pinned to the side opposite the one
 * SWT read its colors under, shows its closed title and every item of its open menu at 4.5
 * contrast, read from the screen. The menu opens through {@code performClick:} on the {@code
 * NSPopUpButton} and closes through {@code cancelTracking} from a timer, which runs while the menu
 * tracks. Each case saves its captures and band readings to {@code
 * target/diagnostics/cocoa-combo-list/}. It pins against item titles drawn {@code #656565} on the
 * dark menu (2.2:1) while the chrome gave the Combo a white foreground. Skipped where the screen
 * does not show the menu.
 */
@Tag("swt")
class CocoaComboListTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final String COCOA = "org.eclipse.swt.internal.cocoa.";

  /** WCAG AA contrast for normal text. */
  private static final double TEXT_CONTRAST = 4.5;

  private static final String[] ITEMS = {
    "Follow theme", "Light", "Dark", "Sandstone", "Nightwater", "Garden Light"
  };

  /** Height the capture allows each menu row; macOS 15 rows are 22 pixels. */
  private static final int ROW = 24;

  private Display display;
  private final StringBuilder report = new StringBuilder();

  @AfterEach
  void close() {
    if (display != null) {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }

  @Test
  void theDarkMenuReadsOverALightStart() {
    check(false, "dark-over-light");
  }

  @Test
  void theLightMenuReadsOverADarkStart() {
    check(true, "light-over-dark");
  }

  private void check(boolean cachedDark, String name) {
    assumeTrue(RunningPlatform.COCOA, "cocoa pop-up button menu");
    display = new Display();
    display.setDarkThemePreferred(!cachedDark);
    CocoaChrome chrome = new CocoaChrome(display, cachedDark, new SwtUiDispatcher(display));
    chrome.apply(!cachedDark);
    Shell shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setLayout(null);
    shell.setBounds(120, 120, 420, 360);
    Combo combo = new Combo(shell, SWT.READ_ONLY);
    combo.setItems(ITEMS);
    combo.select(0);
    combo.setBounds(40, 40, 220, combo.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
    shell.open();
    shell.forceActive();
    settle();
    Point at = combo.toDisplay(0, 0);
    Point size = combo.getSize();
    Rectangle field = new Rectangle(at.x, at.y, size.x, size.y);
    ImageData closed = capture(field);
    report.append("closed\n");
    double title = weakestBand(closed, new Rectangle(8, 2, field.width - 30, field.height - 4));
    save(name + "-closed", closed);
    final Rectangle area =
        new Rectangle(field.x - 60, field.y - 40, field.width + 120, ROW * ITEMS.length + 80);
    final ImageData[] open = {null};
    final long view = viewId(combo);
    display.timerExec(
        900,
        new Runnable() {
          @Override
          public void run() {
            open[0] = capture(area);
            send(send(view, "menu", 0), "cancelTracking", 0);
          }
        });
    display.timerExec(
        5000,
        new Runnable() {
          @Override
          public void run() {
            send(send(view, "menu", 0), "cancelTracking", 0);
          }
        });
    send(view, "performClick:", 0);
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return open[0] != null;
          }
        },
        BOUND);
    assumeTrue(open[0] != null, "the menu did not open");
    report.append("open\n");
    int bands = report.length();
    double items =
        weakestBand(
            open[0], new Rectangle(field.x - area.x + 14, 0, field.width - 40, open[0].height));
    save(name + "-open", open[0]);
    assumeTrue(
        report.substring(bands).split("\n").length >= ITEMS.length,
        "the screen does not show the menu\n" + report);
    assertTrue(title >= TEXT_CONTRAST, "closed title\n" + report);
    assertTrue(items >= TEXT_CONTRAST, "menu items\n" + report);
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

  /**
   * Returns the lowest contrast of any text band in an area: each run of at least five rows that
   * holds ink, read as its strongest pixel against the band's most common color.
   */
  private double weakestBand(ImageData data, Rectangle area) {
    double min = Double.MAX_VALUE;
    int start = -1;
    for (int y = area.y; y <= area.y + area.height; y++) {
      boolean inked = y < area.y + area.height && inked(data, area, y);
      if (inked && start < 0) {
        start = y;
      } else if (!inked && start >= 0) {
        if (y - start >= 5) {
          RGB ground = mode(data, area.x, start, area.width, y - start);
          RGB ink = strongest(data, area.x, start, area.width, y - start, ground);
          double contrast = Contrast.ratio(ink, ground);
          min = Math.min(min, contrast);
          report.append(
              String.format(
                  "rows %d-%d ground %s ink %s %.2f%n",
                  start, y - 1, hex(ground), hex(ink), contrast));
        }
        start = -1;
      }
    }
    return min;
  }

  /** Returns true when a row holds at least two pixels at 1.6 contrast to its most common color. */
  private static boolean inked(ImageData data, Rectangle area, int y) {
    RGB row = mode(data, area.x, y, area.width, 1);
    int count = 0;
    for (int x = Math.max(0, area.x); x < area.x + area.width && x < data.width; x++) {
      if (Contrast.ratio(data.palette.getRGB(data.getPixel(x, y)), row) > 1.6) {
        count++;
      }
    }
    return count >= 2;
  }

  private static RGB mode(ImageData data, int x0, int y0, int width, int height) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = Math.max(0, y0); y < y0 + height && y < data.height; y++) {
      for (int x = Math.max(0, x0); x < x0 + width && x < data.width; x++) {
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

  private static RGB strongest(ImageData data, int x0, int y0, int width, int height, RGB ground) {
    RGB best = ground;
    double max = 1;
    for (int y = Math.max(0, y0); y < y0 + height && y < data.height; y++) {
      for (int x = Math.max(0, x0); x < x0 + width && x < data.width; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double contrast = Contrast.ratio(rgb, ground);
        if (contrast > max) {
          max = contrast;
          best = rgb;
        }
      }
    }
    return best;
  }

  private void settle() {
    final long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
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

  private void save(String name, ImageData data) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "cocoa-combo-list"));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
      Files.writeString(dir.resolve(name + ".txt"), report.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }

  private static long viewId(Control control) {
    try {
      Field view = Control.class.getDeclaredField("view");
      view.setAccessible(true);
      Class<?> id = Class.forName(COCOA + "id", false, Widget.class.getClassLoader());
      return id.getField("id").getLong(view.get(control));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** Sends a selector with no argument, or with one when it ends in a colon. */
  private static long send(long id, String selector, long arg) {
    try {
      Class<?> os = Class.forName(COCOA + "OS", false, Widget.class.getClassLoader());
      long sel = (long) os.getMethod("sel_registerName", String.class).invoke(null, selector);
      if (selector.endsWith(":")) {
        return (long)
            os.getMethod("objc_msgSend", long.class, long.class, long.class)
                .invoke(null, id, sel, arg);
      }
      return (long) os.getMethod("objc_msgSend", long.class, long.class).invoke(null, id, sel);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
