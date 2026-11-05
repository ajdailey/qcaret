package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.diff.LineDiff;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link Comparison} from two texts with {@link LineDiff}. A side over {@value #MAX_LINES}
 * lines or {@value #MAX_CHARS} chars is not compared. A line over {@value #MAX_LINE_CHARS} chars is
 * compared whole and shown cut, with a notice.
 */
final class Comparisons {
  /** Most lines of a side compared. */
  static final int MAX_LINES = 100_000;

  /** Most chars of a side compared. */
  static final int MAX_CHARS = 16 << 20;

  /** Chars of a line shown. */
  static final int MAX_LINE_CHARS = 10_000;

  private Comparisons() {}

  /**
   * Reports whether a text is within the bounds.
   *
   * @param text the text
   * @return false when it has too many lines or chars to compare
   */
  static boolean fits(TextContent text) {
    return text.lineCount() <= MAX_LINES && text.length() <= MAX_CHARS;
  }

  /**
   * Compares two texts by line, line breaks excluded.
   *
   * @param leftTitle the left side's name
   * @param left the left text, within the bounds
   * @param rightTitle the right side's name
   * @param right the right text, within the bounds
   * @param token checked per edit distance step
   * @return the comparison
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static Comparison build(
      String leftTitle,
      TextContent left,
      String rightTitle,
      TextContent right,
      CancellationToken token) {
    List<LineDiff.Line> script = LineDiff.diff(lines(left), lines(right), token);
    StringBuilder leftRows = new StringBuilder();
    StringBuilder rightRows = new StringBuilder();
    List<Comparison.Row> rows = new ArrayList<Comparison.Row>(script.size());
    List<Integer> changes = new ArrayList<Integer>();
    boolean[] cut = new boolean[1];
    int i = 0;
    while (i < script.size()) {
      LineDiff.Line line = script.get(i);
      if (line.kind() == LineDiff.Kind.SAME) {
        add(rows, leftRows, rightRows, Comparison.Row.SAME, line.text(), line.text(), cut);
        i++;
        continue;
      }
      changes.add(rows.size());
      int removedEnd = i;
      while (removedEnd < script.size() && script.get(removedEnd).kind() == LineDiff.Kind.REMOVED) {
        removedEnd++;
      }
      int addedEnd = removedEnd;
      while (addedEnd < script.size() && script.get(addedEnd).kind() == LineDiff.Kind.ADDED) {
        addedEnd++;
      }
      int removed = removedEnd - i;
      int added = addedEnd - removedEnd;
      for (int k = 0; k < Math.max(removed, added); k++) {
        String l = k < removed ? script.get(i + k).text() : null;
        String r = k < added ? script.get(removedEnd + k).text() : null;
        Comparison.Row kind =
            l != null && r != null
                ? Comparison.Row.CHANGED
                : l != null ? Comparison.Row.REMOVED : Comparison.Row.ADDED;
        add(rows, leftRows, rightRows, kind, l, r, cut);
      }
      i = addedEnd;
    }
    String notice = "";
    if (changes.isEmpty()) {
      notice = UiText.COMPARE_IDENTICAL.text();
    } else if (cut[0]) {
      notice = UiText.COMPARE_LINES_CUT.format(MAX_LINE_CHARS);
    }
    return new Comparison(
        leftTitle, rightTitle, leftRows.toString(), rightRows.toString(), rows, changes, notice);
  }

  private static List<String> lines(TextContent text) {
    int count = text.lineCount();
    List<String> lines = new ArrayList<String>(count);
    for (int line = 0; line < count; line++) {
      lines.add(text.line(line));
    }
    return lines;
  }

  private static void add(
      List<Comparison.Row> rows,
      StringBuilder left,
      StringBuilder right,
      Comparison.Row kind,
      String l,
      String r,
      boolean[] cut) {
    if (!rows.isEmpty()) {
      left.append('\n');
      right.append('\n');
    }
    rows.add(kind);
    append(left, l, cut);
    append(right, r, cut);
  }

  private static void append(StringBuilder out, String line, boolean[] cut) {
    if (line == null) {
      return;
    }
    if (line.length() > MAX_LINE_CHARS) {
      out.append(line, 0, MAX_LINE_CHARS);
      cut[0] = true;
    } else {
      out.append(line);
    }
  }
}
