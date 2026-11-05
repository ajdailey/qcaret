package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Comment and uncomment, at every selection, as one {@link EditBatch.Kind#COMMAND} batch. Each
 * function returns null when it changes nothing or the language has no fitting delimiter. A range
 * keeps text inserted at either end; a caret stays before text inserted at it.
 */
public final class CommentEdits {
  private CommentEdits() {}

  /**
   * Toggles line comments on the lines the selections touch. When every non-blank line starts with
   * the line comment after its indentation, each loses it and one space after it; otherwise each
   * non-blank line gets the comment and a space at its block's smallest indentation. Without a line
   * comment, each block of lines is toggled as one block comment.
   *
   * @param text the text
   * @param set the selections
   * @param line the line comment, or null
   * @param blockStart the block comment opener, or null
   * @param blockEnd the block comment closer, or null
   * @return the edit, or null
   */
  public static Edit toggleLines(
      TextContent text, SelectionSet set, String line, String blockStart, String blockEnd) {
    List<int[]> blocks = Lines.blocks(text, set, false);
    if (line == null || line.isEmpty()) {
      if (!hasBlock(blockStart, blockEnd)) {
        return null;
      }
      List<int[]> ranges = new ArrayList<int[]>();
      for (int[] b : blocks) {
        int[] range = trimmed(text, text.offsetOfLine(b[0]), Lines.end(text, b[1]));
        if (range[1] > range[0]) {
          ranges.add(range);
        }
      }
      return toggleRanges(text, set, ranges, blockStart, blockEnd);
    }
    boolean commented = true;
    boolean any = false;
    for (int[] b : blocks) {
      for (int l = b[0]; l <= b[1]; l++) {
        int indent = indent(text, l);
        if (indent < 0) {
          continue;
        }
        any = true;
        commented &= startsWith(text, text.offsetOfLine(l) + indent, Lines.end(text, l), line);
      }
    }
    if (!any) {
      return null;
    }
    List<Replacement> out = new ArrayList<Replacement>();
    for (int[] b : blocks) {
      int least = Integer.MAX_VALUE;
      for (int l = b[0]; l <= b[1]; l++) {
        int indent = indent(text, l);
        if (indent >= 0) {
          least = Math.min(least, indent);
        }
      }
      for (int l = b[0]; l <= b[1]; l++) {
        int indent = indent(text, l);
        if (indent < 0) {
          continue;
        }
        int start = text.offsetOfLine(l);
        if (commented) {
          int at = start + indent;
          int length = line.length();
          if (at + length < Lines.end(text, l) && text.charAt(at + length) == ' ') {
            length++;
          }
          out.add(new Replacement(at, length, ""));
        } else {
          out.add(new Replacement(start + least, 0, line + " "));
        }
      }
    }
    return mapped(out, set);
  }

  /**
   * Toggles a block comment around each selection, or around a caret's line without its indentation
   * and trailing blanks. A range whose text, trimmed, starts with the opener and ends with the
   * closer loses both and one space inside each; otherwise it is wrapped in the opener and a space,
   * and a space and the closer. A range overlapping or touching an earlier one is skipped.
   *
   * @param text the text
   * @param set the selections
   * @param blockStart the opener, or null
   * @param blockEnd the closer, or null
   * @return the edit, or null
   */
  public static Edit toggleBlock(
      TextContent text, SelectionSet set, String blockStart, String blockEnd) {
    if (!hasBlock(blockStart, blockEnd)) {
      return null;
    }
    List<int[]> ranges = new ArrayList<int[]>();
    for (Selection s : set.selections()) {
      int[] range;
      if (s.isEmpty()) {
        int l = text.lineAt(s.caret());
        range = trimmed(text, text.offsetOfLine(l), Lines.end(text, l));
      } else {
        range = trimmed(text, s.start(), s.end());
      }
      if (range[1] > range[0]) {
        ranges.add(range);
      }
    }
    return toggleRanges(text, set, ranges, blockStart, blockEnd);
  }

  private static Edit toggleRanges(
      TextContent text, SelectionSet set, List<int[]> ranges, String blockStart, String blockEnd) {
    List<Replacement> out = new ArrayList<Replacement>();
    int last = -1;
    for (int[] r : ranges) {
      int start = r[0];
      int end = r[1];
      if (start <= last) {
        continue;
      }
      last = end;
      if (end - start >= blockStart.length() + blockEnd.length()
          && startsWith(text, start, end, blockStart)
          && startsWith(text, end - blockEnd.length(), end, blockEnd)) {
        int open = blockStart.length();
        if (start + open < end - blockEnd.length() && text.charAt(start + open) == ' ') {
          open++;
        }
        int close = blockEnd.length();
        int closeAt = end - close;
        if (closeAt > start + open && text.charAt(closeAt - 1) == ' ') {
          close++;
        }
        out.add(new Replacement(start, open, ""));
        out.add(new Replacement(end - close, close, ""));
      } else {
        out.add(new Replacement(start, 0, blockStart + " "));
        out.add(new Replacement(end, 0, " " + blockEnd));
      }
    }
    return out.isEmpty() ? null : mapped(out, set);
  }

  /** Returns the leading whitespace length of a line, or -1 for a blank line. */
  private static int indent(TextContent text, int line) {
    int start = text.offsetOfLine(line);
    int end = Lines.end(text, line);
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      if (c != ' ' && c != '\t') {
        return i - start;
      }
    }
    return -1;
  }

  /** Returns a range without its leading and trailing whitespace and line breaks. */
  private static int[] trimmed(TextContent text, int start, int end) {
    int from = start;
    int to = end;
    while (from < to && Character.isWhitespace(text.charAt(from))) {
      from++;
    }
    while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
      to--;
    }
    return new int[] {from, to};
  }

  private static boolean startsWith(TextContent text, int at, int limit, String prefix) {
    if (at + prefix.length() > limit) {
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      if (text.charAt(at + i) != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasBlock(String blockStart, String blockEnd) {
    return blockStart != null && !blockStart.isEmpty() && blockEnd != null && !blockEnd.isEmpty();
  }

  private static Edit mapped(List<Replacement> out, SelectionSet set) {
    EditBatch batch = EditBatch.of(EditBatch.Kind.COMMAND, out);
    List<Selection> after = new ArrayList<Selection>(set.size());
    List<Replacement> sorted = batch.replacements();
    for (Selection s : set.selections()) {
      // A range keeps text inserted at either end; a caret stays before it.
      boolean forward = s.anchor() <= s.caret();
      boolean empty = s.isEmpty();
      after.add(
          new Selection(
              Lines.map(sorted, s.anchor(), empty || forward),
              Lines.map(sorted, s.caret(), empty || !forward),
              s.preferredColumn()));
    }
    return new Edit(batch, SelectionSet.of(after, set.primaryIndex()));
  }
}
