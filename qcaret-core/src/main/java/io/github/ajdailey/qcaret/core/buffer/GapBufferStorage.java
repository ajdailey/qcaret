package io.github.ajdailey.qcaret.core.buffer;

import java.util.Objects;

/**
 * Gap buffer: one {@code char[]} with a gap at the last edit, and the line starts in a second
 * gapped {@code int[]}. Line starts before the gap are absolute; those after it are stored as the
 * distance from the end of the text, so an edit at the gap leaves them unchanged. An edit costs
 * O(1) at the gap plus moving the gap. {@link #snapshot()} shares both arrays and is O(1); the
 * first edit after it copies them, O(n).
 */
public final class GapBufferStorage extends GapBufferText implements TextStorage {
  private static final int MIN_GAP = 64;

  private boolean shared;

  private GapBufferStorage(char[] chars, int length, int[] starts, int lineCount) {
    super(chars, length, chars.length, length, starts, lineCount, starts.length);
  }

  /**
   * Builds a storage holding a copy of {@code text}.
   *
   * @param text the initial text
   * @return the storage
   */
  public static GapBufferStorage of(CharSequence text) {
    String s = text.toString();
    int n = s.length();
    char[] chars = new char[n + gapFor(n)];
    s.getChars(0, n, chars, 0);
    int[] terms = LineBreaks.terminators(s);
    int[] starts = new int[terms.length + gapFor(terms.length)];
    for (int i = 0; i < terms.length; i++) {
      starts[i] = terms[i] + 1;
    }
    return new GapBufferStorage(chars, n, starts, terms.length);
  }

  private static int gapFor(int n) {
    return Math.max(MIN_GAP, n / 16);
  }

  @Override
  public TextSnapshot snapshot() {
    shared = true;
    return new Snapshot(this);
  }

  @Override
  public void replace(int offset, int removeLength, CharSequence text) {
    Objects.checkFromIndexSize(offset, removeLength, len);
    if (shared) {
      buf = buf.clone();
      lines = lines.clone();
      shared = false;
    }
    moveGap(offset);
    if (offset >= 1 && lgs > 0 && lines[lgs - 1] == offset) {
      lgs--;
    }
    int end = offset + removeLength;
    while (lge < lines.length && len - lines[lge] <= end) {
      lge++;
    }
    ge += removeLength;
    len -= removeLength;
    int n = text.length();
    if (ge - gs < n) {
      growText(n);
    }
    for (int i = 0; i < n; i++) {
      buf[gs + i] = text.charAt(i);
    }
    gs += n;
    len += n;
    for (int q = Math.max(1, offset); q <= offset + n; q++) {
      char c = charAt(q - 1);
      if (c == '\n' || (c == '\r' && (q == len || charAt(q) != '\n'))) {
        if (lgs == lge) {
          growLines();
        }
        lines[lgs++] = q;
      }
    }
  }

  private void moveGap(int to) {
    if (to < gs) {
      int n = gs - to;
      System.arraycopy(buf, to, buf, ge - n, n);
      gs = to;
      ge -= n;
      while (lgs > 0 && lines[lgs - 1] > to) {
        lines[--lge] = len - lines[--lgs];
      }
    } else if (to > gs) {
      int n = to - gs;
      System.arraycopy(buf, ge, buf, gs, n);
      gs = to;
      ge += n;
      while (lge < lines.length && len - lines[lge] <= to) {
        lines[lgs++] = len - lines[lge++];
      }
    }
  }

  private void growText(int need) {
    int tail = buf.length - ge;
    char[] grown = new char[len + need + gapFor(len + need)];
    System.arraycopy(buf, 0, grown, 0, gs);
    System.arraycopy(buf, ge, grown, grown.length - tail, tail);
    ge = grown.length - tail;
    buf = grown;
  }

  private void growLines() {
    int tail = lines.length - lge;
    int count = lgs + tail;
    int[] grown = new int[count + 1 + gapFor(count + 1)];
    System.arraycopy(lines, 0, grown, 0, lgs);
    System.arraycopy(lines, lge, grown, grown.length - tail, tail);
    lge = grown.length - tail;
    lines = grown;
  }

  /** An immutable copy of the buffer state; the storage copies the arrays before its next edit. */
  private static final class Snapshot extends GapBufferText implements TextSnapshot {
    Snapshot(GapBufferText s) {
      super(s.buf, s.gs, s.ge, s.len, s.lines, s.lgs, s.lge);
    }
  }
}
