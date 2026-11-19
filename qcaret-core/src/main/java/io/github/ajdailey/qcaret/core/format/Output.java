package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;

/**
 * The formatted text of one range, built from verbatim copies of the source and replaced gaps, in
 * source order with no part skipped. It maps given source offsets to output offsets: an offset in a
 * copy keeps its place in the copied text, and an offset in a replaced gap lands after the new gap.
 * A disabled output only checks the token, for Check.
 */
final class Output {
  /** Steps between cancellation checks. */
  private static final int CHECK_STEPS = 4096;

  private final String source;
  private final StringBuilder out;
  private final FormatLayout layout;
  private final String base;
  private final int[] points;
  private final int[] mapped;
  private final CancellationToken token;
  private int next;
  private int steps;

  /**
   * Creates an output.
   *
   * @param source the range's text
   * @param layout the indent, line ending and size limit; null for a disabled output
   * @param base the whitespace every inserted line starts with
   * @param points source offsets to map, ascending
   * @param token checked every {@value #CHECK_STEPS} steps
   */
  Output(String source, FormatLayout layout, String base, int[] points, CancellationToken token) {
    this.source = source;
    this.layout = layout;
    this.base = base;
    this.points = points;
    this.mapped = new int[points.length];
    this.token = token;
    this.out = layout == null ? null : new StringBuilder(source.length());
  }

  /** Reports whether the output collects text. */
  boolean enabled() {
    return out != null;
  }

  /** Copies {@code source[from, to)} unchanged. */
  void copy(int from, int to) {
    step();
    if (out == null) {
      return;
    }
    int start = out.length();
    while (next < points.length && points[next] < to) {
      mapped[next] = start + points[next] - from;
      next++;
    }
    reserve(to - from, from);
    out.append(source, from, to);
  }

  /** Replaces the gap {@code source[from, to)} with nothing. */
  void join(int from, int to) {
    gap(from, to, -1);
  }

  /**
   * Replaces the gap {@code source[from, to)} with a line break, the base whitespace and {@code
   * depth} indent levels.
   */
  void newline(int from, int to, int depth) {
    gap(from, to, depth);
  }

  /** Replaces the gap {@code source[from, to)} with one space. */
  void space(int from, int to) {
    step();
    if (out == null) {
      return;
    }
    reserve(1, from);
    out.append(' ');
    resolveGap(to);
  }

  private void gap(int from, int to, int depth) {
    step();
    if (out == null) {
      return;
    }
    if (depth >= 0) {
      long size =
          (long) layout.lineEnding().length()
              + base.length()
              + (long) layout.indent().length() * depth;
      reserve(size, from);
      out.append(layout.lineEnding()).append(base);
      for (int i = 0; i < depth; i++) {
        out.append(layout.indent());
      }
    }
    resolveGap(to);
  }

  private void resolveGap(int to) {
    while (next < points.length && points[next] < to) {
      mapped[next] = out.length();
      next++;
    }
  }

  /**
   * Returns the output text.
   *
   * @return the text; null for a disabled output
   */
  String text() {
    if (out == null) {
      return null;
    }
    while (next < points.length) {
      mapped[next++] = out.length();
    }
    return out.toString();
  }

  /** Returns the output offset of each point; valid after {@link #text}. */
  int[] mapped() {
    return mapped;
  }

  private void reserve(long chars, int at) {
    if (out.length() + chars > layout.maxChars()) {
      throw new FormatException(
          at, "The formatted text would exceed " + layout.maxChars() + " characters");
    }
  }

  private void step() {
    if (++steps % CHECK_STEPS == 0) {
      token.check();
    }
  }
}
