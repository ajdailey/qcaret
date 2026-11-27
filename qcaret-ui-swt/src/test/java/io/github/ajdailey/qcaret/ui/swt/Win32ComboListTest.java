package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.ui.swt.platform.ComboListSelection;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.platform.WindowRebuild;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On win32 the open list of a read-only or editable {@link Combo} in a {@link Dialogs#shell}
 * dialog, drawn through {@link ComboListSelection}, shows its selected row on a ground of its own
 * with text at 4.5 contrast, read from the screen below the combo: in {@link Palette#DARK}'s
 * selection under a dark start and after a live switch to dark, natively under a light start, under
 * the host's system theme setting. Each case saves the list and its row readings to {@code
 * target/diagnostics/win32-combo-list/}. Skipped where the screen does not show the test window or
 * the list.
 */
@Tag("swt")
class Win32ComboListTest {
  private static final Duration BOUND = Duration.ofSeconds(5);

  /** WCAG AA contrast for normal text. */
  private static final double TEXT_CONTRAST = 4.5;

  /**
   * The native light selection's text contrast: white on Windows' accent {@code #0078d7} is
   * 4.499:1, just under {@link #TEXT_CONTRAST}.
   */
  private static final double NATIVE_CONTRAST = 4.49;

  /** How long one posted Down gets to move the selection before it is posted again. */
  private static final Duration KEY_BOUND = Duration.ofSeconds(2);

  /** Logical pixels skipped at each edge of the selected band: the frame and focus outline. */
  private static final int EDGE = 2;

  /** {@link Palette#DARK}'s selection, which the dark list's selected row is filled with. */
  private static final RGB DARK_SELECTION =
      new RGB(
          Palette.DARK.selection().red(),
          Palette.DARK.selection().green(),
          Palette.DARK.selection().blue());

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
    assertEquals(DARK_SELECTION, check(SWT.READ_ONLY, ThemeMode.DARK, false, 0, "dark"));
  }

  @Test
  void theSelectedRowReadsAfterKeyboardMovesUnderADarkStart() {
    assertEquals(DARK_SELECTION, check(SWT.READ_ONLY, ThemeMode.DARK, false, 2, "dark-down"));
  }

  @Test
  void anEditableComboSelectedRowReadsAfterKeyboardMovesUnderADarkStart() {
    assertEquals(
        DARK_SELECTION, check(SWT.DROP_DOWN, ThemeMode.DARK, false, 2, "editable-dark-down"));
  }

  @Test
  void theSelectedRowReadsUnderALightStart() {
    assertNotEquals(DARK_SELECTION, check(SWT.READ_ONLY, ThemeMode.LIGHT, false, 0, "light"));
  }

  @Test
  void theSelectedRowReadsAfterALiveSwitchToDark() {
    assertEquals(DARK_SELECTION, check(SWT.READ_ONLY, ThemeMode.LIGHT, true, 0, "light-to-dark"));
  }

  @Test
  void anEditableComboSelectedRowReadsUnderADarkStart() {
    assertEquals(DARK_SELECTION, check(SWT.DROP_DOWN, ThemeMode.DARK, false, 0, "editable-dark"));
  }

  @Test
  void anEditableComboSelectedRowReadsAfterALiveSwitchToDark() {
    assertEquals(
        DARK_SELECTION, check(SWT.DROP_DOWN, ThemeMode.LIGHT, true, 0, "editable-light-to-dark"));
  }

  /**
   * Opens the list with the pointer off it and checks its selected row, then moves the selection
   * {@code downs} rows with the Down key and checks the row it lands on; returns the last row's
   * fill.
   *
   * @param style {@code SWT.READ_ONLY} or {@code SWT.DROP_DOWN}, an editable combo
   * @param switchToDark sets the dark native setting after the combo exists, as a live switch does
   */
  private RGB check(int style, ThemeMode mode, boolean switchToDark, int downs, String name) {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 combo list");
    display = new Display();
    SwtThemeService.open(display, mode);
    Shell parent = new Shell(display, SWT.SHELL_TRIM);
    parent.setBounds(20, 20, 200, 100);
    parent.open();
    Shell dialog = Dialogs.shell(parent, "Combo list", true);
    final Combo combo = new Combo(dialog, style);
    ComboListSelection.PLATFORM.draw(combo);
    combo.setItems(ITEMS);
    combo.select(1);
    dialog.setBounds(60, 60, 320, 220);
    dialog.open();
    dialog.forceActive();
    settle();
    ScreenProbe.assumeShows(dialog);
    if (switchToDark) {
      PlatformTraits.PLATFORM.themeFollow(display).showOnWindows(true, WindowRebuild.NONE);
    }
    // A pointer left over the list by an earlier test would move its selection.
    display.setCursorLocation(parent.toDisplay(0, 0));
    combo.setFocus();
    final Shell active = dialog;
    assumeTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return display.getActiveShell() == active && display.getFocusControl() == combo;
              }
            },
            BOUND),
        "the dialog is active and the combo has the focus, so posted keys reach it");
    final RGB body = mostRows(capture(combo));
    combo.setListVisible(true);
    ImageData list = stableList(combo, body);
    save(name, list);
    assumeTrue(
        !mostRows(list).equals(body),
        "the screen does not show the open list below the combo; body " + hex(body));
    report.setLength(0);
    int[] band = selectedBand(list, mostRows(list));
    double minimum = mode == ThemeMode.LIGHT && !switchToDark ? NATIVE_CONTRAST : TEXT_CONTRAST;
    RGB fill = readable(list, band, name, minimum);
    if (downs == 0) {
      return fill;
    }
    for (int i = 1; i <= downs; i++) {
      down(combo, 1 + i);
    }
    // The hook repaints a keyboard move on a later WM_PAINT, which only the event loop dispatches.
    settle();
    ImageData moved = stableList(combo, body);
    report.setLength(0);
    int[] movedBand = selectedBand(moved, mostRows(moved));
    String movedName = name + "-moved";
    save(movedName, moved);
    assertTrue(
        movedBand != null && movedBand[0] > band[0],
        "the selected band moves down with the selection\n" + report);
    return readable(moved, movedBand, movedName, minimum);
  }

  /**
   * Posts Down until the selection reaches {@code target}, at most twice: the second post goes only
   * when the first left the selection where it was.
   */
  private void down(final Combo combo, final int target) {
    BooleanSupplier reached =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return combo.getSelectionIndex() == target;
          }
        };
    postDown();
    if (!EventLoop.runUntil(display, reached, KEY_BOUND)
        && combo.getSelectionIndex() == target - 1) {
      postDown();
    }
    assertTrue(
        EventLoop.runUntil(display, reached, BOUND),
        "Down moves the selection to " + target + ", at " + combo.getSelectionIndex());
  }

  /** Captures until two reads in a row match, so the list has finished opening and painting. */
  private ImageData stableList(final Combo combo, final RGB body) {
    final ImageData[] list = {null};
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            ImageData previous = list[0];
            list[0] = capture(combo);
            report.setLength(0);
            return !mostRows(list[0]).equals(body)
                && selectedBand(list[0], mostRows(list[0])) != null
                && previous != null
                && Arrays.equals(previous.data, list[0].data);
          }
        },
        BOUND);
    return list[0];
  }

  /**
   * Asserts that the band is a row on its own ground with text at {@code minimum} contrast; returns
   * the band's fill.
   */
  private RGB readable(ImageData list, int[] band, String name, double minimum) {
    assertTrue(band != null, "no row on its own ground\n" + report);
    // The band's middle row: its edge rows carry the focus outline, XORed over the fill.
    RGB fill = rowMode(list, (band[0] + band[1]) / 2);
    double contrast = SwtThemeService.contrast(strongest(list, band, fill), fill);
    report.append(String.format("selected %s contrast=%.3f%n", hex(fill), contrast));
    save(name, list);
    assertTrue(contrast >= minimum, "selected row text\n" + report);
    return fill;
  }

  /** Posts a Down press and release to the focused combo, whose list is open. */
  private void postDown() {
    Event key = new Event();
    key.type = SWT.KeyDown;
    key.keyCode = SWT.ARROW_DOWN;
    display.post(key);
    key.type = SWT.KeyUp;
    display.post(key);
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

  /**
   * Returns the pixel that contrasts most with the fill, inside the band's edges: the keyboard
   * focus outline is dotted along them and reads as ink on a row with no text.
   */
  private static RGB strongest(ImageData data, int[] rows, RGB fill) {
    RGB best = fill;
    double max = 1;
    for (int y = rows[0] + EDGE; y <= rows[1] - EDGE; y++) {
      for (int x = EDGE + 1; x < data.width - EDGE - 1; x++) {
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
