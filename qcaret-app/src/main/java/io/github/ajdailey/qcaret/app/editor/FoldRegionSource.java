package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import java.util.List;

/**
 * Computes a text's fold regions. Called on a worker with a snapshot; the caller installs the
 * result with {@link DisplayProjection#setFoldRegions} only while the document is still at {@code
 * revision}.
 */
public interface FoldRegionSource {
  /**
   * Returns the text's fold regions.
   *
   * @param text the snapshot
   * @param revision the document revision of the snapshot
   * @return {@code {startLine, endLine}} pairs, ascending by start line, each end above its start;
   *     empty when the regions of that revision are not computed
   */
  List<int[]> regions(TextSnapshot text, long revision);
}
