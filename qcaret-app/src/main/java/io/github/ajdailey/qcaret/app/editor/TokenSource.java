package io.github.ajdailey.qcaret.app.editor;

/** A document's syntax tokens by model line, for painting. Called on the UI thread. */
public interface TokenSource {
  /**
   * Returns a line's tokens. A line without tokens yet is requested and its view repainted once
   * they arrive.
   *
   * @param line the model line
   * @return {@code start, end, role} triples with offsets in the line and the {@link
   *     io.github.ajdailey.qcaret.app.theme.TokenRole} ordinal, ascending; null when not known
   */
  int[] tokens(int line);
}
