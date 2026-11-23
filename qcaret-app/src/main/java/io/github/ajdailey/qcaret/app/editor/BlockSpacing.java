package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pixels a Basic preview pane adds to the separator lines around headings and code blocks. A
 * separator line is an empty rendered line between two blocks; a block with no separator beside it
 * gets no space on that side.
 *
 * <ul>
 *   <li>Above a heading: {@value #HEADING_ABOVE} px at the bottom of the separator before it.
 *   <li>Below a level 1 or 2 heading: {@value #RULE_GAP} px at the top of the separator after it,
 *       between the heading and its rule.
 *   <li>Around a code block: {@value #CODE_PADDING} px at the bottom of the separator before it and
 *       at the top of the separator after it, inside the block's box.
 * </ul>
 */
public final class BlockSpacing {
  /** Pixels added above a heading. */
  public static final int HEADING_ABOVE = 4;

  /** Pixels between a level 1 or 2 heading's line and its rule. */
  public static final int RULE_GAP = 6;

  /** Pixels between a code block's box and its text, above and below. */
  public static final int CODE_PADDING = 8;

  private static final BlockSpacing NONE = new BlockSpacing(new int[0], new int[0], new int[0]);

  private final int[] lines;
  private final int[] above;
  private final int[] below;

  private BlockSpacing(int[] lines, int[] above, int[] below) {
    this.lines = lines;
    this.above = above;
    this.below = below;
  }

  /**
   * Returns spacing that adds nothing.
   *
   * @return the empty spacing
   */
  public static BlockSpacing none() {
    return NONE;
  }

  /**
   * Computes a render's spacing.
   *
   * @param r the render
   * @return the separator lines that take space and their pixels
   */
  public static BlockSpacing of(RenderedMarkdown r) {
    Map<Integer, int[]> space = new TreeMap<Integer, int[]>();
    for (MarkdownHeading heading : r.headings()) {
      add(r, space, heading.line() - 1, 0, HEADING_ABOVE);
      if (heading.level() <= 2) {
        add(r, space, heading.line() + 1, RULE_GAP, 0);
      }
    }
    for (CodeBlock code : r.codeBlocks()) {
      add(r, space, code.firstLine() - 1, 0, CODE_PADDING);
      add(r, space, code.firstLine() + code.lineCount(), CODE_PADDING, 0);
    }
    int[] lines = new int[space.size()];
    int[] above = new int[lines.length];
    int[] below = new int[lines.length];
    int i = 0;
    for (Map.Entry<Integer, int[]> e : space.entrySet()) {
      lines[i] = e.getKey();
      above[i] = e.getValue()[0];
      below[i] = e.getValue()[1];
      i++;
    }
    return new BlockSpacing(lines, above, below);
  }

  private static void add(
      RenderedMarkdown r, Map<Integer, int[]> space, int line, int top, int bottom) {
    if (!separator(r, line)) {
      return;
    }
    int[] pixels = space.get(line);
    if (pixels == null) {
      pixels = new int[2];
      space.put(line, pixels);
    }
    pixels[0] = Math.max(pixels[0], top);
    pixels[1] = Math.max(pixels[1], bottom);
  }

  /** Reports whether a line is an empty line outside every block. */
  static boolean separator(RenderedMarkdown r, int line) {
    if (line < 0 || line >= r.lineCount()) {
      return false;
    }
    int end = line + 1 < r.lineCount() ? r.lineStart(line + 1) - 1 : r.text().length();
    if (end != r.lineStart(line)) {
      return false;
    }
    List<MarkdownBlock> blocks = r.blocks();
    int lo = 0;
    int hi = blocks.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (blocks.get(mid).firstLine() <= line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo == 0) {
      return true;
    }
    MarkdownBlock block = blocks.get(lo - 1);
    return line >= block.firstLine() + block.lineCount();
  }

  /**
   * Returns the separator lines that take space.
   *
   * @return ascending rendered lines. Shared: do not modify
   */
  public int[] lines() {
    return lines;
  }

  /**
   * Returns the pixels added at the top of a line.
   *
   * @param line a rendered line
   * @return 0 for a line that takes no space
   */
  public int above(int line) {
    int i = Arrays.binarySearch(lines, line);
    return i < 0 ? 0 : above[i];
  }

  /**
   * Returns the pixels added at the bottom of a line.
   *
   * @param line a rendered line
   * @return 0 for a line that takes no space
   */
  public int below(int line) {
    int i = Arrays.binarySearch(lines, line);
    return i < 0 ? 0 : below[i];
  }
}
