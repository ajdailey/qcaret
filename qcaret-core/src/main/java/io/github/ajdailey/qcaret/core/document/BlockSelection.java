package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import java.util.ArrayList;
import java.util.List;

/**
 * A rectangular selection in lines and visual columns ({@link Columns}). It covers every line from
 * the anchor's to the caret's, between the two columns.
 *
 * @param anchorLine the line where the block started
 * @param anchorColumn the column where the block started
 * @param caretLine the line of the moving corner
 * @param caretColumn the column of the moving corner
 */
public record BlockSelection(int anchorLine, int anchorColumn, int caretLine, int caretColumn) {
  /** Checks the components. */
  public BlockSelection {
    if (anchorLine < 0 || anchorColumn < 0 || caretLine < 0 || caretColumn < 0) {
      throw new IllegalArgumentException("negative line or column");
    }
  }

  /**
   * Returns one row per line of the block, top first. A block as wide as nothing has empty rows,
   * before a tab or wide char its column falls inside.
   *
   * @param text the text; every line of the block must exist
   * @param tabWidth columns per tab stop
   * @return the rows
   */
  public List<BlockRow> rows(TextContent text, int tabWidth) {
    int left = Math.min(anchorColumn, caretColumn);
    int right = Math.max(anchorColumn, caretColumn);
    int top = Math.min(anchorLine, caretLine);
    int bottom = Math.max(anchorLine, caretLine);
    List<BlockRow> rows = new ArrayList<BlockRow>(bottom - top + 1);
    LineView view = new LineView();
    for (int line = top; line <= bottom; line++) {
      text.lineView(line, view);
      int start = Columns.index(view, left, tabWidth, false);
      int end = left == right ? start : Columns.index(view, right, tabWidth, true);
      int padding = 0;
      if (start == view.length()) {
        padding = Math.max(0, left - Columns.column(view, start, tabWidth));
      }
      rows.add(
          new BlockRow(line, view.start() + start, view.start() + Math.max(start, end), padding));
    }
    return rows;
  }

  /**
   * Returns the block as one selection per line, anchored on the anchor column's side; the caret's
   * line holds the primary.
   *
   * @param text the text
   * @param tabWidth columns per tab stop
   * @return the selections
   */
  public SelectionSet toSelections(TextContent text, int tabWidth) {
    List<BlockRow> rows = rows(text, tabWidth);
    List<Selection> out = new ArrayList<Selection>(rows.size());
    boolean leftAnchor = anchorColumn <= caretColumn;
    int primary = 0;
    for (BlockRow row : rows) {
      if (row.line() == caretLine) {
        primary = out.size();
      }
      out.add(
          leftAnchor
              ? new Selection(row.start(), row.end(), caretColumn)
              : new Selection(row.end(), row.start(), caretColumn));
    }
    return SelectionSet.of(out, primary);
  }
}
