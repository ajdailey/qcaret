package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Typing and Backspace with auto-closed pairs, decided at each caret on its own:
 *
 * <ul>
 *   <li>An open char inserts its pair with the caret between when the next char is a line end,
 *       blank, close bracket, {@code ,} or {@code ;}; a quote also needs a char before it that is
 *       no word char and not the same quote.
 *   <li>A close char typed before the same char moves the caret over it.
 *   <li>Backspace between an open char and its close char deletes both.
 * </ul>
 *
 * A selection is replaced as by plain typing. Each function returns null when no caret uses a pair,
 * so plain typing applies.
 */
public final class AutoClose {
  private AutoClose() {}

  /**
   * Types one char at every selection.
   *
   * @param text the text
   * @param set the selections
   * @param typed the char
   * @param pairs the pairs
   * @return the edit, a {@link EditBatch.Kind#TYPING} batch with an empty batch when every caret
   *     only moves over a close char, or null
   */
  public static Edit type(TextContent text, SelectionSet set, char typed, Pairs pairs) {
    char closer = pairs.closer(typed);
    if (closer == 0 && !pairs.isCloser(typed)) {
      return null;
    }
    List<Replacement> out = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    String one = String.valueOf(typed);
    boolean used = false;
    int shift = 0;
    for (Selection s : set.selections()) {
      int at = s.caret();
      if (!s.isEmpty()) {
        out.add(new Replacement(s.start(), s.end() - s.start(), one));
        after.add(Selection.caret(s.start() + shift + 1));
        shift += 1 - (s.end() - s.start());
        continue;
      }
      char next = at < text.length() ? text.charAt(at) : 0;
      char previous = at > 0 ? text.charAt(at - 1) : 0;
      if (pairs.isCloser(typed) && next == typed) {
        used = true;
        after.add(Selection.caret(at + shift + 1));
        continue;
      }
      String inserted = one;
      if (closer != 0 && closes(pairs, typed, closer, previous, next)) {
        inserted = one + closer;
        used = true;
      }
      out.add(new Replacement(at, 0, inserted));
      after.add(Selection.caret(at + shift + 1));
      shift += inserted.length();
    }
    if (!used) {
      return null;
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.TYPING, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Runs Backspace at every caret, deleting a pair around a caret whole and one char elsewhere.
   *
   * @param text the text
   * @param set the selections
   * @param pairs the pairs
   * @return the edit, a {@link EditBatch.Kind#BACKSPACE} batch, or null when a selection is not
   *     empty or no caret sits inside a pair
   */
  public static Edit backspace(TextContent text, SelectionSet set, Pairs pairs) {
    boolean used = false;
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        return null;
      }
      used |= insidePair(text, s.caret(), pairs);
    }
    if (!used) {
      return null;
    }
    List<Replacement> out = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int at = s.caret();
      int from;
      int to = at;
      if (insidePair(text, at, pairs) && at - 1 >= lastEnd) {
        from = at - 1;
        to = at + 1;
      } else {
        from = Math.max(lastEnd, Moves.left(text, at));
      }
      if (to > from) {
        out.add(new Replacement(from, to - from, ""));
      }
      after.add(Selection.caret(from + shift));
      shift -= to - from;
      lastEnd = Math.max(lastEnd, to);
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.BACKSPACE, out), SelectionSet.of(after, set.primaryIndex()));
  }

  private static boolean insidePair(TextContent text, int at, Pairs pairs) {
    if (at <= 0 || at >= text.length()) {
      return false;
    }
    char closer = pairs.closer(text.charAt(at - 1));
    return closer != 0 && text.charAt(at) == closer;
  }

  private static boolean closes(Pairs pairs, char open, char close, char previous, char next) {
    boolean room =
        next == 0
            || Character.isWhitespace(next)
            || next == ','
            || next == ';'
            || (pairs.isCloser(next) && pairs.partner(next) != 0);
    if (!room) {
      return false;
    }
    return open != close || (!Words.isWord(previous) && previous != open);
  }
}
