package io.github.ajdailey.qcaret.ui.swt.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Times a full view's paint for {@code -Dqcaret.hex.paint=<file>}: a canvas tall enough for {@value
 * #ROWS} rows of 16 bytes, every byte cached, a selection over half the rows, painted {@value
 * #PAINTS} times with {@code redraw} and {@code update}. Writes the median, 95th percentile and
 * maximum to the file; asserts only that every paint ran.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.hex.paint", matches = ".+")
class HexPaintTimeTest {
  private static final int ROWS = 60;
  private static final int PAINTS = 300;

  @Test
  void aFullViewPaint() throws Exception {
    Display display = new Display();
    Font font =
        new Font(
            display,
            PlatformTraits.PLATFORM.monospaceFamily(),
            PlatformTraits.PLATFORM.monospaceSize(),
            SWT.NORMAL);
    final int[] painted = new int[1];
    HexInput input = new Rows(painted);
    HexRows rows = new HexRows(1L << 20, 16);
    HexPainter painter = new HexPainter(Palette.LIGHT);
    Image measure = new Image(display, 10, 10);
    GC gc = new GC(measure);
    HexGrid grid;
    try {
      gc.setFont(font);
      double advance = gc.textExtent("0".repeat(1000)).x / 1000.0;
      grid = new HexGrid(rows, advance, gc.getFontMetrics().getHeight());
    } finally {
      gc.dispose();
      measure.dispose();
    }
    int width = grid.rowWidth();
    int height = grid.y(ROWS);
    Image image = new Image(display, width, height);
    gc = new GC(image);
    try {
      gc.setFont(font);
      HexPainter.View view =
          new HexPainter.View(
              grid, 0, ROWS, new HexSelection(64, 64 + 16 * ROWS / 2, false), true, false);
      long[] nanos = new long[PAINTS];
      for (int i = 0; i < PAINTS; i++) {
        long start = System.nanoTime();
        painter.paint(gc, width, height, view, input);
        nanos[i] = System.nanoTime() - start;
      }
      Arrays.sort(nanos);
      String report =
          String.format(
              Locale.ROOT,
              "%d rows of 16 bytes, %d x %d px, %d paints: median %.2f ms, p95 %.2f ms, max %.2f"
                  + " ms%n",
              ROWS,
              width,
              height,
              PAINTS,
              nanos[PAINTS / 2] / 1e6,
              nanos[PAINTS * 95 / 100] / 1e6,
              nanos[PAINTS - 1] / 1e6);
      Files.writeString(
          Path.of(System.getProperty("qcaret.hex.paint")), report, StandardCharsets.UTF_8);
      assertEquals(PAINTS * ROWS, painted[0]);
    } finally {
      gc.dispose();
      image.dispose();
      font.dispose();
      display.dispose();
    }
  }

  /** Every row cached: bytes rising through all 256 values. */
  private static final class Rows implements HexInput {
    private final int[] painted;

    Rows(int[] painted) {
      this.painted = painted;
    }

    @Override
    public HexRow row(long row) {
      painted[0]++;
      byte[] bytes = new byte[16];
      for (int i = 0; i < 16; i++) {
        bytes[i] = (byte) (row * 16 + i);
      }
      return new HexRow(row * 16, "", bytes, new BitSet());
    }

    @Override
    public void attach(HexView view) {
      // Not used.
    }

    @Override
    public void detach(HexView view) {
      // Not used.
    }

    @Override
    public void resized(int rows) {
      // Not used.
    }

    @Override
    public void scrolled(int selection) {
      // Not used.
    }

    @Override
    public void scrollBy(long rows) {
      // Not used.
    }

    @Override
    public void key(HexKey key, boolean extend) {
      // Not used.
    }

    @Override
    public void switchColumn() {
      // Not used.
    }

    @Override
    public void clicked(long offset, boolean textColumn, boolean extend) {
      // Not used.
    }

    @Override
    public void dragged(long offset) {
      // Not used.
    }

    @Override
    public void selectAll() {
      // Not used.
    }

    @Override
    public void focusChanged(boolean focused) {
      // Not used.
    }

    @Override
    public void copy(HexCopy kind) {
      // Not used.
    }

    @Override
    public void goToOffset() {
      // Not used.
    }

    @Override
    public void openFind() {
      // Not used.
    }

    @Override
    public void find(String text, boolean hex, boolean forward) {
      // Not used.
    }

    @Override
    public void cancel() {
      // Not used.
    }

    @Override
    public void noticeAction() {
      // Not used.
    }
  }
}
