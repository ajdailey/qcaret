package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * A render as an HTML fragment for the Browser preview: the body's content, without the page's head
 * and stylesheet, and the element ids that scroll to a source line.
 *
 * @param body the HTML; local images embedded as {@code data:} URIs
 * @param lines source lines, ascending, each the first line of the element of the same index in
 *     {@code ids}. Shared, not copied: do not modify
 * @param ids the id of each line's element: a heading's GitLab anchor, else {@code
 *     qcaret-line-<line>}
 */
public record MarkdownHtml(String body, int[] lines, List<String> ids) {
  /** Copies the ids. */
  public MarkdownHtml {
    ids = List.copyOf(ids);
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
