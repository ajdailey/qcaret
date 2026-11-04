package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * A replacement with its removed text, in the coordinates of the text before its batch. A list of
 * changes is sorted by offset and non-overlapping.
 */
record Change(int offset, String removed, String inserted) {
  /** Returns the batch that undoes {@code changes}, in the coordinates after them. */
  static List<Change> inverse(List<Change> changes) {
    List<Change> out = new ArrayList<Change>(changes.size());
    int shift = 0;
    for (Change c : changes) {
      out.add(new Change(c.offset + shift, c.inserted, c.removed));
      shift += c.inserted.length() - c.removed.length();
    }
    return out;
  }

  /** Returns the chars held for undo, at 2 bytes per char. */
  static long bytes(List<Change> changes) {
    long chars = 0;
    for (Change c : changes) {
      chars += c.removed.length() + c.inserted.length();
    }
    return 2 * chars;
  }
}
