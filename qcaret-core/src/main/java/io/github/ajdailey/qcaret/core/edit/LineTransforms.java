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
 * Sort, reverse, duplicate and blank line removal, blank trims and indentation conversion over line
 * blocks: the lines each selection touches, as {@link LineEdits} counts them, or every line but an
 * empty last one when every selection is a caret. Each function returns one {@link
 * EditBatch.Kind#COMMAND} edit, or null when nothing changes, and runs on a worker for a large
 * text. A block, run or line whose change would put a CR directly before an LF, joining two line
 * breaks, is left as it is, and the edit counts its lines as unchanged; when that leaves nothing to
 * change, the edit has no replacements.
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
    IGNORE_CASE,
    /**
     * By the number at the line's start, after spaces and tabs: an optional sign, digits and a
     * fraction. Lines without one follow the numbered ones; equal keys keep their order.
     */
    NUMERIC
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
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      List<String> lines = lines(text, block, token);
      List<String> sorted;
      if (order == Order.NUMERIC) {
        sorted = NumericKeys.sort(lines, token);
      } else {
        sorted = new ArrayList<String>(lines);
        sorted.sort(comparator(order));
      }
      token.check();
      unchanged += reorder(text, block, lines, sorted, out);
    }
    return reordered(out, set, unchanged);
  }

  /**
   * Reverses the lines of each block. Each line break stays in its place, as in {@link #sort}.
   *
   * @param text the text
   * @param set the selections
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit reverse(TextContent text, SelectionSet set, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      List<String> lines = lines(text, block, token);
      List<String> reversed = new ArrayList<String>(lines);
      Collections.reverse(reversed);
      unchanged += reorder(text, block, lines, reversed, out);
    }
    return reordered(out, set, unchanged);
  }

  /**
   * Adds the replacement giving a block's lines a new order with the breaks in place; returns the
   * lines left as they were because the change would join two breaks.
   */
  private static int reorder(
      TextContent text,
      int[] block,
      List<String> lines,
      List<String> order,
      List<Replacement> out) {
    if (order.equals(lines)) {
      return 0;
    }
    StringBuilder joined = new StringBuilder(Lines.end(text, block[1]) - start(text, block));
    for (int line = block[0]; line <= block[1]; line++) {
      joined.append(order.get(line - block[0]));
      if (line < block[1]) {
        joined.append(text, Lines.end(text, line), text.offsetOfLine(line + 1));
      }
    }
    int start = start(text, block);
    Replacement r = new Replacement(start, Lines.end(text, block[1]) - start, joined.toString());
    return add(text, out, r) ? 0 : block[1] - block[0] + 1;
  }

  private static Edit reordered(List<Replacement> out, SelectionSet set, int unchanged) {
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
    for (int[] block : blocks(text, set)) {
      List<String> lines = lines(text, block, token);
      Set<String> seen = new HashSet<String>();
      boolean[] remove = new boolean[lines.size()];
      for (int i = 0; i < remove.length; i++) {
        remove[i] = !seen.add(lines.get(i));
      }
      unchanged += removeRuns(text, block, remove, out);
    }
    return mapped(out, set, unchanged);
  }

  /**
   * Removes every line of a block that is empty or holds only spaces and tabs, with its break, as
   * {@link #removeDuplicates} removes a line. Removing every line of the text leaves it empty.
   *
   * @param text the text
   * @param set the selections
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit removeBlank(TextContent text, SelectionSet set, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      boolean[] remove = new boolean[block[1] - block[0] + 1];
      for (int line = block[0]; line <= block[1]; line++) {
        if ((line - block[0]) % CHECK_LINES == 0) {
          token.check();
        }
        int end = Lines.end(text, line);
        remove[line - block[0]] = blanks(text, text.offsetOfLine(line), end) == end;
      }
      unchanged += removeRuns(text, block, remove, out);
    }
    return mapped(out, set, unchanged);
  }

  /**
   * Adds a removal for each run of marked lines of a block, with their breaks; a run ending at the
   * text's last line takes the break before it. Returns the lines left because a removal would join
   * two breaks.
   */
  private static int removeRuns(
      TextContent text, int[] block, boolean[] remove, List<Replacement> out) {
    int unchanged = 0;
    int last = text.lineCount() - 1;
    int run = -1;
    for (int line = block[0]; line <= block[1] + 1; line++) {
      boolean marked = line <= block[1] && remove[line - block[0]];
      if (marked && run < 0) {
        run = line;
      } else if (!marked && run >= 0) {
        int end = line - 1;
        int from = end < last ? text.offsetOfLine(run) : run > 0 ? Lines.end(text, run - 1) : 0;
        int to = end < last ? text.offsetOfLine(end + 1) : text.length();
        if (to > from && !add(text, out, new Replacement(from, to - from, ""))) {
          unchanged += end - run + 1;
        }
        run = -1;
      }
    }
    return unchanged;
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

  /**
   * Removes the spaces and tabs at the start of every line of each block. A caret in removed blanks
   * lands at its line's start.
   *
   * @param text the text
   * @param set the selections
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit trimLeading(TextContent text, SelectionSet set, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    int unchanged = 0;
    for (int[] block : blocks(text, set)) {
      for (int line = block[0]; line <= block[1]; line++) {
        if ((line - block[0]) % CHECK_LINES == 0) {
          token.check();
        }
        int start = text.offsetOfLine(line);
        int at = blanks(text, start, Lines.end(text, line));
        if (at > start && !add(text, out, new Replacement(start, at - start, ""))) {
          unchanged++;
        }
      }
    }
    return mapped(out, set, unchanged);
  }

  /**
   * Rewrites the spaces and tabs at the start of every line of each block, a tab counting to the
   * next tab stop: to tabs, one tab per whole tab stop and spaces for the rest; to spaces, one
   * space per column. A caret inside a rewritten indent lands at its start, one at its end after
   * it.
   *
   * @param text the text
   * @param set the selections
   * @param tabWidth the columns between tab stops, at least 1
   * @param tabs true to convert to tabs, false to spaces
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the edit, or null
   * @throws CancellationException when canceled
   */
  public static Edit convertIndent(
      TextContent text, SelectionSet set, int tabWidth, boolean tabs, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    for (int[] block : blocks(text, set)) {
      for (int line = block[0]; line <= block[1]; line++) {
        if ((line - block[0]) % CHECK_LINES == 0) {
          token.check();
        }
        int start = text.offsetOfLine(line);
        int end = blanks(text, start, Lines.end(text, line));
        int columns = 0;
        for (int i = start; i < end; i++) {
          columns = text.charAt(i) == '\t' ? (columns / tabWidth + 1) * tabWidth : columns + 1;
        }
        String indent =
            tabs
                ? "\t".repeat(columns / tabWidth) + " ".repeat(columns % tabWidth)
                : " ".repeat(columns);
        if (!indent.contentEquals(text.subSequence(start, end))) {
          out.add(new Replacement(start, end - start, indent));
        }
      }
    }
    return mapped(out, set, 0);
  }

  /** Returns the offset of the first char of a range other than a space or a tab, or its end. */
  private static int blanks(TextContent text, int start, int end) {
    int at = start;
    while (at < end && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
      at++;
    }
    return at;
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
      case NUMERIC -> throw new IllegalArgumentException("NUMERIC sorts by NumericKeys");
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
