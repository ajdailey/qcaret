package io.github.ajdailey.qcaret.core.buffer;

/**
 * Line-break counting over a range taken on its own. A terminator is an LF, or a CR not followed by
 * an LF inside the range, so a CR at the end of a range counts. Joining range A to range B gives
 * {@code count(A) + count(B) - 1} when A ends with CR and B starts with LF, and the sum otherwise.
 * Every scan uses {@link String#indexOf(int, int, int)}, so CR LF text costs two scans, not a
 * char-by-char loop.
 */
final class LineBreaks {
  private LineBreaks() {}

  /** Counts terminators in {@code s[start, end)}. */
  static int count(String s, int start, int end) {
    int n = 0;
    int p = s.indexOf('\n', start, end);
    while (p >= 0) {
      n++;
      p = s.indexOf('\n', p + 1, end);
    }
    p = s.indexOf('\r', start, end);
    while (p >= 0) {
      if (p + 1 == end || s.charAt(p + 1) != '\n') {
        n++;
      }
      p = s.indexOf('\r', p + 1, end);
    }
    return n;
  }

  /** Returns the index of terminator {@code k} (zero-based) in {@code s[start, end)}. */
  static int find(String s, int start, int end, int k) {
    int lf = s.indexOf('\n', start, end);
    int cr = s.indexOf('\r', start, end);
    int n = 0;
    while (lf >= 0 || cr >= 0) {
      int t;
      if (cr >= 0 && (lf < 0 || cr < lf)) {
        t = cr;
        cr = s.indexOf('\r', cr + 1, end);
        if (t + 1 < end && s.charAt(t + 1) == '\n') {
          continue;
        }
      } else {
        t = lf;
        lf = s.indexOf('\n', lf + 1, end);
      }
      if (n == k) {
        return t;
      }
      n++;
    }
    throw new IllegalStateException("terminator " + k + " not in range");
  }

  /** Returns the first index in {@code s[from, end)} holding a CR or an LF, or -1. */
  static int nextBreakChar(String s, int from, int end) {
    int lf = s.indexOf('\n', from, end);
    int cr = s.indexOf('\r', from, lf < 0 ? end : lf);
    return cr >= 0 ? cr : lf;
  }
}
