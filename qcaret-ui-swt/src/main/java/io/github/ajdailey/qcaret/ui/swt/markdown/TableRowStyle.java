package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;

/** Table lookups and the indent and tab stops of a laid-out row's display lines. */
final class TableRowStyle {
  private TableRowStyle() {}

  /**
   * Finds the table holding a line.
   *
   * @param r the render
   * @param line a rendered line
   * @return the table's index, or -1
   */
  static int tableAt(RenderedMarkdown r, int line) {
    List<MarkdownTable> all = r.tables();
    int lo = 0;
    int hi = all.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (all.get(mid).firstLine() <= line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo == 0) {
      return -1;
    }
    MarkdownTable t = all.get(lo - 1);
    return line < t.firstLine() + t.rowCount() ? lo - 1 : -1;
  }

  /**
   * Reports whether a line is a table's header row.
   *
   * @param r the render
   * @param line a rendered line
   * @return true for the first row of a table
   */
  static boolean header(RenderedMarkdown r, int line) {
    int table = tableAt(r, line);
    return table >= 0 && r.tables().get(table).firstLine() == line;
  }

  /**
   * Sets the indent and tab stops of a row's display line, which start each cell segment in its
   * column.
   *
   * @param style the line's style
   * @param base the indent of the row's quotes and lists
   * @param row the row
   * @param sub the display line's index within the row
   */
  static void indent(MarkdownStyler.LineStyle style, int base, TableWrap.Row row, int sub) {
    style.indent = base + row.indents()[sub];
    int[] rowStops = row.tabStops()[sub];
    if (rowStops.length > 0) {
      int[] stops = new int[rowStops.length];
      for (int i = 0; i < stops.length; i++) {
        stops[i] = base + rowStops[i];
      }
      style.tabStops = stops;
    }
  }
}
