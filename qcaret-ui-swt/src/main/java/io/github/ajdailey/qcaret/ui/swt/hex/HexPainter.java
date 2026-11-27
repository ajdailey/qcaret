package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import io.github.ajdailey.qcaret.ui.swt.editor.ControlBoxes;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;

/**
 * Paints a hex view: the header, then each visible row's offset, hex and text cells at their {@link
 * HexGrid} x. A row {@link HexInput#row} does not have yet paints its offset only. A text cell's
 * {@code .} for a byte that does not print takes the {@link ControlBoxes#ink} of the text over its
 * ground; a byte of a replacement for an unencodable char is drawn in the palette's invalid token
 * color. The selection fills both columns; the caret outlines its cell in the caret's column, and
 * the same byte in the other column takes the occurrence background. While dimmed, text is drawn
 * halfway between its color and the background. Allocates no SWT resource while painting.
 */
final class HexPainter {
  /** Two hex digits for each byte value. */
  private static final String[] HEX = new String[256];

  /** The text cell for each byte value. */
  private static final String[] TEXT = new String[256];

  /** One hex digit, over the text column's cells. */
  private static final String[] DIGIT = new String[16];

  static {
    for (int b = 0; b < 256; b++) {
      HEX[b] = HexRows.hexCell((byte) b);
      TEXT[b] = String.valueOf(HexRows.textCell((byte) b));
    }
    for (int d = 0; d < 16; d++) {
      DIGIT[d] = HEX[d].substring(1);
    }
  }

  /** What one paint shows. */
  record View(
      HexGrid grid,
      long topRow,
      int rows,
      HexSelection selection,
      boolean focused,
      boolean dimmed) {}

  private Color background;
  private Color text;
  private Color dimText;
  private Color dot;
  private Color dimDot;
  private Color offsetText;
  private Color gutter;
  private Color selection;
  private Color selectionText;
  private Color selectionDot;
  private Color caret;
  private Color twin;
  private Color invalid;

  HexPainter(Palette palette) {
    setPalette(palette);
  }

  /** Takes a palette's colors. */
  void setPalette(Palette palette) {
    background = color(palette.background());
    text = color(palette.text());
    dimText = color(half(palette.text(), palette.background()));
    Rgb ink = ControlBoxes.ink(palette.text(), palette.background());
    dot = color(ink);
    dimDot = color(half(ink, palette.background()));
    offsetText = color(palette.lineNumbers());
    gutter = color(palette.gutter());
    selection = color(palette.selection());
    selectionText = color(palette.selectionText());
    selectionDot = color(ControlBoxes.ink(palette.selectionText(), palette.selection()));
    caret = color(palette.caret());
    twin = color(palette.occurrence());
    invalid = color(palette.style(TokenRole.INVALID).foreground());
  }

  Color background() {
    return background;
  }

  /**
   * Paints the view.
   *
   * @param gc the canvas's GC with the view's font
   * @param width the canvas width
   * @param height the canvas height
   * @param view what to show
   * @param input supplies the rows
   */
  void paint(GC gc, int width, int height, View view, HexInput input) {
    HexGrid grid = view.grid();
    HexRows rows = grid.rows();
    int line = grid.lineHeight();
    gc.setBackground(background);
    gc.fillRectangle(0, 0, width, height);
    int offsetEnd = grid.x(rows.offsetColumn() + 1);
    gc.setBackground(gutter);
    gc.fillRectangle(0, 0, offsetEnd, height);
    gc.fillRectangle(offsetEnd, 0, width - offsetEnd, line);
    header(gc, grid);
    long count = rows.rowCount();
    for (int i = 0; i < view.rows() && view.topRow() + i < count; i++) {
      long r = view.topRow() + i;
      int y = grid.y(i);
      gc.setForeground(offsetText);
      gc.drawString(rows.offsetText(rows.rowStart(r)), grid.x(0), y, true);
      HexRow row = input.row(r);
      if (row != null) {
        cells(gc, grid, row, y, view);
      }
    }
    caret(gc, grid, view);
  }

  private void header(GC gc, HexGrid grid) {
    HexRows rows = grid.rows();
    gc.setForeground(offsetText);
    gc.drawString(UiText.HEX_HEADER_OFFSET.text(), grid.x(0), 0, true);
    StringBuilder run = new StringBuilder();
    for (int i = 0; i < rows.bytesPerRow(); i += HexRows.GROUP) {
      run.setLength(0);
      for (int b = i; b < i + HexRows.GROUP; b++) {
        run.append(b == i ? "" : " ").append(HEX[b]);
      }
      gc.drawString(run.toString(), grid.x(rows.hexColumn(i)), 0, true);
    }
    run.setLength(0);
    for (int i = 0; i < rows.bytesPerRow(); i++) {
      run.append(DIGIT[i & 0xf]);
    }
    gc.drawString(run.toString(), grid.x(rows.textColumn(0)), 0, true);
  }

  /**
   * Paints a row's cells: the backgrounds, then each run of cells of one color within a group as
   * one string at its first cell's x. A run's glyphs advance by the font's own advance, the one the
   * grid is measured from, so each glyph stays within half a pixel of its cell.
   */
  private void cells(GC gc, HexGrid grid, HexRow row, int y, View view) {
    HexRows rows = grid.rows();
    HexSelection s = view.selection();
    int line = grid.lineHeight();
    int length = row.length();
    int from = (int) Math.max(0, Math.min(length, s.start() - row.offset()));
    int to = (int) Math.max(0, Math.min(length, s.end() - row.offset()));
    if (from < to) {
      gc.setBackground(selection);
      int hx = grid.x(rows.hexColumn(from));
      gc.fillRectangle(hx, y, grid.x(rows.hexColumn(to - 1) + 2) - hx, line);
      int tx = grid.x(rows.textColumn(from));
      gc.fillRectangle(tx, y, grid.x(rows.textColumn(to)) - tx, line);
    } else if (s.length() == 0 && s.caret() >= row.offset() && s.caret() < row.offset() + length) {
      int b = (int) (s.caret() - row.offset());
      gc.setBackground(twin);
      int x = grid.x(s.textColumn() ? rows.hexColumn(b) : rows.textColumn(b));
      int right = s.textColumn() ? grid.x(rows.hexColumn(b) + 2) : grid.x(rows.textColumn(b) + 1);
      gc.fillRectangle(x, y, right - x, line);
    }
    StringBuilder run = new StringBuilder();
    int runStart = 0;
    Color runColor = null;
    for (int b = 0; b <= length; b++) {
      Color color = b < length ? digits(row, b, b >= from && b < to, view.dimmed()) : null;
      if (b == length || color != runColor || b % HexRows.GROUP == 0) {
        if (runColor != null) {
          gc.setForeground(runColor);
          gc.drawString(run.toString(), grid.x(rows.hexColumn(runStart)), y, true);
        }
        run.setLength(0);
        runStart = b;
        runColor = color;
      } else {
        run.append(' ');
      }
      if (b < length) {
        run.append(HEX[row.bytes()[b] & 0xff]);
      }
    }
    runColor = null;
    for (int b = 0; b <= length; b++) {
      Color color = b < length ? textColor(row, b, b >= from && b < to, view.dimmed()) : null;
      if (b == length || color != runColor) {
        if (runColor != null) {
          gc.setForeground(runColor);
          gc.drawString(run.toString(), grid.x(rows.textColumn(runStart)), y, true);
        }
        run.setLength(0);
        runStart = b;
        runColor = color;
      }
      if (b < length) {
        run.append(TEXT[row.bytes()[b] & 0xff]);
      }
    }
  }

  private Color digits(HexRow row, int b, boolean selected, boolean dimmed) {
    if (row.replaced(b)) {
      return invalid;
    }
    return selected ? selectionText : dimmed ? dimText : text;
  }

  private Color textColor(HexRow row, int b, boolean selected, boolean dimmed) {
    if (row.replaced(b) || row.printable(b)) {
      return digits(row, b, selected, dimmed);
    }
    return selected ? selectionDot : dimmed ? dimDot : dot;
  }

  /** Outlines the caret's cell in its column, after the last byte at the size. */
  private void caret(GC gc, HexGrid grid, View view) {
    HexRows rows = grid.rows();
    long at = view.selection().caret();
    long row = rows.rowOf(at);
    if (row < view.topRow() || row >= view.topRow() + view.rows()) {
      return;
    }
    int index = (int) (at - rows.rowStart(row));
    int y = grid.y((int) (row - view.topRow()));
    int x;
    int right;
    if (view.selection().textColumn()) {
      x = grid.x(rows.textColumn(index));
      right = grid.x(rows.textColumn(index) + 1);
    } else {
      x = grid.x(rows.hexColumn(index));
      right = grid.x(rows.hexColumn(index) + 2);
    }
    gc.setForeground(view.focused() ? caret : offsetText);
    gc.setLineWidth(view.focused() ? 2 : 1);
    int inset = view.focused() ? 1 : 0;
    gc.drawRectangle(x + inset, y + inset, right - x - 1 - inset, grid.lineHeight() - 1 - inset);
    gc.setLineWidth(1);
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  private static Rgb half(Rgb top, Rgb ground) {
    return new Rgb(
        (top.red() + ground.red()) / 2,
        (top.green() + ground.green()) / 2,
        (top.blue() + ground.blue()) / 2);
  }
}
