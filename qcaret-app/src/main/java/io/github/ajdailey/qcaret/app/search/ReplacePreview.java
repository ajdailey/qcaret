package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.app.view.PreviewLine;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.diff.LineDiff;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.search.FileReplace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Builds a replace preview's before and after view of one text: the lines the replacements change,
 * {@value #CONTEXT} unchanged lines around each change, and a gap row between changes further
 * apart. Replacements whose context lines overlap form one region; each region's lines before and
 * after are compared by {@link LineDiff}, so only the region's lines are split and diffed, not the
 * whole text. The view stops after {@value #MAX_LINES} lines with a note.
 */
public final class ReplacePreview {
  /** Unchanged lines shown around a change. */
  public static final int CONTEXT = 2;

  /** Lines shown at most. */
  public static final int MAX_LINES = 2000;

  private ReplacePreview() {}

  /**
   * Computes the view.
   *
   * @param text the text before the replace
   * @param replacements ascending, disjoint replacements
   * @param more the note shown when lines are left out, given the changes not shown
   * @param token checked per region
   * @return the lines
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static List<PreviewLine> lines(
      CharSequence text,
      List<Replacement> replacements,
      IntFunction<String> more,
      CancellationToken token) {
    List<PreviewLine> out = new ArrayList<PreviewLine>();
    int[] starts = lineStarts(text);
    int lines = starts.length;
    int shift = 0;
    int i = 0;
    while (i < replacements.size()) {
      token.check();
      int first = Math.max(0, lineAt(starts, replacements.get(i).offset()) - CONTEXT);
      int last = Math.min(lines - 1, lastLine(starts, replacements.get(i)) + CONTEXT);
      int j = i + 1;
      while (j < replacements.size()
          && lineAt(starts, replacements.get(j).offset()) - CONTEXT <= last + 1) {
        last = Math.min(lines - 1, Math.max(last, lastLine(starts, replacements.get(j)) + CONTEXT));
        j++;
      }
      int from = starts[first];
      int to = last + 1 < lines ? starts[last + 1] : text.length();
      List<Replacement> region = new ArrayList<Replacement>(j - i);
      for (int k = i; k < j; k++) {
        Replacement r = replacements.get(k);
        region.add(new Replacement(r.offset() - from, r.removeLength(), r.text()));
      }
      CharSequence before = text.subSequence(from, to);
      List<String> beforeLines = split(before);
      List<String> afterLines = split(FileReplace.apply(before, region));
      if (!out.isEmpty()) {
        out.add(new PreviewLine(PreviewLine.Kind.GAP, 0, ""));
      }
      for (LineDiff.Line line : LineDiff.diff(beforeLines, afterLines, token)) {
        switch (line.kind()) {
          case SAME ->
              out.add(
                  new PreviewLine(PreviewLine.Kind.SAME, first + line.before() + 1, line.text()));
          case REMOVED ->
              out.add(
                  new PreviewLine(
                      PreviewLine.Kind.REMOVED, first + line.before() + 1, line.text()));
          case ADDED ->
              out.add(
                  new PreviewLine(
                      PreviewLine.Kind.ADDED, first + shift + line.after() + 1, line.text()));
        }
      }
      shift += afterLines.size() - beforeLines.size();
      i = j;
      if (out.size() >= MAX_LINES && i < replacements.size()) {
        out.add(new PreviewLine(PreviewLine.Kind.NOTE, 0, more.apply(replacements.size() - i)));
        break;
      }
    }
    return out;
  }

  /** Splits text into lines without their breaks; a final break ends the last line. */
  static List<String> split(CharSequence text) {
    List<String> out = new ArrayList<String>();
    int start = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        out.add(text.subSequence(start, i).toString());
        i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
        start = i;
      } else {
        i++;
      }
    }
    if (start < text.length()) {
      out.add(text.subSequence(start, text.length()).toString());
    }
    return out;
  }

  /** The start offset of every line; a final line break starts no line. */
  private static int[] lineStarts(CharSequence text) {
    int[] starts = new int[16];
    int count = 1;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
        if (i < text.length()) {
          if (count == starts.length) {
            starts = Arrays.copyOf(starts, count * 2);
          }
          starts[count++] = i;
        }
      } else {
        i++;
      }
    }
    return Arrays.copyOf(starts, count);
  }

  private static int lineAt(int[] starts, int offset) {
    int low = 0;
    int high = starts.length - 1;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (starts[mid] <= offset) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  /** The last line a replacement removes chars from; its first line when it removes none. */
  private static int lastLine(int[] starts, Replacement r) {
    return lineAt(starts, r.removeLength() == 0 ? r.offset() : r.end() - 1);
  }
}
