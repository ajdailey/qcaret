package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;

/**
 * Copies a range of display text in one pass over the model text: the shown lines between two
 * collapsed spans are one chunk read, with {@code \n} inserted at each virtual break, and a span is
 * skipped by the model offsets its {@link FoldLayer} position gives. A copy makes one line lookup
 * in the text, at its start, however many rows and spans it covers. One instance serves one {@link
 * DisplayProjection#textRange} call.
 */
final class DisplayText implements ChunkVisitor {
  private final TextSnapshot text;
  private final RowIndex index;
  private final FoldLayer folds;
  private final StringBuilder out;
  private int line;
  private int lineStart;
  private int entry;
  private int nextBreak;
  private int at;
  private boolean cr;
  private int lookups;

  /** Creates a copy of at most {@code length} chars over a projection's text, rows and folds. */
  DisplayText(TextSnapshot text, RowIndex index, FoldLayer folds, int length) {
    this.text = text;
    this.index = index;
    this.folds = folds;
    this.out = new StringBuilder(length);
  }

  /**
   * Returns {@code length} display chars from {@code skip} chars past the start of row {@code sub}
   * of a shown model line.
   *
   * @throws IndexOutOfBoundsException when the range passes the display text's end
   */
  String read(int startLine, int sub, int skip, int length) {
    int need = skip + length;
    line = startLine;
    lineStart = text.offsetOfLine(line);
    lookups++;
    entry = index.lowerBound(line);
    nextBreak = sub;
    int pos = sub == 0 ? lineStart : lineStart + index.breakAt(entry, sub - 1);
    int k = folds.firstSpanFrom(line);
    while (out.length() < need) {
      int runEnd = text.length();
      int last = -1;
      int lastEnd = 0;
      if (k < folds.spans()) {
        // Model offsets from the span's display position and size: the head's end, where the
        // hidden text starts, and the last hidden line's end, where its delimiter starts.
        int hidden = folds.hiddenStart(k);
        runEnd = hidden - index.breaksBefore(folds.head(k) + 1);
        last = folds.last(k);
        lastEnd =
            hidden + folds.charsBefore(k + 1) - folds.charsBefore(k) - index.breaksBefore(last + 1);
      }
      int end = Math.min(runEnd, pos + need - out.length());
      if (pos < end) {
        at = pos;
        text.readChunks(pos, end, this);
      }
      if (out.length() >= need || last < 0) {
        break;
      }
      // Continue at the last hidden line's delimiter, past that line's breaks.
      pos = lastEnd;
      line = last;
      lineStart = pos;
      entry = index.lowerBound(line);
      nextBreak =
          entry < index.entries() && index.line(entry) == line ? index.breakCount(entry) : 0;
      cr = false;
      k++;
    }
    if (out.length() < need) {
      throw new IndexOutOfBoundsException("display range past the text's end");
    }
    return out.substring(skip, need);
  }

  /** Returns the line lookups {@link #read} made in the text. */
  int lookups() {
    return lookups;
  }

  /** Appends the chunk in runs up to each line break char or virtual break. */
  @Override
  public void chunk(CharSequence chunk, int start, int end) {
    int i = start;
    while (i < end) {
      if (cr) {
        cr = false;
        if (chunk.charAt(i) != '\n') {
          nextLine(at);
        }
      }
      int stop = end;
      if (entry < index.entries()
          && index.line(entry) == line
          && nextBreak < index.breakCount(entry)) {
        int virtual = i + lineStart + index.breakAt(entry, nextBreak) - at;
        if (virtual == i) {
          out.append('\n');
          nextBreak++;
          continue;
        }
        stop = Math.min(stop, virtual);
      }
      int breakChar = breakChar(chunk, i, stop);
      int to = breakChar < 0 ? stop : breakChar + 1;
      out.append(chunk, i, to);
      at += to - i;
      i = to;
      if (breakChar >= 0) {
        if (chunk.charAt(breakChar) == '\n') {
          nextLine(at);
        } else {
          cr = true;
        }
      }
    }
  }

  /** Returns the index of the first CR or LF in {@code chunk[from, to)}, or -1. */
  private static int breakChar(CharSequence chunk, int from, int to) {
    if (chunk instanceof String s) {
      int lf = s.indexOf('\n', from, to);
      int cr = s.indexOf('\r', from, lf < 0 ? to : lf);
      return cr >= 0 ? cr : lf;
    }
    for (int i = from; i < to; i++) {
      char c = chunk.charAt(i);
      if (c == '\n' || c == '\r') {
        return i;
      }
    }
    return -1;
  }

  /** Moves to the next model line, which starts at {@code start}. */
  private void nextLine(int start) {
    line++;
    lineStart = start;
    nextBreak = 0;
    while (entry < index.entries() && index.line(entry) < line) {
      entry++;
    }
  }
}
