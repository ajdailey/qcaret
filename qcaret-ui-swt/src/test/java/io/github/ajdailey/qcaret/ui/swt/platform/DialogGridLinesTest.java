package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.ScaleSlack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link DialogColors#gridLines} on every platform: native lines everywhere except a dialog {@link
 * Win32DialogColors} colors, where {@link Win32GridLines} draws {@link Win32DialogColors#GRID}
 * rules; the header divider color {@link Win32HeaderLines} sets for each side. On win32 the rules
 * and the header dividers are read from the screen, so the read fails if SWT stops honoring {@link
 * Win32HeaderLines#KEY}.
 */
@Tag("swt")
class DialogGridLinesTest {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final Duration SETTLE = Duration.ofMillis(100);
  private static final int TOLERANCE = 8;

  private Display display;
  private Shell owner;

  @BeforeEach
  void open() {
    display = new Display();
    owner = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void everyPlatformShowsNativeLinesOutsideADarkDialog() {
    for (PlatformTraits traits :
        List.of(
            PlatformTraits.of("win32", false),
            PlatformTraits.of("cocoa", false),
            PlatformTraits.of("gtk", false),
            PlatformTraits.of("gtk", true))) {
      Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
      traits.dialogColors().apply(dialog);
      Table table = new Table(dialog, SWT.BORDER);
      traits.dialogColors().gridLines(table);
      assertTrue(table.getLinesVisible(), traits.name());
      assertTrue(table.getListeners(SWT.Paint).length == 0, traits.name());
      dialog.dispose();
    }
  }

  @Test
  void onlyAColoredWin32DialogDrawsItsOwnLines() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Table outside = new Table(owner, SWT.BORDER);
    Win32DialogColors.INSTANCE.gridLines(outside);
    assertTrue(outside.getLinesVisible(), "a shell the colors did not apply to");
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    Win32DialogColors.INSTANCE.apply(dialog);
    Table inside = new Table(dialog, SWT.BORDER);
    Win32DialogColors.INSTANCE.gridLines(inside);
    assertFalse(inside.getLinesVisible());
    Table nativeDark = new Table(dialog, SWT.BORDER);
    NativeDialogColors.INSTANCE.gridLines(nativeDark);
    assertTrue(nativeDark.getLinesVisible());
  }

  @Test
  void aColoredDialogDrawsDarkRulesOnRowAndColumnEdgesAndHeaderDividers() {
    assumeTrue(
        "win32".equals(SWT.getPlatform()),
        "GTK paints a table's SWT.Paint in coordinates offset from its item bounds");
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    Win32DialogColors.INSTANCE.apply(dialog);
    dialog.setLayout(new FillLayout());
    Table table = new Table(dialog, SWT.BORDER | SWT.FULL_SELECTION);
    table.setHeaderVisible(true);
    Win32DialogColors.INSTANCE.gridLines(table);
    for (int i = 0; i < 3; i++) {
      new TableColumn(table, SWT.LEFT).setWidth(80);
    }
    for (int i = 0; i < 3; i++) {
      new TableItem(table, SWT.NONE).setText(new String[] {"", "", ""});
    }
    dialog.setSize(400, 300);
    dialog.open();
    table.deselectAll();
    Rectangle cell = table.getItem(1).getBounds(1);
    RGB field = Win32DialogColors.FIELD.getRGB();
    // Read until the table shows: this check skipped once on windows-x86_64 with a read at open.
    ScreenPixels pixels = ScreenPixels.read(table, BOUND);
    int middleX = pixels.pixels(cell.x + cell.width / 2);
    int middleY = pixels.pixels(cell.y + cell.height / 2);
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (!ScreenPixels.near(pixels.at(middleX, middleY), field, TOLERANCE)
        && System.nanoTime() < deadline) {
      pixels = ScreenPixels.read(table, SETTLE);
    }
    assumeTrue(
        ScreenPixels.near(pixels.at(middleX, middleY), field, TOLERANCE),
        "a screen read shows the table; read " + pixels.at(middleX, middleY));
    int slack = pixels.pixels(ScaleSlack.of(table)) + 1;
    RGB grid = Win32DialogColors.GRID.getRGB();
    int rowEdge = pixels.pixels(cell.y + table.getItemHeight()) - 1;
    assertTrue(
        found(pixels, middleX, rowEdge, 0, slack, grid),
        "row rule near y " + rowEdge + ": " + pixels.at(middleX, rowEdge));
    int columnEdge = pixels.pixels(cell.x + cell.width) - 1;
    assertTrue(
        found(pixels, columnEdge, middleY, slack, 0, grid),
        "column rule near x " + columnEdge + ": " + pixels.at(columnEdge, middleY));
    int below = pixels.pixels(table.getItem(2).getBounds().y + 2 * table.getItemHeight()) - 1;
    assertTrue(
        found(pixels, middleX, below, 0, slack, grid),
        "rule under the last row near y " + below + ": " + pixels.at(middleX, below));
    int divider = pixels.pixels(cell.x) - 1;
    int header = pixels.pixels(table.getHeaderHeight() / 2);
    assertTrue(
        found(pixels, divider, header, slack, 0, grid),
        "header divider near x " + divider + ": " + pixels.at(divider, header));
  }

  /**
   * Item tops as win32 reports them at 150 % for 29 px rows: 19.33 pt each, rounded, so the
   * multiplied item height (19 pt) falls 5 pt behind by row 15.
   */
  @Test
  void rowRulesFollowFractionalItemTopsWithoutDrift() {
    int header = 25;
    int itemHeight = 19;
    List<Integer> tops = new ArrayList<Integer>();
    for (int row = 0; row < 20; row++) {
      tops.add(header + Math.round(row * 29 / 1.5f));
    }
    List<Integer> rules = Win32GridLines.rules(tops, itemHeight, header, 600);
    for (int row = 1; row < 20; row++) {
      assertEquals(tops.get(row) - 1, rules.get(row - 1), "rule above row " + row);
    }
    assertNotEquals(header + 15 * itemHeight - 1, rules.get(14), "a multiplied height drifts");
    int pitch = tops.get(19) - tops.get(18);
    assertEquals(tops.get(19) + pitch - 1, rules.get(19), "first empty row");
    assertEquals(tops.get(19) + 2 * pitch - 1, rules.get(20), "second empty row");
    for (int rule : rules) {
      assertTrue(rule < 600, "rule " + rule + " inside the client area");
    }
    assertEquals(
        List.of(header + itemHeight - 1, header + 2 * itemHeight - 1),
        Win32GridLines.rules(new ArrayList<Integer>(), itemHeight, header, header + 2 * itemHeight),
        "an empty table");
    assertEquals(
        List.of(header + itemHeight - 1),
        Win32GridLines.rules(List.of(header), itemHeight, header, header + itemHeight + 1),
        "one item steps by the item height");
  }

  /**
   * {@link Win32GridLines#rowRules} on a shown table of 20 items: each rule is the pixel row above
   * the next item's top as {@link TableItem#getBounds()} reports it. GTK and a win32 run at a whole
   * zoom give whole-point rows; the fractional case is {@link
   * #rowRulesFollowFractionalItemTopsWithoutDrift}.
   */
  @Test
  void rowRulesMatchItemBoundsOverFifteenRows() {
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    dialog.setLayout(null);
    Table table = new Table(dialog, SWT.BORDER | SWT.FULL_SELECTION);
    table.setHeaderVisible(true);
    new TableColumn(table, SWT.LEFT).setWidth(120);
    for (int i = 0; i < 20; i++) {
      new TableItem(table, SWT.NONE).setText("mock row " + i);
    }
    table.setBounds(0, 0, 200, table.getHeaderHeight() + 30 * table.getItemHeight());
    dialog.setSize(260, table.getSize().y + 80);
    dialog.open();
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (System.nanoTime() < deadline
        && !(table.getItem(19).getBounds().y > table.getItem(18).getBounds().y
            && table.getClientArea().height > table.getItem(19).getBounds().y)) {
      if (!display.readAndDispatch()) {
        display.update();
      }
    }
    assertTrue(
        table.getClientArea().height > table.getItem(19).getBounds().y,
        "all 20 rows shown: " + table.getClientArea() + ", last " + table.getItem(19).getBounds());
    List<Integer> rules = Win32GridLines.rowRules(table);
    for (int row = 1; row < 20; row++) {
      assertEquals(
          table.getItem(row).getBounds().y - 1, rules.get(row - 1), "rule above row " + row);
    }
    int last = table.getItem(19).getBounds().y;
    int pitch = last - table.getItem(18).getBounds().y;
    assertEquals(last + pitch - 1, rules.get(19), "first empty row");
  }

  @Test
  void theHeaderDividerColorFollowsTheSide() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Shell dark = new Shell(owner, SWT.DIALOG_TRIM);
    Win32DialogColors.INSTANCE.apply(dark);
    assertEquals(Win32DialogColors.GRID, Win32HeaderLines.get(display));
    display.setData(DialogColors.NATIVE_DARK, Boolean.FALSE);
    Shell light = new Shell(owner, SWT.DIALOG_TRIM);
    Win32DialogColors.INSTANCE.apply(light);
    assertEquals(
        Win32DialogColors.GRID, Win32HeaderLines.get(display), "a dark dialog is still open");
    dark.dispose();
    Shell later = new Shell(owner, SWT.DIALOG_TRIM);
    Win32DialogColors.INSTANCE.apply(later);
    assertNull(Win32HeaderLines.get(display));
    NativeDialogColors.INSTANCE.apply(new Shell(owner, SWT.DIALOG_TRIM));
    assertNull(Win32HeaderLines.get(display));
  }

  private static boolean found(ScreenPixels pixels, int x, int y, int slackX, int slackY, RGB rgb) {
    for (int dx = -slackX; dx <= slackX; dx++) {
      for (int dy = -slackY; dy <= slackY; dy++) {
        int px = x + dx;
        int py = y + dy;
        if (px >= 0
            && py >= 0
            && px < pixels.width()
            && py < pixels.height()
            && ScreenPixels.near(pixels.at(px, py), rgb, TOLERANCE)) {
          return true;
        }
      }
    }
    return false;
  }
}
