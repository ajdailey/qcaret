package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import java.util.List;

/**
 * The pages of one rendered Markdown print job: measures the render's lines in the printer's pixels
 * and draws laid-out pages. A line lays out with Basic's styles at the body's width; a table row
 * wraps each cell inside its column. Every call runs on the job's thread, between {@link
 * PrintTarget#startMarkdown} and {@link PrintTarget#finish} or {@link PrintTarget#close}. A failing
 * call throws {@link PrintTarget.PrintFailure}.
 */
public interface MarkdownPrintSheet extends TextMeasurer {
  /**
   * The area below the page header.
   *
   * @param width pixels
   * @param height pixels
   * @param padding pixels on each side of a table cell's text
   */
  record Body(int width, int height, int padding) {}

  /**
   * Rows of one rendered line placed on a page.
   *
   * @param line the rendered line
   * @param firstRow its first row drawn, from 0
   * @param rowCount the rows drawn
   * @param y pixels from the body's top to the first row drawn
   * @param table the row's placement in its table, or null outside a table
   * @param edges the table's column edges, or null outside a table
   */
  record Placed(
      int line, int firstRow, int rowCount, int y, MarkdownTableLayout.Row table, int[] edges) {}

  /**
   * One page.
   *
   * @param number the page, from 1
   * @param total the page count of the whole layout, also when a range prints fewer
   * @param lines the placed lines, top to bottom
   */
  record Page(int number, int total, List<Placed> lines) {}

  /**
   * Returns the page's body.
   *
   * @return its size
   */
  Body body();

  /**
   * Takes an image's file content before any line is measured. The image prints at the request's
   * size, no wider and no taller than the body.
   *
   * @param request the image
   * @param bytes the file's content, empty when it was not read; an unreadable image prints as a
   *     box with its file name
   */
  void image(MarkdownImageRequest request, byte[] bytes);

  /**
   * Returns the indent a line's quotes and lists take.
   *
   * @param line a rendered line
   * @return pixels from the body's left edge
   */
  int indent(int line);

  /**
   * Lays out a rendered line and returns the height of each row it takes.
   *
   * @param line the rendered line
   * @param table the row's placement in its table, or null outside a table
   * @return at least one height, top row first; one height for a table row
   */
  int[] rows(int line, MarkdownTableLayout.Row table);

  /**
   * Prints a page.
   *
   * @param page the page
   */
  void page(Page page);
}
