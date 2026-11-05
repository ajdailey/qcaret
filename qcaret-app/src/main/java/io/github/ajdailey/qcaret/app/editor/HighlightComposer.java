package io.github.ajdailey.qcaret.app.editor;

import java.util.Arrays;

/**
 * Composes what one view paints on a row from its layers, lowest first: the syntax tokens'
 * foreground, the find matches' background, then the secondary selections, which set both. The view
 * maps each run to its toolkit style. Toolkit-free; confined to the UI thread.
 */
public final class HighlightComposer {
  /** No role or no background in a run. */
  public static final int NONE = -1;

  /** A run's background layer. */
  public enum Background {
    /** A find match. */
    FIND_MATCH,
    /** A secondary selection; it also sets the foreground. */
    SELECTION
  }

  private TokenSource syntax;
  private int[] matches = new int[0];

  /**
   * Sets the syntax layer.
   *
   * @param source the document's tokens, or null for none
   */
  public void setSyntax(TokenSource source) {
    syntax = source;
  }

  /**
   * Returns the syntax layer.
   *
   * @return the source, or null
   */
  public TokenSource syntax() {
    return syntax;
  }

  /**
   * Replaces the find matches.
   *
   * @param ranges model offset pairs, ascending
   */
  public void setMatches(int[] ranges) {
    matches = ranges.clone();
  }

  /**
   * Returns the find matches.
   *
   * @return model offset pairs, ascending
   */
  public int[] matches() {
    return matches.clone();
  }

  /**
   * Returns the styled runs of part of a line.
   *
   * @param line the model line
   * @param lineStart the model offset of the line's first char
   * @param from the model offset of the first char painted
   * @param to the model offset after the last char painted
   * @param selected secondary selections as model offset pairs, ascending and disjoint
   * @return {@code start, length, role, background} per run, start relative to {@code from}, role a
   *     {@link io.github.ajdailey.qcaret.app.theme.TokenRole} ordinal and background a {@link
   *     Background} ordinal, each {@link #NONE} when absent; runs are ascending and disjoint, and
   *     unstyled text has none
   */
  public int[] compose(int line, int lineStart, int from, int to, int[] selected) {
    int[] tokens = syntax == null ? null : syntax.tokens(line);
    int firstMatch = firstEndingAfter(matches, from);
    int lastMatch = firstMatch * 2;
    while (lastMatch < matches.length && matches[lastMatch] < to) {
      lastMatch += 2;
    }
    int[] cuts =
        new int
            [2
                + (tokens == null ? 0 : tokens.length)
                + (lastMatch - firstMatch * 2)
                + selected.length];
    int n = 0;
    cuts[n++] = from;
    cuts[n++] = to;
    if (tokens != null) {
      for (int i = 0; i < tokens.length; i += 3) {
        n = cut(cuts, n, lineStart + tokens[i], from, to);
        n = cut(cuts, n, lineStart + tokens[i + 1], from, to);
      }
    }
    for (int i = firstMatch * 2; i < lastMatch; i += 2) {
      n = cut(cuts, n, matches[i], from, to);
      n = cut(cuts, n, matches[i + 1], from, to);
    }
    for (int i = 0; i < selected.length; i++) {
      n = cut(cuts, n, selected[i], from, to);
    }
    Arrays.sort(cuts, 0, n);
    int[] out = new int[n * 4];
    int size = 0;
    int t = 0;
    int m = firstMatch * 2;
    int s = 0;
    for (int i = 0; i + 1 < n; i++) {
      int a = cuts[i];
      int b = cuts[i + 1];
      if (b <= a) {
        continue;
      }
      while (tokens != null && t < tokens.length && lineStart + tokens[t + 1] <= a) {
        t += 3;
      }
      int role =
          tokens != null && t < tokens.length && lineStart + tokens[t] <= a ? tokens[t + 2] : NONE;
      while (m < matches.length && matches[m + 1] <= a) {
        m += 2;
      }
      while (s < selected.length && selected[s + 1] <= a) {
        s += 2;
      }
      int background = NONE;
      if (s < selected.length && selected[s] <= a) {
        background = Background.SELECTION.ordinal();
      } else if (m < matches.length && matches[m] <= a) {
        background = Background.FIND_MATCH.ordinal();
      }
      if (role == NONE && background == NONE) {
        continue;
      }
      if (size > 0
          && out[size - 4] + out[size - 3] == a - from
          && out[size - 2] == role
          && out[size - 1] == background) {
        out[size - 3] += b - a;
        continue;
      }
      out[size++] = a - from;
      out[size++] = b - a;
      out[size++] = role;
      out[size++] = background;
    }
    return Arrays.copyOf(out, size);
  }

  private static int cut(int[] cuts, int n, int at, int from, int to) {
    if (at > from && at < to) {
      cuts[n++] = at;
    }
    return n;
  }

  /** Returns the index of the first pair whose end is after {@code offset}. */
  private static int firstEndingAfter(int[] ranges, int offset) {
    int lo = 0;
    int hi = ranges.length / 2;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (ranges[mid * 2 + 1] <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
