package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Outline dock's entries from a render's headings. A heading's parent is the closest
 * earlier heading with a lower level, so a skipped level nests one step: an H3 under an H1 is the
 * H1's child. A heading with no lower heading before it is a top entry.
 */
final class OutlineTree {
  /** Most entries listed. */
  static final int MAX_ENTRIES = 10_000;

  /** Chars of each heading's text listed. */
  static final int TEXT_CHARS = 160;

  private OutlineTree() {}

  /**
   * Builds the entries of the first {@link #MAX_ENTRIES} headings.
   *
   * @param headings the headings in document order
   * @return the entries, each parent before its children
   */
  @WorkerThread
  static List<OutlineEntry> of(List<MarkdownHeading> headings) {
    int count = Math.min(headings.size(), MAX_ENTRIES);
    List<OutlineEntry> out = new ArrayList<OutlineEntry>(count);
    // The open ancestors' indexes, outermost first; their levels rise strictly.
    int[] open = new int[7];
    int depth = 0;
    for (int i = 0; i < count; i++) {
      MarkdownHeading h = headings.get(i);
      while (depth > 0 && out.get(open[depth - 1]).level() >= h.level()) {
        depth--;
      }
      int parent = depth == 0 ? -1 : open[depth - 1];
      String text = h.text();
      if (text.length() > TEXT_CHARS) {
        int end = TEXT_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
          end--;
        }
        text = text.substring(0, end);
      }
      out.add(new OutlineEntry(text, h.level(), parent, h.sourceLine()));
      open[depth++] = i;
    }
    return List.copyOf(out);
  }

  /**
   * Finds the entry holding a source line: the last one starting at or above it.
   *
   * @param entries the entries, by source line
   * @param line a zero-based source line
   * @return the entry's index, or -1 above the first entry
   */
  static int at(List<OutlineEntry> entries, int line) {
    int lo = 0;
    int hi = entries.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (entries.get(mid).sourceLine() <= line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }
}
