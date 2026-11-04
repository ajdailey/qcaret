package io.github.ajdailey.qcaret.core.buffer;

/**
 * Text stored as immutable leaves, read leaf by leaf by {@link TextCursor} and {@link LineView}.
 */
interface LeafText extends TextContent {
  /**
   * Returns the leaf holding the char at {@code index}.
   *
   * @param index an offset from 0 to {@code length() - 1}
   * @param start receives the leaf's start offset in {@code start[0]}
   * @return the leaf
   */
  String leaf(int index, int[] start);
}
