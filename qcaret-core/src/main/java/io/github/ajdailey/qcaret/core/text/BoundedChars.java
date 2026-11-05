package io.github.ajdailey.qcaret.core.text;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;

/**
 * A line's chars that throw {@link LimitReached} once {@link #charAt} has been called more times
 * than a limit, so a backtracking regex run over it ends instead of stalling its thread. A regex
 * reads the subject through {@code charAt}, so the count bounds the regex's steps. Every {@value
 * #CHECK_READS} reads it also checks a {@link CancellationToken}. Not thread-safe; reuse one
 * instance per thread with {@link #reset}.
 */
public final class BoundedChars implements CharSequence {
  /** Reads allowed beyond {@code 8 * length} by {@link #lineLimit}. */
  public static final int BASE_READS = 1 << 16;

  /** Reads between cancellation checks. */
  static final int CHECK_READS = 4096;

  private CharSequence text = "";
  private long remaining;
  private long used;
  private CancellationToken token;
  private int sinceCheck;

  /** Thrown by {@link #charAt} past the limit; carries no stack trace. */
  public static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitReached() {
      super(null, null, false, false);
    }
  }

  /**
   * Returns the read limit for one regex run over a line: {@code 8 * length + 65,536}.
   *
   * @param line the line
   * @return reads
   */
  public static long lineLimit(CharSequence line) {
    return 8L * line.length() + BASE_READS;
  }

  /**
   * Sets the chars and the limit, zeroes {@link #used} and checks no token.
   *
   * @param line the chars read
   * @param limit reads allowed before {@link LimitReached}
   * @return this
   */
  public BoundedChars reset(CharSequence line, long limit) {
    return reset(line, limit, null);
  }

  /**
   * Sets the chars, the limit and the token, and zeroes {@link #used}.
   *
   * @param line the chars read
   * @param limit reads allowed before {@link LimitReached}
   * @param cancel checked every {@value #CHECK_READS} reads, or null
   * @return this
   */
  public BoundedChars reset(CharSequence line, long limit, CancellationToken cancel) {
    text = line;
    remaining = limit;
    used = 0;
    token = cancel;
    sinceCheck = 0;
    return this;
  }

  /**
   * Allows more reads without resetting the count, for the next regex run over the same chars.
   *
   * @param limit reads allowed from now on before {@link LimitReached}
   */
  public void allow(long limit) {
    remaining = limit;
  }

  /**
   * Returns the reads since the last {@link #reset}, the throwing read excluded.
   *
   * @return reads
   */
  public long used() {
    return used;
  }

  @Override
  public int length() {
    return text.length();
  }

  /**
   * Returns a char and counts the read.
   *
   * @throws LimitReached past the limit
   * @throws java.util.concurrent.CancellationException when the token was canceled
   */
  @Override
  public char charAt(int index) {
    if (--remaining < 0) {
      throw new LimitReached();
    }
    if (token != null && ++sinceCheck >= CHECK_READS) {
      sinceCheck = 0;
      token.check();
    }
    used++;
    return text.charAt(index);
  }

  @Override
  public CharSequence subSequence(int from, int to) {
    return text.subSequence(from, to).toString();
  }

  @Override
  public String toString() {
    return text.toString();
  }
}
