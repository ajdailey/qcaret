package io.github.ajdailey.qcaret.core.document;

import java.util.Arrays;
import java.util.List;

/**
 * Maps offsets across one applied batch. A position inside a replaced range, or at the start of
 * one, maps by its {@link Bias} to the start or the end of the inserted text; a position at the end
 * of a removed range maps to the end of the inserted text.
 */
final class ChangeMap {
  private final int[] offsets;
  private final int[] removed;
  private final int[] inserted;

  /** Shift before change i: inserted minus removed chars of changes 0 to i - 1. */
  private final int[] shifts;

  ChangeMap(List<Change> changes) {
    int n = changes.size();
    offsets = new int[n];
    removed = new int[n];
    inserted = new int[n];
    shifts = new int[n];
    int shift = 0;
    for (int i = 0; i < n; i++) {
      Change c = changes.get(i);
      offsets[i] = c.offset();
      removed[i] = c.removed().length();
      inserted[i] = c.inserted().length();
      shifts[i] = shift;
      shift += inserted[i] - removed[i];
    }
  }

  /** Returns the number of replacements. */
  int size() {
    return offsets.length;
  }

  /** Maps an offset in the text before the batch to the text after it. */
  int map(int offset, Bias bias) {
    int i = Arrays.binarySearch(offsets, offset);
    if (i < 0) {
      i = -i - 2;
    }
    if (i < 0) {
      return offset;
    }
    int start = offsets[i] + shifts[i];
    int end = offsets[i] + removed[i];
    if (offset > end || (offset == end && removed[i] > 0)) {
      return offset + shifts[i] + inserted[i] - removed[i];
    }
    return bias == Bias.BEFORE ? start : start + inserted[i];
  }
}
