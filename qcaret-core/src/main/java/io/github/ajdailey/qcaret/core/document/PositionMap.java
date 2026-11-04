package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Maps an offset from an earlier revision to the current one through a log of the last {@value
 * #MAX_BATCHES} batches and at most {@value #MAX_REPLACEMENTS} replacements. Past the log the
 * mapping is {@link #UNMAPPABLE}. Search results and background results use it.
 */
public final class PositionMap {
  /** Returned for a revision older than the log. */
  public static final int UNMAPPABLE = -1;

  /** Batches kept. */
  static final int MAX_BATCHES = 10_000;

  /** Replacements kept over all batches; the newest batch is kept whatever its size. */
  static final int MAX_REPLACEMENTS = 1_000_000;

  private final ArrayDeque<Entry> log = new ArrayDeque<Entry>();
  private long replacements;
  private long current;

  private record Entry(long revision, ChangeMap map) {}

  PositionMap(long revision) {
    current = revision;
  }

  void add(long revision, ChangeMap map) {
    log.addLast(new Entry(revision, map));
    replacements += map.size();
    current = revision;
    while (log.size() > 1 && (log.size() > MAX_BATCHES || replacements > MAX_REPLACEMENTS)) {
      replacements -= log.removeFirst().map.size();
    }
  }

  /**
   * Maps an offset to the current revision.
   *
   * @param revision the revision the offset belongs to
   * @param offset the offset in that revision's text
   * @param bias the side of an insert at the offset
   * @return the offset in the current text, or {@link #UNMAPPABLE} when {@code revision} is older
   *     than the log
   * @throws IllegalArgumentException when {@code revision} is newer than the current one
   */
  public int map(long revision, int offset, Bias bias) {
    if (revision > current) {
      throw new IllegalArgumentException("revision " + revision + " is newer than " + current);
    }
    if (revision == current) {
      return offset;
    }
    if (log.isEmpty() || log.peekFirst().revision > revision + 1) {
      return UNMAPPABLE;
    }
    int out = offset;
    Iterator<Entry> it = log.iterator();
    while (it.hasNext()) {
      Entry e = it.next();
      if (e.revision > revision) {
        out = e.map.map(out, bias);
      }
    }
    return out;
  }
}
