package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On win32 the open list of a read-only {@link Combo} in a {@link Dialogs#shell} dialog shows its
 * selected row on a ground of its own with text at 4.5 contrast, under a dark and a light start,
 * read from the screen below the combo. Each case saves the list and its row readings to {@code
 * target/diagnostics/win32-combo-list/}. Skipped where the screen does not show the test window or
 * the list.
 */
@Tag("swt")
class Win32ComboListTest {
  private static final Duration BOUND = Duration.ofSeconds(5);

  /** WCAG AA contrast for normal text. */
  private static final double TEXT_CONTRAST = 4.5;

  /** Rows the list shows: the combo's items. */
  private static final String[] ITEMS = {"Alpha item", "Beta item", "Gamma item", "Delta item"};

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
  void theSelectedRowReadsUnderADarkStart() {
    check(ThemeMode.DARK, "dark");
  }

  @Test
  void theSelectedRowReadsUnderALightStart() {
    check(ThemeMode.LIGHT, "light");
  }

  private void check(ThemeMode mode, String name) {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 combo list");
    display = new Display();
    SwtThemeService.open(display, mode);
    Shell parent = new Shell(display, SWT.SHELL_TRIM);
    parent.setBounds(20, 20, 200, 100);
    parent.open();
    Shell dialog = Dialogs.shell(parent, "Combo list", true);
    final Combo combo = new Combo(dialog, SWT.READ_ONLY);
    combo.setItems(ITEMS);
    combo.select(1);
    dialog.setBounds(60, 60, 320, 220);
    dialog.open();
    dialog.forceActive();
    settle();
    ScreenProbe.assumeShows(dialog);
    combo.setFocus();
    final RGB body = mostRows(capture(combo));
    combo.setListVisible(true);
    final ImageData[] list = {null};
    final int[][] selected = {null};
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            list[0] = capture(combo);
            report.setLength(0);
            selected[0] = selectedBand(list[0], mostRows(list[0]));
            return !mostRows(list[0]).equals(body) && selected[0] != null;
          }
        },
        BOUND);
    save(name, list[0]);
    assumeTrue(
        !mostRows(list[0]).equals(body),
        "the screen does not show the open list below the combo; body " + hex(body));
    assertTrue(selected[0] != null, "no row on its own ground\n" + report);
    RGB fill = rowMode(list[0], selected[0][0]);
    double contrast = SwtThemeService.contrast(strongest(list[0], selected[0], fill), fill);
    report.append(String.format("selected %s contrast=%.2f%n", hex(fill), contrast));
    save(name, list[0]);
    assertTrue(contrast >= TEXT_CONTRAST, "selected row text\n" + report);
  }

  /** Reads the screen below the combo, where its list opens, four rows and a margin high. */
  private ImageData capture(Combo combo) {
    Point size = combo.getSize();
    Point origin = combo.toDisplay(0, size.y);
    int height = combo.getItemHeight() * ITEMS.length + 4;
    Image image = new Image(display, size.x, height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      return Pixels.read(image, combo);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns the color that is the most common color of the most rows: the list's ground. */
  private static RGB mostRows(ImageData data) {
    Map<RGB, Integer> counts = new HashMap<RGB, Integer>();
    RGB best = null;
    int bestCount = 0;
    for (int y = 0; y < data.height; y++) {
      RGB mode = rowMode(data, y);
      Integer count = counts.get(mode);
      int next = count == null ? 1 : count + 1;
      counts.put(mode, next);
      if (next > bestCount) {
        best = mode;
        bestCount = next;
      }
    }
    return best;
  }

  /**
   * Returns the first and last row past the frame of the tallest run of rows whose most common
   * color is not the ground, or null when no run is at least four rows high.
   */
  private int[] selectedBand(ImageData data, RGB ground) {
    int[] best = null;
    int start = -1;
    for (int y = 1; y <= data.height; y++) {
      boolean other = y < data.height - 1 && !rowMode(data, y).equals(ground);
      if (other && start < 0) {
        start = y;
      } else if (!other && start >= 0) {
        report.append(String.format("band %d-%d %s%n", start, y - 1, hex(rowMode(data, start))));
        if (y - start >= 4 && (best == null || y - start > best[1] - best[0] + 1)) {
          best = new int[] {start, y - 1};
        }
        start = -1;
      }
    }
    report.append("ground " + hex(ground) + "\n");
    return best;
  }

  private static RGB strongest(ImageData data, int[] rows, RGB fill) {
    RGB best = fill;
    double max = 1;
    for (int y = rows[0]; y <= rows[1]; y++) {
      for (int x = 3; x < data.width - 3; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double contrast = SwtThemeService.contrast(rgb, fill);
        if (contrast > max) {
          max = contrast;
          best = rgb;
        }
      }
    }
    return best;
  }

  private static RGB rowMode(ImageData data, int y) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int x = 0; x < data.width; x++) {
      int pixel = data.getPixel(x, y);
      Integer count = counts.get(pixel);
      int next = count == null ? 1 : count + 1;
      counts.put(pixel, next);
      if (next > bestCount) {
        best = pixel;
        bestCount = next;
      }
    }
    return data.palette.getRGB(best);
  }

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

  private void save(String name, ImageData data) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-combo-list"));
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
}
