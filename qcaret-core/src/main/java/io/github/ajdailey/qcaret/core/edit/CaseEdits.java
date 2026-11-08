package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Case conversions of each selected range, or the word at a caret ({@link Words#at}), one code
 * point at a time and independent of the locale, so a char never expands (no SS for a sharp s). A
 * range stays selected; a caret stays at its offset. Each function returns one {@link
 * EditBatch.Kind#COMMAND} edit, or null when nothing changes.
 */
public final class CaseEdits {
  /** A conversion. */
  public enum Mode {
    /** Every code point to upper case. */
    UPPER,
    /** Every code point to lower case. */
    LOWER,
    /** The first letter of each word to title case, its other letters to lower case. */
    TITLE,
    /** Upper and title case letters to lower case, lower case letters to upper case. */
    SWAP
  }

  private CaseEdits() {}

  /**
   * Converts each selected range, or the word at each caret. In {@link Mode#TITLE} a word is a run
   * of letters, digits and combining marks; an apostrophe between two letters joins them, so {@code
   * don't} becomes {@code Don't}.
   *
   * @param text the text
   * @param set the selections
   * @param mode the conversion
   * @return the edit, or null when nothing changes
   */
  public static Edit convert(TextContent text, SelectionSet set, Mode mode) {
    List<Replacement> out = new ArrayList<Replacement>();
    int lastEnd = -1;
    for (Selection s : set.selections()) {
      int[] range = s.isEmpty() ? Words.at(text, s.caret()) : new int[] {s.start(), s.end()};
      if (range.length == 0 || range[0] < lastEnd || range[0] == range[1]) {
        continue;
      }
      String old = text.subSequence(range[0], range[1]).toString();
      String converted = convert(old, mode);
      if (!converted.equals(old)) {
        out.add(new Replacement(range[0], range[1] - range[0], converted));
      }
      lastEnd = range[1];
    }
    if (out.isEmpty()) {
      return null;
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      if (s.isEmpty()) {
        int at = s.caret();
        int shift = 0;
        for (Replacement r : out) {
          if (r.offset() >= at) {
            break;
          }
          if (at >= r.end()) {
            shift += r.text().length() - r.removeLength();
          } else {
            int into = at - r.offset();
            shift += Math.min(into, r.text().length()) - into;
          }
        }
        after.add(new Selection(at + shift, at + shift, s.preferredColumn()));
      } else {
        int start = Lines.map(out, s.start(), true);
        int end = Lines.map(out, s.end(), false);
        after.add(s.anchor() <= s.caret() ? Selection.of(start, end) : Selection.of(end, start));
      }
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Converts a string.
   *
   * @param text the string
   * @param mode the conversion
   * @return the converted string
   */
  public static String convert(String text, Mode mode) {
    StringBuilder out = new StringBuilder(text.length());
    boolean inWord = false;
    int i = 0;
    while (i < text.length()) {
      int c = text.codePointAt(i);
      int next = i + Character.charCount(c);
      out.appendCodePoint(
          switch (mode) {
            case UPPER -> Character.toUpperCase(c);
            case LOWER -> Character.toLowerCase(c);
            case SWAP -> swap(c);
            case TITLE ->
                Character.isLetter(c) && !inWord
                    ? Character.toTitleCase(c)
                    : Character.toLowerCase(c);
          });
      if (mode == Mode.TITLE) {
        inWord =
            Character.isLetterOrDigit(c)
                || isMark(c)
                || (inWord && isApostrophe(c) && next < text.length() && letterAt(text, next));
      }
      i = next;
    }
    return out.toString();
  }

  /**
   * Reports whether a code point is a combining mark.
   *
   * @param c the code point
   * @return true for a nonspacing, spacing or enclosing mark
   */
  static boolean isMark(int c) {
    int type = Character.getType(c);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  private static int swap(int c) {
    if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
      return Character.toLowerCase(c);
    }
    return Character.isLowerCase(c) ? Character.toUpperCase(c) : c;
  }

  private static boolean isApostrophe(int c) {
    return c == '\'' || c == '\u2019';
  }

  private static boolean letterAt(String text, int index) {
    return Character.isLetter(text.codePointAt(index));
  }
}
