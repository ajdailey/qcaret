package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;

/**
 * Where a source match shows in a render. The render has no source offsets, so the rendered span is
 * set only when the match's text occurs once in its block's source and once in the block's rendered
 * text, outside a table.
 *
 * @param line the rendered line of the match's source line, as {@link
 *     RenderedMarkdown#renderedLine} maps it
 * @param start the match's rendered start, or -1 without a span
 * @param end the match's rendered end, or -1 without a span
 */
record PreviewMatch(int line, int start, int end) {
  /** The longest block source or rendered text searched for the span. */
  static final int MAX_CHARS = 100_000;

  /**
   * Maps a match.
   *
   * @param rendered the render of {@code source}
   * @param source the document text the match was found in
   * @param start the match's start
   * @param end the match's end; equal to {@code start} for a line without a match
   * @return the rendered line and span
   */
  static PreviewMatch of(RenderedMarkdown rendered, TextSnapshot source, int start, int end) {
    int sourceLine = source.lineAt(Math.min(start, source.length()));
    int line = rendered.renderedLine(sourceLine);
    if (end <= start || end > source.length() || inTable(rendered, line)) {
      return new PreviewMatch(line, -1, -1);
    }
    int blockLine = rendered.sourceLine(line);
    int next = line + 1;
    while (next < rendered.lineCount() && rendered.sourceLine(next) <= sourceLine) {
      next++;
    }
    int sourceStart = source.offsetOfLine(Math.min(blockLine, sourceLine));
    int sourceEnd =
        Math.max(
            end,
            next < rendered.lineCount()
                ? source.offsetOfLine(rendered.sourceLine(next))
                : source.length());
    int renderedStart = rendered.lineStart(line);
    int renderedEnd =
        next < rendered.lineCount() ? rendered.lineStart(next) : rendered.text().length();
    if (sourceEnd - sourceStart > MAX_CHARS || renderedEnd - renderedStart > MAX_CHARS) {
      return new PreviewMatch(line, -1, -1);
    }
    String text = source.subSequence(start, end).toString();
    String block = source.subSequence(sourceStart, sourceEnd).toString();
    String shown = rendered.text().substring(renderedStart, renderedEnd);
    int at = shown.indexOf(text);
    if (once(block, text) && at >= 0 && shown.indexOf(text, at + 1) < 0) {
      return new PreviewMatch(line, renderedStart + at, renderedStart + at + text.length());
    }
    return new PreviewMatch(line, -1, -1);
  }

  private static boolean once(String in, String text) {
    int at = in.indexOf(text);
    return at >= 0 && in.indexOf(text, at + 1) < 0;
  }

  private static boolean inTable(RenderedMarkdown rendered, int line) {
    for (MarkdownTable table : rendered.tables()) {
      if (line >= table.firstLine() && line < table.firstLine() + table.rowCount()) {
        return true;
      }
    }
    return false;
  }
}
