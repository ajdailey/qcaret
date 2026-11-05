package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Indentation folding: a region opens on a non-blank line when the next non-blank line is indented
 * deeper, and ends on the last non-blank line before a line indented no deeper than its first.
 * Blank lines, empty or spaces and tabs only, belong to no level, so trailing blank lines stay
 * outside the region. Indentation counts columns, a tab to the next stop. One pass over the lines.
 */
public final class IndentFoldSource implements FoldRegionSource {
  private final int tabWidth;

  /**
   * Creates the source.
   *
   * @param tabWidth columns per tab stop
   */
  public IndentFoldSource(int tabWidth) {
    this.tabWidth = Math.max(1, tabWidth);
  }

  @WorkerThread
  @Override
  public List<int[]> regions(TextSnapshot text, long revision) {
    int lines = text.lineCount();
    int[] ends = new int[lines];
    int[] stackLines = new int[16];
    int[] stackIndents = new int[16];
    int depth = 0;
    int lastShown = -1;
    int count = 0;
    LineView view = new LineView();
    for (int line = 0; line < lines; line++) {
      int indent = indent(text.lineView(line, view));
      if (indent < 0) {
        continue;
      }
      while (depth > 0 && stackIndents[depth - 1] >= indent) {
        depth--;
        if (lastShown > stackLines[depth]) {
          ends[stackLines[depth]] = lastShown;
          count++;
        }
      }
      if (depth == stackLines.length) {
        stackLines = Arrays.copyOf(stackLines, depth * 2);
        stackIndents = Arrays.copyOf(stackIndents, depth * 2);
      }
      stackLines[depth] = line;
      stackIndents[depth++] = indent;
      lastShown = line;
    }
    while (depth > 0) {
      depth--;
      if (lastShown > stackLines[depth]) {
        ends[stackLines[depth]] = lastShown;
        count++;
      }
    }
    List<int[]> out = new ArrayList<int[]>(count);
    for (int line = 0; line < lines; line++) {
      if (ends[line] > line) {
        out.add(new int[] {line, ends[line]});
      }
    }
    return out;
  }

  /** Returns a line's indentation in columns, or -1 for a blank line. */
  private int indent(LineView line) {
    int column = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\t') {
        column += tabWidth - column % tabWidth;
      } else if (c == ' ') {
        column++;
      } else {
        return column;
      }
    }
    return -1;
  }
}
