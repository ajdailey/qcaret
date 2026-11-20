package io.github.ajdailey.qcaret.core.markdown;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The rendered text and its attributes while {@link BlockWriter} and {@link InlineWriter} build
 * them. A line takes the list and quote depth current when it starts, and the item marker set
 * before it when it is the item's first line. Source lines are kept monotonic as they are added.
 */
final class MarkdownOutput {
  private final StringBuilder text = new StringBuilder();
  private final Ints lineStarts = new Ints();
  private final Ints lineBits = new Ints();
  private final Ints numbers = new Ints();
  private final Ints sourceLines = new Ints();
  private final List<StyleRun> runs = new ArrayList<StyleRun>();
  final List<CodeBlock> codeBlocks = new ArrayList<CodeBlock>();
  final List<MarkdownTable> tables = new ArrayList<MarkdownTable>();
  final List<MarkdownImage> images = new ArrayList<MarkdownImage>();
  final List<MarkdownLink> links = new ArrayList<MarkdownLink>();
  final List<MarkdownHeading> headings = new ArrayList<MarkdownHeading>();
  final List<MarkdownBlock> blocks = new ArrayList<MarkdownBlock>();
  final List<MarkdownAlert> alerts = new ArrayList<MarkdownAlert>();
  final List<MarkdownFootnote> footnotes = new ArrayList<MarkdownFootnote>();
  final List<MarkdownFootnote.Reference> footnoteReferences =
      new ArrayList<MarkdownFootnote.Reference>();
  CodeBlock frontMatter;

  int listDepth;
  int quoteDepth;
  private int markerBits;
  private int markerNumber;
  private boolean markerPending;
  private int lastSource;

  int length() {
    return text.length();
  }

  int lineCount() {
    return lineStarts.size();
  }

  void append(CharSequence chars) {
    text.append(chars);
  }

  void append(char c) {
    text.append(c);
  }

  String text(int start) {
    return text.substring(start);
  }

  /**
   * Starts a rendered line.
   *
   * @param sourceLine the source line it shows; an earlier one than the last line's is raised
   * @param flags {@link RenderedMarkdown#CODE}, {@link RenderedMarkdown#RULE} or 0
   */
  void startLine(int sourceLine, int flags) {
    if (lineStarts.size() > 0) {
      text.append('\n');
    }
    lineStarts.add(text.length());
    int bits =
        Math.min(listDepth, RenderedMarkdown.DEPTH_MASK)
            | Math.min(quoteDepth, RenderedMarkdown.DEPTH_MASK) << RenderedMarkdown.QUOTE_SHIFT
            | flags;
    int number = 0;
    if (markerPending) {
      bits |= markerBits;
      number = markerNumber;
      markerPending = false;
    }
    lineBits.add(bits);
    numbers.add(number);
    lastSource = Math.max(lastSource, sourceLine);
    sourceLines.add(lastSource);
  }

  /** Puts a marker on the next line started. */
  void setMarker(RenderedMarkdown.ListMarker marker, int number, RenderedMarkdown.TaskState task) {
    markerBits =
        marker.ordinal() << RenderedMarkdown.MARKER_SHIFT
            | task.ordinal() << RenderedMarkdown.TASK_SHIFT;
    markerNumber = marker == RenderedMarkdown.ListMarker.NUMBER ? number : 0;
    markerPending = true;
  }

  boolean markerPending() {
    return markerPending;
  }

  /** Adds a run over a non-empty range. */
  void run(int start, int end, InlineStyle style, int level) {
    if (end > start) {
      runs.add(new StyleRun(start, end, style, level));
    }
  }

  /** Records a heading on the current line. */
  void heading(int level, String text, String anchor, int sourceLine) {
    headings.add(new MarkdownHeading(level, text, anchor, sourceLine, lineStarts.size() - 1));
  }

  RenderedMarkdown build() {
    if (lineStarts.size() == 0) {
      startLine(0, 0);
    }
    List<StyleRun> sorted = new ArrayList<StyleRun>(runs);
    sorted.sort(
        new Comparator<StyleRun>() {
          @Override
          public int compare(StyleRun a, StyleRun b) {
            return Integer.compare(a.start(), b.start());
          }
        });
    return new RenderedMarkdown(
        text.toString(),
        lineStarts.toArray(),
        lineBits.toArray(),
        numbers.toArray(),
        sourceLines.toArray(),
        sorted,
        this);
  }
}
