package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.syntax.FoldRegions;
import java.util.List;

/**
 * The regions of two sources merged by {@link FoldRegions#merge}; null while either source has not
 * computed the revision's regions.
 */
public final class MergedFoldSource implements FoldRegionSource {
  private final FoldRegionSource first;
  private final FoldRegionSource second;

  /**
   * Creates the source.
   *
   * @param first asked first; its null skips the second
   * @param second the other source
   */
  public MergedFoldSource(FoldRegionSource first, FoldRegionSource second) {
    this.first = first;
    this.second = second;
  }

  @WorkerThread
  @Override
  public List<int[]> regions(TextSnapshot text, long revision) {
    List<int[]> a = first.regions(text, revision);
    if (a == null) {
      return null;
    }
    List<int[]> b = second.regions(text, revision);
    return b == null ? null : FoldRegions.merge(a, b);
  }
}
