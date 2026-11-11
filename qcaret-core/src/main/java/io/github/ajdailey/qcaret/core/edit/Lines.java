package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/** Line ranges of selections and offset mapping through replacements, for the edit functions. */
final class Lines {
  private Lines() {}

  /** Returns the offset of a line's end, before its break. */
  static int end(TextContent text, int line) {
    return text.offsetOfLine(line) + text.lineLength(line);
  }

  /** Returns the offset of the next line's start, or the text length for the last line. */
  static int next(TextContent text, int line) {
    return line + 1 < text.lineCount() ? text.offsetOfLine(line + 1) : text.length();
  }

  /** Returns the last line a selection touches: a range ending at a line start leaves it out. */
  static int lastLine(TextContent text, Selection s) {
    int first = text.lineAt(s.start());
    int last = text.lineAt(s.end());
    if (last > first && s.end() == text.offsetOfLine(last)) {
      last--;
    }
    return last;
  }

  /**
   * Returns the line blocks the selections touch, top first, as {first, last} pairs; blocks that
   * overlap, or touch when {@code mergeAdjacent}, are one.
   */
  static List<int[]> blocks(TextContent text, SelectionSet set, boolean mergeAdjacent) {
    List<int[]> out = new ArrayList<int[]>();
    for (Selection s : set.selections()) {
      int first = text.lineAt(s.start());
      int last = lastLine(text, s);
      int[] previous = out.isEmpty() ? null : out.get(out.size() - 1);
      if (previous != null && first <= previous[1] + (mergeAdjacent ? 1 : 0)) {
        previous[1] = Math.max(previous[1], last);
      } else {
        out.add(new int[] {first, last});
      }
    }
    return out;
  }

  /** Returns the number of lines in the blocks. */
  static int lineCount(List<int[]> blocks) {
    int lines = 0;
    for (int[] block : blocks) {
      lines += block[1] - block[0] + 1;
    }
    return lines;
  }

  /** Returns the index of the block holding a line. */
  static int blockOf(List<int[]> blocks, int line) {
    for (int i = 0; i < blocks.size(); i++) {
      if (line >= blocks.get(i)[0] && line <= blocks.get(i)[1]) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Maps an offset of the text before sorted replacements to the text after them. An offset at an
   * insert, or inside or at the start of a replaced range, lands before the new text when {@code
   * before}, else after it.
   */
  static int map(List<Replacement> sorted, int offset, boolean before) {
    int shift = 0;
    for (Replacement r : sorted) {
      if (offset < r.offset()) {
        break;
      }
      if (offset > r.offset() && offset >= r.end()) {
        shift += r.text().length() - r.removeLength();
        continue;
      }
      return r.offset() + shift + (before ? 0 : r.text().length());
    }
    return offset + shift;
  }

  /** Maps a selection through sorted replacements, both ends before inserted text. */
  static Selection map(List<Replacement> sorted, Selection s) {
    return new Selection(
        map(sorted, s.anchor(), true), map(sorted, s.caret(), true), s.preferredColumn());
  }

  /**
   * Reports whether sorted, non-overlapping replacements put a CR directly before an LF, which
   * joins two line breaks into one. No CR LF pair may cross a range's edge before the edit.
   */
  static boolean joinsBreaks(TextContent text, List<Replacement> sorted) {
    for (int i = 0; i < sorted.size(); i++) {
      Replacement r = sorted.get(i);
      StringBuilder result = new StringBuilder(r.text());
      int before = charBefore(text, sorted, i);
      if (before >= 0) {
        result.insert(0, (char) before);
      }
      boolean beforeNext = i + 1 < sorted.size() && r.end() == sorted.get(i + 1).offset();
      if (!beforeNext && r.end() < text.length()) {
        result.append(text.charAt(r.end()));
      }
      if (crLfCount(result) > crLfCount(text.subSequence(r.offset(), r.end()))) {
        return true;
      }
    }
    return false;
  }

  /** Returns the char the result holds before replacement {@code i}, or -1 at the text's start. */
  private static int charBefore(TextContent text, List<Replacement> sorted, int i) {
    int at = sorted.get(i).offset();
    for (int j = i - 1; j >= 0 && sorted.get(j).end() == at; j--) {
      String previous = sorted.get(j).text();
      if (!previous.isEmpty()) {
        return previous.charAt(previous.length() - 1);
      }
      at = sorted.get(j).offset();
    }
    return at > 0 ? text.charAt(at - 1) : -1;
  }

  private static int crLfCount(CharSequence s) {
    int count = 0;
    for (int i = 1; i < s.length(); i++) {
      if (s.charAt(i - 1) == '\r' && s.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }
}
