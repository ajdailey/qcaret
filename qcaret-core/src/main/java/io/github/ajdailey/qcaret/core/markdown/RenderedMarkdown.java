package io.github.ajdailey.qcaret.core.markdown;

import java.util.List;

/**
 * One render of a Markdown text: plain text in lines, the style runs over it, each line's block
 * attributes, the code blocks, tables, images, links and headings, and the map between rendered and
 * source lines. Immutable and thread-safe; {@link MarkdownRenderer} builds it.
 *
 * <p>Rendered lines end with {@code \n}; the last has no break. Offsets are UTF-16 indexes into
 * {@link #text}. A list bullet, an item number and a task checkbox are line attributes, not text. A
 * rule is an empty line with {@link #rule}. Blocks are separated by one empty line that carries the
 * enclosing quote and list depth; items of a tight list are not. A control char shows as its {@code
 * ControlChars.visibleKeepingBreaks} text, such as {@code <1B>}; heading text keeps the char.
 */
public final class RenderedMarkdown {
  /** A list item's marker on its first line. */
  public enum ListMarker {
    /** Not the first line of an item. */
    NONE,
    /** A bullet list item. */
    BULLET,
    /** An ordered list item; {@link #number} holds its number. */
    NUMBER
  }

  /** A task list item's checkbox on its first line. */
  public enum TaskState {
    /** Not a task item, or not its first line. */
    NONE,
    /** {@code [ ]}. */
    OPEN,
    /** {@code [x]}. */
    DONE
  }

  static final int DEPTH_MASK = 0xff;
  static final int QUOTE_SHIFT = 8;
  static final int MARKER_SHIFT = 16;
  static final int TASK_SHIFT = 18;
  static final int CODE = 1 << 20;
  static final int RULE = 1 << 21;

  private static final ListMarker[] MARKERS = ListMarker.values();
  private static final TaskState[] TASKS = TaskState.values();

  private final String text;
  private final int[] lineStarts;
  private final int[] lineBits;
  private final int[] numbers;
  private final int[] sourceLines;
  private final List<StyleRun> runs;
  private final List<CodeBlock> codeBlocks;
  private final List<MarkdownTable> tables;
  private final List<MarkdownImage> images;
  private final List<MarkdownLink> links;
  private final List<MarkdownHeading> headings;
  private final List<MarkdownAlert> alerts;
  private final List<MarkdownBlock> blocks;
  private final List<MarkdownFootnote> footnotes;
  private final List<MarkdownFootnote.Reference> footnoteReferences;
  private final CodeBlock frontMatter;

  RenderedMarkdown(
      String text,
      int[] lineStarts,
      int[] lineBits,
      int[] numbers,
      int[] sourceLines,
      List<StyleRun> runs,
      MarkdownOutput parts) {
    this.text = text;
    this.lineStarts = lineStarts;
    this.lineBits = lineBits;
    this.numbers = numbers;
    this.sourceLines = sourceLines;
    this.runs = List.copyOf(runs);
    this.codeBlocks = List.copyOf(parts.codeBlocks);
    this.tables = List.copyOf(parts.tables);
    this.images = List.copyOf(parts.images);
    this.links = List.copyOf(parts.links);
    this.headings = List.copyOf(parts.headings);
    this.alerts = List.copyOf(parts.alerts);
    this.blocks = List.copyOf(parts.blocks);
    this.footnotes = List.copyOf(parts.footnotes);
    this.footnoteReferences = List.copyOf(parts.footnoteReferences);
    this.frontMatter = parts.frontMatter;
  }

  /**
   * Returns the rendered text.
   *
   * @return lines separated by {@code \n}
   */
  public String text() {
    return text;
  }

  /**
   * Counts the rendered lines.
   *
   * @return at least 1
   */
  public int lineCount() {
    return lineStarts.length;
  }

  /**
   * Returns the offset a rendered line starts at.
   *
   * @param line from 0 to {@link #lineCount} - 1
   * @return the offset
   */
  public int lineStart(int line) {
    return lineStarts[line];
  }

  /**
   * Returns the style runs.
   *
   * @return ordered by start, then by the order the renderer opened them
   */
  public List<StyleRun> runs() {
    return runs;
  }

  /**
   * Returns a line's list nesting.
   *
   * @param line a rendered line
   * @return 0 outside a list, 1 in a top-level list
   */
  public int listDepth(int line) {
    return lineBits[line] & DEPTH_MASK;
  }

  /**
   * Returns a line's block quote nesting.
   *
   * @param line a rendered line
   * @return 0 outside a quote
   */
  public int quoteDepth(int line) {
    return (lineBits[line] >>> QUOTE_SHIFT) & DEPTH_MASK;
  }

  /**
   * Returns the list marker a line starts with.
   *
   * @param line a rendered line
   * @return {@link ListMarker#NONE} except on an item's first line
   */
  public ListMarker marker(int line) {
    return MARKERS[(lineBits[line] >>> MARKER_SHIFT) & 3];
  }

  /**
   * Returns an ordered item's number.
   *
   * @param line a rendered line whose {@link #marker} is {@link ListMarker#NUMBER}
   * @return the number; 0 on any other line
   */
  public int number(int line) {
    return numbers[line];
  }

  /**
   * Returns a task item's checkbox.
   *
   * @param line a rendered line
   * @return {@link TaskState#NONE} except on a task item's first line
   */
  public TaskState task(int line) {
    return TASKS[(lineBits[line] >>> TASK_SHIFT) & 3];
  }

  /**
   * Reports whether a line belongs to a code block and is shaded.
   *
   * @param line a rendered line
   * @return true for a code line
   */
  public boolean code(int line) {
    return (lineBits[line] & CODE) != 0;
  }

  /**
   * Reports whether a line is a thematic break.
   *
   * @param line a rendered line
   * @return true for an empty line drawn as a rule
   */
  public boolean rule(int line) {
    return (lineBits[line] & RULE) != 0;
  }

  /**
   * Returns the code blocks.
   *
   * @return in rendered order
   */
  public List<CodeBlock> codeBlocks() {
    return codeBlocks;
  }

  /**
   * Returns the tables.
   *
   * @return in rendered order
   */
  public List<MarkdownTable> tables() {
    return tables;
  }

  /**
   * Returns the images.
   *
   * @return in rendered order
   */
  public List<MarkdownImage> images() {
    return images;
  }

  /**
   * Returns the links.
   *
   * @return in rendered order
   */
  public List<MarkdownLink> links() {
    return links;
  }

  /**
   * Returns the headings.
   *
   * @return in rendered order
   */
  public List<MarkdownHeading> headings() {
    return headings;
  }

  /**
   * Returns the leaf blocks.
   *
   * @return in rendered order
   */
  public List<MarkdownBlock> blocks() {
    return blocks;
  }

  /**
   * Returns the alerts.
   *
   * @return in rendered order
   */
  public List<MarkdownAlert> alerts() {
    return alerts;
  }

  /**
   * Returns the footnote definitions.
   *
   * @return by number
   */
  public List<MarkdownFootnote> footnotes() {
    return footnotes;
  }

  /**
   * Returns the footnote references.
   *
   * @return in rendered order
   */
  public List<MarkdownFootnote.Reference> footnoteReferences() {
    return footnoteReferences;
  }

  /**
   * Returns the YAML front matter, rendered as a code block with the {@code yaml} grammar. It is
   * also in {@link #codeBlocks}.
   *
   * @return the block, or null when the document has none
   */
  public CodeBlock frontMatter() {
    return frontMatter;
  }

  /**
   * Finds the heading an in-document link names.
   *
   * @param anchor a {@link LinkTarget#anchor}
   * @return the heading's rendered line, or -1
   */
  public int headingLine(String anchor) {
    for (MarkdownHeading h : headings) {
      if (h.anchor().equals(anchor)) {
        return h.line();
      }
    }
    return -1;
  }

  /**
   * Maps a rendered line to the source line it came from. The map is monotonic: a later rendered
   * line never maps to an earlier source line.
   *
   * @param line a rendered line; clamped to the lines
   * @return the zero-based source line
   */
  public int sourceLine(int line) {
    return sourceLines[Math.max(0, Math.min(line, sourceLines.length - 1))];
  }

  /**
   * Maps a source line to the first rendered line showing it, or, for a source line with no output
   * (a blank line, a link reference definition, a fence), the last rendered line before it.
   *
   * @param line a zero-based source line
   * @return the rendered line; 0 for a line before the first output
   */
  public int renderedLine(int line) {
    int lo = 0;
    int hi = sourceLines.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (sourceLines[mid] < line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo < sourceLines.length && sourceLines[lo] == line) {
      return lo;
    }
    return Math.max(0, lo - 1);
  }
}
