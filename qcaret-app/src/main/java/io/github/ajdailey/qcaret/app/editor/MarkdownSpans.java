package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.InlineStyle;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.markdown.StyleRun;
import java.util.Arrays;
import java.util.List;

/**
 * A render's style runs and code-block tokens flattened into disjoint spans, for a view that styles
 * one painted line at a time. A span is a {@code start, end, bits} triple in rendered offsets,
 * ascending; text outside every span is plain. The bits hold one flag per {@link InlineStyle} but
 * {@link InlineStyle#HEADING}, the heading level in {@link #LEVEL_SHIFT}, and the code token's
 * {@code TokenRole} ordinal plus 1 in {@link #ROLE_SHIFT}. Nested runs of one style count once.
 */
public final class MarkdownSpans {
  /** {@link InlineStyle#STRONG}. */
  public static final int STRONG = 1;

  /** {@link InlineStyle#EMPHASIS}. */
  public static final int EMPHASIS = 1 << 1;

  /** {@link InlineStyle#STRIKETHROUGH}. */
  public static final int STRIKETHROUGH = 1 << 2;

  /** {@link InlineStyle#CODE}. */
  public static final int CODE = 1 << 3;

  /** {@link InlineStyle#LINK}. */
  public static final int LINK = 1 << 4;

  /** {@link InlineStyle#KBD}. */
  public static final int KBD = 1 << 5;

  /** {@link InlineStyle#SUB}. */
  public static final int SUB = 1 << 6;

  /** {@link InlineStyle#SUP}. */
  public static final int SUP = 1 << 7;

  /** The heading level, 0 outside a heading. */
  public static final int LEVEL_SHIFT = 8;

  /** The code token's role ordinal plus 1, 0 outside a token. */
  public static final int ROLE_SHIFT = 11;

  private static final int FLAGS = 8;

  private MarkdownSpans() {}

  /**
   * Flattens a render's runs and its code tokens.
   *
   * @param rendered the render
   * @param codeTokens per code block, {@code start, end, role} triples ascending
   * @return the spans, adjacent spans with equal bits merged
   */
  public static int[] of(RenderedMarkdown rendered, List<int[]> codeTokens) {
    List<StyleRun> runs = rendered.runs();
    int tokens = 0;
    for (int[] block : codeTokens) {
      tokens += block.length / 3;
    }
    long[] starts = new long[runs.size() + tokens];
    long[] ends = new long[starts.length];
    int[] bits = new int[starts.length];
    int n = 0;
    for (StyleRun run : runs) {
      if (run.end() > run.start()) {
        starts[n] = run.start();
        ends[n] = run.end();
        bits[n] = bitsOf(run);
        n++;
      }
    }
    for (int[] block : codeTokens) {
      for (int t = 0; t + 2 < block.length; t += 3) {
        if (block[t + 1] > block[t]) {
          starts[n] = block[t];
          ends[n] = block[t + 1];
          bits[n] = (block[t + 2] + 1) << ROLE_SHIFT;
          n++;
        }
      }
    }
    // An event is position << 33 | open << 32 | index: a close sorts before an open at its
    // position.
    long[] sorted = new long[2 * n];
    for (int i = 0; i < n; i++) {
      sorted[2 * i] = (starts[i] << 33) | (1L << 32) | i;
      sorted[2 * i + 1] = (ends[i] << 33) | i;
    }
    Arrays.sort(sorted);
    int[] counts = new int[FLAGS];
    int level = 0;
    int levels = 0;
    int role = 0;
    int[] out = new int[3 * Math.max(1, 2 * n)];
    int size = 0;
    int at = 0;
    for (long event : sorted) {
      int position = (int) (event >>> 33);
      if (position > at) {
        int current = current(counts, levels > 0 ? level : 0, role);
        if (current != 0) {
          if (size > 0 && out[size - 2] == at && out[size - 1] == current) {
            out[size - 2] = position;
          } else {
            out[size++] = at;
            out[size++] = position;
            out[size++] = current;
          }
        }
        at = position;
      }
      boolean open = (event & (1L << 32)) != 0;
      int b = bits[(int) (event & 0xffffffffL)];
      int delta = open ? 1 : -1;
      for (int f = 0; f < FLAGS; f++) {
        if ((b & (1 << f)) != 0) {
          counts[f] += delta;
        }
      }
      int runLevel = (b >>> LEVEL_SHIFT) & 7;
      if (runLevel != 0) {
        levels += delta;
        if (open) {
          level = runLevel;
        }
      }
      int runRole = b >>> ROLE_SHIFT;
      if (runRole != 0) {
        role = open ? runRole : 0;
      }
    }
    return Arrays.copyOf(out, size);
  }

  private static int current(int[] counts, int level, int role) {
    int bits = 0;
    for (int f = 0; f < FLAGS; f++) {
      if (counts[f] > 0) {
        bits |= 1 << f;
      }
    }
    return bits | (level << LEVEL_SHIFT) | (role << ROLE_SHIFT);
  }

  private static int bitsOf(StyleRun run) {
    return switch (run.style()) {
      case HEADING -> Math.max(1, Math.min(6, run.level())) << LEVEL_SHIFT;
      case STRONG -> STRONG;
      case EMPHASIS -> EMPHASIS;
      case STRIKETHROUGH -> STRIKETHROUGH;
      case CODE -> CODE;
      case LINK -> LINK;
      case KBD -> KBD;
      case SUB -> SUB;
      case SUP -> SUP;
    };
  }

  /**
   * Finds the first span ending after an offset.
   *
   * @param spans spans from {@link #of}
   * @param offset a rendered offset
   * @return the span's index in triples; the span count when none ends after the offset
   */
  public static int first(int[] spans, int offset) {
    int lo = 0;
    int hi = spans.length / 3;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (spans[3 * mid + 1] <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /**
   * Returns a span's heading level.
   *
   * @param bits a span's bits
   * @return 1 to 6, or 0
   */
  public static int level(int bits) {
    return (bits >>> LEVEL_SHIFT) & 7;
  }

  /**
   * Returns a span's code token role.
   *
   * @param bits a span's bits
   * @return the {@code TokenRole} ordinal, or -1 outside a token
   */
  public static int role(int bits) {
    return (bits >>> ROLE_SHIFT) - 1;
  }
}
