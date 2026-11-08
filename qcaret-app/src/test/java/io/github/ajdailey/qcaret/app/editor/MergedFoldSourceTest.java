package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Merging two fold sources, and a revision one of them has not computed. */
class MergedFoldSourceTest {
  private static FoldRegionSource source(final List<int[]> regions) {
    return new FoldRegionSource() {
      @Override
      public List<int[]> regions(TextSnapshot text, long revision) {
        return regions;
      }
    };
  }

  @Test
  void mergesOrReturnsNullWhileEitherSourceHasNoRegions() {
    FoldRegionSource some = source(List.of(new int[] {0, 2}, new int[] {4, 6}));
    FoldRegionSource more = source(List.of(new int[] {0, 5}));
    List<int[]> merged = new MergedFoldSource(some, more).regions(null, 1);
    assertEquals(2, merged.size());
    assertEquals(5, merged.get(0)[1]);
    assertEquals(4, merged.get(1)[0]);
    assertNull(new MergedFoldSource(source(null), more).regions(null, 1));
    assertNull(new MergedFoldSource(some, source(null)).regions(null, 1));
  }
}
