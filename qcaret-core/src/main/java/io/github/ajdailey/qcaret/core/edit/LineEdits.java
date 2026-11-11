package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Line and text commands as functions from a text and its selections to an {@link Edit}. A line a
 * selection touches is any line holding part of it; a range that ends at a line's start leaves that
 * line out. Each function returns null when it changes nothing. Every batch is a {@link
 * EditBatch.Kind#COMMAND}.
 */
public final class LineEdits {
  private LineEdits() {}

  /**
   * Duplicates each selected range after itself; when every selection is a caret, duplicates each
   * block of touched lines below itself. The selections stay on the original text.
   *
   * @param text the text
   * @param set the selections
   * @param lineEnding the break added after a last line that has none
   * @return the edit
   */
  public static Edit duplicate(TextContent text, SelectionSet set, String lineEnding) {
    List<Replacement> out = new ArrayList<Replacement>();
    boolean carets = true;
    for (Selection s : set.selections()) {
      carets &= s.isEmpty();
    }
    if (carets) {
      Replacement lastLine = null;
      for (int[] block : Lines.blocks(text, set, false)) {
        int start = text.offsetOfLine(block[0]);
        int next = Lines.next(text, block[1]);
        if (block[1] == text.lineCount() - 1) {
          String lines = text.subSequence(start, Lines.end(text, block[1])).toString();
          lastLine = new Replacement(text.length(), 0, lineEnding + lines);
          out.add(lastLine);
        } else {
          out.add(new Replacement(next, 0, text.subSequence(start, next).toString()));
        }
      }
      // A caret at a line start belongs to that line and moves past a copy inserted there; one at
      // the text's end belongs to the last line and stays before that line's copy.
      List<Selection> after = new ArrayList<Selection>(set.size());
      for (Selection s : set.selections()) {
        int at = s.caret();
        int shift = 0;
        for (Replacement r : out) {
          if (r.offset() < at || (r.offset() == at && r != lastLine)) {
            shift += r.text().length();
          }
        }
        after.add(new Selection(at + shift, at + shift, s.preferredColumn()));
      }
      return new Edit(
          EditBatch.of(EditBatch.Kind.COMMAND, mergedAtEnd(out)),
          SelectionSet.of(after, set.primaryIndex()));
    } else {
      for (Selection s : set.selections()) {
        if (!s.isEmpty()) {
          out.add(new Replacement(s.end(), 0, text.subSequence(s.start(), s.end()).toString()));
        }
      }
    }
    return mapped(out, set);
  }

  /**
   * Moves each block of touched lines one line up or down, past the neighboring line; blocks that
   * touch move together. Nothing moves when a block is at the top (up) or bottom (down). When the
   * move would put a CR and an LF side by side, which joins two breaks into one, the edit has no
   * replacements and counts the touched lines as unchanged. The selections move with their lines.
   *
   * @param text the text
   * @param set the selections
   * @param up true to move up
   * @return the edit, or null
   */
  public static Edit moveLines(TextContent text, SelectionSet set, boolean up) {
    List<int[]> blocks = Lines.blocks(text, set, true);
    int last = text.lineCount() - 1;
    if (up ? blocks.get(0)[0] == 0 : blocks.get(blocks.size() - 1)[1] == last) {
      return null;
    }
    List<Replacement> out = new ArrayList<Replacement>();
    // Per block: its old end, its new start and the length of the break that follows it after.
    int[][] moved = new int[blocks.size()][];
    for (int i = 0; i < blocks.size(); i++) {
      int a = blocks.get(i)[0];
      int b = blocks.get(i)[1];
      String block = slice(text, text.offsetOfLine(a), Lines.end(text, b));
      String blockBreak = slice(text, Lines.end(text, b), Lines.next(text, b));
      if (up) {
        int above = a - 1;
        String line = slice(text, text.offsetOfLine(above), Lines.end(text, above));
        String lineBreak = slice(text, Lines.end(text, above), text.offsetOfLine(a));
        int start = text.offsetOfLine(above);
        out.add(
            new Replacement(
                start, Lines.next(text, b) - start, block + lineBreak + line + blockBreak));
        moved[i] = new int[] {Lines.end(text, b), start, lineBreak.length()};
      } else {
        int below = b + 1;
        String line = slice(text, text.offsetOfLine(below), Lines.end(text, below));
        String lineBreak = slice(text, Lines.end(text, below), Lines.next(text, below));
        int start = text.offsetOfLine(a);
        out.add(
            new Replacement(
                start, Lines.next(text, below) - start, line + blockBreak + block + lineBreak));
        moved[i] =
            new int[] {
              Lines.end(text, b), start + line.length() + blockBreak.length(), lineBreak.length()
            };
      }
    }
    if (Lines.joinsBreaks(text, out)) {
      return Edit.unchanged(Lines.lineCount(blocks));
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      int i = Lines.blockOf(blocks, text.lineAt(s.start()));
      int oldStart = text.offsetOfLine(blocks.get(i)[0]);
      after.add(
          new Selection(
              movedOffset(s.anchor(), oldStart, moved[i]),
              movedOffset(s.caret(), oldStart, moved[i]),
              s.preferredColumn()));
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Deletes every touched line with its break; the last line takes the break before it. Each block
   * leaves a caret at the start of the line that takes its place. When a CR and an LF would become
   * neighbors, the edit has no replacements and counts the touched lines as unchanged.
   *
   * @param text the text
   * @param set the selections
   * @return the edit
   */
  public static Edit deleteLines(TextContent text, SelectionSet set) {
    List<int[]> blocks = Lines.blocks(text, set, true);
    int last = text.lineCount() - 1;
    List<Replacement> out = new ArrayList<Replacement>();
    List<Selection> after = new ArrayList<Selection>();
    int primaryBlock = Lines.blockOf(blocks, text.lineAt(set.primary().start()));
    int removed = 0;
    for (int[] block : blocks) {
      int from;
      int to;
      int caret;
      if (block[1] < last) {
        from = text.offsetOfLine(block[0]);
        to = text.offsetOfLine(block[1] + 1);
        caret = from;
      } else if (block[0] > 0) {
        from = Lines.end(text, block[0] - 1);
        to = text.length();
        caret = text.offsetOfLine(block[0] - 1);
      } else {
        from = 0;
        to = text.length();
        caret = 0;
      }
      out.add(new Replacement(from, to - from, ""));
      after.add(Selection.caret(caret - removed));
      removed += to - from;
    }
    if (Lines.joinsBreaks(text, out)) {
      return Edit.unchanged(Lines.lineCount(blocks));
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, primaryBlock));
  }

  /**
   * Joins the lines each selection touches, or a caret's line with the next: each break and the
   * leading whitespace after it become one space, or nothing after an empty line, after whitespace,
   * or before an empty rest. A caret lands at its first join; a range keeps its ends.
   *
   * @param text the text
   * @param set the selections
   * @return the edit, or null when no line has a next one
   */
  public static Edit joinLines(TextContent text, SelectionSet set) {
    int lastLine = text.lineCount() - 1;
    List<int[]> ranges = new ArrayList<int[]>();
    for (Selection s : set.selections()) {
      int first = text.lineAt(s.start());
      int last = Math.max(Lines.lastLine(text, s), Math.min(first + 1, lastLine));
      int[] previous = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
      if (previous != null && first <= previous[1]) {
        previous[1] = Math.max(previous[1], last);
      } else if (last > first) {
        ranges.add(new int[] {first, last});
      }
    }
    List<Replacement> out = new ArrayList<Replacement>();
    for (int[] range : ranges) {
      for (int line = range[0]; line < range[1]; line++) {
        int end = Lines.end(text, line);
        int next = text.offsetOfLine(line + 1);
        int nextEnd = Lines.end(text, line + 1);
        int rest = next;
        while (rest < nextEnd && isBlank(text.charAt(rest))) {
          rest++;
        }
        boolean space =
            end > text.offsetOfLine(line) && !isBlank(text.charAt(end - 1)) && rest < nextEnd;
        out.add(new Replacement(end, rest - end, space ? " " : ""));
      }
    }
    if (out.isEmpty()) {
      return null;
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      if (s.isEmpty() && text.lineAt(s.caret()) < lastLine) {
        after.add(Selection.caret(Lines.map(out, Lines.end(text, text.lineAt(s.caret())), true)));
      } else {
        after.add(Lines.map(out, s));
      }
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Converts each selected range, or the word at a caret, to upper or lower case, as {@link
   * CaseEdits#convert(TextContent, SelectionSet, CaseEdits.Mode)} does.
   *
   * @param text the text
   * @param set the selections
   * @param upper true for upper case
   * @return the edit, or null when nothing changes
   */
  public static Edit changeCase(TextContent text, SelectionSet set, boolean upper) {
    return CaseEdits.convert(text, set, upper ? CaseEdits.Mode.UPPER : CaseEdits.Mode.LOWER);
  }

  /**
   * Inserts one indent at the start of every touched line that is not empty: a tab, or spaces to
   * the next tab stop. The selections map through the insert.
   *
   * @param text the text
   * @param set the selections
   * @param tabWidth columns per tab stop
   * @param spaces true to indent with spaces
   * @return the edit, or null when every touched line is empty
   */
  public static Edit indent(TextContent text, SelectionSet set, int tabWidth, boolean spaces) {
    List<Replacement> out = new ArrayList<Replacement>();
    String unit = spaces ? " ".repeat(tabWidth) : "\t";
    for (int[] block : Lines.blocks(text, set, false)) {
      for (int line = block[0]; line <= block[1]; line++) {
        if (text.lineLength(line) > 0) {
          out.add(new Replacement(text.offsetOfLine(line), 0, unit));
        }
      }
    }
    return out.isEmpty() ? null : new Edit(EditBatch.of(EditBatch.Kind.COMMAND, out), null);
  }

  /**
   * Removes one indent from the start of every touched line: a leading tab, or up to a tab width of
   * leading spaces. The selections map through the removal.
   *
   * @param text the text
   * @param set the selections
   * @param tabWidth columns per tab stop
   * @return the edit, or null when no touched line starts with whitespace
   */
  public static Edit outdent(TextContent text, SelectionSet set, int tabWidth) {
    List<Replacement> out = new ArrayList<Replacement>();
    for (int[] block : Lines.blocks(text, set, false)) {
      for (int line = block[0]; line <= block[1]; line++) {
        int start = text.offsetOfLine(line);
        int end = Lines.end(text, line);
        int at = start;
        if (at < end && text.charAt(at) == '\t') {
          at++;
        } else {
          while (at < end && at - start < tabWidth && text.charAt(at) == ' ') {
            at++;
          }
        }
        if (at > start) {
          out.add(new Replacement(start, at - start, ""));
        }
      }
    }
    return out.isEmpty() ? null : new Edit(EditBatch.of(EditBatch.Kind.COMMAND, out), null);
  }

  /**
   * The Tab key: indents the touched lines when a selection spans lines, else replaces each
   * selection with a tab, or with spaces to the next tab stop from its start column.
   *
   * @param text the text
   * @param set the selections
   * @param tabWidth columns per tab stop
   * @param spaces true to insert spaces
   * @return the edit, or null
   */
  public static Edit insertTab(TextContent text, SelectionSet set, int tabWidth, boolean spaces) {
    for (Selection s : set.selections()) {
      if (Lines.lastLine(text, s) > text.lineAt(s.start())) {
        return indent(text, set, tabWidth, spaces);
      }
    }
    List<Replacement> out = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    for (Selection s : set.selections()) {
      String unit = "\t";
      if (spaces) {
        int line = text.lineAt(s.start());
        int lineStart = text.offsetOfLine(line);
        int column =
            Columns.column(text.subSequence(lineStart, s.start()), s.start() - lineStart, tabWidth);
        unit = " ".repeat(tabWidth - column % tabWidth);
      }
      out.add(new Replacement(s.start(), s.end() - s.start(), unit));
      after.add(Selection.caret(s.start() + shift + unit.length()));
      shift += unit.length() - (s.end() - s.start());
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.TYPING, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Splits each selection that spans lines into one selection per line, each from the range's start
   * or the line's start to the range's end or the line's end. A caret or one-line range stays.
   *
   * @param text the text
   * @param set the selections
   * @return the selections
   */
  public static SelectionSet splitIntoLines(TextContent text, SelectionSet set) {
    List<Selection> out = new ArrayList<Selection>();
    int primary = 0;
    for (int i = 0; i < set.size(); i++) {
      Selection s = set.selections().get(i);
      int first = text.lineAt(s.start());
      int last = Lines.lastLine(text, s);
      for (int line = first; line <= last; line++) {
        int from = Math.max(s.start(), text.offsetOfLine(line));
        int to = Math.min(s.end(), Lines.end(text, line));
        out.add(first == last ? s : Selection.of(from, Math.max(from, to)));
      }
      if (i == set.primaryIndex()) {
        primary = out.size() - 1;
      }
    }
    return SelectionSet.of(out, primary);
  }

  /** Returns the replacements with two inserts at the same offset, at the text's end, as one. */
  private static List<Replacement> mergedAtEnd(List<Replacement> out) {
    int n = out.size();
    if (n < 2 || out.get(n - 2).offset() != out.get(n - 1).offset()) {
      return out;
    }
    List<Replacement> merged = new ArrayList<Replacement>(out.subList(0, n - 2));
    String both = out.get(n - 2).text() + out.get(n - 1).text();
    merged.add(new Replacement(out.get(n - 1).offset(), 0, both));
    return merged;
  }

  /**
   * Returns where an offset of a moved block lands: inside the block it moves with it; in the break
   * after the block it lands in the break that follows the block afterwards, at most at its end.
   */
  private static int movedOffset(int offset, int oldStart, int[] moved) {
    int oldEnd = moved[0];
    int newStart = moved[1];
    if (offset <= oldEnd) {
      return newStart + offset - oldStart;
    }
    return newStart + oldEnd - oldStart + Math.min(offset - oldEnd, moved[2]);
  }

  /** Builds an edit whose selections map through inserts with each offset kept before them. */
  private static Edit mapped(List<Replacement> out, SelectionSet set) {
    EditBatch batch = EditBatch.of(EditBatch.Kind.COMMAND, out);
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      after.add(Lines.map(batch.replacements(), s));
    }
    return new Edit(batch, SelectionSet.of(after, set.primaryIndex()));
  }

  private static String slice(TextContent text, int start, int end) {
    return text.subSequence(start, end).toString();
  }

  private static boolean isBlank(char c) {
    return c == ' ' || c == '\t';
  }
}
