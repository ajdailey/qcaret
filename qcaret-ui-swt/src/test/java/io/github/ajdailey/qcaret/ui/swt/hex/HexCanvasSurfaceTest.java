package io.github.ajdailey.qcaret.ui.swt.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexScroll;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import io.github.ajdailey.qcaret.ui.swt.editor.ControlBoxes;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A {@link HexPane}'s rows with a recording controller: clicks and drags in both columns and below
 * the last byte, the caret keys, Tab, the editor keys and the window keys a Hex Viewer runs itself;
 * the painted colors (background, offset gutter, selection, caret twin, the {@code .} of a byte
 * that does not print in the {@link ControlBoxes#ink}, a replaced byte in the invalid color); and
 * cells placed from the measured advance, which the pom also runs at {@code -Dswt.autoScale=150}.
 */
@Tag("swt")
class HexCanvasSurfaceTest {
  private static final int SIZE = 40;

  private Display display;
  private Shell shell;
  private Font font;
  private final Recorder input = new Recorder();

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    font =
        new Font(
            display,
            PlatformTraits.PLATFORM.monospaceFamily(),
            PlatformTraits.PLATFORM.monospaceSize(),
            SWT.NORMAL);
  }

  @AfterEach
  void close() {
    shell.dispose();
    font.dispose();
    display.dispose();
  }

  private HexPane pane(boolean window) {
    HexPane pane =
        new HexPane(
            shell,
            input,
            Palette.LIGHT,
            font,
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            new Timers(),
            new HexPane.Host() {
              @Override
              public void askGoToOffset(
                  Shell parent, Predicate<String> valid, Consumer<String> answer) {
                input.calls.add("goTo");
              }

              @Override
              public void close() {
                input.calls.add("close");
              }
            },
            window);
    pane.control().setBounds(0, 0, 900, 300);
    pane.control().layout(true, true);
    pane.showLayout(new HexRows(SIZE, 16));
    pane.showScroll(new HexScroll(0, 3, 3, 0));
    shell.open();
    input.calls.clear();
    return pane;
  }

  private static void mouse(Canvas canvas, int type, int x, int y, int state) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    event.stateMask = state;
    canvas.notifyListeners(type, event);
  }

  private static void key(Canvas canvas, int keyCode, char character, int state) {
    Event event = new Event();
    event.keyCode = keyCode;
    event.character = character;
    event.stateMask = state;
    canvas.notifyListeners(SWT.KeyDown, event);
  }

  @Test
  void clicksAndDragsReportTheByteAndColumnUnderThePointer() {
    HexPane pane = pane(false);
    Canvas canvas = pane.canvas();
    HexRows rows = pane.rows();
    int line = pane.lineHeight();
    int y = line + line / 2;
    mouse(canvas, SWT.MouseDown, pane.columnX(rows.hexColumn(5)) + 1, y, 0);
    mouse(canvas, SWT.MouseUp, 0, 0, 0);
    mouse(canvas, SWT.MouseDown, pane.columnX(rows.textColumn(1)) + 1, y + line, SWT.SHIFT);
    mouse(canvas, SWT.MouseUp, 0, 0, 0);
    mouse(canvas, SWT.MouseDown, pane.columnX(rows.hexColumn(15)) + 1, 10 * line, 0);
    mouse(canvas, SWT.MouseDown, pane.columnX(rows.hexColumn(2)) + 1, y, 0);
    mouse(canvas, SWT.MouseMove, pane.columnX(rows.hexColumn(9)) + 1, y + line, SWT.BUTTON1);
    mouse(canvas, SWT.MouseUp, 0, 0, 0);
    assertEquals(
        List.of(
            "clicked 5 false false",
            "clicked 17 true true",
            "clicked 40 false false",
            "clicked 2 false false",
            "dragged 25"),
        input.calls);
  }

  @Test
  void keysMoveTheCaretAndRunTheViewCommands() {
    HexPane pane = pane(false);
    Canvas canvas = pane.canvas();
    int primary = SWT.MOD1;
    key(canvas, SWT.ARROW_RIGHT, '\0', 0);
    key(canvas, SWT.END, '\0', SWT.SHIFT);
    key(canvas, SWT.HOME, '\0', primary);
    key(canvas, SWT.PAGE_DOWN, '\0', 0);
    key(canvas, SWT.TAB, '\t', 0);
    key(canvas, 'c', 'c', primary);
    key(canvas, 'a', 'a', primary);
    key(canvas, SWT.ESC, (char) 27, 0);
    key(canvas, 'f', 'f', primary);
    assertEquals(
        List.of(
            "key RIGHT false",
            "key ROW_END true",
            "key SOURCE_START false",
            "key PAGE_DOWN false",
            "switchColumn",
            "copy FOCUSED",
            "selectAll",
            "cancel"),
        input.calls);
  }

  @Test
  void aHexViewerWindowRunsTheWindowKeysItself() {
    HexPane pane = pane(true);
    Canvas canvas = pane.canvas();
    int primary = SWT.MOD1;
    key(canvas, 'f', 'f', primary);
    key(canvas, 'w', 'w', primary);
    assertEquals(List.of("openFind", "close"), input.calls);
    input.calls.clear();
    pane.showFind("4D 5A", true);
    key(canvas, SWT.F3, '\0', 0);
    assertEquals(List.of("find 4D 5A true true"), input.calls);
    assertTrue(pane.findBar().getVisible());
  }

  @Test
  void cellsTakeTheirColors() {
    int zoom = readbackZoom();
    assumeTrue(zoom > 0, "cocoa reads no Image back at device zoom " + deviceZoom());
    HexRows rows = new HexRows(SIZE, 16);
    Image image = new Image(display, 900, 120);
    GC gc = new GC(image);
    try {
      gc.setFont(font);
      gc.setTextAntialias(SWT.OFF);
      double advance = gc.textExtent("0".repeat(1000)).x / 1000.0;
      int line = gc.getFontMetrics().getHeight();
      HexGrid grid = new HexGrid(rows, advance, line);
      HexPainter painter = new HexPainter(Palette.LIGHT);
      painter.paint(
          gc,
          900,
          120,
          new HexPainter.View(grid, 0, 3, new HexSelection(2, 4, false), true, false),
          input);
      Pixels pixels = new Pixels(image.getImageData(zoom), zoom);
      Palette p = Palette.LIGHT;
      assertEquals(rgb(p.background()), pixels.at(890, 110));
      assertEquals(rgb(p.gutter()), pixels.at(1, line + 1));
      assertEquals(rgb(p.selection()), pixels.at(grid.x(rows.hexColumn(2)), line + 1));
      assertEquals(rgb(p.selection()), pixels.at(grid.x(rows.textColumn(3)), line + 1));
      // Byte 0x00 at 0 prints as '.' in the box ink; byte 0x41 at 1 as 'A' in the text color.
      assertTrue(has(pixels, grid, rows.textColumn(0), 0, ink(p)), "dot ink");
      assertTrue(has(pixels, grid, rows.textColumn(1), 0, rgb(p.text())), "text");
      assertTrue(
          has(pixels, grid, rows.hexColumn(16 + 5), 1, rgb(p.style(TokenRole.INVALID).foreground()))
              || has(
                  pixels, grid, rows.hexColumn(5), 1, rgb(p.style(TokenRole.INVALID).foreground())),
          "replaced");
      painter.paint(
          gc,
          900,
          120,
          new HexPainter.View(grid, 0, 3, new HexSelection(6, 6, false), true, false),
          input);
      pixels = new Pixels(image.getImageData(zoom), zoom);
      assertEquals(rgb(p.occurrence()), pixels.at(grid.x(rows.textColumn(6)), line + 1));
      painter.setPalette(Palette.DARK);
      painter.paint(
          gc,
          900,
          120,
          new HexPainter.View(grid, 0, 3, new HexSelection(0, 0, false), true, true),
          input);
      assertEquals(
          rgb(Palette.DARK.background()), new Pixels(image.getImageData(zoom), zoom).at(890, 110));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Test
  void eachCellSitsAtItsMeasuredColumn() {
    Canvas canvas = new Canvas(shell, SWT.NONE);
    GC gc = new GC(canvas);
    try {
      gc.setFont(font);
      double advance = gc.textExtent("0".repeat(1000)).x / 1000.0;
      HexRows rows = new HexRows(SIZE, 32);
      HexGrid grid = new HexGrid(rows, advance, 10);
      int zoom = PlatformTraits.PLATFORM.layoutZoom(canvas);
      // One pixel in points, plus a point for rounding each position to points.
      double slack = 100.0 / zoom + 1;
      for (int column = 1; column <= rows.columns(); column++) {
        int run = gc.textExtent("0".repeat(column)).x;
        int cell = grid.x(column) - HexGrid.PAD;
        assertTrue(
            Math.abs(cell - run) <= slack,
            "column "
                + column
                + " at "
                + cell
                + ", a run of zeros ends at "
                + run
                + ", zoom "
                + zoom);
      }
      assertFalse(grid.x(rows.columns()) - HexGrid.PAD < rows.columns() * Math.floor(advance));
    } finally {
      gc.dispose();
    }
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  private static RGB ink(Palette p) {
    return rgb(ControlBoxes.ink(p.text(), p.background()));
  }

  private static int deviceZoom() {
    return Integer.getInteger("org.eclipse.swt.internal.deviceZoom", 100);
  }

  /**
   * Returns the zoom an Image's drawn pixels read back at, or 0 where none does. GTK draws an Image
   * at 100. win32 draws at the device zoom; a read at another zoom while the GC is open replays the
   * drawing into a new handle, which does not match it. cocoa draws at the device zoom and reads
   * back only at 100 and 200; any other read replaces the drawn representation with a blank one.
   */
  private static int readbackZoom() {
    int device = deviceZoom();
    return switch (SWT.getPlatform()) {
      case "gtk" -> 100;
      case "cocoa" -> device == 100 || device == 200 ? device : 0;
      default -> device;
    };
  }

  /** Reports whether a cell of a row holds a pixel of a color. */
  private static boolean has(Pixels pixels, HexGrid grid, int column, int row, RGB color) {
    int y0 = pixels.pixel(grid.y(row));
    int y1 = pixels.pixel(grid.y(row) + grid.lineHeight());
    for (int y = y0; y < y1; y++) {
      for (int x = pixels.pixel(grid.x(column)); x < pixels.pixel(grid.x(column + 1)); x++) {
        if (color.equals(pixels.data().palette.getRGB(pixels.data().getPixel(x, y)))) {
          return true;
        }
      }
    }
    return false;
  }

  /** An Image's pixels at a zoom, addressed in points. */
  private record Pixels(ImageData data, int zoom) {
    /** Returns the first pixel at or after a point. */
    int pixel(int point) {
      return (int) Math.ceil(point * zoom / 100.0);
    }

    /** Returns the color of a point's first pixel. */
    RGB at(int x, int y) {
      return data.palette.getRGB(data.getPixel(pixel(x), pixel(y)));
    }
  }

  /** Records the calls; row 0 holds 0x00, 'A', then rising values, byte 21 replaced. */
  private static final class Recorder implements HexInput {
    final List<String> calls = new ArrayList<String>();

    @Override
    public void attach(HexView view) {
      calls.add("attach");
    }

    @Override
    public void detach(HexView view) {
      calls.add("detach");
    }

    @Override
    public HexRow row(long row) {
      int start = (int) row * 16;
      int length = Math.min(16, SIZE - start);
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) (start + i == 1 ? 'A' : start + i);
      }
      BitSet replaced = new BitSet();
      if (row == 1) {
        replaced.set(5);
      }
      return new HexRow(start, "", bytes, replaced);
    }

    @Override
    public void resized(int rows) {
      // Not recorded: the layout reports it.
    }

    @Override
    public void scrolled(int selection) {
      calls.add("scrolled " + selection);
    }

    @Override
    public void scrollBy(long rows) {
      calls.add("scrollBy " + rows);
    }

    @Override
    public void key(HexKey key, boolean extend) {
      calls.add("key " + key + " " + extend);
    }

    @Override
    public void switchColumn() {
      calls.add("switchColumn");
    }

    @Override
    public void clicked(long offset, boolean textColumn, boolean extend) {
      calls.add("clicked " + offset + " " + textColumn + " " + extend);
    }

    @Override
    public void dragged(long offset) {
      calls.add("dragged " + offset);
    }

    @Override
    public void selectAll() {
      calls.add("selectAll");
    }

    @Override
    public void focusChanged(boolean focused) {
      // Not recorded: the window manager decides.
    }

    @Override
    public void copy(HexCopy kind) {
      calls.add("copy " + kind);
    }

    @Override
    public void goToOffset() {
      calls.add("goToOffset");
    }

    @Override
    public void openFind() {
      calls.add("openFind");
    }

    @Override
    public void find(String text, boolean hex, boolean forward) {
      calls.add("find " + text + " " + hex + " " + forward);
    }

    @Override
    public void cancel() {
      calls.add("cancel");
    }

    @Override
    public void noticeAction() {
      calls.add("noticeAction");
    }
  }

  /** Runs nothing: no drag in these tests leaves the rows. */
  private static final class Timers implements UiDispatcher {
    @Override
    public void asyncExec(Runnable task) {
      // Not used.
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      // Not used.
    }

    @Override
    public void cancelTimer(Runnable task) {
      // Not used.
    }

    @Override
    public boolean isUiThread() {
      return true;
    }
  }
}
