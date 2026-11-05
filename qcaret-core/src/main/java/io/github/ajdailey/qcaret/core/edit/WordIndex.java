package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The distinct words of a text, for word completion: runs of letters, digits and {@code _} that
 * start with a letter or {@code _}, {@value #MIN_LENGTH} to {@value #MAX_LENGTH} chars long, at
 * most {@value #MAX_WORDS} per text (the first found). Sorted by lower case, then as written.
 * Immutable.
 */
public final class WordIndex {
  /** Shortest word kept. */
  public static final int MIN_LENGTH = 2;

  /** Longest word kept. */
  public static final int MAX_LENGTH = 100;

  /** Words kept per text at most. */
  public static final int MAX_WORDS = 100_000;

  /** Chars scanned between cancellation checks. */
  private static final int CHECK_CHARS = 1 << 16;

  private static final Comparator<String[]> ORDER =
      new Comparator<String[]>() {
        @Override
        public int compare(String[] a, String[] b) {
          int c = a[0].compareTo(b[0]);
          return c != 0 ? c : a[1].compareTo(b[1]);
        }
      };

  private final String[] words;
  private final String[] lower;

  private WordIndex(String[][] sorted) {
    words = new String[sorted.length];
    lower = new String[sorted.length];
    for (int i = 0; i < sorted.length; i++) {
      lower[i] = sorted[i][0];
      words[i] = sorted[i][1];
    }
  }

  /**
   * Collects the words of a text. Runs on a worker for a large text.
   *
   * @param text the text
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the index
   * @throws CancellationException when canceled
   */
  public static WordIndex build(TextContent text, CancellationToken token) {
    WordSet found = new WordSet();
    char[] word = new char[MAX_LENGTH + 1];
    int n = 0;
    int hash = 0;
    int length = text.length();
    TextCursor cursor = text.cursor(0);
    for (int i = 0; i <= length && found.size < MAX_WORDS; i++) {
      if (i % CHECK_CHARS == 0) {
        token.check();
      }
      char c = i < length ? cursor.next() : ' ';
      if (Words.isWord(c)) {
        if (n <= MAX_LENGTH) {
          word[n++] = c;
          hash = 31 * hash + c;
        }
        continue;
      }
      if (n >= MIN_LENGTH && n <= MAX_LENGTH && !Character.isDigit(word[0])) {
        found.add(word, n, hash);
      }
      n = 0;
      hash = 0;
    }
    String[][] sorted = new String[found.size][];
    int k = 0;
    for (String w : found.table) {
      if (w != null) {
        sorted[k++] = new String[] {fold(w), w};
      }
    }
    Arrays.sort(sorted, ORDER);
    return new WordIndex(sorted);
  }

  /** An open-addressing set that copies a word into a string only when it is new. */
  private static final class WordSet {
    String[] table = new String[1 << 10];
    int size;

    void add(char[] chars, int length, int hash) {
      int mask = table.length - 1;
      int at = (hash ^ (hash >>> 16)) & mask;
      while (table[at] != null) {
        if (same(table[at], chars, length)) {
          return;
        }
        at = (at + 1) & mask;
      }
      table[at] = new String(chars, 0, length);
      if (++size * 2 > table.length) {
        grow();
      }
    }

    private void grow() {
      String[] old = table;
      table = new String[old.length * 2];
      int mask = table.length - 1;
      for (String w : old) {
        if (w != null) {
          int hash = w.hashCode();
          int at = (hash ^ (hash >>> 16)) & mask;
          while (table[at] != null) {
            at = (at + 1) & mask;
          }
          table[at] = w;
        }
      }
    }

    private static boolean same(String w, char[] chars, int length) {
      if (w.length() != length) {
        return false;
      }
      for (int i = 0; i < length; i++) {
        if (w.charAt(i) != chars[i]) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Returns the number of words.
   *
   * @return the count
   */
  public int size() {
    return words.length;
  }

  /**
   * Returns the words that start with a prefix, ignoring case, other than the prefix itself.
   *
   * @param prefix the prefix, not empty
   * @param max the most words returned
   * @return the words in index order
   */
  public List<String> complete(String prefix, int max) {
    String key = fold(prefix);
    int lo = 0;
    int hi = lower.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (lower[mid].compareTo(key) < 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    List<String> out = new ArrayList<String>();
    for (int i = lo; i < words.length && out.size() < max && lower[i].startsWith(key); i++) {
      if (!words[i].equals(prefix)) {
        out.add(words[i]);
      }
    }
    return out;
  }

  /** Lower-cases each char on its own, so the length stays and a prefix stays a prefix. */
  private static String fold(String word) {
    char[] chars = word.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      chars[i] = Character.toLowerCase(chars[i]);
    }
    return new String(chars);
  }
}
