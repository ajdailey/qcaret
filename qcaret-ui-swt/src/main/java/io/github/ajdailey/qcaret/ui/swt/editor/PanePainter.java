package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.editor.LineEnds;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * What an {@link EditorPane} paints over its {@link StyledText}: the gutter's line numbers, fold
 * markers and bookmark marks, the gutter's tool tip, a collapsed region's ellipsis box, the
 * whitespace and line-ending marks, and the secondary carets or a block selection. Reads the pane's
 * font, metrics and gutter geometry at each paint, and repaints the widget when the {@link
 * Bookmarks} it follows change.
 */
final class PanePainter {
  private final EditorPane pane;
  private final StyledText text;
  private final DisplayProjection projection;
  private final SelectionModel selections;
  private Color gutter;
  private Color lineNumbers;
  private Color selectionBack;
  private Color caret;
  private Color bookmarkComment;
  private Color whitespace;
  private final Runnable bookmarksChanged =
      new Runnable() {
        @Override
        public void run() {
          if (!text.isDisposed()) {
            text.redraw();
          }
        }
      };
  private Bookmarks bookmarks;
  private boolean markWhitespace;
  private boolean markLineEndings;

  PanePainter(
      EditorPane pane, StyledText text, DisplayProjection projection, SelectionModel selections) {
    this.pane = pane;
    this.text = text;
    this.projection = projection;
    this.selections = selections;
  }

  /** Takes the gutter, line number, selection, caret, bookmark and whitespace colors. */
  void setPalette(Palette palette) {
    selectionBack = color(palette.selection());
    gutter = color(palette.gutter());
    lineNumbers = color(palette.lineNumbers());
    caret = color(palette.caret());
    bookmarkComment = color(palette.bookmarkComment());
    whitespace = color(palette.whitespace());
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Follows a document's bookmarks in place of the previous ones and repaints. */
  void setBookmarks(Bookmarks value) {
    if (bookmarks != null) {
      bookmarks.removeListener(bookmarksChanged);
    }
    bookmarks = value;
    value.addListener(bookmarksChanged);
    bookmarksChanged.run();
  }

  /** Stops following the bookmarks. */
  void release() {
    if (bookmarks != null) {
      bookmarks.removeListener(bookmarksChanged);
    }
  }

  /** Shows or hides the marks; returns true when either changed. */
  boolean setMarks(boolean whitespaceMarks, boolean lineEndingMarks) {
    if (markWhitespace != whitespaceMarks || markLineEndings != lineEndingMarks) {
      markWhitespace = whitespaceMarks;
      markLineEndings = lineEndingMarks;
      return true;
    }
    return false;
  }

  boolean[] marks() {
    return new boolean[] {markWhitespace, markLineEndings};
  }

  void paintGutter(GC gc, int y, int height) {
    ColumnMetrics metrics = pane.metrics();
    gc.setBackground(gutter);
    gc.fillRectangle(0, y, pane.gutterWidth(), height);
    gc.setForeground(lineNumbers);
    gc.setFont(pane.font());
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      if (projection.subRow(row) == 0) {
        int line = projection.lineOfRow(row);
        if (pane.lineNumbersShown()) {
          String number = Integer.toString(line + 1);
          int x = pane.foldColumn() - EditorPane.GUTTER_PADDING - metrics.x(number.length());
          gc.drawString(number, x, top, true);
        }
        if (projection.foldEnd(line) >= 0) {
          paintFoldMarker(gc, top, projection.collapsed(line));
        }
      }
    }
  }

  /**
   * Draws a fold marker box in the gutter's fold column: a minus sign, or a plus when collapsed.
   */
  private void paintFoldMarker(GC gc, int top, boolean collapsed) {
    ColumnMetrics metrics = pane.metrics();
    int lineHeight = text.getLineHeight();
    int size = Math.max(5, Math.min(metrics.x(EditorPane.FOLD_COLUMNS) - 4, lineHeight - 4)) | 1;
    int x = pane.foldColumn() + (metrics.x(EditorPane.FOLD_COLUMNS) - size) / 2;
    int y = top + (lineHeight - size) / 2;
    int middle = size / 2;
    gc.drawRectangle(x, y, size - 1, size - 1);
    gc.drawLine(x + 2, y + middle, x + size - 3, y + middle);
    if (collapsed) {
      gc.drawLine(x + middle, y + 2, x + middle, y + size - 3);
    }
  }

  /** Draws an ellipsis box after the last row of each visible collapsed region's first line. */
  void paintCollapsed(GC gc) {
    if (projection.collapsedCount() == 0) {
      return;
    }
    ColumnMetrics metrics = pane.metrics();
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    gc.setForeground(lineNumbers);
    gc.setFont(pane.font());
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int line = projection.lineOfRow(row);
      boolean lastRow = row + 1 >= rows || projection.lineOfRow(row + 1) != line;
      if (!lastRow || !projection.collapsed(line)) {
        continue;
      }
      Point end =
          text.getLocationAtOffset(projection.rowStart(row) + projection.rowText(row).length());
      int x = end.x + metrics.x(1);
      if (x >= text.getLeftMargin()) {
        gc.drawRectangle(x, top + 1, metrics.x(3) + 1, lineHeight - 3);
        gc.drawString("...", x + 1, top, true);
      }
    }
  }

  void paintCarets(GC gc) {
    SelectionSet set = selections.selections();
    if (set.size() == 1) {
      return;
    }
    Rectangle area = text.getClientArea();
    int top = text.getTopIndex();
    int bottom = Math.min(projection.rowCount() - 1, text.getLineIndex(area.height));
    int from = projection.toModel(projection.rowStart(top));
    int to = projection.toModel(projection.rowStart(bottom) + projection.rowText(bottom).length());
    int lineHeight = text.getLineHeight();
    gc.setBackground(caret);
    List<Selection> list = set.selections();
    for (int i = PaneHighlights.firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() > to) {
        break;
      }
      if (i == set.primaryIndex()
          || s.caret() < from
          || s.caret() > to
          || projection.hidden(projection.lineAt(s.caret()))) {
        continue;
      }
      Point at = text.getLocationAtOffset(projection.toDisplay(s.caret()));
      if (at.x >= text.getLeftMargin() - EditorPane.CARET_WIDTH) {
        gc.fillRectangle(at.x, at.y, EditorPane.CARET_WIDTH, lineHeight);
      }
    }
  }

  /**
   * Paints a block on the visible rows: its part past each short line's end in the selection color,
   * and each row's caret but the widget's own, past the line end at the block's caret column.
   */
  void paintBlock(GC gc) {
    ColumnMetrics metrics = pane.metrics();
    EditorLayout layout = pane.editorLayout();
    BlockSelection block = selections.block();
    SelectionSet set = selections.selections();
    Rectangle area = text.getClientArea();
    int top = text.getTopIndex();
    int bottom = Math.min(projection.rowCount() - 1, text.getLineIndex(area.height));
    int from = projection.toModel(projection.rowStart(top));
    int to = projection.toModel(projection.rowStart(bottom) + projection.rowText(bottom).length());
    int left = Math.min(block.anchorColumn(), block.caretColumn());
    int right = Math.max(block.anchorColumn(), block.caretColumn());
    int lineHeight = text.getLineHeight();
    List<Selection> list = set.selections();
    for (int i = PaneHighlights.firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() > to) {
        break;
      }
      Point end = text.getLocationAtOffset(projection.toDisplay(s.end()));
      int endColumn = layout.lineColumn(projection.toDisplay(s.end()));
      if (endColumn < right) {
        int x0 = end.x + metrics.x(Math.max(0, left - endColumn));
        int x1 = end.x + metrics.x(right - endColumn);
        gc.setBackground(selectionBack);
        gc.fillRectangle(x0, end.y, x1 - x0, lineHeight);
      }
      int caretColumn = layout.lineColumn(projection.toDisplay(s.caret()));
      boolean virtual = caretColumn < block.caretColumn();
      if (i == set.primaryIndex() && !virtual) {
        continue;
      }
      Point at = text.getLocationAtOffset(projection.toDisplay(s.caret()));
      int x = virtual ? at.x + metrics.x(block.caretColumn() - caretColumn) : at.x;
      if (x >= text.getLeftMargin() - EditorPane.CARET_WIDTH) {
        gc.setBackground(caret);
        gc.fillRectangle(x, at.y, EditorPane.CARET_WIDTH, lineHeight);
      }
    }
  }

  /**
   * Paints the bookmark column on the first row of each visible bookmarked line: a bar in the caret
   * color, or for a commented bookmark the bar and a square at its top right in the comment color.
   */
  void paintBookmarks(GC gc) {
    if (bookmarks == null) {
      return;
    }
    int[] lines = bookmarks.lines();
    if (lines.length == 0) {
      return;
    }
    int[] commented = bookmarks.commentedLines();
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int line = projection.lineOfRow(row);
      if (projection.subRow(row) == 0 && Arrays.binarySearch(lines, line) >= 0) {
        int height = Math.max(1, lineHeight - 2);
        if (Arrays.binarySearch(commented, line) >= 0) {
          gc.setBackground(bookmarkComment);
          gc.fillRectangle(
              EditorPane.BOOKMARK_BAR,
              top + 1,
              EditorPane.BOOKMARK_COLUMN - EditorPane.BOOKMARK_BAR - 3,
              Math.min(height, 7));
        } else {
          gc.setBackground(caret);
        }
        gc.fillRectangle(1, top + 1, EditorPane.BOOKMARK_BAR - 1, height);
      }
    }
  }

  /**
   * Shows a commented bookmark's comment as the tooltip while the pointer is over the gutter left
   * of the fold markers on its line; elsewhere left of the text, the modified-line limit notice
   * while the marks are over the limit; no tooltip otherwise.
   */
  void updateToolTip(int x, int y) {
    String tip = null;
    if (bookmarks != null && x >= 0 && x < pane.foldColumn() && projection.rowCount() > 0) {
      int row = text.getLineIndex(y);
      int top = text.getLinePixel(row);
      if (y >= top && y < top + text.getLineHeight()) {
        tip = bookmarks.comment(projection.lineOfRow(row));
      }
    }
    if (tip == null && x >= 0 && x < text.getLeftMargin() && pane.lineChanges().overLimit()) {
      tip = UiText.LINE_CHANGES_OVER_LIMIT.format(LineChanges.MAX_LINES, LineChanges.MAX_CHARS);
    }
    if (!Objects.equals(tip, text.getToolTipText())) {
      text.setToolTipText(tip);
    }
  }

  /** Paints the whitespace and line-ending marks on the visible rows. */
  void paintMarks(GC gc) {
    if (!markWhitespace && !markLineEndings) {
      return;
    }
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    gc.setForeground(whitespace);
    gc.setBackground(whitespace);
    gc.setFont(pane.font());
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      String rowText = projection.rowText(row);
      int rowStart = projection.rowStart(row);
      if (markWhitespace) {
        paintBlanks(gc, rowText, rowStart, top + lineHeight / 2);
      }
      int line = projection.lineOfRow(row);
      if (markLineEndings && (row + 1 == rows || projection.lineOfRow(row + 1) != line)) {
        LineEnding ending = LineEnds.of(projection.document(), line);
        if (ending != null) {
          Point end = text.getLocationAtOffset(rowStart + rowText.length());
          gc.drawString(ending.name(), end.x + pane.metrics().x(1) / 2, top, true);
        }
      }
    }
  }

  /** Paints a dot in each space and an arrow across each tab of one row. */
  private void paintBlanks(GC gc, String rowText, int rowStart, int middle) {
    int i = 0;
    int length = rowText.length();
    while (i < length) {
      char c = rowText.charAt(i);
      if (c == ' ') {
        int j = i;
        while (j < length && rowText.charAt(j) == ' ') {
          j++;
        }
        int x0 = text.getLocationAtOffset(rowStart + i).x;
        int x1 = text.getLocationAtOffset(rowStart + j).x;
        int n = j - i;
        for (int k = 0; k < n; k++) {
          int x = x0 + (x1 - x0) * (2 * k + 1) / (2 * n);
          gc.fillRectangle(x - 1, middle - 1, 2, 2);
        }
        i = j;
      } else if (c == '\t') {
        int x0 = text.getLocationAtOffset(rowStart + i).x + 2;
        int x1 = text.getLocationAtOffset(rowStart + i + 1).x - 2;
        if (x1 > x0) {
          int head = Math.min(3, x1 - x0);
          gc.drawLine(x0, middle, x1, middle);
          gc.drawLine(x1 - head, middle - head, x1, middle);
          gc.drawLine(x1 - head, middle + head, x1, middle);
        }
        i++;
      } else {
        i++;
      }
    }
  }
}
