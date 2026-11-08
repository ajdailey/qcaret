package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Wrap Paragraph: hard-wraps paragraphs at a column. With a selection, every paragraph in the lines
 * the selections touch is wrapped; with carets only, the paragraph at each caret.
 *
 * <p>A paragraph is a run of lines that are not blank and that all start, after their indent, with
 * the line comment, or none of which does. A line holding only blanks, or only its indent, the
 * comment and blanks, is blank. Each wrapped line starts with the first line's prefix: its indent,
 * plus the comment and the blanks after it on a comment paragraph; the other lines' prefixes are
 * dropped. Words are runs of chars other than space and tab, joined by one space. A line takes the
 * words that keep it within the column, tabs counted to the next tab stop and a char per code
 * point; a longer word is a line of its own. Wrapped lines end in the paragraph's first line break,
 * or in the given line ending for a one-line paragraph.
 */
public final class ParagraphWrap {
  /** Lines between cancellation checks. */
  private static final int CHECK_LINES = 4096;

  private final TextContent text;
  private final int column;
  private final String comment;
  private final int tabWidth;
  private final String lineEnding;
  private final CancellationToken token;
  private int checked;

  private ParagraphWrap(
      TextContent text,
      int column,
      String comment,
      int tabWidth,
      String lineEnding,
      CancellationToken token) {
    this.text = text;
    this.column = column;
    this.comment = comment == null ? "" : comment;
    this.tabWidth = Math.max(1, tabWidth);
    this.lineEnding = lineEnding;
    this.token = token;
  }

  /**
   * Wraps the paragraphs. A selection afterwards spans its wrapped lines; a caret in a wrapped
   * paragraph ends up at the paragraph's end.
   *
   * @param text the text
   * @param set the selections
   * @param column the widest line, in columns, at least 1
   * @param comment the grammar's line comment, or null or empty for none
   * @param tabWidth columns per tab stop
   * @param lineEnding the break of a one-line paragraph
   * @param token checked every {@value #CHECK_LINES} lines
   * @return one {@link EditBatch.Kind#COMMAND} edit, or null when nothing changes
   * @throws CancellationException when canceled
   */
  public static Edit wrap(
      TextContent text,
      SelectionSet set,
      int column,
      String comment,
      int tabWidth,
      String lineEnding,
      CancellationToken token) {
    return new ParagraphWrap(text, Math.max(1, column), comment, tabWidth, lineEnding, token)
        .edit(set);
  }

  private Edit edit(SelectionSet set) {
    boolean carets = true;
    for (Selection s : set.selections()) {
      carets &= s.isEmpty();
    }
    List<int[]> paragraphs = carets ? atCarets(set) : inBlocks(set);
    List<Replacement> out = new ArrayList<Replacement>();
    for (int[] p : paragraphs) {
      check();
      Replacement r = wrapped(p[0], p[1]);
      if (r != null) {
        out.add(r);
      }
    }
    if (out.isEmpty()) {
      return null;
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      if (carets) {
        after.add(Selection.caret(Lines.map(out, s.caret(), false)));
      } else {
        int start = Lines.map(out, s.start(), true);
        int end = Lines.map(out, s.end(), false);
        after.add(s.caret() < s.anchor() ? Selection.of(end, start) : Selection.of(start, end));
      }
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }

  /** Returns the paragraphs in the selected lines, top first, as {first, last} line pairs. */
  private List<int[]> inBlocks(SelectionSet set) {
    List<int[]> out = new ArrayList<int[]>();
    for (int[] block : Lines.blocks(text, set, false)) {
      int line = block[0];
      while (line <= block[1]) {
        check();
        if (blank(line)) {
          line++;
          continue;
        }
        boolean commented = commented(line);
        int last = line;
        while (last < block[1] && !blank(last + 1) && commented(last + 1) == commented) {
          last++;
          check();
        }
        out.add(new int[] {line, last});
        line = last + 1;
      }
    }
    return out;
  }

  /** Returns the paragraph at each caret on a line that is not blank, top first, each once. */
  private List<int[]> atCarets(SelectionSet set) {
    List<int[]> out = new ArrayList<int[]>();
    int done = -1;
    for (Selection s : set.selections()) {
      int line = text.lineAt(s.caret());
      if (line <= done || blank(line)) {
        continue;
      }
      boolean commented = commented(line);
      int first = line;
      while (first > 0 && !blank(first - 1) && commented(first - 1) == commented) {
        first--;
        check();
      }
      int last = line;
      while (last + 1 < text.lineCount() && !blank(last + 1) && commented(last + 1) == commented) {
        last++;
        check();
      }
      out.add(new int[] {first, last});
      done = last;
    }
    return out;
  }

  /** Returns the replacement wrapping lines {@code first} to {@code last}, or null for none. */
  private Replacement wrapped(int first, int last) {
    String head = text.line(first);
    String prefix = head.substring(0, contentStart(head));
    int room = column - width(prefix);
    String newline =
        first < last
            ? text.subSequence(Lines.end(text, first), text.offsetOfLine(first + 1)).toString()
            : lineEnding;
    StringBuilder out = new StringBuilder();
    out.append(prefix);
    int used = 0;
    for (int line = first; line <= last; line++) {
      check();
      String content = text.line(line);
      int i = contentStart(content);
      int length = content.length();
      while (i < length) {
        int end = i;
        while (end < length && !blank(content.charAt(end))) {
          end++;
        }
        if (end > i) {
          int words = content.codePointCount(i, end);
          if (used > 0 && used + 1 + words > room) {
            out.append(newline).append(prefix);
            used = 0;
          }
          if (used > 0) {
            out.append(' ');
            used++;
          }
          out.append(content, i, end);
          used += words;
        }
        i = end + 1;
      }
    }
    int start = text.offsetOfLine(first);
    int end = Lines.end(text, last);
    String wrapped = out.toString();
    if (wrapped.contentEquals(text.subSequence(start, end))) {
      return null;
    }
    return new Replacement(start, end - start, wrapped);
  }

  /** Returns where a line's words start: after its indent, and the comment and blanks after it. */
  private int contentStart(String line) {
    int i = indent(line);
    if (!comment.isEmpty() && line.startsWith(comment, i)) {
      i += comment.length();
      while (i < line.length() && blank(line.charAt(i))) {
        i++;
      }
    }
    return i;
  }

  private boolean blank(int line) {
    String content = text.line(line);
    return contentStart(content) == content.length();
  }

  private boolean commented(int line) {
    String content = text.line(line);
    return !comment.isEmpty() && content.startsWith(comment, indent(content));
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && blank(line.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean blank(char c) {
    return c == ' ' || c == '\t';
  }

  /** Returns the columns a prefix takes from column 0. */
  private int width(String prefix) {
    int at = 0;
    for (int i = 0; i < prefix.length(); i++) {
      at = prefix.charAt(i) == '\t' ? (at / tabWidth + 1) * tabWidth : at + 1;
    }
    return at;
  }

  private void check() {
    if (++checked % CHECK_LINES == 0) {
      token.check();
    }
  }
}
