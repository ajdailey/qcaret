package io.github.ajdailey.qcaret.core.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The fold regions of a {@link SyntaxEngine} whose frontier reached the end: computed from every
 * line's packed fold counts, region marker counts and start state, and merged by {@link
 * FoldRegions#merge}. Reads the engine's arrays without copying them, so it runs on the engine's
 * thread before the next edit.
 */
final class FoldRegionPass {
  private static final int OPEN_MASK = SyntaxEngine.OPEN_MASK;
  private static final int CLOSE_SHIFT = SyntaxEngine.CLOSE_SHIFT;
  private static final int OVERLONG = SyntaxEngine.OVERLONG;
  private static final int LEVEL_MASK = SyntaxEngine.LEVEL_MASK;
  private static final int PREVIOUS = SyntaxEngine.PREVIOUS;
  private static final int BLANK = SyntaxEngine.BLANK;

  private final Tokenizer tokenizer;
  private final Grammar.FoldMode mode;
  private final int[] states;
  private final int[] folds;
  private final int[] marks;
  private final int lineCount;

  /**
   * Creates the pass over the engine's arrays.
   *
   * @param tokenizer reads the folding span of a state
   * @param mode the grammar's fold mode
   * @param states each line's start state, and the state after the last line
   * @param folds each line's packed fold counts
   * @param marks each line's packed region marker counts, or null
   * @param lineCount the lines the arrays hold
   */
  FoldRegionPass(
      Tokenizer tokenizer,
      Grammar.FoldMode mode,
      int[] states,
      int[] folds,
      int[] marks,
      int lineCount) {
    this.tokenizer = tokenizer;
    this.mode = mode;
    this.states = states;
    this.folds = folds;
    this.marks = marks;
    this.lineCount = lineCount;
  }

  /** Computes the regions of every source and merges them. */
  List<int[]> regions() {
    List<int[]> main =
        switch (mode) {
          case BRACKETS, MARKERS -> pairs(folds);
          case SECTIONS -> sections();
          default -> List.of();
        };
    List<int[]> out = FoldRegions.merge(main, spans());
    return marks == null ? out : FoldRegions.merge(out, pairs(marks));
  }

  /**
   * Pairs packed open and close counts into regions: one per start line, the largest, spanning at
   * least two lines.
   */
  private List<int[]> pairs(int[] counts) {
    int[] open = new int[64];
    int depth = 0;
    int[] endOf = new int[lineCount];
    Arrays.fill(endOf, -1);
    for (int line = 0; line < lineCount; line++) {
      int f = counts[line];
      if ((f & OVERLONG) != 0) {
        continue;
      }
      int closes = (f >>> CLOSE_SHIFT) & OPEN_MASK;
      for (int j = 0; j < closes && depth > 0; j++) {
        int start = open[--depth];
        if (line > start && line > endOf[start]) {
          endOf[start] = line;
        }
      }
      int opens = f & OPEN_MASK;
      for (int j = 0; j < opens; j++) {
        if (depth == open.length) {
          open = Arrays.copyOf(open, depth * 2);
        }
        open[depth++] = line;
      }
    }
    return ascending(endOf);
  }

  /**
   * Returns the folding spans covering two or more lines: from the line ending inside a span to the
   * line ending outside it. A span left open runs to the last line.
   */
  private List<int[]> spans() {
    List<int[]> out = new ArrayList<int[]>();
    int openLine = -1;
    int openKey = 0;
    for (int line = 0; line < lineCount; line++) {
      int key = tokenizer.foldSpan(states[line + 1]);
      if (openLine >= 0 && key != openKey) {
        out.add(new int[] {openLine, line});
        openLine = -1;
      }
      if (key != 0 && openLine < 0) {
        openLine = line;
        openKey = key;
      }
    }
    if (openLine >= 0 && openLine < lineCount - 1) {
      out.add(new int[] {openLine, lineCount - 1});
    }
    return out;
  }

  /**
   * Returns the sections: each header runs to the line before the next header of the same or a
   * higher level, or to the last non-blank line. A previous-line rule makes the line above it the
   * header when that line is not blank, not a header and not inside a span.
   */
  private List<int[]> sections() {
    byte[] level = new byte[lineCount];
    int last = -1;
    for (int line = 0; line < lineCount; line++) {
      int f = folds[line];
      if ((f & OVERLONG) != 0 || (f & BLANK) == 0) {
        last = line;
      }
      if ((f & OVERLONG) != 0 || (f & LEVEL_MASK) == 0) {
        continue;
      }
      int header = line;
      if ((f & PREVIOUS) != 0) {
        header = line - 1;
        if (header < 0
            || (folds[header] & (BLANK | LEVEL_MASK | OVERLONG)) != 0
            || tokenizer.inSpan(states[header])) {
          continue;
        }
      }
      level[header] = (byte) (f & LEVEL_MASK);
    }
    int[] endOf = new int[lineCount];
    Arrays.fill(endOf, -1);
    int[] open = new int[LEVEL_MASK + 1];
    int depth = 0;
    for (int line = 0; line < lineCount; line++) {
      if (level[line] == 0) {
        continue;
      }
      while (depth > 0 && level[open[depth - 1]] >= level[line]) {
        int start = open[--depth];
        endOf[start] = line - 1;
      }
      if (depth == open.length) {
        open = Arrays.copyOf(open, depth * 2);
      }
      open[depth++] = line;
    }
    while (depth > 0) {
      int start = open[--depth];
      endOf[start] = last;
    }
    return ascending(endOf);
  }

  /** Returns {@code {line, endOf[line]}} for each line whose end lies below it, ascending. */
  private static List<int[]> ascending(int[] endOf) {
    List<int[]> out = new ArrayList<int[]>();
    for (int line = 0; line < endOf.length; line++) {
      if (endOf[line] > line) {
        out.add(new int[] {line, endOf[line]});
      }
    }
    return out;
  }
}
