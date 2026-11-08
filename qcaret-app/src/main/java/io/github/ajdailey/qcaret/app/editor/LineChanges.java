package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.diff.LineDiff;
import java.util.ArrayList;
import java.util.List;

/**
 * The gutter's modified-line marks: for each line of a text, whether it was added or changed since
 * the saved text and whether saved lines were removed above it, from a {@link LineDiff} of the two
 * texts by line. In a run of removed lines followed by added lines, the added lines pair with the
 * removed ones as changed, and the rest are added; a run of removed lines alone marks the line
 * after it, or the last line with {@link #REMOVED_BELOW}. A text over {@value #MAX_LINES} lines or
 * {@value #MAX_CHARS} chars, on either side, is not compared ({@link #overLimit}). Immutable.
 *
 * @param revision the revision of the current text
 * @param kinds a bit set of {@link #ADDED}, {@link #CHANGED}, {@link #REMOVED_ABOVE} and {@link
 *     #REMOVED_BELOW} per line of the current text; empty when nothing is marked
 * @param overLimit true when a side was too large to compare
 */
public record LineChanges(long revision, byte[] kinds, boolean overLimit) {
  /** A line not in the saved text. */
  public static final int ADDED = 1;

  /** A line that replaces saved lines. */
  public static final int CHANGED = 2;

  /** Saved lines were removed before this line. */
  public static final int REMOVED_ABOVE = 4;

  /** Saved lines were removed after this line, the last. */
  public static final int REMOVED_BELOW = 8;

  /** Most lines of a side compared. */
  public static final int MAX_LINES = 100_000;

  /** Most chars of a side compared. */
  public static final int MAX_CHARS = 16 << 20;

  /** No marks. */
  public static final LineChanges NONE = new LineChanges(-1, new byte[0], false);

  /**
   * Returns a line's marks.
   *
   * @param line a line
   * @return its bits, 0 for none or a line out of range
   */
  public int kind(int line) {
    return line >= 0 && line < kinds.length ? kinds[line] : 0;
  }

  /**
   * Reports whether any line is marked.
   *
   * @return false when the texts match or were not compared
   */
  public boolean any() {
    for (byte kind : kinds) {
      if (kind != 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Compares the current text with the saved text by line, line breaks excluded.
   *
   * @param saved the text of the last load or save
   * @param current the current text
   * @param revision the current text's revision
   * @param token checked per edit distance step
   * @return the marks
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static LineChanges compute(
      TextContent saved, TextContent current, long revision, CancellationToken token) {
    if (!fits(saved) || !fits(current)) {
      return new LineChanges(revision, new byte[0], true);
    }
    List<LineDiff.Line> script = LineDiff.diff(lines(saved, token), lines(current, token), token);
    byte[] kinds = new byte[current.lineCount()];
    boolean any = false;
    int i = 0;
    int size = script.size();
    while (i < size) {
      if (script.get(i).kind() == LineDiff.Kind.SAME) {
        i++;
        continue;
      }
      any = true;
      int removedEnd = i;
      while (removedEnd < size && script.get(removedEnd).kind() == LineDiff.Kind.REMOVED) {
        removedEnd++;
      }
      int addedEnd = removedEnd;
      while (addedEnd < size && script.get(addedEnd).kind() == LineDiff.Kind.ADDED) {
        addedEnd++;
      }
      int removed = removedEnd - i;
      for (int k = removedEnd; k < addedEnd; k++) {
        kinds[script.get(k).after()] |= (byte) (k - removedEnd < removed ? CHANGED : ADDED);
      }
      if (addedEnd == removedEnd) {
        if (addedEnd < size) {
          kinds[script.get(addedEnd).after()] |= (byte) REMOVED_ABOVE;
        } else if (kinds.length > 0) {
          kinds[kinds.length - 1] |= (byte) REMOVED_BELOW;
        }
      }
      i = addedEnd;
    }
    return new LineChanges(revision, any ? kinds : new byte[0], false);
  }

  private static boolean fits(TextContent text) {
    return text.lineCount() <= MAX_LINES && text.length() <= MAX_CHARS;
  }

  private static List<String> lines(TextContent text, CancellationToken token) {
    int count = text.lineCount();
    List<String> lines = new ArrayList<String>(count);
    for (int line = 0; line < count; line++) {
      if ((line & 4095) == 0) {
        token.check();
      }
      lines.add(text.line(line));
    }
    return lines;
  }
}
