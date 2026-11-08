package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transpose. With two or more non-empty selections, the selected texts rotate: each takes the text
 * of the one before it and the first takes the last one's. Otherwise each caret swaps the
 * characters on either side of it and moves past both; at a line end it swaps the two before it and
 * stays; at a line start it does nothing. A character is a grapheme cluster ({@link
 * BreakIterator#getCharacterInstance}), so a surrogate pair or a base char with its combining marks
 * moves whole, and no swap crosses a line break.
 */
public final class Transpose {
  /** Chars on each side of a caret the cluster boundaries are read from. */
  private static final int WINDOW = 64;

  private Transpose() {}

  /**
   * Returns the transposition as one {@link EditBatch.Kind#COMMAND} edit; the text length does not
   * change.
   *
   * @param text the text
   * @param set the selections
   * @return the edit, or null when nothing changes
   */
  public static Edit transpose(TextContent text, SelectionSet set) {
    int ranges = 0;
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        ranges++;
      }
    }
    return ranges >= 2 ? rotate(text, set) : swapAtCarets(text, set);
  }

  private static Edit rotate(TextContent text, SelectionSet set) {
    List<Selection> ranges = new ArrayList<Selection>();
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        ranges.add(s);
      }
    }
    List<Replacement> out = new ArrayList<Replacement>(ranges.size());
    List<String> moved = new ArrayList<String>(ranges.size());
    for (int i = 0; i < ranges.size(); i++) {
      Selection from = ranges.get((i + ranges.size() - 1) % ranges.size());
      Selection to = ranges.get(i);
      String part = text.subSequence(from.start(), from.end()).toString();
      moved.add(part);
      out.add(new Replacement(to.start(), to.end() - to.start(), part));
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int next = 0;
    for (Selection s : set.selections()) {
      if (s.isEmpty()) {
        after.add(new Selection(s.caret() + shift, s.caret() + shift, s.preferredColumn()));
        continue;
      }
      int start = s.start() + shift;
      int end = start + moved.get(next++).length();
      after.add(s.anchor() <= s.caret() ? Selection.of(start, end) : Selection.of(end, start));
      shift = end - s.end();
    }
    EditBatch batch = EditBatch.of(EditBatch.Kind.COMMAND, out);
    return batch.replacements().isEmpty()
        ? null
        : new Edit(batch, SelectionSet.of(after, set.primaryIndex()));
  }

  private static Edit swapAtCarets(TextContent text, SelectionSet set) {
    List<Replacement> out = new ArrayList<Replacement>();
    List<Selection> after = new ArrayList<Selection>(set.size());
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int[] swap = s.isEmpty() ? swapAt(text, s.caret()) : new int[0];
      if (swap.length == 0 || swap[0] < lastEnd) {
        after.add(s);
        continue;
      }
      String first = text.subSequence(swap[0], swap[1]).toString();
      String second = text.subSequence(swap[1], swap[2]).toString();
      out.add(new Replacement(swap[0], swap[2] - swap[0], second + first));
      after.add(Selection.caret(swap[3]));
      lastEnd = swap[2];
    }
    if (out.isEmpty()) {
      return null;
    }
    EditBatch batch = EditBatch.of(EditBatch.Kind.COMMAND, out);
    return batch.replacements().isEmpty()
        ? null
        : new Edit(batch, SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Returns {start, middle, end, caret after} of the two clusters a caret swaps, or an empty array
   * when it swaps nothing.
   */
  private static int[] swapAt(TextContent text, int caret) {
    int line = text.lineAt(caret);
    int lineStart = text.offsetOfLine(line);
    int lineEnd = Lines.end(text, line);
    if (caret <= lineStart || caret > lineEnd) {
      return new int[0];
    }
    int from = Math.max(lineStart, caret - WINDOW);
    int to = Math.min(lineEnd, caret + WINDOW);
    BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
    clusters.setText(text.subSequence(from, to).toString());
    int at = caret - from;
    if (!clusters.isBoundary(at)) {
      return new int[0];
    }
    if (caret == lineEnd) {
      int middle = clusters.preceding(at);
      int start = middle == BreakIterator.DONE ? BreakIterator.DONE : clusters.preceding(middle);
      if (start == BreakIterator.DONE) {
        return new int[0];
      }
      return new int[] {from + start, from + middle, caret, caret};
    }
    int start = clusters.preceding(at);
    int end = clusters.following(at);
    return new int[] {from + start, caret, from + end, from + end};
  }
}
