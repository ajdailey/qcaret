package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;

/** Finds the next literal occurrence of a selection's text, for Add Next Occurrence. */
public final class Occurrences {
  /** Chars scanned between cancellation checks. */
  private static final int CHECK_CHARS = 1 << 16;

  private Occurrences() {}

  /**
   * Returns the first occurrence of a text at or after an offset, wrapping to the start; matching
   * is exact and case sensitive. Runs on a worker for a large text.
   *
   * @param text the text
   * @param needle the text sought, not empty
   * @param from the first offset tried
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the start of the occurrence, or -1 when the text holds none
   * @throws CancellationException when canceled
   */
  public static int next(TextContent text, String needle, int from, CancellationToken token) {
    int found = scan(text, needle, from, text.length(), token);
    if (found < 0) {
      found = scan(text, needle, 0, Math.min(text.length(), from + needle.length() - 1), token);
    }
    return found;
  }

  /** Returns the first occurrence starting in {@code [from, to - needle length]}, or -1. */
  private static int scan(
      TextContent text, String needle, int from, int to, CancellationToken token) {
    char first = needle.charAt(0);
    int last = to - needle.length();
    for (int i = Math.max(0, from); i <= last; i++) {
      if ((i - from) % CHECK_CHARS == 0) {
        token.check();
      }
      if (text.charAt(i) != first) {
        continue;
      }
      int k = 1;
      while (k < needle.length() && text.charAt(i + k) == needle.charAt(k)) {
        k++;
      }
      if (k == needle.length()) {
        return i;
      }
    }
    return -1;
  }
}
