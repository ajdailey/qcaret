package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Edit;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-indent and smart indent at every selection, as typing edits.
 *
 * <ul>
 *   <li>Enter copies the leading whitespace of the caret's line, up to the caret, to the new line.
 *       After an opener ({@link IndentRules#increase}) the new line takes one more level, a tab or
 *       a tab width of spaces as the document indents; when the text after the caret starts with a
 *       closer ({@link IndentRules#decrease}), the closer moves to a line of its own at the
 *       original indentation and the caret stays on the indented line between.
 *   <li>A closer typed on a line holding only whitespace before the caret outdents the line to the
 *       previous non-blank line's indentation, one level less unless that line opens a level. The
 *       line is never indented further.
 * </ul>
 */
public final class SmartIndent {
  /** Lines searched upward for a non-blank line. */
  static final int SEARCH_LINES = 1000;

  private SmartIndent() {}

  /**
   * Returns the edit Enter makes at every selection.
   *
   * @param text the text
   * @param set the selections
   * @param ending the line ending to insert
   * @param rules the indent rules
   * @param tabWidth columns per tab stop
   * @param spaces true to indent with spaces
   * @return the edit; each caret lands on its new line
   */
  public static Edit newline(
      TextContent text,
      SelectionSet set,
      String ending,
      IndentRules rules,
      int tabWidth,
      boolean spaces) {
    List<Replacement> replacements = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    for (Selection s : set.selections()) {
      int lineStart = text.offsetOfLine(text.lineAt(s.start()));
      String before = text.subSequence(lineStart, s.start()).toString();
      String indent = before.substring(0, leading(before));
      String insert;
      int caret;
      if (rules.increase(before)) {
        String inner = indent + unit(tabWidth, spaces);
        int endLine = text.lineAt(s.end());
        int lineEnd = text.offsetOfLine(endLine) + text.lineLength(endLine);
        insert = ending + inner;
        caret = insert.length();
        if (rules.decrease(text.subSequence(s.end(), lineEnd))) {
          insert += ending + indent;
        }
      } else {
        insert = ending + indent;
        caret = insert.length();
      }
      replacements.add(new Replacement(s.start(), s.end() - s.start(), insert));
      after.add(Selection.caret(s.start() + shift + caret));
      shift += insert.length() - (s.end() - s.start());
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.TYPING, replacements),
        SelectionSet.of(after, set.primaryIndex()));
  }

  /**
   * Returns the edit typing a closer makes when it outdents a line at some caret.
   *
   * @param text the text
   * @param set the selections
   * @param typed the typed text
   * @param rules the indent rules
   * @param tabWidth columns per tab stop
   * @param spaces true to indent with spaces
   * @return the edit, or null when no caret outdents its line, so the text types as usual
   */
  public static Edit typed(
      TextContent text,
      SelectionSet set,
      String typed,
      IndentRules rules,
      int tabWidth,
      boolean spaces) {
    if (typed.length() != 1) {
      return null;
    }
    List<Replacement> replacements = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int lastLine = -1;
    boolean any = false;
    for (Selection s : set.selections()) {
      int line = text.lineAt(s.start());
      int lineStart = text.offsetOfLine(line);
      String before = text.subSequence(lineStart, s.start()).toString();
      String indent = null;
      if (s.isEmpty()
          && line != lastLine
          && !before.isEmpty()
          && leading(before) == before.length()
          && rules.decrease(before + typed)) {
        indent = outdented(text, line, rules, tabWidth, spaces);
        if (indent != null
            && Columns.column(indent, indent.length(), tabWidth)
                >= Columns.column(before, before.length(), tabWidth)) {
          indent = null;
        }
      }
      Replacement r =
          indent == null
              ? new Replacement(s.start(), s.end() - s.start(), typed)
              : new Replacement(lineStart, before.length(), indent + typed);
      any |= indent != null;
      replacements.add(r);
      after.add(Selection.caret(r.offset() + shift + r.text().length()));
      shift += r.text().length() - r.removeLength();
      lastLine = text.lineAt(s.end());
    }
    if (!any) {
      return null;
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.TYPING, replacements),
        SelectionSet.of(after, set.primaryIndex()));
  }

  /** Returns the indentation a closer line takes after the previous non-blank line, or null. */
  private static String outdented(
      TextContent text, int line, IndentRules rules, int tabWidth, boolean spaces) {
    for (int above = line - 1; above >= Math.max(0, line - SEARCH_LINES); above--) {
      String previous = text.line(above);
      int lead = leading(previous);
      if (lead == previous.length()) {
        continue;
      }
      String indent = previous.substring(0, lead);
      if (rules.increase(previous)) {
        return indent;
      }
      int columns = Math.max(0, Columns.column(indent, lead, tabWidth) - tabWidth);
      return spaces
          ? " ".repeat(columns)
          : "\t".repeat(columns / tabWidth) + " ".repeat(columns % tabWidth);
    }
    return null;
  }

  private static String unit(int tabWidth, boolean spaces) {
    return spaces ? " ".repeat(tabWidth) : "\t";
  }

  /** Returns the length of a text's leading spaces and tabs. */
  private static int leading(CharSequence line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }
}
