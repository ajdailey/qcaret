package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link TableFit#spread(Table)} on a table shaped like the Bookmarks list, sized inside a shell
 * with no layout: narrowed past the switch from pinned header minima to proportion, then widened
 * back by 1 to 12 points. The pom also runs this class with {@code -Dswt.autoScale=150}.
 */
@Tag("swt")
class TableFitSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(3);
  private static final int[] WEIGHTS = {0, 260, 480};

  private Display display;
  private Shell shell;
  private Table table;
  private int[] minima;
  private int height;

  /** The horizontal scroll bar's visibility at the first, wide width. */
  private Boolean idle;

  /** The largest total of the column widths since it was last reset. */
  private int peak;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    shell.setSize(1000, 600);
    table = new Table(shell, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setHeaderVisible(true);
    String[] headers = {"Line", "Comment", "Text"};
    int[] widths = {70, WEIGHTS[1], WEIGHTS[2]};
    for (int i = 0; i < headers.length; i++) {
      TableColumn column = new TableColumn(table, SWT.LEFT);
      column.setText(headers[i]);
      column.setWidth(widths[i]);
    }
    Listener total =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int sum = 0;
            for (TableColumn column : table.getColumns()) {
              sum += column.getWidth();
            }
            peak = Math.max(peak, sum);
          }
        };
    for (TableColumn column : table.getColumns()) {
      column.addListener(SWT.Resize, total);
    }
    for (int i = 0; i < 3; i++) {
      new TableItem(table, SWT.NONE)
          .setText(new String[] {String.valueOf(i + 1), "", "mock line " + i});
    }
    TableFit.spread(table);
    minima = new int[WEIGHTS.length];
    GC gc = new GC(table);
    try {
      for (int i = 1; i < minima.length; i++) {
        minima[i] = gc.textExtent(table.getColumn(i).getText()).x + TableFit.HEADER_PADDING;
      }
    } finally {
      gc.dispose();
    }
    shell.open();
    // Rows that fit without a horizontal bar and need a vertical one beside it, as on OBERON.
    int bar = Math.max(8, table.getHorizontalBar().getSize().y);
    height =
        table.getHeaderHeight() + 3 * table.getItemHeight() + 2 * table.getBorderWidth() + bar / 2;
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  /**
   * Each width the narrowing sweep and the widening steps set ends with the columns at {@link
   * TableFit#widths}, within the client area, and the horizontal scroll bar as it reads at the
   * first, wide width; while widening, the columns' total stays within the client area. On win32 at
   * 150% (OBERON) widening across the switch widened Comment before Text narrowed, and the
   * horizontal bar that total brought stayed under columns that fit. The total assertion catches
   * the order on every platform, the scroll bar assertion the bar on win32; GTK's overlay scroll
   * bars read visible at every width.
   */
  @Test
  void theColumnsFitAfterNarrowingPastTheSwitchAndWideningBack() {
    size(600);
    idle = table.getHorizontalBar().getVisible();
    int low = 0;
    for (int width = 599; width > 150; width--) {
      size(width);
      if (table.getColumn(1).getWidth() < minima[1]) {
        low = width;
        break;
      }
    }
    assertTrue(low > 0, "Comment never went below its header minimum " + minima[1]);
    for (int step = 1; step <= 12; step++) {
      size(low);
      assertFits(low);
      peak = 0;
      size(low + step);
      assertFits(low + step);
      assertTrue(
          peak <= room() + ScaleSlack.of(table),
          "total " + peak + " widening to " + (low + step) + " in " + room());
    }
  }

  /**
   * Sizes the table and waits until the columns hold their spread widths and the horizontal bar
   * reads as at the first width.
   */
  private void size(int width) {
    table.setBounds(0, 0, width, height);
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return settled()
                && (idle == null || table.getHorizontalBar().getVisible() == idle.booleanValue());
          }
        },
        BOUND);
  }

  /** Returns the client width less the vertical scroll bar's width while it is hidden. */
  private int room() {
    ScrollBar vertical = table.getVerticalBar();
    int room = table.getClientArea().width;
    if (!vertical.getVisible()) {
      room -= vertical.getSize().x;
    }
    return room;
  }

  private boolean settled() {
    int[] expected = TableFit.widths(table, WEIGHTS, minima);
    for (int i = 1; i < expected.length; i++) {
      if (table.getColumn(i).getWidth() != expected[i]) {
        return false;
      }
    }
    return true;
  }

  private void assertFits(int width) {
    int[] expected = TableFit.widths(table, WEIGHTS, minima);
    int[] actual = new int[expected.length];
    int sum = 0;
    for (int i = 0; i < table.getColumnCount(); i++) {
      int column = table.getColumn(i).getWidth();
      if (i > 0) {
        actual[i] = column;
      }
      sum += column;
    }
    assertArrayEquals(expected, actual, "columns at " + width);
    int room = room();
    assertTrue(
        sum <= room + ScaleSlack.of(table),
        "columns " + sum + " in " + room + " at " + width + ", zoom " + TableFit.zoom(table));
    assertEquals(
        idle.booleanValue(),
        table.getHorizontalBar().getVisible(),
        "horizontal scroll bar at " + width);
  }
}
