package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.util.function.LongConsumer;

/**
 * Finds a {@link HexPattern} in a {@link HexSource}. Reads the source directly in windows of
 * {@value #WINDOW} match starts plus the pattern length less one byte, so a match across a window
 * or cache block boundary is found; the {@link HexBlocks} cache is not touched. Blocks on I/O: call
 * it on a worker.
 */
public final class ByteSearch {
  /** Match starts examined per source read. */
  static final int WINDOW = 1 << 16;

  private final HexSource source;
  private final HexPattern pattern;
  private final CancellationToken token;
  private final LongConsumer progress;
  private final byte[] buffer = new byte[WINDOW + HexPattern.MAX_LENGTH];
  private long examined;

  private ByteSearch(
      HexSource source, HexPattern pattern, CancellationToken token, LongConsumer progress) {
    this.source = source;
    this.pattern = pattern;
    this.token = token;
    this.progress = progress;
  }

  /**
   * Finds the nearest match from a position. Forward, the first match starting at or after {@code
   * from}; backward, the last match starting at or before it. With {@code wrap}, a search that
   * reaches the end continues from the other end up to {@code from}.
   *
   * @param source the bytes
   * @param pattern the pattern
   * @param from the first start examined; a caller finding the next match after one at {@code m}
   *     passes {@code m + 1} forward and {@code m - 1} backward
   * @param forward the direction
   * @param wrap whether to continue from the other end
   * @param token checked before each read
   * @param progress receives the count of match starts examined after each read, out of {@code
   *     max(0, size - length + 1)}; may be null
   * @return the match's first byte, or -1 when there is none
   * @throws IOException when a read fails, {@link HexSourceChangedException} included
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static long find(
      HexSource source,
      HexPattern pattern,
      long from,
      boolean forward,
      boolean wrap,
      CancellationToken token,
      LongConsumer progress)
      throws IOException {
    return new ByteSearch(source, pattern, token, progress).find(from, forward, wrap);
  }

  @WorkerThread
  private long find(long from, boolean forward, boolean wrap) throws IOException {
    long last = source.size() - pattern.length();
    if (last < 0) {
      return -1;
    }
    if (forward) {
      long start = Math.max(0, Math.min(from, last + 1));
      long hit = scan(start, last, true);
      if (hit < 0 && wrap) {
        hit = scan(0, start - 1, true);
      }
      return hit;
    }
    long end = Math.min(from, last);
    long hit = scan(0, end, false);
    if (hit < 0 && wrap) {
      hit = scan(Math.max(0, end + 1), last, false);
    }
    return hit;
  }

  /** Examines match starts in {@code [low, high]} in the direction given. */
  @WorkerThread
  private long scan(long low, long high, boolean forward) throws IOException {
    long remaining = high - low + 1;
    long next = forward ? low : high;
    while (remaining > 0) {
      token.check();
      int starts = (int) Math.min(WINDOW, remaining);
      long window = forward ? next : next - starts + 1;
      source.read(window, buffer, starts + pattern.length() - 1, null);
      if (forward) {
        for (int i = 0; i < starts; i++) {
          if (pattern.matches(buffer, i)) {
            return window + i;
          }
        }
        next = window + starts;
      } else {
        for (int i = starts - 1; i >= 0; i--) {
          if (pattern.matches(buffer, i)) {
            return window + i;
          }
        }
        next = window - 1;
      }
      remaining -= starts;
      examined += starts;
      if (progress != null) {
        progress.accept(examined);
      }
    }
    return -1;
  }
}
