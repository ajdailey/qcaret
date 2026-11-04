package io.github.ajdailey.qcaret.core.buffer;

import java.util.Arrays;

/**
 * Line-break counting over a range taken on its own. A terminator is an LF, or a CR not followed by
 * an LF inside the range, so a CR at the end of a range counts. Joining range A to range B gives
 * {@code count(A) + count(B) - 1} when A ends with CR and B starts with LF, and the sum otherwise.
 */
final class LineBreaks {
  private static final int[] NONE = new int[0];

  private LineBreaks() {}

  /** Counts terminators in {@code s[start, end)}. */
  static int count(CharSequence s, int start, int end) {
    int n = 0;
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 == end || s.charAt(i + 1) != '\n'))) {
        n++;
      }
    }
    return n;
  }

  /** Counts terminators in {@code s[start, end)}. */
  static int count(char[] s, int start, int end) {
    int n = 0;
    for (int i = start; i < end; i++) {
      char c = s[i];
      if (c == '\n' || (c == '\r' && (i + 1 == end || s[i + 1] != '\n'))) {
        n++;
      }
    }
    return n;
  }

  /**
   * Counts terminators in {@code s[start, end)}, scanning with {@link String#indexOf} when no CR.
   */
  static int count(String s, int start, int end) {
    int cr = s.indexOf('\r', start);
    if (cr >= 0 && cr < end) {
      return count((CharSequence) s, start, end);
    }
    int n = 0;
    int p = s.indexOf('\n', start);
    while (p >= 0 && p < end) {
      n++;
      p = s.indexOf('\n', p + 1);
    }
    return n;
  }

  /** Returns the index of terminator {@code k} (zero-based) in {@code s[start, end)}. */
  static int find(String s, int start, int end, int k) {
    int cr = s.indexOf('\r', start);
    if (cr < 0 || cr >= end) {
      int p = start - 1;
      for (int i = 0; i <= k; i++) {
        p = s.indexOf('\n', p + 1);
      }
      return p;
    }
    return find((CharSequence) s, start, end, k);
  }

  /** Returns the index of terminator {@code k} (zero-based) in {@code s[start, end)}. */
  static int find(CharSequence s, int start, int end, int k) {
    int n = 0;
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 == end || s.charAt(i + 1) != '\n'))) {
        if (n == k) {
          return i;
        }
        n++;
      }
    }
    throw new IllegalStateException("terminator " + k + " not in range");
  }

  /** Returns the index of terminator {@code k} (zero-based) in {@code s[start, end)}. */
  static int find(char[] s, int start, int end, int k) {
    int n = 0;
    for (int i = start; i < end; i++) {
      char c = s[i];
      if (c == '\n' || (c == '\r' && (i + 1 == end || s[i + 1] != '\n'))) {
        if (n == k) {
          return i;
        }
        n++;
      }
    }
    throw new IllegalStateException("terminator " + k + " not in range");
  }

  /** Returns the sorted terminator indexes of all of {@code s}. */
  static int[] terminators(String s) {
    int n = count(s, 0, s.length());
    if (n == 0) {
      return NONE;
    }
    int[] out = new int[n];
    if (s.indexOf('\r') < 0) {
      int p = s.indexOf('\n');
      for (int j = 0; j < n; j++) {
        out[j] = p;
        p = s.indexOf('\n', p + 1);
      }
      return out;
    }
    int j = 0;
    int len = s.length();
    for (int i = 0; i < len && j < n; i++) {
      char c = s.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 == len || s.charAt(i + 1) != '\n'))) {
        out[j++] = i;
      }
    }
    return out;
  }

  /** Returns the first index in a sorted array whose value is at least {@code key}. */
  static int lowerBound(int[] a, int from, int to, int key) {
    int i = Arrays.binarySearch(a, from, to, key);
    if (i < 0) {
      return -i - 1;
    }
    while (i > from && a[i - 1] == key) {
      i--;
    }
    return i;
  }
}
