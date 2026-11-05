package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Literal occurrences: the next one of a selection's text, for Add Next Occurrence, and the whole
 * words in a range, for the occurrence highlight.
 */
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

  /**
   * Returns the occurrences of a word in a range that are whole words: no letter, digit or {@code
   * _} touches either end. Matching is exact and case sensitive.
   *
   * @param text the text
   * @param word the word, not empty
   * @param from the first offset an occurrence may start at
   * @param to the offset an occurrence must end by
   * @param max the most occurrences returned
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return start and end offset pairs, ascending
   * @throws CancellationException when canceled
   */
  public static int[] words(
      TextContent text, String word, int from, int to, int max, CancellationToken token) {
    int start = Math.max(0, from);
    int end = Math.min(text.length(), to);
    List<Integer> found = new ArrayList<Integer>();
    int at = start;
    while (found.size() < max) {
      at = scan(text, word, at, end, token);
      if (at < 0) {
        break;
      }
      int after = at + word.length();
      boolean whole =
          (at == 0 || !Words.isWord(text.charAt(at - 1)))
              && (after == text.length() || !Words.isWord(text.charAt(after)));
      if (whole) {
        found.add(at);
      }
      at = whole ? after : at + 1;
    }
    int[] ranges = new int[found.size() * 2];
    for (int i = 0; i < found.size(); i++) {
      ranges[2 * i] = found.get(i);
      ranges[2 * i + 1] = found.get(i) + word.length();
    }
    return ranges;
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
