package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Expand Selection and Select Line. Expand Selection widens each selection to the smallest of these
 * ranges that holds it and is longer: the word at it, the inside of the quotes around it on its
 * line, the same with the quotes, the inside of the innermost bracket pair around it, the same with
 * the brackets, its lines without the last break, and the whole text. Repeated presses widen.
 */
public final class SelectionExpansion {
  /** Chars scanned between cancellation checks. */
  private static final int CHECK_CHARS = 1 << 16;

  private static final String QUOTES = "\"'`";

  private SelectionExpansion() {}

  /**
   * Expands every selection. Brackets pair as {@link Brackets#match} pairs them, counting only the
   * brackets the filter counts. A line longer than {@code scan} chars offers no quotes.
   *
   * @param text the text
   * @param set the selections
   * @param pairs the bracket pairs; quote pairs among them are ignored
   * @param filter the brackets that count
   * @param scan the chars sought on each side of a selection for its bracket pair
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the expanded set, or null when a bracket search reached {@code scan} chars before the
   *     text's end
   * @throws CancellationException when canceled
   */
  public static SelectionSet expand(
      TextContent text,
      SelectionSet set,
      Pairs pairs,
      Brackets.Filter filter,
      int scan,
      CancellationToken token) {
    List<Selection> out = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      List<int[]> candidates = new ArrayList<int[]>();
      int[] word = Words.at(text, s.start());
      if (word.length > 0 && word[1] >= s.end()) {
        candidates.add(word);
      }
      quotes(text, s, scan, candidates);
      int[] brackets = brackets(text, s, pairs, filter, scan, token);
      if (brackets.length == 1) {
        return null;
      }
      if (brackets.length == 2) {
        candidates.add(new int[] {brackets[0] + 1, brackets[1]});
        candidates.add(new int[] {brackets[0], brackets[1] + 1});
      }
      candidates.add(
          new int[] {
            text.offsetOfLine(text.lineAt(s.start())), Lines.end(text, lastLine(text, s))
          });
      candidates.add(new int[] {0, text.length()});
      int[] best = null;
      for (int[] c : candidates) {
        boolean holds = c[0] <= s.start() && c[1] >= s.end() && c[1] - c[0] > s.end() - s.start();
        if (holds && (best == null || c[1] - c[0] < best[1] - best[0])) {
          best = c;
        }
      }
      out.add(best == null ? s : Selection.of(best[0], best[1]));
    }
    return SelectionSet.of(out, set.primaryIndex());
  }

  /**
   * Selects the lines each selection touches, with their breaks; a selection that already holds
   * exactly those lines takes the next line too.
   *
   * @param text the text
   * @param set the selections
   * @return the set
   */
  public static SelectionSet lines(TextContent text, SelectionSet set) {
    List<Selection> out = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      int first = text.lineAt(s.start());
      int last = lastLine(text, s);
      int start = text.offsetOfLine(first);
      int end = Lines.next(text, last);
      if (s.start() == start && s.end() == end && last + 1 < text.lineCount()) {
        end = Lines.next(text, last + 1);
      }
      out.add(Selection.of(start, end));
    }
    return SelectionSet.of(out, set.primaryIndex());
  }

  private static int lastLine(TextContent text, Selection s) {
    return s.isEmpty() ? text.lineAt(s.start()) : Lines.lastLine(text, s);
  }

  /** Adds the inside and outside of each quote pair on the selection's line that holds it. */
  private static void quotes(TextContent text, Selection s, int scan, List<int[]> out) {
    int line = text.lineAt(s.start());
    if (text.lineAt(s.end()) != line || text.lineLength(line) > scan) {
      return;
    }
    int lineStart = text.offsetOfLine(line);
    int lineEnd = Lines.end(text, line);
    int i = lineStart;
    while (i < lineEnd) {
      char q = text.charAt(i);
      if (QUOTES.indexOf(q) < 0 || escaped(text, i, lineStart)) {
        i++;
        continue;
      }
      int close = i + 1;
      while (close < lineEnd && (text.charAt(close) != q || escaped(text, close, lineStart))) {
        close++;
      }
      if (close >= lineEnd) {
        i++;
        continue;
      }
      if (i < s.start() && close >= s.end()) {
        out.add(new int[] {i + 1, close});
        out.add(new int[] {i, close + 1});
      }
      i = close + 1;
    }
  }

  /** Reports whether an odd run of backslashes precedes an offset on its line. */
  private static boolean escaped(TextContent text, int at, int lineStart) {
    int count = 0;
    while (at - count > lineStart && text.charAt(at - count - 1) == '\\') {
      count++;
    }
    return count % 2 != 0;
  }

  /**
   * Returns {open, close} of the innermost bracket pair around a selection, an empty array when
   * none holds it, or one element when the search reached {@code scan} chars before the text's end.
   */
  private static int[] brackets(
      TextContent text,
      Selection s,
      Pairs pairs,
      Brackets.Filter filter,
      int scan,
      CancellationToken token) {
    int low = (int) Math.max(0, (long) s.start() - scan);
    int high = (int) Math.min(text.length(), (long) s.end() + scan);
    Map<Character, Integer> depth = new HashMap<Character, Integer>();
    for (int i = s.start() - 1; i >= low; i--) {
      if ((s.start() - i) % CHECK_CHARS == 0) {
        token.check();
      }
      char c = text.charAt(i);
      if (!pairs.isBracket(c) || !filter.counts(i)) {
        continue;
      }
      if (!pairs.opens(c)) {
        depth.merge(pairs.partner(c), 1, Integer::sum);
        continue;
      }
      int open = depth.getOrDefault(c, 0);
      if (open > 0) {
        depth.put(c, open - 1);
        continue;
      }
      int partner = Brackets.match(text, i, pairs, i, high, token, filter);
      if (partner == Brackets.BEYOND) {
        return new int[] {Brackets.BEYOND};
      }
      if (partner >= s.end()) {
        return new int[] {i, partner};
      }
    }
    return low > 0 ? new int[] {Brackets.BEYOND} : new int[0];
  }
}
