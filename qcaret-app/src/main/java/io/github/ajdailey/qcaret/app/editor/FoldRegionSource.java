package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import java.util.List;

/** The fold regions of a document at a revision, for the fold layer. Called on the UI thread. */
public interface FoldRegionSource {
  /**
   * Returns the fold regions of a text.
   *
   * @param text the document's text at {@code revision}
   * @param revision the document revision
   * @return {@code {startLine, endLine}} pairs, ascending by start line; empty when the regions of
   *     that revision are not computed
   */
  List<int[]> regions(TextSnapshot text, long revision);
}
