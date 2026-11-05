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
}
