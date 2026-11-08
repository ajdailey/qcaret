package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Delete Word Back and Delete Word Forward: each selection goes; at a caret, the span a word move
 * ({@link Words#left}, {@link Words#right}) crosses goes, so neither a surrogate pair nor a base
 * char and its marks is split. A line break is deleted on its own, a CR LF as one.
 */
public final class WordDeletes {
  private WordDeletes() {}

  /**
   * Returns the deletion at every selection as one {@link EditBatch.Kind#COMMAND} edit; each caret
   * lands where its span started.
   *
   * @param text the text
   * @param set the selections
   * @param back true to delete before each caret, false after it
   * @return the edit, or null when nothing is deleted
   */
  public static Edit delete(TextContent text, SelectionSet set, boolean back) {
    List<Replacement> out = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int from = s.start();
      int to = s.end();
      if (s.isEmpty()) {
        if (back) {
          from = Words.left(text, s.caret());
        } else {
          to = Words.right(text, s.caret());
        }
      }
      from = Math.max(from, lastEnd);
      to = Math.max(to, from);
      if (to > from) {
        out.add(new Replacement(from, to - from, ""));
      }
      after.add(Selection.caret(from + shift));
      shift -= to - from;
      lastEnd = to;
    }
    if (out.isEmpty()) {
      return null;
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }
}
