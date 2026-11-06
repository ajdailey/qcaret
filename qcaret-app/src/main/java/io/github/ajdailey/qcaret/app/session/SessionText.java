package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The text forms of the values session and recovery files hold. */
public final class SessionText {
  /** Most selections written for one view. */
  public static final int MAX_SELECTIONS = 10_000;

  private SessionText() {}

  /**
   * Formats selections: {@code anchor,caret} pairs, {@code ;}-separated, the primary first; at most
   * {@link #MAX_SELECTIONS}.
   *
   * @param set the selections
   * @return the text
   */
  public static String selections(SelectionSet set) {
    StringBuilder text = new StringBuilder();
    append(text, set.primary());
    int written = 1;
    for (int i = 0; i < set.size() && written < MAX_SELECTIONS; i++) {
      if (i != set.primaryIndex()) {
        text.append(';');
        append(text, set.selections().get(i));
        written++;
      }
    }
    return text.toString();
  }

  private static void append(StringBuilder text, Selection s) {
    text.append(s.anchor()).append(',').append(s.caret());
  }

  /**
   * Reads selections, clamped to a text's length.
   *
   * @param text as {@link #selections(SelectionSet)}
   * @param length the text's length
   * @return the selections, or null when the text is empty or malformed
   */
  public static SelectionSet selections(String text, int length) {
    if (text.isBlank()) {
      return null;
    }
    List<Selection> list = new ArrayList<Selection>();
    for (String item : text.split(";")) {
      int comma = item.indexOf(',');
      if (comma <= 0 || list.size() == MAX_SELECTIONS) {
        break;
      }
      try {
        int anchor = Integer.parseInt(item.substring(0, comma).strip());
        int caret = Integer.parseInt(item.substring(comma + 1).strip());
        if (anchor < 0 || caret < 0) {
          break;
        }
        list.add(Selection.of(Math.min(anchor, length), Math.min(caret, length)));
      } catch (NumberFormatException e) {
        break;
      }
    }
    return list.isEmpty() ? null : SelectionSet.of(list, 0);
  }

  /**
   * Formats whole numbers, comma-separated.
   *
   * @param values the numbers
   * @return the text
   */
  public static String numbers(int[] values) {
    StringBuilder text = new StringBuilder();
    for (int value : values) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(value);
    }
    return text.toString();
  }

  /**
   * Reads whole numbers that are not negative.
   *
   * @param text as {@link #numbers(int[])}
   * @return the numbers; a malformed item ends the list
   */
  public static int[] numbers(String text) {
    if (text.isBlank()) {
      return new int[0];
    }
    String[] items = text.split(",");
    int[] values = new int[items.length];
    int count = 0;
    for (String item : items) {
      try {
        int value = Integer.parseInt(item.strip());
        if (value < 0) {
          break;
        }
        values[count++] = value;
      } catch (NumberFormatException e) {
        break;
      }
    }
    return Arrays.copyOf(values, count);
  }

  /**
   * Looks up a remembered charset.
   *
   * @param name the charset's name
   * @return the charset, or null when the name is malformed or the runtime lacks it
   */
  public static Charset charset(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      return null;
    }
  }
}
