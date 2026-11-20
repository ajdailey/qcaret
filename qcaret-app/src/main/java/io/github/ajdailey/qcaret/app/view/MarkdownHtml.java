package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * A render as an HTML fragment for the Browser preview: the body's content, the element ids that
 * scroll to a source line, and the full page the browser shows.
 *
 * @param body the HTML; local images embedded as {@code data:} URIs
 * @param lines source lines, ascending, each the first line of the element of the same index in
 *     {@code ids}. Shared, not copied: do not modify
 * @param ids the id of each line's element: a heading's GitLab anchor, else {@code
 *     qcaret-line-<line>}
 * @param page the body inside its page with a palette's stylesheet, built off the UI thread; null
 *     before it is built
 */
public record MarkdownHtml(String body, int[] lines, List<String> ids, String page) {
  /** Copies the ids. */
  public MarkdownHtml {
    ids = List.copyOf(ids);
  }

  /**
   * Creates a fragment without its page.
   *
   * @param body the HTML
   * @param lines the source lines
   * @param ids the ids
   */
  public MarkdownHtml(String body, int[] lines, List<String> ids) {
    this(body, lines, ids, null);
  }

  /**
   * Returns this fragment with a page.
   *
   * @param next the page
   * @return a copy carrying {@code next}
   */
  public MarkdownHtml withPage(String next) {
    return new MarkdownHtml(body, lines, ids, next);
  }

  /**
   * Returns the id of the last element starting at or above a source line.
   *
   * @param line a source line
   * @return the id, or null when no element starts at or above it
   */
  public String idAt(int line) {
    int low = 0;
    int high = lines.length - 1;
    int found = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (lines[mid] <= line) {
        found = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return found < 0 ? null : ids.get(found);
  }
}
