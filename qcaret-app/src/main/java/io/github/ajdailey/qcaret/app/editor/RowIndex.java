package io.github.ajdailey.qcaret.app.editor;

import java.util.Arrays;

/**
 * The model lines that span more than one display row, with their row breaks, sorted by line. A
 * line without an entry is one row. Entry {@code j} holds the breaks {@code breaks[first[j]]} up to
 * {@code breaks[first[j + 1]]}, so {@code first[j]} is also the number of virtual breaks in the
 * lines before entry {@code j}. Edits replace a line range and shift the entries after it: O(log
 * entries) when the line count and the number of entries and breaks are unchanged, else a pass over
 * the tail. {@link #replaceAll} applies several ranges in one pass over all entries. Confined to
 * one thread.
 */
final class RowIndex {
  private int size;
  private int[] lines;
  private int[] first;
  private int[] breaks;

  RowIndex() {
    this(new int[8], new int[9], new int[8], 0);
  }

  private RowIndex(int[] lines, int[] first, int[] breaks, int size) {
    this.lines = lines;
    this.first = first;
    this.breaks = breaks;
    this.size = size;
  }

  /** Collects entries in line order. */
  static final class Builder {
    private int[] lines = new int[16];
    private int[] first = new int[17];
    private int[] breaks = new int[16];
    private int size;
    private int visited;
    private int measured;

    /** Counts lines read for this builder, measured or only scanned. */
    void visit(int lines) {
      visited += lines;
    }

    /** Returns the lines {@link #visit} counted. */
    int visited() {
      return visited;
    }

    /** Returns the lines passed to {@link #add}, with or without breaks. */
    int measured() {
      return measured;
    }

    /**
     * Adds a line's breaks; lines must ascend. A line without breaks is skipped.
     *
     * @param line the model line
     * @param rowBreaks its breaks, ascending
     */
    void add(int line, int[] rowBreaks) {
      measured++;
      if (rowBreaks.length == 0) {
        return;
      }
      if (size == lines.length) {
        lines = Arrays.copyOf(lines, size * 2);
        first = Arrays.copyOf(first, size * 2 + 1);
      }
      int at = first[size];
      if (at + rowBreaks.length > breaks.length) {
        breaks = Arrays.copyOf(breaks, Math.max(breaks.length * 2, at + rowBreaks.length));
      }
      System.arraycopy(rowBreaks, 0, breaks, at, rowBreaks.length);
      lines[size] = line;
      size++;
      first[size] = at + rowBreaks.length;
    }

    int size() {
      return size;
    }

    RowIndex build() {
      return new RowIndex(lines, first, breaks, size);
    }
  }

  /** Returns an independent copy, for a second view's projection. */
  RowIndex copy() {
    return new RowIndex(lines.clone(), first.clone(), breaks.clone(), size);
  }

  /**
   * Returns the number of virtual breaks.
   *
   * @return breaks over all lines
   */
  int totalBreaks() {
    return first[size];
  }

  /**
   * Returns the number of entries.
   *
   * @return lines with more than one row
   */
  int entries() {
    return size;
  }

  /** Returns the index of the first entry whose line is at or after {@code line}. */
  int lowerBound(int line) {
    int lo = 0;
    int hi = size;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (lines[mid] < line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** Returns the virtual breaks in lines before {@code line}. */
  int breaksBefore(int line) {
    return first[lowerBound(line)];
  }

  /** Returns the entry of a line, or -1. */
  int entryOf(int line) {
    int j = lowerBound(line);
    return j < size && lines[j] == line ? j : -1;
  }

  int line(int entry) {
    return lines[entry];
  }

  int firstBreak(int entry) {
    return first[entry];
  }

  int breakCount(int entry) {
    return first[entry + 1] - first[entry];
  }

  /** Returns break {@code k} of an entry: the in-line offset where row {@code k + 1} starts. */
  int breakAt(int entry, int k) {
    return breaks[first[entry] + k];
  }

  /** Returns a line's breaks. */
  int[] breaksOf(int line) {
    int j = entryOf(line);
    return j < 0 ? RowBreaks.NONE : Arrays.copyOfRange(breaks, first[j], first[j + 1]);
  }

  /**
   * Returns the last entry whose first display row is at or before {@code row}, or -1. Entry j's
   * first row is {@code lines[j] + first[j]}, which ascends with j.
   */
  int entryAtOrBeforeRow(int row) {
    int lo = 0;
    int hi = size - 1;
    int found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (lines[mid] + first[mid] <= row) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return found;
  }

  /**
   * Replaces the entries of lines {@code [from, oldEnd)} with those of {@code replacement}, whose
   * lines lie in {@code [from, newEnd)}, and shifts the lines after by {@code newEnd - oldEnd}.
   */
  void replace(int from, int oldEnd, int newEnd, Builder replacement) {
    int j0 = lowerBound(from);
    int j1 = lowerBound(oldEnd);
    int delta = newEnd - oldEnd;
    int k = replacement.size;
    int b0 = first[j0];
    int oldTotal = first[size];
    int removedBreaks = first[j1] - b0;
    int addedBreaks = replacement.first[k];
    int entryShift = k - (j1 - j0);
    int breakShift = addedBreaks - removedBreaks;
    int tail = size - j1;
    if (entryShift != 0) {
      if (size + entryShift > lines.length) {
        int capacity = Math.max(lines.length * 2, size + entryShift);
        lines = Arrays.copyOf(lines, capacity);
        first = Arrays.copyOf(first, capacity + 1);
      }
      System.arraycopy(lines, j1, lines, j1 + entryShift, tail);
      System.arraycopy(first, j1 + 1, first, j1 + 1 + entryShift, tail);
    }
    if (breakShift != 0) {
      if (oldTotal + breakShift > breaks.length) {
        breaks = Arrays.copyOf(breaks, Math.max(breaks.length * 2, oldTotal + breakShift));
      }
      System.arraycopy(
          breaks, b0 + removedBreaks, breaks, b0 + addedBreaks, oldTotal - b0 - removedBreaks);
    }
    for (int i = 0; i < k; i++) {
      lines[j0 + i] = replacement.lines[i];
      first[j0 + i] = b0 + replacement.first[i];
    }
    System.arraycopy(replacement.breaks, 0, breaks, b0, addedBreaks);
    size += entryShift;
    first[j0 + k] = b0 + addedBreaks;
    if (delta != 0 || breakShift != 0) {
      for (int j = j0 + k; j < size; j++) {
        lines[j] += delta;
        first[j + 1] += breakShift;
      }
    }
  }

  /**
   * Applies {@link #replace} for each range in one pass over the entries, for a many-caret edit
   * whose sequential replaces would each shift every later entry. Range {@code i} is {@code
   * [from[i], oldEnd[i])}, its replacement's lines lie in {@code [from[i], newEnd[i])}, and both
   * are in the line numbers before every range is applied; the ranges ascend and do not overlap.
   */
  void replaceAll(int[] from, int[] oldEnd, int[] newEnd, Builder[] replacements) {
    int n = from.length;
    int[] j0 = new int[n];
    int[] j1 = new int[n];
    int outSize = size;
    int outTotal = first[size];
    for (int i = 0; i < n; i++) {
      j0[i] = lowerBound(from[i]);
      j1[i] = lowerBound(oldEnd[i]);
      Builder b = replacements[i];
      outSize += b.size - (j1[i] - j0[i]);
      outTotal += b.first[b.size] - (first[j1[i]] - first[j0[i]]);
    }
    int[] outLines = new int[Math.max(8, outSize)];
    int[] outFirst = new int[outLines.length + 1];
    int[] outBreaks = new int[Math.max(8, outTotal)];
    int at = 0;
    int written = 0;
    int j = 0;
    int delta = 0;
    for (int i = 0; i <= n; i++) {
      int end = i < n ? j0[i] : size;
      int kept = first[end] - first[j];
      System.arraycopy(breaks, first[j], outBreaks, written, kept);
      int shift = written - first[j];
      for (; j < end; j++) {
        outLines[at] = lines[j] + delta;
        outFirst[at++] = first[j] + shift;
      }
      written += kept;
      if (i == n) {
        break;
      }
      Builder b = replacements[i];
      System.arraycopy(b.breaks, 0, outBreaks, written, b.first[b.size]);
      for (int r = 0; r < b.size; r++) {
        outLines[at] = b.lines[r] + delta;
        outFirst[at++] = written + b.first[r];
      }
      written += b.first[b.size];
      j = j1[i];
      delta += newEnd[i] - oldEnd[i];
    }
    outFirst[at] = written;
    lines = outLines;
    first = outFirst;
    breaks = outBreaks;
    size = at;
  }
}
