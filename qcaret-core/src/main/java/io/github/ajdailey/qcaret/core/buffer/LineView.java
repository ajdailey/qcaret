package io.github.ajdailey.qcaret.core.buffer;

import java.util.Objects;

/**
 * One line's text without its line break, read in place from the leaves of one text version. {@link
 * TextContent#lineView} repositions a view without copying chars, so one view serves any number of
 * lines; {@link #toString} and {@link #subSequence} copy. A view is for one thread.
 */
public final class LineView implements CharSequence {
  private final int[] at = new int[1];
  private LeafText text;
  private int line;
  private int start;
  private int length;
  private String leaf;
  private int leafStart;

  /** Creates a view of no line; {@link TextContent#lineView} positions it. */
  public LineView() {}

  void set(LeafText text, int line, int start, int length, String leaf, int leafStart) {
    this.text = text;
    this.line = line;
    this.start = start;
    this.length = length;
    this.leaf = leaf;
    this.leafStart = leafStart;
  }

  int[] scratch() {
    return at;
  }

  String leaf() {
    return leaf;
  }

  int leafStart() {
    return leafStart;
  }

  /**
   * Returns the line index.
   *
   * @return the line the view was last positioned on
   */
  public int line() {
    return line;
  }

  /**
   * Returns the offset of the line's first char.
   *
   * @return the start offset
   */
  public int start() {
    return start;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public char charAt(int index) {
    Objects.checkIndex(index, length);
    int offset = start + index;
    int k = offset - leafStart;
    if (leaf == null || k < 0 || k >= leaf.length()) {
      leaf = positioned().leaf(offset, at);
      leafStart = at[0];
      k = offset - leafStart;
    }
    return leaf.charAt(k);
  }

  @Override
  public CharSequence subSequence(int from, int to) {
    Objects.checkFromToIndex(from, to, length);
    return positioned().subSequence(start + from, start + to);
  }

  private LeafText positioned() {
    if (text == null) {
      throw new IllegalStateException("line view not positioned");
    }
    return text;
  }

  @Override
  public String toString() {
    return text == null ? "" : text.subSequence(start, start + length).toString();
  }
}
