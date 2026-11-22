package io.github.ajdailey.qcaret.app.print;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.MarkdownBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pages of a rendered Markdown document on a {@link MarkdownPrintSheet}'s body, laid out once
 * from the row heights the sheet measures. Confined to one worker.
 *
 * <ul>
 *   <li>A paragraph line breaks between its wrapped rows. A code line, a table row, a heading and a
 *       rule never split; an image sits in one row, which never splits.
 *   <li>A heading never ends a page: it moves to the next page with the separator lines after it,
 *       unless it is the page's first line.
 *   <li>A table wider than the body has its columns narrowed to fit ({@link
 *       MarkdownTableLayout#fit}); a row is one row as tall as its tallest wrapped cell.
 *   <li>A table continued onto a page starts that page with its header row again.
 *   <li>Separator lines at the top of a page are left out.
 *   <li>A line that does not split and is taller than the body breaks between its rows; a row
 *       taller than the body takes a page of its own, cut at the body's bottom.
 * </ul>
 */
public final class MarkdownPageLayout {
  private final RenderedMarkdown rendered;
  private final MarkdownPrintSheet sheet;
  private final int height;
  private final MarkdownBlock.Kind[] kinds;
  private final int[] tableOf;
  private final MarkdownTableLayout[] tables;
  private final int[][] tableEdges;
  private final List<List<MarkdownPrintSheet.Placed>> pages =
      new ArrayList<List<MarkdownPrintSheet.Placed>>();
  private List<MarkdownPrintSheet.Placed> current = new ArrayList<MarkdownPrintSheet.Placed>();
  private int y;

  private MarkdownPageLayout(RenderedMarkdown rendered, MarkdownPrintSheet sheet) {
    this.rendered = rendered;
    this.sheet = sheet;
    this.height = Math.max(1, sheet.body().height());
    int lines = rendered.lineCount();
    this.kinds = new MarkdownBlock.Kind[lines];
    for (MarkdownBlock block : rendered.blocks()) {
      for (int i = 0; i < block.lineCount() && block.firstLine() + i < lines; i++) {
        kinds[block.firstLine() + i] = block.kind();
      }
    }
    this.tableOf = new int[lines];
    Arrays.fill(tableOf, -1);
    List<MarkdownTable> all = rendered.tables();
    this.tables = new MarkdownTableLayout[all.size()];
    this.tableEdges = new int[all.size()][];
    for (int t = 0; t < all.size(); t++) {
      MarkdownTable table = all.get(t);
      for (int row = 0; row < table.rowCount(); row++) {
        tableOf[table.firstLine() + row] = t;
      }
    }
  }

  /**
   * Lays out a render.
   *
   * @param rendered the render
   * @param sheet measures cells and rows; its images must be given first
   * @param cancel checked per line
   * @return the layout, at least one page
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static MarkdownPageLayout of(
      RenderedMarkdown rendered, MarkdownPrintSheet sheet, CancellationToken cancel) {
    MarkdownPageLayout layout = new MarkdownPageLayout(rendered, sheet);
    layout.measureTables(cancel);
    layout.paginate(cancel);
    return layout;
  }

  private void measureTables(CancellationToken cancel) {
    List<MarkdownTable> all = rendered.tables();
    for (int t = 0; t < all.size(); t++) {
      cancel.check();
      MarkdownTable table = all.get(t);
      tables[t] = new MarkdownTableLayout(table, rendered.text(), sheet.body().padding());
      tables[t].measure(sheet, Integer.MAX_VALUE);
      tables[t].fit(sheet, sheet.body().width() - sheet.indent(table.firstLine()));
      tableEdges[t] = tables[t].edges();
    }
  }

  private void paginate(CancellationToken cancel) {
    for (int line = 0; line < rendered.lineCount(); line++) {
      cancel.check();
      int[] rows = sheet.rows(line, tableRow(line));
      if (splits(line)) {
        for (int row = 0; row < rows.length; row++) {
          place(line, row, 1, rows[row]);
        }
      } else {
        place(line, 0, rows.length, sum(rows, 0, rows.length));
        if (sum(rows, 0, rows.length) > height && rows.length > 1) {
          splitTall(line, rows);
        }
      }
    }
    if (!current.isEmpty() || pages.isEmpty()) {
      pages.add(current);
    }
  }

  /** Places rows of a line, breaking the page before them when they do not fit. */
  private void place(int line, int firstRow, int rowCount, int rowsHeight) {
    boolean separator = kinds[line] == null;
    if (separator && current.isEmpty()) {
      return;
    }
    if (y + rowsHeight > height && !current.isEmpty()) {
      List<MarkdownPrintSheet.Placed> carried = carryHeading();
      pages.add(current);
      current = new ArrayList<MarkdownPrintSheet.Placed>();
      y = 0;
      for (MarkdownPrintSheet.Placed p : carried) {
        add(p.line(), p.firstRow(), p.rowCount(), heightOf(p));
      }
      if (separator && current.isEmpty()) {
        return;
      }
      int table = tableOf[line];
      if (table >= 0 && line != rendered.tables().get(table).firstLine()) {
        int header = rendered.tables().get(table).firstLine();
        int[] rows = sheet.rows(header, tableRow(header));
        add(header, 0, rows.length, sum(rows, 0, rows.length));
      }
    }
    add(line, firstRow, rowCount, rowsHeight);
  }

  /**
   * Replaces a line that does not split, placed whole, by its rows one at a time, so it breaks
   * between them.
   */
  private void splitTall(int line, int[] rows) {
    MarkdownPrintSheet.Placed whole = current.remove(current.size() - 1);
    y = whole.y();
    for (int row = 0; row < rows.length; row++) {
      if (y + rows[row] > height && !current.isEmpty()) {
        pages.add(current);
        current = new ArrayList<MarkdownPrintSheet.Placed>();
        y = 0;
      }
      add(line, row, 1, rows[row]);
    }
  }

  /**
   * Removes a heading from the end of the page, with the separator lines after it, unless it is the
   * page's first line.
   *
   * @return the lines removed, top first; empty when the page does not end with a heading
   */
  private List<MarkdownPrintSheet.Placed> carryHeading() {
    int last = current.size() - 1;
    while (last >= 0 && kinds[current.get(last).line()] == null) {
      last--;
    }
    if (last <= 0 || kinds[current.get(last).line()] != MarkdownBlock.Kind.HEADING) {
      return List.of();
    }
    List<MarkdownPrintSheet.Placed> tail =
        new ArrayList<MarkdownPrintSheet.Placed>(current.subList(last, current.size()));
    current.subList(last, current.size()).clear();
    return tail;
  }

  private void add(int line, int firstRow, int rowCount, int rowsHeight) {
    int table = tableOf[line];
    if (!current.isEmpty()) {
      MarkdownPrintSheet.Placed prev = current.get(current.size() - 1);
      if (prev.line() == line && prev.firstRow() + prev.rowCount() == firstRow) {
        current.set(
            current.size() - 1,
            new MarkdownPrintSheet.Placed(
                line,
                prev.firstRow(),
                prev.rowCount() + rowCount,
                prev.y(),
                prev.table(),
                prev.edges()));
        y += rowsHeight;
        return;
      }
    }
    current.add(
        new MarkdownPrintSheet.Placed(
            line, firstRow, rowCount, y, tableRow(line), table < 0 ? null : tableEdges[table]));
    y += rowsHeight;
  }

  private int heightOf(MarkdownPrintSheet.Placed p) {
    int[] rows = sheet.rows(p.line(), p.table());
    return sum(rows, p.firstRow(), p.firstRow() + p.rowCount());
  }

  private MarkdownTableLayout.Row tableRow(int line) {
    int table = tableOf[line];
    return table < 0 ? null : tables[table].row(line - rendered.tables().get(table).firstLine());
  }

  /** Reports whether a line may break between its rows. */
  private boolean splits(int line) {
    MarkdownBlock.Kind kind = kinds[line];
    return kind != MarkdownBlock.Kind.CODE
        && kind != MarkdownBlock.Kind.TABLE
        && kind != MarkdownBlock.Kind.HEADING
        && kind != MarkdownBlock.Kind.RULE
        && !rendered.code(line);
  }

  private static int sum(int[] values, int from, int to) {
    int total = 0;
    for (int i = from; i < to; i++) {
      total += values[i];
    }
    return total;
  }

  /**
   * Returns the page count.
   *
   * @return at least 1
   */
  public int pageCount() {
    return pages.size();
  }

  /**
   * Returns a page.
   *
   * @param number the page, from 1 to {@link #pageCount}
   * @return the page
   */
  public MarkdownPrintSheet.Page page(int number) {
    return new MarkdownPrintSheet.Page(number, pages.size(), List.copyOf(pages.get(number - 1)));
  }
}
