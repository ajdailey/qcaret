package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.ui.swt.platform.ListSelection;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.platform.WindowRebuild;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On win32 the selected row of a {@link Dialogs#list} in a {@link Dialogs#shell} dialog, drawn
 * through {@link ListSelection}, reads on the screen: {@code #626262} with focus and {@code
 * #747474} without, text at 4.5 contrast, under a dark start and after a live switch to light;
 * natively under a light start and after a live switch to dark, as the dialog keeps the colors it
 * was created with. The row is read 2 px in from each edge, its fill at its middle pixel row. A
 * selection or focus change repaints it without a {@code redraw}; after a live switch the test asks
 * for a paint with one. Each case saves the list and its readings to {@code
 * target/diagnostics/win32-list/}. Skipped where the screen does not show the list.
 */
@Tag("swt")
class Win32ListTest {
  private static final Duration BOUND = Duration.ofSeconds(5);

  /** WCAG AA contrast for normal text. */
  private static final double TEXT_CONTRAST = 4.5;

  /** White on Windows' accent {@code #0078d7} is 4.499:1, just under {@link #TEXT_CONTRAST}. */
  private static final double NATIVE_CONTRAST = 4.49;

  /** Logical pixels skipped at each edge of the selected row. */
  private static final int EDGE = 2;

  private static final RGB FOCUSED = new RGB(0x62, 0x62, 0x62);
  private static final RGB UNFOCUSED = new RGB(0x74, 0x74, 0x74);

  private static final String[] ITEMS = {"Alpha item", "Beta item", "Gamma item", "Delta item"};

  private Display display;
  private List list;
  private Text other;
  private final StringBuilder report = new StringBuilder();

  @AfterEach
  void close() {
    if (display != null) {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }

  @Test
  void theFocusedSelectedRowReadsUnderADarkStart() {
    open(ThemeMode.DARK);
    focus(list);
    assertEquals(FOCUSED, read(1, true, "dark-focused"));
    list.select(2);
    assertEquals(FOCUSED, read(2, true, "dark-focused-moved"));
  }

  @Test
  void theUnfocusedSelectedRowReadsUnderADarkStart() {
    open(ThemeMode.DARK);
    focus(list);
    focus(other);
    assertEquals(UNFOCUSED, read(1, true, "dark-unfocused"));
  }

  @Test
  void theSelectedRowIsNativeUnderALightStart() {
    open(ThemeMode.LIGHT);
    focus(list);
    assertNative(read(1, false, "light-focused"));
    focus(other);
    assertNative(read(1, false, "light-unfocused"));
  }

  @Test
  void theFocusedSelectedRowOfADarkListStaysDarkAfterASwitchToLight() {
    open(ThemeMode.DARK);
    focus(list);
    assertEquals(FOCUSED, read(1, true, "switch-focused-dark"));
    switchTo(false);
    assertEquals(FOCUSED, read(1, true, "switch-focused-light"));
  }

  @Test
  void theUnfocusedSelectedRowOfADarkListStaysDarkAfterASwitchToLight() {
    open(ThemeMode.DARK);
    focus(other);
    assertEquals(UNFOCUSED, read(1, true, "switch-unfocused-dark"));
    switchTo(false);
    assertEquals(UNFOCUSED, read(1, true, "switch-unfocused-light"));
  }

  /** Pins against a light dialog's list painted dark after a switch to dark (OBERON, on QLogg). */
  @Test
  void aLightListStaysNativeAfterASwitchToDark() {
    open(ThemeMode.LIGHT);
    focus(list);
    assertNative(read(1, false, "switch-light-focused"));
    switchTo(true);
    assertNative(read(1, false, "switch-light-focused-dark"));
    focus(other);
    assertNative(read(1, false, "switch-light-unfocused-dark"));
  }

  private static void assertNative(RGB fill) {
    assertNotEquals(FOCUSED, fill);
    assertNotEquals(UNFOCUSED, fill);
  }

  /** Opens a dialog with the list, row 1 selected, and a text field to take the focus. */
  private void open(ThemeMode mode) {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 list");
    display = new Display();
    SwtThemeService.open(display, mode);
    Shell parent = new Shell(display, SWT.SHELL_TRIM);
    parent.setBounds(20, 20, 200, 100);
    parent.open();
    Shell dialog = Dialogs.shell(parent, "List", true);
    list = Dialogs.list(dialog);
    list.setItems(ITEMS);
    list.select(1);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.heightHint = list.getItemHeight() * (ITEMS.length + 1);
    list.setLayoutData(data);
    other = new Text(dialog, SWT.BORDER);
    other.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    dialog.setBounds(60, 60, 320, 240);
    dialog.open();
    dialog.forceActive();
    display.setCursorLocation(parent.toDisplay(0, 0));
    settle();
  }

  private void focus(final Control control) {
    control.setFocus();
    assumeTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return display.getFocusControl() == control;
              }
            },
            BOUND),
        "the dialog is active and " + control + " has the focus");
    settle();
  }

  /** Switches the native side live, as the theme menu does, then repaints the list. */
  private void switchTo(boolean dark) {
    PlatformTraits.PLATFORM.themeFollow(display).showOnWindows(dark, WindowRebuild.NONE);
    list.redraw();
    settle();
  }

  /**
   * Reads row {@code index} from the screen until two reads match; asserts its text contrast and
   * returns its fill.
   */
  private RGB read(int index, boolean dark, String name) {
    final ImageData[] shot = {null};
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            ImageData previous = shot[0];
            shot[0] = capture();
            return previous != null && Arrays.equals(previous.data, shot[0].data);
          }
        },
        BOUND);
    ImageData data = shot[0];
    report.setLength(0);
    RGB ground = Pixels.mode(data);
    report.append("ground ").append(hex(ground)).append('\n');
    save(name, data);
    assumeTrue(
        ground.equals(list.getBackground().getRGB()),
        "the screen shows the list; ground " + hex(ground));
    int height = list.getItemHeight();
    int top = (index - list.getTopIndex()) * height;
    RGB fill = Pixels.mode(data, new Rectangle(EDGE, top + height / 2, data.width - 2 * EDGE, 1));
    Rectangle inner = new Rectangle(EDGE, top + EDGE, data.width - 2 * EDGE, height - 2 * EDGE);
    double contrast = SwtThemeService.contrast(strongest(data, inner, fill), fill);
    report.append(String.format("row %d fill %s contrast=%.3f%n", index, hex(fill), contrast));
    save(name, data);
    assertTrue(contrast >= (dark ? TEXT_CONTRAST : NATIVE_CONTRAST), "selected row\n" + report);
    return fill;
  }

  private static RGB strongest(ImageData data, Rectangle area, RGB fill) {
    RGB best = fill;
    double max = 1;
    for (int y = area.y; y < area.y + area.height; y++) {
      for (int x = area.x; x < area.x + area.width; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double value = SwtThemeService.contrast(rgb, fill);
        if (value > max) {
          max = value;
          best = rgb;
        }
      }
    }
    return best;
  }

  /** Reads the list's client area from the screen. */
  private ImageData capture() {
    Rectangle client = list.getClientArea();
    Point origin = list.toDisplay(0, 0);
    Image image = new Image(display, client.width, client.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      return Pixels.read(image, list);
    } finally {
      gc.dispose();
      image.dispose();
    }
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
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-list"));
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
