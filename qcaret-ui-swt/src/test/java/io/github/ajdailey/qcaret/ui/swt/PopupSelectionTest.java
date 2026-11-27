package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A history field's open list draws its highlighted row in {@link PopupSelection#BACKGROUND} on a
 * dark list and the native selection on a light one, read from the screen. Skipped where the screen
 * does not show the test window.
 */
@Tag("swt")
class PopupSelectionTest {
  private static final Duration BOUND = Duration.ofSeconds(5);

  private static final int TOLERANCE = 8;

  private Display display;
  private Shell shell;
  private HistoryField field;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setLayout(new GridLayout(1, false));
    field = new HistoryField(shell, 200);
    field.setItems(List.of("first entry", "second entry", "third entry"));
    shell.setBounds(40, 40, 360, 240);
    shell.open();
    settle();
    ScreenProbe.assumeShows(shell);
  }

  @AfterEach
  void close() {
    field.hide();
    display.dispose();
  }

  @Test
  void aDarkListDrawsThePaletteSelection() {
    RGB seen = highlightedRow(new Color(0x2b, 0x2b, 0x2b));
    assertTrue(near(seen, PopupSelection.BACKGROUND.getRGB()), "highlighted row " + seen);
  }

  @Test
  void aLightListKeepsTheNativeSelection() {
    RGB seen = highlightedRow(new Color(0xff, 0xff, 0xff));
    assertFalse(near(seen, PopupSelection.BACKGROUND.getRGB()), "highlighted row " + seen);
  }

  /**
   * Opens the list on a background, highlights the second row and reads its most common color once
   * it differs from the list and the window behind it.
   */
  private RGB highlightedRow(final Color background) {
    field.show(1);
    Shell popup = field.popup();
    assertNotNull(popup);
    final Table table = (Table) popup.getChildren()[0];
    table.setBackground(background);
    table.redraw();
    final RGB behind = shell.getBackground().getRGB();
    final RGB[] seen = {null};
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            seen[0] = read(table);
            return !near(seen[0], background.getRGB()) && !near(seen[0], behind);
          }
        },
        BOUND);
    return seen[0];
  }

  private RGB read(Table table) {
    Rectangle row = table.getItem(1).getBounds();
    Point origin = table.toDisplay(row.x, row.y);
    int width = Math.max(1, Math.min(row.width, table.getClientArea().width));
    Image image = new Image(display, width, Math.max(1, row.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      return mode(Pixels.read(image, table));
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

  private static RGB mode(ImageData data) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = 0; y < data.height; y++) {
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
    }
    return data.palette.getRGB(best);
  }

  private static boolean near(RGB a, RGB b) {
    return Math.abs(a.red - b.red) <= TOLERANCE
        && Math.abs(a.green - b.green) <= TOLERANCE
        && Math.abs(a.blue - b.blue) <= TOLERANCE;
  }
}
