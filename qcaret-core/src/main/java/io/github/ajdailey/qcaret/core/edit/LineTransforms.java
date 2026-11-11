package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Sort, duplicate removal and trailing-blank trim over line blocks: the lines each selection
 * touches, as {@link LineEdits} counts them, or every line but an empty last one when every
 * selection is a caret. Each function returns one {@link EditBatch.Kind#COMMAND} edit, or null when
 * nothing changes, and runs on a worker for a large text. A block, run or line whose change would
 * put a CR directly before an LF, joining two line breaks, is left as it is, and the edit counts
 * its lines as unchanged; when that leaves nothing to change, the edit has no replacements.
 */
public final class LineTransforms {
  /** Lines between cancellation checks. */
  private static final int CHECK_LINES = 4096;

  /** A sort order. */
  public enum Order {
    /** By UTF-16 code unit. */
    ASCENDING,
    /** By UTF-16 code unit, last first. */
    DESCENDING,
    /** Ignoring case, then by code unit. */
    IGNORE_CASE
  }

  private LineTransforms() {}

  /**
   * Sorts the lines of each block. Each line break stays in its place, so a block with mixed breaks
   * keeps them in order, the length does not change and the selections keep their offsets.
   *
   * @param text the text
   * @param set the selections
   * @param order the order
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit sort(
      TextContent text, SelectionSet set, Order order, CancellationToken token) {
    Comparator<String> comparator = comparator(order);
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      List<String> lines = lines(text, block, token);
      List<String> sorted = new ArrayList<String>(lines);
      sorted.sort(comparator);
      token.check();
      if (sorted.equals(lines)) {
        continue;
      }
      StringBuilder joined = new StringBuilder(Lines.end(text, block[1]) - start(text, block));
      for (int line = block[0]; line <= block[1]; line++) {
        joined.append(sorted.get(line - block[0]));
        if (line < block[1]) {
          joined.append(text, Lines.end(text, line), text.offsetOfLine(line + 1));
        }
      }
      int start = start(text, block);
      Replacement r = new Replacement(start, Lines.end(text, block[1]) - start, joined.toString());
      if (!add(text, out, r)) {
        unchanged += block[1] - block[0] + 1;
      }
    }
    if (out.isEmpty()) {
      return unchanged == 0 ? null : Edit.unchanged(unchanged);
    }
    return new Edit(EditBatch.of(EditBatch.Kind.COMMAND, out), set, unchanged);
  }

  /**
   * Removes every line of a block equal to an earlier line of the block, with its break; a removed
   * last line of the text takes the break before it. Selections map through the removal; an offset
   * in a removed line lands where the line was.
   *
   * @param text the text
   * @param set the selections
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit removeDuplicates(TextContent text, SelectionSet set, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    int last = text.lineCount() - 1;
    for (int[] block : blocks(text, set)) {
      List<String> lines = lines(text, block, token);
      Set<String> seen = new HashSet<String>();
      int run = -1;
      for (int line = block[0]; line <= block[1] + 1; line++) {
        boolean duplicate = line <= block[1] && !seen.add(lines.get(line - block[0]));
        if (duplicate && run < 0) {
          run = line;
        } else if (!duplicate && run >= 0) {
          int end = line - 1;
          int from = end < last ? text.offsetOfLine(run) : Lines.end(text, run - 1);
          int to = end < last ? text.offsetOfLine(end + 1) : text.length();
          if (!add(text, out, new Replacement(from, to - from, ""))) {
            unchanged += end - run + 1;
          }
          run = -1;
        }
      }
    }
    return mapped(out, set, unchanged);
  }

  /**
   * Removes the spaces and tabs at the end of every line of each block. A caret in removed blanks
   * lands at its line's new end.
   *
   * @param text the text
   * @param set the selections
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit trimTrailing(TextContent text, SelectionSet set, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      for (int line = block[0]; line <= block[1]; line++) {
        if ((line - block[0]) % CHECK_LINES == 0) {
          token.check();
        }
        int start = text.offsetOfLine(line);
        int end = Lines.end(text, line);
        int at = end;
        while (at > start && (text.charAt(at - 1) == ' ' || text.charAt(at - 1) == '\t')) {
          at--;
        }
        if (at < end && !add(text, out, new Replacement(at, end - at, ""))) {
          unchanged++;
        }
      }
    }
    return mapped(out, set, unchanged);
  }

  /** Adds a replacement unless it joins two line breaks; returns false when it does. */
  private static boolean add(TextContent text, List<Replacement> out, Replacement r) {
    if (Lines.joinsBreaks(text, Collections.singletonList(r))) {
      return false;
    }
    out.add(r);
    return true;
  }

  private static Edit mapped(List<Replacement> out, SelectionSet set, int unchanged) {
    if (out.isEmpty()) {
      return unchanged == 0 ? null : Edit.unchanged(unchanged);
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      after.add(Lines.map(out, s));
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out),
        SelectionSet.of(after, set.primaryIndex()),
        unchanged);
  }

  /**
   * Returns the blocks: when every selection is a caret, every line but an empty last one, so a
   * final line break stays last.
   */
  private static List<int[]> blocks(TextContent text, SelectionSet set) {
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        return Lines.blocks(text, set, false);
      }
    }
    int last = text.lineCount() - 1;
    if (last > 0 && text.lineLength(last) == 0) {
      last--;
    }
    return Collections.singletonList(new int[] {0, last});
  }

  private static int start(TextContent text, int[] block) {
    return text.offsetOfLine(block[0]);
  }

  private static List<String> lines(TextContent text, int[] block, CancellationToken token) {
    List<String> lines = new ArrayList<String>(block[1] - block[0] + 1);
    for (int line = block[0]; line <= block[1]; line++) {
      if ((line - block[0]) % CHECK_LINES == 0) {
        token.check();
      }
      lines.add(text.line(line));
    }
    return lines;
  }

  private static Comparator<String> comparator(Order order) {
    return switch (order) {
      case ASCENDING -> Comparator.naturalOrder();
      case DESCENDING -> Comparator.reverseOrder();
      case IGNORE_CASE ->
          new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
              int c = a.compareToIgnoreCase(b);
              return c != 0 ? c : a.compareTo(b);
            }
          };
    };
  }
}
